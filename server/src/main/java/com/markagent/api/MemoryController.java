package com.markagent.api;

import com.markagent.common.dto.ContextUsage;
import com.markagent.common.dto.MemorySearchResult;
import com.markagent.context.TokenBudgetService;
import com.markagent.memory.MemoryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 长期记忆 + 上下文用量（挂会话维度，userId 取 JWT principal） */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class MemoryController {

    private final MemoryService memoryService;
    private final TokenBudgetService tokenBudgetService;

    public MemoryController(MemoryService memoryService, TokenBudgetService tokenBudgetService) {
        this.memoryService = memoryService;
        this.tokenBudgetService = tokenBudgetService;
    }

    /** 长期记忆全文 */
    @GetMapping("/memory")
    public Map<String, Object> memory(Authentication authentication) {
        return Map.of("content", memoryService.getMemory(authentication.getName()));
    }

    /** 记忆检索（向量档/关键词档自动降级） */
    @PostMapping("/memory/search")
    public List<MemorySearchResult> search(@RequestBody Map<String, Object> body, Authentication authentication)
            throws Exception {
        String query = String.valueOf(body.getOrDefault("query", ""));
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 5;
        return memoryService.search(authentication.getName(), query, limit);
    }

    /** 记忆保存 */
    @PostMapping("/memory/save")
    public Map<String, String> save(@RequestBody Map<String, Object> body,
            @PathVariable String sessionId, Authentication authentication) throws IOException {
        String content = String.valueOf(body.getOrDefault("content", ""));
        memoryService.save(authentication.getName(), content, sessionId);
        return Map.of("status", "saved");
    }

    /** 上下文用量（AgentScope 持久化状态估算） */
    @GetMapping("/context")
    public ContextUsage context(@PathVariable String sessionId, Authentication authentication) {
        return tokenBudgetService.usage(authentication.getName(), sessionId);
    }
}
