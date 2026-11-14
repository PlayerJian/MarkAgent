package com.markagent.tools;

import com.markagent.agent.model.TaskIdContext;
import com.markagent.agent.model.TaskUserContext;
import com.markagent.agent.team.WorkerAgentFactory;
import com.markagent.common.sse.SseEventType;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.util.Ids;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多智能体团队工具（M6）：leader 经 spawn_agent 派生专家 worker。
 * <ul>
 *   <li>worker 是一次性 ReActAgent（无 stateStore，见 {@link WorkerAgentFactory}）。</li>
 *   <li>background=false（默认）：在工具线程内同步跑到完成（latch 等待，超时 10 分钟），
 *       结果文本直接作为工具结果返回，行为与历史版本一致。</li>
 *   <li>background=true：虚拟线程后台派生并立即返回，worker 状态与结果登记进
 *       {@link WorkerRegistry}，leader 稍后用 agent_wait 按 agentId 限时等待并收取
 *       &lt;task-notification&gt; 结构化结果（可多次查询）。</li>
 *   <li>两种模式统一走 {@link #runWorker} 执行体：worker 事件扇出为 AGENT_MSG，
 *       结束广播 AGENT_DONE；pause/stop 经 {@link #interruptTask} dispose 流订阅并把
 *       RUNNING 句柄置 FAILED，保证后台线程与 agent_wait 等待都不悬挂。</li>
 *   <li>agent_list 列出任务下全部 worker 全景（startedAt 升序），agent_stop 按 agentId
 *       停止指定 RUNNING worker；已终态句柄超过 TTL（markagent.team.handle-ttl-minutes）
 *       后由新派生/agent_list 时机清理。</li>
 * </ul>
 */
@Component
public class TeamTool {

    private static final Logger log = LoggerFactory.getLogger(TeamTool.class);
    private static final int RESULT_MAX_CHARS = 4000;
    private static final int SUMMARY_MAX_CHARS = 500;
    private static final int LIST_SUMMARY_MAX_CHARS = 200;
    private static final int WAIT_TIMEOUT_MINUTES = 10;
    private static final int AGENT_WAIT_DEFAULT_SECONDS = 30;
    private static final int AGENT_WAIT_MAX_SECONDS = 300;

    private final WorkerAgentFactory workerFactory;
    private final SseEmitterRegistry registry;
    private final CalculatorTool calculator;
    private final DateTimeTool dateTime;
    private final HttpRequestTool httpRequest;
    private final WebSearchTool webSearch;
    private final WebFetchTool webFetch;
    private final WorkspaceFileTool workspaceFile;
    private final FileSearchTool fileSearch;
    private final FileEditTool fileEdit;

    /** taskId → agentId → 存活中的 worker 流订阅（任务 pause/stop 收尾全体，agent_stop 定位单个） */
    private final Map<String, ConcurrentHashMap<String, Disposable>> workerSubs = new ConcurrentHashMap<>();
    /** 派生登记表：agentId → 句柄（agent_wait/agent_list 查询；任务 cleanup 时按 taskId 移除） */
    private final WorkerRegistry workers = new WorkerRegistry();
    /** 句柄 TTL：已终态句柄超过该分钟数后，由新派生/agent_list 时机清理 */
    private final long handleTtlMinutes;

    public TeamTool(WorkerAgentFactory workerFactory, SseEmitterRegistry registry,
            CalculatorTool calculator, DateTimeTool dateTime,
            HttpRequestTool httpRequest, WebSearchTool webSearch,
            WebFetchTool webFetch, WorkspaceFileTool workspaceFile,
            FileSearchTool fileSearch, FileEditTool fileEdit,
            @Value("${markagent.team.handle-ttl-minutes:30}") long handleTtlMinutes) {
        this.workerFactory = workerFactory;
        this.registry = registry;
        this.calculator = calculator;
        this.dateTime = dateTime;
        this.httpRequest = httpRequest;
        this.webSearch = webSearch;
        this.webFetch = webFetch;
        this.workspaceFile = workspaceFile;
        this.fileSearch = fileSearch;
        this.fileEdit = fileEdit;
        this.handleTtlMinutes = handleTtlMinutes;
    }

    /** 派生专家 worker 执行子任务并返回其结论。
     *  background=true 时后台派生立即返回（用 agent_wait 查询结果），false（默认）同步等待完成。 */
    @Tool(name = "spawn_agent",
            description = "派生一个专家 worker 智能体执行子任务并返回其结论。多步骤复杂任务可派生多个 worker 并行分工（如调研员/编码员/写手），汇总各结论形成终稿。参数 role 描述 worker 的角色定位，goal 描述其子任务目标。参数 background=true 时后台派生并立即返回，之后用 agent_wait 工具查询其状态与结果；background=false（默认）时同步等待 worker 完成并直接返回结果。",
            readOnly = false)
    public String spawnAgent(@ToolParam(name = "role", required = true,
            description = "worker 的角色定位，如「资深技术调研员」「Python 编码专家」") String role,
            @ToolParam(name = "goal", required = true,
                    description = "worker 的子任务目标，描述清楚要产出什么") String goal,
            @ToolParam(name = "background", required = false,
                    description = "true=后台派生立即返回，之后用 agent_wait 工具查询该 worker 的状态与结果；false=同步等待 worker 完成后返回结果（默认）") Boolean background) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文（spawn_agent 只能在任务内调用）";
        }
        boolean bg = Boolean.TRUE.equals(background);
        String agentId = Ids.agent();
        registry.broadcast(taskId, SseEventType.AGENT_SPAWN,
                Map.of("agentId", agentId, "role", role, "background", bg));

        // 两种模式都登记句柄：后台模式供 agent_wait 查询；同步模式仅做状态记录，不影响返回值
        purgeExpiredHandles();
        WorkerRegistry.Handle handle = new WorkerRegistry.Handle(agentId, taskId, role);
        workers.register(handle);
        // taskId/userId 在工具线程捕获后传给执行体：后台虚拟线程拿不到 TaskXxxContext 的 ThreadLocal
        String userId = TaskUserContext.get();

        if (bg) {
            Thread.ofVirtual().name("worker-bg-" + agentId)
                    .start(() -> runWorker(handle, taskId, agentId, role, goal, userId));
            return "已在后台派生 worker「" + agentId + "」（" + role + "）。稍后用 agent_wait 工具查询其状态与结果。";
        }
        return runWorker(handle, taskId, agentId, role, goal, userId);
    }

    /** 查询已派生 worker 的状态与结果：限时等待完成闩，返回 <task-notification> 结构化结果。 */
    @Tool(name = "agent_wait",
            description = "查询已派生 worker 的状态与结果。spawn_agent 以 background=true 派生的 worker 会立即返回，之后用本工具按 agent_id 等待并收取其执行结果（可多次查询，直到拿到 completed/failed 终态）。返回 <task-notification> 结构化格式，status 为 completed/failed/running 之一。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String agentWait(@ToolParam(name = "agent_id", required = true,
            description = "要查询的 worker 的 agentId（spawn_agent 返回的「已在后台派生 worker『xxx』」中的 xxx）") String agentId,
            @ToolParam(name = "timeout_seconds", required = false,
                    description = "最多等待秒数（默认 30，上限 300，超限按 300 处理）；worker 尚未完成时返回 running 状态") Integer timeoutSeconds) {
        WorkerRegistry.Handle handle = workers.get(agentId);
        if (handle == null) {
            return "错误：worker " + agentId + " 不存在或已清理";
        }
        int timeout = timeoutSeconds == null ? AGENT_WAIT_DEFAULT_SECONDS
                : Math.max(0, Math.min(timeoutSeconds, AGENT_WAIT_MAX_SECONDS));
        try {
            if (!handle.getCompletionLatch().await(timeout, TimeUnit.SECONDS)) {
                // 超时未完成：running 提示，leader 可稍后再次查询
                return formatNotification(agentId, "running", "仍在执行，可稍后再次 agent_wait");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (WorkerRegistry.RUNNING.equals(handle.getStatus())) {
                return formatNotification(agentId, "running", "等待被中断，worker 仍在执行，可稍后再次 agent_wait");
            }
        }
        // 完成闩已释放（含 interruptTask 收尾路径）：按终态返回结构化通知
        return WorkerRegistry.SUCCESS.equals(handle.getStatus())
                ? formatNotification(agentId, "completed", handle.getResultText())
                : formatNotification(agentId, "failed", handle.getResultText());
    }

    /** 列出当前任务名下所有已登记 worker：身份/角色/状态/启动时间/结果摘要（startedAt 升序，条目间空行分隔）。 */
    @Tool(name = "agent_list",
            description = "列出当前任务名下所有已派生的 worker：agentId、角色、状态、启动时间与结果前 200 字摘要（按启动时间升序）。用于掌握后台派生（spawn_agent background=true）worker 的实时全景：RUNNING 表示仍在执行，配合 agent_wait 查询结果、agent_stop 停止指定 worker。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String agentList() {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文（agent_list 只能在任务内调用）";
        }
        purgeExpiredHandles();
        List<WorkerRegistry.Handle> handles = workers.listByTask(taskId);
        if (handles.isEmpty()) {
            return "当前任务没有派生过 worker";
        }
        return formatAgentList(handles);
    }

    /** 停止指定后台 worker：RUNNING → FAILED（原因「leader 主动停止」）并 dispose 其流订阅；已终态/不存在按语义返回。 */
    @Tool(name = "agent_stop",
            description = "停止指定后台派生的 worker。仅对 RUNNING 状态的 worker 生效：将其置为 failed（原因「leader 主动停止」）并终止其执行；已处于终态（SUCCESS/FAILED）或不存在时返回相应提示。停止后可继续用 agent_wait 收取该 worker 的失败结果。",
            readOnly = false)
    public String stopAgent(@ToolParam(name = "agent_id", required = true,
            description = "要停止的 worker 的 agentId（agent_list 列出的 worker 字段或 spawn_agent 返回文本中的 xxx）") String agentId) {
        return stopAgent(workers, workerSubs, agentId);
    }

    /**
     * agent_stop 决策与执行（纯函数，供单测）：终态判定、RUNNING → FAILED 跳变、
     * 定位并 dispose 单个订阅。先置 FAILED 再 dispose——dispose 触发的 doFinally 收尾
     * （ok=false + 放闩）不会覆盖已定终态；tryFail 返回 false 说明竞态期间 worker 已自行终结。
     */
    static String stopAgent(WorkerRegistry registry,
            Map<String, ConcurrentHashMap<String, Disposable>> workerSubs, String agentId) {
        WorkerRegistry.Handle handle = registry.get(agentId);
        if (handle == null) {
            return "错误：worker " + agentId + " 不存在或已清理";
        }
        if (!WorkerRegistry.RUNNING.equals(handle.getStatus())) {
            return "worker " + agentId + " 已处于终态（" + handle.getStatus() + "），无需停止";
        }
        if (!handle.tryFail("leader 主动停止")) {
            return "worker " + agentId + " 已处于终态（" + handle.getStatus() + "），无需停止";
        }
        ConcurrentHashMap<String, Disposable> subs = workerSubs.get(handle.getTaskId());
        if (subs != null) {
            Disposable sub = subs.remove(agentId);
            if (sub != null) {
                sub.dispose();
            }
        }
        log.info("worker {} 被 leader 主动停止 task={}", agentId, handle.getTaskId());
        return "已停止 worker " + agentId;
    }

    /** 组装 agent_list 条目文本（纯函数，供单测）：每 worker 五行字段，条目间空行分隔 */
    static String formatAgentList(List<WorkerRegistry.Handle> handles) {
        StringBuilder sb = new StringBuilder();
        for (WorkerRegistry.Handle h : handles) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("worker: ").append(h.getAgentId()).append('\n')
                    .append("role: ").append(h.getRole()).append('\n')
                    .append("status: ").append(h.getStatus()).append('\n')
                    .append("startedAt: ").append(h.getStartedAt()).append('\n')
                    .append("summary: ").append(summaryOf(h.getResultText()));
        }
        return sb.toString();
    }

    /** 结果文本前 200 字摘要：换行压缩为空格，超长截断加「…」，空文本给占位 */
    private static String summaryOf(String text) {
        String safe = text == null ? "" : text;
        if (safe.isBlank()) {
            return "（暂无输出）";
        }
        String oneLine = safe.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > LIST_SUMMARY_MAX_CHARS
                ? oneLine.substring(0, LIST_SUMMARY_MAX_CHARS) + "…" : oneLine;
    }

    /**
     * 同步执行体：前台（background=false）在当前工具线程跑，后台（background=true）在
     * worker-bg 虚拟线程跑。两者共用同一套流订阅 + latch 等待（上限 10 分钟）+ 事件扇出，
     * 完成后把最终文本写入登记句柄并置终态、释放完成闩（interruptTask 已置 FAILED 时幂等跳过）。
     */
    private String runWorker(WorkerRegistry.Handle handle, String taskId, String agentId, String role,
            String goal, String userId) {
        // 启动前复核：登记后立刻被 interruptTask 收尾（任务 pause/stop 竞态）则不再启动
        if (!WorkerRegistry.RUNNING.equals(handle.getStatus())) {
            log.info("worker {} 启动前已被终止 task={}", agentId, taskId);
            registry.broadcast(taskId, SseEventType.AGENT_DONE,
                    Map.of("agentId", agentId, "ok", false, "resultSummary", "worker 已终止（任务暂停或停止）"));
            return "worker「" + agentId + "」已终止（任务暂停或停止）";
        }

        ReActAgent worker = workerFactory.create(agentId, role, calculator, dateTime, httpRequest,
                webSearch, webFetch, workspaceFile, fileSearch, fileEdit);
        RuntimeContext rt = RuntimeContext.builder()
                .userId(userId)
                .build();
        rt.put("taskId", taskId);

        StringBuilder finalText = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        AtomicReference<String> errorMessage = new AtomicReference<>(null);
        Disposable[] holder = new Disposable[1];
        try {
            List<Msg> msgs = List.of(Msg.builderForRole(MsgRole.USER)
                    .textContent("子任务目标：\n" + goal).build());
            holder[0] = worker.streamEvents(msgs, rt)
                    .doOnNext(event -> handleWorkerEvent(taskId, agentId, event, finalText))
                    .doOnError(error -> {
                        ok.set(false);
                        errorMessage.set(error.getMessage());
                        log.warn("worker {} 失败 task={}: {}", agentId, taskId, error.getMessage());
                    })
                    .doFinally(signal -> {
                        // 订阅被 dispose（任务 pause/stop 联动）按失败收尾：后台句柄不能停在 RUNNING
                        if (signal == SignalType.CANCEL) {
                            ok.set(false);
                            finalText.append("\n[worker 已终止（任务暂停或停止）]");
                        }
                        latch.countDown();
                        ConcurrentHashMap<String, Disposable> subs = workerSubs.get(taskId);
                        if (subs != null) {
                            subs.remove(agentId, holder[0]);
                        }
                    })
                    .subscribe();
            workerSubs.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>()).put(agentId, holder[0]);
            if (!latch.await(WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                ok.set(false);
                finalText.append("\n[worker 超时（10 分钟）未完成]");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok.set(false);
            finalText.append("\n[worker 被中断]");
        } catch (Exception e) {
            ok.set(false);
            finalText.append("\n[worker 启动失败: ").append(e.getMessage()).append(']');
        }

        // 登记表回填：结果文本 + 终态 + 完成闩（先文本后状态，agent_wait 醒来时必能读到完整内容）
        handle.appendResult(finalText.toString().strip());
        if (ok.get()) {
            handle.completeSuccess();
        } else {
            String reason = errorMessage.get() == null ? null : "worker 异常: " + errorMessage.get();
            handle.completeFailure(reason);
        }

        String result = finalText.toString().strip();
        String summary = result.length() > SUMMARY_MAX_CHARS
                ? result.substring(0, SUMMARY_MAX_CHARS) + "…" : result;
        registry.broadcast(taskId, SseEventType.AGENT_DONE,
                Map.of("agentId", agentId, "ok", ok.get(), "resultSummary", summary));

        String reply = result.isEmpty() ? "（worker 未产出文本结论）" : result;
        return "worker「" + agentId + "」（" + role + "）执行完成：\n"
                + reply.substring(0, Math.min(reply.length(), RESULT_MAX_CHARS));
    }

    /**
     * 组装 &lt;task-notification&gt; 结构化通知（纯函数，供单测）：
     * summary = 文本前 500 字且换行压缩为空格；result = 完整文本，上限 4000 字截断加「…」。
     */
    static String formatNotification(String agentId, String status, String text) {
        String safe = text == null ? "" : text;
        String oneLine = safe.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        String summary = oneLine.length() > SUMMARY_MAX_CHARS
                ? oneLine.substring(0, SUMMARY_MAX_CHARS) + "…" : oneLine;
        String result = safe.length() > RESULT_MAX_CHARS
                ? safe.substring(0, RESULT_MAX_CHARS) + "…" : safe;
        return "<task-notification>\n"
                + "<agent-id>" + agentId + "</agent-id>\n"
                + "<status>" + status + "</status>\n"
                + "<summary>" + summary + "</summary>\n"
                + "<result>" + result + "</result>\n"
                + "</task-notification>";
    }

    /** 终止任务下所有在跑的 worker（任务 pause/stop 联动）：dispose 流订阅 + RUNNING 句柄置 FAILED */
    public void interruptTask(String taskId) {
        ConcurrentHashMap<String, Disposable> subs = workerSubs.remove(taskId);
        int subCount = 0;
        if (subs != null) {
            subCount = subs.size();
            for (Disposable sub : subs.values()) {
                sub.dispose();
            }
        }
        // 后台派生收尾：RUNNING → FAILED（原因「任务已暂停/停止」）+ 完成闩，
        // 保证后台执行线程与 agent_wait 等待者都不会悬挂（dispose 也触发 doFinally → latch countDown）
        int failed = workers.failByTask(taskId, "任务已暂停/停止");
        log.info("worker 收尾 task={} subCount={} failedHandles={}", taskId, subCount, failed);
    }

    /** 任务终结清理登记（防止 spawn 频繁任务的内存残留） */
    public void cleanup(String taskId) {
        workerSubs.remove(taskId);
        workers.removeByTask(taskId);
    }

    /** TTL 清理：移除已终态超过 handleTtlMinutes 分钟的句柄（agent_list / 新派生时机调用） */
    private void purgeExpiredHandles() {
        int removed = workers.purgeExpired(handleTtlMinutes);
        if (removed > 0) {
            log.info("worker 句柄 TTL 清理 removed={}", removed);
        }
    }

    /** worker 事件 → AGENT_MSG 扇出：text 增量直出，工具调用以 kind 标记 */
    private void handleWorkerEvent(String taskId, String agentId, AgentEvent event, StringBuilder finalText) {
        try {
            if (event instanceof TextBlockDeltaEvent text) {
                finalText.append(text.getDelta());
                registry.broadcast(taskId, SseEventType.AGENT_MSG,
                        Map.of("agentId", agentId, "kind", "text", "delta", text.getDelta()));
            } else if (event instanceof ToolCallStartEvent start) {
                registry.broadcast(taskId, SseEventType.AGENT_MSG,
                        Map.of("agentId", agentId, "kind", "tool", "name", start.getToolCallName(), "ok", true));
            } else if (event instanceof ToolResultEndEvent end
                    && end.getState() != ToolResultState.RUNNING) {
                registry.broadcast(taskId, SseEventType.AGENT_MSG, Map.of(
                        "agentId", agentId, "kind", "tool_end",
                        "name", end.getToolCallName(),
                        "ok", end.getState() == ToolResultState.SUCCESS));
            }
        } catch (Exception e) {
            log.warn("worker 事件扇出失败 task={} agent={}: {}", taskId, agentId, e.getMessage());
        }
    }
}
