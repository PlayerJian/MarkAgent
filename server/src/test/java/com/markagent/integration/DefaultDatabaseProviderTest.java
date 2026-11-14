package com.markagent.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultDatabaseProvider 单测：未启用路径错误文本、护栏先行、缺驱动文案、前缀→驱动推导。
 * 不发真实数据库连接（classpath 无 JDBC 驱动，缺驱动路径恰好可确定性断言）。
 */
class DefaultDatabaseProviderTest {

    @Test
    void disabledReturnsErrorText() {
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(false, "", "", "");
        String result = provider.query("SELECT 1");
        assertTrue(result.contains("数据库集成未启用"));
        assertTrue(result.contains("markagent.integration.database.enabled=false"));
    }

    @Test
    void enabledButEmptyUrlReturnsErrorText() {
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(true, "  ", "u", "p");
        assertTrue(provider.query("SELECT 1").contains("数据库集成未启用"));
    }

    @Test
    void guardRunsBeforeConnecting() {
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(true, "jdbc:mysql://localhost/db", "u", "p");
        assertTrue(provider.query("DELETE FROM t").contains("仅允许"));
        assertTrue(provider.query("SELECT 1; SELECT 2").contains("多语句"));
    }

    @Test
    void missingDriverReturnsClearError() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            // classpath 已有 mysql 驱动：跳过缺驱动文案断言（会真实尝试连接）
            Assumptions.assumeTrue(false, "classpath 已含 mysql 驱动，跳过缺驱动错误测试");
        } catch (ClassNotFoundException expected) {
            // 期望路径：缺驱动 → 明确错误文案
        }
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(
                true, "jdbc:mysql://localhost:3306/db", "u", "p");
        String result = provider.query("SELECT 1");
        assertTrue(result.contains("缺少 JDBC 驱动"));
        assertTrue(result.contains("com.mysql.cj.jdbc.Driver"));
    }

    @Test
    void driverForMapsKnownPrefixes() {
        assertEquals("com.mysql.cj.jdbc.Driver", DefaultDatabaseProvider.driverFor("jdbc:mysql://h/db"));
        assertEquals("com.mysql.cj.jdbc.Driver", DefaultDatabaseProvider.driverFor("JDBC:MYSQL://h/db"));
        assertEquals("org.postgresql.Driver", DefaultDatabaseProvider.driverFor("jdbc:postgresql://h/db"));
        assertEquals("org.h2.Driver", DefaultDatabaseProvider.driverFor("jdbc:h2:mem:test"));
        assertNull(DefaultDatabaseProvider.driverFor("jdbc:unknown://h/db"));
    }
}
