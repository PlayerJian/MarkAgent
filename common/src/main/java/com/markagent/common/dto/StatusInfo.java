package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 健康状态（免认证） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusInfo(
        String version,
        String provider,
        String defaultModel,
        int runningTasks,
        String storageRoot,
        String uptime
) {
}
