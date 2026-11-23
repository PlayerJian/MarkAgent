package com.markagent.sessionmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.config.MarkAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.deepseek.DeepSeekChatModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 会话滚动记忆：shouldRecord 阈值矩阵 + 记录写入/侧车更新 + LLM 失败保旧（临时目录，不联网） */
class SessionMemoryServiceTest {

    @TempDir
    Path tmp;

    private SessionMemoryService newService(String root, DeepSeekChatModel chatModel) {
        MarkAgentProperties props = new MarkAgentProperties(
                new MarkAgentProperties.Storage(root), null, null, null, null, null, null, null, null,
                null, new MarkAgentProperties.SessionMemory(true, 10000, 5000, 3));
        return new SessionMemoryService(chatModel, props, new ObjectMapper());
    }

    private Path memoryFile(String root, String userId, String sessionId) {
        return Path.of(root).toAbsolutePath().normalize()
                .resolve("sessions").resolve(userId).resolve(sessionId).resolve("session-memory.md");
    }

    private Path sidecarFile(String root, String userId, String sessionId) {
        return Path.of(root).toAbsolutePath().normalize()
                .resolve("sessions").resolve(userId).resolve(sessionId).resolve("session-memory-state.json");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "等待超时");
    }

    @Test
    void shouldRecordThresholdMatrix() {
        // 首次（侧车不存在 lastRecorded=-1）：起始阈值 initTokens
        assertTrue(SessionMemoryService.shouldRecord(10000, -1, 0, 10000, 5000, 3));
        assertFalse(SessionMemoryService.shouldRecord(9999, -1, 0, 10000, 5000, 3));
        // 增量：used - lastRecorded >= deltaTokens
        assertTrue(SessionMemoryService.shouldRecord(15000, 10000, 0, 10000, 5000, 3));
        assertFalse(SessionMemoryService.shouldRecord(14999, 10000, 0, 10000, 5000, 3));
        // 工具间隔：toolCallsSince >= callInterval（即使 used 远低于起始阈值）
        assertTrue(SessionMemoryService.shouldRecord(0, -1, 3, 10000, 5000, 3));
        assertTrue(SessionMemoryService.shouldRecord(0, 10000, 3, 10000, 5000, 3));
        assertFalse(SessionMemoryService.shouldRecord(0, 10000, 2, 10000, 5000, 3));
        // 都不满足
        assertFalse(SessionMemoryService.shouldRecord(8000, 5000, 1, 10000, 5000, 3));
        // 压缩后 used 回落：增量不触发
        assertFalse(SessionMemoryService.shouldRecord(3000, 9000, 0, 10000, 5000, 3));
    }

    @Test
    void recordsMemoryAndSidecarOnFirstThreshold() throws Exception {
        String root = tmp.resolve("r1").toString();
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenReturn("# 会话记忆\n## 用户目标\n- 完成任务A");
        SessionMemoryService service = newService(root, chatModel);

        service.evaluateAndRecord("u1", "s1", "完成 A", "输出 A", 12000);

        // 虚拟线程后台执行 → 轮询等待落盘
        Path memory = memoryFile(root, "u1", "s1");
        await(() -> Files.exists(memory));
        assertTrue(Files.readString(memory, StandardCharsets.UTF_8).contains("完成任务A"));

        String sidecar = Files.readString(sidecarFile(root, "u1", "s1"), StandardCharsets.UTF_8);
        // Jackson INDENT_OUTPUT 会在冒号前加空格，按 JSON 解析断言字段值
        com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(sidecar);
        assertTrue(node.get("lastRecordedTokens").asLong() == 12000);
        assertTrue(node.get("toolCallsSince").asInt() == 0);
    }

    @Test
    void toolCallsTriggerRecordBelowTokenThreshold() throws Exception {
        String root = tmp.resolve("r2").toString();
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenReturn("# 会话记忆\n## 关键上下文\n- 工具调用记录");
        SessionMemoryService service = newService(root, chatModel);

        service.onToolCall("u2", "s2");
        service.onToolCall("u2", "s2");
        service.onToolCall("u2", "s2");
        service.evaluateAndRecord("u2", "s2", "g", "o", 100); // 远低于 initTokens，靠工具间隔触发

        Path memory = memoryFile(root, "u2", "s2");
        await(() -> Files.exists(memory));
        assertTrue(Files.readString(memory, StandardCharsets.UTF_8).contains("工具调用记录"));
        // 成功后内存计数清零：再评估（不满足任何阈值）不触发
        DeepSeekChatModel chatModel2 = mock(DeepSeekChatModel.class);
        when(chatModel2.call(anyString())).thenReturn("不应被调用");
        newService(root, chatModel2).evaluateAndRecord("u2", "s2", "g", "o", 100);
        verifyNoInteractions(chatModel2);
    }

    @Test
    void noTriggerDoesNotCallLlm() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        SessionMemoryService service = newService(tmp.resolve("r3").toString(), chatModel);

        service.evaluateAndRecord("u3", "s3", "g", "o", 100); // 低于 initTokens 且无工具调用

        // 不满足阈值 → 不派生后台线程 → 无任何 LLM 交互（同步可断言）
        verifyNoInteractions(chatModel);
    }

    @Test
    void llmFailureKeepsOldFileAndSidecar() throws Exception {
        String root = tmp.resolve("r4").toString();
        Path memory = memoryFile(root, "u4", "s4");
        Files.createDirectories(memory.getParent());
        Files.writeString(memory, "# 会话记忆\n## 用户目标\n- 旧记忆", StandardCharsets.UTF_8);
        Path sidecar = sidecarFile(root, "u4", "s4");
        Files.writeString(sidecar, "{\"lastRecordedTokens\":10000,\"toolCallsSince\":0,\"lastUpdatedAt\":\"t0\"}",
                StandardCharsets.UTF_8);

        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("LLM 超时"));
        SessionMemoryService service = newService(root, chatModel);

        service.evaluateAndRecord("u4", "s4", "g", "o", 16000); // 增量 6000 >= 5000 触发

        // 等待后台线程完成（Mockito timeout 验证调用已发生）
        verify(chatModel, timeout(5000)).call(anyString());
        // 旧文件不变、侧车不变（下次再试）
        assertTrue(Files.readString(memory, StandardCharsets.UTF_8).contains("旧记忆"));
        String sidecarJson = Files.readString(sidecar, StandardCharsets.UTF_8);
        assertTrue(sidecarJson.contains("\"lastRecordedTokens\":10000"));
        assertTrue(sidecarJson.contains("\"t0\""));
    }

    @Test
    void nullUserNormalizedToLocalUser() throws Exception {
        String root = tmp.resolve("r5").toString();
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenReturn("# 会话记忆\n## 用户目标\n- 本地用户");
        SessionMemoryService service = newService(root, chatModel);

        service.evaluateAndRecord(null, "s5", "g", "o", 12000);

        Path memory = Path.of(root).toAbsolutePath().normalize()
                .resolve("sessions/local-user/s5/session-memory.md");
        await(() -> Files.exists(memory));
        assertTrue(Files.readString(memory, StandardCharsets.UTF_8).contains("本地用户"));
    }
}
