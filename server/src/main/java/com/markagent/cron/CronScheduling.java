package com.markagent.cron;

import com.markagent.common.dto.TaskRecord;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 定时调度纯函数集合（无 IO、无状态），包级可见：单测直接覆盖判定逻辑。
 * cron 语义采用 Spring CronExpression：6 字段（秒 分 时 日 月 周），按服务器本地时区解释。
 */
final class CronScheduling {

    /** cron 格式说明（解析失败错误文本与工具描述共用） */
    static final String CRON_HELP =
            "cron 表达式为 6 字段格式（秒 分 时 日 月 周，按服务器本地时间），如「0 30 9 * * MON-FRI」表示工作日 09:30。";

    private CronScheduling() {
    }

    /** 解析并校验 6 字段 cron 表达式；非法（空、字段数不对、数值越界）抛 IllegalArgumentException（带格式说明） */
    static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空。" + CRON_HELP);
        }
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式无效：" + e.getMessage() + "。" + CRON_HELP, e);
        }
    }

    /**
     * 计算 after 之后的下一执行时间；不存在下一匹配（如 2 月 31 日）返回 null。
     * Instant 不支持 DayOfWeek 等日历字段，须经 ZonedDateTime 计算（parse(String) 同用系统默认时区）。
     */
    static Instant nextRun(CronExpression expression, Instant after) {
        ZonedDateTime next = expression.next(after.atZone(ZoneId.systemDefault()));
        return next == null ? null : next.toInstant();
    }

    /** 是否重叠：running 中已存在同 sessionId 的任务（同会话同一时刻只允许一个在跑） */
    static boolean overlaps(List<TaskRecord> running, String sessionId) {
        if (sessionId == null) {
            return false;
        }
        return running.stream().anyMatch(task -> sessionId.equals(task.sessionId()));
    }

    /** 当前应触发的任务：enabled 且 nextRunAt 非空且已到期（含顺延后的过期触发） */
    static List<CronJob> dueJobs(List<CronJob> jobs, Instant now) {
        return jobs.stream()
                .filter(CronJob::enabled)
                .filter(job -> job.nextRunAt() != null && !job.nextRunAt().isAfter(now))
                .toList();
    }

    /**
     * 计算下次唤醒延迟毫秒：min(最近到期任务的剩余时间, pollIntervalMs)，下限 1ms。
     * 无到期任务（或全部停用）时按 pollIntervalMs 整周期醒来——保证唤醒频率不低于每分钟一次。
     */
    static long sleepMillis(List<CronJob> jobs, Instant now, long pollIntervalMs) {
        Instant nearest = jobs.stream()
                .filter(CronJob::enabled)
                .map(CronJob::nextRunAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        if (nearest == null) {
            return pollIntervalMs;
        }
        long delay = Duration.between(now, nearest).toMillis();
        if (delay <= 0) {
            return 1;
        }
        return Math.min(delay, pollIntervalMs);
    }
}
