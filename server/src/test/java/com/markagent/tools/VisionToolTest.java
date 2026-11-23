package com.markagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VisionTool 纯逻辑单测（不覆盖真实 HTTP 调用）：图片格式魔数判定、data URL 解码、
 * user 提示词组装、请求体结构与响应解析。
 */
class VisionToolTest {

    private VisionTool tool() {
        return new VisionTool("https://api.deepseek.com", "test-key", "deepseek-flash", 4096, "./data");
    }

    // ---------- 格式判定 ----------

    @Test
    void detectsFormatsByMagicBytes() {
        assertEquals("jpeg", VisionTool.detectFormat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0}));
        assertEquals("png", VisionTool.detectFormat(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals("gif", VisionTool.detectFormat("GIF89a".getBytes()));
        assertEquals("webp", VisionTool.detectFormat("RIFF1234WEBP".getBytes()));
    }

    @Test
    void rejectsUnknownFormats() {
        assertNull(VisionTool.detectFormat("hello world".getBytes()));
        assertNull(VisionTool.detectFormat(new byte[] {0, 1, 2}));
        assertNull(VisionTool.detectFormat(null));
    }

    // ---------- 图片来源解析 ----------

    @Test
    void decodesDataUrl() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        assertEquals("png", VisionTool.detectFormat(tool().resolveImage(url)));
    }

    @Test
    void readsLocalFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("shot.png");
        Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
        assertEquals("png", VisionTool.detectFormat(tool().resolveImage(file.toString())));
    }

    @Test
    void rejectsMissingLocalFile() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tool().resolveImage("no-such-dir/no-such-image.png"));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    void rejectsBadUrl() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tool().resolveImage("ftp://example.com/x.png"));
        assertTrue(e.getMessage().contains("http/https"));
    }

    @Test
    void rejectsBadBase64() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tool().resolveImage("data:image/png;base64,!!!not-base64!!!"));
        assertTrue(e.getMessage().contains("无法解码"));
    }

    // ---------- user 提示词组装 ----------

    @Test
    void userTextWithoutContextIsJustTask() {
        assertEquals("识别图中文字", VisionTool.buildUserText("识别图中文字", null));
        assertEquals("识别图中文字", VisionTool.buildUserText(" 识别图中文字 ", ""));
    }

    @Test
    void userTextWithContextCarriesGoalAndProgress() {
        String text = VisionTool.buildUserText("核实图中数据",
                "团队目标：核对财报表格；已完成：第1页数据已核对；本图：财报第2页截图");
        assertTrue(text.contains("[任务背景]"));
        assertTrue(text.contains("团队目标：核对财报表格"));
        assertTrue(text.contains("[本次任务]"));
        assertTrue(text.contains("核实图中数据"));
        assertTrue(text.indexOf("[任务背景]") < text.indexOf("[本次任务]"));
    }

    // ---------- 请求体结构 ----------

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestUsesOpenAiCompatibleBlockFormat() {
        Map<String, Object> request = tool().buildRequest("识别图中文字", "data:image/png;base64,AAAA");
        assertEquals("deepseek-flash", request.get("model"));
        assertEquals(4096, request.get("max_tokens"));
        // 不发 DeepSeek 专有 thinking 字段：保持对 qwen 等 OpenAI 兼容端点的通用性
        assertNull(request.get("thinking"));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        assertEquals(2, messages.size());
        Map<String, Object> system = messages.get(0);
        assertEquals("system", system.get("role"));
        assertTrue(((String) system.get("content")).contains("不要编造"));

        Map<String, Object> user = messages.get(1);
        assertEquals("user", user.get("role"));
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) user.get("content");
        assertEquals(2, blocks.size());
        assertEquals("text", blocks.get(0).get("type"));
        assertEquals("识别图中文字", blocks.get(0).get("text"));
        Map<String, Object> imageBlock = blocks.get(1);
        assertEquals("image_url", imageBlock.get("type"));
        Map<String, Object> imageUrl = (Map<String, Object>) imageBlock.get("image_url");
        assertEquals("data:image/png;base64,AAAA", imageUrl.get("url"));
        assertEquals("auto", imageUrl.get("detail"));
    }

    // ---------- 响应解析 ----------

    @Test
    void parsesContent() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"图中是一张发票\"}}]}";
        assertEquals("图中是一张发票", VisionTool.parseResponse(body));
    }

    @Test
    void emptyContentReportsFinishReason() {
        String body = "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}";
        String result = VisionTool.parseResponse(body);
        assertTrue(result.contains("未返回文本内容"));
        assertTrue(result.contains("content_filter"));
    }

    @Test
    void emptyChoicesReportsError() {
        assertTrue(VisionTool.parseResponse("{\"choices\":[]}").contains("空 choices"));
    }

    // ---------- 入口校验 ----------

    @Test
    void analyzeWithoutApiKeyFailsFast() {
        VisionTool noKey = new VisionTool("https://api.deepseek.com", "", "deepseek-flash", 4096, "./data");
        assertTrue(noKey.analyze("x.png", "识别文字", null).contains("未配置视觉模型 API Key"));
    }

    @Test
    void analyzeRejectsBlankParams() {
        VisionTool t = tool();
        assertTrue(t.analyze("", "识别文字", null).contains("image 参数为空"));
        assertTrue(t.analyze("x.png", "  ", null).contains("task 参数为空"));
    }
}
