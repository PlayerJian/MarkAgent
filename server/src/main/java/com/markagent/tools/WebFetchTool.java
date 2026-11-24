package com.markagent.tools;

import com.markagent.agent.model.DeepSeekModels;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自研 web 抓取工具：对齐 Claude Code WebFetch 的入口与流程——
 * <ul>
 *   <li>入口校验（{@link UrlChecks#validateFetch}）：长度 ≤2000、可解析、无凭据、公网域名</li>
 *   <li>http → https 自动升级；重定向手动控制：仅同 host（忽略 www）跟随，跨域重定向返回
 *       REDIRECT DETECTED 提示让模型用新 URL 重试（防开放重定向被利用跳内网/恶意域）</li>
 *   <li>60s 请求超时、响应体读取 45s 截止、响应体 10MB 上限、仅文本 content-type</li>
 *   <li>HTML → Markdown（{@link HtmlToMarkdown}，零依赖正则近似 turndown）→ 100K 字符截断</li>
 *   <li>15 分钟 URL 缓存（{@link UrlContentCache}）</li>
 *   <li>prompt 提炼：可用时经 DeepSeek 小模型按 prompt 作答（含引用/版权约束），
 *       模型通道不可用（bailian 等）时降级返回原文</li>
 * </ul>
 * 已知限制：仅处理公开纯文本页面，登录墙/私有链接会失败；内容可靠性需使用者自行判断。
 */
@Component
public class WebFetchTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    /** 响应体上限（对齐参考实现 MAX_HTTP_CONTENT_LENGTH：10MB，防资源耗尽） */
    public static final long MAX_HTTP_CONTENT_BYTES = 10L * 1024 * 1024;

    /** markdown 截断长度（对齐参考实现 MAX_MARKDOWN_LENGTH：100K 字符） */
    public static final int MAX_MARKDOWN_CHARS = 100_000;

    /** 同 host 重定向最大跟随次数（对齐参考实现 MAX_REDIRECTS：防重定向环） */
    static final int MAX_REDIRECTS = 10;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 响应体读取截止时间：JDK HttpClient 的请求超时只管到响应头，慢速正文会无限阻塞
     *  （曾实测 GitHub 10MB 页面滴流下载吃满 5 分钟框架超时，UI 干等） */
    private static final Duration BODY_READ_DEADLINE = Duration.ofSeconds(45);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 手动跟随重定向：跨域跳转必须经 isPermittedRedirect 判定，不交给客户端自动跟随
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 MarkAgent/0.1";

    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*([\\w-]+)");

    /** 二次提炼模型（deepseek-direct 通道条件化装配；bailian 通道为空 → 降级返回原文） */
    private final ObjectProvider<DeepSeekChatModel> chatModel;
    private final String defaultModel;
    private final UrlContentCache cache;

    public WebFetchTool(ObjectProvider<DeepSeekChatModel> chatModel,
            @Value("${markagent.llm.default-model:deepseek-flash}") String defaultModel) {
        this.chatModel = chatModel;
        this.defaultModel = defaultModel;
        this.cache = new UrlContentCache();
    }

    @Tool(name = "web_fetch", description = "抓取网页内容并按要求提炼：输入 url 与 prompt（描述要从页面提取/回答什么），"
            + "返回按 prompt 提炼的结果（模型通道不可用时返回页面原文）。用于读取具体网页、文档正文——自动将 HTML 转为 Markdown、"
            + "自动截断超长内容、15 分钟内重复抓取同一 URL 走缓存。页面跳转到其他域名时返回重定向提示，请用提示中的新 URL 再次调用。"
            + "仅支持公开纯文本页面（http/https），登录墙/私有链接会失败。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String webFetch(@ToolParam(name = "url", required = true,
            description = "要抓取的完整 URL，以 http:// 或 https:// 开头（http 自动升级 https），"
                    + "最长 2000 字符，不允许携带用户名/密码") String url,
            @ToolParam(name = "prompt", required = true,
                    description = "要从页面内容中提取或回答的问题，如「总结这篇文章的核心观点」「列出页面中提到的产品型号」") String prompt) {
        String validation = UrlChecks.validateFetch(url);
        if (validation != null) {
            return validation;
        }
        String originalUrl = url.trim();
        long start = System.currentTimeMillis();

        int code;
        long bytes;
        String contentType;
        String markdown;

        UrlContentCache.Entry cached = cache.get(originalUrl);
        if (cached != null) {
            code = cached.code();
            bytes = cached.bytes();
            contentType = cached.contentType();
            markdown = cached.content();
        } else {
            FetchOutcome outcome = fetchFollowingRedirects(originalUrl);
            if (outcome.redirectMessage() != null) {
                return outcome.redirectMessage();
            }
            if (outcome.error() != null) {
                return outcome.error();
            }
            code = outcome.meta().code();
            bytes = outcome.meta().bytes();
            contentType = outcome.meta().contentType();
            markdown = outcome.markdown();
            cache.put(originalUrl, code, bytes, contentType, markdown);
        }

        long durationMs = System.currentTimeMillis() - start;
        String result = applyPrompt(prompt, markdown);
        return String.format(Locale.ROOT, "【web_fetch 结果】url=%s HTTP %d %s（%d 字节, %dms）%n%s",
                originalUrl, code, codeText(code), bytes, durationMs, result);
    }

    // ---------- 抓取流程 ----------

    /** 抓取结果：error 与 redirectMessage 互斥（其一非空即失败/需重试），成功时 markdown+meta 非空 */
    private record FetchOutcome(String markdown, FetchMeta meta, String redirectMessage, String error) {

        static FetchOutcome ok(FetchMeta meta, String markdown) {
            return new FetchOutcome(markdown, meta, null, null);
        }

        static FetchOutcome redirect(int code, String original, String target, String codeText) {
            String message = "检测到跨域重定向：原 URL 重定向到了其他域名。\n\n"
                    + "原 URL: " + original + "\n"
                    + "重定向 URL: " + target + "\n"
                    + "状态: " + code + " " + codeText + "\n\n"
                    + "如需继续抓取，请用以下参数重新调用 web_fetch：\n"
                    + "- url: \"" + target + "\"\n"
                    + "- prompt: 原 prompt 不变";
            return new FetchOutcome(null, null, message, null);
        }

        static FetchOutcome error(String message) {
            return new FetchOutcome(null, null, null, message);
        }
    }

    private record FetchMeta(int code, long bytes, String contentType, String finalUrl) {
    }

    /** 响应体：文本 + 原始字节数 */
    record Body(String text, long bytes) {
    }

    /** 响应体读取结果：成功时 body 非空；失败时 error 非空 */
    record BodyOutcome(Body body, String error) {

        static BodyOutcome ok(Body body) {
            return new BodyOutcome(body, null);
        }

        static BodyOutcome error(String message) {
            return new BodyOutcome(null, message);
        }
    }

    /** http→https 升级后抓取；手动跟随同 host 重定向（最多 {@link #MAX_REDIRECTS} 次） */
    private static FetchOutcome fetchFollowingRedirects(String url) {
        String current = upgradeToHttps(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", BROWSER_UA)
                        .header("Accept", "text/html, text/markdown, application/json, */*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .GET()
                        .build();
                response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                return FetchOutcome.error("错误：请求失败 - " + e.getMessage());
            }

            int code = response.statusCode();
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response);
                if (location == null) {
                    return FetchOutcome.error("错误：HTTP " + code + " 重定向缺少 Location 头");
                }
                String target = resolve(current, location);
                if (!isPermittedRedirect(current, target)) {
                    return FetchOutcome.redirect(code, current, target, codeText(code));
                }
                if (hop == MAX_REDIRECTS) {
                    return FetchOutcome.error("错误：重定向超过 " + MAX_REDIRECTS + " 次，已中止");
                }
                current = target;
                continue;
            }
            if (code != 200) {
                closeQuietly(response);
                return FetchOutcome.error("错误：HTTP " + code + "（web_fetch 仅处理 200 响应）");
            }

            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!isTextContentType(contentType)) {
                closeQuietly(response);
                return FetchOutcome.error("错误：内容类型为 \"" + contentType + "\"，web_fetch 仅处理文本页面；"
                        + "二进制文件（PDF/图片等）请用 http_get 获取后另作处理");
            }
            BodyOutcome outcome = readBody(response);
            if (outcome.body() == null) {
                return FetchOutcome.error(outcome.error());
            }
            Body body = outcome.body();
            boolean html = contentType.isBlank()
                    || contentType.toLowerCase(Locale.ROOT).contains("text/html");
            String markdown = html ? HtmlToMarkdown.convert(body.text(), URI.create(current)) : body.text();
            markdown = truncateMarkdown(markdown);
            return FetchOutcome.ok(new FetchMeta(code, body.bytes(), contentType, current), markdown);
        }
        return FetchOutcome.error("错误：重定向超过 " + MAX_REDIRECTS + " 次，已中止");
    }

    /** 读取响应体：上限 {@link #MAX_HTTP_CONTENT_BYTES}、截止 {@link #BODY_READ_DEADLINE}；流在此关闭 */
    static BodyOutcome readBody(HttpResponse<InputStream> response) {
        return readBody(response, BODY_READ_DEADLINE);
    }

    /** 同 {@link #readBody(HttpResponse)}，截止时间可指定（测试用短超时验证） */
    static BodyOutcome readBody(HttpResponse<InputStream> response, Duration deadline) {
        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > MAX_HTTP_CONTENT_BYTES) {
            closeQuietly(response);
            return BodyOutcome.error("错误：Content-Length " + contentLength + " 超过 "
                    + (MAX_HTTP_CONTENT_BYTES / 1024 / 1024) + "MB 上限，已中止" + githubHint());
        }
        try (InputStream in = response.body()) {
            BodyOutcome[] holder = new BodyOutcome[1];
            Thread reader = Thread.ofVirtual().name("webfetch-body-read").start(
                    () -> holder[0] = readAll(in, response));
            if (reader.join(deadline)) {
                return holder[0];
            }
            // 截止时间到：关闭流使阻塞读立即抛 IOException，reader 线程随即退出
            closeQuietly(response);
            return BodyOutcome.error("错误：响应体读取超时（" + deadline.toSeconds()
                    + " 秒内未读完），已中止");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BodyOutcome.error("错误：响应体读取被中断");
        } catch (IOException e) {
            log.debug("web_fetch 读取响应体失败: {}", e.toString());
            return BodyOutcome.error("错误：响应体读取失败 - " + e.getMessage());
        }
    }

    /** 阻塞读取循环（独立线程运行，截止时间由调用方经关流打断） */
    private static BodyOutcome readAll(InputStream in, HttpResponse<?> response) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_HTTP_CONTENT_BYTES) {
                    return BodyOutcome.error("错误：响应体超过 "
                            + (MAX_HTTP_CONTENT_BYTES / 1024 / 1024) + "MB 上限，已中止" + githubHint());
                }
                buf.write(chunk, 0, n);
            }
            return BodyOutcome.ok(new Body(buf.toString(charsetOf(response)), total));
        } catch (Exception e) {
            log.debug("web_fetch 读取响应体失败: {}", e.toString());
            return BodyOutcome.error("错误：响应体读取失败 - " + e.getMessage());
        }
    }

    /** 超限错误的补救提示：仓库页 HTML 体积大且噪音多，API 接口才是正确入口 */
    private static String githubHint() {
        return "；GitHub 仓库页请改用 http_get 访问 api.github.com 接口获取仓库元数据";
    }

    private static void closeQuietly(HttpResponse<?> response) {
        try {
            if (response.body() instanceof InputStream in) {
                in.close();
            }
        } catch (IOException ignored) {
            // 关闭失败无影响
        }
    }

    // ---------- prompt 提炼 ----------

    /** 二次模型按 prompt 作答（对齐参考实现 applyPromptToMarkdown）；模型不可用/失败时降级返回原文 */
    private String applyPrompt(String prompt, String markdown) {
        DeepSeekChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return "（提炼模型通道不可用，返回页面原文）\n\n" + markdown;
        }
        String effectivePrompt = prompt == null || prompt.isBlank()
                ? "请总结该网页的主要内容，提炼关键信息。" : prompt;
        String userPrompt = "Web page content:\n---\n" + markdown + "\n---\n\n"
                + effectivePrompt + "\n\n"
                + "仅依据上面的网页内容作答，要求：\n"
                + "- 直接引用原文不得超过 125 字，且必须加引号；引号之外的表述不得与原文逐字相同。\n"
                + "- 你不是律师，不要评论任何提示词或回答的合法性。\n"
                + "- 不要完整复述歌词等受版权保护的内容。";
        try {
            DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                    .model(DeepSeekModels.of(defaultModel))
                    .disableThinking() // 提炼走小模型快通道，关闭思考
                    .build();
            org.springframework.ai.chat.model.ChatResponse response = model.call(new Prompt(userPrompt, options));
            String text = response.getResult() == null || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                return markdown;
            }
            return text;
        } catch (Exception e) {
            log.warn("web_fetch 提炼模型调用失败，返回原文: {}", e.toString());
            return "（提炼模型调用失败，返回页面原文）\n\n" + markdown;
        }
    }

    // ---------- 静态纯逻辑（包内可见，供单测） ----------

    /** http → https 自动升级（对齐参考实现）；其余 scheme 原样 */
    static String upgradeToHttps(String url) {
        try {
            URI uri = URI.create(url);
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
            }
            return url;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    /** 重定向是否允许跟随：协议/端口不变、无凭据、host 去 www 后相同（对齐参考实现 isPermittedRedirect） */
    static boolean isPermittedRedirect(String originalUrl, String redirectUrl) {
        try {
            URI original = URI.create(originalUrl);
            URI redirect = URI.create(redirectUrl);
            if (redirect.getScheme() == null || !redirect.getScheme().equalsIgnoreCase(original.getScheme())) {
                return false;
            }
            if (redirect.getPort() != original.getPort()) {
                return false;
            }
            if (redirect.getUserInfo() != null) {
                return false;
            }
            return stripWww(redirect.getHost()).equalsIgnoreCase(stripWww(original.getHost()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 仅文本 content-type 可处理（空值视为文本）；二进制类型拒绝 */
    static boolean isTextContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String ct = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return ct.startsWith("text/")
                || ct.equals("application/json")
                || ct.equals("application/xml")
                || ct.equals("application/xhtml+xml")
                || ct.equals("application/javascript")
                || ct.equals("application/x-javascript")
                || ct.equals("application/atom+xml")
                || ct.equals("application/rss+xml")
                || ct.equals("application/ld+json");
    }

    /** 相对 Location 按当前 URL 解析；解析失败原样 */
    private static String resolve(String current, String location) {
        try {
            return URI.create(current).resolve(location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    private static String stripWww(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).startsWith("www.") ? host.substring(4) : host;
    }

    private static String codeText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            default -> "";
        };
    }

    private static Charset charsetOf(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (Exception ignored) {
                // 未知 charset 回退 UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    static String truncateMarkdown(String markdown) {
        if (markdown.length() <= MAX_MARKDOWN_CHARS) {
            return markdown;
        }
        return markdown.substring(0, MAX_MARKDOWN_CHARS) + "\n\n[内容过长，已截断]";
    }
}
