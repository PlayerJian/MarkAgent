package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** LLM 全局设置（GET/PUT /settings/llm） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmSettings(
        String provider,                       // deepseek-direct | bailian
        String model,                          // 默认模型
        List<String> directModels,             // 直连通道可选模型
        EmbeddingSettings embedding
) {
    public record EmbeddingSettings(boolean enabled, String provider, String baseUrl, String model, boolean apiKeySet) {
    }
}
