package com.markagent.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.agent.AgentChatService;
import com.markagent.common.dto.ChatRequest;
import com.markagent.common.dto.TaskStatus;
import com.markagent.store.AtomicJsonFile;
import com.markagent.store.TaskStore;
import com.markagent.util.Ids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务（cron）体系核心。
 * <ul>
 *   <li>持久化：data/cron/jobs.json 单文件整存整取（写走 {@link AtomicJsonFile}，进程内锁串行读改写）；构造时加载到内存</li>
 *   <li>CRUD：create/list/get/delete/setEnabled，全部按 userId 隔离；cron 非法抛 IllegalArgumentException 由上层转错误文本</li>
 *   <li>调度：@PostConstruct 启动守护虚拟线程，唤醒延迟 = min(最近到期剩余, 60s)（不低于每分钟醒来一次），
 *       到期任务经防重叠检查（同 sessionId 已有 RUNNING 则跳过并顺延）后触发，触发后重算 nextRunAt 落盘；
 *       任何触发/落盘异常只记日志，绝不影响调度循环存活</li>
 *   <li>开关：markagent.cron.enabled=false 时不启动循环，CRUD 照常可用</li>
 * </ul>
 */
@Service
public class CronJobService {

    private static final Logger log = LoggerFactory.getLogger(CronJobService.class);

    /** 调度巡检周期上限（毫秒）：保证醒来频率不低于每分钟一次 */
    static final long DEFAULT_POLL_INTERVAL_MS = 60_000;

    private static final String FILE_NAME = "jobs.json";

    /** 文件容器：{jobs:[...]} 整存整取 */
    public record CronJobsFile(List<CronJob> jobs) {
    }

    private final Path jobsFile;
    private final ObjectMapper mapper;
    private final TaskStore taskStore;
    private final CronTrigger trigger;
    private final boolean schedulingEnabled;
    private final long pollIntervalMs;

    /** 内存列表与文件读改写互斥（volatile 引用供读方无锁快照） */
    private final Object lock = new Object();
    private volatile List<CronJob> jobs = List.of();
    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    /** Spring 装配入口：默认触发器 = AgentChatService.start（会话 id 由 fireDue 每次触发换新）。
     *  chatService 加 @Lazy 断开循环依赖（agentChatService → toolkit → cronTool → cronJobService），
     *  代理在触发时才解析，启动期无需真实实例。 */
    @Autowired
    public CronJobService(Path storageRoot, ObjectMapper mapper, TaskStore taskStore,
            @Lazy AgentChatService chatService,
            @Value("${markagent.cron.enabled:true}") boolean schedulingEnabled) {
        this(storageRoot, mapper, taskStore,
                job -> chatService.start(new ChatRequest(job.sessionId(), job.prompt(), null, null), job.userId()),
                schedulingEnabled, DEFAULT_POLL_INTERVAL_MS);
    }

    /** 测试入口（包级）：注入假触发器与巡检周期，不依赖 Spring 容器 */
    CronJobService(Path storageRoot, ObjectMapper mapper, TaskStore taskStore, CronTrigger trigger,
            boolean schedulingEnabled, long pollIntervalMs) {
        this.jobsFile = storageRoot.resolve("cron").resolve(FILE_NAME);
        this.mapper = mapper;
        this.taskStore = taskStore;
        this.trigger = trigger;
        this.schedulingEnabled = schedulingEnabled;
        this.pollIntervalMs = Math.max(1, pollIntervalMs);
        loadFromDisk();
    }

    /** 启动调度循环：enabled=false 时只保留 CRUD；重复调用幂等 */
    @PostConstruct
    public void startScheduler() {
        if (!schedulingEnabled) {
            log.info("定时任务调度已禁用（markagent.cron.enabled=false），仅保留 CRUD");
            return;
        }
        if (!schedulerRunning.compareAndSet(false, true)) {
            return;
        }
        // 虚拟线程天然守护线程，随 JVM 退出，无需显式停机
        Thread.ofVirtual().name("cron-scheduler").start(this::schedulerLoop);
        log.info("定时任务调度循环已启动（{}，已加载 {} 个任务）", jobsFile, jobs.size());
    }

