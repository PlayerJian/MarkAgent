package com.markagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text2SQL 全量评测跑批（60 题，需 server 已在 8090 运行）。
 * <p>
 * 每题流程：POST /chat（新会话）→ 轮询任务至终态（超时 6 分钟）→ GET events 拼最终答案
 * → 读 audit jsonl 取 agent 执行的 db_query SQL 与 schema 工具调用 → gold SQL 逐语句执行
 * → agent SQL 结果归一化比对（execution match）。4 路并发，进度实时打印，逐题落盘增量 jsonl。
 * <p>
 * 指标：SQL 覆盖度（gold 语句被 agent 查询结果复现的比例，题级=全语句复现即 match）、
 * 平均 schema 工具调用数（上下文效率）、平均 db_query 数、超时/失败列表。
 */
class EvalRunnerTest {

    private static final String BASE = "http://127.0.0.1:8090";
    private static final String JDBC = "jdbc:postgresql://127.0.0.1:5433/school";
    private static final int CONCURRENCY = 4;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(6);
    private static final String EVAL_ID = String.valueOf(System.currentTimeMillis());
    private static final Path PROGRESS_FILE = Path.of("D:/workspace/MarkAgent/data/eval/progress-" + EVAL_ID + ".jsonl");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static String authToken;

    @Test
    void evalAll60() throws Exception {
        Files.createDirectories(PROGRESS_FILE.getParent());
        authToken = login();

        JsonNode root = MAPPER.readTree(Path.of("D:/workspace/MarkAgent/docs/学校综合管理系统-Text2SQL问题集.json").toFile());
        JsonNode questions = root.get("questions");
        int limit = Integer.getInteger("eval.questionLimit", 0); // 冒烟用：-Deval.questionLimit=3
        int count = limit > 0 ? Math.min(limit, questions.size()) : questions.size();
        System.out.println("== EvalRunner: " + count + "/" + questions.size() + " questions, concurrency=" + CONCURRENCY);

        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(count);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<JsonNode> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int qi = 0; qi < count; qi++) {
            JsonNode q = questions.get(qi);
            pool.submit(() -> {
                try {
                    JsonNode r = evalOne(q);
                    results.add(r);
                    Files.writeString(PROGRESS_FILE, MAPPER.writeValueAsString(r) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    int n = done.incrementAndGet();
                    System.out.printf("== progress %d/%d id=%d %s (%.0fs)%n", n, questions.size(),
                            q.get("id").asInt(),
                            r.get("match").asBoolean() ? "MATCH" : "miss ",
                            r.get("durationSec").asDouble());
                } catch (Exception e) {
                    System.out.println("== question " + q.get("id") + " crashed: " + e);
                    done.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        // 汇总
        int total = results.size();
        int matched = (int) results.stream().filter(r -> r.get("match").asBoolean()).count();
        int judgeCorrect = (int) results.stream()
                .filter(r -> r.has("judgeVerdict") && r.get("judgeVerdict").asText().equals("correct")).count();
        int judgePartial = (int) results.stream()
                .filter(r -> r.has("judgeVerdict") && r.get("judgeVerdict").asText().equals("partial")).count();
        double avgCov = results.stream().mapToDouble(r -> r.get("coverage").asDouble()).average().orElse(0);
        double avgSchema = results.stream().mapToDouble(r -> r.get("schemaCalls").asDouble()).average().orElse(0);
        double avgDb = results.stream().mapToDouble(r -> r.get("dbQueries").asDouble()).average().orElse(0);
        double avgSec = results.stream().mapToDouble(r -> r.get("durationSec").asDouble()).average().orElse(0);
        System.out.println("========================================");
        System.out.printf("== RESULT: exec-match=%d/%d (%.1f%%)  judge-correct=%d judge-partial=%d (%.1f%%/%.1f%%)  avg-coverage=%.2f  avg-schema-calls=%.1f  avg-db-queries=%.1f  avg-duration=%.0fs%n",
                matched, total, matched * 100.0 / total, judgeCorrect, judgePartial,
                judgeCorrect * 100.0 / total, judgePartial * 100.0 / total,
                avgCov, avgSchema, avgDb, avgSec);
        List<JsonNode> failed = results.stream().filter(r -> r.get("status").asText().equals("TIMEOUT")
                || r.get("status").asText().equals("FAILED")).toList();
        System.out.println("== FAILED/TIMEOUT: " + failed.size() + " " + failed.stream()
                .map(r -> "Q" + r.get("id").asText() + "(" + r.get("status").asText() + ")").toList());
        System.out.println("== progress file: " + PROGRESS_FILE);
    }

    /** 单题评测：agent 全流程 + execution match 判定 */
    private JsonNode evalOne(JsonNode q) throws Exception {
        long start = System.currentTimeMillis();
        String question = q.get("question").asText();
        String goal = "请查询 school 数据库回答以下问题。用 search_modules 定位模块、load_module 读模块文档、"
                + "缺细节用 table_schema、db_query 执行只读 SQL，最后给出答案（含关键数字与所用 SQL）。"
                + "问题：" + question;

        // 1. 发起任务
        String taskId = postJson("/api/v1/chat", Map.of("goal", goal)).get("taskId").asText();

        // 2. 轮询终态
        String status = "RUNNING";
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode task = getJson("/api/v1/tasks/" + taskId);
            status = task.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED")) {
                break;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        if (!status.equals("COMPLETED") && !status.equals("FAILED") && !status.equals("CANCELLED")) {
            status = "TIMEOUT";
        }

        // 3. 答案：读磁盘 events 文件拼 TEXT delta。
        //    （GET /events API 有 JsonNode 序列化 bug（data 成 POJO 视图），文件格式正常，本地读文件最稳）
        Thread.sleep(1000); // 等最后一波事件落盘
        StringBuilder answer = new StringBuilder();
        Path eventFile = Path.of("D:/workspace/MarkAgent/data/events/" + taskId + ".jsonl");
        if (Files.isRegularFile(eventFile)) {
            for (String line : Files.readAllLines(eventFile, StandardCharsets.UTF_8)) {
                JsonNode e = MAPPER.readTree(line);
                if (e.has("type") && e.get("type").asText().equals("TEXT")
                        && e.get("data") != null && e.get("data").has("delta")) {
                    answer.append(e.get("data").get("delta").asText());
                }
            }
        }

        // 4. audit：db_query SQL 与 schema 工具调用
        List<String> agentSqls = new ArrayList<>();
        int schemaCalls = 0;
        List<String> auditLines = readAuditToday(taskId);
        for (String line : auditLines) {
            JsonNode a = MAPPER.readTree(line);
            String tool = a.get("toolName").asText();
            if (tool.equals("db_query") && a.has("args")) {
                JsonNode args = MAPPER.readTree(a.get("args").asText());
                if (args != null && args.has("sql")) {
                    agentSqls.add(args.get("sql").asText());
                }
            } else if (tool.equals("search_modules") || tool.equals("load_module")
                    || tool.equals("table_schema") || tool.equals("search_tables")) {
                schemaCalls++;
            }
        }

        // 5. gold SQL 逐语句执行；agent SQL 执行比对——按「交集列投影后行集合一致」判定
        //    （agent 多查了无关列仍算对；口径/过滤差异导致行内容不同才算错）
        double coverage = 0;
        int goldStmts = 0;
        int matchedStmts = 0;
        String goldResultText = "";
        try (Connection conn = DriverManager.getConnection(JDBC, "postgres", "school123456")) {
            List<QueryResult> goldResults = new ArrayList<>();
            for (String stmt : splitStatements(q.get("sql").asText())) {
                goldStmts++;
                QueryResult r = executeNormalized(conn, stmt);
                goldResults.add(r);
                if (r != null) {
                    goldResultText += "列: " + String.join(", ", r.cols()) + "\n"
                            + r.rows().stream().limit(15).map(row -> "  " + row).reduce("", (a, b) -> a + b + "\n")
                            + (r.rows().size() > 15 ? "  …（共 " + r.rows().size() + " 行）\n" : "");
                }
            }
            List<QueryResult> agentResults = new ArrayList<>();
            for (String sql : agentSqls) {
                QueryResult r = executeNormalized(conn, sql);
                if (r != null) {
                    agentResults.add(r);
                }
            }
            for (QueryResult gold : goldResults) {
                if (gold != null && agentResults.stream().anyMatch(a -> matches(gold, a))) {
                    matchedStmts++;
                }
            }
            coverage = goldStmts == 0 ? 0 : (double) matchedStmts / goldStmts;
        }

        // 6. LLM judge：答案文本 vs 问题 + gold 结果（execution match 对「答案组装在文本里」的场景失明）
        String judgeVerdict = "unknown";
        String judgeReason = "";
        if (status.equals("COMPLETED") && !answer.isEmpty()) {
            try {
                JsonNode judge = judge(question, goldResultText, answer.toString());
                judgeVerdict = judge.get("verdict").asText();
                judgeReason = judge.get("reason").asText();
            } catch (Exception e) {
                judgeReason = "judge 调用失败: " + e.getMessage();
            }
        } else if (!status.equals("COMPLETED")) {
            judgeVerdict = "wrong";
            judgeReason = "任务状态 " + status;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evalId", EVAL_ID);
        out.put("id", q.get("id").asInt());
        out.put("difficulty", q.get("difficulty").asInt());
        out.put("category", q.get("category").asText());
        out.put("question", question);
        out.put("status", status);
        out.put("match", coverage >= 1.0 && goldStmts > 0 && status.equals("COMPLETED"));
        out.put("coverage", coverage);
        out.put("goldStmts", goldStmts);
        out.put("matchedStmts", matchedStmts);
        out.put("judgeVerdict", judgeVerdict);
        out.put("judgeReason", judgeReason);
        out.put("schemaCalls", schemaCalls);
        out.put("dbQueries", agentSqls.size());
        out.put("agentAnswer", answer.toString().replace('\n', ' ').substring(0, Math.min(answer.length(), 800)));
        out.put("agentSqls", agentSqls);
        out.put("durationSec", (System.currentTimeMillis() - start) / 1000.0);
        return MAPPER.valueToTree(out);
    }

    /** 归一化查询结果：列名列表 + 排序行集合（NULL 标记、行内单元格 | 分隔） */
    record QueryResult(List<String> cols, List<String> rows) {
    }

    /** 交集列投影后的行集合一致判定（gold ⊆ agent 且 agent ⊆ gold 于公共列上） */
    private static boolean matches(QueryResult gold, QueryResult agent) {
        List<String> common = gold.cols().stream().filter(agent.cols()::contains).toList();
        if (common.isEmpty()) {
            return false;
        }
        List<Integer> gIdx = common.stream().map(gold.cols()::indexOf).toList();
        List<Integer> aIdx = common.stream().map(agent.cols()::indexOf).toList();
        Set<String> gRows = project(gold, gIdx);
        Set<String> aRows = project(agent, aIdx);
        return gRows.equals(aRows);
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

    /** gold/agent SQL 按分号拆多语句（含尾注释剥离） */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        for (String part : sql.split(";")) {
            String s = part.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 执行 SQL → 归一化结果（行排序、NULL 标记）；执行失败返回 null */
    private static QueryResult executeNormalized(Connection conn, String sql) {
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
            return null;
        }
    }

    /**
     * LLM judge：DeepSeek flash 直调，判答案文本 vs 问题 + gold 结果。
     * 返回 {"verdict": correct|partial|wrong, "reason": "..."}；调用失败抛异常由调用方置 unknown。
     */
    private static JsonNode judge(String question, String goldResultText, String answer) throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            key = ""; // 与 application.yml 默认一致（评测用途）
        }
        String prompt = "你是 Text2SQL 评测裁判。判断 AI 助手对数据查询问题的回答是否正确。\n"
                + "问题：" + question + "\n"
                + "正确答案的查询结果（gold SQL 执行，最多 15 行）：\n" + goldResultText + "\n"
                + "AI 助手回答：\n" + answer.substring(0, Math.min(answer.length(), 3000)) + "\n"
                + "输出 JSON：{\"verdict\": \"correct|partial|wrong\", \"reason\": \"一句话理由\"}。"
                + "判定标准：关键结论/数字与正确答案一致→correct；关键部分正确但有遗漏或口径偏差→partial；关键结论错误→wrong。";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-flash");
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("judge HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        return MAPPER.readTree(root.get("choices").get(0).get("message").get("content").asText());
    }

    private static List<String> readAuditToday(String taskId) {
        List<String> out = new ArrayList<>();
        try {
            Path file = Path.of("D:/workspace/MarkAgent/data/audit/" + java.time.LocalDate.now() + ".jsonl");
            if (!Files.isRegularFile(file)) {
                return out;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.contains("\"" + taskId + "\"")) {
                    out.add(line);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String login() throws Exception {
        JsonNode r = postJson("/api/v1/auth/login", Map.of("username", "admin", "password", "admin123"));
        return r.get("token").asText();
    }

    private static JsonNode postJson(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", authToken == null ? "" : "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException(path + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + authToken)
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException(path + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body());
    }
}
