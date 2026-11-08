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

/** 技能管理：list / show */
@Command(name = "skill", description = "技能管理（列表/详情）",
        mixinStandardHelpOptions = true,
        subcommands = {SkillCommand.ListCmd.class, SkillCommand.ShowCmd.class})
public class SkillCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    private static ApiClient client(String server) {
        return new ApiClient(CliConfig.server(server), CliConfig.requireToken());
    }

    @Command(name = "list", description = "技能列表", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Override
        public Integer call() {
            JsonNode skills = client(server).get("/api/v1/skills");
            if (!skills.isArray() || skills.isEmpty()) {
                System.out.println(DIM + "（暂无技能）" + RESET);
                return 0;
            }
            for (JsonNode s : skills) {
                String state = s.path("enabled").asBoolean(true) ? GREEN + "启用" : DIM + "停用";
                System.out.printf("%-20s v%-8s %s%s  %s%n",
                        s.path("name").asText("?"),
                        s.path("version").asText("-"),
                        state, RESET,
                        DIM + truncate(s.path("description").asText(""), 60) + RESET);
            }
            return 0;
        }
    }

    @Command(name = "show", description = "技能详情（SKILL.md 全文）", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "技能名")
        String name;

        @Override
        public Integer call() {
            if (name == null) {
                throw new CliException("用法: markagent skill show <技能名>");
            }
            JsonNode s = client(server).get("/api/v1/skills/" + name);
            System.out.println("技能: " + s.path("name").asText() + " v" + s.path("version").asText());
            System.out.println("描述: " + s.path("description").asText(""));
            String content = s.path("content").asText("");
            if (!content.isBlank()) {
                System.out.println(DIM + "────────────────────────────────────" + RESET);
                System.out.println(content);
            }
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
