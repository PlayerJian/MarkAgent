package com.markagent.schemagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ER 图 HTML 解析：从「学校综合管理系统-ER图.html」的内嵌 JS 提取 E（实体关键列，含 PK/FK 标记）
 * 与 R（关系边数组，含模块归属/基数/中文标签）。
 * <p>
 * 源码形如：
 * <pre>
 *   const E = { sys_user:{m:'A',f:['bigint id PK','varchar username','varchar user_type']}, ... };
 *   const R = [ ['A','sys_user ||--o{ sys_user_role : "拥有"'], ... ];
 * </pre>
 */
final class ErParser {

    /** E 条目：表名 + 模块 + f 数组（'类型 列名 [PK|FK]' 的字符串列表） */
    private static final Pattern E_ENTRY = Pattern.compile(
            "(\\w+):\\{m:'([A-R])',f:\\[([^\\]]*)\\]");
    /** f 数组中的单个字段：类型 列名 [PK] [FK]（PK/FK 可出现任一/两个/没有） */
    private static final Pattern E_FIELD = Pattern.compile(
            "'(\\w+)\\s+(\\w+)\\s*(PK)?\\s*(FK)?'");
    /** R 条目：模块 + 关系串。关系：左表(1侧) ||--o{ 或 ||--o| 右表(子表) : "标签" */
    private static final Pattern R_ENTRY = Pattern.compile(
            "\\['([A-R])','(\\w+)\\s+\\|\\|--(o\\{|o\\|)\\s+(\\w+)\\s*:\\s*\"([^\"]*)\"'\\]");

    private ErParser() {
    }

    /** 解析 ER HTML：返回 (实体映射表名→ErEntity, 关系边列表) */
    static Result parse(Path erHtml) throws IOException {
        String html = Files.readString(erHtml);
        Map<String, Model.ErEntity> entities = new LinkedHashMap<>();
        Matcher em = E_ENTRY.matcher(html);
        while (em.find()) {
            String table = em.group(1);
            String module = em.group(2);
            List<Model.ErField> fields = new ArrayList<>();
            Matcher fm = E_FIELD.matcher(em.group(3));
            while (fm.find()) {
                fields.add(new Model.ErField(fm.group(2), fm.group(1),
                        fm.group(3) != null, fm.group(4) != null));
            }
            entities.put(table, new Model.ErEntity(table, module, fields));
        }
        List<Model.ErRelation> relations = new ArrayList<>();
        Matcher rm = R_ENTRY.matcher(html);
        while (rm.find()) {
            relations.add(new Model.ErRelation(rm.group(1), rm.group(2), rm.group(4),
                    rm.group(3), rm.group(5)));
        }
        return new Result(entities, relations);
    }

    record Result(Map<String, Model.ErEntity> entities, List<Model.ErRelation> relations) {
    }
}
