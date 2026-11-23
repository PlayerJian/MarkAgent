package com.markagent.cron;

import com.markagent.agent.model.TaskUserContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 定时任务工具：agent 可创建/查看/启停/删除当前用户的定时任务。
 * userId 由 MarkAgentToolRegistrar 从 RuntimeContext 注入工具执行线程（TaskUserContext）。
 * cron 表达式为 6 字段（秒 分 时 日 月 周），按服务器本地时间解释。
 */
@Component
public class CronTool {

    private final CronJobService service;

    public CronTool(CronJobService service) {
        this.service = service;
    }

    @Tool(name = "cron_create", description = "创建定时任务：到达指定时间自动执行目标文本（适合周期性汇报、定时巡检等）。"
            + "cron 表达式为 6 字段（秒 分 时 日 月 周，按服务器本地时间），如「0 30 9 * * MON-FRI」表示工作日 09:30。"
            + "prompt 需自包含（不依赖当前对话上下文），触发时由 agent 在新任务中执行。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String create(
            @ToolParam(name = "name", required = true, description = "任务名称（简短描述用途，如「工作日早报」）") String name,
            @ToolParam(name = "cron_expression", required = true,
                    description = "6 字段 cron 表达式：秒 分 时 日 月 周。示例「0 30 9 * * MON-FRI」= 工作日 09:30") String cronExpression,
            @ToolParam(name = "prompt", required = true,
                    description = "触发时交给 agent 执行的目标文本（自包含，不依赖对话上下文）") String prompt) {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        try {
            CronJob job = service.create(userId, name, cronExpression, prompt);
            return "已创建定时任务 " + job.id() + "：" + job.name()
                    + "，表达式 " + job.cronExpression()
                    + "，下次执行时间 " + fmt(job.nextRunAt()) + "（服务器本地时间）";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "cron_list", description = "列出当前用户的全部定时任务（含启停状态、cron 表达式与上/下次执行时间），"
            + "用于确认已有任务或获取 job_id。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String list() {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        List<CronJob> jobs = service.list(userId);
        if (jobs.isEmpty()) {
            return "（暂无定时任务）";
        }
        StringBuilder sb = new StringBuilder();
        for (CronJob job : jobs) {
            sb.append("- [").append(job.id()).append("] ").append(job.name())
                    .append(job.enabled() ? "（启用）" : "（停用）").append('\n')
                    .append("  cron: ").append(job.cronExpression()).append('\n')
                    .append("  prompt: ").append(truncate(job.prompt(), 100)).append('\n')
                    .append("  上次执行: ").append(fmt(job.lastRunAt()))
                    .append("，下次执行: ").append(fmt(job.nextRunAt()))
                    .append("（服务器本地时间）\n");
        }
        if (!service.schedulingEnabled()) {
            sb.append("注意：定时调度当前全局禁用（markagent.cron.enabled=false），任务不会自动执行。");
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "cron_disable", description = "停用指定定时任务（不再自动执行，任务保留可再次启用）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String disable(@ToolParam(name = "job_id", required = true,
            description = "定时任务 id（cron_list 可查）") String jobId) {
        return setEnabled(jobId, false);
    }

    @Tool(name = "cron_enable", description = "启用指定定时任务（从当前时刻起重新计算下次执行时间）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String enable(@ToolParam(name = "job_id", required = true,
            description = "定时任务 id（cron_list 可查）") String jobId) {
        return setEnabled(jobId, true);
    }

    @Tool(name = "cron_delete", description = "删除指定定时任务（不可恢复）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String delete(@ToolParam(name = "job_id", required = true,
            description = "定时任务 id（cron_list 可查）") String jobId) {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        if (!service.delete(userId, jobId)) {
            return "错误：定时任务 " + jobId + " 不存在";
        }
        return "已删除定时任务 " + jobId;
    }

    private String setEnabled(String jobId, boolean enabled) {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        return service.setEnabled(userId, jobId, enabled)
                .map(job -> enabled
                        ? "已启用定时任务 " + job.id() + "：" + job.name()
                                + "，下次执行时间 " + fmt(job.nextRunAt()) + "（服务器本地时间）"
                        : "已停用定时任务 " + job.id() + "：" + job.name() + "，不再自动执行")
                .orElse("错误：定时任务 " + jobId + " 不存在");
    }

    /** 时间展示：服务器本地时区 yyyy-MM-dd HH:mm:ss；null → "-" */
    private static String fmt(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(instant);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }
}
