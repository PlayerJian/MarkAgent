package com.markagent.schema;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema 文档目录（Text2SQL 语义层）：启动时加载 data/schema-docs/——
 * 18 份模块文档进内存、150 份表文档建内存检索索引（表名/列名/中文注释三字段子串打分）。
 * <p>
 * 检索策略：查询按空格分词；短词（≤4 字符）直接 contains，长词按 2 字滑窗短语匹配（命中率=命中片/总片）；
 * 表名命中权重 3、列名 1.5、中文名/关系文本 1。150 表全量 600KB，遍历打分毫秒级，无需倒排。
 * <p>
 * 分层：search_modules 只给模块摘要（路由）→ load_module 给模块全文（表摘要卡）→
 * table_schema 按需补单表全文 → db_query 执行。文档为生成器快照（com.markagent.schemagen.SchemaDocGenerator），
 * 重跑生成后调用 reload() 即可生效。
 */
@Component
public class SchemaCatalog {

    private static final Logger log = LoggerFactory.getLogger(SchemaCatalog.class);

    /** 模块文档（内存全量驻留） */
    public record ModuleDoc(String code, String name, String summary, String keywords,
                            List<String> tables, String body) {
    }

    /** 表条目：检索索引字段 + 展示用摘要。enumText 为枚举取值文本（业务语义最强信号，独立加权） */
    public record TableEntry(String table, String chineseName, String moduleCode, String moduleName,
                             String nameText, String columnText, String commentText, String enumText,
                             String snippet) {
    }

    /** 检索命中 */
    public record TableHit(String table, String chineseName, String moduleCode, String moduleName,
                           double score) {
    }

    public record ModuleHit(String code, String name, String summary, double score,
                            List<String> matchedTables) {
    }

    private final Path root;
    private final Map<String, ModuleDoc> modules = new LinkedHashMap<>();
    private final List<TableEntry> tables = new ArrayList<>();
    private String glossary = "";

    public SchemaCatalog(Path storageRoot) {
        this.root = storageRoot.resolve("schema-docs");
    }

    @PostConstruct
    void init() {
        reload();
    }

