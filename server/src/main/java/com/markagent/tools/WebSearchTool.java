package com.markagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自研 web 搜索工具：不需要任何搜索 API key，直接抓取搜索引擎的 HTML 结果页并解析。
 * <p>
 * 通道（自动降级）：Bing（cn.bing.com，全球可达）→ DuckDuckGo（html.duckduckgo.com，
 * 免 key 且对爬虫友好，供可达其网络的部署使用）。解析为纯 JDK 正则——与
 * {@link HttpRequestTool} 一致的「零新依赖」风格（刻意不引 jsoup，同
 * {@link MarkAgentToolRegistrar} 的绕依赖思路）。
 * <p>
 * 已知限制：HTML 抓取受搜索引擎反爬策略影响（响应结构变化或限流时降级/报错）；
 * 结果仅为网页快照信息，可靠性需使用者自行判断。
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int MAX_RESULTS = 10;
    private static final int SNIPPET_MAX_CHARS = 200;
    private static final int OUTPUT_MAX_CHARS = 4000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 MarkAgent/0.1";

    // ---- Bing 结果页结构 ----
    private static final Pattern BING_BLOCK = Pattern.compile("<li[^>]*class=\"[^\"]*b_algo[^\"]*\"");
    private static final Pattern H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile("<a\\s([^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HREF_ATTR = Pattern.compile("href\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern LINE_CLAMP = Pattern.compile(
            "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern BING_REDIRECT_U = Pattern.compile("[?&]u=([^&]+)");

    // ---- DuckDuckGo html 端点结构 ----
    private static final Pattern DDG_BLOCK = Pattern.compile("<h2[^>]*class=\"[^\"]*result__title");
    private static final Pattern CLASS_RESULT_A = Pattern.compile("class\\s*=\\s*[\"'][^\"']*result__a");
    private static final Pattern CLASS_RESULT_SNIPPET = Pattern.compile(
            "class\\s*=\\s*[\"'][^\"']*result__snippet");
    private static final Pattern ELEMENT = Pattern.compile(
            "<([a-z]+)([^>]*)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern DDG_REDIRECT_UDDG = Pattern.compile("[?&]uddg=([^&]+)");

    // ---- HTML 实体 ----
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

    /** 搜索结果（内部模型） */
    private record SearchResult(String title, String url, String snippet) {
    }

    /** 引擎请求结果：reachable=false 表示网络/HTTP 失败（区别于「有响应但零结果」） */
    private record EngineOutcome(boolean reachable, List<SearchResult> results) {
    }

    @Tool(name = "web_search", description = "实时网络搜索：以关键词检索公开网页，返回「标题/网址/摘要」列表（最多 10 条）。"
            + "当需要最新资讯、实时数据、事实查证，或信息超出模型知识截止日期时使用；抓取具体网页正文用 http_get。"
            + "query 建议用空格分隔的关键词（中文亦可）；结果仅为搜索引擎快照，重要结论需交叉验证。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String webSearch(@ToolParam(name = "query", required = true,
            description = "搜索关键词或问题，建议用空格分隔的关键词") String query,
            @ToolParam(name = "maxResults", required = false,
                    description = "返回结果条数上限，默认 5，范围 1-10") Integer maxResults) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return "错误：query 不能为空";
        }
        int n = maxResults == null ? 5 : Math.max(1, Math.min(maxResults, MAX_RESULTS));

        EngineOutcome bing = searchBing(q, n);
        if (!bing.results().isEmpty()) {
            return format(q, bing.results());
        }
        EngineOutcome ddg = searchDuckDuckGo(q, n);
        if (!ddg.results().isEmpty()) {
            return format(q, ddg.results());
        }
        if (bing.reachable() || ddg.reachable()) {
            return "未找到与「" + q + "」相关的搜索结果（可尝试更换关键词）。";
        }
        return "错误：搜索引擎均不可达（Bing 与 DuckDuckGo 请求失败或超时），请稍后重试。";
    }

    // ---------- Bing 通道 ----------

    private static EngineOutcome searchBing(String query, int max) {
        String html = fetch("https://cn.bing.com/search?q=" + encode(query) + "&count=20");
        if (html == null) {
            return new EngineOutcome(false, List.of());
        }
        List<SearchResult> out = new ArrayList<>();
        Matcher blockMatcher = BING_BLOCK.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (blockMatcher.find()) {
            starts.add(blockMatcher.start());
        }
        for (int i = 0; i < starts.size() && out.size() < max; i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : Math.min(html.length(), from + 20000);
            SearchResult result = parseBingBlock(html.substring(from, to));
            if (result != null) {
                out.add(result);
            }
        }
        return new EngineOutcome(true, out);
    }

    /** 解析单个 b_algo 块：标题优先取 h2 内首个 http 链接，摘要取 b_lineclamp 段落 */
    private static SearchResult parseBingBlock(String block) {
        String title = null;
        String url = null;
        Matcher h2 = H2.matcher(block);
        if (h2.find()) {
            String[] pair = firstHttpAnchor(h2.group(1));
            if (pair != null) {
                url = pair[0];
                title = pair[1];
            }
        }
        if (title == null) {
            String[] pair = firstHttpAnchor(block);
            if (pair != null) {
                url = pair[0];
                title = pair[1];
            }
        }
        if (title == null || url == null || title.isBlank()) {
            return null;
        }
        String snippet = "";
        Matcher clamp = LINE_CLAMP.matcher(block);
        if (clamp.find()) {
            snippet = truncate(cleanText(clamp.group(1)), SNIPPET_MAX_CHARS);
        }
        return new SearchResult(truncate(title, 120), resolveBingUrl(url), snippet);
    }

    /** 块/片段内第一个 http 链接：返回 [href, 文本]；无则 null */
    private static String[] firstHttpAnchor(String fragment) {
        Matcher m = ANCHOR.matcher(fragment);
        while (m.find()) {
            Matcher h = HREF_ATTR.matcher(m.group(1));
            if (!h.find()) {
                continue;
            }
            String href = h.group(1);
            if (!href.startsWith("http")) {
                continue;
            }
            String text = cleanText(m.group(2));
            if (text.isBlank()) {
                continue;
            }
            return new String[]{href, text};
        }
        return null;
    }

    /** Bing 链接可能是 /ck/a 跳转（u=a1<base64url>），还原真实地址 */
    private static String resolveBingUrl(String href) {
        if (href.startsWith("http") && !href.contains("/ck/a")) {
            return href;
        }
        Matcher u = BING_REDIRECT_U.matcher(href);
        if (u.find()) {
            String b64 = u.group(1);
            if (b64.startsWith("a1")) {
                b64 = b64.substring(2);
            }
            try {
                return new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 解码失败则保留原 href
            }
        }
        return href;
    }

    // ---------- DuckDuckGo 通道 ----------

    private static EngineOutcome searchDuckDuckGo(String query, int max) {
        String html = fetch("https://html.duckduckgo.com/html/?q=" + encode(query));
        if (html == null) {
            return new EngineOutcome(false, List.of());
        }
        List<SearchResult> out = new ArrayList<>();
        Matcher blockMatcher = DDG_BLOCK.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (blockMatcher.find()) {
            starts.add(blockMatcher.start());
        }
        for (int i = 0; i < starts.size() && out.size() < max; i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : Math.min(html.length(), from + 20000);
            SearchResult result = parseDdgBlock(html.substring(from, to));
            if (result != null) {
                out.add(result);
            }
        }
        return new EngineOutcome(true, out);
    }

    /** 解析单个 result__title 块：result__a 链接 + result__snippet 摘要 */
    private static SearchResult parseDdgBlock(String block) {
        String[] pair = firstAnchorWithClass(block, CLASS_RESULT_A);
        if (pair == null || pair[0].isBlank() || pair[1].isBlank()) {
            return null;
        }
        String snippet = "";
        Matcher m = ELEMENT.matcher(block);
        while (m.find()) {
            if (CLASS_RESULT_SNIPPET.matcher(m.group(2)).find()) {
                snippet = truncate(cleanText(m.group(3)), SNIPPET_MAX_CHARS);
                break;
            }
        }
        return new SearchResult(truncate(pair[1], 120), resolveDdgUrl(pair[0]), snippet);
    }

    /** class 属性含指定标记的首个 <a>：返回 [href, 文本]；无则 null */
    private static String[] firstAnchorWithClass(String fragment, Pattern classMarker) {
        Matcher m = ANCHOR.matcher(fragment);
        while (m.find()) {
            if (!classMarker.matcher(m.group(1)).find()) {
                continue;
            }
            Matcher h = HREF_ATTR.matcher(m.group(1));
            String href = h.find() ? h.group(1) : "";
            String text = cleanText(m.group(2));
            if (text.isBlank()) {
                continue;
            }
            return new String[]{href, text};
        }
        return null;
    }

    /** DDG 链接是 //duckduckgo.com/l/?uddg=<真实地址> 跳转，解码还原 */
    private static String resolveDdgUrl(String href) {
        Matcher u = DDG_REDIRECT_UDDG.matcher(href);
        if (u.find()) {
            try {
                return URLDecoder.decode(u.group(1), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 解码失败则保留原 href
            }
        }
        return href.startsWith("//") ? "https:" + href : href;
    }

    // ---------- 公共 ----------

    private static String fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("web_search 引擎返回 HTTP {}", response.statusCode());
                return null;
            }
            return response.body() == null ? "" : response.body();
        } catch (Exception e) {
            log.debug("web_search 请求失败: {}", e.toString());
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 去 HTML 标签 + 解实体（命名 + 数字）+ 压缩空白 */
    private static String cleanText(String raw) {
        String text = raw.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&ensp;", " ")
                .replace("&emsp;", " ")
                .replace("&middot;", "·");
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            String replacement = m.group();
            try {
                int code = body.startsWith("x") || body.startsWith("X")
                        ? Integer.parseInt(body.substring(1), 16)
                        : Integer.parseInt(body);
                replacement = new String(Character.toChars(code));
            } catch (Exception ignored) {
                // 非法码点则保留原文
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String format(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("【web_search 结果】query=\"").append(query).append("\" 共 ")
                .append(results.size()).append(" 条\n");
        int index = 1;
        for (SearchResult result : results) {
            sb.append(index++).append(". ").append(result.title()).append("\n");
            sb.append("   网址: ").append(result.url()).append("\n");
            if (!result.snippet().isBlank()) {
                sb.append("   摘要: ").append(result.snippet()).append("\n");
            }
            if (sb.length() > OUTPUT_MAX_CHARS) {
                sb.append("…（其余结果省略）");
                break;
            }
        }
        return sb.toString();
    }
}
