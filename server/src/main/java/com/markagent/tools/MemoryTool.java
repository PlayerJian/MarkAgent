package com.markagent.tools;

import com.markagent.agent.model.TaskUserContext;
import com.markagent.common.dto.MemorySearchResult;
import com.markagent.memory.MemoryService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆工具：agent 主动写入重要事实 / 检索历史记忆。
 * userId 由 MarkAgentToolRegistrar 从 RuntimeContext 注入工具执行线程（TaskUserContext）。
 */
@Component
public class MemoryTool {

    private final MemoryService memoryService;

    public MemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "memory_save", description = "把需要长期记住的重要信息写入用户长期记忆（跨会话保留）。"
            + "适用：用户偏好、重要结论、事实约定等。不要写入临时性的中间结果。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String save(@ToolParam(name = "content", required = true,
            description = "要记住的内容（简洁、自包含的事实陈述）") String content) {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        try {
            return memoryService.save(userId, content, "agent");
        } catch (Exception e) {
            return "错误：记忆保存失败 - " + e.getMessage();
        }
    }

    @Tool(name = "memory_search", description = "检索用户长期记忆中的相关内容（历史偏好、事实、约定）。"
            + "处理任务前可先检索是否有相关记忆。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String search(@ToolParam(name = "query", required = true,
            description = "检索查询，如「用户偏好的报告格式」") String query) {
        String userId = TaskUserContext.get();
        if (userId == null) {
            return "错误：无用户上下文";
        }
        try {
            List<MemorySearchResult> results = memoryService.search(userId, query, 3);
            if (results.isEmpty()) {
                return "（无相关记忆）";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                MemorySearchResult r = results.get(i);
                sb.append(i + 1).append(") [").append(r.source()).append("] ")
                        .append(r.content()).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "错误：记忆检索失败 - " + e.getMessage();
        }
    }
}
