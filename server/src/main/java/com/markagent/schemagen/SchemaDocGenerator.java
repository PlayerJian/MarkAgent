package com.markagent.schemagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Schema 文档生成器（离线一次跑）：三源合流 → 150 份表文档 + 18 份模块文档 + glossary。
 * <pre>
 * 用法：java -cp "server/target/classes;D:\dev\maven\repository\org\postgresql\postgresql\42.7.7\postgresql-42.7.7.jar" \
 *      com.markagent.schemagen.SchemaDocGenerator [--db-url jdbc:... --db-user u --db-pass p --docs-dir d:\...\docs --out-dir d:\...\data\schema-docs]
 * </pre>
 * 默认值指向 school 库（127.0.0.1:5433）与仓库 docs/、data/schema-docs。
 * <p>
 * 关系合流规则（列级出边）：
 * <ol>
 *   <li>ER R 边（父→子）+ 子表 FK 列名匹配（xxx_id / _xxx_id）→ 直接绑定</li>
 *   <li>未绑定 R 边与剩余 FK 列等数 → 按定义顺序绑定（⚠ 标记）</li>
 *   <li>表清单关联文本「→ a, b(标签), c」→ 为无 R 边的 FK 列补目标（等数顺序绑定）</li>
 *   <li>多态清单 → 多态出边（type 列 + id 列 → 多目标）</li>
 *   <li>入边 = 全部出边的反向聚合</li>
 * </ol>
 */
public final class SchemaDocGenerator {

    /** 18 模块的业务简介与检索关键词（模块名来自表清单 MD，此处只补语义） */
    private static final Map<String, ModuleMeta> MODULE_META = Map.ofEntries(
            Map.entry("A", new ModuleMeta("基础与权限",
                    "系统用户/角色/权限管理，数据字典，操作与登录日志，系统配置，文件附件，短信，定时任务",
                    "用户 角色 权限 字典 日志 配置 文件 短信 定时任务")),
            Map.entry("B", new ModuleMeta("组织架构",
                    "学校/校区/院系/年级/班级/楼栋/教室/学年/教研组等组织结构",
                    "学校 校区 院系 部门 年级 班级 楼栋 教室 学年 教研组")),
            Map.entry("C", new ModuleMeta("人员",
                    "学生/教师/家长档案，学籍异动，教师证书/培训/考勤/评价，学生健康档案与保险",
                    "学生 教师 家长 学籍 培训 证书 考勤 评价 健康 保险")),
            Map.entry("D", new ModuleMeta("教务",
                    "学期/科目/开课/课表/调课/教学计划与进度/教材征订/选修课/教室预约",
                    "教务 课表 课程 科目 教材 选修 教室 预约 调课 学期")),
            Map.entry("E", new ModuleMeta("考试与成绩",
                    "考试安排/成绩明细与统计/考场座位/监考/违纪/成绩申诉/题库/试卷",
                    "考试 成绩 排名 考场 监考 违纪 申诉 题库 试题 试卷")),
            Map.entry("F", new ModuleMeta("考勤与德育",
                    "课堂考勤/请假审批/奖惩/德育评价/考勤规则/考勤日汇总",
                    "考勤 请假 迟到 早退 缺勤 奖惩 处分 德育")),
            Map.entry("G", new ModuleMeta("财务",
                    "收费项目/账单/缴费/退费/费用减免/发票/财务对账/奖学金",
                    "财务 账单 缴费 退费 减免 发票 对账 奖学金 学费")),
            Map.entry("H", new ModuleMeta("家校与通知",
                    "公告通知与回执/作业布置提交/站内消息/家长会/家长反馈/问卷",
                    "通知 公告 作业 消息 家长会 反馈 问卷 家校")),
            Map.entry("I", new ModuleMeta("一卡通与门禁",
                    "校园卡发卡补办/钱包充值消费/门禁设备与通行规则/刷卡事件/访客/商户/设备维保/黑白名单/人脸",
                    "一卡通 校园卡 门禁 钱包 充值 消费 刷卡 通行 访客 商户 设备")),
            Map.entry("J", new ModuleMeta("宿舍",
                    "宿舍房间/住宿分配/归寝记录/卫生检查/调宿",
                    "宿舍 住宿 归寝 床位 调宿 卫生检查")),
            Map.entry("K", new ModuleMeta("资产与后勤",
                    "资产分类/资产台账/领用归还/盘点/报废/报修维修/耗材出入库/采购/供应商",
                    "资产 后勤 报修 维修 耗材 采购 供应商 盘点 报废")),
            Map.entry("L", new ModuleMeta("食堂",
                    "菜品/每周菜单/食品留样/订餐记录（订餐走一卡通支付）",
                    "食堂 菜品 菜单 订餐 留样 档口")),
            Map.entry("M", new ModuleMeta("图书馆",
                    "图书分类/书目/馆藏副本/借阅/预约/逾期罚金（校园卡借书，罚金走一卡通支付）",
                    "图书馆 图书 借阅 还书 预约 罚金 馆藏")),
            Map.entry("N", new ModuleMeta("体育与健康",
                    "体测成绩/体检/晨午检/运动会/运动项目/比赛成绩",
                    "体测 体检 晨检 午检 运动会 比赛 健康")),
            Map.entry("O", new ModuleMeta("校车",
                    "校车车辆/线路/站点/学生乘车分配/上下车刷卡",
                    "校车 线路 站点 乘车 刷卡 接送")),
            Map.entry("P", new ModuleMeta("安全保卫",
                    "巡更点/巡更计划/巡更记录/安全事件/应急演练/隐患排查",
                    "安保 巡更 安全 事件 演练 隐患")),
            Map.entry("Q", new ModuleMeta("招生",
                    "招生计划/报名申请/录取注册（注册即建学籍+首张账单）",
                    "招生 报名 录取 注册 入学")),
            Map.entry("R", new ModuleMeta("工作流引擎",
                    "流程定义/流程实例/审批任务（请假/采购/退费/调宿/奖学金等申请统一审批）",
                    "工作流 审批 流程 申请 审核")));