    /** 全量重载模块文档 / 表索引 / glossary（生成器重跑后调用） */
    public synchronized void reload() {
        Map<String, ModuleDoc> nextModules = new LinkedHashMap<>();
        List<TableEntry> nextTables = new ArrayList<>();
        String nextGlossary = "";
        try {
            Path modulesDir = root.resolve("modules");
            if (Files.isDirectory(modulesDir)) {
                try (var files = Files.list(modulesDir)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                        ModuleDoc doc = parseModule(f);
                        if (doc != null) {
                            nextModules.put(doc.code(), doc);
                        }
                    }
                }
            }
            Path tablesDir = root.resolve("tables");
            if (Files.isDirectory(tablesDir)) {
                try (var files = Files.list(tablesDir)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                        TableEntry entry = parseTable(f);
                        if (entry != null) {
                            nextTables.add(entry);
                        }
                    }
                }
            }
            Path glossaryFile = root.resolve("glossary.md");
            if (Files.isRegularFile(glossaryFile)) {
                nextGlossary = Files.readString(glossaryFile, StandardCharsets.UTF_8);
            }
            modules.clear();
            modules.putAll(nextModules);
            tables.clear();
            tables.addAll(nextTables);
            glossary = nextGlossary;
            log.info("SchemaCatalog loaded: modules={} tables={} glossary={}bytes",
                    modules.size(), tables.size(), glossary.length());
        } catch (IOException e) {
            log.warn("SchemaCatalog reload failed: {}", e.getMessage());
        }
    }

    public Map<String, ModuleDoc> modules() {
        return modules;
    }

    public int tableCount() {
        return tables.size();
    }

    public String glossary() {
        return glossary;
    }

    /** 按问题关键词检索模块：表级打分后按模块聚合（模块命中证据 = Σ 命中表得分 + 模块摘要/关键词直接命中加成） */
    public List<ModuleHit> searchModules(String question, int topK) {
        List<String> words = words(question);
        if (words.isEmpty() || modules.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scoreByModule = new LinkedHashMap<>();
        Map<String, List<String>> matchedByModule = new LinkedHashMap<>();
        for (TableEntry entry : tables) {
            double s = score(entry, words);
            if (s > 0) {
                scoreByModule.merge(entry.moduleCode(), s, Double::sum);
                matchedByModule.computeIfAbsent(entry.moduleCode(), k -> new ArrayList<>()).add(entry.table());
            }
        }
        // 模块摘要与关键词直接命中（覆盖业务词不在表注释里的场景）
        for (ModuleDoc doc : modules.values()) {
            double s = 0;
            for (String w : words) {
                if (contains(doc.summary(), w)) {
                    s += 2.0;
                }
                if (contains(doc.keywords(), w)) {
                    s += 1.5;
                }
            }
            if (s > 0) {
                scoreByModule.merge(doc.code(), s, Double::sum);
            }
        }
        return scoreByModule.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    ModuleDoc doc = modules.get(e.getKey());
                    return new ModuleHit(e.getKey(), doc == null ? e.getKey() : doc.name(),
                            doc == null ? "" : doc.summary(), e.getValue(),
                            matchedByModule.getOrDefault(e.getKey(), List.of()));
                })
                .toList();
    }

    /** 按关键词检索表（表名/列名/中文名与关系文本三字段加权） */
    public List<TableHit> searchTables(String keyword, int topK) {
        List<String> words = words(keyword);
        if (words.isEmpty()) {
            return List.of();
        }
        return tables.stream()
                .map(t -> new TableHit(t.table(), t.chineseName(), t.moduleCode(), t.moduleName(),
                        score(t, words)))
                .filter(h -> h.score() > 0)
                .sorted(Comparator.comparingDouble(TableHit::score).reversed())
                .limit(topK)
                .toList();
    }

    /** 模块全文（含表摘要卡与跨模块关联）；特殊名 glossary 返回全局口径 */
    public String moduleBody(String code) {
        if (code.equalsIgnoreCase("glossary")) {
            return glossary;
        }
        ModuleDoc doc = modules.get(code);
        return doc == null ? null : doc.body();
    }

    /** 单表文档全文（按名精确匹配，可一次多张，以 \n\n---\n\n 分隔） */
    public String tableBodies(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            Path f = root.resolve("tables").resolve(name + ".md");
            if (!Files.isRegularFile(f)) {
                sb.append("（表 ").append(name).append(" 无文档，可用 db_query 的 DESCRIBE 兜底）\n\n");
                continue;
            }
            try {
                if (!sb.isEmpty()) {
                    sb.append("\n---\n\n");
                }
                sb.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                sb.append("（读取 ").append(name).append(" 文档失败：").append(e.getMessage()).append("）\n\n");
            }
        }
        return sb.toString();
    }

    /** 诊断用：按表名取索引条目（测试/调试；生产不暴露） */
    TableEntry debugEntry(String table) {
        for (TableEntry t : tables) {
            if (t.table().equals(table)) {
                return t;
            }
        }
        return null;
    }

    /** 表展示摘要（search_tables/search_modules 返回用）：中文名 + 模块 + 枚举列 */
    public String snippet(String table) {
        for (TableEntry t : tables) {
            if (t.table().equals(table)) {
                return t.snippet();
            }
        }
        return table;
    }

    /** 查询打分：词（2 字片）× 字段权重累加。表名 3.0 / 枚举值 2.0 / 列名 1.5 / 中文名与关系文本 1.0 */
    private static double score(TableEntry entry, List<String> words) {
        double total = 0;
        for (String w : words) {
            if (contains(entry.nameText(), w)) {
                total += 3.0;
            }
            if (contains(entry.enumText(), w)) {
                total += 2.0 * matchRatio(entry.enumText(), w);
            }
            if (contains(entry.columnText(), w)) {
                total += 1.5;
            }
            if (contains(entry.commentText(), w)) {
                total += 1.0 * matchRatio(entry.commentText(), w);
            }
        }
        return total;
    }

    /** 子串匹配（大小写不敏感）；整词不中时按 2 字滑窗片兜底（中文 3-4 字词如「消费记录」拆片召回），返回命中片占比 */
    private static double matchRatio(String text, String word) {
        String t = text.toLowerCase(Locale.ROOT);
        String w = word.toLowerCase(Locale.ROOT);
        if (t.contains(w)) {
            return 1.0;
        }
        if (w.length() <= 2) {
            return 0;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 1 < w.length(); i++) {
            total++;
            if (t.contains(w.substring(i, i + 2))) {
                hit++;
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    private static boolean contains(String text, String word) {
        return matchRatio(text, word) > 0;
    }

    /**
     * 查询分词：按空白/逗号/句读切，词长 ≤4 原样保留；>4 拆 2 字滑窗片（中文无词典场景的近似分词，
     * 跨词片如「费记」自然零命中）。长度 1 的残片过滤。
     */
    static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : query.split("[\\s,，。；;、？?]+")) {
            String w = part.trim().toLowerCase(Locale.ROOT);
            if (w.length() < 2) {
                continue;
            }
            if (w.length() <= 4) {
                out.add(w);
            } else {
                for (int i = 0; i + 1 < w.length(); i++) {
                    out.add(w.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 模块文档解析：--- frontmatter --- + 正文 */
    private ModuleDoc parseModule(Path f) {
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String code = f.getFileName().toString().replace(".md", "");
            String name = code;
            String summary = "";
            String keywords = "";
            List<String> tables = List.of();
            int start = text.indexOf("---");
            int end = text.indexOf("---", start + 3);
            if (start == 0 && end > 0) {
                for (String line : text.substring(3, end).split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    switch (k) {
                        case "module" -> name = v;
                        case "summary" -> summary = v;
                        case "keywords" -> keywords = v;
                        case "tables" -> tables = List.of(v.split(",\\s*"));
                        default -> {
                        }
                    }
                }
            }
            return new ModuleDoc(code, name, summary, keywords, tables, text);
        } catch (IOException e) {
            log.warn("parse module {} failed: {}", f, e.getMessage());
            return null;
        }
    }

    /** 表文档解析：标题（表名+中文名）、模块行、列名、出/入边文本、枚举值 → 索引字段与摘要 */
    private TableEntry parseTable(Path f) {
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String table = f.getFileName().toString().replace(".md", "");
            String chineseName = table;
            String moduleCode = "?";
            String moduleName = "?";
            List<String> columns = new ArrayList<>();
            StringBuilder enumText = new StringBuilder();
            StringBuilder relationText = new StringBuilder();
            List<String> lines = text.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("# ") && line.contains("（")) {
                    chineseName = line.substring(line.indexOf('（') + 1, line.lastIndexOf('）'));
                } else if (line.startsWith("- 模块：")) {
                    String mod = line.substring("- 模块：".length()).trim();
                    int space = mod.indexOf(' ');
                    moduleCode = space > 0 ? mod.substring(0, space) : mod;
                    moduleName = space > 0 ? mod.substring(space + 1) : mod;
                } else if (line.startsWith("| ") && !line.startsWith("| 列") && !line.startsWith("|---")) {
                    String[] cells = line.split("\\|");
                    if (cells.length >= 2 && !cells[1].trim().isBlank()) {
                        columns.add(cells[1].trim());
                    }
                } else if (line.equals("## 枚举值")) {
                    // 枚举值独立索引（业务语义最强信号：搜「消费」→ 含 trans_type=消费 的表）；跳过段间空行
                    if (table.equals("wallet_transaction") || table.equals("payment")) {
                        System.out.println("DBG enum-section hit for " + table + " at line " + i);
                    }
                    for (int j = i + 1; j < lines.size(); j++) {
                        String l = lines.get(j).trim();
                        if (l.isBlank()) {
                            continue;
                        }
                        if (!l.startsWith("| ") && !l.startsWith("|-")) {
                            break; // 分隔行 |---| 不以 "| " 开头，需单独放行
                        }
                        String[] cells = l.split("\\|");
                        if (cells.length >= 3 && !cells[1].trim().equals("列")) {
                            enumText.append(cells[1].trim()).append('=').append(cells[2].trim()).append(' ');
                        }
                    }
                } else if (line.startsWith("## 出边关系") || line.startsWith("## 入边关系")) {
                    for (int j = i + 1; j < lines.size(); j++) {
                        String l = lines.get(j).trim();
                        if (l.isBlank()) {
                            continue;
                        }
                        if (!l.startsWith("- ")) {
                            break;
                        }
                        relationText.append(l, 2, l.length()).append(' ');
                    }
                }
            }
            String columnText = String.join(" ", columns);
            String commentText = chineseName + " " + moduleName + " " + relationText;
            String snippet = "[" + table + "] " + chineseName + "（模块 " + moduleCode + " " + moduleName + "）"
                    + (columns.isEmpty() ? "" : "，列含 " + String.join(", ", columns.subList(0, Math.min(columns.size(), 10))));
            return new TableEntry(table, chineseName, moduleCode, moduleName, table, columnText, commentText,
                    enumText.toString(), snippet);
        } catch (IOException e) {
            log.warn("parse table {} failed: {}", f, e.getMessage());
            return null;
        }
    }
}
