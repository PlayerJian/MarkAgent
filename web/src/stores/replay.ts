/**
 * 会话/任务记录回放：把 SSE 事件流 fold 成 UI 状态。
 * chatStore 的实时流（SSE 推送）与历史记录查看页（REST 全量事件）共用同一个 reducer，
 * 保证"实时看到的"和"回放看到的"完全一致。
 */

export interface ToolCallUi {
  callId: string
  name: string
  resultSummary: string
  ok: boolean
  running: boolean
}

export interface UiMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning: string
  toolCalls: ToolCallUi[]
  streaming: boolean
}

export interface PlanStepUi {
  id: string
  title: string
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  detail: string
}

export interface PlanUi {
  steps: PlanStepUi[]
  currentStepId: string
}

/** 多智能体面板：worker 派生/流式输出/完成态 */
export interface AgentUi {
  agentId: string
  role: string
  text: string
  tools: { name: string; ok: boolean }[]
  done: boolean
  ok: boolean
}

export type TaskUiStatus = 'idle' | 'running' | 'paused' | 'completed' | 'failed' | 'cancelled'

export interface SseEventEnvelope {
  seq: number
  type: string
  data: unknown
}

/** reducer 关心的状态切片（chatStore 的 ChatState 与回放页共用同一形状） */
export interface ReplayState {
  messages: UiMessage[]
  plan: PlanUi | null
  agents: AgentUi[]
  taskStatus: TaskUiStatus
  sessionId: string | null
  lastSeq: number
  error: string | null
  /** 上下文压缩系统提示（COMPACT 事件折叠结果，消息流底部细线展示） */
  compactNotice: { phase: string; note: string } | null
}

export const initialReplay: ReplayState = {
  messages: [],
  plan: null,
  agents: [],
  taskStatus: 'idle',
  sessionId: null,
  lastSeq: 0,
  error: null,
  compactNotice: null,
}

/**
 * 单个 SSE 事件 → 新状态（无变化返回 null）。
 * makeId：新 assistant 消息的 id 生成器（chatStore 用全局计数器，回放页用任务内计数器）。
 */
