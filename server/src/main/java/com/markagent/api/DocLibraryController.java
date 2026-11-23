package com.markagent.api;

import com.markagent.common.dto.DocLibraryRecord;
import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;
import com.markagent.docs.DocLibraryService;
import com.markagent.docs.DocParsePipeline;
import com.markagent.docs.DocStorageLayout;
import com.markagent.docs.LibraryIndexManager;
import com.markagent.store.DocLibraryStore;
import com.markagent.store.DocStore;
import com.markagent.util.Ids;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 文档库与文档 REST 端点（/api/v1/docs/**）。
 * 用户隔离：记录按 userId 过滤 + 文件目录 data/docs/files/{userId}/...；
 * 归属校验沿用 404 不泄露模式（同 ChatController）。
 */
@RestController
@RequestMapping("/api/v1/docs")
public class DocLibraryController {

    private final DocLibraryStore libraryStore;
    private final DocStore docStore;
    private final DocStorageLayout layout;
    private final DocParsePipeline pipeline;
    private final LibraryIndexManager indexManager;
    private final DocLibraryService libraryService;

    public DocLibraryController(DocLibraryStore libraryStore, DocStore docStore,
            DocStorageLayout layout, DocParsePipeline pipeline, LibraryIndexManager indexManager,
            DocLibraryService libraryService) {
        this.libraryStore = libraryStore;
        this.docStore = docStore;
        this.layout = layout;
        this.pipeline = pipeline;
        this.indexManager = indexManager;
        this.libraryService = libraryService;
    }

    public record CreateLibraryRequest(String name) {
    }

    // ==================== 文档库 ====================

    @PostMapping("/libraries")
    public DocLibraryRecord create(@RequestBody CreateLibraryRequest request, Authentication authentication) {
        if (request.name() == null || request.name().isBlank()) {
            throw GlobalExceptionHandler.badRequest("库名不能为空");
        }
        String name = request.name().trim();
        if (name.length() > 100) {
            throw GlobalExceptionHandler.badRequest("库名过长（≤100 字符）");
        }
        DocLibraryRecord library = new DocLibraryRecord(Ids.docLibrary(), authentication.getName(),
                name, Instant.now().toString());
        libraryStore.save(library);
        return library;
    }

    @GetMapping("/libraries")
    public List<DocLibraryRecord> libraries(Authentication authentication) {
        // 单文件问答的临时库（up_ 前缀）是会话内部资源，不进用户库列表
        return libraryStore.list(authentication.getName()).stream()
                .filter(l -> !l.id().startsWith("up_"))
                .toList();
    }

    @DeleteMapping("/libraries/{id}")
    public Map<String, String> deleteLibrary(@PathVariable("id") String id, Authentication authentication) {
        requireOwnedLibrary(id, authentication);
        libraryService.deleteLibrary(authentication.getName(), id);
        return Map.of("deleted", id);
    }