    /** 停止调度循环（测试与优雅停机用） */
    void stopScheduler() {
        schedulerRunning.set(false);
    }

    /** 调度循环是否在跑（测试断言用） */
    boolean schedulerRunning() {
        return schedulerRunning.get();
    }

    /** 全局调度开关状态（工具列表响应附提示用） */
    public boolean schedulingEnabled() {
        return schedulingEnabled;
    }

    // ==================== CRUD ====================

    /**
     * 创建任务：会话由系统生成（同一 job 的每次触发换新会话，防 AgentScope 状态复用）。
     * cron 非法或 name/prompt 为空抛 IllegalArgumentException（上层转错误文本）。
     */
    public CronJob create(String userId, String name, String cronExpression, String prompt) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务目标（prompt）不能为空");
        }
        CronExpression expression = CronScheduling.parse(cronExpression);
        Instant now = Instant.now();
        CronJob job = new CronJob(Ids.next("cron_"), userId, name.trim(), cronExpression.trim(), prompt,
                Ids.session(), true, null, CronScheduling.nextRun(expression, now), now);
        synchronized (lock) {
            List<CronJob> updated = new ArrayList<>(jobs);
            updated.add(job);
            persist(updated);
            jobs = updated;
        }
        return job;
    }

    /** 当前用户全部定时任务（内存快照，无 IO） */
    public List<CronJob> list(String userId) {
        return jobs.stream().filter(job -> userId.equals(job.userId())).toList();
    }

    /** 按归属查单个任务（REST 归属校验用） */
    public Optional<CronJob> get(String userId, String id) {
        return jobs.stream().filter(job -> userId.equals(job.userId()) && id.equals(job.id())).findFirst();
    }

    /** 删除：不存在或非本人返回 false（静默，不泄露他人任务存在性） */
    public boolean delete(String userId, String id) {
        synchronized (lock) {
            List<CronJob> updated = jobs.stream()
                    .filter(job -> !(userId.equals(job.userId()) && id.equals(job.id())))
                    .toList();
            if (updated.size() == jobs.size()) {
                return false;
            }
            persist(updated);
            jobs = updated;
            return true;
        }
    }

    /**
     * 启停：enable 时从当前时刻重算 nextRunAt（避免积压补跑），disable 时置空 nextRunAt。
     * 不存在或非本人返回 Optional.empty。
     */
    public Optional<CronJob> setEnabled(String userId, String id, boolean enabled) {
        synchronized (lock) {
            Optional<CronJob> found = jobs.stream()
                    .filter(job -> userId.equals(job.userId()) && id.equals(job.id()))
                    .findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            CronJob current = found.get();
            Instant next = enabled
                    ? CronScheduling.nextRun(CronScheduling.parse(current.cronExpression()), Instant.now())
                    : null;
            CronJob updatedJob = current.with(enabled, current.lastRunAt(), next);
            List<CronJob> updated = jobs.stream()
                    .map(job -> job.id().equals(id) ? updatedJob : job)
                    .toList();
            persist(updated);
            jobs = updated;
            return Optional.of(updatedJob);
        }
    }

    // ==================== 调度循环 ====================

    private void schedulerLoop() {
        log.info("cron 调度循环线程启动（巡检周期 {}ms）", pollIntervalMs);
        while (schedulerRunning.get()) {
            try {
                Thread.sleep(CronScheduling.sleepMillis(jobs, Instant.now(), pollIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                tickOnce(Instant.now());
            } catch (Exception e) {
                log.error("cron 调度巡检异常（循环继续）: {}", e.getMessage(), e);
            }
        }
        log.info("cron 调度循环线程退出");
    }

    /** 单次调度巡检（包级可见：单测直接驱动，跳过睡眠） */
    void tickOnce(Instant now) {
        List<CronJob> due;
        synchronized (lock) {
            due = CronScheduling.dueJobs(jobs, now);
        }
        for (CronJob job : due) {
            fireDue(job, now);
        }
    }

    /**
     * 触发单个到期任务：防重叠 → 换新会话触发 → 推进 nextRunAt 落盘；任何异常只记日志，绝不上抛。
     * <p>
     * 每次触发换新 sessionId：AgentScope 按 (userId, sessionId) 持久化 AgentState，复用旧会话会
     * 加载上次运行的状态（curIter 耗尽后新触发立即终止）。防重叠判定基于「当前运行」的会话：
     * 新 sessionId 在触发后立即落盘，下一轮巡检即可比对到仍在 RUNNING 的任务。
     */
    private void fireDue(CronJob job, Instant now) {
        String runSessionId = Ids.session();
        try {
            if (CronScheduling.overlaps(taskStore.list(job.userId(), TaskStatus.RUNNING), job.sessionId())) {
                log.info("cron 跳过本次（同会话任务仍在运行） job={} sessionId={}", job.id(), job.sessionId());
                advance(job, now, false, null); // 只顺延 nextRunAt，不改 lastRunAt 与会话
                return;
            }
            trigger.fire(job.withSessionId(runSessionId));
            advance(job, now, true, runSessionId);
        } catch (Exception e) {
            log.error("cron 触发失败 job={}: {}", job.id(), e.getMessage(), e);
            try {
                // 失败同样顺延（避免每轮重试打爆模型通道）；会话仍更新：
                // 若任务实际已启动（fire 中途异常），后续防重叠判定才能命中该运行
                advance(job, now, true, runSessionId);
            } catch (Exception persistError) {
                log.error("cron 顺延落盘失败 job={}: {}", job.id(), persistError.getMessage(), persistError);
            }
        }
    }

    /**
     * 推进计划时间并落盘：ran=true 时记 lastRunAt=now；nextRunAt 一律从 now 起算（顺延语义）。
     * newSessionId 非空时一并替换会话 id（触发换新会话）。
     */
    private void advance(CronJob job, Instant now, boolean ran, String newSessionId) {
        synchronized (lock) {
            Optional<CronJob> current = jobs.stream().filter(j -> j.id().equals(job.id())).findFirst();
            // 已被删除或启停状态已变（如触发瞬间被 disable）则不动，避免复活已停用任务
            if (current.isEmpty() || current.get().enabled() != job.enabled()) {
                return;
            }
            CronJob base = current.get();
            Instant next = CronScheduling.nextRun(CronScheduling.parse(base.cronExpression()), now);
            if (next == null) {
                log.warn("cron 表达式无下一匹配时间（如 2 月 31 日），任务挂起 job={}", job.id());
            }
            CronJob baseUpdated = base.with(base.enabled(), ran ? now : base.lastRunAt(), next);
            CronJob updated = newSessionId != null ? baseUpdated.withSessionId(newSessionId) : baseUpdated;
            List<CronJob> list = jobs.stream().map(j -> j.id().equals(job.id()) ? updated : j).toList();
            persist(list);
            jobs = list;
        }
    }

    // ==================== 持久化 ====================

    /** 启动/构造时从磁盘加载（文件缺失或损坏时从空列表起步，不阻塞服务启动） */
    private void loadFromDisk() {
        try {
            AtomicJsonFile file = new AtomicJsonFile(jobsFile);
            if (!file.exists()) {
                return;
            }
            CronJobsFile stored = file.readAs(mapper, CronJobsFile.class);
            jobs = stored == null || stored.jobs() == null ? List.of() : List.copyOf(stored.jobs());
        } catch (IOException e) {
            log.error("cron 任务加载失败 {}: {}", jobsFile, e.getMessage(), e);
            jobs = List.of();
        }
    }

    /** 整存落盘（失败只记日志，内存态继续可用——与 JsonFileTaskStore 同风格） */
    private void persist(List<CronJob> updated) {
        try {
            new AtomicJsonFile(jobsFile).writeJson(mapper, new CronJobsFile(updated));
        } catch (IOException e) {
            log.error("cron 任务落盘失败 {}: {}", jobsFile, e.getMessage(), e);
        }
    }
}
