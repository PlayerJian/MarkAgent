package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.DIM;
import static com.markagent.cli.SseStreamRenderer.GREEN;
import static com.markagent.cli.SseStreamRenderer.RESET;

/** 会话管理：list / show / delete */
@Command(name = "session", description = "会话管理（列表/消息/删除）",
        mixinStandardHelpOptions = true,
        subcommands = {
                SessionCommand.ListCmd.class, SessionCommand.ShowCmd.class, SessionCommand.DeleteCmd.class
        })
public class SessionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    private static ApiClient client(String server) {
        return new ApiClient(CliConfig.server(server), CliConfig.requireToken());
    }

    @Command(name = "list", description = "会话列表", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Override
        public Integer call() {
            JsonNode sessions = client(server).get("/api/v1/sessions");
            if (!sessions.isArray() || sessions.isEmpty()) {
                System.out.println(DIM + "（暂无会话）" + RESET);
                return 0;
            }
            for (JsonNode s : sessions) {
                System.out.printf("%-10s %s %s%n",
                        shortId(s.path("id").asText()),
                        truncate(s.path("title").asText("（无标题）"), 50),
                        DIM + s.path("updatedAt").asText("") + RESET);
            }
            return 0;
        }
    }

    @Command(name = "show", description = "会话消息记录", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "会话 ID")
        String sessionId;

        @Override
        public Integer call() {
            if (sessionId == null) {
                throw new CliException("用法: markagent session show <会话ID>");
            }
            JsonNode sessions = client(server).get("/api/v1/sessions");
            JsonNode target = null;
            for (JsonNode s : sessions) {
                if (sessionId.equals(s.path("id").asText())) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                throw new CliException("会话不存在: " + sessionId);
            }
            System.out.println("会话: " + target.path("id").asText() + "  " + target.path("title").asText(""));
            for (JsonNode m : target.path("messages")) {
                String role = m.path("role").asText("?");
                String content = m.path("content").asText("");
                String prefix = "assistant".equals(role) ? GREEN + "助手" : "用户";
                System.out.println(prefix + RESET + ": " + content);
                System.out.println();
            }
            return 0;
        }
    }

    @Command(name = "delete", description = "删除会话", mixinStandardHelpOptions = true)
    static class DeleteCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "会话 ID")
        String sessionId;

        @Override
        public Integer call() {
            if (sessionId == null) {
                throw new CliException("用法: markagent session delete <会话ID>");
            }
            client(server).delete("/api/v1/sessions/" + sessionId);
            System.out.println(GREEN + "✓ 已删除会话 " + sessionId + RESET);
            return 0;
        }
    }

    private static String shortId(String id) {
        return id.length() <= 10 ? id : id.substring(0, 10);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
