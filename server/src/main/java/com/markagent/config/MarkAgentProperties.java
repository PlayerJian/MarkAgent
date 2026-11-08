package com.markagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * markagent.* 自定义配置。
 * 所有密钥一律环境变量注入，不落配置文件。
 */
@ConfigurationProperties(prefix = "markagent")
public record MarkAgentProperties(
        Storage storage,
        Llm llm,
        Embedding embedding,
        Context context,
        Security security,
        Web web,
        Mcp mcp,
        Agent agent
) {

    public record Storage(String root) {
        public Storage {
            root = root == null ? "./data" : root;
        }
    }

    /** 自装配 ReActAgent 的构建参数（不依赖 agentscope starter 的 agent.* 配置） */
    public record Agent(String name, String sysPrompt, int maxIters, long streamTimeoutMinutes) {
        public Agent {
            name = name == null ? "MarkAgent" : name;
            sysPrompt = sysPrompt == null ? "你是一名专业的企业级 AI 助手。" : sysPrompt;
            maxIters = maxIters <= 0 ? 30 : maxIters;
            streamTimeoutMinutes = streamTimeoutMinutes <= 0 ? 10 : streamTimeoutMinutes;
        }
    }

    /** 双通道：deepseek-direct（Spring AI 直连）| bailian（百炼，AgentScope dashscope starter） */
    public record Llm(String provider, String defaultModel) {
        public Llm {
            provider = provider == null ? "deepseek-direct" : provider;
            defaultModel = defaultModel == null ? "deepseek-flash" : defaultModel;
        }
    }

    public record Embedding(boolean enabled, String provider, String baseUrl, String model) {
        public Embedding {
            provider = provider == null ? "bailian-compatible" : provider;
            baseUrl = baseUrl == null ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl;
            model = model == null ? "text-embedding-v3" : model;
        }
    }

    public record Context(long maxTokens, double compactThreshold) {
        public Context {
            maxTokens = maxTokens <= 0 ? 16000 : maxTokens;
            compactThreshold = compactThreshold <= 0 ? 0.75 : compactThreshold;
        }
    }

    public record Security(Jwt jwt, BootstrapAdmin bootstrapAdmin) {
        public record Jwt(String secret, long ttlHours) {
            public Jwt {
                secret = secret == null ? "" : secret;
                ttlHours = ttlHours <= 0 ? 72 : ttlHours;
            }
        }

        public record BootstrapAdmin(String username, String password) {
            public BootstrapAdmin {
                username = username == null ? "admin" : username;
                password = password == null ? "admin123" : password;
            }
        }
    }

    public record Web(String staticLocations) {
        public Web {
            staticLocations = staticLocations == null ? "classpath:/static/" : staticLocations;
        }
    }

    public record Mcp(long stdioTimeoutMs) {
        public Mcp {
            stdioTimeoutMs = stdioTimeoutMs <= 0 ? 10000 : stdioTimeoutMs;
        }
    }
}
