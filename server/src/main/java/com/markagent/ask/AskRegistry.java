package com.markagent.ask;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待决人工审批登记表：ask_user 工具阻塞等待期间把 {@link PendingAsk} 登记于此，
 * REST 端点经 callId 应答（{@link #answer}），任务 pause/stop 收尾经
 * {@link #cancelPending} 统一取消，任务终结时 {@link #removeByTask} 清理，
 * 前端经 {@link #pendingOf} 查询待决列表。
 * <p>
 * 线程安全：登记表用 {@link ConcurrentHashMap}；单个待决项的「未应答 → 已应答/已取消」
 * 跳变收敛在 {@link PendingAsk} 内部的 CAS 上（幂等），完成闩只在跳变成功时 countDown。
 * cancelPending / removeByTask 均为幂等，可被 AgentChatService 的 pause/stop/finish
 * 安全并发调用。
 */
@Component
public class AskRegistry {

    private final Map<String, PendingAsk> byCallId = new ConcurrentHashMap<>();

    /** 登记待决审批（callId 全局唯一，重复登记视为 bug 直接抛异常） */
    public void register(PendingAsk ask) {
        PendingAsk prev = byCallId.putIfAbsent(ask.callId(), ask);
        if (prev != null) {
            throw new IllegalStateException("审批已登记: " + ask.callId());
        }
    }

    /** 按 callId 查询；不存在返回 null */
    public PendingAsk get(String callId) {
        return byCallId.get(callId);
    }

    /** 移除登记（工具等待结束的 finally 清理用），返回是否存在过 */
    public boolean remove(String callId) {
        return byCallId.remove(callId) != null;
    }

    /**
     * 应答：按 callId 找到待决审批并写入用户选择、释放完成闩。
     * 不存在或已应答/已取消返回 false（幂等）。
     */
    public boolean answer(String callId, String value) {
        PendingAsk ask = byCallId.get(callId);
        if (ask == null) {
            return false;
        }
        return ask.answer(value);
    }

    /**
     * 取消任务下所有「未应答」的待决审批（任务 pause/stop 联动）：
     * 统一置取消标记并 countDown，工具线程醒来后返回「审批已取消」。返回取消数量；幂等。
     */
    public int cancelPending(String taskId) {
        int cancelled = 0;
        for (PendingAsk ask : byCallId.values()) {
            if (ask.taskId().equals(taskId) && ask.cancel()) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** 移除任务下所有登记（任务终结 cleanup 用），返回移除数量；幂等 */
    public int removeByTask(String taskId) {
        int removed = 0;
        for (Map.Entry<String, PendingAsk> entry : byCallId.entrySet()) {
            if (entry.getValue().taskId().equals(taskId)
                    && byCallId.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /** 任务下仍待决（未应答且未取消）的审批列表（前端查询用） */
    public List<Map<String, Object>> pendingOf(String taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PendingAsk ask : byCallId.values()) {
            if (ask.taskId().equals(taskId) && ask.isPending()) {
                out.add(Map.of(
                        "callId", ask.callId(),
                        "question", ask.question(),
                        "options", ask.options(),
                        "createdAt", ask.createdAt().toString()));
            }
        }
        return out;
    }

    /** 当前登记数量（观测/测试用） */
    public int size() {
        return byCallId.size();
    }
}
