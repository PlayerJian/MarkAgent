package com.markagent.schemagen;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PG 元数据抓取：java.sql 直连（驱动 Class.forName 显式加载，与 integration.DefaultDatabaseProvider 同模式）。
 * <ul>
 *   <li>列定义：pg_attribute + col_description（含 COMMENT ON 注释，通常为空）</li>
 *   <li>行数：pg_class.reltuples 估算值（ANALYZE 后较准，文档标注「约」）</li>
 *   <li>枚举值：候选列跑 GROUP BY 取 distinct（启发式列名 + 表清单枚举线索），值数 &gt;25 判为非枚举</li>
 * </ul>
 * 表名/列名一律加双引号（class 是保留字）。
 */
final class PgMetaFetcher implements AutoCloseable {

    /** 枚举候选列名：status/type/method/direction/level/scope/category/gender/dimension/channel/result 等 */
    private static final Pattern ENUM_COL = Pattern.compile(
            "^(status|result|gender|direction|dimension|channel|role|relation|"
                    + ".*_type|.*_status|.*_method|.*_level|.*_stage|.*_scope|.*_category|"
                    + ".*_way|.*_mode|.*_flag|.*_grade|.*_period|.*_season|.*_limit)$",
            Pattern.CASE_INSENSITIVE);
    /** 明显非枚举的列名（值多/唯一码），直接跳过 DISTINCT */
    private static final Pattern SKIP_COL = Pattern.compile(
            "^(id|.*_id|.*_no|.*_code|.*_name|.*_time|.*_date|.*_json|.*_content|.*_desc|"
                    + ".*_phone|.*_no|.*_hash|.*_token|.*_url|.*_path|.*_ip|.*_remark|.*_reason|"
                    + "username|email|address|location|detail|description|comment|title|content)$",
            Pattern.CASE_INSENSITIVE);
    /** 单表枚举值输出上限 */
    private static final int MAX_ENUM_VALUES = 25;
    /** 枚举值收集单查询超时（秒） */
    private static final int ENUM_QUERY_TIMEOUT_SECONDS = 30;
    /** 跑枚举 DISTINCT 的行数上限（大流水表值域稳定但全量扫描慢，超限用估算行数直接跳过枚举采集） */
    private static final long ENUM_MAX_ROWS = 500_000;

    private final Connection conn;

    private PgMetaFetcher(Connection conn) {
        this.conn = conn;
    }

    static PgMetaFetcher connect(String jdbcUrl, String user, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
        // reltuples 估算依赖统计信息：灌数后未 ANALYZE 的表值为 -1，先全库分析一次（135 万行约十秒级）
        try (Statement st = conn.createStatement()) {
            st.execute("ANALYZE");
        }
        return new PgMetaFetcher(conn);
    }

    /** 拉取 public schema 下全部表：列定义（顺序=建表顺序）+ 估算行数 */
    List<Model.TableMeta> fetchTables() throws SQLException {
        String sql = """
                SELECT c.relname AS table_name, c.reltuples::bigint AS est_rows
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                ORDER BY c.relname
                """;
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getLong(2));
            }
        }
        String colSql = """
                SELECT c.relname AS table_name, a.attname AS column_name,
                       format_type(a.atttypid, a.atttypmod) AS data_type,
                       NOT a.attnotnull AS nullable,
                       col_description(a.attrelid, a.attnum) AS comment
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY c.relname, a.attnum
                """;
        Map<String, List<Model.ColumnMeta>> byTable = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(colSql)) {
            while (rs.next()) {
                String table = rs.getString(1);
                byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(new Model.ColumnMeta(
                        rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
            }
        }
        List<Model.TableMeta> out = new ArrayList<>();
        for (Map.Entry<String, List<Model.ColumnMeta>> e : byTable.entrySet()) {
            out.add(new Model.TableMeta(e.getKey(), rows.getOrDefault(e.getKey(), 0L), e.getValue()));
        }
        return out;
    }

    /**
     * 收集枚举值：候选 = 列名启发式匹配 ∪ 表清单枚举线索列；类型限定字符类/小整数。
     * 返回 table -> (列 -> 值列表)；值按频次降序，最多 {@value #MAX_ENUM_VALUES} 个。
     */
    Map<String, Map<String, List<String>>> collectEnums(
            List<Model.TableMeta> tables, Map<String, Map<String, List<String>>> enumHintsByTable) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Model.TableMeta table : tables) {
            if (table.estRows() == 0 || table.estRows() > ENUM_MAX_ROWS) {
                continue; // 空表无值可采；超大表跳过（枚举线索仍会进文档）
            }
            Map<String, List<String>> hints = enumHintsByTable.getOrDefault(table.table(), Map.of());
            for (Model.ColumnMeta col : table.columns()) {
                boolean candidate = hints.containsKey(col.name())
                        || (ENUM_COL.matcher(col.name()).matches() && !SKIP_COL.matcher(col.name()).matches());
                if (!candidate) {
                    continue;
                }
                String baseType = col.type().toLowerCase(Locale.ROOT);
                if (!(baseType.contains("char") || baseType.equals("text")
                        || baseType.startsWith("smallint") || baseType.startsWith("integer")
                        || baseType.startsWith("bigint"))) {
                    continue;
                }
                List<String> values = distinctValues(table.table(), col.name());
                if (values != null && values.size() >= 2 && values.size() <= MAX_ENUM_VALUES) {
                    out.computeIfAbsent(table.table(), k -> new LinkedHashMap<>()).put(col.name(), values);
                }
            }
        }
        return out;
    }

    /** 单列 distinct 值（频次降序）。超时/值过多/执行失败 → null（调用方丢弃） */
    private List<String> distinctValues(String table, String column) {
        String sql = "SELECT \"" + column + "\" AS v, count(*) AS c FROM \"" + table
                + "\" WHERE \"" + column + "\" IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT " + (MAX_ENUM_VALUES + 1);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(ENUM_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
                return values.size() > MAX_ENUM_VALUES ? null : values;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** 表清单枚举线索合并：线索列（如 pay_method(微信/支付宝/校园卡/银行)）恒进文档，实测值作为补充 */
    static Map<String, Map<String, List<String>>> hintsByTable(List<Model.TableInfo> infos) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Model.TableInfo info : infos) {
            if (!info.enumHints().isEmpty()) {
                out.put(info.table(), info.enumHints());
            }
        }
        return out;
    }

    /** 从 JDBC 连接读取全部表名集合（校验用） */
    static Set<String> allTables(List<Model.TableMeta> metas) {
        Set<String> out = new java.util.TreeSet<>();
        for (Model.TableMeta m : metas) {
            out.add(m.table());
        }
        return out;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
