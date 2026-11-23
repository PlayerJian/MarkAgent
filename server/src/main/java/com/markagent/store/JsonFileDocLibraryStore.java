package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.DocLibraryRecord;
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

/** 文档库 JSON 文件存储：data/docs/libraries/{id}.json（写入走 AtomicJsonFile 底座） */
@Component
public class JsonFileDocLibraryStore implements DocLibraryStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileDocLibraryStore.class);

    private final Path librariesDir;
    private final ObjectMapper mapper;

    public JsonFileDocLibraryStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.librariesDir = storageRoot.resolve("docs").resolve("libraries");
    }

    @Override
    public void save(DocLibraryRecord library) {
        try {
            new AtomicJsonFile(librariesDir.resolve(library.id() + ".json")).writeJson(mapper, library);
        } catch (IOException e) {
            log.error("文档库落盘失败 id={}: {}", library.id(), e.getMessage(), e);
        }
    }

    @Override
    public Optional<DocLibraryRecord> get(String id) {
        try {
            return Optional.ofNullable(new AtomicJsonFile(librariesDir.resolve(id + ".json"))
                    .readAs(mapper, DocLibraryRecord.class));
        } catch (IOException e) {
            log.warn("文档库读取失败 id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<DocLibraryRecord> list(String userId) {
        List<DocLibraryRecord> out = new ArrayList<>();
        if (!Files.isDirectory(librariesDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(librariesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .forEach(p -> {
                        try {
                            DocLibraryRecord library = new AtomicJsonFile(p)
                                    .readAs(mapper, DocLibraryRecord.class);
                            if (library != null && userId.equals(library.userId())) {
                                out.add(library);
                            }
                        } catch (IOException e) {
                            log.warn("文档库列表读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("文档库目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void delete(String id) {
        try {
            new AtomicJsonFile(librariesDir.resolve(id + ".json")).delete();
        } catch (IOException e) {
            log.warn("文档库删除失败 id={}: {}", id, e.getMessage());
        }
    }
}
