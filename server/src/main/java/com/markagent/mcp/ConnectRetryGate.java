package com.markagent.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP 连接失败重试门（Claude Code 工程细节：连接失败认证缓存）。
 * <p>
 * 某 server 连接失败后 {@link #markFailure}，TTL（15 分钟）内 {@link #shouldSkip} 返回 true——
 * 启动期自动连接等无人值守路径据此短路跳过，防止不可达 server 在批量重连场景触发
 * 「连接/认证雪崩」。手动 API connect 是用户显式操作，不受此门限制（总是尝试）；
 * 连接成功经 {@link #markSuccess} 清除失败记录。
 * <p>
 * 实现：id → 最近失败时刻，写多读少、调用频率低，全部 synchronized 简单锁足够；
 * TTL 到期在 shouldSkip 里惰性清除。构造注入 {@link Clock} 便于单测拨表（默认为系统钟）。
 */
public class ConnectRetryGate {

    /** 失败记录 TTL：15 分钟内自动路径跳过重试 */
    public static final Duration TTL = Duration.ofMinutes(15);

    private final Clock clock;
    /** id → 最近一次连接失败时刻 */
    private final Map<String, Instant> failures = new HashMap<>();

    public ConnectRetryGate() {
        this(Clock.systemUTC());
    }

    public ConnectRetryGate(Clock clock) {
        this.clock = clock;
    }

    /** 该 server 是否应跳过自动连接（存在 TTL 内的失败记录） */
    public synchronized boolean shouldSkip(String id) {
        Instant failedAt = failures.get(id);
        if (failedAt == null) {
            return false;
        }
        if (!clock.instant().isBefore(failedAt.plus(TTL))) {
            failures.remove(id); // TTL 到期：清除记录并放行
            return false;
        }
        return true;
    }

    /** 记录一次连接失败 */
    public synchronized void markFailure(String id) {
        failures.put(id, clock.instant());
    }

    /** 连接成功：清除失败记录 */
    public synchronized void markSuccess(String id) {
        failures.remove(id);
    }
}
