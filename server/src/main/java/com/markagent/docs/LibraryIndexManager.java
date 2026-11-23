package com.markagent.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;
import com.markagent.store.DocStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库索引生命周期管理：userId+libraryId → 句柄（Lucene 写/搜 + 块序列内存视图）。
 * <ul>
 *   <li>索引目录 data/docs/indexes/{userId}/{libraryId}/lucene（磁盘持久化，重启即用）</li>
 *   <li>句柄懒打开：打开时装载库内 READY 文档的块序列（解析时已冻结在产物目录）</li>
 *   <li>单写者：写入发生在解析流水线线程；删除经控制器触发</li>
 * </ul>
 */
@Component
public class LibraryIndexManager {

    private static final Logger log = LoggerFactory.getLogger(LibraryIndexManager.class);

    public record Handle(LibraryIndex index, LibraryBlockStore blocks) {
    }

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final DocStore docStore;
    private final DocStorageLayout layout;
    private final ObjectMapper mapper;
    private final Path indexesRoot;

    public LibraryIndexManager(DocStore docStore, DocStorageLayout layout, ObjectMapper mapper, Path storageRoot) {
        this.docStore = docStore;
        this.layout = layout;
        this.mapper = mapper;
        this.indexesRoot = storageRoot.resolve("docs").resolve("indexes");
    }

    private static String key(String userId, String libraryId) {
        return userId + "/" + libraryId;
    }

    private Path indexPath(String userId, String libraryId) {
        return indexesRoot.resolve(userId).resolve(libraryId).resolve("lucene");
    }

    /** 打开/获取句柄：首次打开时装载库内全部 READY 文档的块序列 */
    public synchronized Handle handle(String userId, String libraryId) throws IOException {
        String key = key(userId, libraryId);
        Handle handle = handles.get(key);
        if (handle == null) {
            LibraryIndex index = LibraryIndex.open(indexPath(userId, libraryId));
            LibraryBlockStore blocks = new LibraryBlockStore();
            for (DocRecord doc : docStore.list(libraryId)) {
                if (doc.status() != DocStatus.READY) {
                    continue;
                }
                List<DocBlock> list = BlockReader.read(
                        layout.mineruDir(doc.userId(), doc.libraryId(), doc.id()), mapper);
                if (list.isEmpty()) {
                    // 文本类直读文档：块序列在 native-blocks.json
                    list = readNativeBlocks(doc);
                }
                if (!list.isEmpty()) {
                    blocks.put(doc.id(), list);
                }
            }
            handle = new Handle(index, blocks);
            handles.put(key, handle);
            log.info("文档库索引打开 {}（块序列 {} 篇）", key, blocks.docIds().size());
        }
        return handle;
    }

    /** 解析完成入库：先删后写保证重试幂等，块序列装载进内存视图 */
    public void addDocument(DocRecord doc, List<DocBlock> blocks) throws IOException {
        Handle handle = handle(doc.userId(), doc.libraryId());
        handle.index().deleteDocument(doc.id());
        handle.index().addDocument(doc.id(), blocks);
        handle.blocks().put(doc.id(), blocks);
    }

    public void removeDocument(String userId, String libraryId, String docId) throws IOException {
        Handle handle = handles.get(key(userId, libraryId));
        if (handle != null) {
            handle.index().deleteDocument(docId);
            handle.blocks().remove(docId);
        }
    }

    /** 删库：关闭句柄并清索引目录 */
    public void removeLibrary(String userId, String libraryId) throws IOException {
        Handle handle = handles.remove(key(userId, libraryId));
        if (handle != null) {
            handle.index().close();
        }
        deleteDir(indexPath(userId, libraryId).getParent());
    }

    public List<LibraryIndex.Hit> search(String userId, String libraryId, String query, int topK) throws IOException {
        return handle(userId, libraryId).index().search(query, topK);
    }

    public LibraryBlockStore blocks(String userId, String libraryId) throws IOException {
        return handle(userId, libraryId).blocks();
    }

    /** 装载文本类直读文档的块序列 */
    private List<DocBlock> readNativeBlocks(DocRecord doc) {
        Path file = layout.nativeBlocksPath(doc.userId(), doc.libraryId(), doc.id());
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            var type = mapper.getTypeFactory().constructCollectionType(List.class, DocBlock.class);
            List<DocBlock> blocks = mapper.readValue(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8), type);
            return blocks == null ? List.of() : blocks;
        } catch (IOException e) {
            log.warn("块序列读取失败 {}: {}", file, e.getMessage());
            return List.of();
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
                    log.warn("索引目录清理失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("索引目录清理扫描失败 {}: {}", dir, e.getMessage());
        }
    }
}
