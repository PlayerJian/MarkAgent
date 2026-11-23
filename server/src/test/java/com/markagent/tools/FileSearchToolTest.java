package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileSearchTool 单测：@TempDir 建样例目录树（嵌套目录、多扩展名、2MB 大文件、
 * 含 NUL 的假二进制、.git 目录），验证 file_grep / file_glob 的匹配、glob 过滤、
 * path 起点、max_results 截断、跳过规则、沙箱越界拒绝与错误提示。
 */
class FileSearchToolTest {

    /** 长目录名（150 字符）：用于触发 20000 字符总输出截断 */
    private static final String LONG_DIR = "d".repeat(150);

    @TempDir
    Path tmp;

    /** 沙箱根：tmp/workspace/task-1（对齐 WorkspaceFileTool 的 data/workspace/{taskId} 布局） */
    private Path taskRoot;

    @BeforeEach
    void setUp() throws IOException {
        TaskIdContext.set("task-1");
        taskRoot = tmp.resolve("workspace").resolve("task-1");
        Files.createDirectories(taskRoot);

        write("src/main/java/App.java", "public class App {\n    int value = 42;\n}\n");
        write("src/main/java/Helper.java", "class Helper {\n    int noMatch = 0;\n}\n");
        write("src/main/resources/app.properties", "value=42\nname=foo\n");
        write("docs/readme.md", "# Readme\nValue=42\n");
        write("notes.txt", "just a note\n");
        write(".git/config", "GIT_NEEDLE\n");

        // 2MB 大文件：内含匹配行，验证按大小跳过
        byte[] big = new byte[2 * 1024 * 1024];
        byte[] head = "BIG_NEEDLE\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, big, 0, head.length);
        Files.write(taskRoot.resolve("big.bin"), big);

        // 含 NUL 的假二进制：NUL 之前有匹配串，验证按二进制跳过
        Files.write(taskRoot.resolve("fake.bin"),
                new byte[]{'B', 'I', 'N', '_', 'N', 'E', 'E', 'D', 'L', 'E', 0, 1, 2, 3});

        // 60 行匹配文件：验证 max_results 截断
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            many.append("hit line ").append(i).append('\n');
        }
        write("many.txt", many.toString());

        // 超长行：验证单行 300 字符截断
        write("long.txt", "a".repeat(400) + "needle1\n");

