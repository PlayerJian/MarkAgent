package com.markagent.memory;

import com.markagent.common.dto.MemorySearchResult;
import com.markagent.config.MarkAgentProperties;
import com.markagent.embedding.EmbeddingProvider;
import com.markagent.embedding.KeywordEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 长期记忆索引化重构：旧格式迁移 / 去重 / 索引更新 / API 兼容（临时目录，不联网） */
class MemoryServiceTest {

    @TempDir
    Path tmp;

    private MemoryService newService(String storageRoot) {
        return newService(storageRoot, null);
    }

    private MemoryService newService(String storageRoot, MarkAgentProperties.Memory memory) {
        MarkAgentProperties props = new MarkAgentProperties(
                new MarkAgentProperties.Storage(storageRoot), null, null, null, null, null, null, null, memory,
                null, null);
        return new MemoryService(props, new EmbeddingProvider() {
            @Override
            public boolean available() {
                return false; // 强制关键词档，测试不联网
            }

            @Override
            public double[] embed(String text) {
                return new double[0];
            }

            @Override
            public String source() {
                return "stub";
            }
        }, new KeywordEmbeddingProvider());
    }

    private static Path userDir(String storageRoot, String userId) {
        return Path.of(storageRoot).toAbsolutePath().normalize().resolve("memory").resolve(userId);
    }

    private static String readAll(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void migrateOldFormatSplitsSectionsWithoutDataLoss() throws Exception {
        String root = tmp.resolve("m1").toString();
        Path dir = userDir(root, "u1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"), String.join("\n",
                "## 2026-01-01T00:00:00Z [manual]",
                "用户喜欢蓝色主题",
                "",
                "## 2026-01-02T00:00:00Z [agent]",
                "用户使用暗色模式",
                ""), StandardCharsets.UTF_8);

        MemoryService service = newService(root);
        List<MemorySearchResult> results = service.search("u1", "蓝色", 3);

        // 迁移产物：entries/ 两条记忆 + MEMORY.md 变为索引
        Path entriesDir = dir.resolve("entries");
        assertTrue(Files.isDirectory(entriesDir));
        try (var files = Files.list(entriesDir)) {
            assertEquals(2, files.count());
        }
        String index = readAll(dir.resolve("MEMORY.md"));
        assertTrue(index.contains("- ["));
        assertTrue(index.contains("](entries/"));
        assertFalse(index.contains("2026-01-01")); // 旧段落头不再出现在索引里

        // 数据不丢：两个正文都在 entries 文件中
        String allEntries;
        try (var files = Files.list(entriesDir)) {
            StringBuilder sb = new StringBuilder();
            for (Path f : files.sorted().toList()) {
                sb.append(readAll(f)).append('\n');
            }
            allEntries = sb.toString();
        }
        assertTrue(allEntries.contains("用户喜欢蓝色主题"));
        assertTrue(allEntries.contains("用户使用暗色模式"));
        assertTrue(results.stream().anyMatch(r -> r.content().contains("蓝色")));
    }

    @Test
    void migrationIsIdempotentAndSaveAppendsAfterIt() throws Exception {
        String root = tmp.resolve("m2").toString();
        Path dir = userDir(root, "u2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"),
                "## 2026-01-01T00:00:00Z [manual]\n旧记忆正文\n", StandardCharsets.UTF_8);

        MemoryService service = newService(root);
        service.search("u2", "旧记忆", 3); // 首次入口 → 迁移
        service.save("u2", "# 新记忆\n新内容", "manual"); // 迁移后 save 只追加索引，不重写

