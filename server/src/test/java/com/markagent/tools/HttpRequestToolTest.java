package com.markagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpRequestTool 入口 url 校验单测（不覆盖真实 HTTP 请求）：
 * 基础校验放行/拒绝路径（scheme 白名单、长度、凭据、可解析、主机名）。
 */
class HttpRequestToolTest {

    @Test
    void acceptsValidUrls() {
        assertNull(UrlChecks.validateHttp("https://example.com/api"));
        assertNull(UrlChecks.validateHttp("http://example.com:8080/api?q=1"));
        assertNull(UrlChecks.validateHttp("  https://example.com/x  "));
        // 与 web_fetch 不同：http_get 允许内网/单标签主机（部署环境内 API 场景）
        assertNull(UrlChecks.validateHttp("http://localhost:8080/api"));
    }

    @Test
    void rejectsMissingUrl() {
        assertTrue(UrlChecks.validateHttp(null).contains("缺失"));
        assertTrue(UrlChecks.validateHttp("").contains("为空"));
        assertTrue(UrlChecks.validateHttp("   ").contains("为空"));
    }

    @Test
    void rejectsOversizeUrl() {
        assertTrue(UrlChecks.validateHttp("https://example.com/" + "a".repeat(2000)).contains("过长"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertTrue(UrlChecks.validateHttp("ftp://example.com/f").contains("http/https"));
        assertTrue(UrlChecks.validateHttp("file:///etc/passwd").contains("http/https"));
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertTrue(UrlChecks.validateHttp("https://user:pass@example.com/").contains("用户名/密码"));
    }

    @Test
    void rejectsMissingHost() {
        assertTrue(UrlChecks.validateHttp("https:///path-only").contains("主机名"));
    }

    @Test
    void rejectsUnparseableUrl() {
        assertTrue(UrlChecks.validateHttp("https://exa mple.com/").contains("无法解析"));
    }
}
