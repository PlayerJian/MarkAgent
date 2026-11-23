package com.markagent.tools;

import com.markagent.mcp.McpServerManager;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP 资源读取工具：mcp_resources_list（列出服务器资源清单）/ mcp_resource_read（按 uri 读资源文本）。
 * <p>
 * 绕过 AgentScope 2.0.4 的实现：其 McpClientWrapper 公开 API 只有 initialize/listTools/callTool，
 * 无 resources 方法、无底层 client getter（javap 2.0.4 验证，连接与工具调用仍走框架）。本类经反射
 * 取出 McpSyncClientWrapper / McpAsyncClientWrapper 私有的 client 字段，直接调用底层 SDK
 * （io.modelcontextprotocol 0.17.2，与 agentscope-core 声明版本一致，server pom 已显式声明）的
 * listResources/readResource——复用既有连接，不为资源读取另起 stdio 进程。反射仅依赖「字段名
 * client + 两个 wrapper 类型」，任一变化会给出明确错误文本；该契约由 McpResourceToolTest 用
 * 真实 wrapper + 假传输层全链路钉住。
 * <p>
 * 返回约定：正常结果以清单/文本返回；失败以「错误：」开头（审计按此判定失败）。
 * 二进制资源读取本身成功，返回说明文本（非「错误：」）引导改用 MCP 工具处理。
 */
@Component
public class McpResourceTool {

    /** 文本资源读取截断上限（字符） */
    static final int TEXT_MAX = 20000;

    /** 资源列表每台服务器条数上限 */
    static final int LIST_MAX = 200;

    /** 底层 SDK 调用超时（与 McpServerManager 的 OP_TIMEOUT 一致） */
    static final Duration OP_TIMEOUT = Duration.ofSeconds(30);

    private final McpServerManager mcpManager;

