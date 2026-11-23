package com.markagent.ask;

import com.markagent.agent.model.TaskIdContext;
import com.markagent.common.sse.SseEventType;
import com.markagent.events.SseEmitterRegistry;
import com.markagent.util.Ids;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 人工审批工具：模型执行高风险/不可逆动作前调用 ask_user 向用户征求确认。
 * <ul>
 *   <li>工具线程登记 {@link PendingAsk} 并广播 ASK_USER 事件后阻塞在完成闩上，
 *       前端（或 REST 端点）以 callId 应答，工具把用户选择作为结果文本返回给模型。</li>
 *   <li>超时未应答按 markagent.ask-user.auto-approve 决定：false 中止（默认），
 *       true 自动选择第 1 个选项。</li>
 *   <li>任务 pause/stop 时集成方调 {@link AskRegistry#cancelPending} 释放完成闩，
 *       工具返回「审批已取消」，不会悬挂。</li>
 * </ul>
 */
@Component
public class AskUserTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    /** 超时下限（秒）：过短的等待没有审批意义，且避免误触超时策略 */
    static final int MIN_TIMEOUT_SECONDS = 5;
    /** 超时上限（秒）：防止任务被单个审批卡死过久 */
    static final int MAX_TIMEOUT_SECONDS = 600;
    /** 缺省选项：未传 options 时的二选一 */
    static final List<String> DEFAULT_OPTIONS = List.of("同意", "拒绝");

    /** ASK_USER 事件广播动作：默认走 {@link SseEmitterRegistry}，单测注入 no-op/捕获替身 */
    @FunctionalInterface
    interface Broadcaster {
        void broadcast(String taskId, Map<String, Object> event);
    }

    private final AskRegistry asks;
    private final Broadcaster broadcaster;
    private final int defaultTimeoutSeconds;
    private final boolean autoApprove;

    /** 多构造器（另有测试入口）时必须显式标注装配构造器，否则 Spring 7 报「无默认构造器」 */
    @Autowired
    public AskUserTool(AskRegistry asks, SseEmitterRegistry registry,
            @Value("${markagent.ask-user.default-timeout-seconds:120}") int defaultTimeoutSeconds,
            @Value("${markagent.ask-user.auto-approve:false}") boolean autoApprove) {
        this(asks, defaultTimeoutSeconds, autoApprove,
                (taskId, event) -> registry.broadcast(taskId, SseEventType.ASK_USER, event));
    }

    /** 测试/定制入口：显式传入广播动作（单测注入 no-op/捕获替身，绕开 SseEmitterRegistry 重依赖） */
    AskUserTool(AskRegistry asks, int defaultTimeoutSeconds, boolean autoApprove, Broadcaster broadcaster) {
        this.asks = asks;
        this.broadcaster = broadcaster;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.autoApprove = autoApprove;
    }

    /**
     * 向用户发起审批并阻塞等待应答（默认最长 120 秒，可配）。
     * 返回用户选择的选项原文；超时按 auto-approve 策略处理；任务暂停/停止时返回已取消提示。
     */
    @Tool(name = "ask_user",
            description = "在执行高风险或不可逆操作前，向用户发起人工审批并阻塞等待应答。"
                    + "参数 question 为要询问用户的问题，必须自包含：说明将要执行的操作、风险与代价"
                    + "（如费用、影响范围、不可回退性）。参数 options 为候选选项（2-5 个，逗号分隔或 "
                    + "JSON 数组字符串），不传时默认 [\"同意\",\"拒绝\"]；工具返回文本为用户对所选选项的确认。"
                    + "参数 timeout_seconds 为最多等待秒数（默认 120，上限 600，下限 5，超限按上下限处理）。"
                    + "只有确实需要用户拍板（外部付费、删除数据、生产变更、发布动作等）时才调用本工具；"
                    + "用户在限定时间内未应答时操作会中止（或按配置自动选择第 1 个选项），如需继续请用户重新发起。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String askUser(@ToolParam(name = "question", required = true,
            description = "要询问用户的问题：自包含，说明将要执行的操作、风险与代价（如费用、影响范围、不可回退性）") String question,
            @ToolParam(name = "options", required = false,
                    description = "候选选项（2-5 个），逗号分隔（同意,拒绝）或 JSON 数组字符串（[\"同意\",\"拒绝\"]）；缺省 [\"同意\",\"拒绝\"]") String options,
            @ToolParam(name = "timeout_seconds", required = false,
                    description = "最多等待用户应答的秒数（默认 120，上限 600，下限 5，超限按上下限处理）") Integer timeoutSeconds) {
        String taskId = TaskIdContext.get();
        if (taskId == null) {
            return "错误：无任务上下文（ask_user 只能在任务内调用）";
        }

        List<String> optionsList = parseOptions(options);
        if (optionsList == null) {
            return "错误：options 参数非法（需 2-5 个非空选项，逗号分隔或 JSON 数组字符串，"
                    + "如 \"同意,拒绝\" 或 [\"同意\",\"拒绝\"]）";
        }

        int timeout = clampTimeout(timeoutSeconds);
        String callId = Ids.next("ask_");
        PendingAsk pending = new PendingAsk(callId, taskId, question, optionsList);
        asks.register(pending);
        try {
            // 广播 ASK_USER 事件（callId 为前端/端点应答的凭据）
            try {
                broadcaster.broadcast(taskId, Map.of(
                        "callId", callId,
                        "question", question,
                        "options", optionsList,
                        "timeoutSeconds", timeout));
            } catch (Exception e) {
                log.warn("ASK_USER 广播失败 task={} callId={}: {}", taskId, callId, e.getMessage());
            }

            boolean released;
            try {
                released = pending.latch().await(timeout, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 等待被中断：已被 cancelPending 取消（任务暂停/停止）按取消返回，否则按未应答处理
                if (pending.isCancelled()) {
                    return "审批已取消（任务暂停或停止）";
                }
                return unanswered("等待被中断", optionsList);
            }
            if (released) {
                if (pending.isCancelled()) {
                    return "审批已取消（任务暂停或停止）";
                }
                String answer = pending.getAnswer();
                if (answer == null) {
                    // 防御：完成闩已释放但无应答值（理论不可达）按未应答处理
                    return unanswered("超时 " + timeout + " 秒", optionsList);
                }
                return "用户选择：" + answer;
            }
            // 超时未应答
            return unanswered("超时 " + timeout + " 秒", optionsList);
        } finally {
            asks.remove(callId);
        }
    }

    /** 未应答收尾：auto-approve=true 自动选择第 1 个选项，否则中止（reason 用于中止文案，如「超时 120 秒」） */
    private String unanswered(String reason, List<String> optionsList) {
        if (autoApprove) {
            return "用户未在限定时间内确认，已按超时策略自动选择第 1 个选项：" + optionsList.get(0);
        }
        return "用户未在限定时间内确认（" + reason + "），操作已中止；如需继续请用户重新发起";
    }

    /** 超时秒数：缺省用配置 default-timeout-seconds；超限按上下限（5-600）处理 */
    private int clampTimeout(Integer timeoutSeconds) {
        int t = timeoutSeconds == null ? defaultTimeoutSeconds : timeoutSeconds;
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(t, MAX_TIMEOUT_SECONDS));
    }

    /**
     * 解析 options 参数：null/空白 → 缺省 ["同意","拒绝"]；
     * JSON 数组字符串（["A","B"]）或逗号分隔（A,B），选项去空白、剥引号。
     * 非法（选项数不在 2-5、存在空选项、JSON 格式残缺）返回 null。
     */
    static List<String> parseOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_OPTIONS;
        }
        String s = raw.trim();
        List<String> entries;
        if (s.startsWith("[") || s.endsWith("]")) {
            // 形似 JSON 数组：必须完整以 [ 开头、] 结尾，残缺按非法处理
            if (!s.startsWith("[") || !s.endsWith("]")) {
                return null;
            }
            entries = splitJsonArray(s.substring(1, s.length() - 1));
            if (entries == null) {
                return null;
            }
        } else {
            entries = new ArrayList<>();
            for (String part : s.split(",", -1)) {
                entries.add(part.trim());
            }
        }
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            String opt = stripJsonQuotes(entry);
            if (opt.isEmpty()) {
                return null; // 空选项非法
            }
            out.add(opt);
        }
        if (out.size() < 2 || out.size() > 5) {
            return null;
        }
        return List.copyOf(out);
    }

    /** 按逗号切分 JSON 数组内层（尊重双引号包裹的逗号）；引号不配对等残缺格式返回 null */
    private static List<String> splitJsonArray(String inner) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                cur.append(c);
            } else if (c == ',' && !inQuote) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (inQuote) {
            return null; // 引号未闭合：残缺 JSON
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** 剥掉 JSON 字符串条目两侧的双引号（"A" → A；未完整包裹按原文保留） */
    private static String stripJsonQuotes(String entry) {
        String s = entry.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
