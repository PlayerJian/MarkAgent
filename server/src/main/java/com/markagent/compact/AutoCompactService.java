package com.markagent.compact;

import com.markagent.common.dto.ContextUsage;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.sse.SseEventType;
import com.markagent.config.MarkAgentProperties;
import com.markagent.context.TokenBudgetService;
import com.markagent.events.SseEmitterRegistry;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动上下文压缩（auto-compact）。有效窗口 = 总窗口 − 摘要预留（reserveTokens），
 * 再留 bufferTokens 缓冲线提前触发。时机：AgentScope 流结束是唯一安全压缩点（agent 已停止写状态文件）。
 * 流程：读持久化 AgentState → 裸 LLM 摘要（无工具）→ 重建 AgentState（摘要消息 + 尾部保留消息）→ 回写，
 * 下一次任务/resume 自动加载压缩后的上下文。
 * <ul>
 *   <li>触发：used &gt;= budget − reserveTokens − bufferTokens（checkAndBroadcast 每轮迭代提前广播 planned）</li>
 *   <li>熔断：连续 maxConsecutiveFailures 次失败后跳过，成功清零</li>
 *   <li>PTL 防御：摘要请求超限时剥掉最旧 20% 输入重试一次</li>
 *   <li>全程 try/catch：压缩任何一步失败绝不影响任务正常终止流程</li>
 * </ul>
 */
