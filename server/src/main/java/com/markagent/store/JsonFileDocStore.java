package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 文档 JSON 文件存储：data/docs/documents/{id}.json（写入走 AtomicJsonFile 底座） */
@Component
public class JsonFileDocStore implements DocStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileDocStore.class);

    private final Path documentsDir;
    private final ObjectMapper mapper;

    public JsonFileDocStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.documentsDir = storageRoot.resolve("docs").resolve("documents");
    }

    @Override
    public void save(DocRecord doc) {
        try {
            new AtomicJsonFile(documentsDir.resolve(doc.id() + ".json")).writeJson(mapper, doc);
        } catch (IOException e) {
            log.error("文档落盘失败 id={}: {}", doc.id(), e.getMessage(), e);
        }
    }

    @Override
    public Optional<DocRecord> get(String id) {
        try {
            return Optional.ofNullable(new AtomicJsonFile(documentsDir.resolve(id + ".json"))
                    .readAs(mapper, DocRecord.class));
        } catch (IOException e) {
            log.warn("文档读取失败 id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<DocRecord> list(String libraryId) {
        List<DocRecord> out = new ArrayList<>();
        if (!Files.isDirectory(documentsDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(documentsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .forEach(p -> {
                        try {
                            DocRecord doc = new AtomicJsonFile(p).readAs(mapper, DocRecord.class);
                            if (doc != null && libraryId.equals(doc.libraryId())) {
                                out.add(doc);
                            }
                        } catch (IOException e) {
                            log.warn("文档列表读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("文档目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public List<DocRecord> listAll(DocStatus status) {
        List<DocRecord> out = new ArrayList<>();
        if (!Files.isDirectory(documentsDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(documentsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    DocRecord doc = new AtomicJsonFile(p).readAs(mapper, DocRecord.class);
                    if (doc != null && doc.status() == status) {
                        out.add(doc);
                    }
                } catch (IOException e) {
                    log.warn("文档状态扫描读取失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("文档目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void delete(String id) {
        try {
            new AtomicJsonFile(documentsDir.resolve(id + ".json")).delete();
        } catch (IOException e) {
            log.warn("文档删除失败 id={}: {}", id, e.getMessage());
        }
    }

    @Override
    public void deleteByLibrary(String libraryId) {
        for (DocRecord doc : list(libraryId)) {
            delete(doc.id());
        }
    }
}
