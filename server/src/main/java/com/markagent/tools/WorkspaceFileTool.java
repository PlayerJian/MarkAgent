package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作区文件工具：在任务专属沙箱（data/workspace/{taskId}/）内读写文件，
 * 供 agent 落地草稿、计划、中间产物。路径经规范化校验，禁止越出沙箱。
 */
@Component
public class WorkspaceFileTool {

    private final Path workspaceRoot;

    public WorkspaceFileTool(@Value("${markagent.storage.root:./data}") String storageRoot) {
        this.workspaceRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("workspace");
    }

    @Tool(name = "file_read", description = "读取任务工作区内的文本文件。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String read(@ToolParam(name = "path", required = true,
            description = "相对工作区的文件路径，如 drafts/notes.md") String path) {
        try {
            Path file = resolve(path);
            if (!Files.exists(file)) {
                return "错误：文件不存在 " + path;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() > 20000 ? content.substring(0, 20000) + "…（截断）" : content;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "file_write", description = "将内容写入任务工作区的文件（覆盖写，自动创建父目录）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String write(
            @ToolParam(name = "path", required = true,
                    description = "相对工作区的文件路径，如 drafts/notes.md") String path,
            @ToolParam(name = "content", required = true, description = "要写入的文本内容") String content) {
        try {
            String text = content == null ? "" : content;
            Path file = resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return "已写入 " + path + "（" + text.length() + " 字符）";
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "file_append", description = "向任务工作区的文件追加内容（文件不存在则创建）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String append(
            @ToolParam(name = "path", required = true,
                    description = "相对工作区的文件路径") String path,
            @ToolParam(name = "content", required = true, description = "要追加的文本内容") String content) {
        try {
            Path file = resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return "已追加到 " + path;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "file_list", description = "列出任务工作区某目录下的文件（相对路径）。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String list(@ToolParam(name = "path", required = false,
            description = "相对工作区的目录路径，缺省为根目录") String path) {
        try {
            Path dir = resolve(path == null || path.isBlank() ? "." : path);
            if (!Files.isDirectory(dir)) {
                return "错误：目录不存在 " + (path == null ? "." : path);
            }
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .map(p -> (Files.isDirectory(p) ? "[dir]  " : "[file] ") + root().relativize(p))
                        .sorted()
                        .collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 沙箱根：data/workspace/{taskId}；无任务上下文时拒绝 */
    private Path root() {
        String taskId = TaskIdContext.get();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("无任务上下文，工作区不可用");
        }
        return workspaceRoot.resolve(taskId);
    }

    private Path resolve(String path) {
        Path base = root();
        Path resolved = base.resolve(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("路径越出任务工作区：" + path);
        }
        return resolved;
    }
}
