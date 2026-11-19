package com.markagent.docs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库内各文档的块序列内存视图（块级"行偏移表"）：
 * 检索命中后按块号取邻块上下文，doc_read 按区间读原文——不复制文本进 Lucene，结果永远引用原始块序列。
 */
public class LibraryBlockStore {

    private final Map<String, List<DocBlock>> docs = new ConcurrentHashMap<>();

    public void put(String docId, List<DocBlock> blocks) {
        if (blocks != null && !blocks.isEmpty()) {
            docs.put(docId, List.copyOf(blocks));
        }
    }

    public List<DocBlock> get(String docId) {
        return docs.getOrDefault(docId, List.of());
    }

    public void remove(String docId) {
        docs.remove(docId);
    }

    public Set<String> docIds() {
        return docs.keySet();
    }
}
