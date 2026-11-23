package com.markagent.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConnectRetryGate 单测：可变 Clock 拨表验证 15 分钟 TTL 语义（不联网） */
class ConnectRetryGateTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** 测试用可变时钟：手动拨表模拟时间流逝 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void initiallyDoesNotSkip() {
        ConnectRetryGate gate = new ConnectRetryGate(new MutableClock(START));
        assertFalse(gate.shouldSkip("s1"));
    }

    @Test
    void skipsAfterFailure() {
        ConnectRetryGate gate = new ConnectRetryGate(new MutableClock(START));
        gate.markFailure("s1");
        assertTrue(gate.shouldSkip("s1"));
    }

    @Test
    void skipsWithinTtlAndPassesAfterExpiry() {
        MutableClock clock = new MutableClock(START);
        ConnectRetryGate gate = new ConnectRetryGate(clock);
        gate.markFailure("s1");

        // TTL 内（差 1 秒到 15 分钟）仍跳过
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertTrue(gate.shouldSkip("s1"));
        // 到 15 分钟整：放行
        clock.advance(Duration.ofSeconds(1));
        assertFalse(gate.shouldSkip("s1"));
        // 到期记录已清除：再次查询仍放行
        assertFalse(gate.shouldSkip("s1"));
    }

    @Test
    void markSuccessClearsFailure() {
        MutableClock clock = new MutableClock(START);
        ConnectRetryGate gate = new ConnectRetryGate(clock);
        gate.markFailure("s1");
        gate.markSuccess("s1");
        assertFalse(gate.shouldSkip("s1"));
    }

    @Test
    void serversAreIndependent() {
        ConnectRetryGate gate = new ConnectRetryGate(new MutableClock(START));
        gate.markFailure("s1");
        assertTrue(gate.shouldSkip("s1"));
        assertFalse(gate.shouldSkip("s2"));
    }

    @Test
    void failureCanBeRemarkedAfterExpiry() {
        MutableClock clock = new MutableClock(START);
        ConnectRetryGate gate = new ConnectRetryGate(clock);
        gate.markFailure("s1");
        clock.advance(ConnectRetryGate.TTL);
        assertFalse(gate.shouldSkip("s1")); // 到期放行并清除
        gate.markFailure("s1");              // 再次失败重新入闸
        assertTrue(gate.shouldSkip("s1"));
    }
}
