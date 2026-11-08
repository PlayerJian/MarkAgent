package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 会话消息。role: user | assistant | tool | system */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String id,
        String role,
        String content,
        String reasoning,     // 思考内容（思考模式开启时返回，可为空）
        List<ToolCallRef> toolCalls,
        String createdAt
) {
    public record ToolCallRef(String callId, String name, String argsJson, String resultSummary, boolean ok) {
    }
}
