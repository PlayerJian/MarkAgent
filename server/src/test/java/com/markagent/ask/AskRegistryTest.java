package com.markagent.ask;

import com.markagent.agent.model.TaskIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AskRegistry / PendingAsk / AskUserTool 单测：
 * 登记表与应答幂等、取消只影响指定任务、removeByTask 清理，以及工具的无任务上下文报错、
 * 超时（中止/自动批准）、正常应答、取消唤醒、等待被中断路径。
 * （AskUserTool 用包级构造器注入 no-op/捕获 Broadcaster，绕开 SseEmitterRegistry 重依赖。）
 */
class AskRegistryTest {

    private static final String TASK = "t_test";

    private AskRegistry asks;

    @BeforeEach
    void setUp() {
        asks = new AskRegistry();
    }

    @AfterEach
    void tearDown() {
        TaskIdContext.clear();
    }

    private static PendingAsk pending(String callId, String taskId) {
        return new PendingAsk(callId, taskId, "是否继续？", List.of("同意", "拒绝"));
    }

    private static AskUserTool tool(AskRegistry registry, int timeout, boolean autoApprove,
            AskUserTool.Broadcaster broadcaster) {
        return new AskUserTool(registry, timeout, autoApprove, broadcaster);
    }

    /** 等待决审批出现并返回其 callId（辅助应答/取消线程） */
    private String awaitCallId(AskRegistry registry) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> pending = registry.pendingOf(TASK);
            if (!pending.isEmpty()) {
                return (String) pending.get(0).get("callId");
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("等待审批登记超时");
    }

    // ==================== AskRegistry ====================

    @Test
    void registerGetRemove() {
        PendingAsk p = pending("ask_1", TASK);
        asks.register(p);
        assertEquals(1, asks.size());
        assertEquals(p, asks.get("ask_1"));
        assertNull(asks.get("ask_2"));
        assertTrue(asks.remove("ask_1"));
        assertEquals(0, asks.size());
        assertFalse(asks.remove("ask_1")); // 重复移除幂等
    }

    @Test
    void registerDuplicateThrows() {
        asks.register(pending("ask_1", TASK));
        assertThrows(IllegalStateException.class, () -> asks.register(pending("ask_1", TASK)));
    }

    @Test
    void answerWritesChoiceAndReleasesLatch() throws Exception {
        PendingAsk p = pending("ask_1", TASK);
        asks.register(p);
        assertTrue(asks.answer("ask_1", "同意"));
        assertEquals("同意", p.getAnswer());
        assertTrue(p.latch().await(1, TimeUnit.SECONDS));
        // 已应答再应答：幂等返回 false，不覆盖首次选择
        assertFalse(asks.answer("ask_1", "拒绝"));
        assertEquals("同意", p.getAnswer());
    }

    @Test
    void answerUnknownCallIdReturnsFalse() {
        assertFalse(asks.answer("ask_missing", "同意"));
    }

    @Test
    void cancelPendingOnlyCancelsUnansweredAsksOfTask() throws Exception {
        PendingAsk answered = pending("ask_1", TASK);
        PendingAsk pending1 = pending("ask_2", TASK);
        PendingAsk otherTask = pending("ask_3", "t_other");
        asks.register(answered);
        asks.register(pending1);
        asks.register(otherTask);
        answered.answer("同意");
        // 只取消 t_test 下「未应答」的 ask_2
        assertEquals(1, asks.cancelPending(TASK));
        assertTrue(pending1.isCancelled());
        assertNull(pending1.getAnswer());
        assertTrue(pending1.latch().await(1, TimeUnit.SECONDS));
        assertFalse(answered.isCancelled());
        assertFalse(otherTask.isCancelled());
        // 幂等：t_test 再无待决项；他任务仍可被独立取消
        assertEquals(0, asks.cancelPending(TASK));
        assertEquals(1, asks.cancelPending("t_other"));
        assertTrue(otherTask.isCancelled());
    }

    @Test
    void cancelAfterAnswerReturnsFalse() {
        PendingAsk p = pending("ask_1", TASK);
        assertTrue(p.answer("同意"));
        assertFalse(p.cancel());
        assertFalse(p.isCancelled());
        assertEquals("同意", p.getAnswer());
    }

    @Test
    void removeByTaskOnlyRemovesMatchingTask() {
        asks.register(pending("ask_1", TASK));
        asks.register(pending("ask_2", TASK));
        asks.register(pending("ask_3", "t_other"));
        assertEquals(2, asks.removeByTask(TASK));
        assertEquals(1, asks.size());
        assertNull(asks.get("ask_1"));
        assertNull(asks.get("ask_2"));
        assertNotNull(asks.get("ask_3"));
        // 重复清理幂等
        assertEquals(0, asks.removeByTask(TASK));
    }

    @Test
    void pendingOfListsOnlyUnansweredUncancelled() {
        asks.register(pending("ask_1", TASK));
        PendingAsk answered = pending("ask_2", TASK);
        PendingAsk cancelled = pending("ask_3", TASK);
        asks.register(answered);
        asks.register(cancelled);
        answered.answer("同意");
        cancelled.cancel();
        List<Map<String, Object>> out = asks.pendingOf(TASK);
        assertEquals(1, out.size());
        assertEquals("ask_1", out.get(0).get("callId"));
        assertEquals("是否继续？", out.get(0).get("question"));
        assertEquals(List.of("同意", "拒绝"), out.get(0).get("options"));
        assertNotNull(out.get(0).get("createdAt"));
        // 其他任务不受影响
        asks.register(pending("ask_4", "t_other"));
        assertEquals(1, asks.pendingOf(TASK).size());
    }

