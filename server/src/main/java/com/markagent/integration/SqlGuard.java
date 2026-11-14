package com.markagent.integration;

import java.util.regex.Pattern;

/**
 * SQL 安全护栏（db_query 用）：纯函数，只允许只读单语句查询。
 * <p>
 * 规则（全部保守从严，宁拒勿放）：
 * <ul>
 *   <li>剥离前导空白与注释（-- / # 行注释、&#47;* *&#47; 块注释，循环剥离）后判定起始关键字</li>
 *   <li>白名单：SELECT / SHOW / DESCRIBE / EXPLAIN / WITH（大小写不敏感，整词匹配）</li>
 *   <li>禁止多语句：任一「;」之后存在非空白内容即拒绝（字符串字面量内的分号同样拦截，注释里
 *       的分号场景 v1 不豁免——保守设计，误拦可接受）</li>
 * </ul>
 * 纯函数便于单测；实现不得抛出异常。
 */
final class SqlGuard {

    /** 允许的语句起始关键字（整词匹配：SELECTX 之类不通过） */
    private static final Pattern ALLOWED_START = Pattern.compile(
            "^(SELECT|SHOW|DESCRIBE|EXPLAIN|WITH)\\b", Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    /**
     * 校验 SQL。返回 null 表示通过；否则返回面向模型的中文错误文本（直接作为工具结果返回，
     * 便于模型据此修正参数重试）。
     */
    static String check(String sql) {
        if (sql == null) {
            return "错误：SQL 为空";
        }
        String stripped = stripLeading(sql);
        if (stripped.isEmpty()) {
            return "错误：SQL 为空";
        }
        if (!ALLOWED_START.matcher(stripped).find()) {
            return "错误：仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN/WITH 开头的只读查询，已拒绝执行";
        }
        if (hasTrailingStatement(stripped)) {
            return "错误：禁止多语句（分号后不允许再有内容），已拒绝执行";
        }
        return null;
    }

    /** 剥离前导空白与注释（-- / # 行注释、&#47;* *&#47; 块注释，循环剥离直到语句首字符） */
    static String stripLeading(String sql) {
        String s = sql;
        while (true) {
            String trimmed = s.stripLeading();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                int nl = trimmed.indexOf('\n');
                s = nl < 0 ? "" : trimmed.substring(nl + 1);
            } else if (trimmed.startsWith("/*")) {
                int end = trimmed.indexOf("*/", 2);
                s = end < 0 ? "" : trimmed.substring(end + 2);
            } else {
                return trimmed;
            }
        }
    }

    /** 任一「;」之后存在非空白内容即视为多语句（尾分号「SELECT 1;」合法） */
    static boolean hasTrailingStatement(String sql) {
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == ';' && !sql.substring(i + 1).isBlank()) {
                return true;
            }
        }
        return false;
    }
}
