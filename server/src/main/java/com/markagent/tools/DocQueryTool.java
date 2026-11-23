package com.markagent.tools;

import com.markagent.agent.model.TaskDocContext;
import com.markagent.agent.model.TaskUserContext;
import com.markagent.common.dto.DocRecord;
import com.markagent.docs.DocBlock;
import com.markagent.docs.LibraryBlockStore;
import com.markagent.docs.LibraryIndex;
import com.markagent.docs.LibraryIndexManager;
import com.markagent.store.DocStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档库检索工具（与 file_grep 同构，检索范围换成任务绑定的文档库索引）：
 * <ul>
 *   <li>doc_search：倒排索引召回（关键词子串语义）+ 前后邻块上下文，输出带「文件名 页码 块号 类型」引用</li>
 *   <li>doc_read：按文档/块区间/页读原文（模型扩上下文用，对应 grep 模式里的 Read 步骤）</li>
 * </ul>
 * 检索范围随任务 RuntimeContext 传递：注册器在工具调用入口把 docLibraryId/userId
 * 注入 TaskDocContext/TaskUserContext（同 TaskIdContext 模式），未绑定时报错。
 */
@Component
public class DocQueryTool {

    private static final int DEFAULT_TOP_K = 20;
    private static final int MAX_TOP_K = 50;
    private static final int DEFAULT_CONTEXT = 2;
    private static final int MAX_CONTEXT = 10;
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final LibraryIndexManager indexManager;
    private final DocStore docStore;

    public DocQueryTool(LibraryIndexManager indexManager, DocStore docStore) {
        this.indexManager = indexManager;
        this.docStore = docStore;
    }

