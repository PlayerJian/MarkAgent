package com.markagent.agent.model;

/**
 * 任务绑定的文档库 ID 上下文（ThreadLocal，工具执行线程）。
 * <p>
 * 与 {@link TaskIdContext} 同模式：工具在独立线程执行，ThreadLocal 不可达，
 * 由 MarkAgentToolRegistrar 在每次工具调用入口从 RuntimeContext 注入、结束清除。
 * docLibraryId 随 RuntimeContext 与工具调用同行，不依赖任务线程生命周期。
 */
public final class TaskDocContext {

    private static final ThreadLocal<String> DOC_LIBRARY_ID = new ThreadLocal<>();

    private TaskDocContext() {
    }

    public static void set(String docLibraryId) {
        DOC_LIBRARY_ID.set(docLibraryId);
    }

    public static String get() {
        return DOC_LIBRARY_ID.get();
    }

    public static void clear() {
        DOC_LIBRARY_ID.remove();
    }
}
