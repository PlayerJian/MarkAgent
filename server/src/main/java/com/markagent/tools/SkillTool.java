package com.markagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.SkillRecord;
import com.markagent.skills.SkillRegistry;
import com.markagent.skills.SkillShellRunner;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能调用工具：模型按需读取某技能的 SKILL.md 内容并照其指引执行。
 * 与 sys prompt 中的技能清单配合——清单只给一句话摘要，正文经本工具按需加载（省 token）。
 * <p>
 * 正文替换链（按序）：{{key}} 参数替换 → ${SKILL_DIR} 目录替换 → 内嵌 shell 执行（最后）；
 * allowed_tools 非空时在返回文本末尾追加软约束说明。
 */
@Component
public class SkillTool {

    private final SkillRegistry registry;
    private final Path skillsRoot;
    private final SkillShellRunner shellRunner;
    private final ObjectMapper mapper;

    public SkillTool(SkillRegistry registry, SkillShellRunner shellRunner, ObjectMapper mapper) {
        this.registry = registry;
        this.skillsRoot = registry.skillsRoot();
        this.shellRunner = shellRunner;
        this.mapper = mapper;
    }

    @Tool(name = "skill_invoke", description = "读取指定技能的完整说明（SKILL.md 内容及资源文件列表）。"
            + "当任务与某技能相关时先调用本工具获取技能指引，再按指引执行。"
            + "可选 args 传入 JSON 对象（如 {\"date\":\"2026-11-10\"}），替换正文中的 {{key}} 占位符。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String invoke(@ToolParam(name = "skill", required = true,
            description = "技能名称，如 report-writer") String skillName,
            @ToolParam(name = "args", required = false,
                    description = "可选，JSON 对象字符串，用于替换正文 {{key}} 占位符，如 {\"date\":\"2026-11-10\"}") String args) {
        var loaded = registry.get(skillName);
        if (loaded.isEmpty()) {
            return "错误：技能 " + skillName + " 不存在。可用技能：" + String.join(", ", registry.list()
                    .stream().map(r -> r.name()).toList());
        }
        SkillRecord record = loaded.get().record();
        Map<String, String> params = parseArgs(args);
        if (params == null) {
            return "错误：args 必须是合法 JSON 对象";
        }
        Path dir = skillsRoot.resolve(record.path());
        String content = loaded.get().content();
        // 1) {{key}} 参数替换（未提供的 key 保持原样）
        for (Map.Entry<String, String> entry : params.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        // 2) ${SKILL_DIR} → 技能目录绝对路径（反斜杠转正斜杠）
        content = content.replace("${SKILL_DIR}",
                dir.toAbsolutePath().normalize().toString().replace('\\', '/'));
        // 3) 内嵌 shell 执行（替换链最后一步；双开关关闭时原样保留）
        content = shellRunner.run(record.name(), content, record.shell(), dir);

        StringBuilder sb = new StringBuilder();
        sb.append("# 技能：").append(record.name())
                .append("（版本 ").append(record.version()).append("）\n\n");
        sb.append(content).append('\n');
        // 附加资源文件（如示例、模板），方便模型引用
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
        // 4) allowed_tools 软约束说明（返回文本末尾）
        if (!record.allowedTools().isEmpty()) {
            sb.append("\n\n本技能声明允许的工具：").append(String.join(", ", record.allowedTools()))
                    .append("（声明性建议；需要时优先使用这些工具）");
        }
        return sb.toString();
    }

    /** args → 参数表；非法 JSON 或非对象返回 null（由调用方报错），空/未提供返回空表 */
    private Map<String, String> parseArgs(String args) {
        if (args == null || args.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = mapper.readTree(args);
            if (node == null || !node.isObject()) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            node.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                params.put(entry.getKey(),
                        value == null || value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString());
            });
            return params;
        } catch (IOException e) {
            return null;
        }
    }
}
