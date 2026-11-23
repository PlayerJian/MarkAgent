package com.markagent.memory;

import com.markagent.common.dto.MemorySearchResult;
import com.markagent.config.MarkAgentProperties;
import com.markagent.embedding.EmbeddingProvider;
import com.markagent.embedding.KeywordEmbeddingProvider;
import com.markagent.store.AtomicJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆服务。文件布局（data/memory/{userId}/）：
 * <ul>
 *   <li>MEMORY.md —— 纯索引：每行一条「- [标题](entries/{slug}.md) — 一句话描述」</li>
 *   <li>entries/{slug}.md —— 每条记忆独立一个文件（slug = 内容 SHA-256 前 8 位十六进制，
 *       同一内容重复保存天然去重：只更新同一文件）</li>
 *   <li>YYYY-MM-DD.md —— 日记：当日交互摘要（任务完成时落盘）</li>
 * </ul>
 * 旧格式（MEMORY.md 为「## 」段落堆叠的大文件）在 save/search/getMemory 任一入口首次触发自动迁移，
 * 幂等（entries/ 已存在即跳过）；迁移先写全部 entries 文件成功，再原子替换 MEMORY.md。
 * 检索三档：向量档（EMBEDDING_API_KEY 存在）→ 余弦 top-k；关键词档 → 词袋余弦 top-k。
 */
