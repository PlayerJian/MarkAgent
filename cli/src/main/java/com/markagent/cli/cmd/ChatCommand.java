package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import com.markagent.cli.SseStreamRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.DIM;
import static com.markagent.cli.SseStreamRenderer.RESET;

/** 发起对话任务并流式渲染（POST /chat → GET /tasks/{id}/stream） */
@Command(name = "chat", description = "发起对话任务并流式输出", mixinStandardHelpOptions = true)
public class ChatCommand implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
    String server;

    @Parameters(index = "0", arity = "0..1", description = "任务描述")
    String goal;

    @Option(names = {"--session"}, description = "续接指定会话")
    String sessionId;

    @Option(names = {"--new"}, description = "强制新建会话")
    boolean newSession;

    @Option(names = {"-m", "--model"}, description = "模型（deepseek-flash | deepseek-pro，默认 deepseek-flash）")
    String model;

    @Option(names = {"--thinking"}, description = "开启思考模式（输出思考过程，消耗更多 token）")
    Boolean thinking;

    @Option(names = {"-p", "--planning"}, defaultValue = "auto", description = "计划模式（${COMPLETION-CANDIDATES}）")
    String planning;

    @Option(names = {"-w", "--worker"}, split = ",", description = "worker 角色（r,c,w 或 researcher,coder,writer，逗号分隔）")
    List<String> workers;

    @Option(names = {"--raw"}, description = "原始 SSE 事件输出（调试用）")
    boolean raw;

    @Override
    public Integer call() {
        String token = CliConfig.requireToken();
        String base = CliConfig.server(server);
        if (goal == null || goal.isBlank()) {
            throw new CliException("请提供任务描述：markagent chat \"任务内容\"");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        if (model != null && !model.isBlank()) {
            options.put("model", model);
        }
        options.put("planning", planning);
        if (thinking != null) {
            options.put("thinking", thinking);
        }
        if (workers != null && !workers.isEmpty()) {
            options.put("workers", workers.stream().map(ChatCommand::mapWorker).toList());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goal", goal);
        if (!newSession && sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        body.put("options", options);

        ApiClient client = new ApiClient(base, token);
        JsonNode accepted = client.post("/api/v1/chat", body);
        String taskId = accepted.path("taskId").asText();
        String sid = accepted.path("sessionId").asText();
        System.out.println(DIM + "任务已受理 task=" + taskId + " session=" + sid + RESET);
        System.out.println();
        new SseStreamRenderer(raw).render(client.stream("/api/v1/tasks/" + taskId + "/stream"));
        System.out.println();
        return 0;
    }

    /** 单字母角色缩写展开为完整角色名 */
    private static String mapWorker(String w) {
        return switch (w.trim().toLowerCase()) {
            case "r" -> "researcher";
            case "c" -> "coder";
            case "w" -> "writer";
            default -> w.trim();
        };
    }
}
