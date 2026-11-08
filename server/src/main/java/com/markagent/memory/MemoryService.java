package com.markagent.memory;

import com.markagent.common.dto.MemorySearchResult;
import com.markagent.embedding.EmbeddingProvider;
import com.markagent.embedding.KeywordEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆服务。文件布局（data/memory/{userId}/）：
 * <ul>
 *   <li>MEMORY.md —— 结构化长期记忆，每条以「## 」分段，带时间戳与来源</li>
 *   <li>YYYY-MM-DD.md —— 日记：当日交互摘要（任务完成时落盘）</li>
 * </ul>
 * 检索三档：向量档（EMBEDDING_API_KEY 存在）→ 余弦 top-k；关键词档 → 词袋余弦 top-k。
 */
@Component
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final Pattern SECTION = Pattern.compile("(?m)^## (.+)$");

    private final Path memoryRoot;
    private final EmbeddingProvider vectorProvider;
    private final KeywordEmbeddingProvider keywordProvider;

    public MemoryService(@Value("${markagent.storage.root:./data}") String storageRoot,
            EmbeddingProvider vectorProvider, KeywordEmbeddingProvider keywordProvider) {
        this.memoryRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("memory");
        this.vectorProvider = vectorProvider;
        this.keywordProvider = keywordProvider;
    }

    /** 保存长期记忆条目（追加到 MEMORY.md） */
    public synchronized String save(String userId, String content, String source) throws IOException {
        Path dir = userDir(userId);
        Files.createDirectories(dir);
        Path file = dir.resolve("MEMORY.md");
        String stamp = java.time.Instant.now().toString();
        StringBuilder sb = new StringBuilder();
        if (Files.exists(file)) {
            sb.append(Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
        }
        sb.append("## ").append(stamp).append(" [").append(source == null ? "manual" : source).append("]\n");
        sb.append(content.strip()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return "已保存到长期记忆（MEMORY.md）";
    }

    /** 日记：当日摘要追加（任务完成/会话收尾时调用） */
    public synchronized void recordDiary(String userId, String summary) throws IOException {
        Path dir = userDir(userId);
        Files.createDirectories(dir);
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path file = dir.resolve(date + ".md");
        String stamp = java.time.Instant.now().toString();
        StringBuilder sb = new StringBuilder();
        if (Files.exists(file)) {
            sb.append(Files.readString(file, StandardCharsets.UTF_8).stripTrailing()).append('\n');
        }
        sb.append("## ").append(stamp).append('\n').append(summary.strip()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** MEMORY.md 全文（无则空串） */
    public String getMemory(String userId) {
        Path file = userDir(userId).resolve("MEMORY.md");
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败 {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /** 检索：MEMORY.md 条目 + 日记条目，向量/关键词两档，返回 top-k */
    public List<MemorySearchResult> search(String userId, String query, int limit) throws Exception {
        List<Entry> entries = loadEntries(userId);
        if (entries.isEmpty()) {
            return List.of();
        }
        int topK = Math.min(Math.max(limit, 1), 10);
        if (vectorProvider.available()) {
            try {
                double[] q = vectorProvider.embed(query);
                List<Entry> ranked = new ArrayList<>(entries);
                ranked.sort(Comparator.comparingDouble(e -> -cosine(q, e.embed(vectorProvider))));
                return top(ranked, topK);
            } catch (Exception e) {
                // 装配了 bean 但无有效 key（调用 401）等 → 降级关键词档
                log.warn("向量检索不可用，降级关键词档：{}", e.getMessage());
            }
        }
        // 关键词降级档
        List<String> queryTokens = keywordProvider.tokenize(query);
        List<String> vocab = new ArrayList<>();
        for (Entry e : entries) {
            for (String t : keywordProvider.tokenize(e.content)) {
                if (!vocab.contains(t)) {
                    vocab.add(t);
                }
            }
        }
        double[] q = keywordProvider.bagOfWords(queryTokens, vocab);
        List<Entry> ranked = new ArrayList<>(entries);
        for (Entry e : ranked) {
            e.score = KeywordEmbeddingProvider.cosine(q, keywordProvider.bagOfWords(keywordProvider.tokenize(e.content), vocab));
        }
        ranked.sort(Comparator.comparingDouble(e -> -e.score));
        return top(ranked, topK);
    }

    private static List<MemorySearchResult> top(List<Entry> ranked, int topK) {
        return ranked.subList(0, Math.min(topK, ranked.size())).stream()
                .map(e -> new MemorySearchResult(e.content, e.score, e.source))
                .toList();
    }

    // ==================== 内部 ====================

    /** 记忆条目：content + 来源（文件标识）+ 最近嵌入缓存 */
    private static final class Entry {
        final String content;
        final String source;
        double score;
        double[] vector;

        Entry(String content, String source) {
            this.content = content;
            this.source = source;
        }

        double[] embed(EmbeddingProvider provider) {
            if (vector == null) {
                try {
                    vector = provider.embed(content);
                } catch (Exception e) {
                    vector = new double[0];
                }
            }
            return vector;
        }
    }

    private List<Entry> loadEntries(String userId) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = file.getFileName().toString();
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = SECTION.matcher(raw);
                int lastStart = 0;
                String lastHeader = null;
                while (m.find()) {
                    if (lastHeader != null) {
                        addEntry(entries, raw.substring(lastStart, m.start()), source, lastHeader);
                    }
                    lastHeader = m.group(1);
                    lastStart = m.start();
                }
                String tail = raw.substring(lastStart);
                if (!tail.isBlank()) {
                    addEntry(entries, tail, source, lastHeader);
                }
            }
        }
        return entries;
    }

    private static void addEntry(List<Entry> entries, String section, String source, String header) {
        // 去掉「## 时间戳 [来源]」行本身，保留正文
        String body = section.replaceFirst("(?m)^## .*$", "").strip();
        if (!body.isBlank()) {
            entries.add(new Entry(body, source));
        }
    }

    private Path userDir(String userId) {
        return memoryRoot.resolve(userId);
    }

    private static double cosine(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
