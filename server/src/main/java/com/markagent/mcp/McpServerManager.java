package com.markagent.mcp;

import com.markagent.common.dto.McpServerConfig;
import com.markagent.store.McpConfigStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器管理器：stdio（本地命令进程）与 sse（远程端点）两种传输，
 * 全部走 AgentScope 的 McpClientBuilder/McpClientWrapper → Toolkit.registerMcpClient
 * （MCP 工具与内置工具一样进入 ReActAgent 的工具集）。
 * <p>
 * 生命周期：toolkit bean 构建时 attach(this) 并加载 data/mcp.json 中 enabled 的服务器——
 * ReActAgent.Builder 会深拷贝 toolkit，启动期的 MCP 注册必须在 agent build 之前完成；
 * 运行期经 REST API 增删改的服务器注册进 agent 自己的 toolkit（reactAgent.getToolkit()）。
 * client 名用配置 id（唯一）。
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final Duration OP_TIMEOUT = Duration.ofSeconds(30);

    private final McpConfigStore configStore;
    /** 启动期注册目标：toolkit bean 构建时 attach；agent 构建后经 bindAgentToolkit 换为 agent 副本 */
    private volatile Toolkit toolkit;
    /** id → 已连接 client */
    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();
    /** id → 最近错误信息（连接失败后保留，供前端展示） */
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public McpServerManager(McpConfigStore configStore) {
        this.configStore = configStore;
    }

    /** toolkit bean 构建时调用：绑定启动期注册目标并连接启用的服务器（早于 agent build） */
    public void attach(Toolkit toolkit) {
        this.toolkit = toolkit;
        for (McpServerConfig config : configStore.list()) {
            if (config.enabled()) {
                try {
                    connect(config);
                } catch (Exception e) {
                    errors.put(config.id(), e.getMessage());
                    log.warn("MCP 服务器启动连接失败 {}: {}", config.name(), e.getMessage());
                }
            }
        }
    }

    /** reactAgent bean 构建后调用：运行期增删 MCP 注册进 agent 的 toolkit 副本（ReActAgent.Builder 深拷贝） */
    public void bindAgentToolkit(Toolkit agentToolkit) {
        this.toolkit = agentToolkit;
        log.info("MCP 注册目标已绑定 agent toolkit（工具数 {}）", agentToolkit.getToolNames().size());
    }

    /** 连接并注册进 Toolkit（幂等：已连接先断开） */
    public synchronized McpClientWrapper connect(McpServerConfig config) {
        disconnect(config.id());
        Toolkit toolkit = this.toolkit;
        try {
            McpClientBuilder builder = McpClientBuilder.create(config.id());
            McpClientWrapper wrapper = switch (config.transport()) {
                case "sse" -> builder.sseTransport(config.url()).buildSync();
                case "stdio" -> builder
                        .stdioTransport(config.command(), config.args(), config.env() == null ? Map.of() : config.env())
                        .buildSync();
                default -> throw new IllegalArgumentException("不支持的 transport: " + config.transport());
            };
            wrapper.initialize().block(OP_TIMEOUT);
            toolkit.registerMcpClient(wrapper).block(OP_TIMEOUT);
            clients.put(config.id(), wrapper);
            errors.remove(config.id());
            log.info("MCP 服务器已连接 {} ({})，工具集: {}", config.name(), config.transport(), toolkit.getToolNames());
            return wrapper;
        } catch (Exception e) {
            errors.put(config.id(), e.getMessage());
            throw new IllegalStateException("MCP 连接失败: " + e.getMessage(), e);
        }
    }

    /** 断开并从 Toolkit 注销 */
    public synchronized void disconnect(String id) {
        McpClientWrapper wrapper = clients.remove(id);
        if (wrapper == null) {
            return;
        }
        try {
            Toolkit target = this.toolkit;
            if (target != null) {
                target.removeMcpClient(id).block(OP_TIMEOUT);
            }
        } catch (Exception e) {
            log.warn("MCP 注销失败 {}: {}", id, e.getMessage());
        }
        try {
            wrapper.close();
        } catch (Exception e) {
            log.warn("MCP 关闭失败 {}: {}", id, e.getMessage());
        }
    }

    /** 服务器当前状态（供列表接口） */
    public Map<String, Object> status(McpServerConfig config) {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean connected = clients.containsKey(config.id());
        status.put("connected", connected);
        status.put("error", errors.get(config.id()));
        if (connected) {
            status.put("toolCount", tools(config.id()).size());
        }
        return status;
    }

    /** 列出服务器工具（name + description + inputSchema） */
    public List<Map<String, Object>> tools(String id) {
        McpClientWrapper wrapper = clients.get(id);
        if (wrapper == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> tools = new ArrayList<>();
            wrapper.listTools().block(OP_TIMEOUT).forEach(tool -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", tool.name());
                item.put("description", tool.description() == null ? "" : tool.description());
                item.put("inputSchema", tool.inputSchema());
                tools.add(item);
            });
            tools.sort(Comparator.comparing(t -> String.valueOf(t.get("name"))));
            return tools;
        } catch (Exception e) {
            errors.put(id, e.getMessage());
            return List.of();
        }
    }

    /** 测试连接：listTools 能通即视为可用 */
    public Map<String, Object> test(String id) {
        McpClientWrapper wrapper = clients.get(id);
        if (wrapper == null) {
            throw new IllegalStateException("服务器未连接: " + id);
        }
        try {
            List<Map<String, Object>> tools = tools(id);
            return Map.of("ok", true, "toolCount", tools.size(),
                    "toolNames", tools.stream().map(t -> t.get("name")).limit(20).toList());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public boolean isConnected(String id) {
        return clients.containsKey(id);
    }
}
