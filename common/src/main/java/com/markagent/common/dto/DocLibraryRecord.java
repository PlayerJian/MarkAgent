package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 文档库记录（data/docs/libraries/{id}.json 的 schema） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocLibraryRecord(
        String id,
        String userId,
        String name,
        String createdAt
) {
}
