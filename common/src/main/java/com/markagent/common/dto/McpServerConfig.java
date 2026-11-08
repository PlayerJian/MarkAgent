package com.markagent.common.dto;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置（持久化于 data/mcp.json）。
 * transport: stdio（本地命令进程）| sse（远程 SSE 端点）。
 */
public record McpServerConfig(
        String id,
        String name,
        String transport,                 // stdio | sse
        String command,                   // stdio：可执行命令
        List<String> args,                // stdio：参数
        Map<String, String> env,          // stdio：环境变量
        String url,                       // sse：端点
        boolean enabled) {

    public McpServerConfig {
        args = args == null ? List.of() : args;
        env = env == null ? Map.of() : env;
    }

    public McpServerConfig withEnabled(boolean value) {
        return new McpServerConfig(id, name, transport, command, args, env, url, value);
    }

    public static McpServerConfig stdio(String id, String name, String command, List<String> args) {
        return new McpServerConfig(id, name, "stdio", command, args, Map.of(), null, true);
    }

    public static McpServerConfig sse(String id, String name, String url) {
        return new McpServerConfig(id, name, "sse", null, List.of(), Map.of(), url, true);
    }
}
