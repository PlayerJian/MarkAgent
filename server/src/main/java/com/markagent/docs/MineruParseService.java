package com.markagent.docs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.config.MarkAgentProperties;
import com.markagent.config.MarkAgentProperties.Docs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.converter.FormHttpMessageConverter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * MinerU file-parse-server 同步解析客户端（纯 HTTP，无子进程）。
 * <p>
 * 协议（官方 file-parse 文档）：
 * <pre>
 * POST /v1/file_parse（multipart：file + wait_timeout + request_id）
 *   200 → 响应体即产物 ZIP（等待窗口内完成）
 *   202 → StatusResponse {request_id, status, status_url, result_url, error}
 *         status: prepared/submitting/waiting-file/pending/running/completed/failed/canceled/submission_unknown
 *         轮询 GET status_url 至 completed → GET result_url 下载附件
 * </pre>
 * request_id 传我方文档 ID：断连/服务重启后可按 request_id 恢复已知任务。
 */
@Component
public class MineruParseService {

    private static final Logger log = LoggerFactory.getLogger(MineruParseService.class);

    /** 轮询间隔（协议 Retry-After 缺省 2s） */
    private static final long POLL_INTERVAL_MS = 2000;

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int waitTimeoutSeconds;
    private final long overallTimeoutMs;

    public MineruParseService(MarkAgentProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        Docs docs = props.docs() == null ? new Docs(null, 0, 0, 0) : props.docs();
        this.baseUrl = docs.mineruUrl().endsWith("/")
                ? docs.mineruUrl().substring(0, docs.mineruUrl().length() - 1)
                : docs.mineruUrl();
        this.waitTimeoutSeconds = docs.waitTimeoutSeconds();
        this.overallTimeoutMs = TimeUnit.MINUTES.toMillis(docs.overallTimeoutMinutes());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // POST 会在 wait_timeout 窗口内阻塞，读超时须大于它
        factory.setReadTimeout((int) TimeUnit.SECONDS.toMillis(waitTimeoutSeconds + 120L));
        this.rest = new RestTemplate(factory);
    }

    /** 包装服务状态响应（仅取用到的字段） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusResponse(String requestId, String taskId, String status, String statusUrl,
            String resultUrl, ErrorDetail error) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ErrorDetail(String message, String code) {
        }
    }

    /**
     * 解析单个文件，产物 ZIP 落 destZip。
     *
     * @param source        原始文件（存在且可读）
     * @param baseRequestId 我方文档 ID（仅作日志/关联标识）
     * @param destZip       产物 ZIP 落盘路径（父目录须存在）
     * @param onStage       阶段回调（每次状态转换触发一次，用于落库/日志；可为 null）
     */
    public void parse(Path source, String baseRequestId, Path destZip,
            java.util.function.Consumer<String> onStage) throws IOException, InterruptedException {
        // 包装服务的恢复标识必须每次尝试唯一：request_id 会被持久化保留（默认 24h），
        // 复用同一 id 重试会恢复旧任务记录（上游任务丢失时永远 task_not_found），
        // 而不是新建任务。docId 只做前缀，每次尝试带时间戳后缀。
        String requestId = baseRequestId + "-" + System.nanoTime();
        long startedNanos = System.nanoTime();
        long deadline = System.currentTimeMillis() + overallTimeoutMs;
        notifyStage(onStage, "submitting", startedNanos);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(source.toFile()));
        body.add("wait_timeout", String.valueOf(waitTimeoutSeconds));
        body.add("request_id", requestId);

        StatusResponse first;
        try {
            first = rest.execute(
                    baseUrl + "/v1/file_parse",
                    HttpMethod.POST,
                    request -> new FormHttpMessageConverter().write(body, MediaType.MULTIPART_FORM_DATA, request),
                    response -> {
                        HttpStatus status = (HttpStatus) response.getStatusCode();
                        if (status.is2xxSuccessful() && status != HttpStatus.ACCEPTED) {
                            // 200：等待窗口内完成，响应体即附件
                            copyToFile(response.getBody(), destZip);
                            return null;
                        }
                        if (status == HttpStatus.ACCEPTED) {
                            // 202：等待预算耗尽，转入轮询
                            return mapper.readValue(response.getBody(), StatusResponse.class);
                        }
                        throw new IOException("MinerU file_parse 失败 HTTP " + status.value() + ": "
                                + readText(response.getBody(), 2000));
                    });
        } catch (Exception e) {
            // RestTemplate 将 extractor 内的 IOException 包为 ResourceAccessException（非受检），
            // 统一还原为 IOException，保留原始消息（"I/O error ... : <原因>"）
            throw new IOException("MinerU file_parse 请求失败: " + e.getMessage(), e);
        }

