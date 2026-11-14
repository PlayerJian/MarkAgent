package com.markagent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchTool 入口 query 纯逻辑单测（不覆盖真实搜索引擎抓取）：
 * query 规范化、域名规范化/匹配/过滤、allowed 与 blocked 互斥语义。
 */
class WebSearchToolTest {

    private static WebSearchTool.SearchResult hit(String url) {
        return new WebSearchTool.SearchResult("标题", url, "摘要");
    }

    // ==================== query 规范化 ====================

    @Test
    void normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("deepseek 发布 新模型", WebSearchTool.normalizeQuery("  deepseek\t发布\n新模型  "));
    }

    @Test
    void normalizeRejectsMissingOrTooShort() {
        assertNull(WebSearchTool.normalizeQuery(null));
        assertNull(WebSearchTool.normalizeQuery(""));
        assertNull(WebSearchTool.normalizeQuery("   "));
        assertNull(WebSearchTool.normalizeQuery("a")); // 对齐 z.string().min(2)
        assertNull(WebSearchTool.normalizeQuery(" 一 ")); // 1 字符
    }

    @Test
    void normalizeAcceptsMinLength() {
        assertEquals("ab", WebSearchTool.normalizeQuery("ab"));
        assertEquals("中文", WebSearchTool.normalizeQuery("中文"));
    }

    @Test
    void normalizeTruncatesOversizeQuery() {
        String longQuery = "词 ".repeat(200);
        String normalized = WebSearchTool.normalizeQuery(longQuery);
        assertEquals(300, normalized.length());
        assertTrue(normalized.startsWith("词 "));
    }

    // ==================== 域名规范化 ====================

    @Test
    void cleanDomainsNormalizesAndDeduplicates() {
        assertEquals(List.of("example.com", "wikipedia.org"),
                WebSearchTool.cleanDomains(List.of(" www.Example.COM ", "example.com", "", "wikipedia.org")));
        assertEquals(List.of(), WebSearchTool.cleanDomains(null));
        assertEquals(List.of(), WebSearchTool.cleanDomains(List.of("  ")));
    }

    // ==================== 域名匹配 ====================

    @Test
    void domainMatchesExactAndSubdomain() {
        assertTrue(WebSearchTool.domainMatches("https://example.com/page", "example.com"));
        assertTrue(WebSearchTool.domainMatches("https://docs.example.com/page", "example.com"));
        assertTrue(WebSearchTool.domainMatches("https://www.example.com/page", "example.com"));
        assertTrue(WebSearchTool.domainMatches("https://example.com/page", "www.example.com"));
        assertFalse(WebSearchTool.domainMatches("https://example.com/page", "badexample.com"));
        assertFalse(WebSearchTool.domainMatches("https://notexample.com/page", "example.com"));
    }

    @Test
    void domainMatchesRejectsBadUrl() {
        assertFalse(WebSearchTool.domainMatches("not a url", "example.com"));
        assertFalse(WebSearchTool.domainMatches("https://example.com/page", ""));
    }

    // ==================== 域名过滤 ====================

    @Test
    void filterKeepsOnlyAllowedDomains() {
        List<WebSearchTool.SearchResult> results = List.of(
                hit("https://example.com/a"),
                hit("https://sub.example.com/b"),
                hit("https://other.com/c"));
        List<WebSearchTool.SearchResult> filtered = WebSearchTool.filterByDomains(
                results, List.of("example.com"), List.of());
        assertEquals(2, filtered.size());
        assertEquals("https://example.com/a", filtered.get(0).url());
        assertEquals("https://sub.example.com/b", filtered.get(1).url());
    }

    @Test
    void filterExcludesBlockedDomains() {
        List<WebSearchTool.SearchResult> results = List.of(
                hit("https://example.com/a"),
                hit("https://ads.example.com/b"),
                hit("https://other.com/c"));
        List<WebSearchTool.SearchResult> filtered = WebSearchTool.filterByDomains(
                results, List.of(), List.of("ads.example.com"));
        assertEquals(2, filtered.size());
        assertEquals("https://example.com/a", filtered.get(0).url());
        assertEquals("https://other.com/c", filtered.get(1).url());
    }

    @Test
    void filterNoopWhenNoDomainLists() {
        List<WebSearchTool.SearchResult> results = List.of(hit("https://example.com/a"));
        assertEquals(results, WebSearchTool.filterByDomains(results, List.of(), List.of()));
        assertEquals(results, WebSearchTool.filterByDomains(results, null, null));
    }

    @Test
    void filterEmptyResultListSafe() {
        assertEquals(List.of(), WebSearchTool.filterByDomains(List.of(), List.of("example.com"), List.of()));
        assertEquals(List.of(), WebSearchTool.filterByDomains(null, List.of("example.com"), List.of()));
    }
}