        Path entriesDir = dir.resolve("entries");
        try (var files = Files.list(entriesDir)) {
            assertEquals(2, files.count());
        }
        String index = readAll(dir.resolve("MEMORY.md"));
        assertEquals(2, index.lines().filter(l -> l.contains("](entries/")).count());
        assertTrue(index.contains("新记忆"));
        assertTrue(index.contains("旧记忆正文"));
    }

    @Test
    void saveWritesEntryFileAndIndexLine() throws Exception {
        String root = tmp.resolve("m3").toString();
        MemoryService service = newService(root);
        service.save("u3", "# 用户偏好\n喜欢蓝色主题", "agent");

        Path entriesDir = userDir(root, "u3").resolve("entries");
        try (var files = Files.list(entriesDir)) {
            List<Path> list = files.toList();
            assertEquals(1, list.size());
            String content = readAll(list.get(0));
            assertTrue(content.startsWith("## 用户偏好"));
            assertTrue(content.contains("- 时间: "));
            assertTrue(content.contains("- 来源: agent"));
            assertTrue(content.contains("喜欢蓝色主题"));
        }
        String index = readAll(userDir(root, "u3").resolve("MEMORY.md"));
        assertTrue(index.startsWith("- [用户偏好](entries/"));
        assertTrue(index.contains(".md) — 喜欢蓝色主题"));
    }

    @Test
    void saveDuplicateContentKeepsSingleEntryAndIndexLine() throws Exception {
        String root = tmp.resolve("m4").toString();
        MemoryService service = newService(root);
        String content = "# 记忆甲\n内容甲";
        service.save("u4", content, "agent");
        service.save("u4", content, "agent");
        service.save("u4", content, "manual");

        Path dir = userDir(root, "u4");
        try (var files = Files.list(dir.resolve("entries"))) {
            assertEquals(1, files.count());
        }
        // 同 slug 重复保存 → 更新同一文件（来源跟随最后一次保存）
        Path entry;
        try (var files = Files.list(dir.resolve("entries"))) {
            entry = files.toList().get(0);
        }
        assertTrue(readAll(entry).contains("- 来源: manual"));

        String index = readAll(dir.resolve("MEMORY.md"));
        assertEquals(1, index.lines().filter(l -> l.contains("](entries/")).count());
    }

    @Test
    void saveReplacesIndexLineInPlace() throws Exception {
        String root = tmp.resolve("m5").toString();
        MemoryService service = newService(root);
        service.save("u5", "# 记忆甲\n内容甲", "agent");
        service.save("u5", "# 记忆乙\n内容乙", "agent");
        service.save("u5", "# 记忆甲\n内容甲", "manual"); // 同内容 → 原位替换，不追加新行

        String index = readAll(userDir(root, "u5").resolve("MEMORY.md"));
        List<String> lines = index.lines().filter(l -> l.contains("](entries/")).toList();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("- [记忆甲]"));
        assertTrue(lines.get(1).startsWith("- [记忆乙]"));
    }

    @Test
    void getMemoryReturnsIndexAndEmptyForUnknownUser() throws Exception {
        String root = tmp.resolve("m6").toString();
        MemoryService service = newService(root);
        assertEquals("", service.getMemory("nobody"));
        service.save("u6", "内容", "manual");
        String index = service.getMemory("u6");
        assertTrue(index.contains("](entries/"));
    }

    @Test
    void getMemoryTriggersMigrationOnOldFormat() throws Exception {
        String root = tmp.resolve("m7").toString();
        Path dir = userDir(root, "u7");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"),
                "## 2026-01-01T00:00:00Z [manual]\n旧格式内容\n", StandardCharsets.UTF_8);

        MemoryService service = newService(root);
        String index = service.getMemory("u7");
        assertTrue(index.contains("- [旧格式内容](entries/"));
        assertTrue(Files.isDirectory(dir.resolve("entries")));
    }

    @Test
    void indexForPromptTruncatesByLines() throws Exception {
        String root = tmp.resolve("m8").toString();
        MemoryService service = newService(root, new MarkAgentProperties.Memory(2, 25000, true));
        service.save("u8", "# 一\n内容一", "manual");
        service.save("u8", "# 二\n内容二", "manual");
        service.save("u8", "# 三\n内容三", "manual");

        String prompt = service.indexForPrompt("u8");
        assertTrue(prompt.contains("…（索引已截断，可用 memory_search 检索全部记忆）"));
        assertEquals(3, prompt.lines().count()); // 2 行索引 + 1 行截断提示
    }

    @Test
    void indexForPromptTruncatesByBytes() throws Exception {
        String root = tmp.resolve("m9").toString();
        MemoryService service = newService(root, new MarkAgentProperties.Memory(200, 100, true));
        service.save("u9", "# 内容一\n正文一", "manual");
        service.save("u9", "# 内容二\n正文二", "manual");
        service.save("u9", "# 内容三\n正文三", "manual");

        String prompt = service.indexForPrompt("u9");
        assertTrue(prompt.contains("…（索引已截断，可用 memory_search 检索全部记忆）"));
        assertEquals(3, prompt.lines().count()); // 每行约 49 字节，100 字节只放得下 2 行
    }

    @Test
    void indexForPromptRespectsInjectFlag() throws Exception {
        String root = tmp.resolve("m10").toString();
        MemoryService service = newService(root, new MarkAgentProperties.Memory(200, 25000, false));
        service.save("u10", "内容", "manual");
        assertEquals("", service.indexForPrompt("u10"));
    }

    @Test
    void searchIncludesDiaryEntries() throws Exception {
        String root = tmp.resolve("m11").toString();
        MemoryService service = newService(root);
        service.save("u11", "# 偏好\n喜欢蓝色", "manual");
        service.recordDiary("u11", "今天完成项目部署");

        List<MemorySearchResult> results = service.search("u11", "部署", 5);
        assertTrue(results.stream().anyMatch(r -> r.content().contains("项目部署")));
        String diaryName = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        assertTrue(results.stream().anyMatch(r -> r.source().equals(diaryName)));
    }
}