    /** 多态清单补丁：MD 第 4 条「access_list / face_feature 的 person_type + person_id」格式与正则不符，内置补全 */
    private static final List<TableListParser.PolyEdge> POLY_PATCH = List.of(
            new TableListParser.PolyEdge("access_list", "person_type", "person_id",
                    "student / teacher / visitor", "黑白名单人员"),
            new TableListParser.PolyEdge("face_feature", "person_type", "person_id",
                    "student / teacher / visitor", "人脸特征人员"));

    /** glossary 全局口径（源自问题集 meta 与表清单第三节，生成器内置维护） */
    private static final String GLOSSARY = """
            # 全局口径（Glossary）

            查询 school 库前先读本文件；与单表文档冲突时以本文件为准。

            ## 时间口径
            - 数据基准日：2026-11-21（周六）。「昨天」= 2026-11-20（周五），「本周」按周一 2026-11-16 起算，「本月」= 2026-11。
            - 当前学期：2026-2027 学年第一学期；考试数据含 9 月月考、10 月月考与 11 月 10 日 期中。
            - 问题中的相对时间（昨晚/上周/明天第3节）需先换算成绝对日期再写 SQL；今天是周六（无课表），校车周六不发车。

            ## SQL 约定
            - 主键统一 id BIGINT 自增；created_at / updated_at 每表默认存在（表文档不再重复）。
            - **class 是 PostgreSQL 保留字**：SQL 中必须写 `"class"`（双引号）。
            - 库内全部是**逻辑外键（无物理约束）**，JOIN 靠列名约定（xxx_id → xxx.id）；表文档的出边/入边已列全，按文档 JOIN。
            - 多态关联（无外键，靠 type 列区分）：
              1. card.owner_type + owner_id → student / teacher / visitor
              2. access_authorization.target_type + target_id → student
              3. access_event.person_type + person_id → student / teacher / visitor
              4. access_list / face_feature 的 person_type + person_id → 同上
              5. notice.target_scope + target_id → 全校 / 年级 / 班级
              6. attendance_rule.scope_type + scope_id → grade / class（全校时为空）
              7. sys_file.biz_type + biz_id → 任意业务单据
              8. consumable_stock.related_type + related_id → purchase_apply / asset_allocation
              9. flow_instance.biz_type + biz_id → 各申请单（请假/采购/退费/调宿/奖学金）

            ## 业务主线
            - 组织→人：school → campus → building → classroom/dorm_room；school_year → semester；grade → class → student；student/teacher/parent 各挂一个 sys_user（1:1）。
            - 教务→考试→成绩：semester+subject+class+teacher 确定 course；exam 按学期+年级组织；score = exam × student × subject 明细，score_summary 为聚合快照（可直查避免大聚合）。
            - **考试分场（易错）**：期中/月考按年级分场——同一个 start_date 对应多场考试（每个年级一场，grade_id 不同）。按考试统计（总分/均分/排名）必须按单场分组：GROUP BY exam_id（或 start_date + grade_id），不要只按 exam_type 聚合，否则多场考试成绩会被错误合并（如「期中总分」会变成多场期中的和）。
            - 一卡通：card 一卡一 wallet_account；消费走 wallet_transaction 关联 merchant；校园卡同时是借书证（book_borrow/book_reservation）、校车乘车凭证（bus_boarding）、订餐支付凭证（meal_order.transaction_id）、罚金支付凭证（book_fine.pay_transaction_id）。
            - 门禁→宿舍：dorm_check 由宿舍门禁 access_event 推导（source_event_id 溯源）；teacher_attendance、attendance_daily 由造数直接生成（与门禁无关联列）。
            - 审批流：leave_request / refund / scholarship_apply / dorm_transfer / purchase_apply 都带 flow_instance_id，统一走 flow_definition → flow_instance → flow_task。
            - 招生闭环：enrollment_plan → enrollment_apply → enrollment_admit，录取注册时生成 student 学籍与首张 fee bill。

            ## 数据现状
            - 总量约 136 万行。大表：attendance 47 万、access_event 29.5 万、attendance_daily 11.8 万、wallet_transaction 9.9 万、score 5.4 万。
            - **空表**：attendance_rule / book_reservation / dorm_inspection 当前 0 行——查询结果为空集是合法答案，不要误判为错误。
            - 过滤条件务必用表文档中的枚举值（status/type 类列的合法取值已列全），不要臆造。
            - 植入型异常数据（钱包坏账 75 笔、访客卡离校后复用 8 次、深夜进馆 4 次、巡更漏打、缺考等）是检测类题目的真实命中，正常查出即可。

            ## 查询规范
            - 先 search_modules 定位模块 → load_module 读模块文档 → 缺表再 table_schema 补表文档 → db_query 执行。
            - 按需取表，不要穷举；一次问题通常 ≤ 8 张表。
            - db_query 结果上限 100 行：需要更大结果集时用聚合（GROUP BY）压缩到 100 行以内。
            - 金额类数值聚合用 SUM/AVG 直接算，注意 NULL 用 COALESCE。
            """;

