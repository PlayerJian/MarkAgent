/**
 * 对话渲染共用组件：消息气泡 + 执行过程汇总块 + 工具调用卡片。
 * ChatPage（实时）与 SessionDetailPage（历史回放）共用，保证两处渲染完全一致。
 */
import { Fragment, useEffect, useRef, useState } from 'react'
import { Card, Collapse, Space, Steps, Tag, Typography } from 'antd'
import { CheckCircleOutlined, DownOutlined, LoadingOutlined, ToolOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { AgentUi, PlanUi, TaskUiStatus, ToolCallUi, UiMessage } from '../stores/replay'

const { Text } = Typography

export const STATUS_BADGE: Record<string, { status: 'default' | 'processing' | 'success' | 'error' | 'warning'; text: string }> = {
  running: { status: 'processing', text: '执行中' },
  paused: { status: 'warning', text: '已暂停' },
  completed: { status: 'success', text: '已完成' },
  failed: { status: 'error', text: '失败' },
  cancelled: { status: 'default', text: '已停止' },
}

const PLAN_STEP_STATUS: Record<string, 'wait' | 'process' | 'finish' | 'error'> = {
  PENDING: 'wait',
  RUNNING: 'process',
  DONE: 'finish',
  FAILED: 'error',
  SKIPPED: 'wait',
}

/** 单个 worker 行：单行角色+状态+摘要，点击展开正文（完成自动折叠，手动可再开） */
function AgentRow({ agent }: { agent: AgentUi }) {
  const [open, setOpen] = useState(!agent.done)
  const prevDone = useRef(agent.done)
  useEffect(() => {
    if (!prevDone.current && agent.done) setOpen(false)
    prevDone.current = agent.done
  }, [agent.done])

  const preview = agent.done ? agent.text : agent.text.slice(-60)
  return (
    <div style={{ borderTop: '1px dashed #e5e7eb', padding: '6px 0' }}>
      <div
        onClick={() => setOpen(!open)}
        style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', minWidth: 0 }}
      >
        {agent.done ? <ToolOutlined /> : <LoadingOutlined spin />}
        <Text
          strong
          style={{ whiteSpace: 'nowrap', flexShrink: 0, maxWidth: '42%', overflow: 'hidden', textOverflow: 'ellipsis' }}
        >
          {agent.role}
        </Text>
        <Tag
          style={{ marginInlineEnd: 0, flexShrink: 0 }}
          color={agent.done ? (agent.ok ? 'green' : 'red') : 'processing'}
        >
          {agent.done ? (agent.ok ? '完成' : '失败') : '执行中'}
        </Tag>
        <span
          style={{
            flex: 1, minWidth: 0, fontSize: 12, color: '#999',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}
        >
          {preview ? preview.replace(/\s+/g, ' ') : '（执行中…）'}
        </span>
        <DownOutlined rotate={open ? 180 : 0} style={{ fontSize: 10, color: '#999', flexShrink: 0 }} />
      </div>
      {open && (
        <div style={{ marginTop: 6 }}>
          {agent.tools.length > 0 && (
            <Space size={4} wrap style={{ marginBottom: 6 }}>
              {agent.tools.map((t, i) => (
                <Tag key={i} color={t.ok ? 'blue' : 'red'} style={{ marginInlineEnd: 0 }}>{t.name}</Tag>
              ))}
            </Space>
          )}
          <div className="markdown-body" style={{ maxHeight: 200, overflowY: 'auto' }}>
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{agent.text || '（执行中…）'}</ReactMarkdown>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * 执行过程汇总块：计划 + 多智能体合并为一个可折叠区块，内联在消息流中（随聊天滚动，
 * 不挤占消息区）。执行中展开看进度，任务结束自动收起为一行摘要，点击可手动展开。
 */
export function ProcessPanel({
  plan, agents, taskStatus,
}: {
  plan: PlanUi | null
  agents: AgentUi[]
  taskStatus: TaskUiStatus
}) {
  const running = taskStatus === 'running' || taskStatus === 'paused'
  const [open, setOpen] = useState(running)
  const prevRunning = useRef(running)
  useEffect(() => {
    if (running && !prevRunning.current) setOpen(true)
    if (!running && prevRunning.current) setOpen(false)
    prevRunning.current = running
  }, [running])

  const steps = plan?.steps ?? []
  if (!steps.length && agents.length === 0) return null

  const doneSteps = steps.filter((s) => s.status === 'DONE').length
  const doneAgents = agents.filter((a) => a.done).length

  return (
    <div style={{ marginBottom: 12 }}>
      <div
        onClick={() => setOpen(!open)}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, minWidth: 0, cursor: 'pointer',
          padding: '6px 10px', background: '#f6f9ff', border: '1px solid #d6e4ff',
          borderRadius: open ? '8px 8px 0 0' : 8,
        }}
      >
        {running ? <LoadingOutlined spin /> : <CheckCircleOutlined style={{ color: '#52c41a' }} />}
        <Text strong style={{ whiteSpace: 'nowrap' }}>执行过程</Text>
        {steps.length > 0 && <Tag style={{ marginInlineEnd: 0 }}>计划 {doneSteps}/{steps.length}</Tag>}
        {agents.length > 0 && <Tag style={{ marginInlineEnd: 0 }}>智能体 {doneAgents}/{agents.length}</Tag>}
        <span style={{ flex: 1 }} />
        <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{open ? '收起' : '展开'}</Text>
        <DownOutlined rotate={open ? 180 : 0} style={{ fontSize: 10, color: '#999' }} />
      </div>
      {open && (
        <div
          style={{
            border: '1px solid #d6e4ff', borderTop: 'none', borderRadius: '0 0 8px 8px',
            padding: '8px 12px', background: '#fbfdff', maxHeight: '45vh', overflowY: 'auto',
          }}
        >
          {steps.length > 0 && (
            <Steps
              direction="vertical"
              size="small"
              current={-1}
              items={steps.map((step) => ({
                title: step.title,
                description: step.detail || undefined,
                status: PLAN_STEP_STATUS[step.status] ?? 'wait',
              }))}
            />
          )}
          {agents.length > 0 && (
            <div style={{ marginTop: steps.length > 0 ? 8 : 0 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>🤖 多智能体协作</Text>
              {agents.map((agent) => (
                <AgentRow key={agent.agentId} agent={agent} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** 工具调用卡片：运行中 spinner；完成后默认折叠为一行（结果摘要可展开查看） */
function ToolCallCard({ call }: { call: ToolCallUi }) {
  return (
    <Card
      size="small"
      style={{ marginTop: 8, background: '#fafafa' }}
      styles={{ body: { paddingTop: 8, paddingBottom: call.running ? 12 : 4 } }}
      title={
        <Space size={6}>
          {call.running ? <LoadingOutlined spin /> : <ToolOutlined />}
          <Text code>{call.name}</Text>
          {!call.running && <Tag color={call.ok ? 'green' : 'red'}>{call.ok ? '成功' : '失败'}</Tag>}
        </Space>
      }
    >
      {call.running ? (
        <Text type="secondary">工具执行中…</Text>
      ) : (
        <Collapse
          ghost
          size="small"
          items={[{
            key: 'result',
            label: <Text type="secondary" style={{ fontSize: 12 }}>结果摘要</Text>,
            children: (
              <Text type="secondary" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                {call.resultSummary || '（无输出）'}
              </Text>
            ),
          }]}
        />
      )}
    </Card>
  )
}

/** 单条消息渲染：assistant 带 reasoning 折叠 + markdown + 工具卡片 */
export function MessageBubble({ message }: { message: UiMessage }) {
  const isUser = message.role === 'user'
  return (
    <div style={{ display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start', marginBottom: 16 }}>
      <div style={{ maxWidth: '85%', minWidth: 120 }}>
        {isUser ? (
          <div style={{ background: '#1677ff', color: '#fff', padding: '8px 14px', borderRadius: 12, whiteSpace: 'pre-wrap' }}>
            {message.content}
          </div>
        ) : (
          <div style={{ background: '#fff', border: '1px solid #f0f0f0', padding: '8px 14px', borderRadius: 12 }}>
            {message.reasoning && (
              <Collapse
                ghost
                size="small"
                items={[{
                  key: 'reasoning',
                  label: <Text type="secondary" style={{ fontSize: 12 }}>🧠 思考过程</Text>,
                  children: (
                    <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                      {message.reasoning}
                    </Text>
                  ),
                }]}
              />
            )}
            {message.content ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
            ) : (
              message.streaming && <Text type="secondary">思考中…</Text>
            )}
            {message.toolCalls.map((call) => (
              <ToolCallCard key={call.callId} call={call} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 完整消息流（含执行过程块的插入位置：当前轮最后一条 assistant 消息之前（过程 → 结果）；
 * 还没有回复时追加在末尾）。ChatPage 与历史回放页共用。
 */
export function ConversationList({
  messages, plan, agents, taskStatus,
}: {
  messages: UiMessage[]
  plan: PlanUi | null
  agents: AgentUi[]
  taskStatus: TaskUiStatus
}) {
  if (messages.length === 0) return null

  let processAnchor = messages.length
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'assistant') {
      processAnchor = i
      break
    }
    if (messages[i].role === 'user') break
  }

  return (
    <>
      {messages.map((message, index) => (
        <Fragment key={message.id}>
          {index === processAnchor && (
            <ProcessPanel plan={plan} agents={agents} taskStatus={taskStatus} />
          )}
          <MessageBubble message={message} />
        </Fragment>
      ))}
      {processAnchor === messages.length && messages.length > 0 && (
        <ProcessPanel plan={plan} agents={agents} taskStatus={taskStatus} />
      )}
    </>
  )
}
