package com.markagent.store;

import com.markagent.common.dto.SessionRecord;

import java.util.List;
import java.util.Optional;

/** 会话持久化（前端会话列表 + 消息历史展示用；agent 上下文由 AgentScope AgentState 独立管理） */
public interface SessionStore {

    void save(SessionRecord session);

    Optional<SessionRecord> get(String userId, String sessionId);

    List<SessionRecord> listByUser(String userId);

    void delete(String userId, String sessionId);
}
