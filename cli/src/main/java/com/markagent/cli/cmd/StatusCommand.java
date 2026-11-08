package com.markagent.cli.cmd;

import com.markagent.cli.ApiClient;
import com.markagent.cli.CliConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/** 服务健康状态（公开端点，无需登录） */
@Command(name = "status", description = "服务健康状态（无需登录）", mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "服务地址（默认 ${DEFAULT-VALUE}）")
    String server;

    @Override
    public Integer call() {
        String base = CliConfig.server(server);
        System.out.println(new ApiClient(base, null).get("/api/v1/status").toPrettyString());
        return 0;
    }
}