    @Tool(name = "doc_search", description = "在任务绑定的文档库内检索内容块（关键词子串语义，类似 grep 但走索引，毫秒级）。"
            + "query 为空格分隔的关键词（如「商誉 减值」）；top_k 限制命中块数（缺省 20，最大 50）；"
            + "context_blocks 为每个命中附带的前后邻块数（缺省 2，最大 10）。"
            + "输出「[文件名 第P页·块N 类型·命中/上下文] 内容」，命中按相关度排序。"
            + "结果不足时换关键词重试，或用 doc_read 按文档/页读更多原文。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String search(
            @ToolParam(name = "query", required = true,
                    description = "空格分隔的关键词，如「商誉 减值」") String query,
            @ToolParam(name = "top_k", required = false,
                    description = "命中块数上限，缺省 20") Integer topK,
            @ToolParam(name = "context_blocks", required = false,
                    description = "每个命中附带的前后邻块数，缺省 2") Integer contextBlocks) {
        try {
            Scope scope = requireScope();
            if (query == null || query.isBlank()) {
                return "错误：query 不能为空";
            }
            int top = topK == null ? DEFAULT_TOP_K : Math.min(Math.max(topK, 1), MAX_TOP_K);
            int ctx = contextBlocks == null ? DEFAULT_CONTEXT
                    : Math.min(Math.max(contextBlocks, 0), MAX_CONTEXT);
            List<LibraryIndex.Hit> hits = indexManager.search(scope.userId(), scope.libraryId(), query, top);
            if (hits.isEmpty()) {
                return "无匹配结果";
            }
            LibraryBlockStore blocks = indexManager.blocks(scope.userId(), scope.libraryId());
            Set<String> shown = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            for (LibraryIndex.Hit hit : hits) {
                List<DocBlock> seq = blocks.get(hit.docId());
                if (seq.isEmpty()) {
                    continue;
                }
                String fileName = docStore.get(hit.docId()).map(DocRecord::fileName).orElse(hit.docId());
                int from = Math.max(0, hit.blockIdx() - ctx);
                int to = Math.min(seq.size() - 1, hit.blockIdx() + ctx);
                for (int i = from; i <= to; i++) {
                    if (!shown.add(hit.docId() + ":" + i)) {
                        continue;
                    }
                    DocBlock block = seq.get(i);
                    sb.append('[').append(fileName).append(" 第").append(block.page())
                            .append("页·块").append(i).append(' ')
                            .append(block.type().isBlank() ? "text" : block.type())
                            .append('·').append(i == hit.blockIdx() ? "命中" : "上下文").append("]\n")
                            .append(block.text()).append("\n\n");
                    if (sb.length() > MAX_OUTPUT_CHARS) {
                        return sb.append("…（截断）").toString();
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(name = "doc_read", description = "读取任务绑定文档库中某个文档的块区间/某页/某块为中心窗口的原文（页码引用）。"
            + "doc 为文档 id 或文件名；三种定位互斥："
            + "① from_block/to_block 绝对块号区间（缺省整篇）；② page 只读某页（1 起）；"
            + "③ around_block + window 以某块为中心扩窗（window 为中心两侧各取的块数，缺省 5）——"
            + "命中后觉得上下文不够就调大 window 重读，无需心算绝对区间。"
            + "输出「[文件名 第P页·块N] 内容」，中心块加标「中心」。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String read(
            @ToolParam(name = "doc", required = true, description = "文档 id 或文件名") String doc,
            @ToolParam(name = "from_block", required = false, description = "起始块号（含，缺省 0）") Integer fromBlock,
            @ToolParam(name = "to_block", required = false, description = "结束块号（含，缺省最后一块）") Integer toBlock,
            @ToolParam(name = "page", required = false, description = "只读某页（1 起），与块区间/中心块互斥") Integer page,
            @ToolParam(name = "around_block", required = false, description = "中心块号（doc_search 命中的块号），与块区间/页互斥") Integer aroundBlock,
            @ToolParam(name = "window", required = false, description = "中心块两侧各取的块数（缺省 5，配合 around_block）") Integer window) {
        try {
            Scope scope = requireScope();
            String docId = resolveDocId(scope, doc);
            if (docId == null) {
                return "错误：文档不存在 " + doc;
            }
            List<DocBlock> seq = indexManager.blocks(scope.userId(), scope.libraryId()).get(docId);
            if (seq.isEmpty()) {
                return "错误：文档无可用内容块";
            }
            String fileName = docStore.get(docId).map(DocRecord::fileName).orElse(docId);

            // 三种定位互斥校验
            int modes = (fromBlock != null || toBlock != null ? 1 : 0)
                    + (page != null ? 1 : 0) + (aroundBlock != null ? 1 : 0);
            if (modes > 1) {
                return "错误：from_block/to_block、page、around_block 三种定位互斥，只能取其一";
            }

            if (page != null) {
                return readPage(seq, fileName, page);
            }
            if (aroundBlock != null) {
                return readAround(seq, fileName, aroundBlock, window == null ? 5 : window);
            }
            return readRange(seq, fileName, fromBlock, toBlock);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /** ③ 以某块为中心扩窗：window 为中心两侧各取块数，中心块加标「中心」 */
    private static String readAround(List<DocBlock> seq, String fileName, int center, int window) {
        if (center < 0 || center >= seq.size()) {
            return "错误：中心块号越界（0 ~ " + (seq.size() - 1) + "）";
        }
        int w = Math.max(0, window);
        int from = Math.max(0, center - w);
        int to = Math.min(seq.size() - 1, center + w);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            DocBlock block = seq.get(i);
            sb.append('[').append(fileName).append(" 第").append(block.page())
                    .append("页·块").append(i).append(i == center ? "·中心" : "").append("]\n")
                    .append(block.text()).append("\n\n");
            if (sb.length() > MAX_OUTPUT_CHARS) {
                return sb.append("…（截断）").toString();
            }
        }
        return sb.toString();
    }

    /** ② 只读某页 */
    private static String readPage(List<DocBlock> seq, String fileName, int page) {
        StringBuilder sb = new StringBuilder("[" + fileName + " 第" + page + "页]\n");
        boolean any = false;
        for (DocBlock block : seq) {
            if (block.page() == page) {
                sb.append(block.text()).append("\n\n");
                any = true;
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    return sb.append("…（截断）").toString();
                }
            }
        }
        return any ? sb.toString() : "该页无内容块";
    }

    /** ① 绝对块号区间（缺省整篇） */
    private static String readRange(List<DocBlock> seq, String fileName, Integer fromBlock, Integer toBlock) {
        int from = fromBlock == null ? 0 : Math.max(0, fromBlock);
        int to = toBlock == null ? seq.size() - 1 : Math.min(seq.size() - 1, toBlock);
        if (from > to) {
            return "错误：块区间无效（from " + from + " > to " + to + "）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            DocBlock block = seq.get(i);
            sb.append('[').append(fileName).append(" 第").append(block.page())
                    .append("页·块").append(i).append("]\n")
                    .append(block.text()).append("\n\n");
            if (sb.length() > MAX_OUTPUT_CHARS) {
                return sb.append("…（截断）").toString();
            }
        }
        return sb.toString();
    }

    /** 当前任务检索范围（docLibraryId/userId 由注册器从 RuntimeContext 注入） */
    private static Scope requireScope() {
        String libraryId = TaskDocContext.get();
        String userId = TaskUserContext.get();
        if (libraryId == null || libraryId.isBlank() || userId == null) {
            throw new IllegalStateException("当前任务未绑定文档库");
        }
        return new Scope(userId, libraryId);
    }

    private record Scope(String userId, String libraryId) {
    }

    /** doc 参数先按文档 id 匹配，再按文件名匹配（须库内唯一，且属当前用户） */
    private String resolveDocId(Scope scope, String doc) {
        if (doc == null || doc.isBlank()) {
            return null;
        }
        var byId = docStore.get(doc);
        if (byId.isPresent() && byId.get().userId().equals(scope.userId())) {
            return doc;
        }
        String found = null;
        for (DocRecord record : docStore.list(scope.libraryId())) {
            if (record.fileName().equals(doc)) {
                if (found != null && !found.equals(record.id())) {
                    return null; // 库内同名文档不唯一，拒绝模糊匹配
                }
                found = record.id();
            }
        }
        return found;
    }
}
