package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 计划步骤（plan_update 事件的载荷单元，也是任务记录中 plan 的持久化单元） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStep(String id, String title, StepStatus status, String detail) {
    public PlanStep withStatus(StepStatus newStatus) {
        return new PlanStep(id, title, newStatus, detail);
    }

    public PlanStep withDetail(String newDetail) {
        return new PlanStep(id, title, status, newDetail);
    }
}
