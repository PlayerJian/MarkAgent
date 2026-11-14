package com.markagent.api;

import com.markagent.audit.AuditLogService;
import com.markagent.audit.AuditQueryResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** 审计日志查询端点：userId 固定取当前登录用户（JWT principal），不做跨用户查询 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogService auditLogService;

    public AuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 工具调用审计查询：GET /api/v1/audit?taskId=&toolName=&from=&to=&limit=&offset=
     * from/to 为 ISO-8601 Instant 字符串（如 2026-11-12T10:00:00Z），全部参数可选（缺省最近 7 天到今天）。
     */
    @GetMapping
    public AuditQueryResult query(
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "toolName", required = false) String toolName,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            Authentication authentication) {
        return auditLogService.query(authentication.getName(), taskId, toolName,
                parseInstant(from, "from"), parseInstant(to, "to"), limit, offset);
    }

    /** ISO-8601 Instant 解析：空值返回 null（不限），非法格式 400 */
    private static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw GlobalExceptionHandler.badRequest(name + " 需为 ISO-8601 时间（如 2026-11-12T10:00:00Z）");
        }
    }
}
