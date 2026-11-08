/** 聊天工作台状态：消息流 + SSE 事件增量 reducer（纯逻辑见 replay.ts，历史回放页共用） */
import { create } from 'zustand'
import { post } from '../api/client'
import { subscribeTaskStream } from '../api/sse'
import { applyEvent, initialReplay } from './replay'
import type { ReplayState, SseEventEnvelope } from './replay'

// 兼容既有导入路径：UI 组件类型统一从 replay.ts 出
export type { AgentUi, PlanUi, PlanStepUi, TaskUiStatus, ToolCallUi, UiMessage, SseEventEnvelope } from './replay'

interface ChatState extends ReplayState {
  taskId: string | null
  model: string
  planning: 'auto' | 'off'
  thinking: boolean
  setModel: (model: string) => void
  setPlanning: (planning: 'auto' | 'off') => void
  setThinking: (thinking: boolean) => void
  send: (goal: string) => Promise<void>
  resumeTask: () => Promise<void>
  pauseTask: () => Promise<void>
  stopTask: () => Promise<void>
  reset: () => void
  /** @internal 订阅任务流（断线重连用） */
  _subscribe: (taskId: string, afterSeq: number) => void
  /** @internal SSE 事件增量 reducer */
  _apply: (event: SseEventEnvelope) => void
}

let seqCounter = 0
let abortController: AbortController | null = null

const nextMsgId = () => `m_${Date.now()}_${seqCounter++}`

export const useChatStore = create<ChatState>((set, get) => ({
  ...initialReplay,
  taskId: null,
  model: 'deepseek-flash',
  planning: 'auto',
  thinking: false,

  setModel: (model) => set({ model }),
  setPlanning: (planning) => set({ planning }),
  setThinking: (thinking) => set({ thinking }),

  send: async (goal) => {
    if (!goal.trim() || get().taskStatus === 'running') return
    // 保留历史消息（会话连续），只重置任务级状态；sessionId 透传给服务端复用会话，
    // AgentScope 状态按 (userId, sessionId) 持久化，agent 会带上之前的对话上下文
    abortController?.abort()
    abortController = null
    set({
      messages: [...get().messages, { id: nextMsgId(), role: 'user', content: goal, reasoning: '', toolCalls: [], streaming: false }],
      plan: null,
      agents: [],
      taskStatus: 'running',
      error: null,
      lastSeq: 0,
    })
    const accepted = await post<{ taskId: string; sessionId: string }>('/chat', {
      goal,
      sessionId: get().sessionId ?? null,
      options: { model: get().model, planning: get().planning, thinking: get().thinking, workers: [] },
    })
    set({ taskId: accepted.taskId, sessionId: accepted.sessionId })
    get()._subscribe(accepted.taskId, 0)
  },

  _subscribe: (taskId: string, afterSeq: number) => {
    abortController?.abort()
    abortController = new AbortController()
    const signal = abortController.signal
    subscribeTaskStream(
      taskId,
      afterSeq,
      {
        onEvent: (event: SseEventEnvelope) => {
          get()._apply(event)
        },
        onError: () => {
          // 断线：以最后收到的 seq 重连回放（简单指数退避由 fetch-event-source 外层处理）
          const state = get()
          if (state.taskStatus === 'running' || state.taskStatus === 'paused') {
            setTimeout(() => get()._subscribe(state.taskId!, state.lastSeq), 1500)
          }
        },
      },
      signal,
    )
  },

  _apply: (event: SseEventEnvelope) => {
    const next = applyEvent(get(), event, nextMsgId)
    if (next) set(next)
  },

  resumeTask: async () => {
    const state = get()
    if (!state.taskId || state.taskStatus !== 'paused') return
    set({ taskStatus: 'running' })
    await post(`/tasks/${state.taskId}/resume`)
    get()._subscribe(state.taskId, state.lastSeq)
  },

  pauseTask: async () => {
    const state = get()
    if (!state.taskId || state.taskStatus !== 'running') return
    await post(`/tasks/${state.taskId}/pause`)
  },

  stopTask: async () => {
    const state = get()
    if (!state.taskId) return
    abortController?.abort()
    await post(`/tasks/${state.taskId}/stop`)
    set({ taskStatus: 'cancelled' })
  },

  // 新对话 = 新会话：清掉 sessionId，下一次发送由服务端分配新会话（任务列表按会话分组）
  reset: () => {
    abortController?.abort()
    abortController = null
    set({
      messages: [], taskId: null, sessionId: null, plan: null, agents: [],
      taskStatus: 'idle', error: null, lastSeq: 0,
    })
  },
}))
