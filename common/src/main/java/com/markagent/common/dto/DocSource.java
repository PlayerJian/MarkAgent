package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 问答检索来源（ChatRequest.source，与普通任务互斥）：
 * libraryId = 文档库问答；uploadId = 单文件问答（先 POST /docs/temp-uploads 拿 ID）。
 * 两者互斥：必须且只能提供一个，服务端强校验。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocSource(String libraryId, String uploadId) {
}
