package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
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

/** 任务 JSON 文件存储：data/tasks/{taskId}.json（写入走 AtomicJsonFile 底座） */
@Component
public class JsonFileTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileTaskStore.class);

    private final Path tasksDir;
    private final ObjectMapper mapper;

    public JsonFileTaskStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.tasksDir = storageRoot.resolve("tasks");
    }

    @Override
    public void save(TaskRecord task) {
        AtomicJsonFile file = new AtomicJsonFile(tasksDir.resolve(task.id() + ".json"));
        try {
            file.writeJson(mapper, task);
        } catch (IOException e) {
            log.error("任务落盘失败 task={}: {}", task.id(), e.getMessage(), e);
        }
    }

    @Override
    public Optional<TaskRecord> get(String taskId) {
        AtomicJsonFile file = new AtomicJsonFile(tasksDir.resolve(taskId + ".json"));
        try {
            return Optional.ofNullable(file.readAs(mapper, TaskRecord.class));
        } catch (IOException e) {
            log.warn("任务读取失败 task={}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<TaskRecord> list(String userId, TaskStatus status) {
        if (!Files.isDirectory(tasksDir)) {
            return List.of();
        }
        List<TaskRecord> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(tasksDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .forEach(p -> {
                        try {
                            TaskRecord task = new AtomicJsonFile(p).readAs(mapper, TaskRecord.class);
                            if (task == null) {
                                return;
                            }
                            if (userId != null && !userId.equals(task.userId())) {
                                return;
                            }
                            if (status != null && status != task.status()) {
                                return;
                            }
                            out.add(task);
                        } catch (IOException e) {
                            log.warn("任务列表读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("任务目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void delete(String taskId) {
        try {
            new AtomicJsonFile(tasksDir.resolve(taskId + ".json")).delete();
        } catch (IOException e) {
            log.warn("任务删除失败 task={}: {}", taskId, e.getMessage());
        }
    }
}
