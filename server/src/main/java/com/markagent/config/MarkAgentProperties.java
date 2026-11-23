package com.markagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Agent agent,
        Memory memory,
        Compact compact,
        SessionMemory sessionMemory,
        Audit audit,
        AskUser askUser,
        Inbox inbox,
        Docs docs
) {

    /** 显式声明绑定构造器：类上有旧签名兼容构造时，引导 Boot 固定走 canonical 构造绑定 */
    @ConstructorBinding
    public MarkAgentProperties {
    }

    /** 旧签名兼容构造（audit/ask-user/inbox/docs 置 null 走各自缺省归一），存量测试夹具无需改动 */
    public MarkAgentProperties(Storage storage, Llm llm, Embedding embedding, Context context,
            Security security, Web web, Mcp mcp, Agent agent, Memory memory, Compact compact,
            SessionMemory sessionMemory) {
        this(storage, llm, embedding, context, security, web, mcp, agent, memory, compact,
                sessionMemory, null, null, null, null);
    }

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

    /** 长期记忆索引：注入 prompt 的行数/字节硬截断保护与注入开关（非法值回退默认） */
    public record Memory(int indexMaxLines, int indexMaxBytes, Boolean injectIndex) {
        public Memory {
            indexMaxLines = indexMaxLines <= 0 ? 200 : indexMaxLines;
            indexMaxBytes = indexMaxBytes <= 0 ? 25000 : indexMaxBytes;
            injectIndex = injectIndex == null ? Boolean.TRUE : injectIndex;
        }
    }

    /** 自动上下文压缩：有效窗口 = maxTokens − reserveTokens；再留 bufferTokens 提前触发；连续失败熔断（非法值回退默认） */
    public record Compact(Boolean enabled, int reserveTokens, int bufferTokens, int maxConsecutiveFailures, int tailTokens) {
        public Compact {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            reserveTokens = reserveTokens <= 0 ? 20000 : reserveTokens;
            bufferTokens = bufferTokens <= 0 ? 13000 : bufferTokens;
            maxConsecutiveFailures = maxConsecutiveFailures <= 0 ? 3 : maxConsecutiveFailures;
            tailTokens = tailTokens <= 0 ? 8000 : tailTokens;
        }
    }

    /** 会话滚动记忆：起始阈值 initTokens / 增量阈值 deltaTokens / 工具调用间隔 toolCallInterval（非法值回退默认） */
    public record SessionMemory(Boolean enabled, long initTokens, long deltaTokens, int toolCallInterval) {
        public SessionMemory {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            initTokens = initTokens <= 0 ? 10000 : initTokens;
            deltaTokens = deltaTokens <= 0 ? 5000 : deltaTokens;
            toolCallInterval = toolCallInterval <= 0 ? 3 : toolCallInterval;
        }
    }

    /** 工具调用审计：data/audit/{yyyy-MM-dd}.jsonl 按日落盘，enabled=false 完全关闭 */
    public record Audit(Boolean enabled) {
        public Audit {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }

    /** 人工审批（ask_user 工具）：超时秒数与超时自动批准开关 */
    public record AskUser(int defaultTimeoutSeconds, Boolean autoApprove) {
        public AskUser {
            defaultTimeoutSeconds = defaultTimeoutSeconds <= 0 ? 120 : defaultTimeoutSeconds;
            autoApprove = autoApprove == null ? Boolean.FALSE : autoApprove;
        }
    }

    /** 跨会话信箱（send_message 工具）：cross-user=false 时仅允许发往当前用户自己的会话 */
    public record Inbox(Boolean crossUser) {
        public Inbox {
            crossUser = crossUser == null ? Boolean.TRUE : crossUser;
        }
    }

    /** 文档库：MinerU 解析服务（WSL 容器 file-parse-server）接入参数与解析并发（非法值回退默认）。
     *  overall-timeout-minutes 兜底大文件：CPU 小模型解析数百页财报可耗时数十分钟 */
    public record Docs(String mineruUrl, int waitTimeoutSeconds, int overallTimeoutMinutes, int parseConcurrency) {
        public Docs {
            mineruUrl = mineruUrl == null || mineruUrl.isBlank() ? "http://127.0.0.1:8003" : mineruUrl;
            waitTimeoutSeconds = waitTimeoutSeconds <= 0 ? 180 : waitTimeoutSeconds;
            overallTimeoutMinutes = overallTimeoutMinutes <= 0 ? 60 : overallTimeoutMinutes;
            parseConcurrency = parseConcurrency <= 0 ? 2 : Math.min(parseConcurrency, 8);
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
