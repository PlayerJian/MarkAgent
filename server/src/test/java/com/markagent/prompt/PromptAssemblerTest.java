package com.markagent.prompt;

import com.markagent.config.MarkAgentProperties;
import com.markagent.embedding.EmbeddingProvider;
import com.markagent.embedding.KeywordEmbeddingProvider;
import com.markagent.memory.MemoryService;
import com.markagent.skills.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 首条消息动态上下文装配：记忆索引 × 会话摘要四组合 + 技能段 + 用户占位规则（临时目录，不联网） */
class PromptAssemblerTest {

    @TempDir
    Path tmp;

    private record Fixture(MemoryService memoryService, SkillRegistry skillRegistry,
            PromptAssembler assembler) {
    }

    private Fixture fixture(Path root) {
        MarkAgentProperties props = new MarkAgentProperties(
                new MarkAgentProperties.Storage(root.toString()), null, null, null, null, null, null, null, null,
                null, null);
        MemoryService memoryService = new MemoryService(props, new EmbeddingProvider() {
            @Override
            public boolean available() {
                return false; // 关键词档，测试不联网
            }

            @Override
            public double[] embed(String text) {
                return new double[0];
            }

            @Override
            public String source() {
                return "stub";
            }
        }, new KeywordEmbeddingProvider());
        SkillRegistry skillRegistry = new SkillRegistry(root.toString());
        return new Fixture(memoryService, skillRegistry,
                new PromptAssembler(memoryService, skillRegistry, root.toString()));
    }

    private static void writeSessionMemory(Path root, String userId, String sessionId, String content)
            throws Exception {
        Path file = root.resolve("sessions").resolve(userId).resolve(sessionId).resolve("session-memory.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void dateOnlyWhenEverythingEmpty() {
        Fixture f = fixture(tmp.resolve("t1"));
        String out = f.assembler().assemble("u1", "s1", "任务目标");
        assertTrue(out.contains("## 动态上下文"));
        assertTrue(out.contains(LocalDate.now().toString()));
        assertFalse(out.contains("## 用户长期记忆索引"));
        assertFalse(out.contains("## 会话摘要"));
        assertFalse(out.contains("## 可用技能"));
    }

    @Test
    void injectsMemoryIndexWithGovernanceRules() throws Exception {
        Path root = tmp.resolve("t2");
        Fixture f = fixture(root);
        f.memoryService().save("u2", "# 用户偏好\n喜欢蓝色主题", "agent");

        String out = f.assembler().assemble("u2", "s2", "任务目标");
        assertTrue(out.contains("## 用户长期记忆索引"));
        assertTrue(out.contains("- [用户偏好](entries/"));
        assertTrue(out.contains("记忆治理规则"));
        assertTrue(out.contains("保存前先 memory_search 查重"));
        assertFalse(out.contains("## 会话摘要"));
    }

    @Test
    void injectsSessionMemoryWhenFileExists() throws Exception {
        Path root = tmp.resolve("t3");
        Fixture f = fixture(root);
        writeSessionMemory(root, "u3", "s3", "上一轮已确认报告格式");

        String out = f.assembler().assemble("u3", "s3", "任务目标");
        assertTrue(out.contains("## 会话摘要"));
        assertTrue(out.contains("上一轮已确认报告格式"));
        assertFalse(out.contains("## 用户长期记忆索引"));
    }

    @Test
    void omitsSessionSummaryWhenFileMissing() {
        Path root = tmp.resolve("t4");
        Fixture f = fixture(root);
        String out = f.assembler().assemble("u4", "s4", "任务目标");
        assertFalse(out.contains("## 会话摘要"));
        assertFalse(out.contains("## 用户长期记忆索引"));
    }

    @Test
    void combinesAllSectionsInOrder() throws Exception {
        Path root = tmp.resolve("t5");
        Fixture f = fixture(root);
        f.memoryService().save("u5", "# 内容五\n正文五", "manual");
        writeSessionMemory(root, "u5", "s5", "会话摘要五");
        f.skillRegistry().save("demo-skill", "---\nname: demo-skill\ndescription: 演示技能\n---\n技能正文");

        String out = f.assembler().assemble("u5", "s5", "任务目标");
        assertTrue(out.contains("## 动态上下文"));
        assertTrue(out.contains("## 用户长期记忆索引"));
        assertTrue(out.contains("## 会话摘要"));
        assertTrue(out.contains("## 可用技能（skills）"));
        assertTrue(out.contains("演示技能"));
        // 段落顺序：动态上下文 → 记忆索引 → 会话摘要 → 技能
        assertTrue(out.indexOf("## 动态上下文") < out.indexOf("## 用户长期记忆索引"));
        assertTrue(out.indexOf("## 用户长期记忆索引") < out.indexOf("## 会话摘要"));
        assertTrue(out.indexOf("## 会话摘要") < out.indexOf("## 可用技能"));
    }

    @Test
    void nullUserFallsBackToLocalUser() throws Exception {
        Path root = tmp.resolve("t6");
        Fixture f = fixture(root);
        writeSessionMemory(root, "local-user", "s6", "本地用户摘要");

        String out = f.assembler().assemble(null, "s6", "任务目标");
        assertTrue(out.contains("本地用户摘要"));
    }
}