        // 长目录名 + 60 行超长匹配行：验证总输出 20000 字符截断
        Path longDir = taskRoot.resolve(LONG_DIR);
        Files.createDirectories(longDir);
        StringBuilder longLines = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longLines.append("needle2").append("x".repeat(400)).append('\n');
        }
        Files.writeString(longDir.resolve("f.txt"), longLines.toString(), StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        TaskIdContext.clear();
    }

    /** 相对沙箱根写文件（自动建父目录） */
    private void write(String rel, String content) throws IOException {
        Path file = taskRoot.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private FileSearchTool newTool() {
        return new FileSearchTool(tmp.toString());
    }

    /** 平台原生相对路径（Windows 下为反斜杠），对齐 Path.toString() 的输出 */
    private static String rel(String path) {
        return Path.of(path).toString();
    }

    // ==================== file_grep：pattern 命中 ====================

    @Test
    void grepFindsPatternMatches() {
        // 其余文件要么不匹配、要么被跳过（big.bin/fake.bin/.git），结果唯一且确定
        String out = newTool().grep("value=42", null, null, null);
        assertEquals(rel("src/main/resources/app.properties") + ":1: value=42", out);
    }

    @Test
    void grepMatchesJavaRegexSemantics() {
        // \\d+ 数字匹配、按行匹配
        assertEquals(rel("src/main/resources/app.properties") + ":1: value=42",
                newTool().grep("value=\\d+", null, null, null));
    }

    // ==================== file_grep：glob 过滤 ====================

    @Test
    void grepGlobFiltersByExtension() {
        assertEquals(rel("src/main/java/App.java") + ":2:     int value = 42;",
                newTool().grep("42", "**/*.java", null, null));
    }

    @Test
    void grepGlobMatchesWorkspaceRelativePath() {
        // glob 匹配相对工作区的路径：docs/*.md 命中 docs/readme.md
        assertEquals(rel("docs/readme.md") + ":2: Value=42",
                newTool().grep("42", "docs/*.md", null, null));
    }

    @Test
    void grepGlobWithBraces() {
        assertEquals(rel("src/main/java/App.java") + ":2:     int value = 42;",
                newTool().grep("42", "**/*.{java,kt}", null, null));
    }

    // ==================== file_grep：path 起点 ====================

    @Test
    void grepPathStartsFromSubdirectory() {
        String out = newTool().grep("42", null, "src", null);
        assertEquals(rel("src/main/java/App.java") + ":2:     int value = 42;\n"
                + rel("src/main/resources/app.properties") + ":1: value=42", out);
    }

    @Test
    void grepRejectsNonDirectoryPath() {
        assertEquals("错误：目录不存在 notes.txt", newTool().grep("x", null, "notes.txt", null));
        assertEquals("错误：目录不存在 missing", newTool().grep("x", null, "missing", null));
    }

    // ==================== file_grep：max_results 截断 ====================

    @Test
    void grepMaxResultsDefaultsTo20() {
        List<String> lines = List.of(newTool().grep("hit line", null, null, null).split("\n"));
        assertEquals(21, lines.size());
        assertEquals("many.txt:1: hit line 0", lines.get(0));
        assertEquals("many.txt:20: hit line 19", lines.get(19));        assertEquals("已达上限 20 条", lines.get(20));
    }

    @Test
    void grepMaxResultsExplicit() {
        List<String> lines = List.of(newTool().grep("hit line", null, null, 5).split("\n"));
        assertEquals(6, lines.size());
        assertEquals("已达上限 5 条", lines.get(5));
    }

    @Test
    void grepMaxResultsClampedAt50() {
        List<String> lines = List.of(newTool().grep("hit line", null, null, 100).split("\n"));
        assertEquals(51, lines.size());
        assertEquals("已达上限 50 条", lines.get(50));
    }

    // ==================== file_grep：二进制 / 大文件 / .git 跳过 ====================

    @Test
    void grepSkipsLargeFile() {
        // BIG_NEEDLE 仅存在于 2MB 大文件中
        assertEquals("无匹配结果", newTool().grep("BIG_NEEDLE", null, null, null));
    }

    @Test
    void grepSkipsBinaryFile() {
        // BIN_NEEDLE 仅存在于含 NUL 的假二进制中
        assertEquals("无匹配结果", newTool().grep("BIN_NEEDLE", null, null, null));
    }

    @Test
    void grepSkipsGitDirectory() {
        // GIT_NEEDLE 仅存在于 .git/config 中
        assertEquals("无匹配结果", newTool().grep("GIT_NEEDLE", null, null, null));
    }

    // ==================== file_grep：行内容与总输出截断 ====================

    @Test
    void grepTruncatesLineContentAt300Chars() {
        assertEquals("long.txt:1: " + "a".repeat(300) + "…",
                newTool().grep("needle1", null, null, null));
    }

    @Test
    void grepTruncatesTotalOutputAt20000Chars() {
        String out = newTool().grep("needle2", null, LONG_DIR, 50);
        assertTrue(out.endsWith("…（截断）"));
        assertEquals(FileSearchTool.MAX_OUTPUT_CHARS + "…（截断）".length(), out.length());
    }

    // ==================== 沙箱边界与错误提示 ====================

    @Test
    void grepRejectsPathEscape() {
        assertTrue(newTool().grep("x", null, "../evil", null).startsWith("错误：路径越出任务工作区"));
        assertTrue(newTool().grep("x", null, "../task-2", null).contains("路径越出"));
        // 绝对路径也不得越出沙箱
        assertTrue(newTool().grep("x", null, tmp.toString(), null).contains("路径越出"));
    }

    @Test
    void grepRejectsInvalidRegex() {
        assertEquals("错误：正则表达式无效：[", newTool().grep("[", null, null, null));
    }

    @Test
    void grepRejectsInvalidGlob() {
        assertTrue(newTool().grep("x", "[abc", null, null).contains("glob 模式无效"));
    }

    @Test
    void grepFailsWithoutTaskContext() {
        TaskIdContext.clear();
        assertEquals("错误：无任务上下文，工作区不可用", newTool().grep("x", null, null, null));
    }

    // ==================== file_glob ====================

    @Test
    void globListsMatchingFilesSorted() {
        assertEquals(rel("src/main/java/App.java") + "\n" + rel("src/main/java/Helper.java"),
                newTool().glob("**/*.java", null));
    }

    @Test
    void globSupportsBracePatterns() {
        assertEquals(rel("src/main/java/App.java") + "\n" + rel("src/main/java/Helper.java"),
                newTool().glob("**/*.{java,kt}", null));
    }

    @Test
    void globMarksDirectoriesWithPrefix() {
        assertEquals("[dir]  " + rel("src/main/java"), newTool().glob("**/java", null));
    }

    @Test
    void globRespectsPathStart() {
        assertEquals(rel("src/main/java/App.java") + "\n" + rel("src/main/java/Helper.java"),
                newTool().glob("**", "src/main/java"));
    }

    @Test
    void globSkipsGitDirectory() {
        String out = newTool().glob("**", null);
        assertFalse(out.contains(".git"));
        assertTrue(out.contains("big.bin"));
        assertTrue(out.contains("fake.bin"));
        assertTrue(out.contains("notes.txt"));
    }

    @Test
    void globCapsAt200Entries() throws IOException {
        Path many = taskRoot.resolve("manyfiles");
        Files.createDirectories(many);
        for (int i = 0; i < 250; i++) {
            Files.writeString(many.resolve(String.format("f%03d.txt", i)), "x", StandardCharsets.UTF_8);
        }
        List<String> lines = List.of(newTool().glob("manyfiles/*.txt", null).split("\n"));
        assertEquals(201, lines.size());
        assertEquals(rel("manyfiles/f000.txt"), lines.get(0));
        assertEquals(rel("manyfiles/f199.txt"), lines.get(199));
        assertEquals("已达上限 200 条", lines.get(200));
    }

    @Test
    void globReturnsEmptyResultText() {
        assertEquals("无匹配结果", newTool().glob("*.nomatch", null));
    }

    @Test
    void globRejectsInvalidPattern() {
        assertTrue(newTool().glob("[", null).contains("glob 模式无效"));
    }

    @Test
    void globRejectsPathEscape() {
        assertTrue(newTool().glob("**", "../evil").startsWith("错误：路径越出任务工作区"));
    }

    @Test
    void globFailsWithoutTaskContext() {
        TaskIdContext.clear();
        assertEquals("错误：无任务上下文，工作区不可用", newTool().glob("**", null));
    }
}
