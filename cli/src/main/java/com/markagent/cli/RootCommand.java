package com.markagent.cli;

import com.markagent.cli.cmd.ChatCommand;
import com.markagent.cli.cmd.ConfigCommand;
import com.markagent.cli.cmd.LoginCommand;
import com.markagent.cli.cmd.LogoutCommand;
import com.markagent.cli.cmd.McpCommand;
import com.markagent.cli.cmd.MemoryCommand;
import com.markagent.cli.cmd.SessionCommand;
import com.markagent.cli.cmd.SkillCommand;
import com.markagent.cli.cmd.StatusCommand;
import com.markagent.cli.cmd.TaskCommand;
import com.markagent.cli.cmd.WhoamiCommand;
import picocli.CommandLine;

/** 根命令：仅聚合子命令与帮助（-s/--server 由各子命令自持，避免 INHERIT 作用域取值的绕圈） */
@CommandLine.Command(
        name = "markagent",
        mixinStandardHelpOptions = true,
        version = "markagent-cli 0.1.0",
        description = "MarkAgent 终端客户端（连接 markagent-server 的 REST/SSE API）",
        subcommands = {
                LoginCommand.class, LogoutCommand.class, WhoamiCommand.class,
                ChatCommand.class, TaskCommand.class, SessionCommand.class,
                MemoryCommand.class, SkillCommand.class, McpCommand.class,
                ConfigCommand.class, StatusCommand.class
        })
public class RootCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
