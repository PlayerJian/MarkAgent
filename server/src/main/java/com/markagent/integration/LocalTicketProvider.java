package com.markagent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.agent.model.TaskUserContext;
import com.markagent.store.AtomicJsonFile;
import com.markagent.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 本地工单实现（v1 默认）：data/tickets/{yyyy-MM}.jsonl 按月落盘，一行一票
 * （id/title/description/userId/status=OPEN/createdAt），追加走 {@link AtomicJsonFile}
 * （O_APPEND 单行写入，天然避免半行撕裂）。
 * <p>
 * create 返回 t_ 前缀工单 id（{@link Ids}）；query 先查当日文件，再回查近
 * {@value #LOOKBACK_DAYS} 天文件（工单 id 不含日期，需按窗口回查）。
 * <p>
 * 接入 JIRA 等真实工单系统：实现 {@link TicketProvider} 替换本 Bean（或加
 * {@code @Primary}/{@code @Qualifier} 二选一），工具层经接口调用不受影响。
 * <p>
 * 配置键：markagent.integration.ticket.provider（默认 local）。仅 local 时生效；
 * 其他值一律返回「工单 provider xxx 未实现」错误文本。
 */
@Component
public class LocalTicketProvider implements TicketProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalTicketProvider.class);

    /** 查询回查窗口：当日 + 近 7 天文件 */
    static final int LOOKBACK_DAYS = 7;

    /** 工单状态（v1 固定 OPEN，接入真实系统后由替代实现管理流转） */
    private static final String STATUS_OPEN = "OPEN";

    private final Path ticketsDir;
    private final ObjectMapper mapper;
    private final String providerName;

    public LocalTicketProvider(ObjectMapper mapper, Path storageRoot,
            @Value("${markagent.integration.ticket.provider:local}") String providerName) {
        this.mapper = mapper;
        this.ticketsDir = storageRoot.resolve("tickets");
        this.providerName = providerName == null ? "local" : providerName.trim();
    }

    /** 工单落盘记录（一行一票；同包测试可直接构造以回放历史文件） */
    record Ticket(String id, String title, String description, String userId, String status, String createdAt) {
    }

    @Override
    public String create(String title, String description) {
        String blocked = providerBlocked();
        if (blocked != null) {
            return blocked;
        }
        if (title == null || title.isBlank()) {
            return "错误：工单标题为空";
        }
        Ticket ticket = new Ticket(
                Ids.next("t_"),
                title,
                description == null ? "" : description,
                TaskUserContext.get(),
                STATUS_OPEN,
                Instant.now().toString());
        try {
            new AtomicJsonFile(monthFile(LocalDate.now())).appendLine(mapper.writeValueAsString(ticket));
            return ticket.id();
        } catch (IOException e) {
            log.error("工单落盘失败 id={}: {}", ticket.id(), e.getMessage(), e);
            return "错误：工单创建失败 - " + e.getMessage();
        }
    }

    @Override
    public String query(String ticketId) {
        String blocked = providerBlocked();
        if (blocked != null) {
            return blocked;
        }
        if (ticketId == null || ticketId.isBlank()) {
            return "错误：工单 ID 为空";
        }
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= LOOKBACK_DAYS; i++) {
            String found = searchFile(monthFile(today.minusDays(i)), ticketId);
            if (found != null) {
                return found;
            }
        }
        return "错误：工单 " + ticketId + " 不存在（已查当日与近 " + LOOKBACK_DAYS + " 天记录）";
    }

    /** 在单个日文件中按 id 查（命中返回详情文本，未命中返回 null，损坏行跳过） */
    private String searchFile(Path file, String ticketId) {
        String content;
        try {
            content = new AtomicJsonFile(file).readString();
        } catch (IOException e) {
            log.warn("工单日文件读取失败 {}: {}", file, e.getMessage());
            return null;
        }
        if (content == null) {
            return null;
        }
        for (String line : content.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                if (ticketId.equals(node.path("id").asText())) {
                    return format(node);
                }
            } catch (IOException e) {
                log.warn("工单行解析失败 {}: {}", file, e.getMessage());
            }
        }
        return null;
    }

    /** 详情文本：id/标题/描述/状态/创建人/创建时间 */
    private String format(JsonNode t) {
        return "工单 " + t.path("id").asText() + "\n"
                + "标题：" + t.path("title").asText() + "\n"
                + "描述：" + t.path("description").asText() + "\n"
                + "状态：" + t.path("status").asText(STATUS_OPEN) + "\n"
                + "创建人：" + t.path("userId").asText("") + "\n"
                + "创建时间：" + t.path("createdAt").asText("");
    }

    /** provider 开关：非 local 一律拒绝（同包测试经此校验未实现路径） */
    private String providerBlocked() {
        return "local".equals(providerName) ? null : "错误：工单 provider " + providerName + " 未实现";
    }

    /** data/tickets/{yyyy-MM}.jsonl（包级可见，测试回放历史日文件用） */
    Path monthFile(LocalDate date) {
        return ticketsDir.resolve(date + ".jsonl");
    }
}
