package com.markagent.api;

import com.markagent.common.dto.TaskRecord;
import com.markagent.store.TaskStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 任务工作区文件浏览/读取（data/workspace/{taskId}/）。
 * 调研等任务产出的 md 报告经 file_write 落在这里；本接口把工作区以只读方式暴露给前端查看，
 * 路径经沙箱校验（禁止越出任务工作区）。
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    /** 文本内容读取上限（字符），超长截断并标记 truncated */
    private static final int MAX_READ_CHARS = 500_000;

    private final TaskStore taskStore;
    private final Path workspaceRoot;

    public WorkspaceController(TaskStore taskStore, Path storageRoot) {
        this.taskStore = taskStore;
        this.workspaceRoot = storageRoot.resolve("workspace");
    }

    public record FileEntry(String path, boolean dir, long size) {
    }

    public record FileContent(String path, String content, boolean truncated) {
    }

    /** 递归列出任务工作区全部文件/目录（相对路径，正斜杠） */
    @GetMapping("/tasks/{id}/files")
    public List<FileEntry> list(@PathVariable("id") String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        Path root = workspaceRoot.resolve(taskId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<FileEntry> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> !p.equals(root)).forEach(p -> out.add(new FileEntry(
                    root.relativize(p).toString().replace('\\', '/'),
                    Files.isDirectory(p),
                    sizeOf(p))));
        } catch (IOException e) {
            throw new IllegalStateException("工作区扫描失败: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(FileEntry::path));
        return out;
    }

    /** 读取任务工作区文件内容（UTF-8 文本；超长截断并标记） */
    @GetMapping("/tasks/{id}/files/content")
    public FileContent content(@PathVariable("id") String taskId,
            @RequestParam("path") String path, Authentication authentication) {
        requireOwned(taskId, authentication);
        Path root = workspaceRoot.resolve(taskId).toAbsolutePath().normalize();
        Path file = root.resolve(path == null ? "" : path).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw GlobalExceptionHandler.notFound("文件 " + path);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = text.length() > MAX_READ_CHARS;
            if (truncated) {
                text = text.substring(0, MAX_READ_CHARS);
            }
            return new FileContent(path, text, truncated);
        } catch (IOException e) {
            throw new IllegalStateException("文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 任务归属校验：不存在或非本人一律 404（不泄露他人任务存在性） */
    private void requireOwned(String taskId, Authentication authentication) {
        TaskRecord task = taskStore.get(taskId)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("任务 " + taskId));
        if (!task.userId().equals(authentication.getName())) {
            throw GlobalExceptionHandler.notFound("任务 " + taskId);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }
}