    private SchemaDocGenerator() {
    }

    public static void main(String[] args) throws Exception {
        String dbUrl = arg(args, "--db-url", "jdbc:postgresql://127.0.0.1:5433/school");
        String dbUser = arg(args, "--db-user", "postgres");
        String dbPass = arg(args, "--db-pass", "school123456");
        Path docsDir = Path.of(arg(args, "--docs-dir", "d:/workspace/MarkAgent/docs"));
        Path outDir = Path.of(arg(args, "--out-dir", "d:/workspace/MarkAgent/data/schema-docs"));

        System.out.println("== [1/5] connecting " + dbUrl + " (ANALYZE included) ...");
        try (PgMetaFetcher fetcher = PgMetaFetcher.connect(dbUrl, dbUser, dbPass)) {
            System.out.println("== [2/5] fetching PG metadata ...");
            List<Model.TableMeta> metas = fetcher.fetchTables();
            System.out.println("   tables=" + metas.size());

            System.out.println("== [3/5] parsing ER html + table-list md ...");
            ErParser.Result er = ErParser.parse(docsDir.resolve("学校综合管理系统-ER图.html"));
            TableListParser.Result list = TableListParser.parse(docsDir.resolve("学校综合管理系统-数据库表清单.md"));
            System.out.println("   er-entities=" + er.entities().size() + " er-relations=" + er.relations().size()
                    + " md-tables=" + list.tables().size() + " md-polymorphic=" + list.polymorphicEdges().size());

            System.out.println("== [4/5] collecting enum values (DISTINCT) ...");
            Map<String, Map<String, List<String>>> hints = PgMetaFetcher.hintsByTable(list.tables());
            Map<String, Map<String, List<String>>> enums = fetcher.collectEnums(metas, hints);

            System.out.println("== [5/5] resolving edges and rendering docs ...");
            new Renderer(metas, er, list, enums, outDir).renderAll();
        }
        System.out.println("== done -> " + outDir);
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return def;
    }

    record ModuleMeta(String name, String summary, String keywords) {
    }

