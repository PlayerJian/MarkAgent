package com.markagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markagent.agent.model.TaskIdContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 视觉理解工具：把图片交给视觉大模型（默认 deepseek-flash，可配 qwen-vl 等任何
 * OpenAI 兼容端点）执行识别文字、描述内容、分析图表等任务，供 agent 自主调用。
 * <p>
 * 依据 DeepSeek 视觉文档（api-docs.deepseek.com/zh-cn/guides/vision）实现：
 * <ul>
 *   <li>OpenAI 兼容 /chat/completions，user 消息 content 为块数组：文本块
 *       （任务描述 + 多智能体协作上下文）在前，image_url 块在后</li>
 *   <li>图片统一读取/下载后 base64 内联（data URL 方式）：本地文件与内网 URL 均可用，
 *       且格式/大小在本地校验；单图上限 32 MiB，格式 JPEG/PNG/GIF/WebP（按魔数判定）</li>
 *   <li>任务要求与协作上下文全部拼进 user 提示词；system 消息只放固定行为约束</li>
 * </ul>
 */
@Component
public class VisionTool {

    /** 单图字节上限：对齐 DeepSeek 视觉 base64 单图 32 MiB 限制 */
    static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

    /** 视觉模型回复截断长度（防超长 OCR 结果撑爆上下文） */
    private static final int MAX_RESULT_CHARS = 16384;

    /** 下载图片 URL 超时（对齐 DeepSeek 侧 60s 下载窗口，本地下载留余量） */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    /** 视觉推理总超时（大图 + 长回复场景） */
    private static final Duration API_TIMEOUT = Duration.ofSeconds(120);

