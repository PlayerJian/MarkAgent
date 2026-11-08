# MarkAgent · 企业级 AI Agent 聊天助手

基于 **Java 21 + Spring Boot 4.1 + Spring AI 2.0 + AgentScope Java 2.0.4 + DeepSeek + React 18** 的企业级 Agent 助手，
三端交付：**Web 网页（含 PWA）、终端 CLI、手机（PWA 安装）**。

## 核心能力

| 能力 | 实现 |
|---|---|
| Planning | AgentScope TaskPlanning + `plan_write`/`plan_mark_done` 工具，计划树实时推送 |
| Tool Use | 内置 calculator / now / http_get / http_post / **web_search**（自研免 key 搜索：Bing→DuckDuckGo 自动降级）/ file_write / file_read / plan / memory / skill / spawn_agent + MCP 动态注册 |
| Memory | 会话态（AgentScope AgentStateStore 持久化）+ 长期记忆（MEMORY.md / 日记 / 向量检索三档降级）+ 上下文压缩（75% 阈值） |
| Multi-Agent | `spawn_agent` 派生专家 worker（独立 ReActAgent，事件扇出 AGENT_MSG/AGENT_DONE） |
| 任务持久化 | JSON 文件存储（TaskStore/SessionStore/CheckpointStore，接口抽象可换 JDBC） |
| 断点恢复 | 任务 pause/resume/stop；AgentScope 状态按 (userId, sessionId) 落盘，**服务重启后可继续** |
| Skills | `skills/` 目录 + SKILL.md frontmatter，`skill_invoke` 按需加载（示例：report-writer、weekly-digest） |
| MCP | stdio/SSE MCP server 管理（`mcp.json`），工具注册进 AgentScope Toolkit（已验 filesystem 16 工具） |
| 上下文管理 | token 估算 / 用量推送 / 模型热切换（deepseek-direct 直连 与 bailian 百炼双通道） |

## 架构

```
markagent-web (React18+Vite+antd5+PWA) ──REST+SSE(JWT)──▶ markagent-server (Boot 4.1.2/Java21)
markagent-cli (picocli 薄客户端)        ──REST+SSE(JWT)──▶   ├─ AgentScope 2.0.4 运行时（ReAct/Toolkit/Planning/Memory/Team/StateStore）
                                                          ├─ Spring AI 2.0.2（DeepSeekChatModel 桥接 Model SPI + embedding）
                                                          ├─ 自研层：任务编排 / 断点恢复 / SSE 推送 / JWT / 管理 API
                                                          └─ data/（JSON 文件存储）
markagent-common（server/cli 共享：SSE 事件协议 + DTO）
```

## 快速开始

前置：JDK 21、Node 24（仅前端）、Maven 3.9+（或用仓库自带 `./mvnw`，阿里云镜像已配置于 `.mvn/settings.xml`）。

```bash
# 1. 启动服务端（开发态）
./mvnw -pl server spring-boot:run          # http://localhost:8080，数据落 server/data/

# 2. 前端开发态（可选，开发时热更）
cd web && npm install && npm run dev        # http://localhost:5173（代理 /api → 8080）

# 3. 终端 CLI
cd cli && ../mvnw -q -pl cli -am package
java -jar cli/target/markagent-cli-0.1.0-shaded.jar login admin admin123
java -jar cli/target/markagent-cli-0.1.0-shaded.jar chat "帮我制定并执行一个三步计划：..."
```

**发布打包（jar 自带前端）：**

```bash
cd web && npm run build                     # 产出 dist/（含 PWA sw.js + manifest）
cd .. && ./mvnw -q clean package -DskipTests
java -jar server/target/markagent-server-0.1.0.jar   # 建议在期望的 data/ 父目录下运行
```

## 配置要点（server/src/main/resources/application.yml）

| 项 | 说明 |
|---|---|
| `spring.ai.deepseek.api-key` | DeepSeek API Key（环境变量 `DEEPSEEK_API_KEY` 或直接写入 yml） |
| `markagent.llm.provider/default-model` | `deepseek-direct` \| `bailian`；默认模型 `deepseek-flash`（运行期 PUT /settings/llm 热切换） |
| `markagent.storage.root` | 数据根目录（默认 `./data`，**相对运行目录**） |
| `markagent.agent.*` | 系统提示词 / 最大迭代（30）/ 流看门狗 `stream-timeout-minutes`（10） |
| `markagent.security.bootstrap-admin` | 初始管理员（默认 admin/admin123，生产务必改） |
| `markagent.security.jwt.secret` | 默认随机（每次重启失效）；固定密钥需设 `MARKAGENT_JWT_SECRET` |
| `markagent.embedding.enabled` | 无 embedding key 时置 false → 关键词检索降级 |
| `markagent.mcp.stdio-timeout-ms` | MCP stdio 工具调用超时 |

## 数据布局（`data/`）

```
users/users.json          sessions/{userId}/{sessionId}.json
tasks/{taskId}.json       checkpoints/{taskId}/{cpSeq}.json
agentscope/               # AgentScope 状态（u_*/s_*/agent_state.json）
workspace/{taskId}/       # MEMORY.md / memory/日记 / plans/PLAN.md / 工具产物
skills/                   # 每技能一目录 + SKILL.md
events/{taskId}.jsonl     # SSE 事件流水（seq 自增，断线回放锚点）
mcp.json  settings.json
```

## API 与 SSE 协议（`/api/v1`，JWT Bearer）

- 认证：`POST /auth/register|login`，`GET /auth/me`
- 会话：`GET|POST /sessions`，`DELETE /sessions/{id}`，`GET /sessions/{id}/memory|context`
- 任务：`POST /chat`（立即返回 taskId）→ `GET /tasks/{id}/stream?afterSeq=`（SSE）；`GET /tasks`、`GET /tasks/{id}`、`POST /tasks/{id}/pause|resume|stop`
- 记忆：`POST /sessions/{id}/memory/search|save`
- 技能：`GET|POST /skills`；MCP：`GET|POST|PUT|DELETE /mcp/servers[/{id}]`；设置：`GET|PUT /settings/llm`

SSE 事件类型：`meta | text | reasoning | tool_start | tool_end | plan_update | agent_spawn | agent_msg | agent_done | memory_update | token | checkpoint | paused | error | done`（`id: <seq>` 为回放锚点）。

## 里程碑验收状态

M0 环境 · M1 骨架 · M2 LLM 闭环 · M3 存储+断点+认证 · M4 Planning+工具+Skills · M5 Memory+上下文 · M6 Multi-agent · M7 MCP+CLI（见 `docs/M7b-cli-acceptance.md`）· **M8 加固+PWA+发布（见 `docs/M8-hardening-acceptance.md`）** —— 全部通过 ✅

## 已知限制

- 存储为 JSON 文件（接口已抽象，JDBC 可插拔）；高并发多写场景建议换数据库
- antd 为单体库，`vendor-antd` chunk 约 1.1MB（gzip 351KB），PWA 首次加载已预缓存
- bailian（百炼）通道的 reasoning_content 透传未全面验证，推理能力以 deepseek-direct 为准
