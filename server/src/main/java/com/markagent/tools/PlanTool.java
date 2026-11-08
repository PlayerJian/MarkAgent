package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import com.markagent.common.dto.PlanStep;
import com.markagent.common.dto.PlanView;
import com.markagent.common.dto.StepStatus;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.sse.SseEventType;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.store.TaskStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 计划工具（对齐 AgentScope Python 版 plan_write / plan_mark_done 语义，Java 2.0.4 无内置实现，自研）。
 * <ul>
 *   <li>plan_write：把计划步骤写入工作区 plans/PLAN.md，更新任务记录并广播 PLAN_UPDATE</li>
 *   <li>plan_mark_done：标记步骤完成，推进 currentStepId，广播 PLAN_UPDATE</li>
 * </ul>
 */
@Component
public class PlanTool {

    private static final Logger log = LoggerFactory.getLogger(PlanTool.class);

    private final SseEmitterRegistry registry;
    private final TaskStore taskStore;
    private final Path workspaceRoot;

    public PlanTool(SseEmitterRegistry registry, TaskStore taskStore,
            @Value("${markagent.storage.root:./data}") String storageRoot) {
        this.registry = registry;
        this.taskStore = taskStore;
        this.workspaceRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("workspace");
    }

    @Tool(name = "plan_write", description = "将任务执行计划写入计划文件并同步给前端计划视图。"
            + "steps 为计划步骤数组，每项含 title（步骤标题）与 detail（可选，步骤详情）。"
            + "执行复杂任务前应先调用本工具制定计划。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String planWrite(@ToolParam(name = "steps", required = true,
            description = "计划步骤数组，每项为 {title: 步骤标题, detail: 可选步骤详情}，"
                    + "如 [{\"title\":\"调研需求\",\"detail\":\"收集需求文档\"}]")
            List<Map<String, Object>> steps) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文";
        }
        try {
            if (steps == null || steps.isEmpty()) {
                return "错误：计划步骤不能为空";
            }
            List<PlanStep> planSteps = new ArrayList<>();
            int index = 1;
            for (Map<String, Object> item : steps) {
                String title = String.valueOf(item.getOrDefault("title", "步骤 " + index));
                String detail = String.valueOf(item.getOrDefault("detail", ""));
                planSteps.add(new PlanStep("step-" + index, title, StepStatus.PENDING, detail));
                index++;
            }

            // 写工作区 plans/PLAN.md
            writePlanMd(taskId, planSteps);

            updatePlan(taskId, new PlanView(planSteps, null, Instant.now().toString()));
            return "已写入计划（" + planSteps.size() + " 步）到 plans/PLAN.md";
        } catch (Exception e) {
            log.warn("plan_write 失败 task={}: {}", taskId, e.getMessage());
            return "错误：计划写入失败 - " + e.getMessage();
        }
    }

    @Tool(name = "plan_mark_done", description = "标记计划中的某一步为已完成，并把下一步标记为进行中。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String planMarkDone(@ToolParam(name = "step_id", required = true,
            description = "步骤 ID，如 step-1") String stepId) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文";
        }
        Optional<TaskRecord> recordOpt = taskStore.get(taskId);
        if (recordOpt.isEmpty() || recordOpt.get().plan() == null) {
            return "错误：当前任务尚无计划，请先调用 plan_write";
        }
        TaskRecord record = recordOpt.get();
        List<PlanStep> steps = new ArrayList<>(record.plan().steps());
        String current = null;
        boolean found = false;
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            if (step.id().equals(stepId)) {
                steps.set(i, step.withStatus(StepStatus.DONE));
                found = true;
                if (i + 1 < steps.size()) {
                    steps.set(i + 1, steps.get(i + 1).withStatus(StepStatus.RUNNING));
                    current = steps.get(i + 1).id();
                }
                break;
            }
        }
        if (!found) {
            return "错误：找不到步骤 " + stepId;
        }
        try {
            writePlanMd(taskId, steps);
        } catch (Exception e) {
            log.warn("PLAN.md 回写失败 task={}: {}", taskId, e.getMessage());
        }
        updatePlan(taskId, new PlanView(steps, current, Instant.now().toString()));
        return "已标记 " + stepId + " 完成" + (current != null ? "，下一步：" + current : "（全部完成）");
    }

    /** 按步骤状态渲染 plans/PLAN.md（DONE 打勾） */
    private void writePlanMd(String taskId, List<PlanStep> steps) throws Exception {
        Path planFile = workspaceRoot.resolve(taskId).resolve("plans").resolve("PLAN.md");
        Files.createDirectories(planFile.getParent());
        StringBuilder markdown = new StringBuilder("# 任务计划\n\n");
        for (PlanStep step : steps) {
            markdown.append(step.status() == StepStatus.DONE ? "- [x] " : "- [ ] ")
                    .append(step.title());
            if (step.detail() != null && !step.detail().isBlank()) {
                markdown.append(" — ").append(step.detail());
            }
            markdown.append('\n');
        }
        Files.writeString(planFile, markdown.toString(), StandardCharsets.UTF_8);
    }

    /** 更新任务记录 + 广播 PLAN_UPDATE */
    private void updatePlan(String taskId, PlanView plan) {
        taskStore.get(taskId).ifPresent(record -> taskStore.save(new TaskRecord(
                record.id(), record.sessionId(), record.userId(), record.goal(), record.status(),
                plan, record.checkpoints(), record.options(), record.createdAt(),
                record.updatedAt(), record.errorMessage())));
        registry.broadcast(taskId, SseEventType.PLAN_UPDATE, Map.of(
                "steps", plan.steps(),
                "currentStepId", plan.currentStepId() == null ? "" : plan.currentStepId(),
                "updatedAt", plan.updatedAt()));
    }
}
