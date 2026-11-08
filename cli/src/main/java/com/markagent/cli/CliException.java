package com.markagent.cli;

/** CLI 业务错误：主入口统一打印红色错误信息并以退出码 1 结束 */
public class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }
}
