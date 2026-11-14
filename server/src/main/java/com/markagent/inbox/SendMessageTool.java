package com.markagent.inbox;

import com.markagent.agent.model.TaskIdContext;
import com.markagent.agent.model.TaskUserContext;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
import com.markagent.common.sse.SseEventType;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.store.SessionStore;
import com.markagent.store.TaskStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 跨会话消息工具（send_message）：把消息投递到目标会话的信箱（{@link SessionInboxService}）。
 * <ul>
 *   <li>消息落盘后目标会话下次任务启动时经 PromptAssembler 注入首条 user 消息</li>
 *   <li>目标会话当前有 RUNNING 任务时，同时向该任务广播 SseEventType.SESSION_MSG 实时推送；
 *       找不到运行中任务也正常返回（消息留在信箱）</li>
 *   <li>归属校验：默认仅允许发往当前用户自己的会话（{@code markagent.inbox.cross-user=true}
 *       时允许发往其他用户的会话并记日志）；会话不存在返回错误</li>
 * </ul>
 * 仅限任务内调用（依赖 TaskIdContext/TaskUserContext，由 MarkAgentToolRegistrar 注入）。
 */
@Component
public class SendMessageTool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);
    private static final int CONTENT_MAX_CHARS = 2000;
    private static final int BROADCAST_MAX_CHARS = 500;
    private static final String LOCAL_USER = "local-user";

    private final SessionInboxService inbox;
    private final SessionStore sessionStore;
    private final TaskStore taskStore;
    private final SseEmitterRegistry registry;
    private final Path storageRoot;
    private final boolean crossUser;

    public SendMessageTool(SessionInboxService inbox, SessionStore sessionStore, TaskStore taskStore,
            SseEmitterRegistry registry, Path storageRoot,
            @Value("${markagent.inbox.cross-user:true}") boolean crossUser) {
        this.inbox = inbox;
        this.sessionStore = sessionStore;
        this.taskStore = taskStore;
        this.registry = registry;
        this.storageRoot = storageRoot;
        this.crossUser = crossUser;
    }

    @Tool(name = "send_message",
            description = "向另一个会话投递一条跨会话消息（会话信箱）。消息写入目标会话的信箱，目标会话下一次任务启动时会在首条消息中收到；若目标会话当前正有任务运行则实时推送。参数 target_session_id 为目标会话 id（s_ 开头），content 为消息正文（最长 2000 字符，超出自动截断）。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String sendMessage(
            @ToolParam(name = "target_session_id", required = true,
                    description = "目标会话 id（s_ 开头）") String targetSessionId,
            @ToolParam(name = "content", required = true,
                    description = "消息正文，最长 2000 字符（超出自动截断）") String content) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文（send_message 只能在任务内调用）";
        }
        String currentUser = TaskUserContext.get();
        if (currentUser == null) {
            // 本地无登录用户场景：与 JsonFileSessionStore 的 userId 默认目录一致
            currentUser = LOCAL_USER;
        }
        String target = targetSessionId == null ? "" : targetSessionId.trim();
        if (target.isBlank()) {
            return "错误：target_session_id 不能为空";
        }
        if (content == null || content.isBlank()) {
            return "错误：消息内容不能为空";
        }
        String text = content.length() > CONTENT_MAX_CHARS
                ? content.substring(0, CONTENT_MAX_CHARS) : content;

        String targetUser = resolveTargetUser(currentUser, target);
        if (targetUser == null) {
            return "错误：会话 " + target + " 不存在或不属于当前用户";
        }

        long seq = inbox.enqueue(targetUser, target,
                new InboxMessage(0, currentUser, taskId, text, Instant.now()));
        if (seq <= 0) {
            return "错误：消息投递失败（信箱写入异常），请稍后重试";
        }

        broadcastIfRunning(targetUser, target, taskId, text);

        return "消息已投递到会话 " + target + "（seq " + seq + "）。目标会话下次任务启动时会收到该消息。";
    }

    /**
     * 解析目标会话的归属用户：当前用户直接命中返回当前用户；未命中且 cross-user=true 时
     * 扫描 data/sessions/&lt;任意用户&gt;/{targetSessionId}.json 找归属用户（找到记日志）；否则返回 null。
     */
    private String resolveTargetUser(String currentUser, String targetSessionId) {
        if (sessionStore.get(currentUser, targetSessionId).isPresent()) {
            return currentUser;
        }
        if (!crossUser) {
            return null;
        }
        Path sessionsDir = storageRoot.resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) {
            return null;
        }
        try (Stream<Path> userDirs = Files.list(sessionsDir)) {
            for (Path userDir : userDirs.toList()) {
                if (Files.isRegularFile(userDir.resolve(targetSessionId + ".json"))) {
                    String owner = userDir.getFileName().toString();
                    log.info("跨用户消息：user={} 向会话 {}/{} 投递（markagent.inbox.cross-user=true）",
                            currentUser, owner, targetSessionId);
                    return owner;
                }
            }
        } catch (IOException e) {
            log.warn("跨用户会话归属扫描失败: {}", e.getMessage());
            return null;
        }
        return null;
    }

    /** 目标会话存在 RUNNING 任务时广播 SESSION_MSG 实时推送（找不到也正常返回） */
    private void broadcastIfRunning(String targetUser, String targetSessionId, String fromTaskId, String text) {
        List<TaskRecord> tasks = taskStore.list(targetUser, TaskStatus.RUNNING);
        if (tasks.isEmpty()) {
            return;
        }
        String brief = text.length() > BROADCAST_MAX_CHARS
                ? text.substring(0, BROADCAST_MAX_CHARS) + "…" : text;
        for (TaskRecord task : tasks) {
            if (task.id() != null && targetSessionId.equals(task.sessionId())) {
                registry.broadcast(task.id(), SseEventType.SESSION_MSG,
                        Map.of("fromTaskId", fromTaskId, "sessionId", targetSessionId, "content", brief));
            }
        }
    }
}
