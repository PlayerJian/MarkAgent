package com.markagent.prompt;

import com.markagent.memory.MemoryService;
import com.markagent.skills.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 每任务首条 user 消息的动态上下文装配：今日日期 + 用户长期记忆索引 + 会话摘要 + 技能清单。
 * <ul>
 *   <li>记忆索引走 {@link MemoryService#indexForPrompt}（行数/字节硬截断保护）；为空则省略整段</li>
 *   <li>会话摘要读 data/sessions/{userId}/{sessionId}/session-memory.md 全文；文件不存在则省略整段</li>
 *   <li>技能清单沿用 {@link SkillRegistry#promptSection} 现有输出</li>
 * </ul>
 * 每段只在有内容时输出；整体为空（全空）时返回 goal 原样。
 */
@Component
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    /** 无用户上下文时的占位用户（与 JsonFileSessionStore 的 userId 默认规则一致） */
    private static final String LOCAL_USER = "local-user";

    private final MemoryService memoryService;
    private final SkillRegistry skillRegistry;
    private final Path sessionsRoot;

    public PromptAssembler(MemoryService memoryService, SkillRegistry skillRegistry,
            @Value("${markagent.storage.root:./data}") String storageRoot) {
        this.memoryService = memoryService;
        this.skillRegistry = skillRegistry;
        this.sessionsRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("sessions");
    }

    /** 组装每任务首条 user 消息的动态上下文段 */
    public String assemble(String userId, String sessionId, String goal) {
        String uid = userId == null ? LOCAL_USER : userId;
        StringBuilder sb = new StringBuilder();
        sb.append("## 动态上下文\n- 今日日期：").append(LocalDate.now()).append('\n');

        String index = memoryService.indexForPrompt(uid);
        if (index != null && !index.isBlank()) {
            sb.append("\n## 用户长期记忆索引\n").append(index.stripTrailing()).append('\n')
                    .append("记忆治理规则：\n")
                    .append("- 值得保存：用户偏好、重要约定、跨会话结论；不保存临时中间结果与可从对话推导的内容\n")
                    .append("- 保存前先 memory_search 查重；已有相关记忆时更新优于新增\n");
        }

        String sessionMemory = readSessionMemory(uid, sessionId);
        if (!sessionMemory.isBlank()) {
            sb.append("\n## 会话摘要\n").append(sessionMemory.stripTrailing()).append('\n');
        }

        String skills = skillRegistry.promptSection();
        if (skills != null && !skills.isBlank()) {
            sb.append('\n').append(skills.stripTrailing()).append('\n');
        }

        String result = sb.toString();
        // 所有可选段全空（理论上动态上下文段恒存在，防御性保留）：装配结果为空则回退 goal 原样
        if (result.isBlank()) {
            return goal == null ? "" : goal;
        }
        return result;
    }

    /** 会话摘要：data/sessions/{userId}/{sessionId}/session-memory.md 全文，不存在则空串 */
    private String readSessionMemory(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        Path file = sessionsRoot.resolve(userId).resolve(sessionId).resolve("session-memory.md");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取会话摘要失败 {}: {}", file, e.getMessage());
            return "";
        }
    }
}
