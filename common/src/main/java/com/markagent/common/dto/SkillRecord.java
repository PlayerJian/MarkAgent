package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 技能元信息（解析自 data/skills/*\/SKILL.md 的 frontmatter）。
 * ignoreUnknown = true：前端/CLI 传入含未知字段的旧 JSON 时反序列化不失败（与 SessionRecord 同做法）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillRecord(
        String name,
        String description,
        String version,
        String path,      // 相对 skills/ 的目录
        boolean enabled,
        String whenToUse,            // 触发场景说明（v1 仅存储）
        List<String> allowedTools,   // 技能声明允许使用的工具（软约束建议）
        boolean userInvocable,       // false = 仅供模型内部使用（v1 仅存储，不影响行为）
        String shell,                // bash / powershell / cmd；null = 禁用内嵌 shell
        List<String> paths,          // 条件技能触发 glob（v1 只解析存储，不启用激活逻辑）
        String model                 // 建议模型（v1 仅存储）
) {
}
