# M7b 验收记录：终端 CLI

验收日期：2026-11-07　验收人：Claude（受控复现 + 修复后回归）

## 交付内容

- **cli/**（picocli 4.7.7 + jackson，JDK HttpClient 手工解析 SSE，无其他依赖）：
  - `login/logout/whoami`：JWT 认证，token 存 `~/.markagent/config.json`
  - `chat "<任务>"`：POST /chat 后订阅 SSE，全事件流式渲染
  - `task list/show/resume/pause/stop/stream [--raw]`：任务全生命周期 + 历史回放
  - `session list/show`、`memory show`、`skill list`、`mcp list`、`config show/set-server`
- **server**（本里程碑修复）：
  - `AgentChatService`：订阅链看门狗（`markagent.agent.stream-timeout-minutes`，默认 10 分钟，
    静默悬挂转可观测失败）、Reactor 丢弃错误落日志、run 终止闩（pause 等停稳）
  - `application.yml`：`io.agentscope: DEBUG` 日志级别

## 验收结果

| 验收点 | 结果 |
|---|---|
| chat 全流程流式（计划/工具/文本/完成） | ✅ 6~8 步计划任务 15~45s 内 DONE |
| pause 立即生效（步骤边界停 + 检查点 + PAUSED） | ✅ 中断后 ~2s 停稳 |
| **resume 续跑到 DONE 且流正常关闭** | ✅ 修复后回归通过（见下） |
| stream 回放（afterSeq 游标判重） | ✅ 历史 + 实时无缝拼接 |
| task list/show/stop | ✅ |
| session/memory/skill/mcp/config/auth 命令 | ✅ 全部冒烟通过 |

## 关键缺陷：resume 后任务永远卡 RUNNING（静默死亡）

**现象**：pause → resume 后，续跑 run 的最终文本全部流出（含 AgentResultEvent 广播的全文），
但之后无 DONE 事件、无状态变更，任务永久 RUNNING，且进程内无任何线程在执行——所有 jstack
线索都指向「框架流未终止」，多次误判为 AgentScope 内部 bug。

**根因**（经 EVENT 轨迹插桩定位）：我方 `AgentChatService.onStreamComplete` 的暂停标记泄漏：

1. `pause()` 将 `pausedFlags[taskId] = true`（本意：区分「interrupt 收尾」与「正常完成」）；
2. 首个 run 的流在 interrupt 后正常结束 → `onStreamComplete` 见标记为 true → 早退（正确）；
3. **`resume()` 从未复位该标记** → 续跑 run 结束时同样早退 → 不广播 DONE、不落 COMPLETED。

框架侧一切正常：AGENT_RESULT / AGENT_END / STREAM complete 均在日志中依次出现。
此前反编译排查（RunControl/serializeOnKey/CAS/钩子）全部指向幽灵，浪费了数轮——教训：
**先插桩观测（事件轨迹 + 订阅生命周期日志），再怀疑框架。**

**修复**（`AgentChatService`）：

- `resume()` 复位 `pausedFlags`；
- `pause()` 经 `runLatches`（每 run 一个 CountDownLatch，`doFinally` 释放）**等待当前 run
  真正终止后再落 PAUSED**——消除「resume 复位标记早于旧 run 收尾」的竞态，同时保证 resume
  读到的是 interrupt 收尾后的完整状态；30s 兜底强制 dispose。

**回归**：chat → pause（6s）→ resume → stream 全程到「✔ 任务完成（COMPLETED）」，进程退出码 0，
任务状态 COMPLETED；服务端日志两次 `STREAM complete`（resume=false / resume=true）。

## 遗留项（转 M8）

- `TeamTool` 派生的 worker 在任务 pause 时收尾依赖超时（10 分钟 latch），需显式联动
  → **已在 M8 完成**（`interruptTask` 联动 dispose，pause 1 秒内返回，见 `docs/M8-hardening-acceptance.md`）
- 看门狗默认 10 分钟，可按业务调 `markagent.agent.stream-timeout-minutes`
