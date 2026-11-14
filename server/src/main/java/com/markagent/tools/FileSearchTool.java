package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件搜索工具：在任务专属沙箱（data/workspace/{taskId}/）内按正则/glob 搜索文件，
 * 对齐 Claude Code file_grep / file_glob 的语义——
 * <ul>
 *   <li>{@link #grep}：逐行正则匹配（Java 正则），支持 glob 文件名过滤与子目录起点，
 *       跳过 .git 目录、大于 1MB 的文件与二进制文件，输出「相对路径:行号: 行内容」并按路径排序</li>
 *   <li>{@link #glob}：按 glob 模式列出匹配的文件与目录（目录加 [dir] 前缀），上限 200 条</li>
 * </ul>
 * 沙箱路径解析与 {@link WorkspaceFileTool} 完全一致：{@code data/workspace/{taskId}}，
 * 路径经规范化校验，禁止越出沙箱；无任务上下文时拒绝。
 */
@Component
public class FileSearchTool {

    /** 跳过的大文件阈值：1MB（防正则搜索撑爆内存） */
    static final long MAX_FILE_BYTES = 1024L * 1024;

    /** 二进制探测字节数：前 8192 字节含 NUL 即视为二进制跳过 */
    static final int BINARY_PROBE_BYTES = 8192;

    /** file_grep 缺省匹配行数上限 */
    static final int DEFAULT_MAX_RESULTS = 20;

    /** file_grep 允许的最大匹配行数（入参超出部分截到该值） */
    static final int HARD_MAX_RESULTS = 50;

    /** file_glob 结果条数上限 */
    static final int GLOB_MAX_RESULTS = 200;

    /** 单行内容截断长度 */
    static final int MAX_LINE_CHARS = 300;

    /** 总输出截断长度 */
    static final int MAX_OUTPUT_CHARS = 20000;

    private final Path workspaceRoot;

    public FileSearchTool(@Value("${markagent.storage.root:./data}") String storageRoot) {
        this.workspaceRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("workspace");
    }

    @Tool(name = "file_grep", description = "在任务工作区内按正则表达式逐行搜索文本文件内容（类似 grep -rn）。"
            + "pattern 为 Java 正则（逐行匹配）；glob 按文件名过滤（如 **/*.java、*.md、**/*.{java,kt}，"
            + "匹配相对工作区的路径，缺省匹配所有文件）；path 为相对工作区的起始目录（缺省工作区根目录）；"
            + "max_results 限制匹配行数（缺省 20，最大 50）。跳过 .git 目录、大于 1MB 的文件与二进制文件；"
            + "输出「相对路径:行号: 行内容」并按路径排序，行内容截断 300 字符。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String grep(
            @ToolParam(name = "pattern", required = true,
                    description = "Java 正则表达式，逐行匹配文件内容") String pattern,
            @ToolParam(name = "glob", required = false,
                    description = "文件名过滤（glob 模式），匹配相对工作区的路径，如 **/*.java、*.md、**/*.{java,kt}；缺省匹配所有文件") String glob,
            @ToolParam(name = "path", required = false,
                    description = "相对工作区的起始目录，缺省为工作区根目录") String path,
            @ToolParam(name = "max_results", required = false,
                    description = "匹配行数上限，缺省 20，最大 50") Integer maxResults) {
        try {
            if (pattern == null || pattern.isBlank()) {
                return "错误：pattern 不能为空";
            }
            Pattern regex;
            try {
                regex = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                return "错误：正则表达式无效：" + pattern;
            }
            int limit = maxResults == null ? DEFAULT_MAX_RESULTS
                    : Math.min(Math.max(maxResults, 1), HARD_MAX_RESULTS);
            Path base = root();
            Path start = path == null || path.isBlank() ? base : resolve(path);
            if (!Files.isDirectory(start)) {
                return "错误：目录不存在 " + (path == null || path.isBlank() ? "." : path);
            }
            PathMatcher globMatcher = (glob == null || glob.isBlank()) ? null : compileGlob(glob);
            List<Hit> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(start)) {
                for (Path p : stream
                        .filter(p -> !Files.isSymbolicLink(p))
                        .filter(p -> !inGitDir(start, p))
                        .filter(p -> !Files.isDirectory(p))
                        .toList()) {
                    if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES || isBinary(p)) {
                        continue;
                    }
                    if (globMatcher != null && !globMatcher.matches(base.relativize(p))) {
                        continue;
                    }
                    collectHits(p, base, regex, hits);
                }
            }
            if (hits.isEmpty()) {
                return "无匹配结果";
            }
            // 按路径（再按行号）排序，保证确定性输出
            hits.sort(Comparator.comparing(Hit::file).thenComparingInt(Hit::line));
            boolean capped = hits.size() > limit;
            List<String> lines = new ArrayList<>();
            for (Hit hit : hits.subList(0, Math.min(hits.size(), limit))) {
                lines.add(hit.file() + ":" + hit.line() + ": " + hit.text());
            }
            if (capped) {
                lines.add("已达上限 " + limit + " 条");
            }
            String out = String.join("\n", lines);
            return out.length() > MAX_OUTPUT_CHARS
                    ? out.substring(0, MAX_OUTPUT_CHARS) + "…（截断）" : out;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "file_glob", description = "在任务工作区内按 glob 模式列出匹配的文件与目录（类似 Glob 工具）。"
            + "pattern 为 glob 模式（如 **/*.java、*.md、**/*.{java,kt}），匹配相对工作区的路径；"
            + "path 为相对工作区的起始目录（缺省工作区根目录）。目录以 [dir] 前缀标记，"
            + "结果按路径排序，上限 200 条。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String glob(
            @ToolParam(name = "pattern", required = true,
                    description = "glob 模式，如 **/*.java、*.md、**/*.{java,kt}") String pattern,
            @ToolParam(name = "path", required = false,
                    description = "相对工作区的起始目录，缺省为工作区根目录") String path) {
        try {
            if (pattern == null || pattern.isBlank()) {
                return "错误：pattern 不能为空";
            }
            Path base = root();
            Path start = path == null || path.isBlank() ? base : resolve(path);
            if (!Files.isDirectory(start)) {
                return "错误：目录不存在 " + (path == null || path.isBlank() ? "." : path);
            }
            PathMatcher matcher = compileGlob(pattern);
            List<Path> matched = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(start)) {
                stream.filter(p -> !p.equals(start)) // 起始目录本身不列出
                        .filter(p -> !Files.isSymbolicLink(p))
                        .filter(p -> !inGitDir(start, p))
                        .filter(p -> matcher.matches(base.relativize(p)))
                        .forEach(matched::add);
            }
            if (matched.isEmpty()) {
                return "无匹配结果";
            }
            matched.sort(Comparator.comparing(p -> base.relativize(p).toString()));
            int shown = Math.min(matched.size(), GLOB_MAX_RESULTS);
            List<String> lines = new ArrayList<>();
            for (Path p : matched.subList(0, shown)) {
                lines.add((Files.isDirectory(p) ? "[dir]  " : "") + base.relativize(p));
            }
            if (matched.size() > GLOB_MAX_RESULTS) {
                lines.add("已达上限 " + GLOB_MAX_RESULTS + " 条");
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 逐行正则匹配单文件：命中行以「相对工作区路径」收集（行内容截断 300 字符） */
    private static void collectHits(Path file, Path base, Pattern regex, List<Hit> hits) throws IOException {
        String rel = base.relativize(file).toString();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (regex.matcher(line).find()) {
                    hits.add(new Hit(rel, lineNo, line.length() > MAX_LINE_CHARS
                            ? line.substring(0, MAX_LINE_CHARS) + "…" : line));
                }
            }
        }
    }

    /** 前 {@link #BINARY_PROBE_BYTES} 字节含 NUL 即视为二进制 */
    private static boolean isBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int n = in.readNBytes(buf, 0, buf.length);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 路径是否落在 .git 目录内（含嵌套 .git；对整个子树剪枝） */
    private static boolean inGitDir(Path start, Path p) {
        for (Path part : start.relativize(p)) {
            if (".git".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 编译 glob 模式；非法模式抛 IllegalArgumentException，由工具方法捕获返回错误文本 */
    private PathMatcher compileGlob(String glob) {
        try {
            return workspaceRoot.getFileSystem().getPathMatcher("glob:" + glob);
        } catch (Exception e) {
            throw new IllegalArgumentException("glob 模式无效：" + glob, e);
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

    /** 沙箱内路径解析：规范化后必须仍在沙箱根内，否则拒绝（与 WorkspaceFileTool 一致） */
    private Path resolve(String path) {
        Path base = root();
        Path resolved = base.resolve(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("路径越出任务工作区：" + path);
        }
        return resolved;
    }

    /** 单条匹配：相对路径 + 行号 + 行内容 */
    private record Hit(String file, int line, String text) {
    }
}