    /** 渲染器：关系合流 → 表文档 + 模块文档 + glossary 落盘 */
    static final class Renderer {
        private final Map<String, Model.TableMeta> metasByTable = new TreeMap<>();
        private final Map<String, Model.ErEntity> erByTable;
        private final List<Model.ErRelation> relations;
        private final List<Model.TableInfo> infos;
        private final Map<String, Model.TableInfo> infoByTable = new LinkedHashMap<>();
        private final List<TableListParser.PolyEdge> polyEdges;
        private final Map<String, Map<String, List<String>>> enums;
        private final Path outDir;
        /** 模块代码 → 表列表（表清单顺序） */
        private final Map<String, List<String>> tablesByModule = new LinkedHashMap<>();
        /** 全部列级出边（含多态），按子表分组 */
        private final Map<String, List<Model.Edge>> outByTable = new LinkedHashMap<>();
        /** 占位边绑定失败明细（数量不等，保守丢弃） */
        private final Set<String> unmatchedList = new LinkedHashSet<>();
        /** pass2 绑定后的出边（含占位绑定与 MD 兜底结果），按子表分组；入边聚合与渲染统一读它 */
        private final Map<String, List<Model.Edge>> boundOutByTable = new LinkedHashMap<>();

        Renderer(List<Model.TableMeta> metas, ErParser.Result er, TableListParser.Result list,
                Map<String, Map<String, List<String>>> enums, Path outDir) {
            for (Model.TableMeta m : metas) {
                metasByTable.put(m.table(), m);
            }
            this.erByTable = er.entities();
            this.relations = er.relations();
            this.infos = list.tables();
            for (Model.TableInfo i : infos) {
                infoByTable.put(i.table(), i);
            }
            this.polyEdges = list.polymorphicEdges();
            this.enums = enums;
            this.outDir = outDir;
        }

        void renderAll() throws IOException {
            resolveEdges();
            bindAllTables();
            Files.createDirectories(outDir.resolve("tables"));
            Files.createDirectories(outDir.resolve("modules"));
            int edgeCount = 0;
            int enumCount = 0;
            for (Model.TableInfo info : infos) {
                Model.TableDoc doc = buildTableDoc(info);
                Files.writeString(outDir.resolve("tables").resolve(doc.table() + ".md"),
                        renderTableDoc(doc), StandardCharsets.UTF_8);
                tablesByModule.computeIfAbsent(info.moduleCode(), k -> new ArrayList<>()).add(info.table());
                edgeCount += doc.outEdges().size() + doc.inEdges().size();
                enumCount += doc.enums().size();
            }
            for (Map.Entry<String, List<String>> e : tablesByModule.entrySet()) {
                Files.writeString(outDir.resolve("modules").resolve(e.getKey() + ".md"),
                        renderModuleDoc(e.getKey(), e.getValue()), StandardCharsets.UTF_8);
            }
            Files.writeString(outDir.resolve("glossary.md"), GLOSSARY, StandardCharsets.UTF_8);
            System.out.println("   tables=" + infos.size() + " modules=" + tablesByModule.size()
                    + " edges=" + edgeCount + " enum-cols=" + enumCount
                    + " unmatched-edges=" + unmatchedList.size());
            if (!unmatchedList.isEmpty()) {
                System.out.println("   unmatched: " + String.join(", ", unmatchedList));
            }
            // 校验：表清单与库内实际表的一致性
            Set<String> dbTables = PgMetaFetcher.allTables(new ArrayList<>(metasByTable.values()));
            Set<String> docTables = new LinkedHashSet<>(infoByTable.keySet());
            Set<String> onlyDb = new LinkedHashSet<>(dbTables);
            onlyDb.removeAll(docTables);
            Set<String> onlyDoc = new LinkedHashSet<>(docTables);
            onlyDoc.removeAll(dbTables);
            System.out.println("   校验 库内表=" + dbTables.size() + " 文档表=" + docTables.size()
                    + " 仅库内=" + onlyDb + " 仅文档=" + onlyDoc);
        }

