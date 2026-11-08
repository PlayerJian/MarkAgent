package com.markagent.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 向量档：包装 Spring AI EmbeddingModel（OpenAI 兼容协议）。
 * 默认百炼兼容端点 text-embedding-v3；改 EMBEDDING_BASE_URL/MODEL 可切硅基流动 BGE。
 * 无 api-key 时自动装配不创建 EmbeddingModel bean，本 provider 不可用 → 降级关键词档。
 */
@Component
public class OpenAiCompatEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatEmbeddingProvider.class);

    private final EmbeddingModel model;

    public OpenAiCompatEmbeddingProvider(ObjectProvider<EmbeddingModel> modelProvider) {
        this.model = modelProvider.getIfAvailable();
        log.info("Embedding 模型装配：{}", model == null ? "无（降级关键词检索）" : "已就绪");
    }

    @Override
    public boolean available() {
        return model != null;
    }

    @Override
    public double[] embed(String text) throws Exception {
        EmbeddingResponse response = model.embedForResponse(java.util.List.of(text));
        if (response.getResults().isEmpty()) {
            throw new IllegalStateException("embedding 返回为空");
        }
        float[] vector = response.getResults().get(0).getOutput();
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i];
        }
        return result;
    }

    @Override
    public String source() {
        return "openai-compatible";
    }
}
