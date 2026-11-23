package com.markagent.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IntegrationTool 参数校验与错误文案单测（注入假 provider 实现，不发起真实连接/网络请求）。
 */
class IntegrationToolTest {

    /** 假工单 provider：透传返回固定文本 */
    private static final TicketProvider FAKE_TICKET = new TicketProvider() {
        @Override
        public String create(String title, String description) {
            return "t_fake";
        }

        @Override
        public String query(String ticketId) {
            return "工单详情";
        }
    };

    private static IntegrationTool tool(DatabaseProvider db, Notifier notifier) {
        return new IntegrationTool(db, notifier, FAKE_TICKET);
    }

    @Test
    void dbQueryAddsSummaryPrefix() {
        IntegrationTool tool = tool(sql -> "共 3 行\nid | name\n1 | a\n2 | b", (t, c) -> "ok");
        String result = tool.db_query("SELECT 1");
        assertTrue(result.startsWith("数据库查询结果（共 3 行）："), result);
        assertTrue(result.contains("id | name"));
    }

    @Test
    void dbQueryRejectsBlankSql() {
        IntegrationTool tool = tool(sql -> "不应调用", (t, c) -> "ok");
        assertTrue(tool.db_query(null).contains("SQL 为空"));
        assertTrue(tool.db_query("  ").contains("SQL 为空"));
    }

    @Test
    void dbQueryPassesThroughProviderError() {
        IntegrationTool tool = tool(sql -> "错误：连接超时", (t, c) -> "ok");
        assertEquals("错误：连接超时", tool.db_query("SELECT 1"));
    }

    @Test
    void notifySendTruncatesContentAt2000() {
        String[] captured = new String[2];
        IntegrationTool tool = tool(sql -> "ok", (title, content) -> {
            captured[0] = title;
            captured[1] = content;
            return "通知已发送";
        });
        tool.notify_send("标题", "x".repeat(2005));
        assertEquals("标题", captured[0]);
        assertTrue(captured[1].startsWith("x".repeat(2000)));
        assertTrue(captured[1].endsWith("…（已截断）"));
        assertEquals(2006, captured[1].length());
    }

    @Test
    void notifySendValidatesBlankArgs() {
        IntegrationTool tool = tool(sql -> "ok", (t, c) -> "不应调用");
        assertTrue(tool.notify_send(null, "内容").contains("通知标题为空"));
        assertTrue(tool.notify_send("标题", "  ").contains("通知内容为空"));
    }

    @Test
    void notifySendPassesThroughProviderResult() {
        IntegrationTool tool = tool(sql -> "ok", (t, c) -> "错误：通知集成未启用（markagent.integration.notify.enabled=false），请配置后重试");
        assertTrue(tool.notify_send("标题", "内容").contains("通知集成未启用"));
    }

    @Test
    void ticketCreateValidatesTitleAndPassesThrough() {
        IntegrationTool tool = tool(sql -> "ok", (t, c) -> "ok");
        assertTrue(tool.ticket_create(" ", "描述").contains("工单标题为空"));
        assertEquals("t_fake", tool.ticket_create("标题", null));
    }

    @Test
    void ticketQueryValidatesIdAndPassesThrough() {
        IntegrationTool tool = tool(sql -> "ok", (t, c) -> "ok");
        assertTrue(tool.ticket_query("").contains("工单 ID 为空"));
        assertEquals("工单详情", tool.ticket_query("t_fake"));
    }
}
