package com.markagent.docs;

import com.markagent.common.dto.DocLibraryRecord;
import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;
import com.markagent.store.DocLibraryStore;
import com.markagent.store.DocStore;
import com.markagent.store.SessionStore;
import com.markagent.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 文档库/临时库生命周期服务：
 * <ul>
 *   <li>deleteLibrary：全量删除（文档目录 + 记录 + 索引 + 库目录），供控制器与会话删除共用</li>
 *   <li>prepareTempLibrary：单文件问答入口——临时上传落位成临时库（id=uploadId），
 *       解析 + 建索引同步就绪后返回；同会话多轮问答复用已有库</li>
 *   <li>启动清理（按用户目录走，不暴露任何全量 store 接口）：
 *       24h 前未认领的临时上传；无任何会话引用的临时库孤儿</li>
 * </ul>
 */
@Service
public class DocLibraryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocLibraryService.class);

    /** 单文件解析等待上限 */
    private static final long PREPARE_TIMEOUT_MINUTES = 10;
    /** 未认领临时上传保留时长 */
    private static final long UPLOAD_TTL_HOURS = 24;

    private final DocLibraryStore libraryStore;
    private final DocStore docStore;
    private final DocStorageLayout layout;
    private final LibraryIndexManager indexManager;
    private final DocParsePipeline pipeline;
    private final SessionStore sessionStore;

    public DocLibraryService(DocLibraryStore libraryStore, DocStore docStore, DocStorageLayout layout,
            LibraryIndexManager indexManager, DocParsePipeline pipeline, SessionStore sessionStore) {
        this.libraryStore = libraryStore;
        this.docStore = docStore;
        this.layout = layout;
        this.indexManager = indexManager;
        this.pipeline = pipeline;
        this.sessionStore = sessionStore;
    }

    /** 全量删除文档库（记录 + 文件 + 索引 + 目录） */
    public void deleteLibrary(String userId, String libraryId) {
        for (DocRecord doc : docStore.list(libraryId)) {
            deleteDir(layout.docDir(userId, libraryId, doc.id()));
        }
        docStore.deleteByLibrary(libraryId);
        libraryStore.delete(libraryId);
        try {
            indexManager.removeLibrary(userId, libraryId);
        } catch (IOException e) {
            log.warn("索引清理失败 lib={}: {}", libraryId, e.getMessage());
        }
        deleteDir(layout.libraryDir(userId, libraryId));
        log.info("文档库已删除 lib={}", libraryId);
    }

    /**
     * 绑定告知：任务启动时注入首条消息，让 agent 明确知道「本会话已绑定文档库、库里有什么」。
     * 否则 agent 会按「用户没提供资料」假设回复，连 doc_search 都不调用。
     * 无库/无 READY 文档时返回 null（不注入）。
     */
    public String describeBinding(String userId, String libraryId) {
        var library = libraryStore.get(libraryId).orElse(null);
        if (library == null || !library.userId().equals(userId)) {
            return null;
        }
        List<String> files = docStore.list(libraryId).stream()
                .filter(d -> d.status() == DocStatus.READY)
                .map(DocRecord::fileName)
                .distinct()
                .toList();
        if (files.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("## 已绑定文档库\n本会话已绑定文档库「")
                .append(library.name()).append("」，已入库文档：\n");
        for (String file : files) {
            sb.append("- ").append(file).append('\n');
        }
        sb.append("\n涉及这些文档内容的问题，请先用 doc_search 检索（空格分隔关键词，如「归母 净利润」），"
                + "结果不足再换词或用 doc_read 扩读；不要假设没有资料，也不要要求用户另行提供文件。\n");
        return sb.toString();
    }

    /**
     * 单文件问答准备：把临时上传落位成临时库（id=uploadId）并同步等待解析+索引就绪。
     * 库已存在（PAUSED 后 resume）时直接等待其文档状态。
     *
     * @return READY 的文档记录；失败/超时抛 IllegalStateException（由调用方转任务失败）
     */
    public DocRecord prepareTempLibrary(String userId, String uploadId) {
        var existing = libraryStore.get(uploadId);
        if (existing.isPresent()) {
            if (!existing.get().userId().equals(userId)) {
                throw new IllegalStateException("临时上传不存在");
            }
            return waitReady(uploadId);
        }

        Path original = findOriginal(layout.tmpUploadDir(userId, uploadId));
        if (original == null) {
            throw new IllegalStateException("临时上传不存在或已过期");
        }
        String fileName = original.getFileName().toString();
        String now = Instant.now().toString();
        libraryStore.save(new DocLibraryRecord(uploadId, userId, "临时文档：" + fileName, now));
        DocRecord doc = new DocRecord(Ids.doc(), uploadId, userId, fileName,
                DocStatus.PARSING, "queued", -1, null, now, now);
        try {
            Path target = layout.originalPath(userId, uploadId, doc.id(), fileName);
            Files.createDirectories(target.getParent());
            Files.move(original, target, StandardCopyOption.REPLACE_EXISTING);
            deleteDir(layout.tmpUploadDir(userId, uploadId));
        } catch (IOException e) {
            throw new IllegalStateException("临时文件迁移失败: " + e.getMessage(), e);
        }
        docStore.save(doc);
        pipeline.submit(doc);
        log.info("单文件问答准备中 upload={} doc={}", uploadId, doc.id());
        return waitReady(uploadId);
    }

    /** 轮询等待临时库唯一文档 READY（解析+索引在流水线内完成） */
    private DocRecord waitReady(String libraryId) {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(PREPARE_TIMEOUT_MINUTES);
        while (System.currentTimeMillis() < deadline) {
            List<DocRecord> docs = docStore.list(libraryId);
            if (docs.isEmpty()) {
                throw new IllegalStateException("临时库无文档记录");
            }
            DocRecord doc = docs.get(0);
            if (doc.status() == DocStatus.READY) {
                return doc;
            }
            if (doc.status() == DocStatus.FAILED) {
                throw new IllegalStateException("文档解析失败：" + doc.error());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待解析被中断");
            }
        }
        throw new IllegalStateException("文档解析超时（" + PREPARE_TIMEOUT_MINUTES + " 分钟）");
    }

    /** 启动清理：按用户目录遍历（用户目录即隔离边界），不依赖任何全量 store 接口 */
    @Override
    public void run(ApplicationArguments args) {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(UPLOAD_TTL_HOURS);
        Path uploadsRoot = layout.root();
        if (!Files.isDirectory(uploadsRoot)) {
            return;
        }
        try (Stream<Path> users = Files.list(uploadsRoot)) {
            for (Path userDir : users.toList()) {
                String userId = userDir.getFileName().toString();
                cleanupExpiredTmpUploads(userDir, cutoff);
                cleanupOrphanTmpLibraries(userDir, userId);
            }
        } catch (IOException e) {
            log.warn("临时资源清理扫描失败: {}", e.getMessage());
        }
    }

    /** 24h 前未认领的临时上传 */
    private void cleanupExpiredTmpUploads(Path userDir, long cutoff) {
        Path tmpDir = userDir.resolve("tmp-uploads");
        if (!Files.isDirectory(tmpDir)) {
            return;
        }
        try (Stream<Path> uploads = Files.list(tmpDir)) {
            for (Path upload : uploads.toList()) {
                if (olderThan(upload, cutoff)) {
                    log.info("清理过期临时上传 {}", upload);
                    deleteDir(upload);
                }
            }
        } catch (IOException e) {
            log.warn("临时上传清理扫描失败 {}: {}", tmpDir, e.getMessage());
        }
    }

    /**
     * 孤儿临时库：文件系统里 up_ 前缀库目录，且该用户会话中无 docLibraryId 引用（会话已删/崩溃遗留）。
     * 引用检查只用该用户自己的 listByUser——不跨用户扫描。
     */
    private void cleanupOrphanTmpLibraries(Path userDir, String userId) {
        Path librariesDir = userDir.resolve("libraries");
        if (!Files.isDirectory(librariesDir)) {
            return;
        }
        List<String> referenced = sessionStore.listByUser(userId).stream()
                .map(s -> s.docLibraryId())
                .filter(id -> id != null && id.startsWith("up_"))
                .toList();
        try (Stream<Path> libs = Files.list(librariesDir)) {
            for (Path lib : libs.toList()) {
                String libId = lib.getFileName().toString();
                if (!libId.startsWith("up_") || referenced.contains(libId)) {
                    continue;
                }
                log.info("清理无会话引用的临时库 lib={} user={}", libId, userId);
                deleteLibrary(userId, libId);
            }
        } catch (IOException e) {
            log.warn("临时库孤儿扫描失败 {}: {}", librariesDir, e.getMessage());
        }
    }

    private static boolean olderThan(Path path, long cutoff) {
        try {
            return Files.getLastModifiedTime(path).toMillis() < cutoff;
        } catch (IOException e) {
            return false;
        }
    }

    /** 临时上传目录内找原件（目录内唯一常规文件，文件名即原文件名） */
    private static Path findOriginal(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
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
