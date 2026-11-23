package com.markagent.integration;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 企业集成工具集：db_query（数据库查询）/ notify_send（IM 通知）/ ticket_create、ticket_query（工单）。
 * <p>
 * 三个能力均走可插拔接口（{@link DatabaseProvider}/{@link Notifier}/{@link TicketProvider}）：
 * 默认实现为「禁用态（数据库/通知）+ 本地兜底（工单 jsonl 落盘）」，接入真实系统时
 * 替换对应 Bean 即可，本类零改动。未启用或未配置时各能力返回明确错误提示文本（工具不抛异常）。
 * <p>
 * 接入提醒：新工具需在 AgentScopeConfig#toolkit 与 WorkerAgentFactory 中经
 * {@code MarkAgentToolRegistrar.register(toolkit, integrationTool)} 注册后才会进入 agent 工具集。
 */
@Component
public class IntegrationTool {

    /** notify_send 内容截断上限（字符） */
    private static final int MAX_NOTIFY_CHARS = 2000;

    private final DatabaseProvider databaseProvider;
    private final Notifier notifier;
    private final TicketProvider ticketProvider;

    public IntegrationTool(DatabaseProvider databaseProvider, Notifier notifier, TicketProvider ticketProvider) {
        this.databaseProvider = databaseProvider;
        this.notifier = notifier;
        this.ticketProvider = ticketProvider;
    }

    /**
     * 对企业数据库执行只读 SQL 查询。结果文本加前缀说明（行数/截断情况取自 provider 首行摘要）。
     * 未启用时返回错误提示；非只读或多语句由护栏拒绝。
     */
    @Tool(name = "db_query", description = "对配置的企业数据库执行只读 SQL 查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH，"
            + "单语句，禁止写操作）。返回结构化文本：首行摘要、列名行、数据行（结果上限 100 行，单元格截断）。"
            + "数据库集成未启用时返回错误提示，不要重试。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String db_query(@ToolParam(name = "sql", required = true,
            description = "只读 SQL 查询语句（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH 开头，单语句）") String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误：SQL 为空";
        }
        String result = databaseProvider.query(sql);
        if (result.startsWith("错误：")) {
            return result;
        }
        int nl = result.indexOf('\n');
        if (nl < 0) {
            return "数据库查询结果：" + result;
        }
        return "数据库查询结果（" + result.substring(0, nl) + "）：\n" + result.substring(nl + 1);
    }

    /** 向企业 IM 发送通知（content 截断 2000 字符）。未启用时返回错误提示。 */
    @Tool(name = "notify_send", description = "向企业 IM（企微/钉钉等 webhook）发送通知。"
            + "title 为标题行，content 为正文（最长 2000 字符，超出自动截断）。"
            + "发送前先 ask_user 征得用户同意（对外发送消息属不可逆操作）。"
            + "通知集成未启用时返回错误提示，不要重试。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String notify_send(
            @ToolParam(name = "title", required = true, description = "通知标题（一行摘要）") String title,
            @ToolParam(name = "content", required = true, description = "通知正文内容") String content) {
        if (title == null || title.isBlank()) {
            return "错误：通知标题为空";
        }
        if (content == null || content.isBlank()) {
            return "错误：通知内容为空";
        }
        String text = content.length() > MAX_NOTIFY_CHARS
                ? content.substring(0, MAX_NOTIFY_CHARS) + "…（已截断）"
                : content;
        return notifier.send(title, text);
    }

    /** 创建工单，返回工单 id。 */
    @Tool(name = "ticket_create", description = "在工单系统中创建工单并返回工单 id（t_ 前缀）。"
            + "title 为工单标题，description 为问题详细描述。"
            + "默认实现为本地落盘（data/tickets/），工单 provider 未实现时返回错误提示。",
            strict = true, readOnly = false, concurrencySafe = false)
    public String ticket_create(
            @ToolParam(name = "title", required = true, description = "工单标题（简要说明问题）") String title,
            @ToolParam(name = "description", required = false, description = "工单详细描述（背景/复现步骤/影响）") String description) {
        if (title == null || title.isBlank()) {
            return "错误：工单标题为空";
        }
        return ticketProvider.create(title, description);
    }

    /** 按 id 查询工单详情。 */
    @Tool(name = "ticket_query", description = "按工单 id 查询工单详情（标题/描述/状态/创建人/创建时间）。"
            + "工单不存在时返回错误提示。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String ticket_query(@ToolParam(name = "ticket_id", required = true,
            description = "工单 id（t_ 前缀，来自 ticket_create 的返回值）") String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return "错误：工单 ID 为空";
        }
        return ticketProvider.query(ticketId);
    }
}
