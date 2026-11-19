package com.markagent.docs;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 库级块倒排索引：磁盘 Lucene（MMapDirectory）+ 单写者 + NRT 搜索。
 * 一个 Lucene 文档 = 一个内容块；term = 字符 bigram；查询 = bigram 短语（子串语义）。
 * 块文本不存 Lucene——展示内容一律从 {@link LibraryBlockStore} 的原始块序列取，索引只做召回。
 */
public class LibraryIndex implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(LibraryIndex.class);

    static final String F_DOC = "docId";
    static final String F_BLOCK = "block";
    static final String F_PAGE = "page";
    static final String F_TYPE = "type";
    static final String F_TEXT = "text";

    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    private LibraryIndex(Directory directory, IndexWriter writer, SearcherManager searcherManager) {
        this.directory = directory;
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    public static LibraryIndex open(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Directory directory = FSDirectory.open(indexDir);
        IndexWriterConfig config = new IndexWriterConfig(BigramAnalyzer.INSTANCE);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        IndexWriter writer = new IndexWriter(directory, config);
        SearcherManager searcherManager = new SearcherManager(writer, null);
        return new LibraryIndex(directory, writer, searcherManager);
    }

    /** 写入文档的全部块（单写者；调用方保证先按 docId 删除旧块实现重入幂等） */
    public void addDocument(String docId, List<DocBlock> blocks) throws IOException {
        for (DocBlock block : blocks) {
            Document doc = new Document();
            doc.add(new StringField(F_DOC, docId, Field.Store.YES));
            doc.add(new IntPoint(F_BLOCK, block.index()));
            doc.add(new StoredField(F_BLOCK, block.index()));
            doc.add(new IntPoint(F_PAGE, block.page()));
            doc.add(new StoredField(F_PAGE, block.page()));
            doc.add(new StringField(F_TYPE, block.type(), Field.Store.YES));
            doc.add(new TextField(F_TEXT, block.text(), Field.Store.NO));
            writer.addDocument(doc);
        }
        writer.commit();
        searcherManager.maybeRefreshBlocking();
        log.debug("索引写入 doc={} blocks={}", docId, blocks.size());
    }

    public void deleteDocument(String docId) throws IOException {
        writer.deleteDocuments(new Term(F_DOC, docId));
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    /** 单个命中：文档 + 块号 + 页码 + 块类型 + 相关度得分 */
    public record Hit(String docId, int blockIdx, int page, String type, float score) {
    }

    /** 关键词检索：空格分隔多个词（SHOULD 组合），词内 bigram 短语保证子串语义 */
    public List<Hit> search(String query, int topK) throws IOException {
        List<String> terms = Arrays.stream(query.trim().split("\\s+"))
                .filter(s -> !s.isBlank()).toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder bool = new BooleanQuery.Builder();
        int clauses = 0;
        for (String term : terms) {
            PhraseQuery phrase = phraseQuery(term);
            if (phrase == null) {
                continue;
            }
            bool.add(phrase, BooleanClause.Occur.SHOULD);
            clauses++;
        }
        if (clauses == 0) {
            return List.of();
        }
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(bool.build(), Math.max(topK, 1));
            List<Hit> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new Hit(doc.get(F_DOC),
                        (int) doc.getField(F_BLOCK).numericValue().intValue(),
                        (int) doc.getField(F_PAGE).numericValue().intValue(),
                        doc.get(F_TYPE), scoreDoc.score));
            }
            return hits;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /** 词 → bigram 短语查询（位置相邻即精确子串）；不足 2 字符的词无 bigram，跳过 */
    private static PhraseQuery phraseQuery(String term) throws IOException {
        List<String> grams = new ArrayList<>();
        try (TokenStream stream = BigramAnalyzer.INSTANCE.tokenStream("", term)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                grams.add(attr.toString());
            }
            stream.end();
        }
        if (grams.isEmpty()) {
            return null;
        }
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        for (String gram : grams) {
            builder.add(new Term(F_TEXT, gram));
        }
        return builder.build();
    }

    @Override
    public void close() throws IOException {
        searcherManager.close();
        writer.close();
        directory.close();
    }
}
