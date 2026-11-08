# M8 验收记录：加固 + PWA + 发布

验收日期：2026-11-07　验收人：Claude

## 交付内容

### 1. 多用户隔离加固

- `ChatController` 任务级端点（`GET /tasks/{id}`、`stream`、`pause`、`resume`、`stop`）
  新增 `requireOwned` 归属校验：非本人任务一律 **404**（不泄露他人任务存在性）。
  此前这些端点无归属检查，任何登录用户可查看/暂停/停止他人任务。
- 实测（alice 越权访问 admin 任务 t_95586038g229dj）：
  详情 404 ✓　pause 404 ✓　stream 404 ✓　alice 自身列表 200 [] ✓

### 2. TeamTool worker 收尾（pause/stop 联动）

- `TeamTool` 登记每任务存活 worker 流订阅（`workerSubs`），新增 `interruptTask(taskId)` / `cleanup(taskId)`；
  `AgentChatService.pause()`/`stop()` 调用 `interruptTask` 立即 dispose worker 流，
  `finish()` 清理登记。消除「任务暂停后工具线程干等 10 分钟 latch 超时」的遗留问题。
- 实测：3 worker 并行运行中 pause，**1 秒内返回**（此前会等到 worker 全部跑完/超时）；
  随后 resume 续跑至 COMPLETED ✓

### 3. PWA（web/）

- `vite-plugin-pwa`（registerType=autoUpdate，skipWaiting+clientsClaim，新版本即生效）
- 图标：`scripts/gen-pwa-icons.mjs` 纯 Node（zlib 手写 PNG 编码）生成 192/512（maskable 安全区）
- manifest：中文名/主题色/standalone；index.html 补 apple-touch-icon / mobile-web-app-capable
- 产物：`dist/sw.js` + `manifest.webmanifest` + precache 18 项（1.39MB）

### 4. 移动端布局（<768px）

- `MainLayout` 新增底部导航（对话/任务/记忆/技能/MCP，safe-area 适配），设置入口收进顶栏用户菜单；
  桌面 Sider 隐藏，内容区 `mobile-pad` 防遮挡。ChatPage 单列流式布局天然适配。

### 5. server 静态托管 + jar 内嵌前端

