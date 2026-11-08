package com.markagent.api;

import com.markagent.common.dto.SkillRecord;
import com.markagent.skills.SkillRegistry;
import com.markagent.skills.SkillRegistry.LoadedSkill;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.markagent.api.GlobalExceptionHandler.notFound;

/** 技能管理：列表/详情/创建/删除/重载/zip 上传 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillRegistry registry;

    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<SkillRecord> list() {
        return registry.list();
    }

    @GetMapping("/{name}")
    public Map<String, Object> detail(@PathVariable String name) {
        LoadedSkill skill = registry.get(name).orElseThrow(() -> notFound("技能 " + name + " 不存在"));
        return Map.of(
                "name", skill.record().name(),
                "description", skill.record().description(),
                "version", skill.record().version(),
                "path", skill.record().path(),
                "enabled", skill.record().enabled(),
                "content", skill.content());
    }

    /** 创建/更新：body 为 SKILL.md 全文（含 frontmatter） */
    @PostMapping
    public SkillRecord save(@RequestBody Map<String, String> body) throws IOException {
        String name = body.get("name");
        String content = body.get("content");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("SKILL.md 内容不能为空");
        }
        return registry.save(name, content).record();
    }

    @DeleteMapping("/{name}")
    public Map<String, Boolean> delete(@PathVariable String name) {
        boolean ok = registry.delete(name);
        if (!ok) {
            throw new com.markagent.api.GlobalExceptionHandler.ApiException(
                    HttpStatus.NOT_FOUND, "NOT_FOUND", "技能 " + name + " 不存在");
        }
        return Map.of("deleted", true);
    }

    @PostMapping("/{name}/reload")
    public Map<String, Object> reload(@PathVariable String name) {
        registry.reload();
        LoadedSkill skill = registry.get(name)
                .orElseThrow(() -> notFound("技能 " + name + " 不存在（重载后仍未找到）"));
        return Map.of("name", skill.record().name(), "version", skill.record().version());
    }

    /** zip 上传：每个含 SKILL.md 的顶层目录注册为一个技能 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        Path tmp = Files.createTempFile("skill-upload-", ".zip");
        file.transferTo(tmp);
        try {
            List<String> installed = registry.installZip(tmp);
            return Map.of("installed", installed);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
