package com.markagent.context;

import com.markagent.common.dto.ContextUsage;
import com.markagent.config.MarkAgentProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文用量估算（chars/4 近似 token，与 DeepSeek 计费口径同数量级）。
 * 数据来源：AgentScope 持久化的 AgentState（data/agentscope/{userId}/{sessionId}/agent_state.json）。
 */
@Component
public class TokenBudgetService {

    private static final String STATE_KEY = "agent_state";

    private final AgentStateStore agentStateStore;
    private final long budget;

    public TokenBudgetService(AgentStateStore agentStateStore, MarkAgentProperties props) {
        this.agentStateStore = agentStateStore;
        this.budget = props.context().maxTokens();
    }

    /** 文本 → token 估算（chars/4） */
    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    /** 会话上下文用量（AgentState.context 全部消息） */
    public ContextUsage usage(String userId, String sessionId) {
        try {
            var state = agentStateStore.get(userId, sessionId, STATE_KEY, AgentState.class);
            if (state.isEmpty()) {
                return new ContextUsage(0, budget, 0.0, 0, null);
            }
            List<Msg> context = state.get().getContext();
            long chars = 0;
            for (Msg msg : context) {
                String text = msg.getTextContent();
                chars += text == null ? 0 : text.length();
            }
            long used = Math.max(0, chars / 4);
            double percent = budget <= 0 ? 0 : Math.min(1.0, (double) used / budget);
            return new ContextUsage(used, budget, percent, context.size(), null);
        } catch (Exception e) {
            return new ContextUsage(0, budget, 0.0, 0, null);
        }
    }

    public long budget() {
        return budget;
    }
}
