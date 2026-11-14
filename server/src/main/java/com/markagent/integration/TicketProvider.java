package com.markagent.integration;

/**
 * 工单能力接口（可插拔企业集成层）。
 * <p>
 * 默认实现 {@link LocalTicketProvider}：本地 jsonl 落盘（data/tickets/{yyyy-MM}.jsonl），
 * 无开关、开箱即用。接入 JIRA 等真实工单系统时提供本接口的替代实现并替换 Bean 即可
 * （多实现并存时用 {@code @Primary} 或 {@code @Qualifier} 选择；v1 仅 LocalTicketProvider 一个实现，
 * 工具层 {@link IntegrationTool} 直接按类型注入）。
 * <p>
 * 返回约定：create 返回工单 id（t_ 前缀）；query 返回详情文本；失败返回以「错误：」开头的文本。
 * 实现不得抛出异常，错误一律以文本返回。
 */
public interface TicketProvider {

    /**
     * 创建工单。
     *
     * @param title       工单标题（非空）
     * @param description 工单详细描述（可为空）
     * @return 工单 id（t_ 前缀）；失败以「错误：」开头
     */
    String create(String title, String description);

    /**
     * 按 id 查询工单详情（标题/描述/状态/创建人/创建时间）。
     *
     * @param ticketId 工单 id
     * @return 详情文本；未找到或失败以「错误：」开头
     */
    String query(String ticketId);
}
