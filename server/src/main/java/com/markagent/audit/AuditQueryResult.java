package com.markagent.audit;

import java.util.List;

/** 审计查询结果：total = 过滤后总条数（分页前），records = 本页记录（createdAt 降序） */
public record AuditQueryResult(long total, List<AuditRecord> records) {
}
