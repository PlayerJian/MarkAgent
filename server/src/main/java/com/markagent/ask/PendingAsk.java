package com.markagent.ask;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次待决人工审批：ask_user 工具阻塞等待期间登记在 {@link AskRegistry}，
 * 用户经 REST 端点应答（{@link AskRegistry#answer}）或任务 pause/stop 收尾
 * （{@link AskRegistry#cancelPending}）时释放完成闩，工具线程据此返回结果文本。
 * <p>
 * 线程安全：answer 用 {@link AtomicReference} 的 CAS 实现「未应答 → 已应答 / 已取消」
 * 单向跳变——应答与取消竞争时先到者胜出（CAS 失败即已定终态），完成闩只在跳变成功
 * 时 countDown（幂等），保证应答线程、取消线程与工具等待线程三方都不悬挂。
 */
public final class PendingAsk {

    /**
     * 取消哨兵：cancelPending 置入 answer 的「特殊标记」。
     * 与 null（未应答）区分，且含 \u0000 控制字符、正常用户应答值不会与之相等。
     */
    private static final String CANCEL_MARKER = "\u0000__cancelled__";

    private final String callId;
    private final String taskId;
    private final String question;
    private final List<String> options;
    private final Instant createdAt = Instant.now();
    private final CountDownLatch latch = new CountDownLatch(1);
    /** 应答值：null=未应答；CANCEL_MARKER=已取消；其余=用户选择（选项原文） */
    private final AtomicReference<String> answer = new AtomicReference<>();

    public PendingAsk(String callId, String taskId, String question, List<String> options) {
        this.callId = callId;
        this.taskId = taskId;
        this.question = question;
        this.options = List.copyOf(options);
    }

    public String callId() {
        return callId;
    }

    public String taskId() {
        return taskId;
    }

    public String question() {
        return question;
    }

    /** 候选选项（不可变） */
    public List<String> options() {
        return options;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** 完成闩：用户应答或取消时 countDown，工具线程靠它限时等待 */
    public CountDownLatch latch() {
        return latch;
    }

    /** 应答原子值：null=未应答；取消哨兵=已取消；其余=选项原文 */
    public AtomicReference<String> answer() {
        return answer;
    }

    /** 用户应答：仅「未应答」状态生效（CAS），返回是否生效；已应答/已取消再应答返回 false（幂等） */
    public boolean answer(String value) {
        if (value == null || !answer.compareAndSet(null, value)) {
            return false;
        }
        latch.countDown();
        return true;
    }

    /** 取消（任务 pause/stop 联动）：仅「未应答」状态生效，置取消哨兵并释放完成闩；幂等 */
    public boolean cancel() {
        if (!answer.compareAndSet(null, CANCEL_MARKER)) {
            return false;
        }
        latch.countDown();
        return true;
    }

    /** 是否已取消（任务暂停或停止） */
    public boolean isCancelled() {
        return answer.get() == CANCEL_MARKER;
    }

    /** 是否仍待决（未应答且未取消） */
    public boolean isPending() {
        return answer.get() == null;
    }

    /** 用户选择值（选项原文）；未应答或已取消时返回 null */
    public String getAnswer() {
        String value = answer.get();
        return value == CANCEL_MARKER ? null : value;
    }
}
