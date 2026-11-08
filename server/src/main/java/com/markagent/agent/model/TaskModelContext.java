package com.markagent.agent.model;

/**
 * 任务级模型名上下文。
 * <p>
 * AgentScope 的 ReActAgent 不透传模型名给 {@code Model.stream}（其 buildGenerateOptions 只合并
 * executionConfig，见反编译），任务指定的模型（task.options.model）须由我方显式注入。
 * 由 AgentChatService 在任务虚拟线程入口设置、结束时清除；每任务一线程，天然隔离。
 */
public final class TaskModelContext {

    private static final ThreadLocal<String> MODEL = new ThreadLocal<>();

    private TaskModelContext() {
    }

    public static void set(String modelName) {
        MODEL.set(modelName);
    }

    /** 当前任务指定的模型名；无任务上下文时返回 null */
    public static String get() {
        return MODEL.get();
    }

    public static void clear() {
        MODEL.remove();
    }
}
