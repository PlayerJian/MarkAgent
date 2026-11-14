package com.markagent.audit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuditLogService 单测（@TempDir 存储根，不碰真实 data/）：
 * 写入→查询往返、userId/taskId/toolName 过滤、按日分文件、enabled=false 不写、limit/offset 分页、超长截断、坏行跳过。
 */
class AuditLogServiceTest {

    @TempDir
    Path tmp;

    /** 与 JsonConfig 同款配置：jsr310 + ISO 时间 + 宽容反序列化 */
    private static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private static AuditLogService newService(Path root, boolean enabled) {
        return new AuditLogService(newMapper(), root, enabled);
    }

    /** 以本地日期正午构造 Instant：任意时区下 createdAt 回算的本地日期都恰为该日 */
    private static Instant atNoon(LocalDate d) {
        return d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static AuditRecord rec(String taskId, String userId, String toolCallId, String toolName,
            Instant createdAt, boolean ok) {
        return new AuditRecord(taskId, "s_" + taskId, userId, toolCallId, toolName,
                "{\"query\":\"天气\"}", ok, "结果摘要 " + toolCallId, 123, createdAt);
    }

    private Path auditDir(Path root) {
        return root.toAbsolutePath().normalize().resolve("audit");
    }

    // ==================== 写入 → 查询往返 ====================

    @Test
    void writeQueryRoundTrip() {
        AuditLogService service = newService(tmp.resolve("r1"), true);
        Instant t1 = Instant.now().minusSeconds(60);
        Instant t2 = Instant.now();
        service.recordToolCall(rec("t_1", "u1", "c_1", "web_search", t1, true));
        service.recordToolCall(rec("t_1", "u1", "c_2", "web_search", t2, true));

        AuditQueryResult result = service.query("u1", null, null, null, null, 100, 0);
        assertEquals(2, result.total());
        assertEquals(2, result.records().size());
        // createdAt 降序：c_2（t2 更新）在前
        assertEquals("c_2", result.records().get(0).toolCallId());
        assertEquals("c_1", result.records().get(1).toolCallId());
        AuditRecord first = result.records().get(0);
        assertEquals("u1", first.userId());
        assertEquals("t_1", first.taskId());
        assertEquals("s_t_1", first.sessionId());
        assertEquals("web_search", first.toolName());
        assertEquals("{\"query\":\"天气\"}", first.args());
        assertTrue(first.ok());
        assertEquals("结果摘要 c_2", first.resultSummary());
        assertEquals(123, first.durationMs());
        assertEquals(t2, first.createdAt());
    }

    // ==================== userId/taskId/toolName 过滤 ====================

    @Test
    void filterByUserIdTaskIdAndToolName() {
        AuditLogService service = newService(tmp.resolve("r2"), true);
        Instant t = Instant.now();
        service.recordToolCall(rec("t_1", "u1", "c_1", "web_search", t, true));
        service.recordToolCall(rec("t_2", "u1", "c_2", "web_search", t, true));
        service.recordToolCall(rec("t_1", "u2", "c_3", "web_search", t, true));
        service.recordToolCall(rec("t_1", "u1", "c_4", "calculator", t, false));

        assertEquals(3, service.query("u1", null, null, null, null, 100, 0).total());
        assertEquals(2, service.query("u1", "t_1", null, null, null, 100, 0).total());
        AuditQueryResult byTool = service.query("u1", "t_1", "web_search", null, null, 100, 0);
        assertEquals(1, byTool.total());
        assertEquals("c_1", byTool.records().get(0).toolCallId());
        assertEquals(1, service.query("u2", null, null, null, null, 100, 0).total());
        assertEquals(0, service.query("u3", null, null, null, null, 100, 0).total());
    }

    // ==================== 按日分文件 ====================

    @Test
    void splitsFilesByLocalDateOfCreatedAt() {
        AuditLogService service = newService(tmp.resolve("r3"), true);
        LocalDate d1 = LocalDate.of(2026, 11, 1);
        LocalDate d2 = LocalDate.of(2026, 11, 2);
        service.recordToolCall(rec("t_1", "u1", "c_1", "web_search", atNoon(d1), true));
        service.recordToolCall(rec("t_1", "u1", "c_2", "web_search", atNoon(d2), true));

        Path dir = auditDir(tmp.resolve("r3"));
        assertTrue(Files.exists(dir.resolve("2026-11-01.jsonl")));
        assertTrue(Files.exists(dir.resolve("2026-11-02.jsonl")));

        // 时间窗限定到 d1 当天：只回 c_1
        Instant from = d1.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant toD1 = d1.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
        AuditQueryResult result = service.query("u1", null, null, from, toD1, 100, 0);
        assertEquals(1, result.total());
        assertEquals("c_1", result.records().get(0).toolCallId());

        // 覆盖两天：两条都在
        Instant toD2 = d2.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
        assertEquals(2, service.query("u1", null, null, from, toD2, 100, 0).total());
    }

    // ==================== enabled=false 不写 ====================

    @Test
    void disabledServiceWritesNothingButQueryStillSafe() {
        Path root = tmp.resolve("r4");
        AuditLogService service = newService(root, false);
        service.recordToolCall(rec("t_1", "u1", "c_1", "web_search", Instant.now(), true));

        assertFalse(Files.exists(auditDir(root)));
        AuditQueryResult result = service.query("u1", null, null, null, null, 100, 0);
        assertEquals(0, result.total());
        assertTrue(result.records().isEmpty());
    }

    // ==================== limit/offset 分页 ====================

    @Test
    void limitAndOffsetPagination() {
        AuditLogService service = newService(tmp.resolve("r5"), true);
        Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            service.recordToolCall(rec("t_1", "u1", "c_" + i, "web_search", base.minusSeconds(i * 10L), true));
        }
        // 降序：c_0 最新 → c_4 最旧
        AuditQueryResult page = service.query("u1", null, null, null, null, 2, 1);
        assertEquals(5, page.total());
        assertEquals(List.of("c_1", "c_2"), page.records().stream().map(AuditRecord::toolCallId).toList());
        // offset 越界：空页但 total 不变
        AuditQueryResult beyond = service.query("u1", null, null, null, null, 2, 10);
        assertEquals(5, beyond.total());
        assertTrue(beyond.records().isEmpty());
    }

    // ==================== 截断与坏行 ====================

    @Test
    void recordTruncatesArgsAndSummary() {
        AuditRecord r = new AuditRecord("t", "s", "u", "c", "tool",
                "a".repeat(3000), true, "b".repeat(800), 1, Instant.now());
        assertEquals(2000, r.args().length());
        assertEquals(500, r.resultSummary().length());
        // 未超限保持完整，null 安全
        AuditRecord small = new AuditRecord("t", "s", "u", "c", "tool",
                "short", true, null, 1, Instant.now());
        assertEquals("short", small.args());
        assertNull(small.resultSummary());
    }

    @Test
    void skipsCorruptLines() throws Exception {
        Path root = tmp.resolve("r6");
        AuditLogService service = newService(root, true);
        service.recordToolCall(rec("t_1", "u1", "c_1", "web_search", Instant.now(), true));
        // 手工追加一行坏 JSON：查询应跳过它且不影响正常行
        Path dayFile = Files.list(auditDir(root)).findFirst().orElseThrow();
        Files.writeString(dayFile, "{not valid json\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        AuditQueryResult result = service.query("u1", null, null, null, null, 100, 0);
        assertEquals(1, result.total());
        assertEquals("c_1", result.records().get(0).toolCallId());
    }
}
