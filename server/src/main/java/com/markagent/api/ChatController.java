package com.markagent.api;

import com.markagent.agent.AgentChatService;
import com.markagent.common.dto.ChatAccepted;
import com.markagent.common.dto.ChatRequest;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.sse.SseEvent;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.store.TaskStore;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天与任务流端点。
 * POST /chat 受理任务（立即返回 taskId）→ GET /tasks/{id}/stream?afterSeq= 订阅 SSE（带断线回放）。
 * userId 取自 JWT principal。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final AgentChatService chatService;
    private final TaskStore taskStore;
    private final SseEmitterRegistry registry;

    public ChatController(AgentChatService chatService, TaskStore taskStore, SseEmitterRegistry registry) {
        this.chatService = chatService;
        this.taskStore = taskStore;
        this.registry = registry;
    }

    /** 受理聊天任务：立即返回 {taskId, sessionId} */
    @PostMapping("/chat")
    public ChatAccepted chat(@RequestBody ChatRequest request, Authentication authentication) {
        return chatService.start(request, authentication.getName());
    }

    /**
     * SSE 事件流：id 即 seq（回放锚点），event 即事件类型，data 为事件 JSON。
     * afterSeq：断线重连时传入最后收到的 seq，先回放缺失事件再续实时流（游标判重，无重复无丢失）。
     */
    @GetMapping(value = "/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String taskId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq,
            Authentication authentication) {
        requireOwned(taskId, authentication);
        return registry.addAndReplay(taskId, SseEmitterRegistry.newEmitter(), afterSeq);
    }

    /** 当前用户任务列表 */
    @GetMapping("/tasks")
    public List<TaskRecord> tasks(Authentication authentication) {
        return taskStore.list(authentication.getName(), null);
    }

    /** 任务事件全量回放（历史记录查看用）：data/events/{taskId}.jsonl 按 seq 升序 */
    @GetMapping("/tasks/{id}/events")
    public List<SseEvent> events(@PathVariable("id") String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        return registry.readAll(taskId);
    }

    /** 任务详情（含状态与检查点列表） */
    @GetMapping("/tasks/{id}")
    public TaskRecord task(@PathVariable("id") String taskId, Authentication authentication) {
        return requireOwned(taskId, authentication);
    }

    /** 暂停：步骤边界生效，写检查点，状态 PAUSED */
    @PostMapping("/tasks/{id}/pause")
    public TaskRecord pause(@PathVariable("id") String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        chatService.pause(taskId);
        return task(taskId, authentication);
    }

    /** 恢复：同 (userId, sessionId) 续跑（AgentScope 状态自动加载） */
    @PostMapping("/tasks/{id}/resume")
    public TaskRecord resume(@PathVariable("id") String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        chatService.resume(taskId);
        return task(taskId, authentication);
    }

    /** 停止：置 CANCELLED */
    @PostMapping("/tasks/{id}/stop")
    public TaskRecord stop(@PathVariable("id") String taskId, Authentication authentication) {
        requireOwned(taskId, authentication);
        chatService.stop(taskId);
        return task(taskId, authentication);
    }

    /** 任务归属校验：不存在或非本人一律 404（不泄露他人任务存在性） */
    private TaskRecord requireOwned(String taskId, Authentication authentication) {
        TaskRecord task = taskStore.get(taskId).orElseThrow(
                () -> GlobalExceptionHandler.notFound("任务 " + taskId));
        if (!task.userId().equals(authentication.getName())) {
            throw GlobalExceptionHandler.notFound("任务 " + taskId);
        }
        return task;
    }
}