    @Test
    void answerAndCancelRaceExactlyOneWins() throws Exception {
        PendingAsk p = pending("ask_1", TASK);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> answer = pool.submit(() -> {
                start.await();
                return p.answer("同意");
            });
            Future<Boolean> cancel = pool.submit(() -> {
                start.await();
                return p.cancel();
            });
            start.countDown();
            boolean answered = answer.get(3, TimeUnit.SECONDS);
            boolean cancelled = cancel.get(3, TimeUnit.SECONDS);
            assertTrue(answered ^ cancelled, "应答与取消必须恰好一方生效");
            assertTrue(p.latch().await(1, TimeUnit.SECONDS));
            if (answered) {
                assertEquals("同意", p.getAnswer());
                assertFalse(p.isCancelled());
            } else {
                assertTrue(p.isCancelled());
                assertNull(p.getAnswer());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== AskUserTool ====================

    @Test
    void noTaskContextReturnsError() {
        AskUserTool tool = tool(asks, 120, false, (taskId, event) -> { });
        String out = tool.askUser("是否继续？", null, null);
        assertEquals("错误：无任务上下文（ask_user 只能在任务内调用）", out);
        assertEquals(0, asks.size());
    }

    @Test
    void parseOptionsDefaultCommaAndJsonArray() {
        assertEquals(List.of("同意", "拒绝"), AskUserTool.parseOptions(null));
        assertEquals(List.of("同意", "拒绝"), AskUserTool.parseOptions("  "));
        assertEquals(List.of("A", "B", "C"), AskUserTool.parseOptions("A,B,C"));
        assertEquals(List.of("A", "B C"), AskUserTool.parseOptions("[\"A\",\"B C\"]"));
        assertEquals(List.of("同意", "拒绝"), AskUserTool.parseOptions("[同意,拒绝]"));
    }

    @Test
    void parseOptionsRejectsInvalidInput() {
        assertNull(AskUserTool.parseOptions("只有一个"));
        assertNull(AskUserTool.parseOptions("1,2,3,4,5,6")); // 超过 5 个
        assertNull(AskUserTool.parseOptions("A,,B")); // 空选项
        assertNull(AskUserTool.parseOptions("[\"A\",\"B")); // 引号未闭合
        assertNull(AskUserTool.parseOptions("[]"));
    }

    @Test
    void invalidOptionsReturnsErrorText() {
        TaskIdContext.set(TASK);
        AskUserTool tool = tool(asks, 120, false, (taskId, event) -> { });
        String out = tool.askUser("是否继续？", "只有一个", null);
        assertTrue(out.startsWith("错误：options 参数非法"), out);
        assertEquals(0, asks.size());
    }

    @Test
    void normalAnswerPathBroadcastsAndReturnsChoice() throws Exception {
        TaskIdContext.set(TASK);
        AtomicReference<Map<String, Object>> event = new AtomicReference<>();
        AskUserTool tool = tool(asks, 120, false, (taskId, data) -> event.set(data));
        Thread answerer = Thread.ofVirtual().start(() -> {
            try {
                String callId = awaitCallId(asks);
                asks.answer(callId, "同意");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String out = tool.askUser("是否继续？", null, null);
        assertEquals("用户选择：同意", out);
        answerer.join(3000);
        // 广播事件内容与 SseEventType.ASK_USER 契约一致
        Map<String, Object> data = event.get();
        assertNotNull(data);
        assertTrue(((String) data.get("callId")).startsWith("ask_"));
        assertEquals("是否继续？", data.get("question"));
        assertEquals(List.of("同意", "拒绝"), data.get("options"));
        assertEquals(120, data.get("timeoutSeconds"));
        // 工具 finally 已移除登记
        assertEquals(0, asks.size());
    }

    @Test
    void cancelPendingWakesToolWithCancelledMessage() throws Exception {
        TaskIdContext.set(TASK);
        AskUserTool tool = tool(asks, 120, false, (taskId, event) -> { });
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                awaitCallId(asks);
                asks.cancelPending(TASK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String out = tool.askUser("是否继续？", null, null);
        assertEquals("审批已取消（任务暂停或停止）", out);
        canceller.join(3000);
        assertEquals(0, asks.size());
    }

    @Test
    void timeoutAbortsOperation() {
        TaskIdContext.set(TASK);
        // 缺省超时调小到下限 5 秒：限时等待超时返回 false → 中止
        AskUserTool tool = tool(asks, 5, false, (taskId, event) -> { });
        long start = System.currentTimeMillis();
        String out = tool.askUser("是否继续？", null, null);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 4000, "应等待接近 5 秒而非立即返回: " + elapsed + "ms");
        assertTrue(out.contains("超时 5 秒"), out);
        assertTrue(out.contains("操作已中止"), out);
        assertEquals(0, asks.size());
    }

    @Test
    void timeoutAutoApprovesFirstOption() {
        TaskIdContext.set(TASK);
        AskUserTool tool = tool(asks, 5, true, (taskId, event) -> { });
        String out = tool.askUser("是否继续？", "选项A,选项B", null);
        assertTrue(out.contains("自动选择第 1 个选项：选项A"), out);
        assertEquals(0, asks.size());
    }

    @Test
    void interruptTreatsAsUnansweredAndRestoresFlag() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(null);
        Thread worker = Thread.ofVirtual().start(() -> {
            TaskIdContext.set(TASK);
            try {
                out.set(tool(asks, 120, false, (taskId, event) -> { }).askUser("是否继续？", null, null));
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                TaskIdContext.clear();
            }
        });
        awaitCallId(asks);
        worker.interrupt();
        worker.join(3000);
        assertEquals("用户未在限定时间内确认（等待被中断），操作已中止；如需继续请用户重新发起", out.get());
        assertEquals(Boolean.TRUE, interrupted.get(), "中断标志应被恢复");
        assertEquals(0, asks.size());
    }
}
