package com.markagent.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.store.AtomicJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 会话信箱（跨会话消息队列）：send_message 写入，目标会话任务启动时注入投递。
 * <ul>
 *   <li>消息落盘 data/inbox/{userId}/{sessionId}.jsonl，每行一条 {@link InboxMessage}；
 *       seq = 该文件当前行数 + 1（首次为 1）。信箱只增不删，seq 依赖行序保持稳定</li>
 *   <li>投递游标 data/inbox/{userId}/{sessionId}.cursor：记录已投递的最大 seq，
 *       文件不存在视为 0（即全部未投递）。游标只前进不后退</li>
 *   <li>{@link #pending} 是幂等只读查询：只返回 seq &gt; 游标的未投递消息（按 seq 升序），
 *       重复调用不会「消费」消息——是否投递完全由 {@link #markDelivered} 的游标推进决定</li>
 *   <li>并发安全：每信箱一把进程内锁（enqueue/pending/markDelivered 同锁互斥）；
 *       所有 IO 异常内部捕获（记 warn，返回空 / -1），不影响主流程</li>
 * </ul>
 * 集成约定：任务启动时由 PromptAssembler 调用 {@link #pending} 取未投递消息注入首条
 * user 消息，注入成功后以「本次 pending 返回的最大 seq」调用 {@link #markDelivered}；
 * 未调用 markDelivered 前，每次任务启动都会重复拿到同一批消息。
 */
@Component
public class SessionInboxService {

    private static final Logger log = LoggerFactory.getLogger(SessionInboxService.class);

    /** 无用户上下文时的占位用户（与 JsonFileSessionStore 的 userId 默认规则一致） */
    private static final String LOCAL_USER = "local-user";

    private final Path inboxDir;
    private final ObjectMapper mapper;
    /** 每信箱一把锁：key = userId/sessionId（进程内同步，风格同 AtomicJsonFile 但按信箱细粒度） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SessionInboxService(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.inboxDir = storageRoot.resolve("inbox");
    }

    /**
     * 追加一条消息到目标会话信箱。入参 message 的 seq 字段忽略，由本方法按文件当前行数
     * 分配（首次为 1）。返回分配的 seq；IO 失败返回 -1（不抛出）。
     */
    public long enqueue(String userId, String sessionId, InboxMessage message) {
        String uid = userId == null ? LOCAL_USER : userId;
        synchronized (lockOf(uid, sessionId)) {
            Path file = fileOf(uid, sessionId);
            long count = lineCount(file);
            if (count < 0) {
                // 行数统计失败则放弃本次投递，避免 seq 冲突（seq 必须严格递增）
                return -1;
            }
            long seq = count + 1;
            InboxMessage withSeq = new InboxMessage(seq, message.fromUserId(), message.fromTaskId(),
                    message.content(), message.createdAt() == null ? Instant.now() : message.createdAt());
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, mapper.writeValueAsString(withSeq) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                return seq;
            } catch (IOException e) {
                log.warn("信箱消息落盘失败 {}/{}: {}", uid, sessionId, e.getMessage());
                return -1;
            }
        }
    }

    /**
     * 读全部未投递消息（seq &gt; 投递游标，按 seq 升序）。幂等只读：不推进游标、不改文件，
     * 未 markDelivered 前重复调用返回同一批消息。IO 异常内部捕获（返回空）。
     */
    public List<InboxMessage> pending(String userId, String sessionId) {
        String uid = userId == null ? LOCAL_USER : userId;
        synchronized (lockOf(uid, sessionId)) {
            long cursor = readCursor(uid, sessionId);
            Path file = fileOf(uid, sessionId);
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            List<InboxMessage> out = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        InboxMessage msg = mapper.readValue(line, InboxMessage.class);
                        if (msg.seq() > cursor) {
                            out.add(msg);
                        }
                    } catch (IOException e) {
                        log.warn("信箱行解析失败 {}/{}: {}", uid, sessionId, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("信箱读取失败 {}/{}: {}", uid, sessionId, e.getMessage());
                return List.of();
            }
            out.sort(Comparator.comparingLong(InboxMessage::seq));
            return out;
        }
    }

    /**
     * 推进投递游标到 uptoSeq（只前进不后退：游标取当前值与 uptoSeq 的较大者）。
     * 调用时机：任务启动并把 {@link #pending} 返回的消息成功注入首条 user 消息之后，
     * 以「本次 pending 返回的最大 seq」调用本方法；此后 pending 不再返回这批消息。
     * IO 异常内部捕获（记 warn，下次启动重新投递）。
     */
    public void markDelivered(String userId, String sessionId, long uptoSeq) {
        if (uptoSeq <= 0) {
            return;
        }
        String uid = userId == null ? LOCAL_USER : userId;
        synchronized (lockOf(uid, sessionId)) {
            long current = readCursor(uid, sessionId);
            if (uptoSeq <= current) {
                return;
            }
            try {
                new AtomicJsonFile(cursorFileOf(uid, sessionId)).writeString(String.valueOf(uptoSeq));
            } catch (IOException e) {
                log.warn("投递游标写入失败 {}/{}: {}", uid, sessionId, e.getMessage());
            }
        }
    }

    private Object lockOf(String userId, String sessionId) {
        return locks.computeIfAbsent(userId + "/" + sessionId, k -> new Object());
    }

    private Path fileOf(String userId, String sessionId) {
        return inboxDir.resolve(userId).resolve(sessionId + ".jsonl");
    }

    private Path cursorFileOf(String userId, String sessionId) {
        return inboxDir.resolve(userId).resolve(sessionId + ".cursor");
    }

    /** 游标文件不存在视为 0（全部未投递）；读失败也按 0 处理（下次启动重复投递，宁重勿漏） */
    private long readCursor(String userId, String sessionId) {
        Path file = cursorFileOf(userId, sessionId);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            log.warn("投递游标读取失败 {}/{}: {}", userId, sessionId, e.getMessage());
            return 0;
        }
    }

    /** 文件行数（不存在为 0）；读取失败返回 -1（调用方放弃投递，避免 seq 冲突） */
    private long lineCount(Path file) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (IOException e) {
            log.warn("信箱行数统计失败 {}: {}", file, e.getMessage());
            return -1;
        }
    }
}
