package com.markagent.compact;

import com.markagent.common.dto.TaskOptions;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
import com.markagent.common.sse.SseEventType;
import com.markagent.config.MarkAgentProperties;
import com.markagent.context.TokenBudgetService;
import com.markagent.events.SseEmitterRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.core.state.TaskContextState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.deepseek.DeepSeekChatModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 自动压缩：shouldCompact 边界 + 状态重建（真 JsonFileAgentStateStore 真存真读）+ 熔断 + PTL 剥头重试 */
class AutoCompactServiceTest {

    @TempDir
    Path tmp;

    private record Fixture(AutoCompactService service, AgentStateStore store, DeepSeekChatModel chatModel,
            SseEmitterRegistry registry) {
    }

    /**
     * 固定夹具：context.max-tokens=1000 且 reserve/buffer 取默认 20000/13000，
     * 阈值 = 1000 − 33000 为负 → 只要有持久化状态就触发压缩；tail-tokens 可调（默认 100 便于验证截断）。
     */
    private Fixture fixture(String root, DeepSeekChatModel chatModel, int tailTokens) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(Path.of(root).resolve("agentscope"));
        MarkAgentProperties props = new MarkAgentProperties(
                new MarkAgentProperties.Storage(root), null, null,
                new MarkAgentProperties.Context(1000, 0.75), null, null, null, null, null,
                new MarkAgentProperties.Compact(true, 20000, 13000, 3, tailTokens), null);
        TokenBudgetService tokenBudget = new TokenBudgetService(store, props);
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        return new Fixture(new AutoCompactService(store, tokenBudget, registry, chatModel, props),
                store, chatModel, registry);
    }

    private Fixture fixture(String root, DeepSeekChatModel chatModel) {
        return fixture(root, chatModel, 100);
    }

    private static TaskRecord task(String id, String userId, String sessionId) {
        return new TaskRecord(id, sessionId, userId, "目标", TaskStatus.RUNNING, null, List.of(),
                TaskOptions.defaults(), "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null);
    }

    private static Msg msg(MsgRole role, String text) {
        return Msg.builderForRole(role).textContent(text).build();
    }

    private static AgentState buildState(String userId, String sessionId, List<Msg> context) {
        PermissionContextState permission = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        PlanModeContextState planMode = new PlanModeContextState(true, "plan.md");
        return AgentState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .summary("旧摘要")
                .context(context)
                .replyId("r1")
                .curIter(7)
                .permissionContext(permission)
                .tasksContext(new TaskContextState())
                .planModeContext(planMode)
                .build();
    }

    @Test
    void shouldCompactBoundaries() {
        // 阈值 = 1_000_000 − 20000 − 13000 = 967000
        assertFalse(AutoCompactService.shouldCompact(966999, 1_000_000, 20000, 13000));
        assertTrue(AutoCompactService.shouldCompact(967000, 1_000_000, 20000, 13000));
        assertTrue(AutoCompactService.shouldCompact(967001, 1_000_000, 20000, 13000));
    }

    @Test
    void rebuildsStateWithSummaryAndTailAndCopiesSubStates() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenReturn("压缩摘要内容");
        Fixture f = fixture(tmp.resolve("c1").toString(), chatModel);

        // 8 条消息 × 100 字符；tail-tokens=100 → 字符预算 400 → 保留尾部 4 条完整消息
        List<Msg> context = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String text = "x".repeat(100);
            context.add(msg(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT, text));
        }
        AgentState old = buildState("u1", "s1", context);
        f.store().save("u1", "s1", "agent_state", old);

        f.service().compactIfNeeded(task("t1", "u1", "s1"));

        AgentState reloaded = f.store().get("u1", "s1", "agent_state", AgentState.class).orElseThrow();
        // 新 context = 摘要消息 + 尾部 4 条
        assertEquals(5, reloaded.getContext().size());
        Msg summaryMsg = reloaded.getContext().get(0);
        assertTrue(summaryMsg.getTextContent().contains("以下是本会话的上下文摘要"));
        assertTrue(summaryMsg.getTextContent().contains("压缩摘要内容"));
        // 尾部保留 = 原 context 最后 4 条（真存真读经 JSON 序列化，按值比较角色与文本）
        for (int i = 0; i < 4; i++) {
            Msg original = context.get(4 + i);
            Msg kept = reloaded.getContext().get(1 + i);
            assertEquals(original.getRole(), kept.getRole());
            assertEquals(original.getTextContent(), kept.getTextContent());
        }
        // summary 字段 + 标量字段 + 子状态原样复制（值相等）
        assertEquals("压缩摘要内容", reloaded.getSummary());
        assertEquals("r1", reloaded.getReplyId());
        assertEquals(7, reloaded.getCurIter());
        assertEquals(old.getPermissionContext(), reloaded.getPermissionContext());
        assertEquals(old.getTasksContext(), reloaded.getTasksContext());
        assertEquals(old.getPlanModeContext(), reloaded.getPlanModeContext());
        assertEquals("u1", reloaded.getUserId());
        assertEquals("s1", reloaded.getSessionId());
        // completed 事件广播（含保留条数）
        verify(f.registry(), times(1)).broadcast(eq("t1"), eq(SseEventType.COMPACT), anyMap());
    }

    @Test
    void underThresholdDoesNothing() {
        // 大预算：阈值 967000，上下文几百字符远不达标 → 不触发 LLM、不广播
        String root = tmp.resolve("c2").toString();
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(Path.of(root).resolve("agentscope"));
        MarkAgentProperties props = new MarkAgentProperties(
                new MarkAgentProperties.Storage(root), null, null,
                new MarkAgentProperties.Context(1_000_000, 0.75), null, null, null, null, null,
                new MarkAgentProperties.Compact(true, 20000, 13000, 3, 8000), null);
        TokenBudgetService tokenBudget = new TokenBudgetService(store, props);
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        AutoCompactService service = new AutoCompactService(store, tokenBudget, registry, chatModel, props);

        store.save("u2", "s2", "agent_state", buildState("u2", "s2", List.of(msg(MsgRole.USER, "短消息"))));

        service.compactIfNeeded(task("t2", "u2", "s2"));
        verifyNoInteractions(chatModel);
        verifyNoInteractions(registry);
        // 状态原样保留
        assertNotNull(store.get("u2", "s2", "agent_state", AgentState.class).orElseThrow());
    }

    @Test
    void emptyStateReturnsWithoutLlmCall() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        Fixture f = fixture(tmp.resolve("c3").toString(), chatModel);
        // 无持久化状态：usage=0 但仍过阈值（负阈值）→ 读状态为空 → 直接返回
        f.service().compactIfNeeded(task("t3", "u3", "s3"));
        verifyNoInteractions(chatModel);
    }

    @Test
    void circuitBreakerSkipsAfterThreeConsecutiveFailures() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("boom"));
        Fixture f = fixture(tmp.resolve("c4").toString(), chatModel);
        f.store().save("u4", "s4", "agent_state", buildState("u4", "s4",
                List.of(msg(MsgRole.USER, "上下文内容"))));

        for (int i = 0; i < 4; i++) {
            f.service().compactIfNeeded(task("t4", "u4", "s4"));
        }

        // 前 3 次尝试调用 LLM 并失败，第 4 次被熔断跳过
        verify(chatModel, times(3)).call(anyString());
        verify(f.registry(), times(3)).broadcast(eq("t4"), eq(SseEventType.COMPACT), anyMap());
    }

    @Test
    void ptlErrorStripsHeadAndRetriesOnce() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString()))
                .thenThrow(new RuntimeException("Error: maximum context length exceeded"))
                .thenReturn("剥头重试成功摘要");
        Fixture f = fixture(tmp.resolve("c5").toString(), chatModel);
        f.store().save("u5", "s5", "agent_state", buildState("u5", "s5",
                List.of(msg(MsgRole.USER, "上下文内容"))));

        f.service().compactIfNeeded(task("t5", "u5", "s5"));

        // 第一次抛超限类异常 → 剥头重试一次成功 → 状态已压缩
        verify(chatModel, times(2)).call(anyString());
        AgentState reloaded = f.store().get("u5", "s5", "agent_state", AgentState.class).orElseThrow();
        assertEquals("剥头重试成功摘要", reloaded.getSummary());
        // 成功 → 失败计数清零、completed 广播
        verify(f.registry(), times(1)).broadcast(eq("t5"), eq(SseEventType.COMPACT), anyMap());
    }

    @Test
    void ptlRetryAlsoFailsCountsAsOneFailure() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("context length too long"));
        Fixture f = fixture(tmp.resolve("c6").toString(), chatModel);
        f.store().save("u6", "s6", "agent_state", buildState("u6", "s6",
                List.of(msg(MsgRole.USER, "上下文内容"))));

        f.service().compactIfNeeded(task("t6", "u6", "s6"));

        // 两次调用（原始 + 剥头重试）计一次失败，广播 failed
        verify(chatModel, times(2)).call(anyString());
        verify(f.registry(), times(1)).broadcast(eq("t6"), eq(SseEventType.COMPACT), anyMap());
    }

    @Test
    void checkAndBroadcastPlannedOncePerTask() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        Fixture f = fixture(tmp.resolve("c7").toString(), chatModel);
        f.store().save("u7", "s7", "agent_state", buildState("u7", "s7",
                List.of(msg(MsgRole.USER, "上下文内容"))));

        TaskRecord t = task("t7", "u7", "s7");
        f.service().checkAndBroadcast(t);
        f.service().checkAndBroadcast(t); // 去重：第二次不广播

        verify(f.registry(), times(1)).broadcast(eq("t7"), eq(SseEventType.COMPACT), anyMap());
        // finish 清理后新一轮任务可再次广播
        f.service().cleanup("t7");
        f.service().checkAndBroadcast(t);
        verify(f.registry(), times(2)).broadcast(eq("t7"), eq(SseEventType.COMPACT), anyMap());
    }
}