    /** 固定 system 行为约束：只基于图片内容，不编造 */
    private static final String SYSTEM_PROMPT =
            "你是一名视觉分析助手。仅基于图片内容回答，不要编造图中不存在的信息；"
                    + "图中无法判断的信息如实说明。";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Path workspaceRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public VisionTool(
            @Value("${markagent.vision.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${markagent.vision.api-key:${spring.ai.deepseek.api-key:}}") String apiKey,
            @Value("${markagent.vision.model:deepseek-flash}") String model,
            @Value("${markagent.vision.max-tokens:4096}") int maxTokens,
            @Value("${markagent.storage.root:./data}") String storageRoot) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.trim();
        this.maxTokens = maxTokens;
        this.workspaceRoot = Path.of(storageRoot).toAbsolutePath().normalize().resolve("workspace");
    }

    @Tool(name = "vision_analyze", description = "把图片交给视觉大模型（deepseek-flash 等）完成视觉任务："
            + "识别图片中的文字（OCR）、描述图片内容、分析截图/图表/照片、看图回答问题等。"
            + "image 支持三种形式：本地文件路径（绝对路径，或任务工作区 data/workspace/{taskId}/ 下的相对路径）、"
            + "http(s) 图片 URL、base64 data URL；格式支持 jpeg/png/gif/webp，单图不超过 32MB。"
            + "task 写清要让视觉模型对这张图片做什么（如「逐字转录图中所有文字，保留排版顺序」）。"
            + "多智能体协作时必须在 context 中说明：团队整体目标、已完成的进展、这张图片与当前任务的关系，"
            + "便于视觉模型在正确的背景下分析。读取网页截图等视觉材料、需要 OCR 或图表解读时优先用本工具。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String analyze(
            @ToolParam(name = "image", required = true,
                    description = "图片来源：本地文件路径（绝对路径或任务工作区内相对路径）、http(s) 图片 URL、"
                            + "或 base64 data URL（data:image/...;base64,...）") String image,
            @ToolParam(name = "task", required = true,
                    description = "要让视觉模型对这张图片完成的具体任务，如「逐字转录图中所有文字」「描述图中场景」"
                            + "「分析这张图表的趋势并给出结论」") String task,
            @ToolParam(name = "context", required = false,
                    description = "多智能体协作上下文：团队整体目标、已完成的进展、这张图片与当前任务的关系。"
                            + "非协作场景可省略") String context) {
        if (apiKey.isBlank()) {
            return "错误：未配置视觉模型 API Key（VISION_API_KEY 或 spring.ai.deepseek.api-key），vision_analyze 不可用";
        }
        if (image == null || image.isBlank()) {
            return "错误：image 参数为空，请提供本地图片路径、图片 URL 或 base64 data URL";
        }
        if (task == null || task.isBlank()) {
            return "错误：task 参数为空，请写明要让视觉模型对图片完成的任务";
        }
        byte[] bytes;
        try {
            bytes = resolveImage(image.trim());
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
        String format = detectFormat(bytes);
        if (format == null) {
            return "错误：图片格式无法识别（仅支持 JPEG/PNG/GIF/WebP，按文件内容判定，与扩展名无关）";
        }
        String dataUrl = "data:image/" + format + ";base64," + Base64.getEncoder().encodeToString(bytes);
        try {
            String body = mapper.writeValueAsString(buildRequest(buildUserText(task, context), dataUrl));
            HttpResponse<String> response = send(body);
            if (response.statusCode() != 200) {
                return String.format(Locale.ROOT, "错误：视觉模型 API 返回 HTTP %d - %s",
                        response.statusCode(), truncate(response.body(), 1000));
            }
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误：视觉模型调用被中断";
        } catch (Exception e) {
            return "错误：视觉模型调用失败 - " + e.getMessage();
        }
    }

    // ---------- 图片解析 ----------

    /** 图片来源 → 原始字节：data URL 解码 / URL 下载 / 本地文件读取；失败抛带中文消息的异常 */
    byte[] resolveImage(String image) {
        if (image.startsWith("data:")) {
            return decodeDataUrl(image);
        }
        // 带 scheme 的一律按 URL 处理：非 http/https 由 UrlChecks 拒绝（如 ftp://），
        // 避免被误当成文件名（Windows 下 Path.of("ftp://x") 可解析成合法相对路径）
        if (image.contains("://")) {
            String validation = UrlChecks.validateHttp(image);
            if (validation != null) {
                throw new IllegalArgumentException(validation);
            }
            return download(image);
        }
        return readLocal(Path.of(image));
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        String b64 = comma < 0 ? dataUrl : dataUrl.substring(comma + 1);
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("data URL 的 base64 数据无法解码 - " + e.getMessage());
        }
    }

    private static byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(url.trim()))
                            .timeout(DOWNLOAD_TIMEOUT)
                            .header("User-Agent", "MarkAgent/0.1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("下载图片返回 HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("下载的图片内容为空");
            }
            checkSize(body);
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("下载图片被中断");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("下载图片失败 - " + e.getMessage());
        }
    }

    /** 本地文件读取：相对路径优先按任务工作区（data/workspace/{taskId}/）解析，无任务上下文时按进程 CWD */
    private byte[] readLocal(Path path) {
        try {
            Path resolved = path.isAbsolute() ? path : workspacePath().resolve(path);
            resolved = resolved.toAbsolutePath().normalize();
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("图片文件不存在 " + resolved);
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("路径不是普通文件 " + resolved);
            }
            byte[] bytes = Files.readAllBytes(resolved);
            checkSize(bytes);
            return bytes;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取图片文件失败 - " + e.getMessage());
        }
    }

    private static void checkSize(byte[] bytes) {
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "图片超过 32MB 上限（实际 " + bytes.length / 1024 / 1024 + "MB）");
        }
    }

    /** 相对路径基准：有任务上下文时取任务工作区 data/workspace/{taskId}，否则取进程 CWD */
    private Path workspacePath() {
        String taskId = TaskIdContext.get();
        if (taskId != null && !taskId.isBlank()) {
            return workspaceRoot.resolve(taskId);
        }
        return Path.of(".");
    }

    /** 魔数判定图片格式（DeepSeek 按文件实际内容判定，不看扩展名）；返回 jpeg/png/gif/webp 或 null */
    static String detectFormat(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "png";
        }
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "gif";
        }
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    // ---------- 请求构造与响应解析 ----------

    /**
     * user 提示词组装：协作上下文 + 任务要求全部拼进 user 消息的文本块
     * （多智能体协作时视觉模型需要知道已实现什么、目标是什么，均在此描述）。
     */
    static String buildUserText(String task, String context) {
        String text = task == null ? "" : task.trim();
        if (context != null && !context.isBlank()) {
            return "[任务背景]\n" + context.trim() + "\n\n[本次任务]\n" + text;
        }
        return text;
    }

    /** OpenAI 兼容请求体：system 放固定约束，user 为 [文本块, image_url 块] */
    Map<String, Object> buildRequest(String userText, String dataUrl) {
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUrl);
        imageUrl.put("detail", "auto");

        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image_url");
        imageBlock.put("image_url", imageUrl);

        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", userText);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", List.of(textBlock, imageBlock));

        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("max_tokens", maxTokens);
        request.put("messages", List.of(systemMessage, userMessage));
        return request;
    }

    private HttpResponse<String> send(String body) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                        .timeout(API_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("User-Agent", "MarkAgent/0.1")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 从 choices[0].message.content 提取文本；空内容附 finish_reason（如 content_filter 拒答） */
    static String parseResponse(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body == null ? "" : body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "错误：视觉模型返回空 choices - " + truncate(body, 500);
            }
            JsonNode choice = choices.get(0);
            String text = choice.path("message").path("content").asText("");
            if (text == null || text.isBlank()) {
                String reason = choice.path("finish_reason").asText("");
                return "错误：视觉模型未返回文本内容" + (reason.isBlank() ? "" : "（finish_reason=" + reason + "）");
            }
            return truncate(text, MAX_RESULT_CHARS);
        } catch (Exception e) {
            return "错误：视觉模型响应解析失败 - " + e.getMessage();
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "…（截断）" : text;
    }
}