        if (first == null) {
            log.info("MinerU 解析同步完成 request_id={} 耗时 {}s", requestId, elapsedSeconds(startedNanos));
            return;
        }

        // 轮询至终态：阶段转换打日志 + 回调（协议状态：prepared/submitting/waiting-file/pending/running/...）
        StatusResponse status = first;
        String lastStage = "submitting";
        while (true) {
            String state = status.status();
            if ("completed".equals(state)) {
                downloadResult(status, requestId, destZip);
                log.info("MinerU 解析轮询完成 request_id={} 耗时 {}s", requestId, elapsedSeconds(startedNanos));
                return;
            }
            if ("failed".equals(state) || "canceled".equals(state) || "submission_unknown".equals(state)) {
                throw new IOException("MinerU 解析失败(" + state + "): " + errorText(status.error()));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("MinerU 解析超时(" + overallTimeoutMs + "ms)，"
                        + "可重试（每次重试新建解析任务）");
            }
            if (!state.equals(lastStage)) {
                log.info("MinerU 解析阶段 request_id={} {} -> {} 已耗时 {}s",
                        requestId, lastStage, state, elapsedSeconds(startedNanos));
                notifyStage(onStage, state, startedNanos);
                lastStage = state;
            }
            Thread.sleep(POLL_INTERVAL_MS);
            status = fetchStatus(status.statusUrl() != null && !status.statusUrl().isBlank()
                    ? status.statusUrl()
                    : baseUrl + "/v1/file_parse/" + requestId);
        }
    }

    private static void notifyStage(java.util.function.Consumer<String> onStage, String stage, long startedNanos) {
        if (onStage == null) {
            return;
        }
        try {
            onStage.accept(stage);
        } catch (Exception e) {
            log.warn("阶段回调异常 stage={}: {}", stage, e.getMessage());
        }
    }

    private static long elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000L;
    }

    /** GET 状态查询（status_url 为包装服务完整 URL） */
    private StatusResponse fetchStatus(String statusUrl) throws IOException {
        try {
            return rest.execute(statusUrl, HttpMethod.GET, null,
                    response -> {
                        if (!((HttpStatus) response.getStatusCode()).is2xxSuccessful()) {
                            throw new IOException("状态查询失败 HTTP "
                                    + response.getStatusCode().value() + ": "
                                    + readText(response.getBody(), 2000));
                        }
                        return mapper.readValue(response.getBody(), StatusResponse.class);
                    });
        } catch (Exception e) {
            throw new IOException("状态查询失败: " + e.getMessage(), e);
        }
    }

    /** 下载产物附件到 destZip（result_url 为空时按 request_id 构造包装服务标准路径） */
    private void downloadResult(StatusResponse status, String requestId, Path destZip) throws IOException {
        String url = status.resultUrl() != null && !status.resultUrl().isBlank()
                ? status.resultUrl()
                : baseUrl + "/v1/file_parse/" + requestId + "/result";
        try {
            rest.execute(url, HttpMethod.GET, null,
                    response -> {
                        if (!((HttpStatus) response.getStatusCode()).is2xxSuccessful()) {
                            throw new IOException("产物下载失败 HTTP " + response.getStatusCode().value());
                        }
                        copyToFile(response.getBody(), destZip);
                        return null;
                    });
        } catch (Exception e) {
            throw new IOException("产物下载失败: " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(destZip) || Files.size(destZip) == 0) {
            throw new IOException("产物下载为空");
        }
    }

    private static String errorText(StatusResponse.ErrorDetail error) {
        return error == null ? "无错误详情" : error.code() + ": " + error.message();
    }

    /** 响应流转存文件（流式，不整包进内存） */
    private static void copyToFile(InputStream in, Path dest) throws IOException {
        try (in; OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    private static String readText(InputStream in, int maxChars) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes(maxChars);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
