package com.markagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 诊断工具：对指定题号执行 gold SQL 与 agent SQL，逐语句打印结果集差异
 * （列名/行数/交集列投影后行集合差集），定位 execution match 失败的具体原因。
 * 用法：mvn surefire:test -pl server -Dtest=EvalDiffTest -Ddiff.ids=3,6,8 -Ddiff.file=progress-xxx.jsonl
 */
class EvalDiffTest {

    private static final String JDBC = "jdbc:postgresql://127.0.0.1:5433/school";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record QueryResult(List<String> cols, List<String> rows) {
    }

    @Test
    void diffGoldVsAgent() throws Exception {
        String ids = System.getProperty("diff.ids", "3");
        String file = System.getProperty("diff.file", "");
        if (file.isBlank()) {
            // 默认取最新 progress 文件
            try (var files = Files.list(Path.of("D:/workspace/MarkAgent/data/eval"))) {
                file = files.filter(p -> p.getFileName().toString().startsWith("progress-"))
                        .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                        .findFirst().orElseThrow().getFileName().toString();
            }
        }
        List<JsonNode> progress = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("D:/workspace/MarkAgent/data/eval/" + file), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                progress.add(MAPPER.readTree(line));
            }
        }
        JsonNode questions = MAPPER.readTree(
                Path.of("D:/workspace/MarkAgent/docs/学校综合管理系统-Text2SQL问题集.json").toFile()).get("questions");

        try (Connection conn = DriverManager.getConnection(JDBC, "postgres", "school123456")) {
            for (String idStr : ids.split(",")) {
                int id = Integer.parseInt(idStr.trim());
                JsonNode p = null;
                for (JsonNode r : progress) {
                    if (r.get("id") != null && r.get("id").asInt() == id) {
                        p = r;
                    }
                }
                if (p == null) {
                    System.out.println("=== Q" + id + " not in progress file, skip ===");
                    continue;
                }
                JsonNode gold = null;
                for (JsonNode q : questions) {
                    if (q.get("id").asInt() == id) {
                        gold = q;
                    }
                }
                System.out.println("=== Q" + id + ": " + p.get("question").asText() + " ===");
                // gold 语句
                int g = 0;
                for (String stmt : split(gold.get("sql").asText())) {
                    g++;
                    QueryResult gr = execute(conn, stmt);
                    System.out.printf("-- gold stmt %d: %d cols x %d rows%n", g,
                            gr == null ? -1 : gr.cols().size(), gr == null ? -1 : gr.rows().size());
                    if (gr != null) {
                        System.out.println("   cols: " + gr.cols());
                        for (String row : gr.rows().subList(0, Math.min(3, gr.rows().size()))) {
                            System.out.println("   row: " + truncate(row, 150));
                        }
                    }
                    // 与每条 agent SQL 比对
                    int a = 0;
                    boolean anyMatch = false;
                    for (JsonNode sql : p.get("agentSqls")) {
                        a++;
                        QueryResult ar = execute(conn, sql.asText());
                        if (ar == null) {
                            continue;
                        }
                        List<String> common = gr.cols().stream().filter(ar.cols()::contains).toList();
                        Set<String> gRows = project(gr, gr.cols().stream().map(gr.cols()::indexOf).toList());
                        Set<String> aRows = project(ar, ar.cols().stream().map(ar.cols()::indexOf).toList());
                        if (gRows.equals(aRows)) {
                            anyMatch = true;
                        }
                        System.out.printf("   agent sql %d: %d cols x %d rows, common-cols=%d, rowset-equal=%s%n",
                                a, ar.cols().size(), ar.rows().size(), common.size(), gRows.equals(aRows));
                        if (!gRows.equals(aRows)) {
                            Set<String> onlyGold = new TreeSet<>(gRows);
                            onlyGold.removeAll(aRows);
                            Set<String> onlyAgent = new TreeSet<>(aRows);
                            onlyAgent.removeAll(gRows);
                            for (String row : onlyGold.stream().limit(2).toList()) {
                                System.out.println("     only-in-gold: " + truncate(row, 150));
                            }
                            for (String row : onlyAgent.stream().limit(2).toList()) {
                                System.out.println("     only-in-agent: " + truncate(row, 150));
                            }
                        }
                    }
                    System.out.println("   -> any agent sql matched: " + anyMatch);
                }
            }
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (!part.strip().isEmpty()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static QueryResult execute(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(60);
            try (ResultSet rs = st.executeQuery(sql)) {
                int cols = rs.getMetaData().getColumnCount();
                List<String> colNames = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    colNames.add(rs.getMetaData().getColumnLabel(i));
                }
                TreeSet<String> rows = new TreeSet<>();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) {
                        String v = rs.getString(i);
                        cells.add(v == null ? "NULL" : v);
                    }
                    rows.add(String.join("|", cells));
                }
                return new QueryResult(colNames, new ArrayList<>(rows));
            }
        } catch (Exception e) {
            System.out.println("   SQL 执行失败: " + truncate(e.getMessage(), 120));
            return null;
        }
    }

    private static Set<String> project(QueryResult r, List<Integer> idx) {
        Set<String> out = new TreeSet<>();
        for (String row : r.rows()) {
            String[] cells = row.split("\\|", -1);
            List<String> picked = new ArrayList<>();
            for (int i : idx) {
                picked.add(i < cells.length ? cells[i] : "NULL");
            }
            out.add(String.join("|", picked));
        }
        return out;
    }
}