@Component
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final Pattern SECTION = Pattern.compile("(?m)^## (.+)$");
    /** 旧段落头「时间戳 [来源]」中的来源括号 */
    private static final Pattern SOURCE_BRACKET = Pattern.compile("\\[([^\\]]*)\\]");

    private final Path memoryRoot;
    private final EmbeddingProvider vectorProvider;
    private final KeywordEmbeddingProvider keywordProvider;
    private final MarkAgentProperties properties;

    public MemoryService(MarkAgentProperties properties,
            EmbeddingProvider vectorProvider, KeywordEmbeddingProvider keywordProvider) {
        String root = properties.storage() == null ? "./data" : properties.storage().root();
        this.memoryRoot = Path.of(root).toAbsolutePath().normalize().resolve("memory");
        this.vectorProvider = vectorProvider;
        this.keywordProvider = keywordProvider;
        this.properties = properties;
    }

    /** 保存长期记忆：写 entries/{slug}.md + 更新 MEMORY.md 索引（同内容重复保存只原位替换索引行） */
    public synchronized String save(String userId, String content, String source) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        Path dir = userDir(userId);
        Files.createDirectories(dir);
        ensureMigrated(dir);

        String stripped = content.strip();
        int nl = stripped.indexOf('\n');
        String firstLine = (nl < 0 ? stripped : stripped.substring(0, nl)).strip();
        String title = titleOf(stripped);
        // 首行已是标题时正文从第二行开始；无首行（空白开头）则正文照抄
        String body = firstLine.isEmpty() ? stripped : (nl < 0 ? "" : stripped.substring(nl + 1).strip());

        String slug = slugOf(stripped);
        Path entriesDir = dir.resolve("entries");
        Files.createDirectories(entriesDir);
        String sourceName = source == null ? "manual" : source;
        String fileContent = "## " + title + "\n"
                + "- 时间: " + Instant.now().toString() + "\n"
                + "- 来源: " + sourceName + "\n\n"
                + body + '\n';
        new AtomicJsonFile(entriesDir.resolve(slug + ".md")).writeString(fileContent);

        String desc = oneLine(body.isEmpty() ? stripped : body);
        if (desc.length() > 60) {
            desc = desc.substring(0, 60);
        }
        updateIndex(dir, slug, title, desc);
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

    /** MEMORY.md 全文（索引格式，无则空串）。API 兼容：返回内容仍为 MEMORY.md */
    public String getMemory(String userId) {
        Path dir = userDir(userId);
        try {
            ensureMigrated(dir);
            Path file = dir.resolve("MEMORY.md");
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败 {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /** 索引全文（注入 prompt 用）：行数/字节双重硬截断保护，超限追加一行截断提示 */
    public String indexForPrompt(String userId) {
        MarkAgentProperties.Memory memory = memoryConfig();
        if (!memory.injectIndex()) {
            return "";
        }
        Path dir = userDir(userId);
        try {
            ensureMigrated(dir);
            Path indexFile = dir.resolve("MEMORY.md");
            if (!Files.isRegularFile(indexFile)) {
                return "";
            }
            String raw = Files.readString(indexFile, StandardCharsets.UTF_8).stripTrailing();
            if (raw.isBlank()) {
                return "";
            }
            int maxLines = memory.indexMaxLines();
            int maxBytes = memory.indexMaxBytes();
            List<String> kept = new ArrayList<>();
            int bytes = 0;
            boolean truncated = false;
            for (String line : raw.split("\n", -1)) {
                String clean = line.stripTrailing();
                int lineBytes = clean.getBytes(StandardCharsets.UTF_8).length + 1; // 含换行符
                if (kept.size() >= maxLines || bytes + lineBytes > maxBytes) {
                    truncated = true;
                    break;
                }
                kept.add(clean);
                bytes += lineBytes;
            }
            if (truncated) {
                StringBuilder sb = new StringBuilder();
                for (String line : kept) {
                    sb.append(line).append('\n');
                }
                sb.append("…（索引已截断，可用 memory_search 检索全部记忆）");
                return sb.toString();
            }
            return String.join("\n", kept);
        } catch (IOException e) {
            log.warn("读取记忆索引失败 {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /** 检索：entries/ 记忆条目 + 日记条目，向量/关键词两档，返回 top-k */
    public List<MemorySearchResult> search(String userId, String query, int limit) throws Exception {
        Path dir = userDir(userId);
        ensureMigrated(dir);
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

    /** 旧格式段落（迁移中间态） */
    private record OldSection(String stamp, String source, String title, String body) {
    }

    private List<Entry> loadEntries(String userId) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        // 每条记忆一个文件（entries/），整个文件即一条检索语料
        Path entriesDir = dir.resolve("entries");
        if (Files.isDirectory(entriesDir)) {
            try (var files = Files.list(entriesDir)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String raw = Files.readString(file, StandardCharsets.UTF_8).strip();
                    if (!raw.isBlank()) {
                        entries.add(new Entry(raw, "entries/" + file.getFileName()));
                    }
                }
            }
        }
        // 日记文件照旧按「## 」段落解析（MEMORY.md 为索引，不入检索语料）
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String source = file.getFileName().toString();
                if ("MEMORY.md".equals(source)) {
                    continue;
                }
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

    /** 旧格式迁移（与 save 同锁互斥；entries/ 已存在即幂等跳过） */
    private synchronized void ensureMigrated(Path dir) throws IOException {
        Path indexFile = dir.resolve("MEMORY.md");
        if (Files.isDirectory(dir.resolve("entries")) || !Files.isRegularFile(indexFile)) {
            return;
        }
        if (!SECTION.matcher(Files.readString(indexFile, StandardCharsets.UTF_8)).find()) {
            return;
        }
        migrate(dir);
    }

    /** 迁移：拆「## 」段落为独立 entries 文件；全部写成功后才原子替换 MEMORY.md，失败回滚不丢原文件 */
    private void migrate(Path dir) throws IOException {
        Path indexFile = dir.resolve("MEMORY.md");
        String raw = Files.readString(indexFile, StandardCharsets.UTF_8);
        List<OldSection> sections = splitOldSections(raw);
        if (sections.isEmpty()) {
            new AtomicJsonFile(indexFile).writeString(""); // 空段落文件 → 转为空索引，幂等
            return;
        }
        Path entriesDir = dir.resolve("entries");
        Files.createDirectories(entriesDir);
        List<Path> written = new ArrayList<>();
        List<String> linkLines = new ArrayList<>();
        try {
            for (OldSection s : sections) {
                String slug = slugOf(s.body());
                Path file = entriesDir.resolve(slug + ".md");
                int n = 2;
                while (Files.exists(file)) { // 旧文件同正文重复段落：加后缀避免覆盖丢数据
                    file = entriesDir.resolve(slug + "-" + n + ".md");
                    n++;
                }
                String fileContent = "## " + s.title() + "\n"
                        + "- 时间: " + s.stamp() + "\n"
                        + "- 来源: " + s.source() + "\n\n"
                        + s.body() + '\n';
                new AtomicJsonFile(file).writeString(fileContent);
                written.add(file);
                String desc = oneLine(s.body());
                if (desc.length() > 60) {
                    desc = desc.substring(0, 60);
                }
                linkLines.add("- [" + sanitizeLinkText(s.title()) + "](entries/" + file.getFileName() + ") — " + desc);
            }
            StringBuilder idx = new StringBuilder();
            for (String line : linkLines) {
                idx.append(line).append('\n');
            }
            new AtomicJsonFile(indexFile).writeString(idx.toString());
        } catch (IOException e) {
            // 任一 entries 写失败：回滚已写文件，MEMORY.md 保持原样，下次入口重试
            for (Path p : written) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 回滚尽力而为
                }
            }
            try {
                Files.deleteIfExists(entriesDir);
            } catch (IOException ignored) {
                // 回滚尽力而为
            }
            throw e;
        }
    }

    /** 拆分旧格式「## 时间戳 [来源] + 正文」段落 */
    private static List<OldSection> splitOldSections(String raw) {
        List<OldSection> sections = new ArrayList<>();
        Matcher m = SECTION.matcher(raw);
        int lastStart = 0;
        String lastHeader = null;
        while (m.find()) {
            if (lastHeader != null) {
                addOldSection(sections, raw.substring(lastStart, m.start()), lastHeader);
            }
            lastHeader = m.group(1);
            lastStart = m.start();
        }
        if (lastHeader != null) {
            addOldSection(sections, raw.substring(lastStart), lastHeader);
        }
        return sections;
    }

    private static void addOldSection(List<OldSection> sections, String section, String header) {
        String body = section.replaceFirst("(?m)^## .*$", "").strip();
        if (body.isBlank()) {
            return;
        }
        String stamp = header.strip();
        String source = "manual";
        Matcher m = SOURCE_BRACKET.matcher(header);
        if (m.find()) {
            stamp = header.substring(0, m.start()).trim();
            String src = m.group(1).trim();
            if (!src.isBlank()) {
                source = src;
            }
        }
        sections.add(new OldSection(stamp, source, titleOf(body), body));
    }

    /** 更新 MEMORY.md 索引：同 slug 的行原位替换（保持位置），不存在则追加一行 */
    private void updateIndex(Path dir, String slug, String title, String desc) throws IOException {
        Path indexFile = dir.resolve("MEMORY.md");
        String linkMarker = "](entries/" + slug + ".md)";
        String newLine = "- [" + sanitizeLinkText(title) + "](entries/" + slug + ".md) — " + desc;
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        if (Files.isRegularFile(indexFile)) {
            for (String line : Files.readString(indexFile, StandardCharsets.UTF_8).split("\n", -1)) {
                String clean = line.stripTrailing();
                if (!replaced && clean.contains(linkMarker)) {
                    sb.append(newLine).append('\n');
                    replaced = true;
                } else if (!clean.isBlank()) {
                    sb.append(clean).append('\n');
                }
            }
        }
        if (!replaced) {
            sb.append(newLine).append('\n');
        }
        new AtomicJsonFile(indexFile).writeString(sb.toString());
    }

    /** 从正文提取标题：首行（去 # 前缀）截断 60 字符；无首行则取正文一行化前 40 字符 */
    private static String titleOf(String body) {
        int nl = body.indexOf('\n');
        String firstLine = (nl < 0 ? body : body.substring(0, nl)).strip();
        String title = firstLine.isEmpty() ? null : firstLine.replaceFirst("^#+", "").strip();
        if (title == null || title.isBlank()) {
            String one = oneLine(body);
            title = one.length() > 40 ? one.substring(0, 40) : one;
        }
        if (title.isBlank()) {
            title = "无标题记忆";
        }
        return title.length() > 60 ? title.substring(0, 60) : title;
    }

    /** slug = 内容 SHA-256 前 8 位十六进制（同一内容重复保存只更新同一文件） */
    private static String slugOf(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 换行/连续空白 → 单个空格，压成一行 */
    private static String oneLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    /** 索引链接文本：去掉会破坏 markdown 链接的字符 */
    private static String sanitizeLinkText(String title) {
        return title.replaceAll("[\\[\\]\\(\\)]", " ").replaceAll("\\s+", " ").strip();
    }

    private MarkAgentProperties.Memory memoryConfig() {
        MarkAgentProperties.Memory memory = properties.memory();
        return memory == null ? new MarkAgentProperties.Memory(200, 25000, true) : memory;
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
