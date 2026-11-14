package com.markagent.agent;

import com.markagent.agent.model.TaskIdContext;
import com.markagent.agent.model.TaskModelContext;
import com.markagent.agent.model.TaskThinkingContext;
import com.markagent.common.dto.ChatAccepted;
import com.markagent.common.dto.ChatMessage;
import com.markagent.common.dto.ChatRequest;
import com.markagent.common.dto.CheckpointRecord;
import com.markagent.common.dto.TaskOptions;
import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;
import com.markagent.common.dto.SessionRecord;
import com.markagent.common.sse.SseEventType;
import com.markagent.ask.AskRegistry;
import com.markagent.inbox.InboxMessage;
import com.markagent.inbox.SessionInboxService;
import com.markagent.compact.AutoCompactService;
import com.markagent.config.MarkAgentProperties;
import com.markagent.context.TokenBudgetService;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.prompt.PromptAssembler;
import com.markagent.sessionmemory.SessionMemoryService;
import com.markagent.store.CheckpointStore;
import com.markagent.store.SessionStore;
import com.markagent.store.TaskStore;
import com.markagent.util.Ids;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务编排核心。
 * <ul>
 *   <li>POST /chat 受理 → 建任务记录 → 虚拟线程跑 ReActAgent.streamEvents → 新事件体系映射为统一 SSE 事件</li>
 *   <li>断点恢复：agent 自带 stateStore（JsonFileAgentStateStore，按 (userId,sessionId) 持久化 AgentState），
 *       pause 用 interrupt(rt) 在步骤边界停 → 写 CheckpointRecord；resume 以同 (userId,sessionId) 重新
 *       streamEvents 即自动加载状态继续。服务重启后依然可恢复。</li>
 *   <li>stop 用 interrupt 后置 CANCELLED。</li>
 * </ul>
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** 无认证阶段（M3c 换 JWT principal）的占位用户 */
    public static final String LOCAL_USER = "local-user";

    private final ReActAgent agent;
    private final TaskStore taskStore;
    private final CheckpointStore checkpointStore;
    private final SessionStore sessionStore;
    private final SseEmitterRegistry registry;
    private final PromptAssembler promptAssembler;
    private final com.markagent.memory.MemoryService memoryService;
    private final com.markagent.tools.TeamTool teamTool;
    private final TokenBudgetService tokenBudgetService;
    private final AutoCompactService autoCompact;
    private final SessionMemoryService sessionMemory;
    private final AskRegistry askRegistry;
    private final SessionInboxService inbox;
    private final AtomicInteger runningTasks;
    private final MarkAgentProperties properties;

    /** taskId → RuntimeContext（pause/resume/stop 控制面用） */
    private final Map<String, RuntimeContext> contexts = new ConcurrentHashMap<>();
    /** taskId → 流订阅（stop 时 dispose） */
    private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();
    /** taskId → 暂停标记（interrupt 后流正常完成，据此区分 PAUSED/COMPLETED） */
    private final Map<String, AtomicBoolean> pausedFlags = new ConcurrentHashMap<>();
    /** taskId → 本次 run 的终止闩（pause 等待 agent 真正停稳后再落 PAUSED） */
    private final Map<String, CountDownLatch> runLatches = new ConcurrentHashMap<>();
    /** taskId → (toolCallId → 输出累积)（TOOL_END 时带 resultSummary） */
    private final Map<String, Map<String, StringBuilder>> toolBuffers = new ConcurrentHashMap<>();
    /** taskId → 已流式广播的正文（AgentResultEvent 全文与 TextBlockDeltaEvent 增量去重） */
    private final Map<String, StringBuilder> textBuffers = new ConcurrentHashMap<>();
    /** taskId → 本轮已结束的工具调用（终止态回填 assistant 消息用） */
    private final Map<String, List<ChatMessage.ToolCallRef>> taskToolCalls = new ConcurrentHashMap<>();

    public AgentChatService(ReActAgent agent, TaskStore taskStore, CheckpointStore checkpointStore,
            SessionStore sessionStore, SseEmitterRegistry registry,
            AtomicInteger runningTasks, MarkAgentProperties properties,
            com.markagent.memory.MemoryService memoryService,
            com.markagent.tools.TeamTool teamTool,
            PromptAssembler promptAssembler,
            TokenBudgetService tokenBudgetService,
            AutoCompactService autoCompact,
            SessionMemoryService sessionMemory,
            AskRegistry askRegistry,
            SessionInboxService inbox) {
        this.agent = agent;
        this.taskStore = taskStore;
        this.checkpointStore = checkpointStore;
        this.sessionStore = sessionStore;
        this.registry = registry;
        this.promptAssembler = promptAssembler;
        this.tokenBudgetService = tokenBudgetService;
        this.autoCompact = autoCompact;
        this.sessionMemory = sessionMemory;
        this.runningTasks = runningTasks;
        this.properties = properties;
        this.memoryService = memoryService;
        this.teamTool = teamTool;
        this.askRegistry = askRegistry;
        this.inbox = inbox;
        // 框架内部 fire-and-forget 订阅的错误会被 reactor 静默丢弃，落日志便于追查静默死亡
        Hooks.onErrorDropped(error -> log.error("Reactor 丢弃未处理错误: {}", error.toString(), error));
    }

    // ==================== 任务生命周期 ====================

    /** 受理任务：建记录 + 异步执行，立即返回 taskId */
    public ChatAccepted start(ChatRequest request, String userId) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? Ids.session() : request.sessionId();
        // 未指定模型时用 yml 的 markagent.llm.default-model（如 deepseek-flash）
        TaskOptions options = request.options() == null ? TaskOptions.defaults() : request.options();
        if (options.model() == null || options.model().isBlank()) {
            options = new TaskOptions(properties.llm().defaultModel(), options.planning(),
                    options.thinking(), options.workers());
        }
        String taskId = Ids.task();
        String now = Instant.now().toString();

        TaskRecord record = new TaskRecord(taskId, sessionId, userId, request.goal(),
                TaskStatus.RUNNING, null, List.of(), options, now, now, null);
        taskStore.save(record);

        // 会话记录（仅 UI 展示层；标题取首个任务描述前 40 字，后续任务只刷新 updatedAt 供列表排序）。
        // messages 回填：每条任务开始追加一条 user 消息（数据层补全，暂无 UI 消费方）
        ChatMessage userMessage = new ChatMessage(Ids.message(), "user", request.goal(), null, List.of(), now);
        sessionStore.get(userId, sessionId).ifPresentOrElse(
                existing -> {
                    List<ChatMessage> messages = new ArrayList<>();
                    if (existing.messages() != null) {
                        messages.addAll(existing.messages());
                    }
                    messages.add(userMessage);
                    sessionStore.save(new SessionRecord(existing.id(), existing.userId(),
                            existing.title(), messages, existing.createdAt(), now));
                },
                () -> {
                    String title = request.goal().length() > 40 ? request.goal().substring(0, 40) + "…" : request.goal();
                    sessionStore.save(new SessionRecord(sessionId, userId, title, List.of(userMessage), now, now));
                });

        runningTasks.incrementAndGet();

        Thread.ofVirtual().name("agent-task-" + taskId).start(() -> runTask(record, false));
        return new ChatAccepted(taskId, sessionId);
    }

    /** 执行（虚拟线程）。resume=true 时附「继续执行」指令，agent 从持久化状态续跑 */
    private void runTask(TaskRecord task, boolean resume) {
        // 任务级模型注入：AgentScope 不透传模型名，bridge 经 ThreadLocal 读取（虚拟线程每任务一线程）
        TaskModelContext.set(task.options().model());
        // 任务级思考开关：同上，经 ThreadLocal 注入 bridge（flash/pro 均为开关式思考模型）
        TaskThinkingContext.set(Boolean.TRUE.equals(task.options().thinking()));
        // 任务级 taskId：工具（plan_write/file_*）经 ThreadLocal 定位任务并广播事件
        TaskIdContext.set(task.id());
        broadcast(task.id(), SseEventType.META, Map.of(
                "sessionId", task.sessionId(),
                "userId", task.userId(),
                "model", task.options().model(),
                "planning", task.options().planning(),
                "thinking", task.options().thinking(),
                "workers", task.options().workers(),
                "resume", resume));
        try {
            RuntimeContext rt = RuntimeContext.builder()
                    .sessionId(task.sessionId())
                    .userId(task.userId())
                    .build();
            // taskId 经 RuntimeContext 传进工具执行线程（ToolExecutor 独立线程池，ThreadLocal 不可达）
            rt.put("taskId", task.id());
            contexts.put(task.id(), rt);
            pausedFlags.computeIfAbsent(task.id(), k -> new AtomicBoolean(false));

            List<Msg> msgs = new ArrayList<>();
            long inboxMaxSeq = 0;
            if (resume) {
                msgs.add(Msg.builderForRole(MsgRole.USER).textContent("请继续执行未完成的任务。").build());
            } else {
                // 动态上下文（技能清单 + 长期记忆索引 + 会话摘要）注入首条消息；技能正文经 skill_invoke 工具按需加载
                String assembled = promptAssembler.assemble(task.userId(), task.sessionId(), task.goal());
                String goal = assembled.isBlank() ? task.goal()
                        : assembled + "\n## 用户任务\n" + task.goal();
                // 跨会话信箱：投递到本会话的未读消息注入首条 user 消息（订阅成功后推进投递游标）
                List<InboxMessage> inboxMessages = inbox.pending(task.userId(), task.sessionId());
                if (!inboxMessages.isEmpty()) {
                    StringBuilder section = new StringBuilder("\n\n## 跨会话消息\n");
                    for (InboxMessage message : inboxMessages) {
                        section.append("- [来自任务 ").append(message.fromTaskId()).append("] ")
                                .append(message.content()).append('\n');
                        inboxMaxSeq = Math.max(inboxMaxSeq, message.seq());
                    }
                    goal = goal + section;
                }
                msgs.add(Msg.builderForRole(MsgRole.USER).textContent(goal).build());
            }

            CountDownLatch latch = new CountDownLatch(1);
            runLatches.put(task.id(), latch);
            Disposable subscription = agent.streamEvents(msgs, rt)
                    .doOnSubscribe(s -> log.info("STREAM subscribe task={} resume={}", task.id(), resume))
                    .doOnNext(event -> handleAgentEvent(task, event))
                    // 看门狗：静默悬挂（框架流既不结束也不报错）超时后强制转失败，可观测可恢复
                    .timeout(Duration.ofMinutes(properties.agent().streamTimeoutMinutes()),
                            Mono.<AgentEvent>error(new RuntimeException(
                                    "任务执行超时（" + properties.agent().streamTimeoutMinutes()
                                            + " 分钟无进展），已强制终止")))
                    .doOnError(error -> failTask(task, error))
                    .doOnComplete(() -> onStreamComplete(task))
                    .doOnCancel(() -> log.warn("STREAM cancelled task={} resume={}", task.id(), resume))
                    .doFinally(sig -> latch.countDown())
                    .subscribe();
            subscriptions.put(task.id(), subscription);
            // 信箱消息已进入首条 user 消息：推进投递游标（订阅成功建立后才标记；启动失败则下次重投）
            if (inboxMaxSeq > 0) {
                inbox.markDelivered(task.userId(), task.sessionId(), inboxMaxSeq);
            }

        } catch (Exception e) {
            failTask(task, e);
        } finally {
            TaskIdContext.clear();
            TaskModelContext.clear();
            TaskThinkingContext.clear();
        }
    }

    /** 流正常结束：interrupt 触发的收尾归 PAUSED/CANCELLED，否则 COMPLETED */
    private void onStreamComplete(TaskRecord task) {
        log.info("STREAM complete task={}", task.id());
        AtomicBoolean paused = pausedFlags.get(task.id());
        if (paused != null && paused.get()) {
            return; // pause() 已处理收尾（PAUSED 事件 + 落盘）
        }
        String finalText = textBuffers.getOrDefault(task.id(), new StringBuilder()).toString();
        // 1) 会话滚动记忆：阈值评估 + 后台虚拟线程记录（不阻塞终止广播）
        sessionMemory.evaluateAndRecord(task.userId(), task.sessionId(), task.goal(), finalText,
                currentUsedTokens(task));
        // 2) 会话历史回填（assistant 消息）
        backfillAssistantMessage(task, finalText);
        // 3) 上下文压缩（同步；内部先估计再决定，失败绝不影响终止流程）
        autoCompact.compactIfNeeded(task);
        // 4) DONE 广播放最后（终态事件会关闭订阅流）
        broadcast(task.id(), SseEventType.DONE, Map.of("status", "COMPLETED"));
        finish(task, TaskStatus.COMPLETED, null);
        // 任务完成 → 日记摘要（当日 YYYY-MM-DD.md）
        try {
            String goal = task.goal() == null ? "" : task.goal().strip();
            String summary = "任务完成 [" + task.sessionId() + "]：" + goal.substring(0, Math.min(goal.length(), 200));
            memoryService.recordDiary(task.userId(), summary);
        } catch (Exception e) {
            log.warn("日记落盘失败 task={}: {}", task.id(), e.getMessage());
        }
    }

    private void failTask(TaskRecord task, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        broadcast(task.id(), SseEventType.ERROR, Map.of(
                "code", "AGENT_EXECUTION_FAILED",
                "message", message,
                "retriable", true));
        // 会话历史回填（失败态同样落一条 assistant 消息）
        backfillAssistantMessage(task, textBuffers.getOrDefault(task.id(), new StringBuilder()).toString());
        // DONE 前压缩（先估计再决定；失败不影响终止流程）
        autoCompact.compactIfNeeded(task);
        broadcast(task.id(), SseEventType.DONE, Map.of("status", "FAILED"));
        finish(task, TaskStatus.FAILED, message);
    }

    /** 暂停：步骤边界生效（agent.interrupt），写检查点，状态 PAUSED */
    public void pause(String taskId) {
        TaskRecord task = requireTask(taskId);
        if (task.status() != TaskStatus.RUNNING) {
            return;
        }
        AtomicBoolean paused = pausedFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        paused.set(true);

        // 先取消待决人工审批再中断：被取消的 ask_user 工具线程立即返回「已取消」，不悬挂等超时
        askRegistry.cancelPending(taskId);

        RuntimeContext rt = contexts.get(taskId);
        if (rt != null) {
            agent.interrupt(rt);
        }
        // 联动收尾在跑的 worker（否则工具线程要干等 10 分钟 latch 超时）
        teamTool.interruptTask(taskId);

        // 等当前 run 真正终止（interrupt 收尾 + 状态落盘完成），避免 resume 读到半截状态
        CountDownLatch latch = runLatches.get(taskId);
        if (latch != null) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    log.warn("暂停等待超时 task={}，强制 dispose 订阅", taskId);
                    Disposable subscription = subscriptions.get(taskId);
                    if (subscription != null) {
                        subscription.dispose();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 检查点：stateKey 指向 AgentScope 持久化状态（resume 依据）
        long seq = registry.nextSeq(taskId);
        CheckpointRecord checkpoint = new CheckpointRecord(seq, Instant.now().toString(),
                0, "paused", task.userId() + "/" + task.sessionId());
        checkpointStore.save(taskId, checkpoint);
        List<CheckpointRecord> checkpoints = new ArrayList<>(task.checkpoints());
        checkpoints.add(checkpoint);

        broadcast(taskId, SseEventType.CHECKPOINT, Map.of(
                "checkpointId", seq, "stateKey", checkpoint.stateKey()));
        broadcast(taskId, SseEventType.PAUSED, Map.of("checkpointId", seq));
        taskStore.save(withStatus(task, TaskStatus.PAUSED, checkpoints));
        // 检查点落盘后：暂停也是终止态压缩点（先估计再决定；COMPACT 事件落 jsonl，resume 回放可见）
        autoCompact.compactIfNeeded(task);
    }

    /** 恢复：同 (userId, sessionId) 重新 streamEvents，agent 自动加载持久化状态续跑 */
    public void resume(String taskId) {
        TaskRecord task = requireTask(taskId);
        if (task.status() != TaskStatus.PAUSED) {
            return;
        }
        // 复位暂停标记：否则续跑 run 正常结束时 onStreamComplete 会误走「pause 已收尾」分支，
        // 不广播 DONE、不落 COMPLETED，任务永远卡 RUNNING
        pausedFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(false)).set(false);
        taskStore.save(withStatus(task, TaskStatus.RUNNING, task.checkpoints()));
        runningTasks.incrementAndGet();
        Thread.ofVirtual().name("agent-task-" + taskId + "-resume").start(() -> runTask(task, true));
    }

    /** 停止：interrupt + dispose + CANCELLED */
    public void stop(String taskId) {
        TaskRecord task = requireTask(taskId);
        if (task.status() == TaskStatus.COMPLETED || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED) {
            return;
        }
        pausedFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(false)).set(true);
        // 先取消待决人工审批再中断（同 pause 路径：ask_user 工具线程立即返回，不悬挂）
        askRegistry.cancelPending(taskId);
        RuntimeContext rt = contexts.get(taskId);
        if (rt != null) {
            agent.interrupt(rt);
        }
        teamTool.interruptTask(taskId);
        Disposable subscription = subscriptions.remove(taskId);
        if (subscription != null) {
            subscription.dispose();
        }
        broadcast(taskId, SseEventType.DONE, Map.of("status", "CANCELLED"));
        finish(task, TaskStatus.CANCELLED, null);
    }

    private void finish(TaskRecord task, TaskStatus status, String errorMessage) {
        taskStore.save(withStatus(task, status, task.checkpoints(), errorMessage));
        contexts.remove(task.id());
        subscriptions.remove(task.id());
        toolBuffers.remove(task.id());
        textBuffers.remove(task.id());
        taskToolCalls.remove(task.id());
        runLatches.remove(task.id());
        autoCompact.cleanup(task.id());
        teamTool.cleanup(task.id());
        askRegistry.removeByTask(task.id());
        runningTasks.decrementAndGet();
    }

    /** 终止态回填：assistant 消息（流式正文截断 1000 字符 + 本轮工具调用列表），保留既有 title/createdAt/updatedAt 语义 */
    private void backfillAssistantMessage(TaskRecord task, String finalText) {
        try {
            String content = truncate(finalText == null ? "" : finalText, 1000);
            List<ChatMessage.ToolCallRef> calls = taskToolCalls.getOrDefault(task.id(), List.of());
            if (content.isBlank() && calls.isEmpty()) {
                return;
            }
            ChatMessage message = new ChatMessage(Ids.message(), "assistant", content, null, calls,
                    Instant.now().toString());
            sessionStore.get(task.userId(), task.sessionId()).ifPresent(existing -> {
                List<ChatMessage> messages = new ArrayList<>();
                if (existing.messages() != null) {
                    messages.addAll(existing.messages());
                }
                messages.add(message);
                sessionStore.save(new SessionRecord(existing.id(), existing.userId(),
                        existing.title(), messages, existing.createdAt(), existing.updatedAt()));
            });
        } catch (Exception e) {
            log.warn("会话历史回填失败 task={}: {}", task.id(), e.getMessage());
        }
    }

    /** 当前会话上下文用量（会话记忆阈值评估用） */
    private long currentUsedTokens(TaskRecord task) {
        return tokenBudgetService.usage(task.userId(), task.sessionId()).usedTokens();
    }

    // ==================== AgentScope 新事件体系 → 统一 SSE ====================

    private void handleAgentEvent(TaskRecord task, AgentEvent event) {
        try {
            // 非增量事件打 DEBUG 轨迹（含未映射生命周期事件，如 MODEL_CALL_END）：排查静默问题用
            if (!(event instanceof TextBlockDeltaEvent) && !(event instanceof ThinkingBlockDeltaEvent)) {
                log.debug("EVENT task={} type={} class={}", task.id(), event.getType(),
                        event.getClass().getSimpleName());
            }
            if (event instanceof TextBlockDeltaEvent text) {
                textBuffer(task.id()).append(text.getDelta());
                broadcast(task.id(), SseEventType.TEXT, Map.of(
                        "messageId", text.getReplyId(), "delta", text.getDelta()));
            } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
                broadcast(task.id(), SseEventType.REASONING, Map.of(
                        "messageId", thinking.getReplyId(), "delta", thinking.getDelta()));
            } else if (event instanceof ToolCallStartEvent start) {
                buffer(task.id()).put(start.getToolCallId(), new StringBuilder());
                broadcast(task.id(), SseEventType.TOOL_START, Map.of(
                        "callId", start.getToolCallId(), "name", start.getToolCallName()));
            } else if (event instanceof ToolResultTextDeltaEvent resultDelta) {
                StringBuilder buf = buffer(task.id()).get(resultDelta.getToolCallId());
                if (buf != null) {
                    buf.append(resultDelta.getDelta());
                }
            } else if (event instanceof ToolResultEndEvent end) {
                // 工具执行完成事件才带结果；ToolCallEndEvent 只是模型请求 flush，无结果
                if (end.getState() == ToolResultState.RUNNING) {
                    return; // 工具挂起（suspended），等待真正的完成事件
                }
                StringBuilder buf = buffer(task.id()).remove(end.getToolCallId());
                boolean ok = end.getState() == ToolResultState.SUCCESS;
                String resultSummary = truncate(buf == null ? "" : buf.toString(), 500);
                broadcast(task.id(), SseEventType.TOOL_END, Map.of(
                        "callId", end.getToolCallId(),
                        "name", end.getToolCallName(),
                        "ok", ok,
                        "resultSummary", resultSummary));
                // 会话滚动记忆：工具调用计数（SUCCESS 与失败都算一次）
                sessionMemory.onToolCall(task.userId(), task.sessionId());
                // 终止态回填：记录本轮已结束的工具调用
                taskToolCalls.computeIfAbsent(task.id(), k -> new ArrayList<>())
                        .add(new ChatMessage.ToolCallRef(end.getToolCallId(), end.getToolCallName(),
                                null, resultSummary, ok));
            } else if (event instanceof ToolCallEndEvent toolCallEnd) {
                log.debug("工具调用已发出 task={} callId={}", task.id(), toolCallEnd.getToolCallId());
            } else if (event instanceof AgentResultEvent result) {
                // AgentScope 流式阶段已用 TextBlockDeltaEvent 逐段广播同一回复；结尾的
                // AgentResultEvent 再带一遍全文，直接广播会把整段正文渲染两遍。
                // 与已流出正文比对：已被增量覆盖则跳过；只覆盖前缀则补广播剩余部分；
                // 完全不重叠（未走流式的兜底路径）则照常广播全文。
                String text = result.getResult() == null ? "" : result.getResult().getTextContent();
                if (text != null && !text.isEmpty()) {
                    String streamed = textBuffer(task.id()).toString();
                    if (streamed.endsWith(text)) {
                        return;
                    }
                    if (!streamed.isEmpty() && text.startsWith(streamed)) {
                        text = text.substring(streamed.length());
                    }
                    if (!text.isEmpty()) {
                        broadcast(task.id(), SseEventType.TEXT, Map.of("messageId", result.getId(), "delta", text));
                    }
                }
            } else if (event instanceof HintBlockEvent hint) {
                broadcast(task.id(), SseEventType.TEXT, Map.of("messageId", hint.getReplyId(), "delta", hint.getHint()));
            } else if (event instanceof ExceedMaxItersEvent exceed) {
                broadcast(task.id(), SseEventType.TEXT, Map.of(
                        "messageId", exceed.getReplyId(),
                        "delta", "[已达到最大迭代次数 " + exceed.getMaxIters() + "，提前结束]"));
            } else if (event instanceof RequestStopEvent) {
                log.info("任务收到停止请求 task={}", task.id());
            } else if (event instanceof AgentEndEvent) {
                log.debug("agent 结束 task={}", task.id());
                // 每轮迭代检查上下文压缩阈值，达标广播 planned（per-task 去重）
                autoCompact.checkAndBroadcast(task);
            } else {
                // AGENT_START/MODEL_CALL_START/TEXT_BLOCK_START 等生命周期事件无需映射，debug 级记录
                log.debug("EVENT unmapped type={} class={} task={}", event.getType(), event.getClass().getSimpleName(), task.id());
            }
        } catch (Exception e) {
            log.warn("事件处理失败 type={} task={}: {}", event.getType(), task.id(), e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private TaskRecord requireTask(String taskId) {
        return taskStore.get(taskId).orElseThrow(
                () -> new com.markagent.api.GlobalExceptionHandler.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "任务 " + taskId + " 不存在"));
    }

    private Map<String, StringBuilder> buffer(String taskId) {
        return toolBuffers.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>());
    }

    private StringBuilder textBuffer(String taskId) {
        return textBuffers.computeIfAbsent(taskId, k -> new StringBuilder());
    }

    private void broadcast(String taskId, SseEventType type, Object data) {
        registry.broadcast(taskId, type, data);
    }

    private static TaskRecord withStatus(TaskRecord task, TaskStatus status, List<CheckpointRecord> checkpoints) {
        return withStatus(task, status, checkpoints, task.errorMessage());
    }

    private static TaskRecord withStatus(TaskRecord task, TaskStatus status, List<CheckpointRecord> checkpoints,
            String errorMessage) {
        return new TaskRecord(task.id(), task.sessionId(), task.userId(), task.goal(), status,
                task.plan(), checkpoints, task.options(), task.createdAt(), Instant.now().toString(), errorMessage);
    }

    private static String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }
}
