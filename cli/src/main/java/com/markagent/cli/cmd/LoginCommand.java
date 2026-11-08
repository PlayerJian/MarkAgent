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
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/** 登录（或 --register 注册）并保存 token 到 ~/.markagent/config.json */
@Command(name = "login", description = "登录并保存 token（首次使用加 --register 注册新用户）",
        mixinStandardHelpOptions = true)
public class LoginCommand implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
    String server;

    @Option(names = {"-r", "--register"}, description = "注册新用户")
    boolean register;

    @Option(names = {"-n", "--name"}, description = "显示名（--register 时使用）")
    String displayName;

    @Parameters(index = "0", description = "用户名")
    String username;

    @Parameters(index = "1", arity = "0..1", description = "密码（缺省时隐藏输入）")
    String password;

    @Override
    public Integer call() {
        if (username == null || username.isBlank()) {
            throw new CliException("用法: markagent login <用户名> [密码]");
        }
        if (password == null) {
            password = readPassword("密码: ");
        }
        String base = CliConfig.server(server);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        if (register && displayName != null) {
            body.put("displayName", displayName);
        }
        JsonNode resp = new ApiClient(base, null)
                .post(register ? "/api/v1/auth/register" : "/api/v1/auth/login", body);
        String token = resp.path("token").asText();
        if (token.isBlank()) {
            throw new CliException("登录响应缺少 token");
        }
        final String baseFinal = base;
        final String user = resp.path("user").path("username").asText(username);
        CliConfig.update(node -> {
            node.put("server", baseFinal);
            node.put("token", token);
            node.put("username", user);
            return node;
        });
        System.out.println(SseStreamRenderer.GREEN + "✓ " + (register ? "注册并登录成功" : "登录成功")
                + "：" + user + "（" + baseFinal + "）" + SseStreamRenderer.RESET);
        return 0;
    }

    /** 无回显密码输入：优先 System.console，退化到 Scanner */
    static String readPassword(String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            return new String(console.readPassword(prompt));
        }
        System.out.print(prompt);
        return new Scanner(System.in, java.nio.charset.Charset.defaultCharset()).nextLine();
    }
}
