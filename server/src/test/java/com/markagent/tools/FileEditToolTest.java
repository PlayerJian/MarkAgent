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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileEditTool 单测：匹配规则（唯一 / 0 次 / 不唯一 / replace_all / 超限）、空 new_string 删除、
 * 行尾归一化与还原（CRLF / LF / CR / 单行文件）、沙箱越界、文件不存在、任务上下文缺失、
 * 以及行尾纯函数的直接验证。
 */
class FileEditToolTest {

    @TempDir
    Path tempDir;

    private FileEditTool tool;

    @BeforeEach
    void setUp() {
        TaskIdContext.set("t_test");
        tool = new FileEditTool(tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskIdContext.clear();
    }

    // ==================== 测试基建 ====================

    /** 任务沙箱目录 data/workspace/t_test */
    private Path sandbox() {
        return tempDir.resolve("workspace").resolve("t_test");
    }

    /** 写入沙箱文件（自动建父目录） */
    private void writeFile(String relativePath, String content) throws IOException {
        Path file = sandbox().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String readFile(String relativePath) throws IOException {
        return Files.readString(sandbox().resolve(relativePath), StandardCharsets.UTF_8);
    }

    // ==================== 基础匹配行为 ====================

    @Test
    void editReplacesUniqueMatch() throws IOException {
        writeFile("notes.md", "hello world");
        String result = tool.edit("notes.md", "world", "Java", null);
        assertEquals("hello Java", readFile("notes.md"));
        assertTrue(result.contains("已完成编辑 notes.md"));
        assertTrue(result.contains("替换 1 处"));
        assertTrue(result.contains("-1 字符")); // world(5) → Java(4)，长度差 -1
    }

    @Test
    void editReportsZeroMatchWithPreview() throws IOException {
        writeFile("notes.md", "hello world");
        String result = tool.edit("notes.md", "xyz", "abc", null);
        assertTrue(result.contains("未找到要替换的原文片段"));
        assertTrue(result.contains("hello world")); // 回显文件前 500 字符供校正
        assertEquals("hello world", readFile("notes.md")); // 文件未改动
    }

    @Test
    void editRejectsAmbiguousMatch() throws IOException {
        writeFile("notes.md", "foo foo foo");
        String result = tool.edit("notes.md", "foo", "bar", null);
        assertTrue(result.contains("匹配不唯一"));
        assertTrue(result.contains("3 处"));
        assertEquals("foo foo foo", readFile("notes.md")); // 文件未改动
    }

    @Test
    void editReplaceAllReplacesEveryMatch() throws IOException {
        writeFile("notes.md", "foo bar foo baz foo");
        String result = tool.edit("notes.md", "foo", "FOO", true);
        assertEquals("FOO bar FOO baz FOO", readFile("notes.md"));
        assertTrue(result.contains("替换 3 处"));
        assertTrue(result.contains("+0 字符")); // foo(3) → FOO(3) 等长，总差 0
    }

    @Test
    void editReplaceAllRejectsOver100Matches() throws IOException {
        String original = "line\n".repeat(150);
        writeFile("notes.md", original);
        String result = tool.edit("notes.md", "line", "L", true);
        assertTrue(result.contains("超过 replace_all 上限 100 处"));
        assertEquals(original, readFile("notes.md")); // 文件未改动
    }

    @Test
    void editWithEmptyNewStringDeletesMatch() throws IOException {
        writeFile("notes.md", "hello world foo");
        String result = tool.edit("notes.md", " world", "", null);
        assertEquals("hello foo", readFile("notes.md"));
        assertTrue(result.contains("替换 1 处"));
        assertTrue(result.contains("-6 字符")); // " world"(6) → ""(0)，长度差 -6
    }

    @Test
    void editRejectsEmptyOldString() throws IOException {
        writeFile("notes.md", "hello");
        String result = tool.edit("notes.md", "", "x", null);
        assertTrue(result.contains("old_string 不能为空串"));
        assertEquals("hello", readFile("notes.md"));
    }

    // ==================== 行尾归一化与还原 ====================

    @Test
    void editPreservesCrlfFile() throws IOException {
        writeFile("crlf.txt", "line1\r\nline2\r\nline3");
        // new_string 自身带 \r\n 也先归一化再按文件风格还原
        String result = tool.edit("crlf.txt", "line2", "LINE2\r\nNEXT", null);
        assertEquals("line1\r\nLINE2\r\nNEXT\r\nline3", readFile("crlf.txt"));
        assertTrue(result.contains("替换 1 处"));
    }

    @Test
    void editPreservesLfFile() throws IOException {
        writeFile("lf.txt", "line1\nline2");
        tool.edit("lf.txt", "line1", "L1", null);
        String updated = readFile("lf.txt");
        assertEquals("L1\nline2", updated);
        assertFalse(updated.contains("\r")); // 未混入 \r
    }

    @Test
    void editPreservesCrFile() throws IOException {
        writeFile("cr.txt", "a\rb\rc");
        tool.edit("cr.txt", "b", "B", null);
        assertEquals("a\rB\rc", readFile("cr.txt"));
    }

    @Test
    void editSingleLineFileUsesSystemEolForInsertedNewline() throws IOException {
        writeFile("one.txt", "abc");
        tool.edit("one.txt", "b", "B\nC", null);
        // 原文件无换行：插入的 \n 按系统默认行尾还原
        assertEquals("aB" + System.lineSeparator() + "Cc", readFile("one.txt"));
    }

    // ==================== 沙箱与上下文校验 ====================

    @Test
    void editRejectsPathEscape() throws IOException {
        writeFile("notes.md", "hello");
        String result = tool.edit("../outside.txt", "h", "x", null);
        assertTrue(result.contains("越出任务工作区"));
        assertEquals("hello", readFile("notes.md")); // 沙箱内文件未受影响
    }

    @Test
    void editReportsMissingFile() {
        String result = tool.edit("ghost.md", "a", "b", null);
        assertTrue(result.contains("文件不存在 ghost.md"));
    }

    @Test
    void editRejectsOversizeFile() throws IOException {
        writeFile("big.txt", "a".repeat((int) FileEditTool.MAX_FILE_SIZE + 1));
        String result = tool.edit("big.txt", "a", "b", null);
        assertTrue(result.contains("过大"));
    }

    @Test
    void editRequiresTaskContext() {
        TaskIdContext.clear();
        String result = tool.edit("notes.md", "a", "b", null);
        assertTrue(result.contains("无任务上下文"));
    }

    // ==================== 行尾纯函数 ====================

    @Test
    void normalizeLineEndingsUnifiesAllStyles() {
        assertEquals("a\nb\nc\nd", FileEditTool.normalizeLineEndings("a\r\nb\rc\nd"));
        assertEquals("a\n\nb", FileEditTool.normalizeLineEndings("a\r\r\nb"));
    }

    @Test
    void detectLineEndingByFirstNewline() {
        assertEquals("\r\n", FileEditTool.detectLineEnding("a\r\nb\rc\nd")); // 首个换行是 \r\n
        assertEquals("\r", FileEditTool.detectLineEnding("a\rb\nc"));
        assertEquals("\n", FileEditTool.detectLineEnding("a\nb\r\nc"));
        assertNull(FileEditTool.detectLineEnding("no newline"));
        assertNull(FileEditTool.detectLineEnding(""));
    }

    @Test
    void restoreLineEndingsConvertsBack() {
        assertEquals("a\r\nb\r\nc", FileEditTool.restoreLineEndings("a\nb\nc", "\r\n"));
        assertEquals("a\rb\rc", FileEditTool.restoreLineEndings("a\nb\nc", "\r"));
        assertEquals("a\nb\nc", FileEditTool.restoreLineEndings("a\nb\nc", "\n"));
        assertEquals("a\nb\nc", FileEditTool.restoreLineEndings("a\nb\nc", null));
    }

    @Test
    void countOccurrencesCountsNonOverlapping() {
        assertEquals(2, FileEditTool.countOccurrences("aaaa", "aa")); // 非重叠：位置 0、2
        assertEquals(2, FileEditTool.countOccurrences("aaaaa", "aa")); // 位置 0、2，末尾剩 1 字符不匹配
        assertEquals(0, FileEditTool.countOccurrences("abc", "x"));
        assertEquals(0, FileEditTool.countOccurrences("abc", ""));
    }

    @Test
    void replaceFirstReplacesOnlyFirstMatch() {
        assertEquals("bar foo", FileEditTool.replaceFirst("foo foo", "foo", "bar"));
        assertEquals("foo", FileEditTool.replaceFirst("foo", "x", "y")); // 未匹配原样返回
    }
}
