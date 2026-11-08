package com.markagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 用户 JSON 文件存储：data/users/users.json（数组；注册并发经 AtomicJsonFile 进程内锁串行化） */
@Component
public class JsonFileUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileUserStore.class);

    private final AtomicJsonFile file;
    private final ObjectMapper mapper;

    public JsonFileUserStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.file = new AtomicJsonFile(storageRoot.resolve("users").resolve("users.json"));
    }

    @Override
    public synchronized Optional<UserEntry> findByUsername(String username) {
        return listAll().stream().filter(u -> u.username().equalsIgnoreCase(username)).findFirst();
    }

    @Override
    public synchronized Optional<UserEntry> findById(String id) {
        return listAll().stream().filter(u -> u.id().equals(id)).findFirst();
    }

    @Override
    public synchronized List<UserEntry> listAll() {
        List<UserEntry> out = new ArrayList<>();
        try {
            JsonNode root = file.readJson(mapper);
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    out.add(new UserEntry(
                            node.path("id").asText(),
                            node.path("username").asText(),
                            node.path("displayName").asText(),
                            node.path("passwordHash").asText(),
                            node.path("createdAt").asText()));
                }
            }
        } catch (IOException e) {
            log.warn("用户列表读取失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public synchronized void save(UserEntry user) {
        try {
            JsonNode root = file.readJson(mapper);
            ArrayNode array = root != null && root.isArray() ? (ArrayNode) root : mapper.createArrayNode();
            ObjectNode node = mapper.createObjectNode();
            node.put("id", user.id());
            node.put("username", user.username());
            node.put("displayName", user.displayName());
            node.put("passwordHash", user.passwordHash());
            node.put("createdAt", user.createdAt());
            // 同 id 覆盖（注册时 id 唯一，不会发生；保留幂等语义）
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).path("id").asText().equals(user.id())) {
                    array.set(i, node);
                    file.writeJson(mapper, array);
                    return;
                }
            }
            array.add(node);
            file.writeJson(mapper, array);
        } catch (IOException e) {
            log.error("用户落盘失败 user={}: {}", user.username(), e.getMessage(), e);
        }
    }
}
