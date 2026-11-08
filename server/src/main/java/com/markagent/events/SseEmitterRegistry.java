package com.markagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.common.sse.SseEvent;
import com.markagent.common.sse.SseEventType;
import com.markagent.store.AtomicJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每任务的 SSE 订阅者登记表 + 事件广播中枢（带断线回放）。
 * <ul>
 *   <li>seq：每任务独立单调递增，作为回放锚点（GET /tasks/{id}/stream?afterSeq=）</li>
 *   <li>每个订阅者维护位置游标 position：广播仅投递 seq &gt; position 的事件并推进游标；
 *       回放从 jsonl 读历史事件，同样经游标判重 → 与实时广播无竞态、无重复、无丢失</li>
 *   <li>广播先落盘 data/events/{taskId}.jsonl 再推送（盘失败不影响推送）</li>
 * </ul>
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** 订阅者 = emitter + 位置游标（已投递的最大 seq） */
    private record Subscriber(SseEmitter emitter, AtomicLong position) {
    }

    private final Map<String, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();
    private final Map<String, AtomicJsonFile> logs = new ConcurrentHashMap<>();
    /** 每任务最近一次事件类型：终态后新订阅者回放完立即关流 */
    private final Map<String, SseEventType> lastTypes = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path eventsDir;

    public SseEmitterRegistry(ObjectMapper mapper, Path storageRoot) {
        this.mapper = mapper;
        this.eventsDir = storageRoot.resolve("events");
    }

    /**
     * 登记订阅者并回放 afterSeq 之后的历史事件（同步完成后再返回 emitter）。
     * afterSeq<=0 时仅订阅实时流。游标机制保证回放与实时广播之间无重复/无丢失。
     */
    public SseEmitter addAndReplay(String taskId, SseEmitter emitter, long afterSeq) {
        AtomicLong position = new AtomicLong(Math.max(0, afterSeq));
        Subscriber subscriber = new Subscriber(emitter, position);

        emitter.onCompletion(() -> remove(taskId, subscriber));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(taskId, subscriber);
        });
        emitter.onError(e -> remove(taskId, subscriber));
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(subscriber);

        // 回放历史（seq > 当前位置）
        if (afterSeq >= 0) {
            replayFromLog(taskId, subscriber);
        }
        // 任务已终态（回放含 DONE/ERROR/PAUSED）：立即关流，避免客户端空等超时
        SseEventType last = lastTypes.get(taskId);
        if (last != null && isTerminal(last)) {
            emitter.complete();
            remove(taskId, subscriber);
        }
        return emitter;
    }

    /** 从 jsonl 回放 seq 大于订阅者游标的事件（游标判重天然消除竞态） */
    private void replayFromLog(String taskId, Subscriber subscriber) {
        Path logPath = eventsDir.resolve(taskId + ".jsonl");
        if (!Files.exists(logPath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(logPath)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    SseEvent event = mapper.readValue(line, SseEvent.class);
                    sendIfNewer(taskId, subscriber, event);
                } catch (IOException e) {
                    log.warn("回放行解析失败 task={}: {}", taskId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("回放读取失败 task={}: {}", taskId, e.getMessage());
        }
    }

    /** 读取任务全量事件（记录查看用）：data/events/{taskId}.jsonl 按 seq 升序解析 */
    public List<SseEvent> readAll(String taskId) {
        Path logPath = eventsDir.resolve(taskId + ".jsonl");
        if (!Files.exists(logPath)) {
            return List.of();
        }
        List<SseEvent> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logPath)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(mapper.readValue(line, SseEvent.class));
                } catch (IOException e) {
                    log.warn("回放行解析失败 task={}: {}", taskId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("回放读取失败 task={}: {}", taskId, e.getMessage());
        }
        return out;
    }

    public void remove(String taskId, Subscriber subscriber) {
        CopyOnWriteArrayList<Subscriber> list = subscribers.get(taskId);
        if (list != null) {
            list.remove(subscriber);
        }
    }

    public long nextSeq(String taskId) {
        return seqs.computeIfAbsent(taskId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 广播：seq 注入 + jsonl 落盘 + 按订阅者游标投递 */
    public void broadcast(String taskId, SseEventType type, Object data) {
        long seq = nextSeq(taskId);
        SseEvent event = new SseEvent(seq, type, java.time.Instant.now().toString(), taskId,
                mapper.valueToTree(data == null ? Map.of() : data));

        // 落盘（先于推送，保证回放流与实时流一致）
        AtomicJsonFile eventLog = logs.computeIfAbsent(taskId, k -> new AtomicJsonFile(eventsDir.resolve(taskId + ".jsonl")));
        lastTypes.put(taskId, type);
        try {
            eventLog.appendLine(mapper.writeValueAsString(event));
        } catch (IOException e) {
            SseEmitterRegistry.log.warn("事件落盘失败 task={}: {}", taskId, e.getMessage());
        }

        // 投递
        CopyOnWriteArrayList<Subscriber> list = subscribers.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : list) {
            sendIfNewer(taskId, subscriber, event);
        }
        // 终态事件投递后关闭订阅流：客户端（web/cli）以此为流结束信号；后续事件（resume 续跑）经 jsonl 回放
        if (isTerminal(type)) {
            completeSubscribers(taskId);
        }
    }

    private static boolean isTerminal(SseEventType type) {
        return type == SseEventType.DONE || type == SseEventType.ERROR || type == SseEventType.PAUSED;
    }

    /** 关闭任务的全部订阅流（移除登记并 complete 各 emitter） */
    private void completeSubscribers(String taskId) {
        CopyOnWriteArrayList<Subscriber> list = subscribers.remove(taskId);
        if (list != null) {
            for (Subscriber subscriber : list) {
                subscriber.emitter().complete();
            }
        }
    }

    /** 事件 seq 大于订阅者游标才投递，并推进游标（幂等去重） */
    private void sendIfNewer(String taskId, Subscriber subscriber, SseEvent event) {
        AtomicLong position = subscriber.position();
        synchronized (position) {
            if (event.seq() <= position.get()) {
                return;
            }
            try {
                subscriber.emitter().send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .name(event.type().name())
                        .data(mapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
                position.set(event.seq());
            } catch (IOException | IllegalStateException e) {
                // 单个订阅者断线：摘除，不影响其他订阅者
                remove(taskId, subscriber);
                subscriber.emitter().complete();
            }
        }
    }

    /** 任务结束后清理内存登记（jsonl 文件保留供回放） */
    public void cleanup(String taskId) {
        subscribers.remove(taskId);
        seqs.remove(taskId);
    }

    /** 标准 SSE 订阅超时：长任务 10 分钟无事件视为断线（客户端重连后经 afterSeq 回放） */
    public static SseEmitter newEmitter() {
        return new SseEmitter(Duration.ofMinutes(10).toMillis());
    }
}
