package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import com.markagent.cli.SseStreamRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/** 当前登录用户信息 */
@Command(name = "whoami", description = "当前登录用户信息", mixinStandardHelpOptions = true)
public class WhoamiCommand implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
    String server;

    @Override
    public Integer call() {
        String token = CliConfig.requireToken();
        JsonNode me = new ApiClient(CliConfig.server(server), token).get("/api/v1/auth/me");
        System.out.println("用户名: " + me.path("username").asText("?"));
        if (me.hasNonNull("displayName")) {
            System.out.println("显示名: " + me.get("displayName").asText());
        }
        if (me.hasNonNull("roles")) {
            System.out.println("角色: " + me.get("roles"));
        }
        System.out.println(SseStreamRenderer.DIM + "服务: " + CliConfig.server(server) + SseStreamRenderer.RESET);
        return 0;
    }
}
