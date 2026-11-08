package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 断点检查点记录 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckpointRecord(
        long seq,
        String at,
        int stepIndex,
        String summary,
        String stateKey // AgentScope 状态的 (userId/sessionId) 引用
) {
}
