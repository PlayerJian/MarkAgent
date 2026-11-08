package com.markagent.api;

import com.markagent.common.dto.McpServerConfig;
import com.markagent.mcp.McpServerManager;
import com.markagent.store.McpConfigStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 服务器管理：列表/增删改/启停/测试/工具清单 */
@RestController
@RequestMapping("/api/v1/mcp/servers")
public class McpController {

    private final McpConfigStore configStore;
    private final McpServerManager manager;

    public McpController(McpConfigStore configStore, McpServerManager manager) {
        this.configStore = configStore;
        this.manager = manager;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return configStore.list().stream().map(this::withStatus).toList();
    }

    @PostMapping
    public McpServerConfig add(@RequestBody McpServerConfig config) throws IOException {
        McpServerConfig saved = configStore.add(config);
        if (saved.enabled()) {
            try {
                manager.connect(saved);
            } catch (Exception e) {
                // 连接失败不阻塞保存：状态经 GET 暴露 error
            }
        }
        return saved;
    }

    @PutMapping("/{id}")
    public McpServerConfig update(@PathVariable String id, @RequestBody McpServerConfig config) throws IOException {
        McpServerConfig existing = configStore.get(id)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("MCP 服务器不存在: " + id));
        McpServerConfig merged = new McpServerConfig(id, config.name() == null ? existing.name() : config.name(),
                config.transport() == null ? existing.transport() : config.transport(),
                config.command() == null ? existing.command() : config.command(),
                config.args() == null || config.args().isEmpty() ? existing.args() : config.args(),
                config.env() == null || config.env().isEmpty() ? existing.env() : config.env(),
                config.url() == null ? existing.url() : config.url(),
                config.enabled());
        configStore.update(merged);
        if (merged.enabled()) {
            try {
                manager.connect(merged);
            } catch (Exception e) {
                // 连接失败不阻塞保存
            }
        } else {
            manager.disconnect(id);
        }
        return merged;
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) throws IOException {
        manager.disconnect(id);
        configStore.delete(id);
        return Map.of("status", "deleted");
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        if (!manager.isConnected(id)) {
            McpServerConfig config = configStore.get(id)
                    .orElseThrow(() -> GlobalExceptionHandler.notFound("MCP 服务器不存在: " + id));
            manager.connect(config); // 未连接先连接再测
        }
        return manager.test(id);
    }

    @GetMapping("/{id}/tools")
    public List<Map<String, Object>> tools(@PathVariable String id) {
        return manager.tools(id);
    }

    private Map<String, Object> withStatus(McpServerConfig config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", config.id());
        item.put("name", config.name());
        item.put("transport", config.transport());
        item.put("command", config.command());
        item.put("args", config.args());
        item.put("env", config.env());
        item.put("url", config.url());
        item.put("enabled", config.enabled());
        item.put("status", manager.status(config));
        return item;
    }
}
