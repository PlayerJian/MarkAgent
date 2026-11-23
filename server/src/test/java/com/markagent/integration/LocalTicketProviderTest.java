package com.markagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.agent.model.TaskUserContext;
import com.markagent.store.AtomicJsonFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalTicketProvider 单测（@TempDir 落盘）：
 * create→query 往返、跨日文件回查（含窗口边界）、状态字段、未实现 provider 错误文案。
 */
class LocalTicketProviderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUser() {
        TaskUserContext.set("u_test");
    }

    @AfterEach
    void clearUser() {
        TaskUserContext.clear();
    }

    private LocalTicketProvider provider(String name) {
        return new LocalTicketProvider(mapper, tempDir, name);
    }

    @Test
    void createReturnsIdAndQueryRoundTrip() {
        LocalTicketProvider provider = provider("local");
        String id = provider.create("数据库连接超时", "生产库偶发超时，请排查");
        assertTrue(id.startsWith("t_"), "工单 id 应以 t_ 开头: " + id);

        String detail = provider.query(id);
        assertTrue(detail.contains("数据库连接超时"));
        assertTrue(detail.contains("生产库偶发超时"));
        assertTrue(detail.contains("状态：OPEN"));
        assertTrue(detail.contains("创建人：u_test"));
        assertTrue(detail.contains("创建时间："));
    }

    @Test
    void queryFindsTicketInPreviousDayFile() throws IOException {
        LocalTicketProvider provider = provider("local");
        // 回放：3 天前的日文件里已有工单
        LocalTicketProvider.Ticket old = new LocalTicketProvider.Ticket(
                "t_old1", "历史工单", "三天前创建", "u_other", "OPEN", "2026-11-09T10:00:00Z");
        new AtomicJsonFile(provider.monthFile(LocalDate.now().minusDays(3)))
                .appendLine(mapper.writeValueAsString(old));

        String detail = provider.query("t_old1");
        assertTrue(detail.contains("历史工单"));
        assertTrue(detail.contains("状态：OPEN"));
        assertTrue(detail.contains("创建人：u_other"));
    }

    @Test
    void queryMissesBeyondLookbackWindow() throws IOException {
        LocalTicketProvider provider = provider("local");
        LocalTicketProvider.Ticket stale = new LocalTicketProvider.Ticket(
                "t_stale", "过期工单", "8 天前创建", "u_other", "OPEN", "2026-11-04T10:00:00Z");
        new AtomicJsonFile(provider.monthFile(LocalDate.now().minusDays(8)))
                .appendLine(mapper.writeValueAsString(stale));

        assertTrue(provider.query("t_stale").contains("不存在"));
    }

    @Test
    void queryNotFoundReturnsError() {
        assertTrue(provider("local").query("t_none").contains("不存在"));
    }

    @Test
    void unimplementedProviderReturnsError() {
        LocalTicketProvider provider = provider("jira");
        assertTrue(provider.create("标题", "描述").contains("工单 provider jira 未实现"));
        assertTrue(provider.query("t_1").contains("工单 provider jira 未实现"));
    }

    @Test
    void blankTitleRejected() {
        assertTrue(provider("local").create("  ", "desc").contains("工单标题为空"));
    }
}
