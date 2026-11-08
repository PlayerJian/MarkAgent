package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 计划树快照 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanView(List<PlanStep> steps, String currentStepId, String updatedAt) {
    public static PlanView empty() {
        return new PlanView(List.of(), null, null);
    }
}
