package com.markagent.schemagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表清单 MD 解析（学校综合管理系统-数据库表清单.md）：
 * <ul>
 *   <li>模块头「### A 基础与权限（14 张）」→ 模块代码/名称</li>
 *   <li>表行「| sys_user | 登录用户 | username, user_type(admin/teacher/student/parent), ... | 关联 |」
 *       → 中文名 / 关键字段 / 枚举线索（括号内以 / 或 、 分隔 2-10 段的视为枚举）/ 关联文本</li>
 *   <li>第三节「多态关联清单」→ 多态出边（源表.type列 + id列 → 目标表列表）</li>
 * </ul>
 */
final class TableListParser {

    /** 模块头 */
    private static final Pattern MODULE = Pattern.compile("^### ([A-R]) (.+?)（(\\d+) 张）");
    /** 表行（跳过表头与分隔行） */
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\| (\\w+) \\| ([^|]+?) \\| ([^|]*?) \\| ([^|]*?)\\s*\\|");
    /** 关键字段中的枚举线索：列名(值/值/...) 或 列名(值、值)，2-10 段、每段 1-10 字符、无空格 */
    private static final Pattern ENUM_HINT = Pattern.compile("(\\w+)\\(([^()]{1,60})\\)");
    /** 多态清单条目：N. 表.type列 + id列 → 目标表列表（说明） */
    private static final Pattern POLY = Pattern.compile(
            "^\\d+\\.\\s+(\\w+)\\.(\\w+)\\s*\\+\\s*(\\w+)\\s*→\\s*(.+?)(?:（([^）]+)）)?$");

    /** 多态出边（源表明确保留） */
    record PolyEdge(String sourceTable, String typeCol, String idCol, String targets, String note) {
    }

    record Result(List<Model.TableInfo> tables, List<PolyEdge> polymorphicEdges) {
    }

    /** 解析表清单：返回全部表信息 + 多态关联清单 */
    static Result parse(Path md) throws IOException {
        List<String> lines = Files.readAllLines(md);
        List<Model.TableInfo> tables = new ArrayList<>();
        List<PolyEdge> poly = new ArrayList<>();
        String moduleCode = "?";
        String moduleName = "?";
        boolean inPolySection = false;
        for (String line : lines) {
            Matcher mm = MODULE.matcher(line);
            if (mm.find()) {
                moduleCode = mm.group(1);
                moduleName = mm.group(2);
                continue;
            }
            if (line.contains("多态关联清单")) {
                inPolySection = true;
                continue;
            }
            if (inPolySection && (line.isBlank() || line.startsWith("**") || line.startsWith("---"))) {
                inPolySection = false;
            }
            if (inPolySection) {
                Matcher pm = POLY.matcher(line);
                if (pm.find()) {
                    poly.add(new PolyEdge(pm.group(1), pm.group(2), pm.group(3),
                            pm.group(4).trim(), pm.group(5) == null ? "" : pm.group(5)));
                    continue;
                }
            }
            Matcher rm = TABLE_ROW.matcher(line);
            if (!rm.find() || rm.group(1).equals("表名")) {
                continue;
            }
            String table = rm.group(1);
            String chinese = rm.group(2).trim();
            String keyFields = rm.group(3).trim();
            String relationText = rm.group(4).trim();
            Map<String, List<String>> hints = new LinkedHashMap<>();
            Matcher hm = ENUM_HINT.matcher(keyFields);
            while (hm.find()) {
                List<String> values = enumValues(hm.group(2));
                if (values != null) {
                    hints.put(hm.group(1), values);
                }
            }
            tables.add(new Model.TableInfo(moduleCode, moduleName, table, chinese, hints, relationText));
        }
        return new Result(tables, poly);
    }

    /** 括号内容是否为枚举值列表：2-10 段、每段 1-10 字符、只含中英文数字和 -，无空格 */
    private static List<String> enumValues(String content) {
        String[] parts = content.split("[/、]");
        if (parts.length < 2 || parts.length > 10) {
            return null;
        }
        for (String p : parts) {
            if (p.isBlank() || p.length() > 10 || !p.matches("[\\w\\u4e00-\\u9fff-]+")) {
                return null;
            }
        }
        return new ArrayList<>(Arrays.asList(parts));
    }

    /**
     * 关联文本 → 目标表名列表（仅主关联部分，标签/说明文字剔除）。
     * 支持倍数标记：dorm_room×2 → [dorm_room, dorm_room]；sys_user×2 同理。
     */
    static List<String> parseRelationTargets(String text) {
        if (text == null || text.isBlank() || text.equals("—")) {
            return List.of();
        }
        String after = text.contains("→") ? text.substring(text.indexOf('→') + 1) : text;
        after = after.split("[；。;]")[0];
        List<String> out = new ArrayList<>();
        for (String part : after.split("[,、/]")) {
            Matcher m = Pattern.compile("[a-z_][a-z_0-9]*").matcher(part);
            if (!m.find()) {
                continue;
            }
            String token = m.group();
            int times = 1;
            Matcher x = Pattern.compile("[×xX]\\s*(\\d+)").matcher(part);
            if (x.find()) {
                times = Math.min(Integer.parseInt(x.group(1)), 4); // 上限 4 防异常输入
            }
            for (int i = 0; i < times; i++) {
                out.add(token);
            }
        }
        return out;
    }
}