- `SpaForwardController`：SPA 深链（/tasks/{id} 等）forward /index.html
- `SecurityConfig`：页面壳与静态资源（含 /sw.js、/manifest.webmanifest、/pwa-*.png）permitAll，
  数据仍由 /api/** JWT 门控
- `server/pom.xml` maven-resources-plugin：`web/dist` 在 process-resources 拷入
  `target/classes/static`（jar 自带前端；无 dist 时跳过 = 纯 API 模式）
- `WebConfig`：`markagent.web.static-locations` 非默认值（如 file:../web/dist/）时注册外部静态位置
- 实测（打包 jar）：`/` 200 html、深链 200、sw.js/manifest/pwa-192.png 200、
  无 token API 401、前端 JS bundle 200 ✓

### 6. chunk 分片（1.4MB 单包 → 分片）

| chunk | 大小（gzip） |
|---|---|
| index（自有代码） | 36KB（13KB） |
| vendor-react | 41KB（15KB） |
| vendor-markdown | 164KB（49KB） |
| vendor-icons | 23KB（8KB） |
| vendor-antd | 1.13MB（351KB，antd 单体库固有） |

chunkSizeWarningLimit 放宽至 1200KB 并注明原因；自有代码与依赖分离，长效缓存友好。

### 7. 发布说明

- `README.md`：架构/能力矩阵/快速开始/发布打包/配置要点/数据布局/API 协议/里程碑状态/已知限制
- 注意：`markagent.storage.root`（默认 ./data）相对**运行目录**——发布时建议在期望的
  data 父目录下启动 jar（本仓库开发态固定为 server/data）

## 回归验证

| 项 | 结果 |
|---|---|
| CLI 多智能体任务（spawn worker + calculator） | ✅ COMPLETED |
| 3 worker 运行中 pause（1s）→ resume → COMPLETED | ✅ |
| 静态托管全链路（含 PWA 文件） | ✅ |
| 多用户越权访问 404 | ✅ |
| server 打包（jar 内嵌 web/dist） | ✅ |

## 补录：自研 web_search 工具（M8 后追加，2026-11-07）

**需求**：不依赖任何搜索 API 服务的自研 web 搜索工具。

- `WebSearchTool`（`@Tool(name="web_search")`，strict/readOnly/concurrencySafe）：
  零新依赖，JDK HttpClient + 纯正则解析 HTML（与 HttpRequestTool / MarkAgentToolRegistrar 的绕依赖风格一致）
- 通道自动降级：**Bing（cn.bing.com，主）→ DuckDuckGo（html.duckduckgo.com，备）**。
  本机网络实测 DDG 完全超时不可达（curl 15s timeout），故 Bing 为主通道；DDG 备通道供可达其网络的部署使用
- 解析：Bing `b_algo` 块（h2 标题链接 + b_lineclamp 摘要，含 /ck/a 跳转还原）；
  DDG `result__title` 块（result__a + uddg 跳转还原）；HTML 实体解码（命名 + 数字码点）
- 注册：主 agent（AgentScopeConfig）+ worker（WorkerAgentFactory/TeamTool 传参链），
  sys-prompt 追加 web_search 使用指引
- **验证**：
  - 独立冒烟（javac 反射直调，不经 LLM）：中英文 query 各返回 3 条结构完整结果
    （标题/真实网址/摘要），实体解码干净
  - CLI 端到端：chat 触发 web_search 4 次（TOOL_START/TOOL_END + 结果文本），
    agent 对结果做相关性判断后改用 http_get 抓 Maven 官方元数据交叉验证，任务 COMPLETED
  - 多智能体路径：spawn_agent 派生「资深技术调研员」worker 调用 web_search 检索
    DeepSeek 最新模型并交叉验证（web_search×多轮 + http_get），汇报含可靠性声明，COMPLETED
- 已知限制：HTML 抓取受搜索引擎反爬/限流影响，结果仅为网页快照，需交叉验证

## 补录：SSE 流完成时 async dispatch 授权拒绝（M8 后修复，2026-11-08）

**现象**：每次 SSE 流完成（任务终态 → `emitter.complete()` 由 agent 线程触发），服务端日志报
`AuthorizationDeniedException: Access Denied`（`CoyoteAdapter.asyncDispatch` → `AuthorizationFilter`），
并伴随「response already committed」错误页告警。用户在前端正常收到全部事件与完成提示——异常
发生在事件投递之后，客户端无感，只有服务端日志被污染。

**根因**：Security 7 的 `AuthorizationFilter` 默认过滤**全部 dispatcher 类型**。`emitter.complete()`
触发 Tomcat 的 ASYNC 再分发，安全过滤链重新执行时安全上下文未被恢复（匿名），
`anyRequest().authenticated()` 拒绝 → 响应已提交，`ExceptionTranslationFilter` 无法兜底。
与模型选择（极速/其他）、客户端（web/CLI）无关——历史上每次流完成都会发生
（M7b/M8 各次 CLI 验收的服务端日志中均有同栈）。

**修复**（`SecurityConfig`）：`dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()`
——异步续跑是同一逻辑请求的延续，授权已在 REQUEST 阶段完成，放行是标准做法；
`/error` 路径本身已 permitAll，ERROR 分发一并放行仅作兜底。

**验证**（修复后，待补）：重建 jar → 登录 → chat → 订阅 stream 到 done，
确认服务端日志零 `AuthorizationDeniedException` 且流正常收尾；无 token 订阅 stream 返回干净 401。

## 遗留项

- 根目录遗留历史 `data/`（早期从项目根启动产生的测试数据，与本会话无关，未删除——如需清理由用户确认）
- 生产建议：固定 `MARKAGENT_JWT_SECRET`（默认每次重启随机，token 全失效）；修改 bootstrap-admin 密码
- JDBC 存储实现（接口已抽象）留待后续里程碑
