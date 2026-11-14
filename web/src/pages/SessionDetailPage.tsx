/**
 * 会话记录详情页：按时间顺序回放一次会话内的每个任务（对话 + 执行过程 + 工具调用），
 * 并展示任务工作区产出的文件（调研 md 报告等）。运行中的任务会续接实时流。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert, Badge, Button, Card, Collapse, Empty, Modal, Space, Spin, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, CaretRightOutlined, FileTextOutlined, StopOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { get, post } from '../api/client'
import { subscribeTaskStream } from '../api/sse'
import { applyEvent, initialReplay } from '../stores/replay'
import type { ReplayState, SseEventEnvelope } from '../stores/replay'
import { ConversationList } from '../components/ConversationView'

const { Text } = Typography

type TaskStatus = 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

interface TaskRecord {
  id: string
  sessionId: string
  goal: string
  status: TaskStatus
  createdAt: string
  updatedAt: string
  errorMessage: string | null
}

interface SessionRecord {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

interface FileEntry {
  path: string
  dir: boolean
  size: number
}

interface FileContent {
  path: string
  content: string
  truncated: boolean
}

const TASK_BADGE: Record<TaskStatus, { status: 'processing' | 'warning' | 'success' | 'error' | 'default'; text: string }> = {
  RUNNING: { status: 'processing', text: '执行中' },
  PAUSED: { status: 'warning', text: '已暂停' },
  COMPLETED: { status: 'success', text: '已完成' },
  FAILED: { status: 'error', text: '失败' },
  CANCELLED: { status: 'default', text: '已停止' },
}

const fmtTime = (iso: string) => (iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '-')

const fmtSize = (bytes: number) => (bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`)

/** 单个任务的记录卡：事件回放 + 工作区文件；运行/暂停中续接实时流 */
function TaskRecordCard({ task, onChanged }: { task: TaskRecord; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [loading, setLoading] = useState(false)
  const [state, setState] = useState<ReplayState>(initialReplay)
  const [files, setFiles] = useState<FileEntry[]>([])
  const [filesLoading, setFilesLoading] = useState(false)
  const [preview, setPreview] = useState<FileContent | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)

  const stateRef = useRef<ReplayState>(initialReplay)
  const abortRef = useRef<AbortController | null>(null)
  const idRef = useRef(0)
  const onChangedRef = useRef(onChanged)
  onChangedRef.current = onChanged

  const live = task.status === 'RUNNING' || task.status === 'PAUSED'
  const liveRef = useRef(live)
  liveRef.current = live

  const makeId = () => `re_${task.id}_${idRef.current++}`

  /** 应用单个事件到回放状态（实时流与历史回放共用） */
  const applyOne = useCallback((ev: SseEventEnvelope) => {
    const next = applyEvent(stateRef.current, ev, makeId)
    if (next) {
      stateRef.current = next
      setState(next)
    }
    // 实时流收到终态：刷新外层任务状态（列表角标/操作按钮）
    if (ev.type === 'DONE') onChangedRef.current()
  }, [task.id])

  /** 从当前回放位置续接实时流（load 与 resume 后调用） */
  const subscribeLive = useCallback(() => {
    if (!liveRef.current) return
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    subscribeTaskStream(
      task.id,
      stateRef.current.lastSeq,
      { onEvent: applyOne, onError: () => { /* 详情页断线不自动重连，可折叠后重新展开 */ } },
      controller.signal,
    )
  }, [task.id, applyOne])

  /** 首次展开：拉取全量事件回放 + 工作区文件列表，运行中则续接实时流 */
  const load = async () => {
    setLoading(true)
    try {
      const events = await get<SseEventEnvelope[]>(`/tasks/${task.id}/events`)
      let s = { ...initialReplay }
      for (const ev of events) {
        const next = applyEvent(s, ev, makeId)
        if (next) s = next
      }
      stateRef.current = s
      setState(s)
      setLoaded(true)

      setFilesLoading(true)
      try {
        setFiles(await get<FileEntry[]>(`/tasks/${task.id}/files`))
      } catch {
        setFiles([])
      } finally {
        setFilesLoading(false)
      }

      subscribeLive()
    } finally {
      setLoading(false)
    }
  }

  // 卸载时断开实时流
  useEffect(() => () => abortRef.current?.abort(), [])

  const onCollapse = (keys: string | string[]) => {
    const isOpen = (Array.isArray(keys) ? keys : [keys]).includes('body')
    setOpen(isOpen)
    if (isOpen && !loaded && !loading) load()
    if (!isOpen) abortRef.current?.abort()
  }

  const action = async (op: 'resume' | 'stop') => {
    await post(`/tasks/${task.id}/${op}`)
    onChanged()
    // resume 后服务端会继续广播新事件：任务记录续接实时流
    if (op === 'resume') setTimeout(subscribeLive, 500)
  }

  const openFile = async (path: string) => {
    setFileError(null)
    try {
      setPreview(await get<FileContent>(`/tasks/${task.id}/files/content?path=${encodeURIComponent(path)}`))
    } catch (e) {
      setFileError((e as Error).message)
    }
  }

  const fileEntries = files.filter((f) => !f.dir)
  const badge = TASK_BADGE[task.status]

  return (
    <Card
      size="small"
      title={
        <Space size={8} wrap>
          <Badge status={badge.status} text={badge.text} />
          <Text strong style={{ fontSize: 13 }}>{task.goal}</Text>
        </Space>
      }
      extra={
        <Space size={8}>
          <Text type="secondary" style={{ fontSize: 12 }}>{fmtTime(task.createdAt)}</Text>
          {task.status === 'PAUSED' && (
            <Button size="small" type="primary" icon={<CaretRightOutlined />} onClick={() => action('resume')}>
              恢复
            </Button>
          )}
          {(task.status === 'RUNNING' || task.status === 'PAUSED') && (
            <Button size="small" danger icon={<StopOutlined />} onClick={() => action('stop')}>
              停止
            </Button>
          )}
        </Space>
      }
    >
      {task.errorMessage && (
        <Alert type="error" showIcon message={task.errorMessage} style={{ marginBottom: 8 }} />
      )}
      <Collapse
        ghost
        activeKey={open ? ['body'] : []}
        onChange={onCollapse}
        items={[{
          key: 'body',
          label: <Text type="secondary" style={{ fontSize: 12 }}>{open ? '收起记录' : '展开对话记录与工作区文件'}</Text>,
          children: loading ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {state.error && <Alert type="error" showIcon message={state.error} />}
              <ConversationList
                messages={state.messages}
                plan={state.plan}
                agents={state.agents}
                taskStatus={state.taskStatus}
                compactNotice={state.compactNotice}
              />
              {state.messages.length === 0 && (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无对话记录" />
              )}
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  📁 工作区文件{filesLoading ? '（加载中…）' : fileEntries.length === 0 ? '（无产出文件）' : ''}
                </Text>
                {fileEntries.length > 0 && (
                  <div style={{ marginTop: 4, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {fileEntries.map((f) => (
                      <Button
                        key={f.path}
                        type="link"
                        size="small"
                        icon={<FileTextOutlined />}
                        style={{ textAlign: 'left', justifyContent: 'flex-start' }}
                        onClick={() => openFile(f.path)}
                      >
                        {f.path}{f.size >= 0 ? `（${fmtSize(f.size)}）` : ''}
                      </Button>
                    ))}
                  </div>
                )}
                {fileError && <Text type="danger" style={{ fontSize: 12 }}>{fileError}</Text>}
              </div>
            </Space>
          ),
        }]}
      />

      {/* 文件内容预览（md 渲染） */}
      <Modal
        open={!!preview}
        title={preview?.path}
        footer={null}
        width={820}
        onCancel={() => setPreview(null)}
      >
        {preview && (
          <>
            {preview.truncated && (
              <Alert type="warning" showIcon message="文件内容过长，已截断展示" style={{ marginBottom: 8 }} />
            )}
            <div className="markdown-body" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{preview.content}</ReactMarkdown>
            </div>
          </>
        )}
      </Modal>
    </Card>
  )
}

/** 会话记录：返回 + 标题 + 按时间顺序的任务卡片 */
export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [session, setSession] = useState<SessionRecord | null>(null)
  const [tasks, setTasks] = useState<TaskRecord[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [sessions, all] = await Promise.all([
        get<SessionRecord[]>('/sessions'),
        get<TaskRecord[]>('/tasks'),
      ])
      setSession(sessions.find((s) => s.id === sessionId) ?? null)
      setTasks(
        all
          .filter((t) => t.sessionId === sessionId)
          .sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
      )
    } finally {
      setLoading(false)
    }
  }, [sessionId])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div style={{ margin: 16, maxWidth: 960, marginLeft: 'auto', marginRight: 'auto' }}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space size={12} wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>
            返回任务列表
          </Button>
          <Text strong style={{ fontSize: 16 }}>
            {session ? session.title : '会话记录'}
          </Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {tasks.length} 轮对话 · {session ? fmtTime(session.createdAt) : ''}
          </Text>
        </Space>

        {loading && tasks.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : tasks.length === 0 ? (
          <Empty description="该会话没有任务记录" />
        ) : (
          tasks.map((t) => <TaskRecordCard key={t.id} task={t} onChanged={load} />)
        )}
      </Space>
    </div>
  )
}
