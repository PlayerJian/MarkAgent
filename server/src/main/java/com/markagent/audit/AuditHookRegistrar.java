package com.markagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.dto.TaskRecord;
import com.markagent.store.TaskStore;
import com.markagent.tools.MarkAgentToolRegistrar;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 审计钩子装配：把 {@link MarkAgentToolRegistrar} 的工具调用捕获流接进 {@link AuditLogService}。
 * <p>
 * 捕获点选在注册器 invoke 层而非 AgentChatService 事件层，因为它是唯一能同时拿到
 * 「入参 + 结果文本 + 耗时」的位置；worker（经 WorkerAgentFactory 同样走注册器）的工具
 * 调用也会被审计。MCP 工具不经注册器，v1 不覆盖（见 McpServerManager 注释）。
 * <p>
 * ok 判定采用项目内统一约定：结果文本以「错误：」开头视为失败（工具方法自身返回
 * 错误文本与注册器异常兜底均遵循该格式）。
 */
@Component
public class AuditHookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AuditHookRegistrar.class);
    /** 业务失败前缀约定（工具方法内错误文本 + 注册器异常兜底的统一格式） */
    private static final String ERROR_PREFIX = "错误：";

    private final AuditLogService auditLog;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;

    public AuditHookRegistrar(AuditLogService auditLog, TaskStore taskStore, ObjectMapper mapper) {
        this.auditLog = auditLog;
        this.taskStore = taskStore;
        this.mapper = mapper;
    }

    @PostConstruct
    void registerHook() {
        MarkAgentToolRegistrar.setToolCallCaptureListener(this::capture);
        log.info("工具调用审计钩子已注册");
    }

    /** 捕获回调：任何异常仅记日志，绝不影响工具执行主流程 */
    private void capture(String taskId, String userId, String toolName, String callId,
            Map<String, Object> args, String resultText, long durationMs) {
        try {
            String sessionId = taskId == null || taskId.isBlank() ? ""
                    : taskStore.get(taskId).map(TaskRecord::sessionId).orElse("");
            boolean ok = resultText == null || !resultText.startsWith(ERROR_PREFIX);
            auditLog.recordToolCall(new AuditRecord(
                    taskId, sessionId, userId, callId, toolName,
                    serialize(args), ok, resultText, durationMs, Instant.now()));
        } catch (Exception e) {
            log.warn("审计记录构造失败 tool={} task={}: {}", toolName, taskId, e.getMessage());
        }
    }

    /** 入参序列化：模型侧 JSON 派生的 Map 正常可序列化；异常时降级为 toString */
    private String serialize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }
}
