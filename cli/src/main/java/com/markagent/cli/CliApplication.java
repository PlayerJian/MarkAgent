package com.markagent.cli;

import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** MarkAgent 终端客户端入口 */
public final class CliApplication {

    public static void main(String[] args) {
        // 输出固定 UTF-8：Windows 下管道/重定向默认 GBK，中文会乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        CommandLine cmd = new CommandLine(new RootCommand());
        // 业务错误统一输出：红色一行 + 退出码 1（不做堆栈噪音）
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            System.err.println(SseStreamRenderer.RED + "✗ " + message + SseStreamRenderer.RESET);
            if (Boolean.getBoolean("markagent.debug")) {
                ex.printStackTrace();
            }
            return 1;
        });
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}
