package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.CheckpointRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 检查点 JSON 文件存储：data/checkpoints/{taskId}/{seq}.json */
@Component
public class JsonFileCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileCheckpointStore.class);

    private final Path checkpointsDir;
    private final ObjectMapper mapper;

    public JsonFileCheckpointStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.checkpointsDir = storageRoot.resolve("checkpoints");
    }

    @Override
    public void save(String taskId, CheckpointRecord checkpoint) {
        AtomicJsonFile file = new AtomicJsonFile(checkpointsDir.resolve(taskId).resolve(checkpoint.seq() + ".json"));
        try {
            file.writeJson(mapper, checkpoint);
        } catch (IOException e) {
            log.error("检查点落盘失败 task={}: {}", taskId, e.getMessage(), e);
        }
    }

    @Override
    public List<CheckpointRecord> list(String taskId) {
        Path dir = checkpointsDir.resolve(taskId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CheckpointRecord> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> seqOf(p)))
                    .forEach(p -> {
                        try {
                            CheckpointRecord checkpoint = new AtomicJsonFile(p).readAs(mapper, CheckpointRecord.class);
                            if (checkpoint != null) {
                                out.add(checkpoint);
                            }
                        } catch (IOException e) {
                            log.warn("检查点读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("检查点目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void delete(String taskId) {
        try (Stream<Path> files = Files.walk(checkpointsDir.resolve(taskId))) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("检查点删除失败 task={}: {}", taskId, e.getMessage());
        }
    }

    private long seqOf(Path p) {
        try {
            return Long.parseLong(p.getFileName().toString().replace(".json", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
