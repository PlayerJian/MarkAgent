package com.markagent.schemagen;

import java.util.List;
import java.util.Map;

/**
 * Schema 文档生成器的数据模型（纯 record，便于传递）。
 * <p>
 * 输入三源：PG information_schema（列/类型/注释/行数）、ER 图 HTML（E 实体 FK 标记 + R 关系边）、
 * 表清单 MD（模块划分/中文名/关键字段枚举线索/关联文本）。合流后产出 TableDoc（150 份）与 ModuleDoc（18 份）。
 */
final class Model {

    private Model() {
    }

    /** PG 元数据：一张表的列定义 */
    record ColumnMeta(String name, String type, boolean nullable, String comment) {
    }

    /** PG 元数据：表 + 估算行数 + 列 */
    record TableMeta(String table, long estRows, List<ColumnMeta> columns) {
    }

    /** ER 图 HTML 中 E 实体：模块 + 关键列（含 PK/FK 标记） */
    record ErEntity(String table, String module, List<ErField> fields) {
    }

    record ErField(String name, String type, boolean pk, boolean fk) {
    }

    /** ER 图 HTML 中 R 关系边：from(1侧) --toCard--> to(子表)。card: o{=一对多, o|=一对一 */
    record ErRelation(String module, String from, String to, String card, String label) {
    }

    /** 表清单 MD：模块代码/名称、表中文名、关键字段枚举线索、关联文本 */
    record TableInfo(String moduleCode, String moduleName, String table, String chineseName,
                     Map<String, List<String>> enumHints, String relationText) {
    }

    /** 最终单表文档的列：说明 = PG 注释（缺省时由关系/枚举线索推导） */
    record ColumnDoc(String name, String type, boolean nullable, String note, boolean pk) {
    }

    /** 列级关系边：fromCol 引用 toTable.toCol。inferred=true 表示列名未直接匹配、按启发式绑定 */
    record Edge(String fromCol, String toTable, String toCol, String label,
                String toModuleCode, String toModuleName, boolean polymorphic, boolean inferred) {
    }

    /** 最终单表文档 */
    record TableDoc(String table, String chineseName, String moduleCode, String moduleName,
                    long estRows, String remark, List<ColumnDoc> columns,
                    Map<String, List<String>> enums, List<Edge> outEdges, List<Edge> inEdges) {
    }
}
