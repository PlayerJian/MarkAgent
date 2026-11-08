package com.markagent.agent.model;

/**
 * 工具执行线程内的当前用户 ID（经 RuntimeContext 传递，由 MarkAgentToolRegistrar 注入）。
 * 与 {@link TaskIdContext} 同理：ToolExecutor 独立线程池执行工具，ThreadLocal 仅在线程内有效。
 */
public final class TaskUserContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private TaskUserContext() {
    }

    public static void set(String userId) {
        USER_ID.set(userId);
    }

    public static String get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
