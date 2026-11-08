package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.DIM;
import static com.markagent.cli.SseStreamRenderer.GREEN;
import static com.markagent.cli.SseStreamRenderer.RED;
import static com.markagent.cli.SseStreamRenderer.RESET;

/** MCP 服务器管理：list / add / rm / test */
@Command(name = "mcp", description = "MCP 服务器管理（列表/添加/删除/测试）",
        mixinStandardHelpOptions = true,
        subcommands = {
                McpCommand.ListCmd.class, McpCommand.AddCmd.class,
                McpCommand.RmCmd.class, McpCommand.TestCmd.class
        })
public class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    private static ApiClient client(String server) {
        return new ApiClient(CliConfig.server(server), CliConfig.requireToken());
    }

    @Command(name = "list", description = "MCP 服务器列表（含连接状态）", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Override
        public Integer call() {
            JsonNode servers = client(server).get("/api/v1/mcp/servers");
            if (!servers.isArray() || servers.isEmpty()) {
                System.out.println(DIM + "（暂无 MCP 服务器）" + RESET);
                return 0;
            }
            for (JsonNode s : servers) {
                JsonNode status = s.path("status");
                boolean connected = status.path("connected").asBoolean(false);
                String state = connected
                        ? GREEN + "已连接（" + status.path("toolCount").asLong(0) + " 工具）" + RESET
                        : RED + (status.hasNonNull("error") ? "失败" : "未连接") + RESET;
                System.out.printf("%-10s %-6s %s %s  %s%n",
                        shortId(s.path("id").asText()),
                        s.path("transport").asText("?"),
                        s.path("name").asText("?"),
                        s.path("enabled").asBoolean(false) ? GREEN + "启用" : DIM + "停用",
                        state);
            }
            return 0;
        }
    }

    @Command(name = "add", description = "添加 MCP 服务器（--stdio 命令 [参数...] 或 --sse 端点）",
            mixinStandardHelpOptions = true)
    static class AddCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Option(names = {"--stdio"}, description = "stdio 传输：命令")
        String command;

        @Option(names = {"--sse"}, description = "sse 传输：端点 URL")
        String url;

        @Parameters(index = "0", description = "服务器名称")
        String name;

        @Parameters(index = "1..*", arity = "0..*", description = "stdio 参数 / sse URL")
        List<String> rest;

        @Override
        public Integer call() {
            if (name == null) {
                throw new CliException("用法: markagent mcp add <名称> --stdio <命令> [参数...] | --sse <URL>");
            }
            boolean stdio = command != null;
            boolean sse = url != null;
            if (stdio == sse) {
                throw new CliException("--stdio 与 --sse 必须二选一");
            }
            List<String> rest = this.rest == null ? List.of() : this.rest;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("enabled", true);
            if (stdio) {
                List<String> args = new ArrayList<>(rest);
                body.put("transport", "stdio");
                body.put("command", command);
                body.put("args", args);
                body.put("url", null);
            } else {
                if (rest.isEmpty()) {
                    throw new CliException("--sse 需要 URL：markagent mcp add <名称> --sse <URL>");
                }
                body.put("transport", "sse");
                body.put("command", null);
                body.put("args", List.of());
                body.put("url", rest.get(0));
            }
            JsonNode saved = client(server).post("/api/v1/mcp/servers", body);
            System.out.println(GREEN + "✓ 已添加 " + name + "（" + shortId(saved.path("id").asText()) + "）" + RESET);
            return 0;
        }
    }

    @Command(name = "rm", description = "删除 MCP 服务器", mixinStandardHelpOptions = true)
    static class RmCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "服务器 ID")
        String id;

        @Override
        public Integer call() {
            if (id == null) {
                throw new CliException("用法: markagent mcp rm <服务器ID>");
            }
            client(server).delete("/api/v1/mcp/servers/" + id);
            System.out.println(GREEN + "✓ 已删除 " + id + RESET);
            return 0;
        }
    }

    @Command(name = "test", description = "测试 MCP 服务器连接", mixinStandardHelpOptions = true)
    static class TestCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "服务器 ID")
        String id;

        @Override
        public Integer call() {
            if (id == null) {
                throw new CliException("用法: markagent mcp test <服务器ID>");
            }
            JsonNode result = client(server).post("/api/v1/mcp/servers/" + id + "/test", Map.of());
            if (result.path("ok").asBoolean(false)) {
                System.out.println(GREEN + "✓ 连接正常，" + result.path("toolCount").asLong(0) + " 个工具可用" + RESET);
                JsonNode names = result.path("toolNames");
                if (names.isArray() && !names.isEmpty()) {
                    System.out.println(DIM + "  " + names + RESET);
                }
            } else {
                System.out.println(RED + "✗ 连接失败: " + result.path("error").asText("") + RESET);
                return 1;
            }
            return 0;
        }
    }

    private static String shortId(String id) {
        return id.length() <= 10 ? id : id.substring(0, 10);
    }
}