export function applyEvent(
  state: ReplayState,
  event: SseEventEnvelope,
  makeId: () => string,
): ReplayState | null {
  // 事件信封 {seq,type,ts,taskId,data:{...}}：业务载荷在 data.data 内（与 CLI SseStreamRenderer 解包一致）。
  // REST 回放接口返回的 data 直接就是载荷（无外层信封），所以两种形态都要兼容。
  const envelope = (event.data ?? {}) as Record<string, unknown>
  const data = (envelope.data ?? envelope) as Record<string, unknown>
  // 服务端 seq 单调递增：<= lastSeq 的必是旧连接与重连回放重叠投递的重复事件，直接丢弃
  if (event.seq <= state.lastSeq && state.lastSeq > 0) {
    return null
  }
  const next: ReplayState = { ...state, lastSeq: event.seq }
  let changed = false

  switch (event.type) {
    case 'META': {
      if (data.sessionId && data.sessionId !== state.sessionId) {
        next.sessionId = data.sessionId as string
        changed = true
      }
      break
    }
    case 'TEXT': {
      const messages = [...state.messages]
      let last = messages[messages.length - 1]
      if (!last || last.role !== 'assistant' || !last.streaming) {
        last = { id: makeId(), role: 'assistant', content: '', reasoning: '', toolCalls: [], streaming: true }
        messages.push(last)
      }
      last = { ...last, content: last.content + ((data.delta as string) ?? '') }
      messages[messages.length - 1] = last
      next.messages = messages
      changed = true
      break
    }
    case 'REASONING': {
      const messages = [...state.messages]
      const last = messages[messages.length - 1]
      if (last && last.role === 'assistant') {
        messages[messages.length - 1] = { ...last, reasoning: last.reasoning + ((data.delta as string) ?? '') }
        next.messages = messages
        changed = true
      }
      break
    }
    case 'TOOL_START': {
      const messages = [...state.messages]
      const last = messages[messages.length - 1]
      if (last && last.role === 'assistant') {
        const toolCalls = [
          ...last.toolCalls,
          { callId: data.callId as string, name: data.name as string, resultSummary: '', ok: false, running: true },
        ]
        messages[messages.length - 1] = { ...last, toolCalls }
        next.messages = messages
        changed = true
      }
      break
    }
    case 'TOOL_END': {
      const messages = [...state.messages]
      const last = messages[messages.length - 1]
      if (last && last.role === 'assistant') {
        const toolCalls = last.toolCalls.map((t) =>
          t.callId === data.callId
            ? { ...t, ok: data.ok as boolean, resultSummary: (data.resultSummary as string) ?? '', running: false }
            : t,
        )
        messages[messages.length - 1] = { ...last, toolCalls }
        next.messages = messages
        changed = true
      }
      break
    }
    case 'PLAN_UPDATE': {
      next.plan = {
        steps: ((data.steps as PlanStepUi[]) ?? []) as PlanStepUi[],
        currentStepId: (data.currentStepId as string) ?? '',
      }
      changed = true
      break
    }
    case 'AGENT_SPAWN': {
      const agentId = data.agentId as string
      const agents = state.agents.some((a) => a.agentId === agentId)
        ? state.agents
        : [
            ...state.agents,
            { agentId, role: (data.role as string) ?? agentId, text: '', tools: [], done: false, ok: false },
          ]
      next.agents = agents
      changed = true
      break
    }
    case 'AGENT_MSG': {
      const agentId = data.agentId as string
      const kind = data.kind as string
      next.agents = state.agents.map((a) => {
        if (a.agentId !== agentId) return a
        if (kind === 'text') return { ...a, text: a.text + ((data.delta as string) ?? '') }
        if (kind === 'tool') return { ...a, tools: [...a.tools, { name: data.name as string, ok: true }] }
        if (kind === 'tool_end') {
          const tools = a.tools.map((t, i) =>
            i === a.tools.length - 1 && t.name === data.name ? { ...t, ok: data.ok as boolean } : t,
          )
          return { ...a, tools }
        }
        return a
      })
      changed = true
      break
    }
    case 'AGENT_DONE': {
      next.agents = state.agents.map((a) =>
        a.agentId === data.agentId
          ? { ...a, done: true, ok: (data.ok as boolean) ?? false, text: a.text || ((data.resultSummary as string) ?? '') }
          : a,
      )
      changed = true
      break
    }
    case 'PAUSED':
      next.taskStatus = 'paused'
      changed = true
      break
    case 'COMPACT': {
      const phase = data.phase as string
      if (phase === 'planned') {
        next.compactNotice = {
          phase,
          note: `上下文即将压缩（已用 ${data.usedTokens ?? '-'} / 有效窗口 ${data.effectiveWindow ?? '-'}）`,
        }
      } else if (phase === 'completed') {
        next.compactNotice = { phase, note: `上下文压缩完成（保留 ${data.keptMessages ?? 0} 条消息）` }
      } else if (phase === 'failed') {
        next.compactNotice = { phase, note: '上下文压缩失败，会话将以完整上下文继续' }
      } else {
        break
      }
      changed = true
      break
    }
    case 'ERROR':
      next.error = data.message as string
      changed = true
      break
    case 'DONE': {
      const status = data.status as string
      next.taskStatus =
        status === 'COMPLETED' ? 'completed' : status === 'PAUSED' ? 'paused' : status === 'CANCELLED' ? 'cancelled' : 'failed'
      const messages = state.messages.map((m) =>
        m.role === 'assistant' && m.streaming ? { ...m, streaming: false } : m,
      )
      next.messages = messages
      changed = true
      break
    }
    default:
      break
  }
  return changed ? next : null
}