        /**
         * 关系合流第一步：R 边按子表列名三级匹配绑定（P_id / _P_id / 段匹配，先占先得——已用列不再参与匹配，
         * 防止 survey_id 被 survey_question 的段匹配抢绑）；未匹配的边留占位（fromCol="?"，inferred=true）；
         * 多态清单（含内置补丁）挂源表。
         */
        private void resolveEdges() {
            Map<String, Set<String>> usedColsByChild = new LinkedHashMap<>();
            for (Model.ErRelation r : relations) {
                if (!infoByTable.containsKey(r.to())) {
                    continue;
                }
                Set<String> used = usedColsByChild.computeIfAbsent(r.to(), k -> new LinkedHashSet<>());
                String matched = matchColumn(r.to(), r.from(), used);
                String child = r.to();
                if (matched != null) {
                    used.add(matched);
                } else if (r.card().equals("o|")) {
                    // 一对一 R 边方向可能与 FK 所在侧相反（如 enrollment_admit ||--o| student，FK 在左表）：
                    // 反向匹配成功则边挂到 from 表（from 持 FK → to）
                    Set<String> usedFrom = usedColsByChild.computeIfAbsent(r.from(), k -> new LinkedHashSet<>());
                    String reverse = matchColumn(r.from(), r.to(), usedFrom);
                    if (reverse != null) {
                        usedFrom.add(reverse);
                        Model.TableInfo target = infoByTable.get(r.to());
                        outByTable.computeIfAbsent(r.from(), k -> new ArrayList<>()).add(new Model.Edge(
                                reverse, r.to(), "id", r.label(),
                                target == null ? "?" : target.moduleCode(),
                                target == null ? "?" : target.moduleName(),
                                false, false));
                        continue;
                    }
                }
                Model.TableInfo parent = infoByTable.get(r.from());
                outByTable.computeIfAbsent(child, k -> new ArrayList<>()).add(new Model.Edge(
                        matched == null ? "?" : matched, r.from(), "id", r.label(),
                        parent == null ? "?" : parent.moduleCode(),
                        parent == null ? "?" : parent.moduleName(),
                        false, matched == null));
            }
            List<TableListParser.PolyEdge> allPoly = new ArrayList<>(polyEdges);
            allPoly.addAll(POLY_PATCH);
            for (TableListParser.PolyEdge p : allPoly) {
                Model.TableInfo source = infoByTable.get(p.sourceTable());
                outByTable.computeIfAbsent(p.sourceTable(), k -> new ArrayList<>()).add(new Model.Edge(
                        p.typeCol() + " + " + p.idCol(), p.targets(), "id", p.note(),
                        source == null ? "?" : source.moduleCode(),
                        source == null ? "?" : source.moduleName(),
                        true, false));
            }
        }

        /**
         * 子表列名 → 父表匹配（排除已用列）：
         * ① xxx_id == P_id / endsWith _P_id（如 head_teacher_id→teacher）
         * ② 列名 stem 与父表任一下划线分段相等（如 wallet_id→wallet_account、menu_id→weekly_menu、route_id→bus_route）
         * ③ 都不中 → null（占位，交给等数/关联文本兜底）。
         */
        private String matchColumn(String child, String parent, Set<String> used) {
            Model.TableMeta meta = metasByTable.get(child);
            if (meta == null) {
                return null;
            }
            for (Model.ColumnMeta c : meta.columns()) {
                if (!used.contains(c.name())
                        && (c.name().equals(parent + "_id") || c.name().endsWith("_" + parent + "_id"))) {
                    return c.name();
                }
            }
            for (Model.ColumnMeta c : meta.columns()) {
                if (used.contains(c.name()) || !c.name().endsWith("_id")) {
                    continue;
                }
                String stem = c.name().substring(0, c.name().length() - 3);
                for (String seg : parent.split("_")) {
                    if (seg.equals(stem)) {
                        return c.name();
                    }
                }
            }
            return null;
        }

        /** 关系合流第二步：每表占位边等数绑定 + MD 关联文本兜底，结果写回 boundOutByTable（全局唯一真相） */
        private void bindAllTables() {
            for (Model.TableInfo info : infos) {
                boundOutByTable.put(info.table(), bindTableEdges(info.table()));
            }
        }

        /**
         * 可绑定引用列（两级）：
         * <ol>
         *   <li>E 实体 FK 标记列（∩ bigint ∩ 未绑定）——语义可靠，优先</li>
         *   <li>全部 bigint 未绑定列（排除主键/时间戳）——覆盖 pay_transaction_id 等弱关联，
         *       但含 biz_id/target_id 等多态列，仅在前者数量不足时降级使用</li>
         * </ol>
         */
        private List<String> fkRefColumns(String table, Set<String> used) {
            Model.ErEntity er = erByTable.get(table);
            if (er == null) {
                return List.of();
            }
            return er.fields().stream()
                    .filter(Model.ErField::fk)
                    .map(Model.ErField::name)
                    .filter(c -> !used.contains(c))
                    .toList();
        }

        private List<String> allRefColumns(String table, Set<String> used) {
            Model.TableMeta meta = metasByTable.get(table);
            if (meta == null) {
                return List.of();
            }
            return meta.columns().stream()
                    .filter(c -> c.type().startsWith("bigint"))
                    .map(Model.ColumnMeta::name)
                    .filter(c -> !c.equals("id") && !c.equals("created_at") && !c.equals("updated_at"))
                    .filter(c -> !used.contains(c))
                    .toList();
        }

