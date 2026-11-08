package com.markagent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** REST 客户端：统一 baseUrl + Bearer token；非 2xx 解析 ApiError 抛 CliException */
public class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String token; // 可为 null（公开端点）

    public ApiClient(String baseUrl, String token) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    public JsonNode post(String path, Object body) {
        return send(request(path).POST(json(body)).build());
    }

    public JsonNode put(String path, Object body) {
        return send(request(path).PUT(json(body)).build());
    }

    public JsonNode delete(String path) {
        return send(request(path).DELETE().build());
    }

    /** SSE 流：返回响应体输入流（逐行解析由 SseStreamRenderer 完成，读到流关闭为止） */
    public InputStream stream(String path) {
        try {
            HttpResponse<InputStream> resp = http.send(
                    request(path).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw errorOf(resp.statusCode(), readBody(resp.body()));
            }
            return resp.body();
        } catch (IOException e) {
            throw new CliException("连接失败 " + baseUrl + path + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("请求被中断");
        }
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw errorOf(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
            }
            byte[] body = resp.body();
            return body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            throw new CliException("请求失败 " + baseUrl + req.uri().getPath() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("请求被中断");
        }
    }

    private CliException errorOf(int status, String body) {
        try {
            JsonNode err = MAPPER.readTree(body);
            String message = err.hasNonNull("message") ? err.get("message").asText() : body;
            String code = err.hasNonNull("code") ? " [" + err.get("code").asText() + "]" : "";
            String hint = status == 401 ? "\n提示: 请重新运行 markagent login" : "";
            return new CliException("HTTP " + status + code + ": " + message + hint);
        } catch (Exception ignored) {
            return new CliException("HTTP " + status + ": " + (body.isBlank() ? "（无响应体）" : body));
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(30)); // 长任务与 SSE 需要大超时
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private static String readBody(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
