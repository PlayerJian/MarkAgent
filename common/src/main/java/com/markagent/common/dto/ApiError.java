package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 统一错误响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiError(String code, String message, String traceId) {
    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId);
    }
}