        /** 两级候选：FK 标记列优先；为空/不等数时降级全部 bigint 列 */
        private List<String> refCandidates(String table, Set<String> used, int needed) {
            List<String> fk = fkRefColumns(table, used);
            if (fk.size() == needed) {
                return fk;
            }
            List<String> all = allRefColumns(table, used);
            return all.size() == needed ? all : List.of();
        }

        /**
         * 单表占位边绑定（pass2 调用一次，结果全局共享）：
         * ① 占位边与候选引用列等数 → 顺序绑定（候选两级：E FK 标记列优先，全部 bigint 列降级）
         * ② 剩余引用列与 MD 关联文本目标等数 → 顺序绑定（覆盖 pay_transaction_id、fee_bill_id 等无 R 边弱关联）
         */
        private List<Model.Edge> bindTableEdges(String table) {
            Model.TableInfo info = infoByTable.get(table);
            List<Model.Edge> rawEdges = new ArrayList<>(outByTable.getOrDefault(table, List.of()));
            Set<String> usedCols = new LinkedHashSet<>();
            for (Model.Edge e : rawEdges) {
                if (!e.inferred()) {
                    usedCols.add(e.fromCol());
                }
            }
            List<Model.Edge> placeholders = rawEdges.stream().filter(Model.Edge::inferred).toList();
            List<String> mdTargets = TableListParser.parseRelationTargets(info.relationText());
            List<Model.Edge> outEdges = new ArrayList<>();
            List<String> freeRefs = refCandidates(table, usedCols, placeholders.size());
            for (Model.Edge e : rawEdges) {
                if (!e.inferred()) {
                    outEdges.add(e);
                    continue;
                }
                String col = freeRefs.isEmpty() ? null : freeRefs.get(placeholders.indexOf(e)); // 等数顺序绑定
                if (col != null) {
                    usedCols.add(col);
                    outEdges.add(new Model.Edge(col, e.toTable(), e.toCol(), e.label(),
                            e.toModuleCode(), e.toModuleName(), false, true));
                } else {
                    unmatchedList.add(table + " -> " + e.toTable());
                }
            }
            // MD 关联文本兜底
            if (!mdTargets.isEmpty()) {
                List<String> boundTargets = new ArrayList<>();
                for (Model.Edge e : outEdges) {
                    if (!e.polymorphic()) {
                        boundTargets.add(e.toTable());
                    }
                }
                List<String> freeTargets = mdTargets.stream().filter(t -> !boundTargets.contains(t)).toList();
                List<String> stillFree = refCandidates(table, usedCols, freeTargets.size());
                if (!stillFree.isEmpty() && stillFree.size() == freeTargets.size()) {
                    for (int i = 0; i < stillFree.size(); i++) {
                        Model.TableInfo target = infoByTable.get(freeTargets.get(i));
                        if (target != null) {
                            usedCols.add(stillFree.get(i));
                            outEdges.add(new Model.Edge(stillFree.get(i), freeTargets.get(i), "id", "",
                                    target.moduleCode(), target.moduleName(), false, true));
                        }
                    }
                }
            }
            return outEdges;
        }

