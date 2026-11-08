package com.markagent.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.markagent.cli.CliConfig;
import com.markagent.cli.CliException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

import static com.markagent.cli.SseStreamRenderer.GREEN;
import static com.markagent.cli.SseStreamRenderer.RESET;

/** CLI 本地配置：show / set-server */
@Command(name = "config", description = "CLI 本地配置（服务地址/token 状态）",
        mixinStandardHelpOptions = true,
        subcommands = {ConfigCommand.ShowCmd.class, ConfigCommand.SetServerCmd.class})
public class ConfigCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "show", description = "显示当前配置", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            JsonNode node = CliConfig.load();
            System.out.println("配置文件: " + CliConfig.configFile());
            System.out.println("服务地址: " + CliConfig.server(null));
            System.out.println("登录用户: " + node.path("username").asText("（未登录）"));
            System.out.println("token: " + (node.hasNonNull("token") ? "已保存" : "（无）"));
            return 0;
        }
    }

    @Command(name = "set-server", description = "持久化服务地址到配置文件", mixinStandardHelpOptions = true)
    static class SetServerCmd implements Callable<Integer> {
        @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
        String server;

        @Parameters(index = "0", description = "服务地址，如 http://localhost:8080")
        String url;

        @Override
        public Integer call() {
            if (url == null || url.isBlank()) {
                throw new CliException("用法: markagent config set-server <地址>");
            }
            String finalUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            CliConfig.update(node -> {
                node.put("server", finalUrl);
                return node;
            });
            System.out.println(GREEN + "✓ 服务地址已设为 " + finalUrl + RESET);
            return 0;
        }
    }
}
