package com.markagent.embedding;

/**
 * 向量化提供者（三档）：
 * <ul>
 *   <li>百炼兼容端点（有 EMBEDDING_API_KEY 时由 Spring AI 自动装配 OpenAiEmbeddingModel）</li>
 *   <li>硅基流动 BGE（改 EMBEDDING_BASE_URL/EMBEDDING_MODEL 即切换，同为 OpenAI 兼容协议）</li>
 *   <li>无 key 降级：{@link KeywordEmbeddingProvider} 词袋向量（关键词检索）</li>
 * </ul>
 */
public interface EmbeddingProvider {

    /** 是否可用（无 embedding key 时为 false） */
    boolean available();

    /** 文本 → 向量（维度由实现决定；不同 provider 的向量不可混比） */
    double[] embed(String text) throws Exception;

    /** 检索依据的来源标识（如 "bailian-compatible: text-embedding-v3" 或 "keyword"） */
    String source();
}
