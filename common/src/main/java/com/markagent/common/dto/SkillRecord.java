package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 技能元信息（解析自 data/skills/*\/SKILL.md 的 frontmatter） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillRecord(
        String name,
        String description,
        String version,
        String path,      // 相对 skills/ 的目录
        boolean enabled
) {
}
