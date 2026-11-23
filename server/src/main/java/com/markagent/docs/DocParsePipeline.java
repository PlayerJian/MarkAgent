package com.markagent.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;
import com.markagent.config.MarkAgentProperties;
import com.markagent.store.DocStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档解析流水线：上传受理后由固定并发线程池执行
 * 「原件 → MinerU(file-parse) → ZIP 解包 → md/content_list 冻结 → 页数统计 → READY」。
 * <p>
 * 状态全部持久化在 DocRecord：服务重启后遗留 PARSING 记录标记 FAILED（用户可 retry）。
 * GPU/VLM 容器是单队列，并发压到配置值（缺省 2）。
 */
@Component
public class DocParsePipeline implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocParsePipeline.class);

    /** 解包产出总量上限（防 zip 炸弹） */
    private static final long MAX_EXTRACT_BYTES = 1L << 30;
    /** 解包条目数上限 */
    private static final int MAX_ENTRIES = 20_000;
    /** 失败原因记录截断长度 */
    private static final int MAX_ERROR_CHARS = 500;

    private final DocStore docStore;
    private final DocStorageLayout layout;
    private final MineruParseService mineru;
    private final ObjectMapper mapper;
    private final LibraryIndexManager indexManager;
    private final ExecutorService executor;

    public DocParsePipeline(DocStore docStore, DocStorageLayout layout, MineruParseService mineru,
            ObjectMapper mapper, LibraryIndexManager indexManager, MarkAgentProperties props) {
        this.docStore = docStore;
        this.layout = layout;
        this.mineru = mineru;
        this.mapper = mapper;
        this.indexManager = indexManager;
        int concurrency = props.docs() == null ? 2 : props.docs().parseConcurrency();
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "doc-parse");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 受理解析：记录已落盘为 PARSING，线程池异步执行 */
    public void submit(DocRecord doc) {
        executor.execute(() -> run(doc));
    }

    private void run(DocRecord doc) {
        long startedNanos = System.nanoTime();
        try {
            Path original = layout.originalPath(doc.userId(), doc.libraryId(), doc.id(), doc.fileName());
            if (!Files.isRegularFile(original)) {
                throw new IOException("原件缺失");
            }
            long size = Files.size(original);
            log.info("文档解析开始 doc={} file={} size={}", doc.id(), doc.fileName(),
                    size < 1024 ? size + "B" : (size / 1024) + "KB");
            // 文本类本地直读：不进 MinerU，按行建块（行级定位，页码恒 1）
            if (DocStorageLayout.NATIVE_TEXT.contains(DocStorageLayout.suffix(doc.fileName()))) {
                List<DocBlock> blocks = nativeTextBlocks(original);
                if (blocks.isEmpty()) {
                    throw new IOException("文本文件无可用内容");
                }
                persistNativeBlocks(doc, blocks);
                indexManager.addDocument(doc, blocks);
                finish(doc, DocStatus.READY, 1, null);
                log.info("文本文档入库完成 doc={} blocks={} 耗时 {}s", doc.id(), blocks.size(),
                        elapsedSeconds(startedNanos));
                return;
            }
            Path zip = layout.docDir(doc.userId(), doc.libraryId(), doc.id()).resolve("mineru-result.zip");
            Files.createDirectories(zip.getParent());
            // 解析阶段落库（queued 由受理方写入；此处从提交开始），供前端展示「解析中·阶段」
            mineru.parse(original, doc.id(), zip, stage -> updateStage(doc, stage));

            Path mineruDir = layout.mineruDir(doc.userId(), doc.libraryId(), doc.id());
            unzip(zip, mineruDir);
            Files.deleteIfExists(zip);

            Path md = requireSingleMd(mineruDir);
            if (Files.size(md) == 0) {
                throw new IOException("MinerU 产物 md 为空");
            }
            int pages = countPages(mineruDir, md);

            // 块序列读取 + 库索引写入（先删后写，重试幂等）；索引失败按解析失败处理
            List<DocBlock> blocks = BlockReader.read(mineruDir, mapper);
            if (blocks.isEmpty()) {
                throw new IOException("MinerU 产物无可索引内容块");
            }
            indexManager.addDocument(doc, blocks);

            finish(doc, DocStatus.READY, pages, null);
            log.info("文档解析完成 doc={} pages={} blocks={} 耗时 {}s", doc.id(), pages, blocks.size(),
                    elapsedSeconds(startedNanos));
        } catch (Exception e) {
            log.warn("文档解析失败 doc={} 耗时 {}s: {}", doc.id(), elapsedSeconds(startedNanos), e.getMessage());
            finish(doc, DocStatus.FAILED, -1, truncate(e.getMessage()));
        }
    }

    /** 解析阶段落库（仅阶段变化时由 MineruParseService 触发；终态前文档已被删则跳过） */
    private void updateStage(DocRecord doc, String stage) {
        docStore.get(doc.id()).ifPresent(current -> {
            if (current.status() != DocStatus.PARSING) {
                return;
            }
            docStore.save(new DocRecord(current.id(), current.libraryId(), current.userId(),
                    current.fileName(), current.status(), stage, current.pageCount(), current.error(),
                    current.createdAt(), Instant.now().toString()));
        });
    }

    /**
     * 终态落盘：先回查记录，文档已被删除（用户删库/删文档）则不复活记录，
     * 只清理产物目录，避免把已删除文档写回存储。
     */
    private void finish(DocRecord doc, DocStatus status, int pages, String error) {
        docStore.get(doc.id()).ifPresentOrElse(current -> {
            docStore.save(new DocRecord(current.id(), current.libraryId(), current.userId(),
                    current.fileName(), status, null, pages, error, current.createdAt(), Instant.now().toString()));
        }, () -> {
            log.info("文档 {} 已被删除，跳过终态落盘", doc.id());
            deleteDir(layout.docDir(doc.userId(), doc.libraryId(), doc.id()));
        });
    }

    private static long elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000L;
    }

    /** 文本类直读：非空行建块（# 开头标 heading 类型），页码恒 1 */
    private static List<DocBlock> nativeTextBlocks(Path file) throws IOException {
        List<DocBlock> out = new ArrayList<>();
        int index = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            String type = text.startsWith("#") ? "heading" : "text";
            out.add(new DocBlock(index++, type, text, 1));
        }
        return out;
    }

    /** 块序列落盘（重启后 LibraryIndexManager 装载块视图用） */
    private void persistNativeBlocks(DocRecord doc, List<DocBlock> blocks) throws IOException {
        Path target = layout.nativeBlocksPath(doc.userId(), doc.libraryId(), doc.id());
        Files.createDirectories(target.getParent());
        mapper.writeValue(target.toFile(), blocks);
    }

    /** 服务启动恢复：遗留 PARSING 一律 FAILED（解析不可跨重启续跑，用户重试即可） */
    @Override
    public void run(ApplicationArguments args) {
        List<DocRecord> stale = docStore.listAll(DocStatus.PARSING);
        if (stale.isEmpty()) {
            return;
        }
        log.info("启动恢复：{} 个 PARSING 文档标记 FAILED", stale.size());
        for (DocRecord doc : stale) {
            docStore.save(new DocRecord(doc.id(), doc.libraryId(), doc.userId(), doc.fileName(),
                    DocStatus.FAILED, null, -1, "服务重启中断解析，请重试", doc.createdAt(),
                    Instant.now().toString()));
        }
    }

    /** ZIP 解包到 mineruDir（zip-slip 防护 + 总量/条目上限） */
    private static void unzip(Path zip, Path dir) throws IOException {
        Files.createDirectories(dir);
        long total = 0;
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new IOException("ZIP 条目数超过上限 " + MAX_ENTRIES);
                }
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("ZIP 路径越出产物目录: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    total += copyBounded(zin, out, MAX_EXTRACT_BYTES - total);
                }
            }
        }
    }

    /** 限量拷贝：剩余额度耗尽即抛异常（防 zip 炸弹） */
    private static long copyBounded(InputStream in, OutputStream out, long budget) throws IOException {
        if (budget <= 0) {
            throw new IOException("ZIP 解包总量超过 " + MAX_EXTRACT_BYTES + " 字节上限");
        }
        byte[] buf = new byte[64 * 1024];
        long written = 0;
        int n;
        while ((n = in.read(buf, 0, (int) Math.min(buf.length, budget - written))) > 0) {
            out.write(buf, 0, n);
            written += n;
            if (written >= budget) {
                if (in.read() != -1) {
                    throw new IOException("ZIP 解包总量超过 " + MAX_EXTRACT_BYTES + " 字节上限");
                }
                break;
            }
        }
        return written;
    }

    /** 产物目录须恰好一个 md 文件 */
    private static Path requireSingleMd(Path mineruDir) throws IOException {
        List<Path> mds = new ArrayList<>();
        try (var stream = Files.walk(mineruDir)) {
            stream.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .forEach(mds::add);
        }
        if (mds.size() != 1) {
            throw new IOException("MinerU 产物 md 数量异常: " + mds.size() + " 个");
        }
        return mds.get(0);
    }

    /**
     * 页数统计（4.x 产物优先，3.x 产物回退）：
     * structured_content.json / middle_json.json 的 pages 数组长度或 metadata 的 page_count；
     * 回退 content_list v1（page_idx 最大 +1）/ v2（每页数组长度）；都没有则 0。
     */
    private int countPages(Path mineruDir, Path md) {
        String stem = md.getFileName().toString().replaceFirst("(?i)\\.md$", "");
        try {
            Path sc = md.resolveSibling("structured_content.json");
            if (Files.isRegularFile(sc)) {
                int pages = pagesFrom(mapper.readTree(Files.readString(sc, StandardCharsets.UTF_8)));
                if (pages > 0) {
                    return pages;
                }
            }
            Path middle = md.resolveSibling("middle_json.json");
            if (Files.isRegularFile(middle)) {
                int pages = pagesFrom(mapper.readTree(Files.readString(middle, StandardCharsets.UTF_8)));
                if (pages > 0) {
                    return pages;
                }
            }
            Path v1 = md.resolveSibling(stem + "_content_list.json");
            if (Files.isRegularFile(v1)) {
                JsonNode root = mapper.readTree(Files.readString(v1, StandardCharsets.UTF_8));
                if (root.isArray()) {
                    int max = -1;
                    for (JsonNode item : root) {
                        if (item.has("page_idx") && item.get("page_idx").isInt()) {
                            max = Math.max(max, item.get("page_idx").asInt());
                        }
                    }
                    if (max >= 0) {
                        return max + 1;
                    }
                }
            }
            Path v2 = md.resolveSibling(stem + "_content_list_v2.json");
            if (Files.isRegularFile(v2)) {
                JsonNode root = mapper.readTree(Files.readString(v2, StandardCharsets.UTF_8));
                if (root.isArray()) {
                    return root.size();
                }
            }
        } catch (IOException e) {
            log.warn("页数统计失败，按 0 处理: {}", e.getMessage());
        }
        return 0;
    }

    /** 4.x 产物：pages 数组长度优先，其次 metadata.document.page_count */
    private static int pagesFrom(JsonNode root) {
        JsonNode pages = root.path("pages");
        if (pages.isArray() && pages.size() > 0) {
            return pages.size();
        }
        JsonNode count = root.path("metadata").path("document").path("page_count");
        if (count.isInt() && count.asInt() > 0) {
            return count.asInt();
        }
        return 0;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "未知错误";
        }
        return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) + "…" : text;
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("目录清理失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("目录清理扫描失败 {}: {}", dir, e.getMessage());
        }
    }
}
