import { useEffect, useState } from 'react'
import { Badge, Button, Card, Space, Table, Typography } from 'antd'
import { EyeOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { get } from '../api/client'

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

const STATUS: Record<TaskStatus, { status: 'processing' | 'warning' | 'success' | 'error' | 'default'; text: string }> = {
  RUNNING: { status: 'processing', text: '执行中' },
  PAUSED: { status: 'warning', text: '已暂停' },
  COMPLETED: { status: 'success', text: '已完成' },
  FAILED: { status: 'error', text: '失败' },
  CANCELLED: { status: 'default', text: '已停止' },
}

/** 列表行 = 一次会话（可能含多次任务/多轮对话），点击进入查看完整记录 */
interface SessionRow {
  sessionId: string
  title: string
  latestGoal: string
  taskCount: number
  latestStatus: TaskStatus
  latestUpdatedAt: string
  createdAt: string
}

const fmtTime = (iso: string) => (iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '-')

/** 任务列表：按会话（session）分组，点击查看会话完整记录 */
export default function TasksPage() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<SessionRow[]>([])
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const [sessions, tasks] = await Promise.all([
        get<SessionRecord[]>('/sessions'),
        get<TaskRecord[]>('/tasks'),
      ])
      const bySession = new Map<string, TaskRecord[]>()
      for (const t of tasks) {
        const list = bySession.get(t.sessionId) ?? []
        list.push(t)
        bySession.set(t.sessionId, list)
      }
      const grouped: SessionRow[] = [...bySession.entries()].map(([sessionId, list]) => {
        const session = sessions.find((s) => s.id === sessionId)
        const sorted = [...list].sort((a, b) => a.updatedAt.localeCompare(b.updatedAt))
        const latest = sorted[sorted.length - 1]
        return {
          sessionId,
          title: session?.title ?? (list[0]?.goal ?? '未命名会话'),
          latestGoal: latest.goal,
          taskCount: list.length,
          latestStatus: latest.status,
          latestUpdatedAt: latest.updatedAt,
          createdAt: session?.createdAt ?? list[0].createdAt,
        }
      })
      grouped.sort((a, b) => b.latestUpdatedAt.localeCompare(a.latestUpdatedAt))
      setRows(grouped)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  return (
    <Card title="任务记录（按会话分组）" style={{ margin: 16 }}>
      <Table<SessionRow>
        rowKey="sessionId"
        loading={loading}
        dataSource={rows}
        pagination={{ pageSize: 10 }}
        onRow={(row) => ({
          onClick: () => navigate(`/tasks/${row.sessionId}`),
          style: { cursor: 'pointer' },
        })}
        columns={[
          {
            title: '状态',
            dataIndex: 'latestStatus',
            width: 100,
            render: (status: TaskStatus) => (
              <Badge status={STATUS[status].status} text={STATUS[status].text} />
            ),
          },
          {
            title: '会话',
            render: (_, row) => (
              <Space direction="vertical" size={0} style={{ width: '100%' }}>
                <Text strong ellipsis style={{ maxWidth: 480 }}>{row.title}</Text>
                <Text type="secondary" ellipsis style={{ fontSize: 12, maxWidth: 480 }}>
                  {row.latestGoal}
                </Text>
              </Space>
            ),
          },
          {
            title: '轮次',
            dataIndex: 'taskCount',
            width: 70,
            render: (n: number) => <Text type="secondary">{n} 轮</Text>,
          },
          {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 170,
            render: fmtTime,
          },
          {
            title: '最近更新',
            dataIndex: 'latestUpdatedAt',
            width: 170,
            render: fmtTime,
          },
          {
            title: '操作',
            width: 120,
            render: (_, row) => (
              <Button
                size="small"
                type="link"
                icon={<EyeOutlined />}
                onClick={(e) => {
                  e.stopPropagation()
                  navigate(`/tasks/${row.sessionId}`)
                }}
              >
                查看记录
              </Button>
            ),
          },
        ]}
      />
    </Card>
  )
}
