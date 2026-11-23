package com.markagent.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 安全护栏纯函数单测：注释/空白剥离、白名单判定、多语句拒绝、大小写不敏感、非法 SQL 拒绝。
 */
class SqlGuardTest {

    @Test
    void stripsLeadingWhitespaceAndComments() {
        assertEquals("SELECT 1", SqlGuard.stripLeading("SELECT 1"));
        assertEquals("SELECT 1", SqlGuard.stripLeading("  \n\t SELECT 1"));
        assertEquals("SELECT 1", SqlGuard.stripLeading("-- 说明\nSELECT 1"));
        assertEquals("SELECT 1", SqlGuard.stripLeading("# MySQL 注释\nSELECT 1"));
        assertEquals("SELECT 1", SqlGuard.stripLeading("/* 块注释 */ SELECT 1"));
        assertEquals("SELECT 1", SqlGuard.stripLeading("  \n\t-- a\n# b\n/* c */ SELECT 1"));
        assertEquals("", SqlGuard.stripLeading("   "));
        assertEquals("", SqlGuard.stripLeading("-- only comment"));
        assertEquals("", SqlGuard.stripLeading("/* 未闭合注释"));
    }

    @Test
    void acceptsWhitelistKeywords() {
        assertNull(SqlGuard.check("SELECT 1"));
        assertNull(SqlGuard.check("SHOW TABLES"));
        assertNull(SqlGuard.check("DESCRIBE users"));
        assertNull(SqlGuard.check("EXPLAIN SELECT * FROM t"));
        assertNull(SqlGuard.check("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    void isCaseInsensitive() {
        assertNull(SqlGuard.check("select 1"));
        assertNull(SqlGuard.check("SeLeCt 1"));
        assertNull(SqlGuard.check("SHOW tables"));
        assertTrue(SqlGuard.check("DELETE FROM t").contains("仅允许"));
        assertTrue(SqlGuard.check("delete from t").contains("仅允许"));
    }

    @Test
    void rejectsNonWhitelistStatements() {
        for (String sql : new String[] {
                "DELETE FROM t", "UPDATE t SET a=1", "INSERT INTO t VALUES (1)",
                "DROP TABLE t", "ALTER TABLE t ADD c INT", "TRUNCATE TABLE t",
                "MERGE INTO t", "CALL p()", "GRANT SELECT ON t TO u", "CREATE TABLE t (id INT)"}) {
            assertNotNull(SqlGuard.check(sql), "应拒绝: " + sql);
        }
    }

    @Test
    void rejectsKeywordWithoutWordBoundary() {
        assertTrue(SqlGuard.check("SELECTED 1").contains("仅允许"));
        assertTrue(SqlGuard.check("SELECTX FROM t").contains("仅允许"));
    }

    @Test
    void rejectsBlankOrCommentOnly() {
        assertTrue(SqlGuard.check(null).contains("为空"));
        assertTrue(SqlGuard.check("").contains("为空"));
        assertTrue(SqlGuard.check("   \n\t ").contains("为空"));
        assertTrue(SqlGuard.check("-- 注释\n# 注释").contains("为空"));
    }

    @Test
    void rejectsMultipleStatements() {
        assertTrue(SqlGuard.check("SELECT 1; SELECT 2").contains("多语句"));
        assertTrue(SqlGuard.check("SELECT 1;\nDROP TABLE t").contains("多语句"));
        // 尾分号合法
        assertNull(SqlGuard.check("SELECT 1;"));
        assertNull(SqlGuard.check("SELECT 1;  \n\t"));
        // 保守拦截：分号后的尾注释同样拒绝（不豁免）
        assertTrue(SqlGuard.check("SELECT 1; -- done").contains("多语句"));
        // 保守拦截：字符串字面量内的分号同样拦截
        assertTrue(SqlGuard.check("SELECT 'a;b'").contains("多语句"));
    }
}
