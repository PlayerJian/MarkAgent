package com.markagent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.McpServerConfig;
import com.markagent.util.Ids;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** MCP 服务器配置存储：data/mcp.json（列表整体读写，走 AtomicJsonFile） */
@Component
public class McpConfigStore {

    private final AtomicJsonFile file;
    private final ObjectMapper mapper;

    public McpConfigStore(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.file = new AtomicJsonFile(storageRoot.resolve("mcp.json"));
    }

    public synchronized List<McpServerConfig> list() {
        if (!file.exists()) {
            return List.of();
        }
        try {
            return mapper.readValue(file.readString(), new TypeReference<List<McpServerConfig>>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    public Optional<McpServerConfig> get(String id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** 新增（自动生成 id） */
    public synchronized McpServerConfig add(McpServerConfig config) throws IOException {
        McpServerConfig withId = new McpServerConfig(Ids.next("mcp_"), config.name(), config.transport(),
                config.command(), config.args(), config.env(), config.url(), config.enabled());
        List<McpServerConfig> all = new ArrayList<>(list());
        all.add(withId);
        save(all);
        return withId;
    }

    public synchronized void update(McpServerConfig config) throws IOException {
        List<McpServerConfig> all = new ArrayList<>(list());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(config.id())) {
                all.set(i, config);
                save(all);
                return;
            }
        }
        throw new IOException("MCP 服务器不存在: " + config.id());
    }

    public synchronized void delete(String id) throws IOException {
        List<McpServerConfig> all = new ArrayList<>(list());
        if (all.removeIf(c -> c.id().equals(id))) {
            save(all);
        }
    }

    private void save(List<McpServerConfig> all) throws IOException {
        file.writeString(mapper.writeValueAsString(all));
    }
}
