package com.markagent.config;

import com.markagent.agent.model.SpringAiModelBridge;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * AgentScope 接线：我方自装配 ReActAgent（不使用 starter 的自动装配，因后者用 InMemoryMemory
 * 且无状态存储配置项）。要点：
 * <ul>
 *   <li>SpringAiModelBridge 注册为 Model SPI 实现（deepseek-direct 通道）</li>
 *   <li>JsonFileAgentStateStore 指向 data/agentscope：AgentScope 按 (userId, sessionId) 持久化
 *       AgentState（上下文/curIter/plan 上下文）→ 服务重启后 resume 仍可继续</li>
 *   <li>Toolkit 空注册（M4 注册内置工具，M7 注册 MCP）</li>
 * </ul>
 */
@Configuration
public class AgentScopeConfig {

    /** 存储根目录统一 bean（data/），各 JsonFile* 存储与事件流水共用 */
    @Bean
    public Path storageRoot(MarkAgentProperties props) {
        return Path.of(props.storage().root()).toAbsolutePath().normalize();
    }

    /**
     * deepseek-direct 通道：注册 Spring AI 桥为 AgentScope Model。
     * bailian 通道由 agentscope-dashscope starter 提供 Model，此处不创建（互斥）。
     */
    @Bean
    @ConditionalOnProperty(name = "markagent.llm.provider", havingValue = "deepseek-direct", matchIfMissing = true)
    public Model agentScopeModel(DeepSeekChatModel chatModel, DeepSeekApi deepSeekApi, ObjectMapper mapper,
            @Value("${markagent.llm.default-model:deepseek-flash}") String defaultModel) {
        return new SpringAiModelBridge(chatModel, deepSeekApi, mapper, defaultModel);
    }

    /** AgentScope 状态存储：JsonFile 实现，根 data/agentscope */
    @Bean
    public AgentStateStore agentStateStore(Path storageRoot) {
        return new JsonFileAgentStateStore(storageRoot.resolve("agentscope"));
    }

    /** 工具集：内置工具 + 计划工具 + 技能工具（M7 追加 MCP 工具）。
     *  经 MarkAgentToolRegistrar 注册——Toolkit.registerTool 的反射 schema 生成依赖
     *  jsonschema-generator 4.38 与 Spring AI 的 5.0.0 classpath 冲突，需绕开。 */
    @Bean
    public Toolkit toolkit(com.markagent.tools.CalculatorTool calculator,
            com.markagent.tools.DateTimeTool dateTime,
            com.markagent.tools.HttpRequestTool httpRequest,
            com.markagent.tools.VisionTool visionTool,
            com.markagent.tools.WebSearchTool webSearch,
            com.markagent.tools.WebFetchTool webFetch,
            com.markagent.tools.WorkspaceFileTool workspaceFile,
            com.markagent.tools.PlanTool planTool,
            com.markagent.tools.SkillTool skillTool,
            com.markagent.tools.MemoryTool memoryTool,
            com.markagent.tools.TeamTool teamTool,
            com.markagent.tools.FileSearchTool fileSearch,
            com.markagent.tools.FileEditTool fileEdit,
            com.markagent.ask.AskUserTool askUser,
            com.markagent.inbox.SendMessageTool sendMessage,
            com.markagent.tools.McpResourceTool mcpResource,
            com.markagent.cron.CronTool cronTool,
            com.markagent.integration.IntegrationTool integrationTool,
            com.markagent.tools.DocQueryTool docQuery,
            com.markagent.schema.SchemaCatalogTool schemaCatalogTool,
            com.markagent.mcp.McpServerManager mcpManager) {
        Toolkit toolkit = new Toolkit();
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, calculator);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, dateTime);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, httpRequest);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, visionTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, webSearch);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, webFetch);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, workspaceFile);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, planTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, skillTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, memoryTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, teamTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, fileSearch);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, fileEdit);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, askUser);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, sendMessage);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, mcpResource);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, cronTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, integrationTool);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, docQuery);
        com.markagent.tools.MarkAgentToolRegistrar.register(toolkit, schemaCatalogTool);
        // MCP 启动期注册：ReActAgent.Builder 深拷贝 toolkit，必须在 agent build 前完成
        mcpManager.attach(toolkit);
        return toolkit;
    }

    /**
     * 自装配 ReActAgent：stateStore 是断点恢复的关键——agent 在每次步骤边界
     * 将 AgentState 落盘，resume 时以同 (userId, sessionId) 调用即自动加载继续。
     */
    @Bean
    public ReActAgent reactAgent(Model agentScopeModel, Toolkit toolkit, AgentStateStore agentStateStore,
            com.markagent.mcp.McpServerManager mcpManager,
            @Value("${markagent.agent.name:MarkAgent}") String name,
            @Value("${markagent.agent.sys-prompt:你是一名专业的企业级 AI 助手。}") String sysPrompt,
            @Value("${markagent.agent.team-addendum:}") String teamAddendum,
            @Value("${markagent.agent.max-iters:30}") int maxIters) {
        // 多智能体团队编排补充提示词：配置非空时追加到 sysPrompt 末尾（换行分隔）
        String effectiveSysPrompt = (teamAddendum == null || teamAddendum.isBlank())
                ? sysPrompt
                : sysPrompt + "\n\n" + teamAddendum;
        ReActAgent agent = ReActAgent.builder()
                .name(name)
                .sysPrompt(effectiveSysPrompt)
                .model(agentScopeModel)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .stateStore(agentStateStore)
                .build();
        // 运行期 MCP 增删注册进 agent 的 toolkit 副本（builder 深拷贝，原 toolkit 不可达）
        mcpManager.bindAgentToolkit(agent.getToolkit());
        return agent;
    }
}
