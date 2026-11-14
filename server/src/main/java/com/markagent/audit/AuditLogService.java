package com.markagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.store.AtomicJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用审计日志：每次工具调用落一条 JSON 行到 data/audit/{yyyy-MM-dd}.jsonl（按 createdAt 本地日期分文件）。
 * <ul>
 *   <li>写入：按日期文件加锁串行追加单行 JSON（不同日期互不阻塞），复用 AtomicJsonFile 底座；
 *       审计落盘绝不影响主流程——enabled=false 直接返回，任何异常内部捕获仅 log.warn，绝不抛出</li>
 *   <li>查询：按日期范围扫描 jsonl 逐行反序列化过滤，createdAt 降序分页；文件不存在/坏行跳过</li>
 * </ul>
 */
@Component
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 查询时间窗缺省：最近 7 天（单侧缺省时以给定侧外推 7 天） */
    static final int DEFAULT_QUERY_DAYS = 7;
    /** 查询时间窗上限（超出时收缩到最近 31 天，保留 to 端） */
    static final int MAX_QUERY_DAYS = 31;
    /** 分页 limit 默认值 */
    static final int DEFAULT_LIMIT = 100;
    /** 分页 limit 上限 */
    static final int MAX_LIMIT = 1000;

    private final ObjectMapper mapper;
    private final Path auditDir;
    private final boolean enabled;
    /** 每日期文件一把锁：同一天串行追加，不同天并行 */
    private final Map<String, Object> dayLocks = new ConcurrentHashMap<>();

    public AuditLogService(ObjectMapper mapper, Path storageRoot,
            @Value("${markagent.audit.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.auditDir = storageRoot.resolve("audit");
        this.enabled = enabled;
    }

    /**
     * 记录一次工具调用审计。签名稳定、可安全并发调用：
     * 任何失败（序列化/落盘）只记 warn 不影响主流程；enabled=false 直接返回。
     */
    public void recordToolCall(AuditRecord record) {
        if (!enabled || record == null) {
            return;
        }
        if (record.createdAt() == null) {
            log.warn("审计记录缺少 createdAt，跳过 toolCallId={}", record.toolCallId());
            return;
        }
        String fileName = fileNameOf(record.createdAt());
        Object lock = dayLocks.computeIfAbsent(fileName, k -> new Object());
        synchronized (lock) {
            try {
                new AtomicJsonFile(auditDir.resolve(fileName)).appendLine(mapper.writeValueAsString(record));
            } catch (Exception e) {
                log.warn("审计落盘失败 toolCallId={}: {}", record.toolCallId(), e.getMessage());
            }
        }
    }

    /**
     * 查询审计记录：userId 过滤（null 不过滤，Controller 固定传当前登录用户），taskId/toolName 可选过滤；
     * from/to 为 ISO-8601 Instant，缺省最近 7 天到今天，范围上限 31 天（超出收缩到最近 31 天）；
     * 结果按 createdAt 降序，limit 默认 100 上限 1000，total 为过滤后总条数（不受分页影响）。
     */
    public AuditQueryResult query(String userId, String taskId, String toolName,
            Instant from, Instant to, int limit, int offset) {
        Instant[] window = resolveWindow(from, to);
        Instant fromEff = window[0];
        Instant toEff = window[1];
        int limitEff = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int offsetEff = Math.max(0, offset);

        List<AuditRecord> matched = new ArrayList<>();
        for (Path file : dayFiles(fromEff, toEff)) {
            readFile(file, userId, taskId, toolName, fromEff, toEff, matched);
        }
        matched.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        int fromIndex = Math.min(offsetEff, matched.size());
        int toIndex = (int) Math.min((long) offsetEff + limitEff, matched.size());
        return new AuditQueryResult(matched.size(), new ArrayList<>(matched.subList(fromIndex, toIndex)));
    }

    /** 时间窗解析：单侧缺省外推 7 天，双侧缺省最近 7 天到今天；超 31 天收缩（保留 to 端，最近数据更有价值） */
    private static Instant[] resolveWindow(Instant from, Instant to) {
        Instant toEff = to != null ? to : (from != null ? from.plus(Duration.ofDays(DEFAULT_QUERY_DAYS)) : Instant.now());
        Instant fromEff = from != null ? from : toEff.minus(Duration.ofDays(DEFAULT_QUERY_DAYS));
        Instant earliest = toEff.minus(Duration.ofDays(MAX_QUERY_DAYS));
        if (fromEff.isBefore(earliest)) {
            fromEff = earliest;
        }
        return new Instant[] { fromEff, toEff };
    }

    /** 时间窗覆盖的日期文件列表（本地日期，含首尾两天；from>to 时自然为空） */
    private List<Path> dayFiles(Instant from, Instant to) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = from.atZone(zone).toLocalDate();
        LocalDate end = to.atZone(zone).toLocalDate();
        List<Path> files = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            files.add(auditDir.resolve(d + ".jsonl"));
        }
        return files;
    }

    /** 逐行反序列化 + 过滤；文件不存在/坏行跳过（只 warn，不抛出） */
    private void readFile(Path file, String userId, String taskId, String toolName,
            Instant from, Instant to, List<AuditRecord> out) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    AuditRecord r = mapper.readValue(line, AuditRecord.class);
                    if (r.createdAt() == null
                            || r.createdAt().isBefore(from) || r.createdAt().isAfter(to)) {
                        continue;
                    }
                    if (userId != null && !userId.equals(r.userId())) {
                        continue;
                    }
                    if (taskId != null && !taskId.equals(r.taskId())) {
                        continue;
                    }
                    if (toolName != null && !toolName.equals(r.toolName())) {
                        continue;
                    }
                    out.add(r);
                } catch (IOException e) {
                    log.warn("审计行解析失败，跳过 {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("审计文件读取失败 {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** createdAt 的本地日期文件名：{yyyy-MM-dd}.jsonl */
    private static String fileNameOf(Instant createdAt) {
        return createdAt.atZone(ZoneId.systemDefault()).toLocalDate() + ".jsonl";
    }
}
