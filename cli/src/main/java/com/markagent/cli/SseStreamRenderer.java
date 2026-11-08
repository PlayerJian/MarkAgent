package com.markagent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE 事件流渲染器：JDK HttpClient 手工解析（id/event/data 三行一组，空行分隔）。
 * 渲染约定：text 直出、reasoning 暗色、工具 [工具] 名 ✓/✗、派生 → worker、错误红、done 总结。
 * raw 模式逐条打印原始事件（调试用）。
 */
public class SseStreamRenderer {

    // ANSI 颜色常量（各命令共用；ESC 用 char 27 构造，避免源码转义歧义）
    private static final String ESC = Character.toString((char) 27);
    public static final String RESET = ESC + "[0m";
    public static final String DIM = ESC + "[2m";
    public static final String GREEN = ESC + "[32m";
    public static final String RED = ESC + "[31m";
    public static final String YELLOW = ESC + "[33m";
    public static final String CYAN = ESC + "[36m";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean raw;

    public SseStreamRenderer(boolean raw) {
        this.raw = raw;
    }

    /** 消费事件流并渲染，直到流关闭（服务端在终态后 close emitter） */
    public void render(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            String data = null;
            String id = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        dispatch(id, event, data);
                        System.out.flush();
                    }
                    event = data = id = null;
                    continue;
                }
                if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).stripLeading();
                }
                // ":" 开头为心跳注释行，忽略
            }
        } catch (java.io.IOException e) {
            // JDK HttpClient 在服务端正常关闭流时抛 "closed"，视为正常收尾
            if (e.getMessage() != null && e.getMessage().contains("closed")) {
                return;
            }
            throw new CliException("读取事件流失败: " + e.getMessage());
        }
    }

    private void dispatch(String id, String event, String data) {
        if (raw) {
            System.out.println(DIM + "[" + (id == null ? "?" : id) + "] " + event + RESET + " " + data);
            return;
        }
        JsonNode node;
        try {
            // 事件信封 {seq,type,ts,taskId,data:{...}}：业务载荷在 data 内
            node = MAPPER.readTree(data).path("data");
        } catch (Exception e) {
            node = null;
        }
        if (event == null || node == null || node.isMissingNode()) {
            return;
        }
        switch (event) {
            case "META" -> println(DIM + "会话 " + text(node, "sessionId") + " 模型 " + text(node, "model")
                    + (node.hasNonNull("planning") ? " 计划 " + text(node, "planning") : "") + RESET);
            case "TEXT" -> print(text(node, "delta"));
            case "REASONING" -> print(DIM + text(node, "delta") + RESET);
            case "TOOL_START" -> println(YELLOW + "[工具] " + text(node, "name") + RESET
                    + (node.hasNonNull("args") ? DIM + " " + compact(node.get("args")) + RESET : ""));
            case "TOOL_END" -> println(node.path("ok").asBoolean(true)
                    ? GREEN + "  ✓ " + RESET + text(node, "name") + summary(node)
                    : RED + "  ✗ " + RESET + text(node, "name") + summary(node));
            case "PLAN_UPDATE" -> renderPlan(node);
            case "AGENT_SPAWN" -> println(GREEN + "→ 派生 worker「" + text(node, "role") + "」("
                    + shortId(node.path("agentId").asText()) + ")" + RESET + DIM + " 目标: " + text(node, "goal") + RESET);
            case "AGENT_MSG" -> renderAgentMsg(node);
            case "AGENT_DONE" -> println(GREEN + "← worker 完成: " + RESET
                    + (node.path("ok").asBoolean(true) ? GREEN + "✓" : RED + "✗") + RESET
                    + DIM + " " + truncate(text(node, "summary"), 200) + RESET);
            case "MEMORY_UPDATE" -> println(DIM + "记忆 " + text(node, "op") + ": " + text(node, "target") + RESET);
            case "TOKEN" -> println(DIM + "上下文 " + node.path("used").asLong() + "/" + node.path("budget").asLong()
                    + "（" + Math.round(node.path("percent").asDouble(0) * 100) + "%）" + RESET);
            case "CHECKPOINT" -> println(DIM + "检查点已写入 " + text(node, "checkpointId") + RESET);
            case "PAUSED" -> println(YELLOW + "任务已暂停（恢复: markagent task resume <id>）" + RESET);
            case "ERROR" -> println(RED + "错误 [" + text(node, "code") + "] " + text(node, "message") + RESET);
            case "DONE" -> renderDone(node);
            default -> { /* 未知类型忽略 */ }
        }
    }

    private void renderPlan(JsonNode node) {
        println(CYAN + "计划:" + RESET);
        JsonNode steps = node.path("steps");
        String current = node.path("currentStepId").asText();
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String marker = switch (step.path("status").asText()) {
                    case "DONE", "COMPLETED" -> GREEN + " ✓ " + RESET;
                    case "FAILED" -> RED + " ✗ " + RESET;
                    case "RUNNING", "IN_PROGRESS" -> YELLOW + " ▶ " + RESET;
                    default -> DIM + " · " + RESET;
                };
                String title = step.path("title").asText("?");
                String detail = step.path("detail").asText("");
                println(marker + title + (step.path("id").asText().equals(current) ? YELLOW + " ←当前" + RESET : "")
                        + (detail.isBlank() ? "" : DIM + "　" + truncate(detail, 80) + RESET));
            }
        }
    }

    private void renderAgentMsg(JsonNode node) {
        String kind = node.path("kind").asText("text");
        switch (kind) {
            case "tool" -> println(DIM + "  [worker工具] " + text(node, "name") + RESET);
            case "tool_end" -> println(node.path("ok").asBoolean(true)
                    ? GREEN + "  ✓ " + RESET + text(node, "name")
                    : RED + "  ✗ " + RESET + text(node, "name"));
            default -> print(CYAN + text(node, "delta") + RESET);
        }
    }

    private void renderDone(JsonNode node) {
        System.out.println();
        println(GREEN + "✔ 任务完成（" + text(node, "status") + "）" + RESET);
        String summary = text(node, "summary");
        if (!summary.isBlank()) {
            System.out.println(summary);
        }
        JsonNode usage = node.path("usage");
        if (usage.isObject()) {
            println(DIM + "用量: " + usage.path("totalTokens").asLong() + " tokens, 迭代 "
                    + usage.path("iterations").asLong() + " 轮" + RESET);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String summary(JsonNode node) {
        String s = truncate(text(node, "resultSummary"), 100);
        return s.isBlank() ? "" : DIM + "　" + s + RESET;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    private static String compact(JsonNode node) {
        try {
            return truncate(MAPPER.writeValueAsString(node), 120);
        } catch (Exception e) {
            return "";
        }
    }

    private static void print(String s) {
        if (!s.isEmpty()) {
            System.out.print(s);
        }
    }

    private static void println(String s) {
        System.out.println(s);
    }
}
