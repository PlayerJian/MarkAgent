package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 长期记忆检索结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemorySearchResult(String content, double score, String source) {
}
