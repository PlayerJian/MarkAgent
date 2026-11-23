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
        String updatedAt,
        String docLibraryId   // 会话级文档检索绑定（库 id 或临时上传 id）；后续轮次未带 source 时继承
) {
}
