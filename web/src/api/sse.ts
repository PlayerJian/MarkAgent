/** SSE 订阅封装：fetch-event-source（可带 Authorization 头），afterSeq 断线回放锚点 */
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { getToken } from './client'

export interface SseEventEnvelope {
  seq: number
  type: string
  data: unknown
}

export interface SseHandlers {
  onEvent: (event: SseEventEnvelope) => void
  onError?: (error: unknown) => void
  onClose?: () => void
}

/**
 * 订阅任务事件流。
 * @param taskId  任务 ID
 * @param afterSeq 回放起点（最后收到的 seq；0 = 只订阅实时）
 * @param signal  AbortSignal（组件卸载/新任务时中断）
 */
export function subscribeTaskStream(
  taskId: string,
  afterSeq: number,
  handlers: SseHandlers,
  signal?: AbortSignal,
) {
  const token = getToken()
  if (!token) return

  fetchEventSource(`/api/v1/tasks/${taskId}/stream?afterSeq=${afterSeq}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal,
    async onopen(response) {
      if (!response.ok) {
        if (response.status === 401) {
          window.location.href = '/login'
        }
        throw new Error(`SSE 连接失败: HTTP ${response.status}`)
      }
    },
    onmessage(message) {
      const seq = Number.parseInt(message.id || '0', 10)
      const type = message.event || 'message'
      let data: unknown = null
      try {
        data = message.data ? JSON.parse(message.data) : null
      } catch {
        data = message.data
      }
      handlers.onEvent({ seq, type, data })
    },
    onerror(error) {
      handlers.onError?.(error)
      throw error // 抛出让 fetch-event-source 停止（外层重连逻辑用 afterSeq 重订阅）
    },
    onclose() {
      handlers.onClose?.()
    },
  }).catch(() => {
    /* 中断/网络错误：静默，由调用方决定重连 */
  })
}
