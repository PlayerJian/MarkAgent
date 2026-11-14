package com.markagent.mcp;

import com.markagent.common.dto.McpServerConfig;
import com.markagent.store.McpConfigStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 服务器管理器：stdio（本地命令进程）与 sse（远程端点）两种传输，
 * 全部走 AgentScope 的 McpClientBuilder/McpClientWrapper → Toolkit.registerMcpClient
 * （MCP 工具与内置工具一样进入 ReActAgent 的工具集）。
 * <p>
 * 生命周期：toolkit bean 构建时 attach(this) 并加载 data/mcp.json 中 enabled 的服务器——
 * ReActAgent.Builder 会深拷贝 toolkit，启动期的 MCP 注册必须在 agent build 之前完成；
 * 运行期经 REST API 增删改的服务器注册进 agent 自己的 toolkit（reactAgent.getToolkit()）。
 * client 名用配置 id（唯一）。
 * <p>
 * 加固点（Claude Code MCP 客户端工程细节迁移）：
 * <ul>
 *   <li>工具名冲突自检：注册后对比前后差集 + 实例变化，检出与内置工具同名的裸名覆盖</li>
 *   <li>工具描述展示层截断（&gt; 2048 字符，追加 …）</li>
 *   <li>ConnectRetryGate 失败重试门：启动期自动连接 15 分钟内短路跳过</li>
 *   <li>启动期 attach 有界并发（默认 3），per-id 锁替代方法级 synchronized</li>
 * </ul>
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final Duration OP_TIMEOUT = Duration.ofSeconds(30);

    /** 工具描述截断上限：OpenAPI 派生 MCP 的描述可达 15-60KB，超限会塞爆模型上下文（Claude Code 经验值） */
    public static final int DESCRIPTION_MAX = 2048;
    private static final String MCP_NAME_PREFIX = "mcp__";

    private final McpConfigStore configStore;
    /** 启动期 MCP 连接并发上限 */
    private final int connectConcurrency;
    /** 连接失败重试门：15 分钟内自动路径跳过（手动 connect 不受限） */
    private final ConnectRetryGate retryGate = new ConnectRetryGate();

    /** 启动期注册目标：toolkit bean 构建时 attach；agent 构建后经 bindAgentToolkit 换为 agent 副本 */
    private volatile Toolkit toolkit;
    /** id → 已连接 client */
    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();
    /** id → 最近错误信息（连接失败后保留，供前端展示） */
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    /** id → per-id 锁：并发 attach 下同 id 的 connect/disconnect 互斥，不同 id 并行 */
    private final Map<String, Object> idLocks = new ConcurrentHashMap<>();

    public McpServerManager(McpConfigStore configStore,
            @Value("${markagent.mcp.connect-concurrency:3}") int connectConcurrency) {
        this.configStore = configStore;
        this.connectConcurrency = connectConcurrency;
    }

    /**
     * toolkit bean 构建时调用：绑定启动期注册目标并连接启用的服务器（早于 agent build）。
     * <p>
     * 有界并发（上限 {@code markagent.mcp.connect-concurrency}，默认 3）+ CountDownLatch
     * 等全部完成——后续 ReActAgent 构建依赖 toolkit 状态，attach 必须在 agent build 前
     * 收齐注册结果。单 server 失败不影响其他（错误进 errors map 供 UI 展示）。
     * 15 分钟内失败过的 server（重试门）跳过并保留 errors 里旧错误信息。
     */
    public void attach(Toolkit toolkit) {
        this.toolkit = toolkit;
        List<McpServerConfig> enabled = configStore.list().stream()
                .filter(McpServerConfig::enabled)
                .toList();
        if (enabled.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, connectConcurrency), r -> {
            Thread t = new Thread(r, "mcp-attach");
            t.setDaemon(true); // 不阻止 JVM 退出；正常路径 latch.await 会等完
            return t;
        });
        CountDownLatch latch = new CountDownLatch(enabled.size());
        try {
            for (McpServerConfig config : enabled) {
                if (retryGate.shouldSkip(config.id())) {
                    latch.countDown();
                    log.info("MCP 服务器 {} ({}) 15 分钟内连接失败过，启动期跳过自动重试", config.name(), config.id());
                    continue;
                }
                pool.submit(() -> {
                    try {
                        connect(config);
                    } catch (Exception e) {
                        log.warn("MCP 服务器启动连接失败 {}: {}", config.name(), e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 MCP 启动期连接被中断，已提交任务继续在后台完成");
        } finally {
            pool.shutdown();
        }
    }

    /** reactAgent bean 构建后调用：运行期增删 MCP 注册进 agent 的 toolkit 副本（ReActAgent.Builder 深拷贝） */
    public void bindAgentToolkit(Toolkit agentToolkit) {
        this.toolkit = agentToolkit;
        log.info("MCP 注册目标已绑定 agent toolkit（工具数 {}）", agentToolkit.getToolNames().size());
    }

    /**
     * 连接并注册进 Toolkit（幂等：已连接先断开）。
     * <p>
     * API 手动触发路径（用户显式操作）：不受重试门限制，总是尝试；
     * 成功 markSuccess 清除失败记录，失败也 markFailure（防止其它自动路径风暴）。
     * 同 id 的 connect/disconnect 走 per-id 锁互斥，不同 id 并行（框架侧 McpClientManager/
     * ToolRegistry 均为 ConcurrentHashMap，支持并发注册）。
     */
    public McpClientWrapper connect(McpServerConfig config) {
        synchronized (lockFor(config.id())) {
            return connectLocked(config);
        }
    }

    private McpClientWrapper connectLocked(McpServerConfig config) {
        disconnect(config.id()); // 同 id 锁可重入，无死锁；先清旧连接保证幂等
        Toolkit target = this.toolkit;
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
            Map<String, AgentTool> builtinBefore = snapshotTools(target);
            target.registerMcpClient(wrapper).block(OP_TIMEOUT);
            checkMcpToolCollision(target, builtinBefore);
            clients.put(config.id(), wrapper);
            errors.remove(config.id());
            retryGate.markSuccess(config.id());
            log.info("MCP 服务器已连接 {} ({})，工具集: {}", config.name(), config.transport(), target.getToolNames());
            return wrapper;
        } catch (Exception e) {
            errors.put(config.id(), e.getMessage());
            retryGate.markFailure(config.id());
            throw new IllegalStateException("MCP 连接失败: " + e.getMessage(), e);
        }
    }

    /** 注册前快照：工具名 → AgentTool 实例（冲突自检用） */
    private static Map<String, AgentTool> snapshotTools(Toolkit toolkit) {
        Map<String, AgentTool> snapshot = new HashMap<>();
        for (String name : toolkit.getToolNames()) {
            snapshot.put(name, toolkit.getTool(name));
        }
        return snapshot;
    }

    /**
     * MCP 工具名冲突自检（javap 反编译 agentscope-core-2.0.4 验证结论，见注释）。
     * <p>
     * 反编译结论：McpClientManager 注册 McpTool 时工具名 = 前缀参数 + 工具原名，
     * 而 Toolkit.registerMcpClient(wrapper) 是公开 API 的唯一重载，内部把前缀参数
     * 固定传空串——即 AgentScope 2.0.4 并不会把 MCP 工具名转成 mcp__{server}__{tool}，
     * 工具以裸名与内置工具同池注册；ToolRegistry 对同名直接 put 覆盖（内置优先去重不成立）。
     * 因此做两层自检：
     * <ul>
     *   <li>同名覆盖：注册前后同名但 AgentTool 实例改变 → MCP 工具覆盖了内置工具 → log.error</li>
     *   <li>裸名注册：注册后新增的无 mcp__ 前缀工具名 → 与内置工具同池无隔离 → log.warn</li>
     * </ul>
     */
    private void checkMcpToolCollision(Toolkit toolkit, Map<String, AgentTool> before) {
        for (Map.Entry<String, AgentTool> entry : before.entrySet()) {
            AgentTool now = toolkit.getTool(entry.getKey());
            if (now != null && now != entry.getValue()) {
                log.error("MCP 工具名与内置工具冲突，已覆盖内置工具: {}", entry.getKey());
            }
        }
        Set<String> added = new HashSet<>(toolkit.getToolNames());
        added.removeAll(before.keySet());
        for (String name : added) {
            if (!name.startsWith(MCP_NAME_PREFIX)) {
                log.warn("MCP 工具裸名注册（AgentScope 2.0.4 未加 mcp__ 前缀，与内置工具同池）: {}", name);
            }
        }
    }

    /** 断开并从 Toolkit 注销（不动重试门状态） */
    public void disconnect(String id) {
        synchronized (lockFor(id)) {
            disconnectLocked(id);
        }
    }

    private void disconnectLocked(String id) {
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

    /** id → 锁对象（per-id，惰性创建；id 数量 = 服务器数量，可接受） */
    private Object lockFor(String id) {
        return idLocks.computeIfAbsent(id, k -> new Object());
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

    /**
     * 列出服务器工具（name + description + inputSchema），description 在此展示层截断。
     * <p>
     * 模型侧描述由 AgentScope 框架透传：McpClientManager 直接把 McpSchema.Tool 的原始
     * description 装进 McpTool/ToolBase，Toolkit 公开 API 无描述改写钩子（javap 反编译
     * 2.0.4 核实）——模型侧暂无法截断，v1 可接受结论；必要时再自研 AgentTool 包装注册。
     */
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
                item.put("description", truncateDescription(tool.description() == null ? "" : tool.description()));
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

    /** 已连接 client 的只读视图（id → wrapper），供 MCP 资源读取等只读消费方使用 */
    public Map<String, McpClientWrapper> connectedClients() {
        return Map.copyOf(clients);
    }

    /** 截断超长工具描述（&gt; 2048 字符，追加 …），防 OpenAPI 派生 MCP 的巨型文档塞爆上下文 */
    public static String truncateDescription(String desc) {
        if (desc == null || desc.length() <= DESCRIPTION_MAX) {
            return desc;
        }
        return desc.substring(0, DESCRIPTION_MAX) + "…";
    }
}
