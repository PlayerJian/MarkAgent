package com.markagent.schema;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Schema 目录工具集（Text2SQL 语义层入口）：search_modules（模块路由）→ load_module（模块文档）
 * → table_schema（缺表补全）→ search_tables（表名兜底）→ db_query 执行。
 * <p>
 * 文档来自离线生成器（data/schema-docs/），四块结构：列定义/枚举值/出边/入边；
 * 全局口径（class 保留字、date_base、空表、多态清单）在 load_module("glossary")。
 */
@Component
public class SchemaCatalogTool {

    private static final int MAX_MODULES = 3;
    private static final int MAX_TABLES = 15;

    private final SchemaCatalog catalog;

    public SchemaCatalogTool(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    /** 问题关键词 → 最相关模块（表级打分按模块聚合），返回 top-3 */
    @Tool(name = "search_modules", description = "按问题关键词在 schema 目录中检索最相关的业务模块（school 库 18 个模块，"
            + "如 图书馆/一卡通/考试与成绩/财务）。返回模块代码、名称、摘要与命中的表。"
            + "数据查询问题的第一步：先调本工具定位模块，再用 load_module 加载模块文档。"
            + "跨模块问题（如『罚款如何通过一卡通支付』）会命中多个模块，都加载。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String searchModules(@ToolParam(name = "question", required = true,
            description = "用户问题原文或关键短语（如「高二期中考试数学前50名」「图书馆逾期罚款」）") String question) {
        if (question == null || question.isBlank()) {
            return "错误：question 为空";
        }
        List<SchemaCatalog.ModuleHit> hits = catalog.searchModules(question, 3);
        if (hits.isEmpty()) {
            return "未命中任何模块。换关键词重试，或用 search_tables 直接搜表名/列名。";
        }
        StringBuilder sb = new StringBuilder("最相关模块（按相关度）：\n");
        for (SchemaCatalog.ModuleHit h : hits) {
            sb.append("- ").append(h.code()).append(" ").append(h.name())
                    .append("：").append(h.summary()).append('\n');
            if (!h.matchedTables().isEmpty()) {
                sb.append("  命中表：").append(String.join(", ", h.matchedTables())).append('\n');
            }
        }
        sb.append("下一步：load_module 加载需要的模块文档（可一次多个，逗号分隔）。");
        return sb.toString();
    }

    /** 模块文档全文（含表摘要卡与跨模块关联）；glossary 为全局口径 */
    @Tool(name = "load_module", description = "加载模块文档全文：模块内每张表的摘要卡（业务含义/关键列/枚举/出边入边）"
            + "与本模块的跨模块关联表清单。加载后即可据摘要卡确定查询需要的表和 JOIN 路径，"
            + "不必再逐表取文档。参数 modules 为模块代码列表（逗号分隔，最多 3 个），"
            + "如「M」或「M,I」；传「glossary」返回全局口径（时间基准、class 保留字、空表、多态清单）。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String loadModule(@ToolParam(name = "modules", required = true,
            description = "模块代码，逗号分隔最多 3 个（A-R 或 glossary），如「M,I」加载图书馆和一卡通") String modules) {
        if (modules == null || modules.isBlank()) {
            return "错误：modules 为空";
        }
        String[] codes = modules.trim().split("[,，\\s]+");
        if (codes.length > MAX_MODULES) {
            return "错误：一次最多加载 " + MAX_MODULES + " 个模块，请分批。";
        }
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            String body = catalog.moduleBody(code.trim());
            if (body == null) {
                sb.append("（模块 ").append(code).append(" 不存在。可用代码：");
                for (String c : catalog.modules().keySet()) {
                    sb.append(c).append(' ').append(catalog.modules().get(c).name()).append("、");
                }
                sb.append("glossary）\n");
                continue;
            }
            sb.append(body).append("\n");
        }
        return sb.toString();
    }

    /** 单表完整文档（缺表补全入口） */
    @Tool(name = "table_schema", description = "取指定表的完整文档：全部列定义（列名/类型/可空/说明）、"
            + "枚举列合法取值、出边关系（本表引用谁）、入边关系（谁引用本表）。"
            + "当模块文档摘要卡信息不足以写 SQL（需要精确列名/JOIN 列/过滤枚举值时）调用；"
            + "按需取表，一次最多 15 张，不要为凑数加载无关表。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String tableSchema(@ToolParam(name = "tables", required = true,
            description = "表名列表，逗号分隔（如「book_fine,wallet_transaction」）") String tables) {
        if (tables == null || tables.isBlank()) {
            return "错误：tables 为空";
        }
        List<String> names = List.of(tables.trim().split("[,，\\s]+"));
        if (names.size() > MAX_TABLES) {
            return "错误：一次最多取 " + MAX_TABLES + " 张表，请分批。";
        }
        return catalog.tableBodies(names);
    }

    /** 表名/列名/注释关键词检索（兜底） */
    @Tool(name = "search_tables", description = "按关键词检索表：匹配表名（权重高）/列名/中文名与关联关系文本。"
            + "不知道问题涉及哪张表时用本工具兜底（如「消费记录」→ wallet_transaction/meal_order）。"
            + "返回表名、中文名、所属模块与列名摘要；确定候选后可用 table_schema 取完整文档。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String searchTables(@ToolParam(name = "keyword", required = true,
            description = "关键词，空格分隔多个词（如「消费 流水」「罚金」）") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "错误：keyword 为空";
        }
        List<SchemaCatalog.TableHit> hits = catalog.searchTables(keyword, 10);
        if (hits.isEmpty()) {
            return "未命中任何表。换关键词重试（试试业务中文名，如「借阅」「报修」）。";
        }
        StringBuilder sb = new StringBuilder("匹配的表（按相关度）：\n");
        for (SchemaCatalog.TableHit h : hits) {
            sb.append("- ").append(catalog.snippet(h.table())).append('\n');
        }
        sb.append("下一步：对候选表用 table_schema 取完整文档确认列与关联。");
        return sb.toString();
    }
}
