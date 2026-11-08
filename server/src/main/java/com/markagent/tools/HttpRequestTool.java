package com.markagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP 请求工具：GET/POST（JSON），仅允许 http/https，超时 15s，响应体截断 8KB。
 */
@Component
public class HttpRequestTool {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(name = "http_get", description = "发起 HTTP GET 请求并返回响应文本（截断 8KB）。用于获取公开网页或 API 数据。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String httpGet(@ToolParam(name = "url", required = true,
            description = "完整 URL，必须以 http:// 或 https:// 开头") String url) {
        return request("GET", url, null);
    }

    @Tool(name = "http_post", description = "发起 HTTP POST 请求（JSON）并返回响应文本（截断 8KB）。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String httpPost(
            @ToolParam(name = "url", required = true,
                    description = "完整 URL，必须以 http:// 或 https:// 开头") String url,
            @ToolParam(name = "body", required = false,
                    description = "JSON 请求体字符串") String body) {
        return request("POST", url, body);
    }

    private static String request(String method, String url, String body) {
        try {
            URI uri = URI.create(url.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return "错误：仅支持 http/https 协议";
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "MarkAgent/0.1")
                    .header("Accept", "text/html,application/json,*/*");
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                String payload = body == null ? "" : body;
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
            }
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (text.length() > 8192) {
                text = text.substring(0, 8192) + "…（截断）";
            }
            return String.format(Locale.ROOT, "HTTP %d%n%s", response.statusCode(), text);
        } catch (Exception e) {
            return "错误：请求失败 - " + e.getMessage();
        }
    }
}
