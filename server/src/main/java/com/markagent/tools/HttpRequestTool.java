package com.markagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP 请求工具：GET/POST（JSON），仅允许 http/https，超时 15s，响应体截断 8KB。
 * <p>
 * 入口校验对齐 Claude Code WebFetch 的 validateURL（见 {@link UrlChecks#validateHttp}）：
 * 长度 ≤2000、可解析、无用户名/密码、scheme 白名单；错误消息具体到校验项，便于模型修正重试。
 */
@Component
public class HttpRequestTool {

    /** POST 请求体上限：1MB（防无界请求体滥用） */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /** 响应文本截断长度 */
    private static final int MAX_RESPONSE_CHARS = 8192;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(name = "http_get", description = "发起 HTTP GET 请求并返回响应文本（截断 8KB）。用于获取公开网页或 API 数据。"
            + "url 需为完整 http/https URL，最长 2000 字符，不允许携带用户名/密码。"
            + "抓取网页正文并按要求提炼内容请优先用 web_fetch。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String httpGet(@ToolParam(name = "url", required = true,
            description = "完整 URL，必须以 http:// 或 https:// 开头，最长 2000 字符，不含用户名/密码") String url) {
        return request("GET", url, null);
    }

    @Tool(name = "http_post", description = "发起 HTTP POST 请求（JSON）并返回响应文本（截断 8KB）。"
            + "url 校验同 http_get；body 为 JSON 字符串，最长 1MB。",
            strict = true, readOnly = false, concurrencySafe = true)
    public String httpPost(
            @ToolParam(name = "url", required = true,
                    description = "完整 URL，必须以 http:// 或 https:// 开头，最长 2000 字符，不含用户名/密码") String url,
            @ToolParam(name = "body", required = false,
                    description = "JSON 请求体字符串，最长 1MB") String body) {
        return request("POST", url, body);
    }

    private static String request(String method, String url, String body) {
        String validation = UrlChecks.validateHttp(url);
        if (validation != null) {
            return validation;
        }
        String target = url.trim();
        if ("POST".equals(method) && body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return "错误：请求体超过 1MB 上限";
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
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
            if (text.length() > MAX_RESPONSE_CHARS) {
                text = text.substring(0, MAX_RESPONSE_CHARS) + "…（截断）";
            }
            return String.format(Locale.ROOT, "HTTP %d%n%s", response.statusCode(), text);
        } catch (Exception e) {
            return "错误：请求失败 - " + e.getMessage();
        }
    }
}
