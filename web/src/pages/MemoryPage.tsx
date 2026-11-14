import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  List,
  Progress,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { get, post } from '../api/client'

interface SessionItem {
  id: string
  title: string
  createdAt: string
}

interface ContextUsage {
  usedTokens: number
  budget: number
  percent: number
  windowSize: number
  lastCompactionAt: string | null
}

interface SearchHit {
  content: string
  score: number
  source: string
}

/** 长期记忆：MEMORY.md 全文 + 日记检索 + 会话上下文用量 */
export default function MemoryPage() {
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [sessionId, setSessionId] = useState<string>()
  const [memory, setMemory] = useState('')
  const [usage, setUsage] = useState<ContextUsage>()
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<SearchHit[]>()
  const [saving, setSaving] = useState('')
  const [messageApi, contextHolder] = message.useMessage()

  const refreshMemory = useCallback(async () => {
    try {
      const res = await get<{ content: string }>(`/sessions/${sessionId}/memory`)
      setMemory(res.content)
    } catch {
      /* 401 由 client 统一跳登录 */
    }
  }, [sessionId])

  useEffect(() => {
    get<SessionItem[]>('/sessions').then(setSessions).catch(() => {})
  }, [])

  useEffect(() => {
    if (!sessionId) return
    refreshMemory()
    get<ContextUsage>(`/sessions/${sessionId}/context`).then(setUsage).catch(() => {})
  }, [sessionId, refreshMemory])

  const search = async () => {
    if (!sessionId || !query.trim()) return
    const res = await post<SearchHit[]>(`/sessions/${sessionId}/memory/search`, {
      query,
      limit: 5,
    })
    setHits(res)
  }

  const save = async () => {
    if (!sessionId || !saving.trim()) return
    await post(`/sessions/${sessionId}/memory/save`, { content: saving })
    messageApi.success('已写入长期记忆')
    setSaving('')
    refreshMemory()
  }

  return (
    <div style={{ padding: 24 }}>
      {contextHolder}
      <Row justify="space-between" align="middle">
        <Typography.Title level={4} style={{ margin: 0 }}>
          记忆
        </Typography.Title>
        <Space>
          <Select
            placeholder="选择会话"
            style={{ width: 240 }}
            value={sessionId}
            onChange={setSessionId}
            options={sessions.map((s) => ({ value: s.id, label: s.title || s.id }))}
          />
        </Space>
      </Row>

      {usage && (
        <Card size="small" style={{ marginTop: 16 }}>
          <Space direction="vertical" style={{ width: '100%' }} size={4}>
            <Space>
              <Typography.Text type="secondary">上下文用量</Typography.Text>
              <Typography.Text strong>
                {usage.usedTokens.toLocaleString()} / {usage.budget.toLocaleString()} tokens
              </Typography.Text>
              <Typography.Text type="secondary">窗口 {usage.windowSize} 条消息</Typography.Text>
            </Space>
            <Progress
              percent={Math.round(usage.percent * 10000) / 100}
              strokeColor={usage.percent > 0.75 ? 'var(--amber)' : 'var(--cyan)'}
              size="small"
            />
          </Space>
        </Card>
      )}

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card
            title="长期记忆（MEMORY.md）"
            size="small"
            extra={
              <Button size="small" onClick={refreshMemory} disabled={!sessionId}>
                刷新
              </Button>
            }
          >
            {memory ? (
              <div className="markdown-body">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{memory}</ReactMarkdown>
              </div>
            ) : (
              <Empty description="暂无记忆" />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="检索记忆" size="small">
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onPressEnter={search}
                placeholder="输入关键词，如「用户的职业偏好」"
              />
              <Button type="primary" onClick={search} disabled={!sessionId}>
                检索
              </Button>
            </Space.Compact>
            <List
              style={{ marginTop: 12 }}
              dataSource={hits}
              locale={{ emptyText: '尚未检索' }}
              renderItem={(hit) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <Space>
                        <Tag color="blue">{hit.source}</Tag>
                        <Typography.Text type="secondary">
                          相似度 {(hit.score * 100).toFixed(1)}%
                        </Typography.Text>
                      </Space>
                    }
                    description={hit.content}
                  />
                </List.Item>
              )}
            />
            <Input.TextArea
              style={{ marginTop: 12 }}
              rows={3}
              value={saving}
              onChange={(e) => setSaving(e.target.value)}
              placeholder="手动写入一条长期记忆…"
            />
            <Button
              type="dashed"
              style={{ marginTop: 8 }}
              onClick={save}
              disabled={!sessionId || !saving.trim()}
            >
              保存到长期记忆
            </Button>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
