package com.markagent.embedding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 降级档：无 embedding key 时用词袋向量做关键词检索。
 * 维度 = 全语料词表大小（动态），余弦相似度退化为共现词占比。
 * 中文按 2-gram + 单字切分，英文按词切分——不依赖任何外部服务。
 * 注意：不实现 EmbeddingProvider（避免与向量档 bean 注入歧义），由 MemoryService 直接引用。
 */
@Component
public class KeywordEmbeddingProvider {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "对", "把", "被", "这", "那", "有", "为",
            "the", "a", "an", "of", "to", "in", "on", "and", "or", "is", "are", "was");

    /** 关键词切分：中文 2-gram + 英文单词，去停用词去重 */
    public List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = text == null ? "" : text.toLowerCase();
        // 英文/数字词
        for (String word : normalized.split("[^a-z0-9]+")) {
            if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }
        // 中文 2-gram（相邻字符对）与单字
        StringBuilder han = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                han.append(c);
            }
        }
        String hanText = han.toString();
        for (int i = 0; i < hanText.length() - 1; i++) {
            tokens.add(hanText.substring(i, i + 2));
        }
        for (int i = 0; i < hanText.length(); i++) {
            String single = String.valueOf(hanText.charAt(i));
            if (!STOP_WORDS.contains(single)) {
                tokens.add(single);
            }
        }
        return new ArrayList<>(tokens);
    }

    /** 两个词表在统一词表上的词袋向量 */
    public double[] bagOfWords(List<String> tokens, List<String> vocabulary) {
        double[] vec = new double[vocabulary.size()];
        Set<String> tokenSet = new LinkedHashSet<>(tokens);
        for (int i = 0; i < vocabulary.size(); i++) {
            vec[i] = tokenSet.contains(vocabulary.get(i)) ? 1.0 : 0.0;
        }
        return vec;
    }

    /** 余弦相似度（同维度向量） */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
