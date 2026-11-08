package com.markagent.agent.model;

/**
 * 任务级 taskId 上下文（ThreadLocal，虚拟线程每任务一线程）。
 * <p>
 * AgentScope 的工具方法无法直接拿到我方 taskId（其 ToolCallParam 只有 RuntimeContext），
 * 由 AgentChatService 在任务线程入口设置、结束清除；工具（plan_write 等）经此取 taskId
 * 以更新任务计划、广播 SSE 事件。
 */
public final class TaskIdContext {

    private static final ThreadLocal<String> TASK_ID = new ThreadLocal<>();

    private TaskIdContext() {
    }

    public static void set(String taskId) {
        TASK_ID.set(taskId);
    }

    public static String get() {
        return TASK_ID.get();
    }

    public static void clear() {
        TASK_ID.remove();
    }
}
