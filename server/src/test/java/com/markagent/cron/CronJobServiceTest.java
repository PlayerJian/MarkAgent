package com.markagent.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
import com.markagent.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CronJobService 单测：@TempDir 建 storageRoot，注入假触发器/假任务库/短巡检周期，
 * 覆盖 CRUD 往返、持久化重载、归属隔离、启停语义、调度开关与 tickOnce 触发判定。
 */
class CronJobServiceTest {

    private static final String CRON_WEEKDAY = "0 30 9 * * MON-FRI";

    @TempDir
    Path tmp;

    /** 测试用任务库：内存列表，支持按 (userId,status) 过滤（同 TaskStore 接口语义） */
    private static final class FakeTaskStore implements TaskStore {
        private final List<TaskRecord> records = new ArrayList<>();

        @Override
        public void save(TaskRecord task) {
            records.add(task);
        }

        @Override
        public Optional<TaskRecord> get(String taskId) {
            return records.stream().filter(t -> t.id().equals(taskId)).findFirst();
        }

        @Override
        public List<TaskRecord> list(String userId, TaskStatus status) {
            return records.stream()
                    .filter(t -> userId == null || userId.equals(t.userId()))
                    .filter(t -> status == null || status == t.status())
                    .toList();
        }

        @Override
        public void delete(String taskId) {
            records.removeIf(t -> t.id().equals(taskId));
        }
    }

    private final FakeTaskStore taskStore = new FakeTaskStore();
    /** 假触发器：记录每次触发的 job（并发安全，调度线程与断言线程共享） */
    private final List<CronJob> fired = new CopyOnWriteArrayList<>();
    private final CronTrigger trigger = fired::add;

    private static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /** 构造服务：schedulingEnabled=false + 不启动循环（CRUD/纯函数测试用，无后台线程干扰） */
    private CronJobService newService() {
        return new CronJobService(tmp, newMapper(), taskStore, trigger, false, 60_000);
    }

    // ==================== CRUD 往返 ====================

