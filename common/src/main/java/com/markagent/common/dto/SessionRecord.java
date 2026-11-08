package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 会话记录（data/sessions/{userId}/{sessionId}.json 的持久化 schema） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRecord(
        String id,
        String userId,
        String title,
        java.util.List<ChatMessage> messages,
        String createdAt,
        String updatedAt
) {
}
