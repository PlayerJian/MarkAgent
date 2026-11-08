package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 任务执行选项（POST /chat 请求体的一部分） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskOptions(
        String model,          // deepseek-flash | deepseek-pro（含 deepseek-v4-* 别名）
        String planning,       // auto | off
        Boolean thinking,      // 思考模式开关（null=默认关闭；flash/pro 均支持）
        List<String> workers   // 多 agent 模式要派生的 worker 角色，如 ["researcher","coder","writer"]
) {
    public TaskOptions {
        planning = planning == null || planning.isBlank() ? "auto" : planning;
        thinking = thinking == null ? Boolean.FALSE : thinking;
        workers = workers == null ? List.of() : workers;
    }

    public static TaskOptions defaults() {
        // model 为 null 由服务端按 markagent.llm.default-model 填充
        return new TaskOptions(null, "auto", Boolean.FALSE, List.of());
    }
}
