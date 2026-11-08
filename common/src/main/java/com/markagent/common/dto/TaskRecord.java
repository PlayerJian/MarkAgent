package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 任务持久化记录（data/tasks/{taskId}.json 的 schema） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRecord(
        String id,
        String sessionId,
        String userId,
        String goal,
        TaskStatus status,
        PlanView plan,
        List<CheckpointRecord> checkpoints,
        TaskOptions options,
        String createdAt,
        String updatedAt,
        String errorMessage
) {
}
