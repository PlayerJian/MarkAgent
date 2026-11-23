package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * file_edit 精确编辑工具：在任务专属沙箱（data/workspace/{taskId}/）内按原文片段查找替换。
 * <p>
 * 与 file_write 整文件覆盖不同，本工具只改局部：模型给出 old_string，工具做精确匹配后替换；
 * 匹配 0 次或不唯一时报错并引导模型修正，避免幻觉性批量改动。匹配前把内容与 old/new 的行尾
 * （\r\n、\r、\n）统一归一化为 \n，写入时还原原文件行尾风格。同一文件经 per-file 锁串行
 * 「读-改-写」，防止并发编辑撕裂文件。
 */
@Component
public class FileEditTool {

    /** 单文件大小上限（1MB），超过拒绝编辑，避免大文件全量读入内存 */
    static final long MAX_FILE_SIZE = 1024L * 1024L;

    /** replace_all 单次最多替换处数，超过报错，防止意外批量改动 */
    static final int MAX_REPLACE_ALL = 100;

    /** 匹配失败时回显的文件片段长度（字符），供模型校正 old_string */
    private static final int PREVIEW_CHARS = 500;

    private final Path workspaceRoot;

    /**
     * per-file 锁表：同一文件的编辑串行执行。锁对象常驻不回收——任务内文件数量有限，
     * 回收反而会引入锁替换竞态（旧锁等待者与新锁持有者并行进入临界区）。
     */
    private final Map<Path, Object> fileLocks = new ConcurrentHashMap<>();

    public FileEditTool(@Value("${markagent.storage.root:./data}") String storageRoot) {
        this.workspaceRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("workspace");
    }

    @Tool(name = "file_edit", description = "精确编辑任务工作区内的文本文件：按原文片段查找并替换，"
            + "缺省要求唯一匹配；replace_all=true 时替换全部匹配（上限 100 处）。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String edit(
            @ToolParam(name = "path", required = true,
                    description = "相对任务工作区的文件路径，如 drafts/notes.md") String path,
            @ToolParam(name = "old_string", required = true,
                    description = "要替换的原文片段，须与文件内容精确一致（行尾差异自动忽略）") String oldString,
            @ToolParam(name = "new_string", required = true,
                    description = "替换后的文本，允许空字符串（等价于删除 old_string）") String newString,
            @ToolParam(name = "replace_all", required = false,
                    description = "true=替换全部匹配（上限 100 处，超过报错）；false=要求唯一匹配（缺省）")
            Boolean replaceAll) {
        try {
            Path file = resolve(path);
            synchronized (fileLocks.computeIfAbsent(file, k -> new Object())) {
                return doEdit(file, path, oldString, newString, replaceAll);
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 锁内执行的读-改-写全流程 */
    private String doEdit(Path file, String path, String oldString, String newString, Boolean replaceAll)
            throws IOException {
        if (!Files.exists(file)) {
            return "错误：文件不存在 " + path;
        }
        if (Files.size(file) > MAX_FILE_SIZE) {
            return "错误：文件过大（超过 1MB），拒绝编辑 " + path;
        }
        if (oldString == null || oldString.isEmpty()) {
            return "错误：old_string 不能为空串";
        }
        String newText = newString == null ? "" : newString;

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String eol = detectLineEnding(raw);
        // 归一化：内容与 old/new 的行尾统一为 \n 再做匹配，避免 CRLF/LF 差异导致匹配失败
        String content = normalizeLineEndings(raw);
        String needle = normalizeLineEndings(oldString);
        String replacement = normalizeLineEndings(newText);

        int count = countOccurrences(content, needle);
        if (count == 0) {
            String preview = raw.length() > PREVIEW_CHARS ? raw.substring(0, PREVIEW_CHARS) : raw;
            return "错误：未找到要替换的原文片段，文件前 500 字符（供校正）：\n" + preview;
        }
        boolean all = Boolean.TRUE.equals(replaceAll);
        if (!all && count > 1) {
            return "错误：原文片段匹配不唯一（" + count + " 处），请提供更长的上下文或设置 replace_all=true";
        }
        if (all && count > MAX_REPLACE_ALL) {
            return "错误：匹配 " + count + " 处，超过 replace_all 上限 " + MAX_REPLACE_ALL + " 处，拒绝替换";
        }
        String updated = all ? content.replace(needle, replacement)
                : replaceFirst(content, needle, replacement);
        // 还原原文件行尾风格；无换行的单行文件回退系统默认行尾
        String output = restoreLineEndings(updated, eol != null ? eol : System.lineSeparator());
        Files.writeString(file, output, StandardCharsets.UTF_8);

        int delta = count * (newText.length() - oldString.length());
        String deltaText = delta >= 0 ? "+" + delta : String.valueOf(delta);
        return "已完成编辑 " + path + "：替换 " + count + " 处（" + deltaText + " 字符）";
    }

    // ==================== 包级静态纯函数（行尾归一化 + 还原，供单测） ====================

    /** 行尾归一化：\r\n、\r 全部转为 \n（必须先替换 \r\n，避免残留 \r） */
    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 判定文本行尾风格：按首个换行符判定，\r\n 优先；
     * 无任何换行符返回 null（调用方回退系统默认行尾）。
     */
    static String detectLineEnding(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
            }
            if (c == '\n') {
                return "\n";
            }
        }
        return null;
    }

    /** 还原行尾：把归一化后的 \n 转为目标行尾风格；null 或 \n 表示不转换 */
    static String restoreLineEndings(String text, String lineEnding) {
        if (lineEnding == null || "\n".equals(lineEnding)) {
            return text;
        }
        return text.replace("\n", lineEnding);
    }

    /** 统计 needle 在 text 中的非重叠匹配次数（空 needle 返回 0） */
    static int countOccurrences(String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 替换首个匹配；未匹配则原样返回 */
    static String replaceFirst(String text, String needle, String replacement) {
        int idx = text.indexOf(needle);
        return idx < 0 ? text : text.substring(0, idx) + replacement + text.substring(idx + needle.length());
    }

    // ==================== 沙箱路径解析（与 WorkspaceFileTool 同约定） ====================

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
