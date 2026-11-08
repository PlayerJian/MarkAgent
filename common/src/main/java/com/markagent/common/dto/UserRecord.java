package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 用户记录（不含密码哈希） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRecord(String id, String username, String displayName, String createdAt) {
}
