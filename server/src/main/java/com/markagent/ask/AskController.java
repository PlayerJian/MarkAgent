package com.markagent.ask;

import com.markagent.common.dto.TaskRecord;
import com.markagent.store.TaskStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.markagent.api.GlobalExceptionHandler.badRequest;
import static com.markagent.api.GlobalExceptionHandler.notFound;

/**
 * 人工审批 REST 端点：
 * <ul>
 *   <li>POST /api/v1/tasks/{taskId}/asks/{callId}/respond：以 callId 应答待决审批（body {answer}），
 *       写入用户选择并唤醒 ask_user 工具线程。</li>
 *   <li>GET /api/v1/tasks/{taskId}/asks：列出该任务下仍待决的审批（前端查询/轮询用）。</li>
 * </ul>
 * 鉴权与归属校验同 {@link com.markagent.api.ChatController}：JWT 认证（SecurityConfig 的
 * anyRequest().authenticated() 已覆盖本路径）+ 任务归属校验（不存在或非本人一律 404）。
 */
@RestController
@RequestMapping("/api/v1")
public class AskController {

    /** 应答请求体：answer 为所选项原文 */
    public record AskRespondRequest(String answer) {
    }

    /** 应答结果 */
    public record AskResponded(String callId, boolean ok) {
    }

    private final TaskStore taskStore;
    private final AskRegistry asks;

    public AskController(TaskStore taskStore, AskRegistry asks) {
        this.taskStore = taskStore;
        this.asks = asks;
    }

    /** 应答待决审批：写入用户选择并唤醒 ask_user 工具线程 */
    @PostMapping("/tasks/{taskId}/asks/{callId}/respond")
    public AskResponded respond(@PathVariable String taskId, @PathVariable String callId,
            @RequestBody AskRespondRequest body, Authentication authentication) {
        requireOwned(taskId, authentication);
        if (body == null || body.answer() == null || body.answer().isBlank()) {
            throw badRequest("answer 不能为空");
        }
        PendingAsk ask = asks.get(callId);
        if (ask == null) {
            throw notFound("审批 " + callId);
        }
        if (!ask.taskId().equals(taskId)) {
            // callId 属于其他任务：与不存在同样处理，不泄露存在性
            throw notFound("审批 " + callId);
        }
        if (!asks.answer(callId, body.answer())) {
            // 登记仍在但应答未生效：已应答或已取消（幂等保护）
            throw badRequest("审批 " + callId + " 已应答或已取消");
        }
        return new AskResponded(callId, true);
    }

    /** 列出任务下仍待决的审批（callId/question/options/createdAt） */
    @GetMapping("/tasks/{taskId}/asks")
    public List<Map<String, Object>> pending(@PathVariable String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        return asks.pendingOf(taskId);
    }

    /** 任务归属校验：不存在或非本人一律 404（不泄露他人任务存在性），同 ChatController.requireOwned */
    private TaskRecord requireOwned(String taskId, Authentication authentication) {
        TaskRecord task = taskStore.get(taskId).orElseThrow(
                () -> notFound("任务 " + taskId));
        if (!task.userId().equals(authentication.getName())) {
            throw notFound("任务 " + taskId);
        }
        return task;
    }
}
