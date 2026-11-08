package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.SessionRecord;
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

/** 会话 JSON 文件存储：data/sessions/{userId}/{sessionId}.json（userId 一层目录即多用户隔离） */
@Component
public class JsonFileSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileSessionStore.class);

    private final Path sessionsDir;
    private final ObjectMapper mapper;

    public JsonFileSessionStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.sessionsDir = storageRoot.resolve("sessions");
    }

    @Override
    public void save(SessionRecord session) {
        AtomicJsonFile file = new AtomicJsonFile(dirOf(session.userId()).resolve(session.id() + ".json"));
        try {
            file.writeJson(mapper, session);
        } catch (IOException e) {
            log.error("会话落盘失败 session={}: {}", session.id(), e.getMessage(), e);
        }
    }

    @Override
    public Optional<SessionRecord> get(String userId, String sessionId) {
        AtomicJsonFile file = new AtomicJsonFile(dirOf(userId).resolve(sessionId + ".json"));
        try {
            return Optional.ofNullable(file.readAs(mapper, SessionRecord.class));
        } catch (IOException e) {
            log.warn("会话读取失败 session={}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<SessionRecord> listByUser(String userId) {
        Path dir = dirOf(userId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SessionRecord> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .forEach(p -> {
                        try {
                            SessionRecord session = new AtomicJsonFile(p).readAs(mapper, SessionRecord.class);
                            if (session != null) {
                                out.add(session);
                            }
                        } catch (IOException e) {
                            log.warn("会话列表读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("会话目录扫描失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            new AtomicJsonFile(dirOf(userId).resolve(sessionId + ".json")).delete();
        } catch (IOException e) {
            log.warn("会话删除失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private Path dirOf(String userId) {
        return sessionsDir.resolve(userId == null ? "local-user" : userId);
    }
}
