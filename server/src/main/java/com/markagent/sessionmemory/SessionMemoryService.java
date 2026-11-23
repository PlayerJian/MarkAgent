package com.markagent.sessionmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.config.MarkAgentProperties;
import com.markagent.store.AtomicJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话滚动记忆（session memory）：任务终止态（自然断点）把「目标/上下文/进度/决策」抽取进
 * data/sessions/{userId}/{sessionId}/session-memory.md，下一次任务经 PromptAssembler 注入首条消息。
 * <ul>
 *   <li>阈值触发：首次 usedTokens &gt;= initTokens，或距上次记录增量 &gt;= deltaTokens，或工具调用满 callInterval 次</li>
 *   <li>侧车 session-memory-state.json 持久化 lastRecordedTokens/toolCallsSince/lastUpdatedAt</li>
 *   <li>LLM 裸调用（不带工具），输出整文件覆盖式写入；失败不动旧文件、不更新侧车，下次再试</li>
 *   <li>记录在虚拟线程后台执行，不阻塞任务终止广播</li>
 * </ul>
 */
@Component
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /** 无用户上下文时的占位用户（与 PromptAssembler / AgentChatService 归一规则一致） */
    private static final String LOCAL_USER = "local-user";
    private static final String MEMORY_FILE = "session-memory.md";
    private static final String STATE_FILE = "session-memory-state.json";
    /** goal / 最终输出截断保护（字符数） */
    private static final int INPUT_TRUNCATE = 4000;

    private final DeepSeekChatModel chatModel;
    private final MarkAgentProperties properties;
    private final ObjectMapper mapper;
    private final Path sessionsRoot;
    /** (userId,sessionId) → 自上次成功记录以来的工具调用数（进程内计数，评估时与侧车合并） */
    private final Map<String, AtomicInteger> toolCallsSince = new ConcurrentHashMap<>();

    public SessionMemoryService(DeepSeekChatModel chatModel, MarkAgentProperties properties, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.mapper = mapper;
        String root = properties.storage() == null ? "./data" : properties.storage().root();
        this.sessionsRoot = Path.of(root).toAbsolutePath().normalize().resolve("sessions");
    }

    /** 侧车状态（session-memory-state.json 的 schema） */
    public record SidecarState(long lastRecordedTokens, int toolCallsSince, String lastUpdatedAt) {
    }

    /**
     * 触发判定（纯静态，供单测）：工具调用间隔 / 首次起始阈值（lastRecorded&lt;0 表示侧车不存在）/
     * 增量阈值，满足任一即记录。
     */
    public static boolean shouldRecord(long usedTokens, long lastRecorded, int toolCallsSince,
            long initTokens, long deltaTokens, int callInterval) {
        if (toolCallsSince >= callInterval) {
            return true;
        }
        if (lastRecorded < 0) {
            // 首次（侧车不存在）：起始阈值 initTokens
            return usedTokens >= initTokens;
        }
        // 增量：距上次记录增长 deltaTokens
        return usedTokens - lastRecorded >= deltaTokens;
    }

    /** 工具调用计数（AgentChatService 在 ToolResultEndEvent 分支调用，SUCCESS 与失败都算一次） */
    public void onToolCall(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        toolCallsSince.computeIfAbsent(key(userId, sessionId), k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 任务终止态评估：满足阈值则虚拟线程后台执行记录（LLM 摘要 + 整文件覆盖写入）。
     * 任何失败只记日志，不影响任务终止流程。
     */
    public void evaluateAndRecord(String userId, String sessionId, String goal, String finalOutput, long usedTokens) {
        try {
            MarkAgentProperties.SessionMemory cfg = config();
            if (!cfg.enabled()) {
                return;
            }
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            SidecarState sidecar = readSidecar(userId, sessionId);
            int totalCalls = sidecar.toolCallsSince() + inMemoryCalls(userId, sessionId);
            if (!shouldRecord(usedTokens, sidecar.lastRecordedTokens(), totalCalls,
                    cfg.initTokens(), cfg.deltaTokens(), cfg.toolCallInterval())) {
                return;
            }
            Thread.ofVirtual().name("session-memory-" + sessionId)
                    .start(() -> record(userId, sessionId, goal, finalOutput, usedTokens));
        } catch (Exception e) {
            log.warn("会话记忆评估失败 {}/{}: {}", userId, sessionId, e.getMessage());
        }
    }

    /** 后台记录：读旧记忆 → 裸 LLM 摘要 → 覆盖写 session-memory.md → 更新侧车 */
    private void record(String userId, String sessionId, String goal, String finalOutput, long usedTokens) {
        try {
            String existing = readMemory(userId, sessionId);
            String summary = chatModel.call(buildPrompt(existing, goal, finalOutput));
            if (summary == null || summary.isBlank()) {
                log.warn("会话记忆摘要为空 {}/{}，保持旧文件", userId, sessionId);
                return;
            }
            writeMemory(userId, sessionId, summary.strip());
            writeSidecar(userId, sessionId, new SidecarState(usedTokens, 0, Instant.now().toString()));
            toolCallsSince.remove(key(userId, sessionId));
            log.info("会话记忆已更新 {}/{} usedTokens={}", userId, sessionId, usedTokens);
        } catch (Exception e) {
            // LLM 失败/超时：不动旧文件、不更新侧车，下次再试
            log.warn("会话记忆记录失败 {}/{}: {}", userId, sessionId, e.getMessage());
        }
    }

    /** 组装记录提示词：明确禁止工具调用，输出固定四段格式的整文件内容 */
    private static String buildPrompt(String existing, String goal, String finalOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是会话记忆维护助手。不要调用任何工具，只输出结构化中文摘要。\n\n")
                .append("请根据现有会话记忆与本任务信息，输出更新后的完整会话记忆文件（整文件覆盖）。\n")
                .append("输出格式固定为四段，缺内容的段落省略：\n")
                .append("# 会话记忆\n")
                .append("## 用户目标\n（最近任务目标，多条按时间倒序，最多 5 条）\n")
                .append("## 关键上下文\n（项目事实、用户约束、重要结论，最多 8 条要点）\n")
                .append("## 进度与结论\n（最近任务做了什么、结论是什么，最多 5 条）\n")
                .append("## 决策与约定\n（用户拍板的技术决策、约定，最多 5 条）\n\n");
        if (existing != null && !existing.isBlank()) {
            sb.append("## 现有会话记忆\n").append(existing.stripTrailing()).append("\n\n");
        }
        sb.append("## 本任务目标\n").append(truncate(goal == null ? "" : goal, INPUT_TRUNCATE)).append("\n\n");
        sb.append("## 本任务最终输出\n")
                .append(truncate(finalOutput == null ? "" : finalOutput, INPUT_TRUNCATE)).append("\n\n");
        sb.append("请只输出更新后的会话记忆文件内容，不要附加任何解释。");
        return sb.toString();
    }

    // ==================== 文件读写 ====================

    private Path dir(String userId, String sessionId) {
        return sessionsRoot.resolve(userId == null ? LOCAL_USER : userId).resolve(sessionId);
    }

    /** 读现有 session-memory.md（不存在返回 null） */
    private String readMemory(String userId, String sessionId) {
        try {
            return new AtomicJsonFile(dir(userId, sessionId).resolve(MEMORY_FILE)).readString();
        } catch (IOException e) {
            log.warn("读取会话记忆失败 {}/{}: {}", userId, sessionId, e.getMessage());
            return null;
        }
    }

    private void writeMemory(String userId, String sessionId, String content) throws IOException {
        new AtomicJsonFile(dir(userId, sessionId).resolve(MEMORY_FILE)).writeString(content);
    }

    /** 读侧车（不存在返回首次哨兵：lastRecordedTokens=-1） */
    private SidecarState readSidecar(String userId, String sessionId) {
        try {
            SidecarState state = new AtomicJsonFile(dir(userId, sessionId).resolve(STATE_FILE))
                    .readAs(mapper, SidecarState.class);
            return state == null ? new SidecarState(-1, 0, null) : state;
        } catch (IOException e) {
            log.warn("读取会话记忆侧车失败 {}/{}: {}", userId, sessionId, e.getMessage());
            return new SidecarState(-1, 0, null);
        }
    }

    private void writeSidecar(String userId, String sessionId, SidecarState state) throws IOException {
        new AtomicJsonFile(dir(userId, sessionId).resolve(STATE_FILE)).writeJson(mapper, state);
    }

    // ==================== 工具方法 ====================

    private int inMemoryCalls(String userId, String sessionId) {
        AtomicInteger counter = toolCallsSince.get(key(userId, sessionId));
        return counter == null ? 0 : counter.get();
    }

    private static String key(String userId, String sessionId) {
        return (userId == null ? LOCAL_USER : userId) + "/" + sessionId;
    }

    private MarkAgentProperties.SessionMemory config() {
        MarkAgentProperties.SessionMemory sm = properties.sessionMemory();
        return sm == null ? new MarkAgentProperties.SessionMemory(true, 10000, 5000, 3) : sm;
    }

    private static String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }
}
