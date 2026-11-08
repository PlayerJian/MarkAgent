package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 上下文使用量（token 事件的载荷 / GET /sessions/{id}/context 响应） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextUsage(
        long usedTokens,
        long budget,
        double percent,       // 0.0 ~ 1.0
        int windowSize,       // 当前窗口消息条数
        String lastCompactionAt
) {
}
