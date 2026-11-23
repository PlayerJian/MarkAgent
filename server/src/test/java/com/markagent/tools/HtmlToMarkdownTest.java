package com.markagent.tools;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HtmlToMarkdown 单测：覆盖标题/链接/图片/加粗斜体/行内代码/代码块保护/列表/实体解码/
 * 脚本删除/相对链接绝对化等核心转换路径（对齐 WebFetch 的 turndown 行为）。
 */
class HtmlToMarkdownTest {

    @Test
    void headingsAndParagraphs() {
        String html = "<html><body><h1>标题一</h1><p>第一段</p><p>第二段</p></body></html>";
        String md = HtmlToMarkdown.convert(html);
        assertEquals("# 标题一\n\n第一段\n\n第二段", md);
    }

    @Test
    void h2ToH6Prefixes() {
        assertEquals("## 二级", HtmlToMarkdown.convert("<h2>二级</h2>"));
        assertEquals("###### 六级", HtmlToMarkdown.convert("<h6>六级</h6>"));
    }

    @Test
    void anchorWithAbsoluteHref() {
        String md = HtmlToMarkdown.convert("<p>参见 <a href=\"https://example.com/a\">文档</a>。</p>");
        assertEquals("参见 [文档](https://example.com/a)。", md);
    }

    @Test
    void anchorWithRelativeHrefResolvedAgainstBase() {
        String html = "<a href=\"/docs/guide\">指南</a>";
        // 无 base：相对链接原样保留（turndown 行为）
        assertEquals("[指南](/docs/guide)", HtmlToMarkdown.convert(html));
        // 有 base：相对链接绝对化
        assertEquals("[指南](https://example.com/docs/guide)",
                HtmlToMarkdown.convert(html, URI.create("https://example.com/index.html")));
    }

    @Test
    void anchorInsideHeading() {
        String md = HtmlToMarkdown.convert("<h2><a href=\"https://x.com\">标题链接</a></h2>");
        assertEquals("## [标题链接](https://x.com)", md);
    }

    @Test
    void imageWithAlt() {
        String html = "<p>图：<img src=\"/a.png\" alt=\"示意图\"/></p>";
        assertEquals("图：![示意图](https://example.com/a.png)",
                HtmlToMarkdown.convert(html, URI.create("https://example.com/page")));
    }

    @Test
    void inlineFormatting() {
        String html = "<p><strong>加粗</strong>与<em>斜体</em>以及<code>code()</code></p>";
        assertEquals("**加粗**与*斜体*以及`code()`", HtmlToMarkdown.convert(html));
    }

    @Test
    void listItems() {
        String html = "<ul><li>项目一</li><li>项目二</li></ul>";
        String md = HtmlToMarkdown.convert(html);
        assertTrue(md.contains("- 项目一"));
        assertTrue(md.contains("- 项目二"));
    }

    @Test
    void preBlockPreservedWithIndentation() {
        String html = "<p>代码：</p><pre><code>if (a) {\n    b();\n}</code></pre>";
        String md = HtmlToMarkdown.convert(html);
        assertTrue(md.contains("```\nif (a) {\n    b();\n}\n```"), "实际输出: " + md);
        // 缩进在代码块内保留
        assertTrue(md.contains("    b();"));
    }

    @Test
    void scriptStyleHeadAndCommentsDropped() {
        String html = "<html><head><title>t</title><style>p{color:red}</style></head>"
                + "<body><!-- 注释 --><script>alert(1)</script><p>正文</p></body></html>";
        String md = HtmlToMarkdown.convert(html);
        assertTrue(md.contains("正文"));
        assertFalse(md.contains("alert"));
        assertFalse(md.contains("color:red"));
        assertFalse(md.contains("注释"));
        assertFalse(md.contains("t</title"));
    }

    @Test
    void entitiesDecoded() {
        String html = "<p>A &amp; B &lt;tag&gt; &quot;引&quot; &#x4E2D;&#25991; &mdash; 破折</p>";
        assertEquals("A & B <tag> \"引\" 中文 — 破折", HtmlToMarkdown.convert(html));
    }

    @Test
    void brBecomesNewline() {
        assertEquals("第一行\n第二行", HtmlToMarkdown.convert("第一行<br>第二行"));
    }

    @Test
    void emptyAndNullInput() {
        assertEquals("", HtmlToMarkdown.convert(null));
        assertEquals("", HtmlToMarkdown.convert(""));
    }

    @Test
    void malformedHtmlDropsTagsKeepsText() {
        assertEquals("未完闭文本", HtmlToMarkdown.convert("<p>未完闭文本"));
    }
}
