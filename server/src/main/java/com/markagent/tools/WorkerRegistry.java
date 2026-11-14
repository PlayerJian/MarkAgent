package com.markagent.tools;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 派生 worker 登记表：后台派生的 worker 完成前登记于此，leader 可经 agent_wait
 * 按 agentId 查询其状态与结果（可多次查询）；任务终结时 cleanup 按 taskId 整体移除。
 * agent_list 按任务列出全部句柄（startedAt 升序）；已终态句柄超过 TTL 后由
 * purgeExpired 清理（防止频繁派生任务的内存残留）。
 * <p>
 * 线程安全：登记表用 {@link ConcurrentHashMap}；单个 handle 的状态/结果文本/完成闩
 * 变更收敛在内部 synchronized 方法上，status 用 volatile 供查询线程无锁读。
 * 完成闩只在 RUNNING → 终态这一次跳变时 countDown（幂等），中断路径不会悬挂。
 */
public class WorkerRegistry {

    /** 派生中（worker 在跑） */
    public static final String RUNNING = "RUNNING";
    /** 正常完成 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败/超时/被终止 */
    public static final String FAILED = "FAILED";

    private final Map<String, Handle> byAgentId = new ConcurrentHashMap<>();

    /** worker 句柄：身份 + 角色 + 状态 + 结果文本 + 完成闩 */
    public static final class Handle {

        private final String agentId;
        private final String taskId;
        /** worker 角色定位（spawn_agent 传入；旧构造重载默认空串） */
        private final String role;
        private final Instant startedAt = Instant.now();
        private final CountDownLatch completionLatch = new CountDownLatch(1);
        /** 结果文本（appendResult 线程安全追加，完成前也可读到部分输出） */
        private final StringBuilder resultText = new StringBuilder();
        /** RUNNING → SUCCESS/FAILED 单向跳变；volatile 供跨线程无锁读 */
        private volatile String status = RUNNING;
        /** 终态时间：RUNNING → 终态跳变时记录；null 表示尚未终态（TTL 清理依据） */
        private volatile Instant finishedAt;

        /** 旧构造重载：role 默认空串（兼容既有调用与测试） */
        public Handle(String agentId, String taskId) {
            this(agentId, taskId, "");
        }

        public Handle(String agentId, String taskId, String role) {
            this.agentId = agentId;
            this.taskId = taskId;
            this.role = role == null ? "" : role;
        }

        public String getAgentId() {
            return agentId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getRole() {
            return role;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        /** 终态时间；null 表示尚未终态 */
        public Instant getFinishedAt() {
            return finishedAt;
        }

        /** 测试钩子：改写终态时间（仅测试构造「过期句柄」用，不改变状态） */
        void setFinishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }

        public String getStatus() {
            return status;
        }

        /** 完成闩：worker 完成/失败/被终止时 countDown，agent_wait 靠它限时等待 */
        public CountDownLatch getCompletionLatch() {
            return completionLatch;
        }

        /** 追加结果文本（线程安全） */
        public void appendResult(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            synchronized (this) {
                resultText.append(text);
            }
        }

        /** 结果文本快照（线程安全） */
        public String getResultText() {
            synchronized (this) {
                return resultText.toString();
            }
        }

        /** 成功终结：RUNNING → SUCCESS 并释放完成闩（已终结则幂等跳过） */
        public void completeSuccess() {
            complete(true, null);
        }

        /** 失败终结：RUNNING → FAILED、记录原因并释放完成闩（已终结则幂等跳过） */
        public void completeFailure(String reason) {
            complete(false, reason);
        }

        /** 尝试失败终结：本次完成 RUNNING → FAILED 跳变返回 true，已终态返回 false（agent_stop 判定竞态用） */
        public boolean tryFail(String reason) {
            return complete(false, reason);
        }

        private boolean complete(boolean success, String reason) {
            boolean transitioned = false;
            synchronized (this) {
                if (status == RUNNING) {
                    if (!success && reason != null && !reason.isBlank()) {
                        resultText.append('\n').append(reason);
                    }
                    status = success ? SUCCESS : FAILED;
                    finishedAt = Instant.now();
                    transitioned = true;
                }
            }
            if (transitioned) {
                // 先置状态再放闩：await 返回的线程必能读到终态与完整文本（happens-before）
                completionLatch.countDown();
            }
            return transitioned;
        }
    }

    /** 登记派生中的 worker（agentId 全局唯一，重复登记视为 bug 直接抛异常） */
    public void register(Handle handle) {
        Handle prev = byAgentId.putIfAbsent(handle.getAgentId(), handle);
        if (prev != null) {
            throw new IllegalStateException("worker 已登记: " + handle.getAgentId());
        }
    }

    /** 按 agentId 查询句柄；不存在返回 null */
    public Handle get(String agentId) {
        return byAgentId.get(agentId);
    }

    /** 移除任务下所有登记（任务终结 cleanup 用），返回移除数量 */
    public int removeByTask(String taskId) {
        int removed = 0;
        for (Map.Entry<String, Handle> entry : byAgentId.entrySet()) {
            if (entry.getValue().getTaskId().equals(taskId)
                    && byAgentId.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /** 任务名下所有登记句柄，按 startedAt 升序（同刻按 agentId 保稳定序）；不存在返回空列表 */
    public List<Handle> listByTask(String taskId) {
        return byAgentId.values().stream()
                .filter(h -> h.getTaskId().equals(taskId))
                .sorted(Comparator.comparing(Handle::getStartedAt).thenComparing(Handle::getAgentId))
                .toList();
    }

    /**
     * TTL 清理：移除「已终态且终态超过 ttlMinutes 分钟」的句柄（agent_list 与新派生时机调用，
     * 防止频繁派生任务的内存残留），返回清理数量。RUNNING 句柄不受影响。
     */
    public int purgeExpired(long ttlMinutes) {
        Instant cutoff = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
        int removed = 0;
        for (Map.Entry<String, Handle> entry : byAgentId.entrySet()) {
            Handle handle = entry.getValue();
            Instant finished = handle.getFinishedAt();
            if (handle.getStatus() != RUNNING && finished != null && finished.isBefore(cutoff)
                    && byAgentId.remove(entry.getKey(), handle)) {
                removed++;
            }
        }
        return removed;
    }

    /** 把任务下所有 RUNNING 句柄置 FAILED（任务 pause/stop 联动用），返回受影响数量 */
    public int failByTask(String taskId, String reason) {
        int failed = 0;
        for (Handle handle : byAgentId.values()) {
            if (handle.getTaskId().equals(taskId) && handle.getStatus() == RUNNING) {
                handle.completeFailure(reason);
                failed++;
            }
        }
        return failed;
    }

    /** 当前登记数量（观测/测试用） */
    public int size() {
        return byAgentId.size();
    }
}
