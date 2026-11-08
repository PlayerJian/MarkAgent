package com.markagent.tools;

import com.markagent.skills.SkillRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 技能调用工具：模型按需读取某技能的 SKILL.md 内容并照其指引执行。
 * 与 sys prompt 中的技能清单配合——清单只给一句话摘要，正文经本工具按需加载（省 token）。
 */
@Component
public class SkillTool {

    private final SkillRegistry registry;
    private final Path skillsRoot;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
        this.skillsRoot = registry.skillsRoot();
    }

    @Tool(name = "skill_invoke", description = "读取指定技能的完整说明（SKILL.md 内容及资源文件列表）。"
            + "当任务与某技能相关时先调用本工具获取技能指引，再按指引执行。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String invoke(@ToolParam(name = "skill", required = true,
            description = "技能名称，如 report-writer") String skillName) {
        var loaded = registry.get(skillName);
        if (loaded.isEmpty()) {
            return "错误：技能 " + skillName + " 不存在。可用技能：" + String.join(", ", registry.list()
                    .stream().map(r -> r.name()).toList());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 技能：").append(loaded.get().record().name())
                .append("（版本 ").append(loaded.get().record().version()).append("）\n\n");
        sb.append(loaded.get().content()).append('\n');
        // 附加资源文件（如示例、模板），方便模型引用
        Path dir = skillsRoot.resolve(loaded.get().record().path());
        try (Stream<Path> files = Files.walk(dir)) {
            var extras = files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                    .map(p -> dir.relativize(p).toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (!extras.isEmpty()) {
                sb.append("\n\n## 资源文件\n");
                for (String extra : extras) {
                    sb.append("- ").append(extra).append('\n');
                }
            }
        } catch (IOException e) {
            sb.append("\n（资源列表读取失败：").append(e.getMessage()).append("）");
        }
        return sb.toString();
    }
}
