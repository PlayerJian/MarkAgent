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
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多智能体团队工具（M6）：leader 经 spawn_agent 派生专家 worker。
 * <p>
 * worker 是一次性 ReActAgent（无 stateStore，见 {@link WorkerAgentFactory}），在
 * 本工具线程内同步跑到完成（latch 等待，超时 10 分钟），期间把 worker 事件扇出为
 * AGENT_MSG 事件（text 增量 / 工具调用），结束广播 AGENT_DONE，最终结果文本
 * 作为工具结果返回给 leader 汇总。
 */
@Component
public class TeamTool {

    private static final Logger log = LoggerFactory.getLogger(TeamTool.class);
    private static final int RESULT_MAX_CHARS = 4000;

    private final WorkerAgentFactory workerFactory;
    private final SseEmitterRegistry registry;
    private final CalculatorTool calculator;
    private final DateTimeTool dateTime;
    private final HttpRequestTool httpRequest;
    private final WebSearchTool webSearch;
    private final WorkspaceFileTool workspaceFile;

    /** taskId → 存活中的 worker 流订阅（任务 pause/stop 时收尾，避免工具线程干等 10 分钟超时） */
    private final Map<String, CopyOnWriteArrayList<Disposable>> workerSubs = new ConcurrentHashMap<>();

    public TeamTool(WorkerAgentFactory workerFactory, SseEmitterRegistry registry,
            CalculatorTool calculator, DateTimeTool dateTime,
            HttpRequestTool httpRequest, WebSearchTool webSearch, WorkspaceFileTool workspaceFile) {
        this.workerFactory = workerFactory;
        this.registry = registry;
        this.calculator = calculator;
        this.dateTime = dateTime;
        this.httpRequest = httpRequest;
        this.webSearch = webSearch;
        this.workspaceFile = workspaceFile;
    }

    /** 派生专家 worker 执行子任务并返回其结论 */
    @Tool(name = "spawn_agent",
            description = "派生一个专家 worker 智能体执行子任务并返回其结论。多步骤复杂任务可派生多个 worker 并行分工（如调研员/编码员/写手），汇总各结论形成终稿。参数 role 描述 worker 的角色定位，goal 描述其子任务目标。",
            readOnly = false)
    public String spawnAgent(@ToolParam(name = "role", required = true,
            description = "worker 的角色定位，如「资深技术调研员」「Python 编码专家」") String role,
            @ToolParam(name = "goal", required = true,
                    description = "worker 的子任务目标，描述清楚要产出什么") String goal) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文（spawn_agent 只能在任务内调用）";
        }
        String agentId = Ids.agent();
        registry.broadcast(taskId, SseEventType.AGENT_SPAWN, Map.of("agentId", agentId, "role", role));

        ReActAgent worker = workerFactory.create(agentId, role, calculator, dateTime, httpRequest, webSearch, workspaceFile);
        RuntimeContext rt = RuntimeContext.builder()
                .userId(TaskUserContext.get())
                .build();
        rt.put("taskId", taskId);

        StringBuilder finalText = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        Disposable[] holder = new Disposable[1];
        try {
            List<Msg> msgs = List.of(Msg.builderForRole(MsgRole.USER)
                    .textContent("子任务目标：\n" + goal).build());
            holder[0] = worker.streamEvents(msgs, rt)
                    .doOnNext(event -> handleWorkerEvent(taskId, agentId, event, finalText))
                    .doOnError(error -> {
                        ok.set(false);
                        log.warn("worker {} 失败 task={}: {}", agentId, taskId, error.getMessage());
                    })
                    .doFinally(signal -> {
                        latch.countDown();
                        CopyOnWriteArrayList<Disposable> subs = workerSubs.get(taskId);
                        if (subs != null) {
                            subs.remove(holder[0]);
                        }
                    })
                    .subscribe();
            workerSubs.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(holder[0]);
            if (!latch.await(10, TimeUnit.MINUTES)) {
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

        String result = finalText.toString().strip();
        String summary = result.length() > 500 ? result.substring(0, 500) + "…" : result;
        registry.broadcast(taskId, SseEventType.AGENT_DONE,
                Map.of("agentId", agentId, "ok", ok.get(), "resultSummary", summary));

        String reply = result.isEmpty() ? "（worker 未产出文本结论）" : result;
        return "worker「" + agentId + "」（" + role + "）执行完成：\n"
                + reply.substring(0, Math.min(reply.length(), RESULT_MAX_CHARS));
    }

    /** 终止任务下所有在跑的 worker（任务 pause/stop 联动） */
    public void interruptTask(String taskId) {
        CopyOnWriteArrayList<Disposable> subs = workerSubs.remove(taskId);
        if (subs == null) {
            return;
        }
        for (Disposable sub : subs) {
            sub.dispose();
        }
        log.info("worker 收尾 task={} count={}", taskId, subs.size());
    }

    /** 任务终结清理登记（防止 spawn 频繁任务的内存残留） */
    public void cleanup(String taskId) {
        workerSubs.remove(taskId);
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
