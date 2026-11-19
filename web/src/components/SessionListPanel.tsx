/**
 * 侧边栏历史对话面板（豆包/千问/Codex 式）：
 * 「新对话」按钮 + 会话列表（滑动窗口，flex 滚动），点击会话加载进聊天工作台续聊，
 * hover 显示删除。原「任务记录」页保留为面板头部小图标入口。
 */
import { useEffect, useState } from 'react'
import { Button, Empty, Popconfirm, Spin, Tooltip, message } from 'antd'
import { DeleteOutlined, MessageOutlined, PlusOutlined, UnorderedListOutlined } from '@ant-design/icons'
import { useLocation, useMatch, useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { del, get } from '../api/client'
import { useChatStore } from '../stores/chatStore'

interface SessionItem {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

/** 列表时间：今天 → HH:mm，昨天 → 昨天，今年 → MM-DD，更早 → YYYY-MM-DD */
const fmtTime = (iso: string) => {
  const t = dayjs(iso)
  if (!t.isValid()) return ''
  const now = dayjs()
  if (t.isSame(now, 'day')) return t.format('HH:mm')
  if (t.isSame(now.subtract(1, 'day'), 'day')) return '昨天'
  if (t.isSame(now, 'year')) return t.format('MM-DD')
  return t.format('YYYY-MM-DD')
}

export default function SessionListPanel() {
  const navigate = useNavigate()
  const location = useLocation()
  const match = useMatch('/chat/:sessionId')
  const storeSessionId = useChatStore((s) => s.sessionId)
  const taskStatus = useChatStore((s) => s.taskStatus)
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [loading, setLoading] = useState(true)
  const [messageApi, contextHolder] = message.useMessage()

  const load = async () => {
    try {
      const list = await get<SessionItem[]>('/sessions')
      setSessions([...list].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)))
    } catch {
      /* 401 由 client 统一处理 */
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 首条消息发出后服务端分配 sessionId（新会话建立、标题落盘）→ 刷新列表
  useEffect(() => {
    if (storeSessionId) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeSessionId])

  // 任务结束/暂停：updatedAt 变化，刷新排序
  useEffect(() => {
    if (taskStatus === 'completed' || taskStatus === 'failed' || taskStatus === 'cancelled' || taskStatus === 'paused') {
      load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskStatus])

  // 窗口重新聚焦时刷新（其他标签页/后台任务完成后回来能看到最新）
  useEffect(() => {
    const f = () => load()
    window.addEventListener('focus', f)
    return () => window.removeEventListener('focus', f)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 当前会话高亮：/chat/:id 取路由参数；/ 下取 store 中的当前 sessionId（进行中的会话） */
  const activeId = match?.params.sessionId ?? (location.pathname === '/' ? storeSessionId : null)

  const open = (id: string) => navigate(`/chat/${id}`)

  const remove = async (id: string) => {
    try {
      await del(`/sessions/${id}`)
      setSessions((list) => list.filter((s) => s.id !== id))
      if (id === activeId || id === storeSessionId) {
        useChatStore.getState().reset()
        navigate('/')
      }
      messageApi.success('会话已删除')
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const newChat = () => {
    useChatStore.getState().reset()
    navigate('/')
  }

  return (
    <div className="session-panel">
      {contextHolder}
      <div className="session-panel-head">
        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={newChat}>
          新对话
        </Button>
        <Tooltip title="任务执行记录">
          <Button size="small" type="text" icon={<UnorderedListOutlined />} onClick={() => navigate('/tasks')} />
        </Tooltip>
      </div>
      <div className="session-panel-title">历史对话</div>
      <div className="session-panel-list">
        {loading ? (
          <div className="session-panel-loading"><Spin size="small" /></div>
        ) : sessions.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史对话" />
        ) : (
          sessions.map((s) => (
            <div
              key={s.id}
              className={`session-item${s.id === activeId ? ' active' : ''}`}
              onClick={() => open(s.id)}
              title={s.title || '未命名会话'}
            >
              <MessageOutlined className="session-item-icon" />
              <div className="session-item-main">
                <div className="session-item-title">{s.title || '未命名会话'}</div>
                <div className="session-item-time">{fmtTime(s.updatedAt)}</div>
              </div>
              <Popconfirm
                title="删除该会话？"
                description="会话记录与绑定上下文将一并删除，不可恢复"
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => remove(s.id)}
              >
                <Button
                  className="session-item-del"
                  type="text"
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={(e) => e.stopPropagation()}
                />
              </Popconfirm>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