    public McpResourceTool(McpServerManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    /**
     * 列出 MCP 服务器资源清单：缺省全部已连接服务器、可传 server_id 指定一台。
     * 输出「- uri | name | mimeType」行，每台上限 {@value #LIST_MAX} 条；某台失败单独注明、不影响其他。
     */
    @Tool(name = "mcp_resources_list", description = "列出 MCP 服务器的资源清单"
            + "（格式：- uri | name | mimeType，每台服务器上限 200 条）。"
            + "可传 server_id 指定服务器，缺省列出全部已连接服务器。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String listResources(@ToolParam(name = "server_id", required = false,
            description = "可选，MCP 服务器 id；缺省列出全部已连接服务器") String serverId) {
        Map<String, McpClientWrapper> clients = mcpManager.connectedClients();
        if (clients.isEmpty()) {
            return "当前没有已连接的 MCP 服务器，无资源可列出";
        }
        String missing = checkConnected(clients, serverId);
        if (missing != null) {
            return missing;
        }
        // TreeMap 按 id 排序，输出稳定（clients 来自 Map.copyOf，顺序不保证）
        Map<String, McpClientWrapper> targets = new TreeMap<>();
        if (serverId == null || serverId.isBlank()) {
            targets.putAll(clients);
        } else {
            targets.put(serverId, clients.get(serverId));
        }
        StringBuilder sections = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, McpClientWrapper> entry : targets.entrySet()) {
            sections.append("\n【").append(entry.getKey()).append("】");
            try {
                List<McpSchema.Resource> resources = reader(entry.getValue()).listAll();
                if (resources.isEmpty()) {
                    sections.append("\n（无资源）");
                    continue;
                }
                for (int i = 0; i < resources.size() && i < LIST_MAX; i++) {
                    sections.append("\n").append(formatResourceLine(resources.get(i)));
                }
                total += Math.min(resources.size(), LIST_MAX);
                if (resources.size() > LIST_MAX) {
                    sections.append("\n（仅显示前 ").append(LIST_MAX).append(" 条，其余 ")
                            .append(resources.size() - LIST_MAX).append(" 条省略）");
                }
            } catch (Exception e) {
                sections.append("\n错误：列出资源失败: ").append(e.getMessage());
            }
        }
        String summary = "共列出 " + total + " 条资源（每台服务器上限 " + LIST_MAX + " 条）：";
        return summary + sections;
    }

    /**
     * 读取指定服务器上 uri 资源：文本片段拼接返回（截断 {@value #TEXT_MAX} 字符）；
     * 二进制资源返回说明文本；uri 不存在等失败以「错误：」开头。
     */
    @Tool(name = "mcp_resource_read", description = "读取 MCP 服务器上指定 uri 资源的文本内容"
            + "（截断 20000 字符；二进制资源返回说明）。先 mcp_resources_list 获取可用 uri。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String readResource(@ToolParam(name = "server_id", required = true,
            description = "MCP 服务器 id") String serverId,
            @ToolParam(name = "uri", required = true,
                    description = "资源 uri，如 file:///etc/config.txt") String uri) {
        if (serverId == null || serverId.isBlank()) {
            return "错误：server_id 不能为空";
        }
        if (uri == null || uri.isBlank()) {
            return "错误：uri 不能为空";
        }
        Map<String, McpClientWrapper> clients = mcpManager.connectedClients();
        String missing = checkConnected(clients, serverId);
        if (missing != null) {
            return missing;
        }
        try {
            McpSchema.ReadResourceResult result = reader(clients.get(serverId)).read(uri);
            if (result == null) {
                return "错误：读取 MCP 资源超时或无响应 [" + serverId + "] " + uri;
            }
            return formatReadResult(serverId, uri, result);
        } catch (Exception e) {
            return "错误：读取 MCP 资源失败 [" + serverId + "] " + uri + ": " + e.getMessage();
        }
    }

    /**
     * 校验 server_id（非空时）是否在已连接列表：合法返回 null，
     * 不合法返回错误文本（附已连接服务器清单，供模型修正后重试）。
     */
    static String checkConnected(Map<String, ?> clients, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        if (clients.containsKey(serverId)) {
            return null;
        }
        return "错误：MCP 服务器未连接: " + serverId
                + "。已连接服务器：" + String.join(", ", clients.keySet());
    }

    // ==================== 底层 SDK 访问（反射绕过 AgentScope 包装层） ====================

    /**
     * 反射取出 AgentScope 2.0.4 wrapper 私有的底层 SDK client 字段。
     * 沿类层级找名为 client 的字段（Mockito 代理等子类没有该字段，继续向父类找）；
     * 找不到或字段不可达抛 IllegalStateException（明确文案，单测钉住契约）。
     */
    static Object rawClient(McpClientWrapper wrapper) {
        for (Class<?> c = wrapper.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("client");
                field.setAccessible(true);
                return field.get(wrapper);
            } catch (NoSuchFieldException e) {
                // 该层级无 client 字段，继续向父类找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("MCP 底层 client 字段不可访问: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException(
                "MCP 底层 client 字段不存在（AgentScope 2.0.4 的 McpClientWrapper 无 getter，"
                        + "本实现依赖反射字段名 client；框架版本变化时请同步适配）");
    }

    /** 底层 SDK 资源读取适配：sync/async 两种 wrapper 统一为同步调用（async 路径 block 超时） */
    private interface ResourceReader {
        List<McpSchema.Resource> listAll();

        McpSchema.ReadResourceResult read(String uri);
    }

    /** 按底层 client 实际类型分发（McpServerManager 目前全走 buildSync，async 分支为防御） */
    private ResourceReader reader(McpClientWrapper wrapper) {
        Object raw = rawClient(wrapper);
        if (raw instanceof McpSyncClient sync) {
            return new ResourceReader() {
                @Override
                public List<McpSchema.Resource> listAll() {
                    McpSchema.ListResourcesResult result = sync.listResources();
                    return result == null || result.resources() == null ? List.of() : result.resources();
                }

                @Override
                public McpSchema.ReadResourceResult read(String uri) {
                    return sync.readResource(new McpSchema.ReadResourceRequest(uri));
                }
            };
        }
        if (raw instanceof McpAsyncClient async) {
            return new ResourceReader() {
                @Override
                public List<McpSchema.Resource> listAll() {
                    McpSchema.ListResourcesResult result = async.listResources().block(OP_TIMEOUT);
                    return result == null || result.resources() == null ? List.of() : result.resources();
                }

                @Override
                public McpSchema.ReadResourceResult read(String uri) {
                    return async.readResource(new McpSchema.ReadResourceRequest(uri)).block(OP_TIMEOUT);
                }
            };
        }
        throw new IllegalStateException(
                "MCP 底层客户端类型未知: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    // ==================== 格式化（纯函数，单测覆盖） ====================

    /** 资源清单行：- uri | name | mimeType（缺省字段以 - 占位） */
    static String formatResourceLine(McpSchema.Resource resource) {
        return "- " + resource.uri()
                + " | " + blank(resource.name())
                + " | " + blank(resource.mimeType());
    }

    /**
     * 读取结果格式化：文本片段拼接（多段以空行分隔）、截断 {@value #TEXT_MAX} 字符；
     * 纯二进制返回说明；文本+二进制混合时附注二进制片段数。
     */
    static String formatReadResult(String serverId, String uri, McpSchema.ReadResourceResult result) {
        String prefix = "[" + serverId + "] " + uri;
        List<McpSchema.ResourceContents> contents = result.contents();
        if (contents == null || contents.isEmpty()) {
            return prefix + " 资源内容为空";
        }
        List<String> texts = new ArrayList<>();
        int blobs = 0;
        String mime = null;
        for (McpSchema.ResourceContents content : contents) {
            if (content instanceof McpSchema.TextResourceContents text) {
                texts.add(text.text() == null ? "" : text.text());
                if (mime == null) {
                    mime = text.mimeType();
                }
            } else if (content instanceof McpSchema.BlobResourceContents blob) {
                blobs++;
                if (mime == null) {
                    mime = blob.mimeType();
                }
            }
        }
        if (texts.isEmpty()) {
            return prefix + " 为二进制资源（" + blank(mime) + "），无法作为文本返回；"
                    + "请改用该 MCP 服务器提供的工具处理";
        }
        String joined = String.join("\n\n", texts);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("（").append(blank(mime)).append("）\n");
        if (joined.length() > TEXT_MAX) {
            sb.append(joined, 0, TEXT_MAX);
            sb.append("\n（内容已截断至 ").append(TEXT_MAX).append(" 字符）");
        } else {
            sb.append(joined);
        }
        if (blobs > 0) {
            sb.append("\n（另有 ").append(blobs).append(" 个二进制片段未显示）");
        }
        return sb.toString();
    }

    /** 空串/空白以 - 占位 */
    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