    /** 单文件问答的临时上传：落盘返回 uploadId，发起 /chat 时经 source.uploadId 引用 */
    @PostMapping("/temp-uploads")
    public Map<String, String> tempUpload(@RequestParam("file") MultipartFile file, Authentication authentication) {
        if (file == null || file.isEmpty()) {
            throw GlobalExceptionHandler.badRequest("未收到文件");
        }
        String fileName = DocStorageLayout.sanitizeFileName(file.getOriginalFilename());
        if (fileName == null || fileName.isBlank()) {
            throw GlobalExceptionHandler.badRequest("文件名非法: " + file.getOriginalFilename());
        }
        String suffix = DocStorageLayout.suffix(fileName);
        if (!DocStorageLayout.SUPPORTED.contains(suffix)) {
            throw GlobalExceptionHandler.badRequest("不支持的文件格式: " + fileName
                    + "（支持 " + String.join(" ", DocStorageLayout.SUPPORTED) + "）");
        }
        String uploadId = Ids.next("up_");
        // 直接以 sanitize 后的原文件名落盘（保留文件名信息，basename 已防路径注入）
        Path original = layout.tmpUploadDir(authentication.getName(), uploadId).resolve(fileName);
        try {
            Files.createDirectories(original.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, original);
            }
        } catch (IOException e) {
            throw new IllegalStateException("临时上传保存失败: " + e.getMessage(), e);
        }
        return Map.of("uploadId", uploadId, "fileName", fileName);
    }

    // ==================== 文档 ====================

    /** 上传解析：受理后立即返回（PARSING），解析在流水线线程池异步执行 */
    @PostMapping("/libraries/{id}/documents")
    public List<DocRecord> upload(@PathVariable("id") String libraryId,
            @RequestParam("files") MultipartFile[] files, Authentication authentication) {
        requireOwnedLibrary(libraryId, authentication);
        if (files == null || files.length == 0) {
            throw GlobalExceptionHandler.badRequest("未收到文件");
        }
        String now = Instant.now().toString();
        List<DocRecord> out = new ArrayList<>();
        for (MultipartFile file : files) {
            String fileName = DocStorageLayout.sanitizeFileName(file.getOriginalFilename());
            if (fileName == null || fileName.isBlank()) {
                throw GlobalExceptionHandler.badRequest("文件名非法: " + file.getOriginalFilename());
            }
            String suffix = DocStorageLayout.suffix(fileName);
            if (!DocStorageLayout.SUPPORTED.contains(suffix)) {
                throw GlobalExceptionHandler.badRequest("不支持的文件格式: " + fileName
                        + "（支持 " + String.join(" ", DocStorageLayout.SUPPORTED) + "）");
            }
            DocRecord doc = new DocRecord(Ids.doc(), libraryId, authentication.getName(),
                    fileName, DocStatus.PARSING, "queued", -1, null, now, now);
            Path original = layout.originalPath(doc.userId(), doc.libraryId(), doc.id(), doc.fileName());
            try {
                Files.createDirectories(original.getParent());
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, original);
                }
            } catch (IOException e) {
                throw new IllegalStateException("原件保存失败: " + e.getMessage(), e);
            }
            docStore.save(doc);
            pipeline.submit(doc);
            out.add(doc);
        }
        return out;
    }

    @GetMapping("/libraries/{id}/documents")
    public List<DocRecord> documents(@PathVariable("id") String libraryId, Authentication authentication) {
        requireOwnedLibrary(libraryId, authentication);
        return docStore.list(libraryId);
    }

    @GetMapping("/documents/{id}")
    public DocRecord document(@PathVariable("id") String id, Authentication authentication) {
        return requireOwnedDoc(id, authentication);
    }

    /** 失败重试：FAILED → PARSING 并重新提交流水线 */
    @PostMapping("/documents/{id}/retry")
    public DocRecord retry(@PathVariable("id") String id, Authentication authentication) {
        DocRecord doc = requireOwnedDoc(id, authentication);
        if (doc.status() != DocStatus.FAILED) {
            throw GlobalExceptionHandler.badRequest("仅失败状态可重试，当前 " + doc.status());
        }
        DocRecord reset = new DocRecord(doc.id(), doc.libraryId(), doc.userId(), doc.fileName(),
                DocStatus.PARSING, "queued", -1, null, doc.createdAt(), Instant.now().toString());
        docStore.save(reset);
        pipeline.submit(reset);
        return reset;
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, String> deleteDocument(@PathVariable("id") String id, Authentication authentication) {
        DocRecord doc = requireOwnedDoc(id, authentication);
        docStore.delete(id);
        try {
            indexManager.removeDocument(doc.userId(), doc.libraryId(), doc.id());
        } catch (IOException e) {
            // 索引清理失败不阻断删文档
        }
        deleteDir(layout.docDir(doc.userId(), doc.libraryId(), doc.id()));
        return Map.of("deleted", id);
    }

    // ==================== 归属校验（404 不泄露存在性） ====================

    private DocLibraryRecord requireOwnedLibrary(String libraryId, Authentication authentication) {
        DocLibraryRecord library = libraryStore.get(libraryId)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("文档库 " + libraryId));
        if (!library.userId().equals(authentication.getName())) {
            throw GlobalExceptionHandler.notFound("文档库 " + libraryId);
        }
        return library;
    }

    private DocRecord requireOwnedDoc(String docId, Authentication authentication) {
        DocRecord doc = docStore.get(docId)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("文档 " + docId));
        if (!doc.userId().equals(authentication.getName())) {
            throw GlobalExceptionHandler.notFound("文档 " + docId);
        }
        return doc;
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
                    // 清理失败不阻断删库响应
                }
            });
        } catch (IOException e) {
            // 同上
        }
    }
}
