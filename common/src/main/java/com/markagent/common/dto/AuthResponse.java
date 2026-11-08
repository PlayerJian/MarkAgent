package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 登录/注册响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthResponse(String token, UserRecord user) {
}
