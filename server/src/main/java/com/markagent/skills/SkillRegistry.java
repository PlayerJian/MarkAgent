package com.markagent.skills;

import com.markagent.common.dto.SkillRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能仓库：扫描 data/skills/{name}/SKILL.md（frontmatter: name/description/version/when_to_use/
 * allowed_tools/user_invocable/shell/paths/model），提供列表/读取/创建/删除/重载。
 * 加载后缓存于内存，文件变动经 reload 生效。
 * 技能内容通过 sys prompt 清单 + skill_invoke 工具暴露给 agent。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 已加载技能：name → (record, 正文) */
    public record LoadedSkill(SkillRecord record, String content) {
    }

    private final Path skillsRoot;
    private final Map<String, LoadedSkill> skills = new ConcurrentHashMap<>();

    public SkillRegistry(@Value("${markagent.storage.root:./data}") String storageRoot) {
        this.skillsRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("skills");
    }

    /** skills 根目录（工具层读取资源用） */
    public Path skillsRoot() {
        return skillsRoot;
    }

    @PostConstruct
    void init() {
        reload();
    }

    /** 全量重扫 skills/ 目录 */
    public synchronized void reload() {
        Map<String, LoadedSkill> next = new ConcurrentHashMap<>();
        try {
            Files.createDirectories(skillsRoot);
            try (Stream<Path> dirs = Files.list(skillsRoot)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillMd)) {
                        continue;
                    }
                    try {
                        LoadedSkill skill = load(skillMd);
                        next.put(skill.record().name(), skill);
                    } catch (Exception e) {
                        log.warn("技能加载失败 {}: {}", dir.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("技能目录扫描失败: {}", e.getMessage());
        }
        skills.clear();
        skills.putAll(next);
        log.info("技能仓库重载完成：{} 个技能", skills.size());
    }

    /** 解析单个 SKILL.md：--- 包围的 frontmatter + 正文。
     *  无 frontmatter 或仅含旧字段（name/description/version）的技能完全兼容，
     *  新字段（when_to_use 等）缺省时取默认值。 */
    static LoadedSkill load(Path skillMd) throws IOException {
        String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
        String dirName = skillMd.getParent().getFileName().toString();
        String name = dirName;
        String description = "";
        String version = "1.0.0";
        String whenToUse = "";
        List<String> allowedTools = List.of();
        boolean userInvocable = true;
        String shell = null;
        List<String> paths = List.of();
        String model = null;
        String content = raw;
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String frontmatter = raw.substring(3, end);
                String currentKey = null;
                for (String rawLine : frontmatter.split("\n")) {
                    // 多行值：以空格/制表符开头的后续行并入上一字段（如 when_to_use）
                    if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                        if ("when_to_use".equals(currentKey)) {
                            whenToUse += " " + rawLine.trim();
                        }
                        continue;
                    }
                    int colon = rawLine.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = rawLine.substring(0, colon).trim();
                    String value = rawLine.substring(colon + 1).trim();
                    currentKey = key;
                    switch (key) {
                        case "name" -> name = value;
                        case "description" -> description = value;
                        case "version" -> version = value;
                        case "when_to_use" -> whenToUse = value;
                        case "allowed_tools" -> allowedTools = parseList(value);
                        case "user_invocable" -> userInvocable = !"false".equalsIgnoreCase(value);
                        case "shell" -> shell = value.isEmpty() ? null : value;
                        case "paths" -> paths = parseList(value);
                        case "model" -> model = value.isEmpty() ? null : value;
                        default -> { /* 忽略其他字段 */ }
                    }
                }
                content = raw.substring(end + 4).strip();
            }
        }
        if (description.isEmpty() && !content.isEmpty()) {
            description = content.lines().findFirst().orElse("").strip();
            if (description.length() > 80) {
                description = description.substring(0, 80) + "…";
            }
        }
        return new LoadedSkill(
                new SkillRecord(name, description, version, dirName, true,
                        whenToUse, allowedTools, userInvocable, shell, paths, model),
                content);
    }

    /** 列表字段解析：逗号分隔（a, b, c）或方括号形式（[a, b, c]） */
    private static List<String> parseList(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> items = new ArrayList<>();
        for (String item : trimmed.split(",")) {
            String s = item.trim();
            if (!s.isEmpty()) {
                items.add(s);
            }
        }
        return items;
    }

    public List<SkillRecord> list() {
        return skills.values().stream()
                .map(LoadedSkill::record)
                .sorted(Comparator.comparing(SkillRecord::name))
                .toList();
    }

    public Optional<LoadedSkill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /** 创建/更新技能：写 skills/{name}/SKILL.md（frontmatter 由调用方组装好传入） */
    public LoadedSkill save(String name, String skillMdContent) throws IOException {
        String safe = sanitize(name);
        Path dir = skillsRoot.resolve(safe);
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, skillMdContent, StandardCharsets.UTF_8);
        LoadedSkill skill = load(skillMd);
        skills.put(skill.record().name(), skill);
        return skill;
    }

    /** 删除技能目录 */
    public boolean delete(String name) {
        String safe = sanitize(name);
        Path dir = skillsRoot.resolve(safe).normalize();
        if (!dir.startsWith(skillsRoot) || dir.equals(skillsRoot)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("技能删除失败 {}: {}", name, e.getMessage());
            return false;
        }
        skills.remove(name);
        return true;
    }

    /** 技能清单（注入 sys prompt）：名称 + 一句话描述 */
    public String promptSection() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 可用技能（skills）\n");
        for (SkillRecord record : list()) {
            sb.append("- ").append(record.name()).append("：").append(record.description()).append('\n');
        }
        sb.append("需要按技能执行时，先用 skill_invoke 工具读取技能内容再照做。\n");
        return sb.toString();
    }

    /** 名称白名单：只允许字母数字与 - _ ，防目录穿越 */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名不能为空");
        }
        String safe = name.replaceAll("[^a-zA-Z0-9\\-_]", "-");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("非法技能名：" + name);
        }
        return safe;
    }

    /** 上传 zip 解压：zip 内每个顶层目录作为技能目录，需含 SKILL.md */
    public List<String> installZip(Path zipFile) throws IOException {
        List<String> installed = new ArrayList<>();
        Path tmp = Files.createTempDirectory("skill-upload-");
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = tmp.resolve(entry.getName()).normalize();
                if (!target.startsWith(tmp)) {
                    continue; // 防 zip slip
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
        try (Stream<Path> dirs = Files.list(tmp)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(dir.resolve("SKILL.md"))) {
                    continue;
                }
                String name = dir.getFileName().toString();
                String content = Files.readString(dir.resolve("SKILL.md"), StandardCharsets.UTF_8);
                save(name, content);
                installed.add(name);
            }
        } finally {
            try (Stream<Path> walk = Files.walk(tmp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ignored) {
                // 临时目录清理失败不影响结果
            }
        }
        return installed;
    }
}
