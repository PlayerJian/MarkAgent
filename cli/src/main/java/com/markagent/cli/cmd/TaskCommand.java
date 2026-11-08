package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import com.markagent.cli.SseStreamRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.DIM;
import static com.markagent.cli.SseStreamRenderer.GREEN;
import static com.markagent.cli.SseStreamRenderer.RED;
import static com.markagent.cli.SseStreamRenderer.RESET;
import static com.markagent.cli.SseStreamRenderer.YELLOW;

/** 任务管理：list / show / resume / pause / stop / stream */
@Command(name = "task", description = "任务管理（列表/详情/暂停/恢复/停止/流式订阅）",
        mixinStandardHelpOptions = true,
        subcommands = {
                TaskCommand.ListCmd.class, TaskCommand.ShowCmd.class, TaskCommand.ResumeCmd.class,
                TaskCommand.PauseCmd.class, TaskCommand.StopCmd.class, TaskCommand.StreamCmd.class
        })
public class TaskCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    /** 共享：构造带 token 的客户端 */
    static ApiClient client(String server) {
        return new ApiClient(CliConfig.server(server), CliConfig.requireToken());
    }

    @Command(name = "list", description = "任务列表", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Override
        public Integer call() {
            JsonNode tasks = client(server).get("/api/v1/tasks");
            if (!tasks.isArray() || tasks.isEmpty()) {
                System.out.println(DIM + "（暂无任务）" + RESET);
                return 0;
            }
            for (JsonNode t : tasks) {
                String status = t.path("status").asText("?");
                String color = switch (status) {
                    case "DONE", "COMPLETED" -> GREEN;
                    case "FAILED", "CANCELLED" -> RED;
                    case "PAUSED" -> YELLOW;
                    default -> "";
                };
                // 完整 ID：截断后无法用于 show/resume/pause/stop
                System.out.printf("%-18s %s%-10s%s %s%n",
                        t.path("id").asText(),
                        color, status, RESET,
                        truncate(t.path("goal").asText(""), 60));
            }
            return 0;
        }
    }

    @Command(name = "show", description = "任务详情（计划/检查点/选项）", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "任务 ID")
        String taskId;

        @Override
        public Integer call() {
            if (taskId == null) {
                throw new CliException("用法: markagent task show <任务ID>");
            }
            JsonNode t = client(server).get("/api/v1/tasks/" + taskId);
            System.out.println("任务: " + t.path("id").asText());
            System.out.println("状态: " + t.path("status").asText("?"));
            System.out.println("目标: " + t.path("goal").asText(""));
            System.out.println("会话: " + t.path("sessionId").asText(""));
            if (t.hasNonNull("errorMessage")) {
                System.out.println(RED + "错误: " + t.get("errorMessage").asText() + RESET);
            }
            JsonNode plan = t.path("plan");
            JsonNode steps = plan.path("steps");
            if (steps.isArray() && !steps.isEmpty()) {
                System.out.println(YELLOW + "计划:" + RESET);
                String current = plan.path("currentStepId").asText();
                for (JsonNode step : steps) {
                    String marker = switch (step.path("status").asText()) {
                        case "DONE", "COMPLETED" -> "✓ ";
                        case "FAILED" -> "✗ ";
                        default -> "· ";
                    };
                    System.out.println("  " + marker + step.path("title").asText("?")
                            + (step.path("id").asText().equals(current) ? " ←当前" : ""));
                }
            }
            JsonNode checkpoints = t.path("checkpoints");
            if (checkpoints.isArray() && !checkpoints.isEmpty()) {
                JsonNode last = checkpoints.get(checkpoints.size() - 1);
                System.out.println("检查点: " + checkpoints.size() + " 个（最新 seq="
                        + last.path("seq").asLong() + " step=" + last.path("stepIndex").asInt(-1) + "）");
            }
            System.out.println(DIM + "创建: " + t.path("createdAt").asText("") + "  更新: "
                    + t.path("updatedAt").asText("") + RESET);
            return 0;
        }
    }

    @Command(name = "resume", description = "恢复暂停的任务（断点续跑）", mixinStandardHelpOptions = true)
    static class ResumeCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "任务 ID")
        String taskId;

        @Override
        public Integer call() {
            JsonNode t = client(server).post("/api/v1/tasks/" + requireId(taskId) + "/resume", Map0.EMPTY);
            System.out.println(GREEN + "✓ 已恢复，当前状态: " + t.path("status").asText() + RESET
                    + DIM + "（流式输出: markagent task stream " + taskId + "）" + RESET);
            return 0;
        }
    }

    @Command(name = "pause", description = "暂停运行中的任务", mixinStandardHelpOptions = true)
    static class PauseCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "任务 ID")
        String taskId;

        @Override
        public Integer call() {
            JsonNode t = client(server).post("/api/v1/tasks/" + requireId(taskId) + "/pause", Map0.EMPTY);
            System.out.println(YELLOW + "⏸ 已暂停，当前状态: " + t.path("status").asText() + RESET);
            return 0;
        }
    }

    @Command(name = "stop", description = "停止运行中的任务", mixinStandardHelpOptions = true)
    static class StopCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "任务 ID")
        String taskId;

        @Override
        public Integer call() {
            JsonNode t = client(server).post("/api/v1/tasks/" + requireId(taskId) + "/stop", Map0.EMPTY);
            System.out.println("已停止，当前状态: " + t.path("status").asText());
            return 0;
        }
    }

    @Command(name = "stream", description = "订阅任务事件流（含历史回放）", mixinStandardHelpOptions = true)
    static class StreamCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "任务 ID")
        String taskId;

        @Option(names = {"--raw"}, description = "原始 SSE 事件输出（调试用）")
        boolean raw;

        @Override
        public Integer call() {
            String id = requireId(taskId);
            ApiClient client = client(server);
            new SseStreamRenderer(raw).render(client.stream("/api/v1/tasks/" + id + "/stream"));
            System.out.println();
            return 0;
        }
    }

    private static String requireId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new CliException("请提供任务 ID（markagent task list 查看）");
        }
        return taskId;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 空请求体（POST 不带 body 的占位） */
    private static final class Map0 {
        static final java.util.Map<String, Object> EMPTY = java.util.Map.of();
    }
}
