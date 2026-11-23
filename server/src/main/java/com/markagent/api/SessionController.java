package com.markagent.api;

import com.markagent.common.dto.SessionRecord;
import com.markagent.docs.DocLibraryService;
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
    private final DocLibraryService docLibraryService;

    public SessionController(SessionStore sessionStore, DocLibraryService docLibraryService) {
        this.sessionStore = sessionStore;
        this.docLibraryService = docLibraryService;
    }

    /** 会话列表（侧边栏历史面板）：剔除 messages 减负，列表只需标题/时间排序 */
    @GetMapping
    public List<SessionRecord> list(Authentication authentication) {
        return sessionStore.listByUser(authentication.getName()).stream()
                .map(s -> new SessionRecord(s.id(), s.userId(), s.title(), List.of(),
                        s.createdAt(), s.updatedAt(), s.docLibraryId()))
                .toList();
    }

    /** 会话详情（含消息历史）：把历史对话加载回聊天工作台（继续多轮对话）用 */
    @GetMapping("/{id}")
    public SessionRecord get(@PathVariable("id") String sessionId, Authentication authentication) {
        return sessionStore.get(authentication.getName(), sessionId)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("会话 " + sessionId + " 不存在"));
    }

    /** 删除会话：会话绑定的单文件临时库（up_）一并即焚；正式库（dl_）是用户资产，不动 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") String sessionId, Authentication authentication) {
        String userId = authentication.getName();
        sessionStore.get(userId, sessionId).ifPresent(session -> {
            if (session.docLibraryId() != null && session.docLibraryId().startsWith("up_")) {
                docLibraryService.deleteLibrary(userId, session.docLibraryId());
            }
        });
        sessionStore.delete(userId, sessionId);
    }
}
