/** 聊天工作台状态：消息流 + SSE 事件增量 reducer（纯逻辑见 replay.ts，历史回放页共用） */
import { create } from 'zustand'
import { get as apiGet, post } from '../api/client'
import { subscribeTaskStream } from '../api/sse'
import { applyEvent, initialReplay } from './replay'
import type { ReplayState, SseEventEnvelope } from './replay'

// 兼容既有导入路径：UI 组件类型统一从 replay.ts 出
export type { AgentUi, PlanUi, PlanStepUi, TaskUiStatus, ToolCallUi, UiMessage, SseEventEnvelope } from './replay'

/** 服务端会话记录（含消息历史，GET /sessions/{id}） */
export interface SessionRecordDto {
  id: string
  title: string
  messages: {
    id: string
    role: string
    content: string
    reasoning: string | null
    toolCalls: { callId: string; name: string; argsJson: string | null; resultSummary: string | null; ok: boolean }[] | null
    createdAt: string
  }[] | null
  createdAt: string
  updatedAt: string
  docLibraryId: string | null
}

/** 检索来源（问答界面二选一：文档库 / 上传文件；两者与「无」互斥） */
export interface DocSourceUi {
  kind: 'none' | 'library' | 'file'
  libraryId?: string
  libraryName?: string
  uploadId?: string
  fileName?: string
}

interface ChatState extends ReplayState {
  taskId: string | null
  model: string
  planning: 'auto' | 'off'
  thinking: boolean
  docSource: DocSourceUi
  /** 历史会话加载中（侧边栏点击后把记录加载进工作台） */
  sessionLoading: boolean
  setModel: (model: string) => void
  setPlanning: (planning: 'auto' | 'off') => void
  setThinking: (thinking: boolean) => void
  setDocSource: (source: DocSourceUi) => void
  send: (goal: string) => Promise<void>
  /** 加载历史会话记录进工作台（续聊走服务端 AgentScope 状态，sessionId 透传） */
  loadSession: (id: string) => Promise<void>
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
/** 加载竞态令牌：快速连点两个历史会话时，只认最后一次请求 */
let loadSeq = 0

const nextMsgId = () => `m_${Date.now()}_${seqCounter++}`

export const useChatStore = create<ChatState>((set, get) => ({
  ...initialReplay,
  taskId: null,
  model: 'deepseek-flash',
  planning: 'auto',
  thinking: false,
  docSource: { kind: 'none' },
  sessionLoading: false,

  setModel: (model) => set({ model }),
  setPlanning: (planning) => set({ planning }),
  setThinking: (thinking) => set({ thinking }),
  setDocSource: (docSource) => set({ docSource }),

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
      compactNotice: null,
    })
    // 检索来源（二选一互斥）：库问答 libraryId / 单文件 uploadId；会话级绑定由服务端继承
    const source = get().docSource
    const body: Record<string, unknown> = {
      goal,
      sessionId: get().sessionId ?? null,
      options: { model: get().model, planning: get().planning, thinking: get().thinking, workers: [] },
    }
    if (source.kind === 'library' && source.libraryId) body.source = { libraryId: source.libraryId }
    if (source.kind === 'file' && source.uploadId) body.source = { uploadId: source.uploadId }
    const accepted = await post<{ taskId: string; sessionId: string }>('/chat', body)
    set({ taskId: accepted.taskId, sessionId: accepted.sessionId })
    get()._subscribe(accepted.taskId, 0)
  },

  /** 加载历史会话：服务端 SessionRecord.messages → UiMessage（工具调用直接映射为完成态） */
  loadSession: async (id) => {
    const token = ++loadSeq
    abortController?.abort()
    abortController = null
    set({ sessionLoading: true })
    try {
      const record = await apiGet<SessionRecordDto>(`/sessions/${id}`)
      if (token !== loadSeq) return // 已被更新的加载请求取代
      const messages = (record.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({
          id: m.id,
          role: m.role as 'user' | 'assistant',
          content: m.content ?? '',
          reasoning: m.reasoning ?? '',
          toolCalls: (m.toolCalls ?? []).map((t) => ({
            callId: t.callId,
            name: t.name,
            resultSummary: t.resultSummary ?? '',
            ok: t.ok,
            running: false,
          })),
          streaming: false,
        }))
      set({
        messages,
        sessionId: id,
        taskId: null,
        plan: null,
        agents: [],
        taskStatus: 'idle',
        error: null,
        lastSeq: 0,
        compactNotice: null,
        // 检索绑定无需回显：服务端按会话级 docLibraryId 自动继承
        docSource: { kind: 'none' },
      })
    } finally {
      if (token === loadSeq) set({ sessionLoading: false })
    }
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

  // 新对话 = 新会话：清掉 sessionId 与检索来源，下一次发送由服务端分配新会话（任务列表按会话分组）
  reset: () => {
    abortController?.abort()
    abortController = null
    set({
      messages: [], taskId: null, sessionId: null, plan: null, agents: [],
      taskStatus: 'idle', error: null, lastSeq: 0, compactNotice: null,
      docSource: { kind: 'none' },
    })
  },
}))
