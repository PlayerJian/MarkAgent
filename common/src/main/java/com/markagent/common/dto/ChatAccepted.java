package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /chat 的即时响应：任务已受理，流式走 /tasks/{id}/stream */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatAccepted(String taskId, String sessionId) {
}
