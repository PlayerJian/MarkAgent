package com.markagent.tools;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkerRegistry 与 TeamTool 纯函数（formatNotification/formatAgentList/stopAgent）的单测：
 * 覆盖登记表并发行为、完成闩语义、失败收尾幂等、role/finishedAt 存取、TTL 清理与任务列表排序，
 * 以及 <task-notification> 格式化的 500/4000 截断与换行压缩、agent_stop 的终态/幂等/dispose 语义。
 * （不覆盖 TeamTool 全量——其依赖 SseEmitterRegistry/ReActAgent 等重依赖，不在单测范围。）
 */
class WorkerRegistryTest {

    private static WorkerRegistry.Handle handle(String agentId, String taskId) {
        return new WorkerRegistry.Handle(agentId, taskId);
    }

    // ==================== WorkerRegistry ====================

    @Test
    void registerAndGet() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        registry.register(h);
        assertEquals(1, registry.size());
        assertEquals(h, registry.get("a_1"));
        assertNull(registry.get("a_2"));
        // 新句柄初始为 RUNNING 且带 startedAt
        assertEquals(WorkerRegistry.RUNNING, h.getStatus());
        assertNotNull(h.getStartedAt());
    }

    @Test
    void registerDuplicateThrows() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(handle("a_1", "t_1"));
        assertThrows(IllegalStateException.class, () -> registry.register(handle("a_1", "t_2")));
    }

    @Test
    void removeByTaskOnlyRemovesMatchingTask() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(handle("a_1", "t_1"));
        registry.register(handle("a_2", "t_1"));
        registry.register(handle("a_3", "t_2"));
        assertEquals(2, registry.removeByTask("t_1"));
        assertEquals(1, registry.size());
        assertNull(registry.get("a_1"));
        assertNull(registry.get("a_2"));
        assertNotNull(registry.get("a_3"));
        // 重复清理幂等
        assertEquals(0, registry.removeByTask("t_1"));
    }

    @Test
    void concurrentRegisterIsThreadSafe() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        int threads = 16;
        int perThread = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            int base = i * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        registry.register(new WorkerRegistry.Handle(
                                "a_" + (base + j), "t_" + ((base + j) % 4)));
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), () -> "并发登记出现异常: " + errors);
        assertEquals(threads * perThread, registry.size());
        assertNotNull(registry.get("a_" + (threads * perThread - 1)));
    }

    @Test
    void concurrentAppendResultIsThreadSafe() throws Exception {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    h.appendResult("x");
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, h.getResultText().length());
    }

    @Test
    void completeSuccessReleasesLatchAndSetsStatus() throws Exception {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        h.appendResult("调研结论：方案 A 可行");
        h.completeSuccess();
        assertTrue(h.getCompletionLatch().await(1, TimeUnit.SECONDS));
        assertEquals(WorkerRegistry.SUCCESS, h.getStatus());
        assertEquals("调研结论：方案 A 可行", h.getResultText());
    }

    @Test
    void completeFailureRecordsReasonAndReleasesLatch() throws Exception {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        h.appendResult("部分输出");
        h.completeFailure("任务已暂停/停止");
        assertTrue(h.getCompletionLatch().await(1, TimeUnit.SECONDS));
        assertEquals(WorkerRegistry.FAILED, h.getStatus());
        assertTrue(h.getResultText().contains("任务已暂停/停止"));
    }

    @Test
    void completeIsIdempotentFirstTransitionWins() {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        h.completeSuccess();
        // 后到的失败终结不覆盖已定终态，也不追加原因
        h.completeFailure("后到的失败不覆盖");
        assertEquals(WorkerRegistry.SUCCESS, h.getStatus());
        assertFalse(h.getResultText().contains("后到的失败不覆盖"));
    }

    @Test
    void latchCountsDownOnlyOnCompletion() throws Exception {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        // 未完成时限时等待超时返回 false（latch 未释放）
        assertFalse(h.getCompletionLatch().await(50, TimeUnit.MILLISECONDS));
        // 完成（成功或失败都行）后 latch 释放
        h.completeFailure("超时");
        assertTrue(h.getCompletionLatch().await(1, TimeUnit.SECONDS));
    }

    @Test
    void failByTaskOnlyFailsRunningHandles() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle done = handle("a_1", "t_1");
        WorkerRegistry.Handle running = handle("a_2", "t_1");
        WorkerRegistry.Handle otherTask = handle("a_3", "t_2");
        registry.register(done);
        registry.register(running);
        registry.register(otherTask);
        done.completeSuccess();
        // 只影响 t_1 下 RUNNING 的句柄
        assertEquals(1, registry.failByTask("t_1", "任务已暂停/停止"));
        assertEquals(WorkerRegistry.SUCCESS, done.getStatus());
        assertEquals(WorkerRegistry.FAILED, running.getStatus());
        assertEquals(WorkerRegistry.RUNNING, otherTask.getStatus());
        assertTrue(running.getResultText().contains("任务已暂停/停止"));
    }

    @Test
    void failByTaskReleasesLatchSoWaitersDoNotHang() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle running = handle("a_1", "t_1");
        registry.register(running);
        // 模拟 agent_wait 的等待者：interruptTask 置 FAILED 后应立即醒来
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> running.getCompletionLatch().await(5, TimeUnit.SECONDS));
            registry.failByTask("t_1", "任务已暂停/停止");
            assertTrue(future.get(3, TimeUnit.SECONDS), "dispose 后等待者应被释放");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== Handle role / finishedAt ====================

    @Test
    void handleRoleAccessibleAndLegacyConstructorDefaultsToEmpty() {
        assertEquals("资深调研员", new WorkerRegistry.Handle("a_1", "t_1", "资深调研员").getRole());
        assertEquals("", handle("a_2", "t_1").getRole());
    }

    @Test
    void finishedAtIsNullWhileRunningAndRecordedOnCompletionOnce() {
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        assertNull(h.getFinishedAt());
        h.completeFailure("失败原因");
        assertNotNull(h.getFinishedAt());
        Instant finished = h.getFinishedAt();
        // 幂等终结不覆盖已记录的终态时间
        h.completeSuccess();
        assertEquals(finished, h.getFinishedAt());
    }

    @Test
    void listByTaskSortsByStartedAtAscendingAndOnlyMatchingTask() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(handle("a_1", "t_1"));
        registry.register(handle("a_2", "t_1"));
        registry.register(handle("a_3", "t_2"));
        assertEquals(List.of("a_1", "a_2"),
                registry.listByTask("t_1").stream().map(WorkerRegistry.Handle::getAgentId).toList());
        assertTrue(registry.listByTask("t_9").isEmpty());
    }

    @Test
    void purgeExpiredRemovesOnlyOldTerminalHandles() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle oldDone = handle("a_1", "t_1");
        WorkerRegistry.Handle freshDone = handle("a_2", "t_1");
        WorkerRegistry.Handle running = handle("a_3", "t_1");
        registry.register(oldDone);
        registry.register(freshDone);
        registry.register(running);
        oldDone.completeSuccess();
        // 测试钩子：把终态时间拨老一小时，模拟 TTL 过期
        oldDone.setFinishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        freshDone.completeSuccess();
        // RUNNING 句柄即使被测试钩子改老终态时间也不清理
        running.setFinishedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        assertEquals(1, registry.purgeExpired(30));
        assertEquals(2, registry.size());
        assertNull(registry.get("a_1"));
        assertNotNull(registry.get("a_2"));
        assertNotNull(registry.get("a_3"));
        // 重复清理幂等
        assertEquals(0, registry.purgeExpired(30));
    }

    // ==================== TeamTool.formatNotification 边界 ====================

    @Test
    void formatNotificationCompressesNewlinesInSummaryButKeepsThemInResult() {
        String text = "第一行\n第二行\r\n第三行";
        String out = TeamTool.formatNotification("a_1", "completed", text);
        assertTrue(out.contains("<summary>第一行 第二行 第三行</summary>"), out);
        assertTrue(out.contains("<result>第一行\n第二行\r\n第三行</result>"), out);
    }

    @Test
    void formatNotificationTruncatesSummaryAt500() {
        String text = "x".repeat(600);
        String out = TeamTool.formatNotification("a_1", "completed", text);
        String expectedSummary = "x".repeat(500) + "…";
        assertTrue(out.contains("<summary>" + expectedSummary + "</summary>"), out);
        // result 未超 4000 上限，保持完整
        assertTrue(out.contains("<result>" + text + "</result>"), out);
    }

    @Test
    void formatNotificationTruncatesResultAt4000() {
        String text = "y".repeat(5000);
        String out = TeamTool.formatNotification("a_1", "failed", text);
        String expectedResult = "y".repeat(4000) + "…";
        assertTrue(out.contains("<result>" + expectedResult + "</result>"), out);
    }

    @Test
    void formatNotificationRunningHintKeepsFullStructure() {
        String out = TeamTool.formatNotification("a_9", "running", "仍在执行，可稍后再次 agent_wait");
        assertEquals("""
                <task-notification>
                <agent-id>a_9</agent-id>
                <status>running</status>
                <summary>仍在执行，可稍后再次 agent_wait</summary>
                <result>仍在执行，可稍后再次 agent_wait</result>
                </task-notification>""", out);
    }

    @Test
    void formatNotificationNullTextIsSafe() {
        String out = TeamTool.formatNotification("a_1", "completed", null);
        assertTrue(out.contains("<summary></summary>"), out);
        assertTrue(out.contains("<result></result>"), out);
        assertTrue(out.contains("<status>completed</status>"), out);
    }

    // ==================== TeamTool.agent_stop 决策与执行 ====================

    @Test
    void stopAgentUnknownReturnsError() {
        assertEquals("错误：worker a_9 不存在或已清理",
                TeamTool.stopAgent(new WorkerRegistry(), new ConcurrentHashMap<>(), "a_9"));
    }

    @Test
    void stopAgentTerminalHandleReturnsNoNeedMessage() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle done = handle("a_1", "t_1");
        registry.register(done);
        done.completeSuccess();
        assertEquals("worker a_1 已处于终态（SUCCESS），无需停止",
                TeamTool.stopAgent(registry, new ConcurrentHashMap<>(), "a_1"));
    }

    @Test
    void stopAgentRunningFailsHandleAndIsIdempotent() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle running = handle("a_1", "t_1");
        registry.register(running);
        assertEquals("已停止 worker a_1", TeamTool.stopAgent(registry, new ConcurrentHashMap<>(), "a_1"));
        assertEquals(WorkerRegistry.FAILED, running.getStatus());
        assertTrue(running.getResultText().contains("leader 主动停止"), running.getResultText());
        assertNotNull(running.getFinishedAt());
        // 幂等：已 FAILED 再停 → 终态提示，不重复写原因
        assertEquals("worker a_1 已处于终态（FAILED），无需停止",
                TeamTool.stopAgent(registry, new ConcurrentHashMap<>(), "a_1"));
    }

    @Test
    void stopAgentDisposesOnlyTargetSubscription() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRegistry.Handle h = handle("a_1", "t_1");
        registry.register(h);
        Map<String, ConcurrentHashMap<String, Disposable>> subs = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Disposable> taskSubs = new ConcurrentHashMap<>();
        AtomicInteger disposed = new AtomicInteger();
        taskSubs.put("a_1", () -> disposed.incrementAndGet());
        taskSubs.put("a_2", () -> disposed.incrementAndGet());
        subs.put("t_1", taskSubs);
        assertEquals("已停止 worker a_1", TeamTool.stopAgent(registry, subs, "a_1"));
        assertEquals(1, disposed.get());
        // 仅移除目标订阅，同任务其他 worker 的订阅不受影响
        assertEquals(1, taskSubs.size());
        assertTrue(taskSubs.containsKey("a_2"));
    }

    // ==================== TeamTool.formatAgentList 组装 ====================

    @Test
    void formatAgentListRendersFieldsWithBlankLineSeparation() {
        WorkerRegistry.Handle h1 = new WorkerRegistry.Handle("a_1", "t_1", "调研员");
        h1.appendResult("第一行\n第二行" + "x".repeat(300));
        WorkerRegistry.Handle h2 = new WorkerRegistry.Handle("a_2", "t_1", "编码员");
        String out = TeamTool.formatAgentList(List.of(h1, h2));
        assertTrue(out.startsWith("worker: a_1\nrole: 调研员\nstatus: RUNNING\nstartedAt: "), out);
        // 摘要：换行压缩为空格（7 字）+ 前 200 字截断（193 个 x）加「…」
        assertTrue(out.contains("summary: 第一行 第二行" + "x".repeat(193) + "…"), out);
        // 空输出占位；条目间空行分隔
        assertTrue(out.contains("\n\nworker: a_2\nrole: 编码员\nstatus: RUNNING\nstartedAt: "), out);
        assertTrue(out.contains("summary: （暂无输出）"), out);
    }
}
