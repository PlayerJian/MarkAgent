package com.markagent.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web_fetch 的 URL 内容缓存（对齐 Claude Code WebFetch 的 15 分钟自清理缓存）：
 * 同一 URL 在 TTL 内重复抓取直接复用已转换的 markdown，避免重复 HTTP 往返。
 * <p>
 * 实现要点：
 * <ul>
 *   <li>按条数限上限（{@link #MAX_ENTRIES}，内容本身已被 {@link WebFetchTool} 截断到 100K 字符，
 *       条数上限等价于粗粒度内存上限）；超限时淘汰 fetchedAt 最早的一条</li>
 *   <li>TTL 到期在 get 里惰性清除（同 {@link com.markagent.mcp.ConnectRetryGate} 的模式）</li>
 *   <li>全部 synchronized——调用频率低（工具调用路径），简单锁足够；构造注入 {@link Clock} 便于单测拨表</li>
 * </ul>
 */
final class UrlContentCache {

    /** 缓存 TTL：15 分钟（对齐参考实现 CACHE_TTL_MS） */
    static final Duration TTL = Duration.ofMinutes(15);

    /** 缓存条数上限（内容 ≤100K 字符/条，最坏约 12.8M 字符） */
    private static final int MAX_ENTRIES = 128;

    private final Clock clock;
    /** LinkedHashMap 保持插入序：fetchedAt 并列（同刻批量写入）时按先插入先淘汰，保证淘汰确定性 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    UrlContentCache() {
        this(Clock.systemUTC());
    }

    UrlContentCache(Clock clock) {
        this.clock = clock;
    }

    synchronized Entry get(String url) {
        Entry entry = entries.get(url);
        if (entry == null) {
            return null;
        }
        if (!clock.instant().isBefore(entry.fetchedAt().plus(TTL))) {
            entries.remove(url); // TTL 到期：惰性清除
            return null;
        }
        return entry;
    }

    synchronized void put(String url, int code, long bytes, String contentType, String content) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(url)) {
            evictOldest();
        }
        entries.put(url, new Entry(code, bytes, contentType, content, clock.instant()));
    }

    /** 淘汰 fetchedAt 最早的一条（等价于简单 LRU 的退化版，规模小足够用） */
    private void evictOldest() {
        String oldestKey = null;
        Instant oldestAt = null;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (oldestAt == null || entry.getValue().fetchedAt().isBefore(oldestAt)) {
                oldestAt = entry.getValue().fetchedAt();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey);
        }
    }

    /** 一条缓存：HTTP 元信息 + 已转换/截断的内容 */
    record Entry(int code, long bytes, String contentType, String content, Instant fetchedAt) {
    }
}
