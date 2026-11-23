package com.markagent.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 工具调用审计记录（data/audit/{yyyy-MM-dd}.jsonl 的单行 schema） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditRecord(
        String taskId,
        String sessionId,
        String userId,
        String toolCallId,
        String toolName,
        String args,
        boolean ok,
        String resultSummary,
        long durationMs,
        Instant createdAt
) {

    /** args 截断上限（字符） */
    public static final int MAX_ARGS_LENGTH = 2000;
    /** resultSummary 截断上限（字符） */
    public static final int MAX_SUMMARY_LENGTH = 500;

    public AuditRecord {
        args = truncate(args, MAX_ARGS_LENGTH);
        resultSummary = truncate(resultSummary, MAX_SUMMARY_LENGTH);
    }

    /** 超长截断（null 原样保留），紧凑构造器内执行，任何构造入口（含 Jackson 反序列化）都生效 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