@Component
public class AutoCompactService {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactService.class);

    private static final String STATE_KEY = "agent_state";
    /** 超限类错误关键词（PTL 防御：剥头重试一次） */
    private static final List<String> PTL_KEYWORDS = List.of(
            "maximum context", "too long", "context length", "exceed", "超", "length");
    /** 摘要输入中工具结果的最大保留字符数 */
    private static final int TOOL_RESULT_MAX_CHARS = 1000;

    private final AgentStateStore agentStateStore;
    private final TokenBudgetService tokenBudget;
    private final SseEmitterRegistry registry;
    private final DeepSeekChatModel chatModel;
    private final MarkAgentProperties properties;
    /** (userId,sessionId) → 连续压缩失败数（熔断） */
    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    /** taskId → 本轮任务是否已广播 planned（去重） */
    private final Map<String, AtomicBoolean> plannedFlags = new ConcurrentHashMap<>();

    public AutoCompactService(AgentStateStore agentStateStore, TokenBudgetService tokenBudget,
            SseEmitterRegistry registry, DeepSeekChatModel chatModel, MarkAgentProperties properties) {
        this.agentStateStore = agentStateStore;
        this.tokenBudget = tokenBudget;
        this.registry = registry;
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /** 触发判定（纯静态）：used &gt;= budget − reserve − buffer */
    public static boolean shouldCompact(long used, long budget, int reserveTokens, int bufferTokens) {
        return used >= budget - reserveTokens - bufferTokens;
    }

    /** 每轮迭代（AgentEndEvent）检查：满足阈值且未广播过 → 广播 COMPACT planned */
    public void checkAndBroadcast(TaskRecord task) {
        try {
            if (!config().enabled()) {
                return;
            }
            AtomicBoolean planned = plannedFlags.computeIfAbsent(task.id(), k -> new AtomicBoolean(false));
            if (planned.get()) {
                return;
            }
            MarkAgentProperties.Compact cfg = config();
            ContextUsage usage = tokenBudget.usage(task.userId(), task.sessionId());
            if (!shouldCompact(usage.usedTokens(), usage.budget(), cfg.reserveTokens(), cfg.bufferTokens())) {
                return;
            }
            if (planned.compareAndSet(false, true)) {
                registry.broadcast(task.id(), SseEventType.COMPACT, Map.of(
                        "phase", "planned",
                        "usedTokens", usage.usedTokens(),
                        "effectiveWindow", usage.budget() - cfg.reserveTokens()));
                log.info("上下文即将压缩 task={} used={}/{}", task.id(), usage.usedTokens(), usage.budget());
            }
        } catch (Exception e) {
            log.warn("压缩预检失败 task={}: {}", task.id(), e.getMessage());
        }
    }

    /**
     * 任务终止态调用：先估计，不满足直接返回；满足则同步执行压缩。
     * 注意入参取 TaskRecord 而非 (userId, sessionId)：completed/failed 事件需按 taskId 广播进任务事件流。
     */
    public void compactIfNeeded(TaskRecord task) {
        try {
            if (!config().enabled()) {
                return;
            }
            MarkAgentProperties.Compact cfg = config();
            ContextUsage usage = tokenBudget.usage(task.userId(), task.sessionId());
            if (!shouldCompact(usage.usedTokens(), usage.budget(), cfg.reserveTokens(), cfg.bufferTokens())) {
                return;
            }
            doCompact(task);
        } catch (Exception e) {
            log.warn("压缩检查失败 task={}: {}", task.id(), e.getMessage());
        }
    }

    /** 任务结束清理 per-task 去重标记（AgentChatService.finish 调用） */
    public void cleanup(String taskId) {
        plannedFlags.remove(taskId);
    }

    // ==================== 压缩执行 ====================

    private void doCompact(TaskRecord task) {
        String key = task.userId() + "/" + task.sessionId();
        MarkAgentProperties.Compact cfg = config();
        int failures = consecutiveFailures.getOrDefault(key, new AtomicInteger()).get();
        if (failures >= cfg.maxConsecutiveFailures()) {
            log.warn("连续压缩失败 {} 次已达熔断上限，本轮跳过 {}/{}", failures, task.userId(), task.sessionId());
            return;
        }

        Optional<AgentState> current = agentStateStore.get(task.userId(), task.sessionId(), STATE_KEY, AgentState.class);
        if (current.isEmpty()) {
            log.debug("无持久化状态可压缩 {}/{}", task.userId(), task.sessionId());
            return;
        }
        AgentState old = current.get();

        String input = buildSummaryInput(old.getContext());
        String summary;
        try {
            summary = chatModel.call(buildCompressPrompt(input));
        } catch (Exception e) {
            if (isPtlError(e)) {
                // PTL 防御：剥掉最旧 20% 头部重试一次
                log.warn("摘要请求超限，剥掉最旧 20% 重试 {}/{}", task.userId(), task.sessionId());
                try {
                    summary = chatModel.call(buildCompressPrompt(stripOldest(input, 0.2)));
                } catch (Exception retry) {
                    onFailure(task, key, "PTL 剥头重试后仍失败：" + retry.getMessage());
                    return;
                }
            } else {
                onFailure(task, key, e.getMessage());
                return;
            }
        }
        if (summary == null || summary.isBlank()) {
            onFailure(task, key, "摘要输出为空");
            return;
        }

        try {
            CompactedState compacted = rebuild(old, summary.strip(), cfg.tailTokens());
            agentStateStore.save(task.userId(), task.sessionId(), STATE_KEY, compacted.state());
            consecutiveFailures.remove(key);
            registry.broadcast(task.id(), SseEventType.COMPACT, Map.of(
                    "phase", "completed",
                    "summaryTokens", TokenBudgetService.estimate(summary),
                    "keptMessages", compacted.keptMessages(),
                    "newContextTokens", contextTokens(compacted.state().getContext())));
            log.info("上下文压缩完成 {}/{}: 摘要 {} tokens，保留尾部 {} 条消息",
                    task.userId(), task.sessionId(), TokenBudgetService.estimate(summary), compacted.keptMessages());
        } catch (Exception e) {
            onFailure(task, key, "压缩状态回写失败：" + e.getMessage());
        }
    }

    private void onFailure(TaskRecord task, String key, String reason) {
        int n = consecutiveFailures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        log.warn("上下文压缩失败（连续 {} 次）{}/{}: {}", n, task.userId(), task.sessionId(), reason);
        try {
            registry.broadcast(task.id(), SseEventType.COMPACT, Map.of(
                    "phase", "failed",
                    "reason", reason == null ? "unknown" : reason));
        } catch (Exception e) {
            log.warn("压缩失败事件广播失败 task={}: {}", task.id(), e.getMessage());
        }
    }

    // ==================== 摘要输入与状态重建 ====================

    /** 全量消息文本：每条 role: content；工具结果只取前 1000 字符 */
    private static String buildSummaryInput(List<Msg> context) {
        if (context == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg msg : context) {
            String text = textOf(msg);
            if (text == null || text.isBlank()) {
                continue;
            }
            String role = msg.getRole() == null ? "unknown" : msg.getRole().name().toLowerCase();
            sb.append(role).append(": ");
            sb.append(msg.getRole() == MsgRole.TOOL && text.length() > TOOL_RESULT_MAX_CHARS
                    ? text.substring(0, TOOL_RESULT_MAX_CHARS) : text);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 消息文本：优先 getTextContent，结构块（工具结果）逐块提取 */
    private static String textOf(Msg msg) {
        String text = msg.getTextContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        if (msg.getContent() != null) {
            for (ContentBlock block : msg.getContent()) {
                appendBlock(sb, block);
            }
        }
        return sb.toString();
    }

    private static void appendBlock(StringBuilder sb, ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(textBlock.getText());
        } else if (block instanceof ToolResultBlock resultBlock) {
            for (ContentBlock output : resultBlock.getOutput()) {
                appendBlock(sb, output);
            }
        }
    }

    /** NO_TOOLS_PREAMBLE 风格：上下文压缩器，明确禁止工具调用 */
    private static String buildCompressPrompt(String input) {
        return """
                你是上下文压缩器，不要调用任何工具。将下面的会话历史压缩成结构化中文摘要，要求：
                - 保留：任务目标、用户约束、未完成事项、关键决策；
                - 丢弃：重复内容、工具调用细节、与目标无关的中间过程；
                - 只输出摘要正文，不要附加任何解释。

                ## 会话历史
                """ + input;
    }

    /**
     * 重建压缩后状态：摘要消息 + 原 context 尾部完整消息（从后往前累计，字符数不超过 tailTokens*4）。
     * 其余字段（replyId/curIter/各子状态）从旧状态原样复制。
     */
    private static CompactedState rebuild(AgentState old, String summary, int tailTokens) {
        long tailChars = (long) tailTokens * 4; // 与 estimate 的 chars/4 反向换算字符预算
        List<Msg> kept = new ArrayList<>();
        long chars = 0;
        List<Msg> context = old.getContext() == null ? List.of() : old.getContext();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            String text = textOf(msg);
            int len = text == null ? 0 : text.length();
            if (!kept.isEmpty() && chars + len > tailChars) {
                break;
            }
            kept.add(0, msg);
            chars += len;
        }
        Msg summaryMsg = Msg.builderForRole(MsgRole.USER)
                .textContent("以下是本会话的上下文摘要，据此继续工作：\n" + summary)
                .build();
        List<Msg> newContext = new ArrayList<>(kept.size() + 1);
        newContext.add(summaryMsg);
        newContext.addAll(kept);

        AgentState newState = AgentState.builder()
                .sessionId(old.getSessionId())
                .userId(old.getUserId())
                .summary(summary)
                .context(newContext)
                .replyId(old.getReplyId())
                .curIter(old.getCurIter())
                .shutdownInterrupted(old.isShutdownInterrupted())
                .permissionContext(old.getPermissionContext())
                .toolContext(old.getToolContext())
                .tasksContext(old.getTasksContext())
                .planModeContext(old.getPlanModeContext())
                .build();
        return new CompactedState(newState, kept.size());
    }

    /** 压缩产物：新状态 + 保留的尾部消息条数（COMPACT completed 事件用） */
    private record CompactedState(AgentState state, int keptMessages) {
    }

    private static long contextTokens(List<Msg> context) {
        long chars = 0;
        for (Msg msg : context) {
            String text = textOf(msg);
            chars += text == null ? 0 : text.length();
        }
        return chars / 4;
    }

    // ==================== 工具方法 ====================

    private static boolean isPtlError(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        for (String keyword : PTL_KEYWORDS) {
            if (message.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉最旧 ratio 头部（按字符数） */
    private static String stripOldest(String input, double ratio) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        int cut = (int) (input.length() * ratio);
        return cut <= 0 || cut >= input.length() ? input : input.substring(cut);
    }

    private MarkAgentProperties.Compact config() {
        MarkAgentProperties.Compact c = properties.compact();
        return c == null ? new MarkAgentProperties.Compact(true, 20000, 13000, 3, 8000) : c;
    }
}
