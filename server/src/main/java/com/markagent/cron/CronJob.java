package com.markagent.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 定时任务持久化记录（data/cron/jobs.json 的 schema）。
 * Instant 字段经 JsonConfig 的 JavaTimeModule 序列化为 ISO-8601 字符串，跨重启无损往返。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CronJob(
        String id,
        String userId,
        String name,
        String cronExpression,
        String prompt,
        String sessionId,       // 当前/最近一次运行所用会话：每次触发换新（防 AgentScope 状态复用），防重叠判定基于它
        boolean enabled,
        Instant lastRunAt,      // 上次触发（尝试）时间；从未触发为 null
        Instant nextRunAt,      // 下次计划触发时间；停用或无下一匹配为 null
        Instant createdAt
) {
    /** 派生副本：更新调度三字段（enabled/lastRunAt/nextRunAt），其余字段不变 */
    public CronJob with(boolean enabled, Instant lastRunAt, Instant nextRunAt) {
        return new CronJob(id, userId, name, cronExpression, prompt, sessionId, enabled, lastRunAt, nextRunAt, createdAt);
    }

    /** 派生副本：仅替换会话 id（每次触发换新会话用） */
    public CronJob withSessionId(String newSessionId) {
        return new CronJob(id, userId, name, cronExpression, prompt, newSessionId, enabled, lastRunAt, nextRunAt, createdAt);
    }
}
