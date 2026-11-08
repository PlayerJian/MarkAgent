package com.markagent.agent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI DeepSeekChatModel → AgentScope Model SPI 桥。
 * <p>
 * 职责：
 * <ul>
 *   <li>AgentScope Msg ⇄ Spring AI Message 双向转换（thinking 块输出给前端，同时作为
 *       reasoning_content 回传 API——官方 thinking_mode 指南要求携带 tools 的请求必须完整回传，
 *       否则 400）</li>
 *   <li>AgentScope ToolSchema → 动态 ToolCallback（call() 不执行——工具由 AgentScope 的 Toolkit 执行，
 *       桥只负责让模型"提出"工具调用）</li>
 *   <li>GenerateOptions → DeepSeekChatOptions（模型/温度/最大 token）</li>
 *   <li>Spring AI ChatResponse → AgentScope ChatResponse（文本增量 + 思考 + 工具调用块）</li>
 * </ul>
 * 该 bean 注册为 io.agentscope.core.model.Model，由 agentscope-spring-boot-starter 自动注入 ReActAgent。
 */
public class SpringAiModelBridge implements Model {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelBridge.class);

    private final DeepSeekChatModel chatModel;
    private final DeepSeekApi deepSeekApi;
    private final ObjectMapper objectMapper;
    private final String defaultModelName;

    public SpringAiModelBridge(DeepSeekChatModel chatModel, DeepSeekApi deepSeekApi,
            ObjectMapper objectMapper, String defaultModelName) {
        this.chatModel = chatModel;
        this.deepSeekApi = deepSeekApi;
        this.objectMapper = objectMapper;
        this.defaultModelName = defaultModelName;
    }

    @Override
    public String getModelName() {
        return defaultModelName;
    }

    @Override
    public int getContextWindowSize() {
        // DeepSeek 官方 64K 上下文；保守取 60K 预留预算余量
        return 60_000;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options) {
        // 优先级：任务上下文（AgentChatService 按 task.options.model 设置）> GenerateOptions > 默认
        String taskModel = TaskModelContext.get();
        String modelName = taskModel != null && !taskModel.isBlank()
                ? taskModel
                : options.getModelName() != null ? options.getModelName() : defaultModelName;
        DeepSeekApi.ChatModel requestedModel = DeepSeekModels.of(modelName);
        if (reasoningEnabled(options)) {
            // 思考开启时直连 DeepSeekApi 原始流：Spring AI 2.0.2 的 DeepSeekChatModel 流式
            // mapper 不解析 reasoning_content（仅聚合 call() 路径读取），绕开以保留思考增量
            return streamViaDeepSeekApi(messages, toolSchemas, options, requestedModel);
        }
        List<Message> springMessages = toSpringMessages(messages);
        DeepSeekChatOptions chatOptions = toChatOptions(toolSchemas, options);

        Prompt prompt = new Prompt(springMessages, chatOptions);
        return chatModel.stream(prompt)
                .map(this::toAgentScopeResponse)
                .filter(r -> r.getContent() != null && !r.getContent().isEmpty())
                .onErrorMap(e -> new IllegalStateException("DeepSeek 模型调用失败: " + e.getMessage(), e));
    }

    /**
     * 思考是否开启（flash/pro 均为开关式思考模型）：
     * AgentScope GenerateOptions 显式指定优先，其次任务级开关（TaskThinkingContext，Web 思考开关透传），
     * 两者均未指定时默认关闭。
     */
    private static boolean reasoningEnabled(GenerateOptions options) {
        if (options.getThinkingBudget() != null) {
            return options.getThinkingBudget() > 0;
        }
        if (options.getReasoningEffort() != null && !options.getReasoningEffort().isBlank()) {
            return true;
        }
        return Boolean.TRUE.equals(TaskThinkingContext.get());
    }

    // ---------- AgentScope → Spring AI ----------

    private List<Message> toSpringMessages(List<Msg> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Msg m : messages) {
            String text = m.getTextContent();
            switch (m.getRole()) {
                case USER -> out.add(new UserMessage(text == null ? "" : text));
                case SYSTEM -> out.add(new SystemMessage(text == null ? "" : text));
                case ASSISTANT -> {
                    if (!skipAssistant(m)) {
                        out.add(toSpringAssistant(m));
                    }
                }
                case TOOL -> out.add(toSpringTool(m));
            }
        }
        return out;
    }

    /**
     * 不回传 API 的 assistant 消息：
     * <ul>
     *   <li>AgentScope 在 interrupt（pause/stop）时生成的收尾消息（generate_reason=INTERRUPTED）</li>
     *   <li>仅含 thinking 块的空正文消息（思考被中途打断）——DeepSeek API 校验拒绝
     *       content 为空字符串的 assistant 消息（实测 400）</li>
     * </ul>
     */
    private static boolean skipAssistant(Msg m) {
        if ("INTERRUPTED".equals(m.getMetadata().get(Msg.METADATA_GENERATE_REASON))) {
            return true;
        }
        String text = m.getTextContent();
        boolean hasToolCalls = !m.getContentBlocks(ToolUseBlock.class).isEmpty();
        return (text == null || text.isEmpty()) && !hasToolCalls;
    }

    private Message toSpringAssistant(Msg m) {
        String text = m.getTextContent();
        // thinking 块随 assistant 消息回传 API（官方：携带 tools 的请求必须完整回传 reasoning_content）
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ToolUseBlock block : m.getContentBlocks(ToolUseBlock.class)) {
            String argsJson;
            try {
                argsJson = objectMapper.writeValueAsString(block.getInput());
            } catch (Exception e) {
                argsJson = "{}";
            }
            toolCalls.add(new AssistantMessage.ToolCall(block.getId(), "function", block.getName(), argsJson));
        }
        String reasoning = thinkingText(m);
        DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder()
                .content(text == null ? "" : text);
        if (reasoning != null) {
            builder.reasoningContent(reasoning);
        }
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    /** 拼接 Msg 中的思考块文本作为 reasoning_content（无思考块时返回 null） */
    private static String thinkingText(Msg m) {
        StringBuilder sb = new StringBuilder();
        for (ThinkingBlock block : m.getContentBlocks(ThinkingBlock.class)) {
            if (block.getThinking() != null) {
                sb.append(block.getThinking());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private Message toSpringTool(Msg m) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (ToolResultBlock block : m.getContentBlocks(ToolResultBlock.class)) {
            String resultText;
            try {
                resultText = objectMapper.writeValueAsString(block.getOutput());
            } catch (Exception e) {
                resultText = String.valueOf(block.getOutput());
            }
            responses.add(new ToolResponseMessage.ToolResponse(block.getId(), block.getName(), resultText));
        }
        if (responses.isEmpty()) {
            // 兜底：整个消息文本作为一条无 id 的工具响应
            responses.add(new ToolResponseMessage.ToolResponse(m.getId(), "tool", m.getTextContent()));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    // ---------- AgentScope 选项 → DeepSeek 选项 ----------

    private DeepSeekChatOptions toChatOptions(List<ToolSchema> toolSchemas, GenerateOptions options) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();

        // 与 stream() 的模型解析优先级一致：任务上下文（task.options.model）> GenerateOptions > 默认
        String taskModel = TaskModelContext.get();
        String modelName = taskModel != null && !taskModel.isBlank()
                ? taskModel
                : options.getModelName() != null ? options.getModelName() : defaultModelName;
        DeepSeekApi.ChatModel chatModel = DeepSeekModels.of(modelName);
        builder.model(chatModel);
        applyThinking(builder, options);
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            List<ToolCallback> callbacks = new ArrayList<>(toolSchemas.size());
            for (ToolSchema schema : toolSchemas) {
                callbacks.add(toToolCallback(schema));
            }
            builder.toolCallbacks(callbacks);
        }
        return builder.build();
    }

    /**
     * 思考开关映射（AgentScope 框架语义 / 任务开关 → DeepSeek API）。
     * <p>
     * 两个来源：AgentScope {@link GenerateOptions}（thinkingBudget：token 预算，null=未指定，
     * &gt;0 开启、&lt;=0 关闭；reasoningEffort：推理强度）与任务级开关
     * {@link TaskThinkingContext}（Web 思考开关经 task.options.thinking 透传）。优先级：
     * GenerateOptions 显式指定 &gt; 任务开关 &gt; 默认关闭。
     * <p>
     * 注意必须显式设置：Spring AI 会把请求 options 与 ChatModel 默认 options 合并，若此处不设，
     * LlmConfig 默认的 disableThinking() 会漏进请求，导致 API 不返回 reasoning_content。
     */
    private void applyThinking(DeepSeekChatOptions.Builder builder, GenerateOptions options) {
        Integer thinkingBudget = options.getThinkingBudget();
        String reasoningEffort = options.getReasoningEffort();
        if (thinkingBudget != null) {
            if (thinkingBudget <= 0) {
                builder.disableThinking();
            } else {
                builder.enableThinking();
            }
        } else if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.enableThinking();
            DeepSeekApi.ChatCompletionRequest.ReasoningEffort effort = toReasoningEffort(reasoningEffort);
            if (effort != null) {
                builder.reasoningEffort(effort);
            } else {
                log.warn("不支持的 reasoningEffort 值 {}（Spring AI 仅建模 high/max），忽略该参数", reasoningEffort);
            }
        } else if (Boolean.TRUE.equals(TaskThinkingContext.get())) {
            builder.enableThinking();
        } else {
            builder.disableThinking(); // 默认关闭思考
        }
    }

    /** reasoningEffort 字符串 → Spring AI 枚举；仅 high/max 有对应建模，其余返回 null */
    private static DeepSeekApi.ChatCompletionRequest.ReasoningEffort toReasoningEffort(String effort) {
        return switch (effort.trim().toLowerCase()) {
            case "high" -> DeepSeekApi.ChatCompletionRequest.ReasoningEffort.HIGH;
            case "max" -> DeepSeekApi.ChatCompletionRequest.ReasoningEffort.MAX;
            default -> null;
        };
    }

    // ---------- 直连 DeepSeekApi 的流式通道（保留 reasoning_content） ----------

    /** 原始流：解析 delta.reasoningContent（thinking）/ delta.content（文本）/ tool_calls（累积，finish 时提交） */
    private Flux<ChatResponse> streamViaDeepSeekApi(List<Msg> messages, List<ToolSchema> toolSchemas,
            GenerateOptions options, DeepSeekApi.ChatModel requestedModel) {
        DeepSeekApi.ChatCompletionRequest request = buildRawRequest(messages, toolSchemas, options, requestedModel);
        Map<Integer, String[]> toolMeta = new ConcurrentHashMap<>(); // index → [id, name]
        Map<Integer, StringBuilder> toolArgBufs = new ConcurrentHashMap<>(); // index → 参数分片累积
        return deepSeekApi.chatCompletionStream(request)
                .map(chunk -> toAgentScopeResponse(chunk, toolMeta, toolArgBufs))
                .filter(r -> r.getContent() != null && !r.getContent().isEmpty())
                .onErrorMap(e -> new IllegalStateException("DeepSeek 模型调用失败: " + e.getMessage(), e));
    }

    private DeepSeekApi.ChatCompletionRequest buildRawRequest(List<Msg> messages, List<ToolSchema> toolSchemas,
            GenerateOptions options, DeepSeekApi.ChatModel requestedModel) {
        DeepSeekApi.ChatCompletionRequest.Builder builder = DeepSeekApi.ChatCompletionRequest.builder()
                .model(requestedModel.getValue())
                .stream(true)
                .messages(toRawMessages(messages));

        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        Integer thinkingBudget = options.getThinkingBudget();
        String reasoningEffort = options.getReasoningEffort();
        if (thinkingBudget != null) {
            builder.thinking(thinkingBudget <= 0
                    ? DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED
                    : DeepSeekApi.ChatCompletionRequest.Thinking.ENABLED);
        } else if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.thinking(DeepSeekApi.ChatCompletionRequest.Thinking.ENABLED);
            DeepSeekApi.ChatCompletionRequest.ReasoningEffort effort = toReasoningEffort(reasoningEffort);
            if (effort != null) {
                builder.reasoningEffort(effort);
            }
        } else if (TaskThinkingContext.get() != null) {
            // 任务级开关（Web 思考开关）：显式落 thinking 参数，与 reasoningEnabled 的通道选择一致
            builder.thinking(TaskThinkingContext.get()
                    ? DeepSeekApi.ChatCompletionRequest.Thinking.ENABLED
                    : DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED);
        }
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            builder.tools(toRawTools(toolSchemas));
        }
        return builder.build();
    }

    /** AgentScope Msg → DeepSeek 原始消息（assistant 带 toolCalls，tool 拆为逐条 toolCallId 响应） */
    private List<DeepSeekApi.ChatCompletionMessage> toRawMessages(List<Msg> messages) {
        List<DeepSeekApi.ChatCompletionMessage> out = new ArrayList<>(messages.size());
        for (Msg m : messages) {
            String content = m.getTextContent() == null ? "" : m.getTextContent();
            switch (m.getRole()) {
                case USER -> out.add(new DeepSeekApi.ChatCompletionMessage(content,
                        DeepSeekApi.ChatCompletionMessage.Role.USER));
                case SYSTEM -> out.add(new DeepSeekApi.ChatCompletionMessage(content,
                        DeepSeekApi.ChatCompletionMessage.Role.SYSTEM));
                case ASSISTANT -> {
                    if (skipAssistant(m)) {
                        break;
                    }
                    List<DeepSeekApi.ChatCompletionMessage.ToolCall> toolCalls = new ArrayList<>();
                    for (ToolUseBlock block : m.getContentBlocks(ToolUseBlock.class)) {
                        toolCalls.add(new DeepSeekApi.ChatCompletionMessage.ToolCall(block.getId(), "function",
                                new DeepSeekApi.ChatCompletionMessage.ChatCompletionFunction(
                                        block.getName(), toJson(block.getInput()))));
                    }
                    // reasoning_content 随 assistant 消息完整回传（官方：携带 tools 的请求漏传会 400）
                    out.add(new DeepSeekApi.ChatCompletionMessage(content,
                            DeepSeekApi.ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, null,
                            thinkingText(m)));
                }
                case TOOL -> {
                    List<ToolResultBlock> results = m.getContentBlocks(ToolResultBlock.class);
                    if (results.isEmpty()) {
                        out.add(new DeepSeekApi.ChatCompletionMessage(content,
                                DeepSeekApi.ChatCompletionMessage.Role.TOOL, null, m.getId(), List.of()));
                    } else {
                        for (ToolResultBlock block : results) {
                            out.add(new DeepSeekApi.ChatCompletionMessage(toJson(block.getOutput()),
                                    DeepSeekApi.ChatCompletionMessage.Role.TOOL, null, block.getId(), List.of()));
                        }
                    }
                }
            }
        }
        return out;
    }

    private List<DeepSeekApi.FunctionTool> toRawTools(List<ToolSchema> toolSchemas) {
        List<DeepSeekApi.FunctionTool> tools = new ArrayList<>(toolSchemas.size());
        for (ToolSchema schema : toolSchemas) {
            Map<String, Object> parameters = schema.getParameters() == null ? Map.of() : schema.getParameters();
            tools.add(new DeepSeekApi.FunctionTool(new DeepSeekApi.FunctionTool.Function(
                    schema.getName(), schema.getDescription() == null ? "" : schema.getDescription(),
                    parameters, null)));
        }
        return tools;
    }

    /** 原始 chunk → AgentScope ChatResponse：reasoning/text 增量即发，tool_calls 累积到 finish 一次性提交 */
    private ChatResponse toAgentScopeResponse(DeepSeekApi.ChatCompletionChunk chunk,
            Map<Integer, String[]> toolMeta, Map<Integer, StringBuilder> toolArgBufs) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (DeepSeekApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            DeepSeekApi.ChatCompletionMessage delta = choice.delta();
            if (delta == null) {
                continue;
            }
            String reasoning = delta.reasoningContent();
            if (reasoning != null && !reasoning.isEmpty()) {
                blocks.add(ThinkingBlock.builder().thinking(reasoning).build());
            }
            String content = delta.content();
            if (content != null && !content.isEmpty()) {
                blocks.add(TextBlock.builder().text(content).build());
            }
            if (delta.toolCalls() != null) {
                for (DeepSeekApi.ChatCompletionMessage.ToolCall tc : delta.toolCalls()) {
                    int index = tc.index() == null ? 0 : tc.index();
                    if (tc.id() != null && !tc.id().isEmpty()) {
                        toolMeta.put(index, new String[] { tc.id(),
                                tc.function() == null ? "" : tc.function().name() });
                    }
                    if (tc.function() != null && tc.function().arguments() != null) {
                        toolArgBufs.computeIfAbsent(index, k -> new StringBuilder())
                                .append(tc.function().arguments());
                    }
                }
            }
            if (choice.finishReason() == DeepSeekApi.ChatCompletionFinishReason.TOOL_CALLS
                    || choice.finishReason() == DeepSeekApi.ChatCompletionFinishReason.TOOL_CALL) {
                for (Map.Entry<Integer, String[]> entry : toolMeta.entrySet()) {
                    String[] meta = entry.getValue();
                    String argsJson = toolArgBufs.getOrDefault(entry.getKey(), new StringBuilder()).toString();
                    // content 必须带上参数 JSON：ToolExecutor 用 ToolUseBlock.getContent() 做 schema 校验输入
                    blocks.add(new ToolUseBlock(meta[0], meta[1], parseJson(argsJson), argsJson, null));
                }
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.builder().text("").build());
        }
        return ChatResponse.builder()
                .id(chunk.id() == null ? "" : chunk.id())
                .content(blocks)
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    /** 动态工具回调：仅声明 schema 让模型提出调用，不在此执行（AgentScope Toolkit 负责执行） */
    private ToolCallback toToolCallback(ToolSchema schema) {
        String inputSchema;
        try {
            inputSchema = objectMapper.writeValueAsString(
                    schema.getParameters() == null ? Map.of() : schema.getParameters());
        } catch (Exception e) {
            inputSchema = "{}";
        }
        ToolDefinition definition = ToolDefinition.builder()
                .name(schema.getName())
                .description(schema.getDescription() == null ? "" : schema.getDescription())
                .inputSchema(inputSchema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                // 不会走到这里：ReAct 循环由 AgentScope Toolkit 执行工具
                log.warn("桥接层收到工具执行请求 {}（应由 AgentScope Toolkit 执行）", schema.getName());
                return "{\"error\":\"executed by AgentScope toolkit\"}";
            }
        };
    }

    // ---------- Spring AI → AgentScope ----------

    private ChatResponse toAgentScopeResponse(org.springframework.ai.chat.model.ChatResponse response) {
        List<ContentBlock> blocks = new ArrayList<>();

        for (org.springframework.ai.chat.model.Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();

            // 思考内容（思考模式开启时返回）
            if (message instanceof DeepSeekAssistantMessage deepSeekMessage) {
                String reasoning = deepSeekMessage.getReasoningContent();
                if (reasoning != null && !reasoning.isEmpty()) {
                    blocks.add(ThinkingBlock.builder().thinking(reasoning).build());
                }
            }

            // 文本增量
            String text = message.getText();
            if (text != null && !text.isEmpty()) {
                blocks.add(TextBlock.builder().text(text).build());
            }

            // 工具调用
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                Map<String, Object> input;
                try {
                    input = objectMapper.readValue(toolCall.arguments(), new TypeReference<>() {
                    });
                } catch (Exception e) {
                    input = Map.of("_raw", toolCall.arguments());
                }
                // content 必须带上参数 JSON：ToolExecutor 用 ToolUseBlock.getContent() 做 schema 校验输入
                blocks.add(new ToolUseBlock(toolCall.id(), toolCall.name(), input, toolCall.arguments(), null));
            }
        }

        if (blocks.isEmpty()) {
            // 空块（如仅 usage 的收尾 chunk）用空文本占位，避免 NPE
            blocks.add(TextBlock.builder().text("").build());
        }
        return ChatResponse.builder()
                .id(response.getMetadata() != null && response.getMetadata().getId() != null
                        ? response.getMetadata().getId() : "")
                .content(blocks)
                .build();
    }
}
