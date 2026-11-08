package com.markagent.cli.cmd;

import com.markagent.cli.CliConfig;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** 清除本地 token */
@Command(name = "logout", description = "清除本地保存的 token", mixinStandardHelpOptions = true)
public class LogoutCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        String username = CliConfig.load().path("username").asText(null);
        CliConfig.update(node -> {
            node.remove("token");
            return node;
        });
        System.out.println("✓ 已退出登录" + (username == null ? "" : "（" + username + "）"));
        return 0;
    }
}
