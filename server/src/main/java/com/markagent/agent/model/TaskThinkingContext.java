package com.markagent.agent.model;

/**
 * 任务级思考模式开关上下文。
 * <p>
 * 与 {@link TaskModelContext} 同理：AgentScope 的 ReActAgent 不透传任务级参数给
 * {@code Model.stream}，任务指定的思考开关（task.options.thinking）须由我方经 ThreadLocal 注入。
 * 由 AgentChatService 在任务虚拟线程入口设置、结束时清除；每任务一线程，天然隔离。
 * <p>
 * 优先级：GenerateOptions 显式指定 &gt; 本上下文（任务开关）&gt; 默认关闭。
 */
public final class TaskThinkingContext {

    private static final ThreadLocal<Boolean> THINKING = new ThreadLocal<>();

    private TaskThinkingContext() {
    }

    public static void set(boolean enabled) {
        THINKING.set(enabled);
    }

    /** 当前任务指定的思考开关；无任务上下文时返回 null（按默认处理） */
    public static Boolean get() {
        return THINKING.get();
    }

    public static void clear() {
        THINKING.remove();
    }
}
