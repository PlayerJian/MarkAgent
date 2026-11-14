package com.markagent.tools;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebFetchTool 纯逻辑单测（不覆盖真实 HTTP 抓取）：
 * 入口 URL 校验、http→https 升级、重定向许可判定、content-type 文本判定、markdown 截断、URL 缓存。
 */
class WebFetchToolTest {

    // ==================== 入口 URL 校验（UrlChecks.validateFetch） ====================

    @Test
    void validateFetchAcceptsPublicHttpsUrl() {
        assertNull(UrlChecks.validateFetch("https://example.com/docs/guide"));
        assertNull(UrlChecks.validateFetch("  https://example.com/docs  ")); // 前后空白 trim 后放行
    }

    @Test
    void validateFetchRejectsMissingUrl() {
        assertTrue(UrlChecks.validateFetch(null).contains("缺失"));
        assertTrue(UrlChecks.validateFetch("").contains("为空"));
        assertTrue(UrlChecks.validateFetch("   ").contains("为空"));
    }

    @Test
    void validateFetchRejectsOversizeUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2000);
        assertTrue(UrlChecks.validateFetch(longUrl).contains("过长"));
    }

    @Test
    void validateFetchRejectsNonHttpScheme() {
        assertTrue(UrlChecks.validateFetch("ftp://example.com/file").contains("http/https"));
        assertTrue(UrlChecks.validateFetch("file:///etc/passwd").contains("http/https"));
        assertTrue(UrlChecks.validateFetch("javascript:alert(1)").contains("http/https"));
    }

    @Test
    void validateFetchRejectsCredentialsInUrl() {
        assertTrue(UrlChecks.validateFetch("https://user:pass@example.com/").contains("用户名/密码"));
    }

    @Test
    void validateFetchRejectsNonPublicHost() {
        assertTrue(UrlChecks.validateFetch("http://localhost:8080/api").contains("公网域名"));
        assertTrue(UrlChecks.validateFetch("http://intranet/").contains("公网域名"));
    }

    @Test
    void validateFetchRejectsUnparseableUrl() {
        assertTrue(UrlChecks.validateFetch("https://exa mple.com/").contains("无法解析"));
    }

    // ==================== http → https 升级 ====================

    @Test
    void upgradeHttpToHttps() {
        assertEquals("https://example.com/path?q=1#frag",
                WebFetchTool.upgradeToHttps("http://example.com/path?q=1#frag"));
    }

    @Test
    void upgradeKeepsHttpsUnchanged() {
        assertEquals("https://example.com/a", WebFetchTool.upgradeToHttps("https://example.com/a"));
    }

    // ==================== 重定向许可判定（对齐 isPermittedRedirect） ====================

    @Test
    void redirectWithinSameHostPermitted() {
        // 同 host 路径变化：允许
        assertTrue(WebFetchTool.isPermittedRedirect("https://example.com/a", "https://example.com/b"));
        // 加/去 www：允许
        assertTrue(WebFetchTool.isPermittedRedirect("https://example.com/a", "https://www.example.com/a"));
        assertTrue(WebFetchTool.isPermittedRedirect("https://www.example.com/a", "https://example.com/a"));
    }

    @Test
    void redirectToDifferentHostDenied() {
        assertFalse(WebFetchTool.isPermittedRedirect("https://example.com/a", "https://evil.com/a"));
    }

    @Test
    void redirectChangingProtocolOrPortDenied() {
        assertFalse(WebFetchTool.isPermittedRedirect("https://example.com/a", "http://example.com/a"));
        assertFalse(WebFetchTool.isPermittedRedirect("https://example.com/a", "https://example.com:8443/a"));
    }

    @Test
    void redirectWithCredentialsDenied() {
        assertFalse(WebFetchTool.isPermittedRedirect("https://example.com/a", "https://user:pw@example.com/a"));
    }

    @Test
    void invalidRedirectUrlDenied() {
        assertFalse(WebFetchTool.isPermittedRedirect("https://example.com/a", "not a url"));
    }

    // ==================== content-type 判定 ====================

    @Test
    void textContentTypesAccepted() {
        assertTrue(WebFetchTool.isTextContentType(""));
        assertTrue(WebFetchTool.isTextContentType(null));
        assertTrue(WebFetchTool.isTextContentType("text/html; charset=utf-8"));
        assertTrue(WebFetchTool.isTextContentType("text/plain"));
        assertTrue(WebFetchTool.isTextContentType("application/json"));
        assertTrue(WebFetchTool.isTextContentType("application/xhtml+xml"));
    }

    @Test
    void binaryContentTypesRejected() {
        assertFalse(WebFetchTool.isTextContentType("application/pdf"));
        assertFalse(WebFetchTool.isTextContentType("application/zip"));
        assertFalse(WebFetchTool.isTextContentType("image/png"));
        assertFalse(WebFetchTool.isTextContentType("application/octet-stream"));
    }

    // ==================== markdown 截断 ====================

    @Test
    void truncateMarkdownAt100kChars() {
        String content = "x".repeat(WebFetchTool.MAX_MARKDOWN_CHARS);
        assertEquals(content, WebFetchTool.truncateMarkdown(content));
        String longContent = content + "tail";
        String truncated = WebFetchTool.truncateMarkdown(longContent);
        assertTrue(truncated.startsWith(content));
        assertTrue(truncated.endsWith("[内容过长，已截断]"));
    }

    // ==================== URL 缓存 ====================

    @Test
    void cacheHitWithinTtl() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        UrlContentCache cache = new UrlContentCache(clock);
        cache.put("https://example.com/a", 200, 100, "text/html", "# 标题");
        UrlContentCache.Entry entry = cache.get("https://example.com/a");
        assertTrue(entry != null && entry.content().equals("# 标题") && entry.code() == 200);
    }

    @Test
    void cacheExpiresAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UrlContentCache cache = new UrlContentCache(clock);
        cache.put("https://example.com/a", 200, 100, "text/html", "content");
        assertEquals("content", cache.get("https://example.com/a").content());
        // 拨表越过 15 分钟 TTL：惰性过期
        clock.advance(UrlContentCache.TTL.plusSeconds(1));
        assertNull(cache.get("https://example.com/a"));
    }

    @Test
    void cacheMissForUnknownUrl() {
        assertNull(new UrlContentCache().get("https://never-cached.example.com/"));
    }

    @Test
    void cacheEvictsOldestWhenFull() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        UrlContentCache cache = new UrlContentCache(clock);
        for (int i = 0; i < 200; i++) {
            cache.put("https://example.com/" + i, 200, 1, "text/plain", "c" + i);
        }
        // 最老的 0..71 已被淘汰（容量 128），较新的保留
        assertNull(cache.get("https://example.com/0"));
        assertEquals("c199", cache.get("https://example.com/199").content());
    }

    @Test
    void cacheDurationIs15Minutes() {
        assertEquals(Duration.ofMinutes(15), UrlContentCache.TTL);
    }

    /** 测试用可变时钟：advance 拨表驱动 TTL 惰性过期 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
