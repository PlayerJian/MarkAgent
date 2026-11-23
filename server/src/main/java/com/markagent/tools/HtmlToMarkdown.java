package com.markagent.tools;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 JDK 的 HTML → Markdown 转换器（web_fetch 用），行为对齐 Claude Code WebFetch 的
 * turndown：标题/链接/图片/加粗/斜体/行内代码/代码块/列表保留结构，其余标签去壳留文本。
 * <p>
 * 与 WebSearchTool 同风格「零新依赖」（刻意不引 jsoup）：正则近似解析，不做完整 DOM 构建。
 * 目标不是保真渲染，而是给 LLM 一份结构清晰、无噪声的正文；坏 HTML（未闭合标签等）
 * 按去壳兜底，不抛异常。
 * <p>
 * 处理顺序（顺序即语义）：
 * <ol>
 *   <li>pre 块抽离为占位符保护（避免后续替换破坏代码缩进）</li>
 *   <li>删除 script/style/noscript/template/head/注释</li>
 *   <li>标题 → # 前缀</li>
 *   <li>行内标签：a → [text](href)（相对链接按 baseUri 绝对化）→ img → strong/b → em/i → code</li>
 *   <li>块级标签 → 段落边界（li → "- "，td/th → " | "，hr → ---，br → 换行）</li>
 *   <li>删除剩余标签 → 解 HTML 实体 → 空白压缩 → 还原 pre 为 ``` 代码块</li>
 * </ol>
 */
public final class HtmlToMarkdown {

    private static final Pattern PRE = Pattern.compile("(?is)<pre\\b[^>]*>(.*?)</pre\\s*>");
    private static final Pattern DROP_BLOCK = Pattern.compile(
            "(?is)<(script|style|noscript|template)\\b[^>]*>.*?</\\1\\s*>|<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern H = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>");
    private static final Pattern A = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\b([^>]*)/?>");
    private static final Pattern STRONG_B = Pattern.compile("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>");
    private static final Pattern EM_I = Pattern.compile("(?is)<(em|i)\\b[^>]*>(.*?)</\\1\\s*>");
    private static final Pattern CODE = Pattern.compile("(?is)<code\\b[^>]*>(.*?)</code\\s*>");
    private static final Pattern BR = Pattern.compile("(?is)<br\\b[^>]*/?>");
    private static final Pattern HR = Pattern.compile("(?is)<hr\\b[^>]*/?>");
    private static final Pattern LI_OPEN = Pattern.compile("(?is)<li\\b[^>]*>");
    private static final Pattern TD_TH_OPEN = Pattern.compile("(?is)<(td|th)\\b[^>]*>");
    private static final Pattern TR_CLOSE = Pattern.compile("(?is)</tr\\s*>");
    private static final Pattern BLOCK_OPEN = Pattern.compile(
            "(?is)<(p|div|section|article|header|footer|aside|nav|main|blockquote|figure|figcaption|table|"
                    + "ul|ol|dl|dt|dd|form|fieldset|details|summary|address|hgroup|menu)\\b[^>]*>");
    private static final Pattern BLOCK_CLOSE = Pattern.compile(
            "(?is)</(p|div|section|article|header|footer|aside|nav|main|blockquote|figure|figcaption|table|"
                    + "ul|ol|dl|dt|dd|form|fieldset|details|summary|address|hgroup|menu|li)\\s*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("thinsp", " "),
            Map.entry("middot", "·"), Map.entry("mdash", "—"), Map.entry("ndash", "–"),
            Map.entry("hellip", "…"), Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"),
            Map.entry("deg", "°"), Map.entry("plusmn", "±"), Map.entry("times", "×"),
            Map.entry("divide", "÷"), Map.entry("lsquo", "\u2018"), Map.entry("rsquo", "\u2019"),
            Map.entry("ldquo", "\u201C"), Map.entry("rdquo", "\u201D"), Map.entry("bull", "•"),
            Map.entry("sect", "§"), Map.entry("para", "¶"), Map.entry("frac12", "½"),
            Map.entry("frac14", "¼"), Map.entry("frac34", "¾"), Map.entry("euro", "€"),
            Map.entry("pound", "£"), Map.entry("yen", "¥"), Map.entry("cent", "¢"),
            Map.entry("szlig", "ß"), Map.entry("shy", ""), Map.entry("zwnj", ""));

    private HtmlToMarkdown() {
    }

    /** 转换；相对链接不绝对化 */
    public static String convert(String html) {
        return convert(html, null);
    }

    /** 转换；baseUri 非空时相对链接/图片地址按它绝对化 */
    public static String convert(String html, URI baseUri) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = html;

        // 1) pre 抽离保护
        List<String> pres = new ArrayList<>();
        Matcher preMatcher = PRE.matcher(text);
        StringBuffer preBuf = new StringBuffer();
        int preIndex = 0;
        while (preMatcher.find()) {
            pres.add(preMatcher.group(1));
            preMatcher.appendReplacement(preBuf,
                    Matcher.quoteReplacement("\u0000" + preIndex++ + "\u0000"));
        }
        preMatcher.appendTail(preBuf);
        text = preBuf.toString();

        // 2) 删脚本/样式/头/注释
        text = DROP_BLOCK.matcher(text).replaceAll("");
        text = COMMENT.matcher(text).replaceAll("");

        // 3) 标题（自带段后空行：标题直接跟正文时也保持结构）
        text = H.matcher(text).replaceAll(r -> "#".repeat(Integer.parseInt(r.group(1))) + " " + r.group(2) + "\n\n");

        // 4) 行内：a（相对链接绝对化）→ img → strong/b → em/i → code
        text = replaceAnchors(text, baseUri);
        text = IMG.matcher(text).replaceAll(r -> {
            String attrs = r.group(1);
            String alt = attr(attrs, "alt");
            String src = attr(attrs, "src");
            return "![" + (alt == null ? "" : alt) + "](" + resolve(baseUri, src) + ")";
        });
        text = STRONG_B.matcher(text).replaceAll(r -> "**" + r.group(2) + "**");
        text = EM_I.matcher(text).replaceAll(r -> "*" + r.group(2) + "*");
        text = CODE.matcher(text).replaceAll(r -> "`" + r.group(1) + "`");

        // 5) 块级边界
        text = BR.matcher(text).replaceAll("\n");
        text = HR.matcher(text).replaceAll("\n\n---\n\n");
        text = LI_OPEN.matcher(text).replaceAll("\n- ");
        text = TD_TH_OPEN.matcher(text).replaceAll(" | ");
        text = TR_CLOSE.matcher(text).replaceAll("\n");
        text = BLOCK_CLOSE.matcher(text).replaceAll("\n\n");
        text = BLOCK_OPEN.matcher(text).replaceAll("");

        // 6) 剩余标签去壳
        text = ANY_TAG.matcher(text).replaceAll(" ");

        // 7) 解实体
        text = decodeEntities(text);

        // 8) 空白压缩（pre 还原前做：不碰代码块内部格式）
        text = text.replace('\r', '\n')
                .replaceAll("[ \t]{2,}", " ")
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");

        // 9) 还原 pre 为代码块
        for (int i = 0; i < pres.size(); i++) {
            String codeText = decodeEntities(ANY_TAG.matcher(pres.get(i)).replaceAll("")).strip();
            text = text.replace("\u0000" + i + "\u0000", "```\n" + codeText + "\n```");
        }
        return text.strip();
    }

    /** a 标签 → [text](href)；无 href 则 [text]()；href 相对时按 baseUri 绝对化 */
    private static String replaceAnchors(String text, URI baseUri) {
        Matcher m = A.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String href = attr(m.group(1), "href");
            String replacement = "[" + m.group(2) + "](" + resolve(baseUri, href) + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 属性串中取 name="value" 或 name='value'；无则 null */
    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?is)" + name + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    /** 相对引用绝对化：绝对 URL / 协议相对 / 锚点原样，其余按 baseUri.resolve（非法引用兜底原样） */
    private static String resolve(URI baseUri, String ref) {
        if (ref == null) {
            return "";
        }
        if (baseUri == null || ref.isBlank()) {
            return ref;
        }
        try {
            return baseUri.resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    /** 命名 + 数字实体解码；非法码点保留原文 */
    private static String decodeEntities(String text) {
        for (Map.Entry<String, String> entry : NAMED_ENTITIES.entrySet()) {
            text = text.replace("&" + entry.getKey() + ";", entry.getValue());
        }
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
                // 非法码点保留原文
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