    @Test
    void createBuildsJobWithSchedule() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "工作日早报", CRON_WEEKDAY, "汇总昨日进展并汇报");
        assertTrue(job.id().startsWith("cron_"));
        assertEquals("u1", job.userId());
        assertEquals("工作日早报", job.name());
        assertEquals(CRON_WEEKDAY, job.cronExpression());
        assertTrue(job.enabled());
        assertNotNull(job.sessionId());
        assertNull(job.lastRunAt());
        assertNotNull(job.nextRunAt());
        assertTrue(job.nextRunAt().isAfter(Instant.now()));
        assertNotNull(job.createdAt());
        // 落盘文件存在
        assertTrue(Files.exists(tmp.resolve("cron").resolve("jobs.json")));
        // 归属隔离：他人列表为空
        assertEquals(List.of(job), svc.list("u1"));
        assertTrue(svc.list("u2").isEmpty());
    }

    @Test
    void createRejectsInvalidInput() {
        CronJobService svc = newService();
        IllegalArgumentException cron = assertThrows(IllegalArgumentException.class,
                () -> svc.create("u1", "n", "bad", "p"));
        assertTrue(cron.getMessage().contains("0 30 9 * * MON-FRI"));
        assertThrows(IllegalArgumentException.class, () -> svc.create("u1", "", CRON_WEEKDAY, "p"));
        assertThrows(IllegalArgumentException.class, () -> svc.create("u1", "n", CRON_WEEKDAY, "  "));
        assertThrows(IllegalArgumentException.class, () -> svc.create(null, "n", CRON_WEEKDAY, "p"));
        // 全部失败均未落盘
        assertTrue(svc.list("u1").isEmpty());
    }

    @Test
    void persistenceReloadsAcrossInstances() {
        CronJobService svc1 = newService();
        CronJob created = svc1.create("u1", "巡检", CRON_WEEKDAY, "检查服务健康");
        Instant nextRunAt = created.nextRunAt();

        // 新实例（模拟服务重启）从同一 storageRoot 加载：字段与 Instant 无损往返
        CronJobService svc2 = new CronJobService(tmp, newMapper(), taskStore, trigger, false, 60_000);
        List<CronJob> reloaded = svc2.list("u1");
        assertEquals(1, reloaded.size());
        assertEquals(created, reloaded.get(0));
        assertEquals(nextRunAt, reloaded.get(0).nextRunAt());
    }

    @Test
    void deleteOwnedOnly() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        // 他人删除 → 失败且任务保留
        assertFalse(svc.delete("u2", job.id()));
        assertEquals(1, svc.list("u1").size());
        // 本人删除 → 成功且持久化
        assertTrue(svc.delete("u1", job.id()));
        assertTrue(svc.list("u1").isEmpty());
        assertTrue(svc.list("u2").isEmpty());
        CronJobService reloaded = new CronJobService(tmp, newMapper(), taskStore, trigger, false, 60_000);
        assertTrue(reloaded.list("u1").isEmpty());
    }

    // ==================== 启停语义 ====================

    @Test
    void disableClearsNextRunAndEnableRecomputes() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        Instant originalNext = job.nextRunAt();

        CronJob disabled = svc.setEnabled("u1", job.id(), false).orElseThrow();
        assertFalse(disabled.enabled());
        assertNull(disabled.nextRunAt());
        assertNull(disabled.lastRunAt());

        CronJob enabled = svc.setEnabled("u1", job.id(), true).orElseThrow();
        assertTrue(enabled.enabled());
        assertNotNull(enabled.nextRunAt());
        // 重算基于当前时刻；CronExpression 精度为整秒，同一分钟内重算结果可能相同，故断言「不早于」
        assertFalse(enabled.nextRunAt().isBefore(originalNext));
    }

    @Test
    void setEnabledRejectsForeignJob() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        assertTrue(svc.setEnabled("u2", job.id(), false).isEmpty());
        assertTrue(svc.list("u1").get(0).enabled());
    }

    // ==================== 调度开关 ====================

    @Test
    void disabledSchedulingDoesNotStartLoop() {
        CronJobService svc = new CronJobService(tmp, newMapper(), taskStore, trigger, false, 60_000);
        svc.startScheduler();
        assertFalse(svc.schedulerRunning());
        // CRUD 仍可用
        svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        assertEquals(1, svc.list("u1").size());
        assertTrue(fired.isEmpty());
    }

    @Test
    void enabledSchedulingStartsLoop() {
        CronJobService svc = new CronJobService(tmp, newMapper(), taskStore, trigger, true, 60_000);
        svc.startScheduler();
        assertTrue(svc.schedulerRunning());
        svc.stopScheduler();
    }

    // ==================== tickOnce 触发判定 ====================

    /** 足够远的巡检时刻：必然越过下次计划时间 */
    private static Instant farFuture() {
        return Instant.now().plus(Duration.ofDays(8));
    }

    @Test
    void tickOnceFiresDueJobAndAdvancesSchedule() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        Instant tick = farFuture();

        svc.tickOnce(tick);

        assertEquals(List.of(job.id()), fired.stream().map(CronJob::id).toList());
        CronJob after = svc.list("u1").get(0);
        assertEquals(tick, after.lastRunAt());
        assertTrue(after.nextRunAt().isAfter(tick));
    }

    @Test
    void tickOnceSkipsFutureAndDisabledJobs() {
        CronJobService svc = newService();
        svc.create("u1", "未来任务", CRON_WEEKDAY, "目标");
        CronJob disabled = svc.create("u1", "停用任务", CRON_WEEKDAY, "目标");
        svc.setEnabled("u1", disabled.id(), false);

        // 立即巡检：未到期 → 不触发
        svc.tickOnce(Instant.now());
        assertTrue(fired.isEmpty());
        // 远未来巡检：停用任务（nextRunAt 已清空）→ 不触发；未到期任务在 tick 时刻已到期 → 触发
        svc.tickOnce(farFuture());
        assertEquals(1, fired.size());
        assertFalse(fired.stream().anyMatch(j -> j.id().equals(disabled.id())));
    }

    @Test
    void tickOnceSkipsOverlappingSessionAndDefersNextRun() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        // 同 sessionId 下已有 RUNNING 任务 → 判定重叠
        taskStore.save(new TaskRecord("t_x", job.sessionId(), "u1", "goal", TaskStatus.RUNNING,
                null, List.of(), null, "ts", "ts", null));
        Instant tick = farFuture();

        svc.tickOnce(tick);

        assertTrue(fired.isEmpty());
        CronJob after = svc.list("u1").get(0);
        assertNull(after.lastRunAt());          // 未执行：lastRunAt 不变
        assertTrue(after.nextRunAt().isAfter(tick)); // 已顺延到下一次计划
    }

    @Test
    void tickOnceSurvivesTriggerFailure() {
        AtomicBoolean called = new AtomicBoolean();
        CronTrigger failing = job -> {
            called.set(true);
            throw new RuntimeException("boom");
        };
        CronJobService svc = new CronJobService(tmp, newMapper(), taskStore, failing, false, 60_000);
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        Instant tick = farFuture();

        // 触发异常被吞掉：tickOnce 不抛、循环存活，且计划已顺延（避免每轮重试）
        assertDoesNotThrow(() -> svc.tickOnce(tick));
        assertTrue(called.get());
        CronJob after = svc.list("u1").get(0);
        assertEquals(tick, after.lastRunAt());
        assertTrue(after.nextRunAt().isAfter(tick));
        assertEquals(job.id(), after.id());
    }

    @Test
    void tickOnceIgnoresDeletedJobs() {
        CronJobService svc = newService();
        CronJob job = svc.create("u1", "任务", CRON_WEEKDAY, "目标");
        svc.delete("u1", job.id());
        assertDoesNotThrow(() -> svc.tickOnce(farFuture()));
        assertTrue(fired.isEmpty());
    }
}
