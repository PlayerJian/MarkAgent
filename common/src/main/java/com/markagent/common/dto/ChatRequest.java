package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /api/v1/chat 请求体：立即返回 {taskId}，流式走 /tasks/{id}/stream */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String sessionId,   // 为空则新建会话
        String goal,        // 任务描述（必填）
        TaskOptions options,
        DocSource source    // 文档库/单文件检索来源（可选；缺省为普通任务）
) {
}
