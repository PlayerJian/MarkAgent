package com.markagent.config;

import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryTemplate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DeepSeek 直连通道的条件化装配。
 * 仅在 markagent.llm.provider=deepseek-direct 且 DEEPSEEK_API_KEY 存在时创建；
 * bailian 通道由 AgentScope dashscope starter 提供模型，此处不创建（互斥，避免 Bean 冲突）。
 */
@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "markagent.llm.provider", havingValue = "deepseek-direct", matchIfMissing = true)
    public DeepSeekApi deepSeekApi(
            @Value("${spring.ai.deepseek.api-key:}") String apiKey,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder) {
        return new DeepSeekApi(
                "https://api.deepseek.com",
                new org.springframework.ai.model.SimpleApiKey(apiKey),
                new org.springframework.http.HttpHeaders(),
                "/chat/completions",
                "/beta",
                restClientBuilder,
                webClientBuilder,
                new org.springframework.web.client.DefaultResponseErrorHandler());
    }

    @Bean
    @ConditionalOnProperty(name = "markagent.llm.provider", havingValue = "deepseek-direct", matchIfMissing = true)
    public DeepSeekChatModel deepSeekChatModel(
            DeepSeekApi deepSeekApi,
            @Value("${markagent.llm.default-model:deepseek-flash}") String model,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ObservationRegistry> observationRegistry) {

        DeepSeekApi.ChatModel chatModel = com.markagent.agent.model.DeepSeekModels.of(model);

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(chatModel)
                .disableThinking() // 默认关闭思考；开启时由 bridge 按任务开关/GenerateOptions 显式覆盖
                .build();

        return new DeepSeekChatModel(
                deepSeekApi,
                options,
                toolCallingManager.getIfAvailable(),
                retryTemplate.getIfAvailable(),
                observationRegistry.getIfAvailable());
    }
}
