package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 统一分页响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageDto<T>(List<T> items, long total, int page, int size) {
}
