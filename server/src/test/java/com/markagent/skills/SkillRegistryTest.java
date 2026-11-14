package com.markagent.skills;

import com.markagent.common.dto.SkillRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SkillRegistry frontmatter 解析单测（纯文件解析，不联网、不起 Spring） */
class SkillRegistryTest {

    /** 在临时目录下建 {dirName}/SKILL.md 并写入内容，返回 SKILL.md 路径 */
    private static Path writeSkill(Path root, String dirName, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(dirName));
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, content, StandardCharsets.UTF_8);
        return skillMd;
    }

    @Test
    void 解析全字段frontmatter(@TempDir Path root) throws IOException {
        Path skillMd = writeSkill(root, "report-writer", """
                ---
                name: report-writer
                description: 生成周报
                when_to_use: 用户需要生成周报时
                  也可以用于月报
                  备选: 培训场景
                version: 2.1.0
                allowed_tools: [file_read, file_write, http_get]
                user_invocable: false
                shell: bash
                paths: [reports/**, logs/*.txt]
                model: deepseek-pro
                ---
                # 生成周报
                正文第一段。
                """);
        SkillRegistry.LoadedSkill loaded = SkillRegistry.load(skillMd);
        SkillRecord r = loaded.record();
        assertEquals("report-writer", r.name());
        assertEquals("生成周报", r.description());
        assertEquals("2.1.0", r.version());
        assertEquals("report-writer", r.path());
        assertTrue(r.enabled());
        assertEquals("用户需要生成周报时 也可以用于月报 备选: 培训场景", r.whenToUse());
        assertEquals(List.of("file_read", "file_write", "http_get"), r.allowedTools());
        assertFalse(r.userInvocable());
        assertEquals("bash", r.shell());
        assertEquals(List.of("reports/**", "logs/*.txt"), r.paths());
        assertEquals("deepseek-pro", r.model());
        assertEquals("# 生成周报\n正文第一段。", loaded.content());
    }

    @Test
    void 旧frontmatter兼容默认值(@TempDir Path root) throws IOException {
        Path skillMd = writeSkill(root, "legacy", """
                ---
                name: legacy
                description: 旧技能
                version: 0.5.0
                ---
                旧技能正文
                """);
        SkillRecord r = SkillRegistry.load(skillMd).record();
        assertEquals("legacy", r.name());
        assertEquals("旧技能", r.description());
        assertEquals("0.5.0", r.version());
        assertEquals("", r.whenToUse());
        assertEquals(List.of(), r.allowedTools());
        assertTrue(r.userInvocable());
        assertNull(r.shell());
        assertEquals(List.of(), r.paths());
        assertNull(r.model());
    }

    @Test
    void 无frontmatter技能兼容(@TempDir Path root) throws IOException {
        Path skillMd = writeSkill(root, "plain", "第一行摘要\n第二行正文");
        SkillRegistry.LoadedSkill loaded = SkillRegistry.load(skillMd);
        SkillRecord r = loaded.record();
        assertEquals("plain", r.name());
        assertEquals("1.0.0", r.version());
        assertEquals("第一行摘要", r.description());
        assertEquals("", r.whenToUse());
        assertEquals(List.of(), r.allowedTools());
        assertTrue(r.userInvocable());
        assertNull(r.shell());
        assertEquals("第一行摘要\n第二行正文", loaded.content());
    }

    @Test
    void allowedTools逗号分隔写法(@TempDir Path root) throws IOException {
        Path skillMd = writeSkill(root, "tools", """
                ---
                name: tools
                description: 工具技能
                allowed_tools: calc, file_read,http_get
                paths: *.java, docs/**
                ---
                正文
                """);
        SkillRecord r = SkillRegistry.load(skillMd).record();
        assertEquals(List.of("calc", "file_read", "http_get"), r.allowedTools());
        assertEquals(List.of("*.java", "docs/**"), r.paths());
    }
}