        /** 组装单表文档：出边读 pass2 全局绑定结果，入边反向聚合也读全局（杜绝未绑定占位残留） */
        private Model.TableDoc buildTableDoc(Model.TableInfo info) {
            Model.TableMeta meta = metasByTable.get(info.table());
            List<Model.Edge> outEdges = boundOutByTable.getOrDefault(info.table(), List.of());

            // ── 列说明推导 ──
            Map<String, Model.Edge> edgeByCol = new LinkedHashMap<>();
            Map<String, String> polyByCol = new LinkedHashMap<>();
            for (Model.Edge e : outEdges) {
                if (e.polymorphic()) {
                    for (String col : e.fromCol().split(" \\+ ")) {
                        polyByCol.put(col, e.toTable() + (e.label().isBlank() ? "" : "（" + e.label() + "）"));
                    }
                } else {
                    edgeByCol.put(e.fromCol(), e);
                }
            }
            List<Model.ColumnDoc> columns = new ArrayList<>();
            if (meta != null) {
                for (Model.ColumnMeta c : meta.columns()) {
                    String note = c.comment();
                    if (note == null || note.isBlank()) {
                        if (c.name().equals("id")) {
                            note = "主键";
                        } else if (edgeByCol.containsKey(c.name())) {
                            Model.Edge e = edgeByCol.get(c.name());
                            note = "→ " + e.toTable() + "." + e.toCol()
                                    + (e.label().isBlank() ? "" : "（" + e.label() + "）");
                        } else if (polyByCol.containsKey(c.name())) {
                            note = "多态 → " + polyByCol.get(c.name());
                        } else if (c.name().equals("created_at")) {
                            note = "创建时间";
                        } else if (c.name().equals("updated_at")) {
                            note = "更新时间";
                        } else {
                            note = "";
                        }
                    }
                    columns.add(new Model.ColumnDoc(c.name(), c.type(), c.nullable(), note,
                            c.name().equals("id")));
                }
            }

            // ── 入边：其他表出边指向本表（读 pass2 绑定结果，多态不入边） ──
            List<Model.Edge> inEdges = new ArrayList<>();
            for (Map.Entry<String, List<Model.Edge>> entry : boundOutByTable.entrySet()) {
                if (entry.getKey().equals(info.table())) {
                    continue;
                }
                Model.TableInfo child = infoByTable.get(entry.getKey());
                for (Model.Edge e : entry.getValue()) {
                    if (!e.polymorphic() && e.toTable().equals(info.table())) {
                        inEdges.add(new Model.Edge(e.fromCol(), entry.getKey(), e.toCol(), e.label(),
                                child == null ? "?" : child.moduleCode(),
                                child == null ? "?" : child.moduleName(),
                                false, e.inferred()));
                    }
                }
            }
            inEdges.sort(Comparator.comparing(Model.Edge::toTable));

            // ── 枚举合并：表清单线索优先，实测 distinct 补缺 ──
            Map<String, List<String>> enums = new LinkedHashMap<>(info.enumHints());
            for (Map.Entry<String, List<String>> e : this.enums.getOrDefault(info.table(), Map.of()).entrySet()) {
                if (!enums.containsKey(e.getKey())) {
                    enums.put(e.getKey(), e.getValue());
                }
            }

            // ── 备注 ──
            String remark = info.relationText()
                    .replace("**", "")
                    .replaceFirst("^→\\s*", "");
            if (meta != null && meta.estRows() == 0) {
                remark = (remark.isBlank() || remark.equals("—") ? "" : remark + "；") + "当前 0 行，空结果是合法的";
            }
            return new Model.TableDoc(info.table(), info.chineseName(), info.moduleCode(), info.moduleName(),
                    meta == null ? 0 : meta.estRows(), remark, columns, enums, outEdges, inEdges);
        }

        /** 模块文档：frontmatter + 表摘要卡 + 跨模块关联 */
        private String renderModuleDoc(String code, List<String> tables) {
            ModuleMeta mm = MODULE_META.getOrDefault(code, new ModuleMeta(code, "", ""));
            StringBuilder sb = new StringBuilder();
            sb.append("---\nmodule: ").append(code).append(' ').append(mm.name())
                    .append("\nsummary: ").append(mm.summary())
                    .append("\nkeywords: ").append(mm.keywords())
                    .append("\ntables: ").append(String.join(", ", tables)).append("\n---\n\n");
            sb.append("# 模块 ").append(code).append(' ').append(mm.name()).append("\n\n");
            sb.append(mm.summary()).append("\n\n");
            sb.append("## 表摘要卡\n");
            for (String table : tables) {
                Model.TableInfo info = infoByTable.get(table);
                Model.TableMeta meta = metasByTable.get(table);
                Model.ErEntity er = erByTable.get(table);
                Model.TableDoc doc = buildTableDoc(info);
                sb.append("\n### ").append(table).append("（").append(info.chineseName()).append("）\n");
                sb.append("- 行数：约 ").append(String.format("%,d", meta == null ? 0 : meta.estRows())).append('\n');
                if (er != null) {
                    List<String> keyCols = er.fields().stream()
                            .filter(f -> !f.name().equals("id") && !f.name().equals("created_at")
                                    && !f.name().equals("updated_at"))
                            .map(f -> f.name() + (f.fk() ? "(FK)" : "")).toList();
                    if (!keyCols.isEmpty()) {
                        sb.append("- 关键列：").append(String.join(", ", keyCols)).append('\n');
                    }
                }
                for (Map.Entry<String, List<String>> e : doc.enums().entrySet()) {
                    sb.append("- 枚举：").append(e.getKey()).append(" = ")
                            .append(String.join("/", e.getValue())).append('\n');
                }
                List<Model.Edge> out = doc.outEdges().stream()
                        .filter(e -> e.toModuleCode().equals(code) || e.polymorphic()).toList();
                if (!out.isEmpty()) {
                    sb.append("- 出边：");
                    for (Model.Edge e : out) {
                        sb.append(e.fromCol()).append("→").append(e.toTable());
                        if (e.polymorphic()) {
                            sb.append("(多态)");
                        }
                        sb.append("；");
                    }
                    sb.append('\n');
                }
                List<Model.Edge> in = doc.inEdges().stream().filter(e -> e.toModuleCode().equals(code)).toList();
                if (!in.isEmpty()) {
                    sb.append("- 入边：");
                    for (Model.Edge e : in) {
                        sb.append(e.toTable()).append('.').append(e.fromCol()).append("；");
                    }
                    sb.append('\n');
                }
            }
            // 跨模块关联汇总
            List<String> cross = new ArrayList<>();
            for (String table : tables) {
                Model.TableDoc doc = buildTableDoc(infoByTable.get(table));
                for (Model.Edge e : doc.outEdges()) {
                    if (!e.toModuleCode().equals(code) && !e.toModuleCode().equals("?")) {
                        cross.add(table + "." + e.fromCol() + " → " + e.toTable() + "（模块 " + e.toModuleCode()
                                + " " + e.toModuleName() + "）" + (e.label().isBlank() ? "" : "：" + e.label()));
                    }
                }
                for (Model.Edge e : doc.inEdges()) {
                    if (!e.toModuleCode().equals(code) && !e.toModuleCode().equals("?")) {
                        cross.add(e.toTable() + "." + e.fromCol() + " → " + table + "（模块 " + e.toModuleCode()
                                + " " + e.toModuleName() + "）" + (e.label().isBlank() ? "" : "：" + e.label()));
                    }
                }
            }
            if (!cross.isEmpty()) {
                sb.append("\n## 跨模块关联\n\n");
                cross.stream().sorted().distinct().forEach(s -> sb.append("- ").append(s).append('\n'));
            }
            return sb.toString();
        }
    }

