package com.markagent.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 从 MinerU 产物目录读取块序列（页码 1 起）。
 * <ul>
 *   <li>4.x 优先 structured_content.json（blocks[].content 为字符串）</li>
 *   <li>回退 middle_json.json（content 为 {type,content} 片段数组）</li>
 *   <li>3.x 兼容 content_list.json（扁平数组带 page_idx）/ content_list_v2.json（每页一个数组）</li>
 * </ul>
 */
public final class BlockReader {

    private static final Logger log = LoggerFactory.getLogger(BlockReader.class);

    /** 3.x content_list 块结构键（提取文本时跳过） */
    private static final Set<String> SKIP_KEYS = Set.of(
            "type", "sub_type", "bbox", "page_idx", "level", "text_level",
            "image_source", "img_path", "path");

    private BlockReader() {
    }

    /** 读取产物目录的块序列；任何失败返回空列表（调用方决定降级策略） */
    public static List<DocBlock> read(Path mineruDir, ObjectMapper mapper) {
        try {
            List<DocBlock> blocks = fromPages(readJson(mineruDir, "structured_content.json", mapper), false);
            if (!blocks.isEmpty()) {
                return blocks;
            }
            blocks = fromPages(readJson(mineruDir, "middle_json.json", mapper), true);
            if (!blocks.isEmpty()) {
                return blocks;
            }
            Path md = findSingleMd(mineruDir);
            String stem = md == null ? "markdown"
                    : md.getFileName().toString().replaceFirst("(?i)\\.md$", "");
            blocks = fromContentList(readJson(mineruDir, stem + "_content_list.json", mapper), false);
            if (!blocks.isEmpty()) {
                return blocks;
            }
            return fromContentList(readJson(mineruDir, stem + "_content_list_v2.json", mapper), true);
        } catch (IOException e) {
            log.warn("块序列读取失败 {}，按空处理: {}", mineruDir, e.getMessage());
            return List.of();
        }
    }

    /** 4.x pages 结构：pages[].blocks[]；segmented=true 时 content 为片段数组（middle_json） */
    private static List<DocBlock> fromPages(JsonNode root, boolean segmented) {
        List<DocBlock> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode pages = root.path("pages");
        if (!pages.isArray()) {
            return out;
        }
        int index = 0;
        for (JsonNode page : pages) {
            int pageIdx = page.path("page_idx").asInt(0);
            for (JsonNode block : page.path("blocks")) {
                String text = segmented ? segmentedText(block.path("content"))
                        : blockText(block.path("content"));
                if (text.isBlank()) {
                    continue;
                }
                out.add(new DocBlock(index++, block.path("type").asText(""), text, pageIdx + 1));
            }
        }
        return out;
    }

    /** 3.x content_list：v2 时外层按页成数组，否则扁平数组带 page_idx */
    private static List<DocBlock> fromContentList(JsonNode root, boolean v2) {
        List<DocBlock> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        int index = 0;
        if (v2) {
            int page = 0;
            for (JsonNode pageItems : root) {
                if (pageItems.isArray()) {
                    for (JsonNode item : pageItems) {
                        String text = legacyText(item);
                        if (!text.isBlank()) {
                            out.add(new DocBlock(index++, item.path("type").asText(""), text, page + 1));
                        }
                    }
                }
                page++;
            }
        } else {
            for (JsonNode item : root) {
                String text = legacyText(item);
                if (text.isBlank()) {
                    continue;
                }
                int pageIdx = item.path("page_idx").asInt(-1);
                out.add(new DocBlock(index++, item.path("type").asText(""),
                        text, pageIdx >= 0 ? pageIdx + 1 : 1));
            }
        }
        return out;
    }

    /** content 为字符串/片段数组的对象通用扁平化 */
    private static String blockText(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText().strip();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode segment : content) {
                String text = blockText(segment.path("content"));
                if (!text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
            return sb.toString().strip();
        }
        if (content.isObject() && content.has("content")) {
            return blockText(content.get("content"));
        }
        return "";
    }

    /** middle_json 片段数组扁平化（同 blockText 的数组路径，保留换行结构） */
    private static String segmentedText(JsonNode content) {
        return blockText(content);
    }

    /** 3.x 块结构文本提取（对齐 agent_proj normalize_elements 的 _block_text 语义） */
    private static String legacyText(JsonNode item) {
        if (item == null) {
            return "";
        }
        if (item.isTextual()) {
            return item.asText().strip();
        }
        if (item.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode child : item) {
                String text = legacyText(child);
                if (!text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
            return sb.toString().strip();
        }
        if (item.has("content")) {
            return legacyText(item.get("content"));
        }
        StringBuilder sb = new StringBuilder();
        item.fields().forEachRemaining(entry -> {
            if (SKIP_KEYS.contains(entry.getKey())) {
                return;
            }
            String text = legacyText(entry.getValue());
            if (!text.isBlank()) {
                sb.append(text).append('\n');
            }
        });
        return sb.toString().strip();
    }

    private static JsonNode readJson(Path dir, String name, ObjectMapper mapper) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Path findSingleMd(Path mineruDir) throws IOException {
        if (!Files.isDirectory(mineruDir)) {
            return null;
        }
        List<Path> mds = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(mineruDir)) {
            stream.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .forEach(mds::add);
        }
        return mds.size() == 1 ? mds.get(0) : null;
    }
}
