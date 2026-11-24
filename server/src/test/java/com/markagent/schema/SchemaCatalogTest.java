package com.markagent.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchemaCatalog 加载与检索质量验证（读仓库真实生成产物 data/schema-docs）。
 * 断言即「60 题评测」的检索层基线：路由命中模块、兜底检索命中表。
 */
class SchemaCatalogTest {

    private SchemaCatalog catalog() {
        SchemaCatalog c = new SchemaCatalog(Path.of("D:/workspace/MarkAgent/data"));
        c.reload();
        return c;
    }

    @Test
    void loadsAllModulesAndTables() {
        SchemaCatalog c = catalog();
        assertEquals(18, c.modules().size(), "18 个模块文档");
        assertEquals(150, c.tableCount(), "150 张表文档");
        assertTrue(c.glossary().contains("class 是 PostgreSQL 保留字"), "glossary 含保留字口径");
    }

    @Test
    void routesLibraryFineQuestionToLibraryAndCardModules() {
        List<SchemaCatalog.ModuleHit> hits = catalog().searchModules("图书馆逾期罚款如何通过校园卡缴纳", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).code().equals("M") || hits.get(0).code().equals("I"),
                "图书馆/一卡通任一置顶，实际=" + hits.get(0).code());
    }

    @Test
    void routesExamRankingQuestionToExamModule() {
        List<SchemaCatalog.ModuleHit> hits = catalog().searchModules("期中考试高二数学年级前50名", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.code().equals("E")), "命中 考试与成绩 模块");
    }

    @Test
    void routesCrossModuleQuestionToBothModules() {
        List<SchemaCatalog.ModuleHit> hits = catalog().searchModules("食堂订餐走一卡通支付 消费统计", 3);
        assertTrue(hits.stream().anyMatch(h -> h.code().equals("L")), "命中 食堂");
        assertTrue(hits.stream().anyMatch(h -> h.code().equals("I")), "命中 一卡通与门禁");
    }

    @Test
    void searchTablesRecallsWalletTransactionByBusinessWord() {
        // 检索层定位为召回候选（top-10 内含目标表），最终裁决由模型结合 table_schema 完成
        List<SchemaCatalog.TableHit> hits = catalog().searchTables("消费记录", 10);
        assertTrue(hits.stream().anyMatch(h -> h.table().equals("wallet_transaction")),
                "消费记录候选含 wallet_transaction，实际=" + hits.stream().map(SchemaCatalog.TableHit::table).toList());
        assertTrue(hits.stream().anyMatch(h -> h.table().equals("meal_order")),
                "消费记录候选含 meal_order，实际=" + hits.stream().map(SchemaCatalog.TableHit::table).toList());
    }

    @Test
    void tableBodiesServeFullDocAndMissingTableHint() {
        String body = catalog().tableBodies(List.of("book_fine", "no_such_table"));
        assertTrue(body.contains("出边关系"), "完整文档含四块结构");
        assertTrue(body.contains("无文档"), "缺失表给出 DESCRIBE 兜底提示");
    }
}