    /** 表文档渲染（纯函数） */
    private static String renderTableDoc(Model.TableDoc doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.table()).append("（").append(doc.chineseName()).append("）\n\n");
        sb.append("- 模块：").append(doc.moduleCode()).append(' ').append(doc.moduleName()).append('\n');
        sb.append("- 行数：约 ").append(String.format("%,d", doc.estRows())).append('\n');
        sb.append("\n## 列定义\n\n| 列 | 类型 | 可空 | 说明 |\n|---|---|---|---|\n");
        for (Model.ColumnDoc c : doc.columns()) {
            sb.append("| ").append(c.name()).append(" | ").append(c.type())
                    .append(" | ").append(c.nullable() ? "是" : "否")
                    .append(" | ").append(c.note() == null ? "" : c.note())
                    .append(" |\n");
        }
        if (!doc.enums().isEmpty()) {
            sb.append("\n## 枚举值\n\n| 列 | 取值（按频次降序） |\n|---|---|\n");
            for (Map.Entry<String, List<String>> e : doc.enums().entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ")
                        .append(String.join(" / ", e.getValue())).append(" |\n");
            }
        }
        if (!doc.outEdges().isEmpty()) {
            sb.append("\n## 出边关系（本表引用其他表）\n\n");
            for (Model.Edge e : doc.outEdges()) {
                sb.append("- ").append(edgeText(doc, e, true)).append('\n');
            }
        }
        if (!doc.inEdges().isEmpty()) {
            sb.append("\n## 入边关系（其他表引用本表）\n\n");
            for (Model.Edge e : doc.inEdges()) {
                sb.append("- ").append(edgeText(doc, e, false)).append('\n');
            }
        }
        if (doc.remark() != null && !doc.remark().isBlank() && !doc.remark().equals("—")) {
            sb.append("\n## 备注\n\n").append(doc.remark()).append('\n');
        }
        return sb.toString();
    }

    private static String edgeText(Model.TableDoc doc, Model.Edge e, boolean out) {
        StringBuilder sb = new StringBuilder();
        if (e.polymorphic()) {
            sb.append(e.fromCol()).append(" → ").append(e.toTable()).append("（多态，按 type 列区分目标）");
            if (!e.label().isBlank()) {
                sb.append("；").append(e.label());
            }
            return sb.toString();
        }
        if (out) {
            sb.append(e.fromCol()).append(" → ").append(e.toTable()).append('.').append(e.toCol());
        } else {
            sb.append(e.toTable()).append('.').append(e.fromCol()).append(" → 本表.").append(e.toCol());
        }
        if (!e.label().isBlank()) {
            sb.append("（").append(e.label()).append("）");
        }
        if (e.inferred()) {
            sb.append(" ⚠按顺序推测");
        }
        if (!e.toModuleCode().equals(doc.moduleCode())) {
            sb.append("；模块 ").append(e.toModuleCode()).append(' ').append(e.toModuleName());
        }
        return sb.toString();
    }
}
