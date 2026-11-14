package com.markagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.tools.UrlChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * IM 通知默认实现：通用 webhook POST JSON（JDK java.net.http.HttpClient，超时 10 秒）。
 * <p>
 * v1 固定企微风格 text 报文结构：
 * <pre>{"msgtype":"text","text":{"content":"标题\n正文"}}</pre>
 * 企微群机器人 webhook 原生支持该结构；钉钉 webhook 需自建桥接（加签/关键字校验、
 * at 人字段等差异不在本实现覆盖范围内），接入钉钉时替换本 Bean 或架设转发桥。
 * <p>
 * 配置键：
 * <ul>
 *   <li>markagent.integration.notify.enabled（默认 false）</li>
 *   <li>markagent.integration.notify.webhook-url（默认空，企微/钉钉通用 webhook 地址）</li>
 * </ul>
 * 未启用或 webhook-url 为空 → 返回明确错误文本（不抛异常）。
 */
@Component
public class WebhookNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String webhookUrl;

    public WebhookNotifier(ObjectMapper mapper,
            @Value("${markagent.integration.notify.enabled:false}") boolean enabled,
            @Value("${markagent.integration.notify.webhook-url:}") String webhookUrl) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @Override
    public String send(String title, String content) {
        if (!enabled || webhookUrl.isEmpty()) {
            return "错误：通知集成未启用（markagent.integration.notify.enabled=false，"
                    + "或 markagent.integration.notify.webhook-url 未配置），请配置后重试";
        }
        String validation = UrlChecks.validateHttp(webhookUrl);
        if (validation != null) {
            return validation;
        }
        try {
            String text = title == null || title.isBlank() ? content : title + "\n" + content;
            Map<String, Object> payload = Map.of("msgtype", "text", "text", Map.of("content", text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return "通知已发送（HTTP " + response.statusCode() + "）";
            }
            String body = response.body() == null ? "" : response.body();
            return "错误：通知发送失败 - HTTP " + response.statusCode() + " "
                    + body.replace('\n', ' ').trim();
        } catch (Exception e) {
            log.warn("通知发送失败: {}", e.getMessage());
            return "错误：通知发送失败 - " + e.getMessage();
        }
    }
}
