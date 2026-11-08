package com.markagent.agent.model;

import org.springframework.ai.deepseek.api.DeepSeekApi;

/**
 * 模型名 → Spring AI ChatModel 枚举映射（含常用别名归一）。
 * <p>
 * 现役模型仅 deepseek-flash（极速）与 deepseek-pro（旗舰）两个：
 * deepseek-flash/deepseek-pro 为 deepseek-v4-flash/deepseek-v4-pro 的别名。
 * deepseek-chat / deepseek-reasoner 已废弃，一律归一为默认模型。
 */
public final class DeepSeekModels {

    private DeepSeekModels() {
    }

    public static DeepSeekApi.ChatModel of(String modelName) {
        String name = modelName == null ? "deepseek-flash" : modelName.trim().toLowerCase();
        return switch (name) {
            case "deepseek-pro", "deepseek-v4-pro" -> DeepSeekApi.ChatModel.DEEPSEEK_V4_PRO;
            default -> DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH; // deepseek-flash 及一切未知/废弃名
        };
    }
}
