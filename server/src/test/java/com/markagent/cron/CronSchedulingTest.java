package com.markagent.cron;

import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CronScheduling 纯函数单测：cron 解析/校验、下次执行时间计算、防重叠判定、
 * 到期筛选与唤醒延迟钳制（全部无 IO，不依赖 Spring 容器）。
 */
class CronSchedulingTest {

    /** 2026-11-16 为周一（2026-11-12 周四为基准） */
    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.systemDefault()).toInstant();
    }

    private static CronJob job(String id, boolean enabled, Instant nextRunAt) {
        return new CronJob(id, "u1", "任务" + id, "0 30 9 * * MON-FRI", "目标", "s_1",
                enabled, null, nextRunAt, Instant.now());
    }

    private static TaskRecord running(String taskId, String sessionId) {
        return new TaskRecord(taskId, sessionId, "u1", "goal", TaskStatus.RUNNING, null, List.of(), null,
                "ts", "ts", null);
    }

    // ==================== 解析与校验 ====================

    @Test
    void parseValidSixFieldExpression() {
        assertNotNull(CronScheduling.parse("0 30 9 * * MON-FRI"));
        // 前后空白应被容忍
        assertNotNull(CronScheduling.parse("  0 30 9 * * MON-FRI  "));
    }

    @Test
    void parseRejectsBlank() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CronScheduling.parse("  "));
        assertTrue(e.getMessage().contains("不能为空"));
        assertTrue(e.getMessage().contains("0 30 9 * * MON-FRI"));
    }

    @Test
    void parseRejectsWrongFieldCount() {
        // 5 字段（无秒）与 7 字段均非法
        assertThrows(IllegalArgumentException.class, () -> CronScheduling.parse("30 9 * * MON-FRI"));
        assertThrows(IllegalArgumentException.class, () -> CronScheduling.parse("0 0 30 9 * * MON-FRI 2026"));
    }

    @Test
    void parseRejectsGarbage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CronScheduling.parse("not-a-cron"));
        assertTrue(e.getMessage().contains("cron 表达式无效"));
        assertTrue(e.getMessage().contains("0 30 9 * * MON-FRI"));
    }

    // ==================== 下次执行时间 ====================

    @Test
    void nextRunOnWeekdayMorning() {
        CronExpression expr = CronScheduling.parse("0 30 9 * * MON-FRI");
        // 周一 00:00 之后 → 当天 09:30
        assertEquals(at(16, 9, 30), CronScheduling.nextRun(expr, at(16, 0, 0)));
        // 周一 09:30 整点已过（next 严格晚于入参）→ 周二 09:30
        assertEquals(at(17, 9, 30), CronScheduling.nextRun(expr, at(16, 9, 30)));
        // 周六 12:00 → 下周一 09:30
        assertEquals(at(16, 9, 30), CronScheduling.nextRun(expr, at(14, 12, 0)));
    }

    @Test
    void nextRunAlwaysStrictlyAfterInput() {
        CronExpression expr = CronScheduling.parse("0 0 9 * * *");
        Instant next = CronScheduling.nextRun(expr, at(16, 9, 0));
        assertTrue(next.isAfter(at(16, 9, 0)));
    }

    @Test
    void nextRunReturnsNullWhenNoMatchExists() {
        // 2 月 31 日永不存在 → 无下一匹配
        CronExpression expr = CronScheduling.parse("0 0 0 31 2 *");
        assertNull(CronScheduling.nextRun(expr, Instant.now()));
    }

    // ==================== 防重叠判定 ====================

    @Test
    void overlapsDetectsSameSessionRunningTask() {
        List<TaskRecord> running = List.of(running("t_1", "s_a"), running("t_2", "s_b"));
        assertTrue(CronScheduling.overlaps(running, "s_a"));
        assertFalse(CronScheduling.overlaps(running, "s_c"));
        assertFalse(CronScheduling.overlaps(List.of(), "s_a"));
        // 空 sessionId 永不判重
        assertFalse(CronScheduling.overlaps(running, null));
    }

    // ==================== 到期筛选 ====================

    @Test
    void dueJobsFiresOnlyEnabledAndDue() {
        Instant now = Instant.now();
        List<CronJob> jobs = List.of(
                job("j1", true, now.minusSeconds(5)),   // 到期 → 触发
                job("j2", true, now),                   // 恰好到期 → 触发（顺延语义）
                job("j3", true, now.plusSeconds(60)),   // 未到期 → 不触发
                job("j4", false, now.minusSeconds(5)),  // 停用 → 不触发
                job("j5", true, null));                 // 无计划 → 不触发
        List<CronJob> due = CronScheduling.dueJobs(jobs, now);
        assertEquals(List.of("j1", "j2"), due.stream().map(CronJob::id).toList());
    }

    // ==================== 唤醒延迟 ====================

    @Test
    void sleepMillisClampsToPollInterval() {
        Instant now = Instant.now();
        // 无任务 → 整周期
        assertEquals(60_000, CronScheduling.sleepMillis(List.of(), now, 60_000));
        // 已到期 → 立即醒来（下限 1ms）
        assertEquals(1, CronScheduling.sleepMillis(List.of(job("j1", true, now.minusSeconds(5))), now, 60_000));
        // 30 秒后到期 → 按到期剩余睡眠
        assertEquals(30_000, CronScheduling.sleepMillis(
                List.of(job("j1", true, now.plusSeconds(30))), now, 60_000));
        // 120 秒后到期 → 钳制到巡检周期（保证不低于每分钟醒来一次）
        assertEquals(60_000, CronScheduling.sleepMillis(
                List.of(job("j1", true, now.plusSeconds(120))), now, 60_000));
        // 停用任务不参与计算
        assertEquals(60_000, CronScheduling.sleepMillis(
                List.of(job("j1", false, now.minusSeconds(5))), now, 60_000));
    }
}
