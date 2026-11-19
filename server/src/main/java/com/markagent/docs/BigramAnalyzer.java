package com.markagent.docs;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ngram.NGramTokenizer;

/**
 * 字符 bigram 分析器：所有文本切成相邻两字（中文自然成词，ASCII 小写化），
 * 索引与查询同分析器 → 查询即子串语义（grep 等价），无需分词词典。
 */
public final class BigramAnalyzer extends Analyzer {

    public static final BigramAnalyzer INSTANCE = new BigramAnalyzer();

    private BigramAnalyzer() {
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new NGramTokenizer(2, 2);
        TokenStream filter = new LowerCaseFilter(tokenizer);
        return new TokenStreamComponents(tokenizer, filter);
    }
}
