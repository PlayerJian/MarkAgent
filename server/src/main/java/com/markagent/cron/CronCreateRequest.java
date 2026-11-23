package com.markagent.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /api/v1/cron 请求体 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CronCreateRequest(
        String name,
        String cronExpression,
        String prompt
) {
}
