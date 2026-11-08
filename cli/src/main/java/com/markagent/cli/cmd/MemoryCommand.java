package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.DIM;
import static com.markagent.cli.SseStreamRenderer.GREEN;
import static com.markagent.cli.SseStreamRenderer.RESET;

/** 长期记忆：show / search / save（挂会话维度，缺省取最新会话） */
@Command(name = "memory", description = "长期记忆（查看/检索/保存）",
        mixinStandardHelpOptions = true,
        subcommands = {
                MemoryCommand.ShowCmd.class, MemoryCommand.SearchCmd.class, MemoryCommand.SaveCmd.class
        })
public class MemoryCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    private static ApiClient client(String server) {
        return new ApiClient(CliConfig.server(server), CliConfig.requireToken());
    }

    /** 未指定 --session 时取最新会话 */
    static String resolveSession(ApiClient client, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        JsonNode sessions = client.get("/api/v1/sessions");
        if (sessions.isArray() && !sessions.isEmpty()) {
            return sessions.get(0).path("id").asText();
        }
        throw new CliException("没有可用会话：请先 markagent chat 创建会话，或用 --session 指定");
    }

    @Command(name = "show", description = "长期记忆全文（MEMORY.md）", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Option(names = {"--session"}, description = "会话 ID（缺省取最新会话）")
        String sessionId;

        @Override
        public Integer call() {
            ApiClient client = client(server);
            String sid = resolveSession(client, sessionId);
            JsonNode resp = client.get("/api/v1/sessions/" + sid + "/memory");
            String content = resp.path("content").asText("");
            System.out.println(DIM + "会话 " + sid + " 的长期记忆:" + RESET);
            System.out.println(content.isBlank() ? DIM + "（暂无记忆）" + RESET : content);
            return 0;
        }
    }

    @Command(name = "search", description = "记忆检索（向量档/关键词档自动降级）", mixinStandardHelpOptions = true)
    static class SearchCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Option(names = {"--session"}, description = "会话 ID（缺省取最新会话）")
        String sessionId;

        @Option(names = {"-n", "--limit"}, defaultValue = "5", description = "返回条数（默认 ${DEFAULT-VALUE}）")
        int limit;

        @Parameters(index = "0", description = "检索关键词")
        String query;

        @Override
        public Integer call() {
            if (query == null || query.isBlank()) {
                throw new CliException("用法: markagent memory search <关键词>");
            }
            ApiClient client = client(server);
            String sid = resolveSession(client, sessionId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("limit", limit);
            JsonNode results = client.post("/api/v1/sessions/" + sid + "/memory/search", body);
            if (!results.isArray() || results.isEmpty()) {
                System.out.println(DIM + "（无匹配记忆）" + RESET);
                return 0;
            }
            for (JsonNode r : results) {
                System.out.printf("%s%.2f%s %s%n",
                        DIM, r.path("score").asDouble(0), RESET,
                        truncate(r.path("content").asText(""), 120));
            }
            return 0;
        }
    }

    @Command(name = "save", description = "保存一条长期记忆", mixinStandardHelpOptions = true)
    static class SaveCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Option(names = {"--session"}, description = "会话 ID（缺省取最新会话）")
        String sessionId;

        @Parameters(index = "0", description = "记忆内容")
        String content;

        @Override
        public Integer call() {
            if (content == null || content.isBlank()) {
                throw new CliException("用法: markagent memory save <内容>");
            }
            ApiClient client = client(server);
            String sid = resolveSession(client, sessionId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", content);
            JsonNode resp = client.post("/api/v1/sessions/" + sid + "/memory/save", body);
            System.out.println(GREEN + "✓ 已保存（" + resp.path("status").asText("saved") + "）" + RESET);
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
