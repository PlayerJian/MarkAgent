package com.markagent.api;

import com.markagent.common.dto.SessionRecord;
import com.markagent.store.SessionStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话列表/删除（agent 上下文由 AgentScope AgentState 管理，本接口只做 UI 展示层） */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionStore sessionStore;

    public SessionController(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @GetMapping
    public List<SessionRecord> list(Authentication authentication) {
        return sessionStore.listByUser(authentication.getName());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") String sessionId, Authentication authentication) {
        sessionStore.delete(authentication.getName(), sessionId);
    }
}
