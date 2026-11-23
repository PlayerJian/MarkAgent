package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 文档记录（data/docs/documents/{id}.json 的 schema）。
 * 状态机：PARSING → READY | FAILED；FAILED 可 retry 重置为 PARSING。
 * parseStage：PARSING 期间的细粒度阶段（queued=线程池排队；提交后为 MinerU
 * 包装服务协议阶段 submitting/waiting-file/pending/running），终态置 null。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocRecord(
        String id,
        String libraryId,
        String userId,
        String fileName,
        DocStatus status,
        String parseStage,
        int pageCount,
        String error,
        String createdAt,
        String updatedAt
) {
}
