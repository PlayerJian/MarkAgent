package com.markagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebhookNotifier 单测：未启用路径错误文本（不发真实网络请求）。
 */
class WebhookNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledReturnsErrorText() {
        WebhookNotifier notifier = new WebhookNotifier(mapper, false, "https://qyapi.weixin.qq.com/x");
        String result = notifier.send("标题", "内容");
        assertTrue(result.contains("通知集成未启用"));
        assertTrue(result.contains("markagent.integration.notify.enabled=false"));
    }

    @Test
    void enabledButEmptyUrlReturnsErrorText() {
        WebhookNotifier notifier = new WebhookNotifier(mapper, true, "");
        assertTrue(notifier.send("标题", "内容").contains("通知集成未启用"));
    }

    @Test
    void enabledWithInvalidUrlReturnsValidationError() {
        WebhookNotifier notifier = new WebhookNotifier(mapper, true, "ftp://example.com/hook");
        assertTrue(notifier.send("标题", "内容").contains("http/https"));
    }
}
