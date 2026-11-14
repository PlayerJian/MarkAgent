import { useEffect, useRef, useState } from 'react'
import {
  Alert, Badge, Button, Empty, Input, Select, Space, Tooltip, Typography,
} from 'antd'
import {
  ArrowDownOutlined, CaretRightOutlined, PauseOutlined, PlusOutlined, StopOutlined,
} from '@ant-design/icons'
import { useChatStore } from '../stores/chatStore'
import { ConversationList, STATUS_BADGE } from '../components/ConversationView'

const { Text } = Typography
const { TextArea } = Input

/** 对话工作台：模型选择 + 流式消息 + 任务控制（pause/resume/stop） */
export default function ChatPage() {
  const messages = useChatStore((s) => s.messages)
  const taskStatus = useChatStore((s) => s.taskStatus)
  const model = useChatStore((s) => s.model)
  const planning = useChatStore((s) => s.planning)
  const thinking = useChatStore((s) => s.thinking)
  const plan = useChatStore((s) => s.plan)
  const agents = useChatStore((s) => s.agents)
  const error = useChatStore((s) => s.error)
  const compactNotice = useChatStore((s) => s.compactNotice)
  const send = useChatStore((s) => s.send)
  const setModel = useChatStore((s) => s.setModel)
  const setPlanning = useChatStore((s) => s.setPlanning)
  const setThinking = useChatStore((s) => s.setThinking)
  const pauseTask = useChatStore((s) => s.pauseTask)
  const resumeTask = useChatStore((s) => s.resumeTask)
  const stopTask = useChatStore((s) => s.stopTask)
  const reset = useChatStore((s) => s.reset)

  const [input, setInput] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  /** 是否粘底跟随：用户上翻查看历史后置 false，回到底部（或发新消息）后恢复 */
  const stickRef = useRef(true)
  const [showJump, setShowJump] = useState(false)

  const scrollToBottom = () => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }

  // 新内容到达时仅在粘底状态下跟随。直接设 scrollTop（瞬时）而不是 scrollIntoView(smooth)：
  // 后者会滚动所有可滚动祖先（外层 Content），且 streaming 高频事件会不断打断/重启动画，
  // 造成视图抖动并持续抢占用户滚动
  useEffect(() => {
    if (stickRef.current) scrollToBottom()
  }, [messages, plan, agents])

  const onListScroll = () => {
    const el = listRef.current
    if (!el) return
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight
    const stick = dist < 80
    stickRef.current = stick
    setShowJump(!stick)
  }

  const badge = STATUS_BADGE[taskStatus]

  const onSend = async () => {
    const goal = input.trim()
    if (!goal) return
    setInput('')
    // 发新消息：回到粘底跟随，立即滚到新消息处
    stickRef.current = true
    setShowJump(false)
    try {
      await send(goal)
    } catch {
      /* 错误已在 store.error 展示 */
    }
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', maxWidth: 960, margin: '0 auto', width: '100%' }}>
      {/* 工具栏 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0', flexWrap: 'wrap' }}>
        <Select
          value={model}
          onChange={setModel}
          style={{ width: 190 }}
          disabled={taskStatus === 'running'}
          options={[
            { value: 'deepseek-flash', label: 'deepseek-flash（极速）' },
            { value: 'deepseek-pro', label: 'deepseek-pro（旗舰）' },
          ]}
        />
        <Tooltip title="开启后模型先输出思考过程再作答（速度慢、消耗大，适合数学/逻辑/深度分析）">
          <Space size={4}>
            <Text type="secondary">思考</Text>
            <Badge dot={thinking} color="purple">
              <Button
                size="small"
                type={thinking ? 'primary' : 'default'}
                onClick={() => setThinking(!thinking)}
                disabled={taskStatus === 'running'}
              >
                {thinking ? '开启' : '关闭'}
              </Button>
            </Badge>
          </Space>
        </Tooltip>
        <Tooltip title="复杂任务先规划再执行">
          <Space size={4}>
            <Text type="secondary">规划</Text>
            <Badge dot={planning === 'auto'} color="blue">
              <Button
                size="small"
                type={planning === 'auto' ? 'primary' : 'default'}
                onClick={() => setPlanning(planning === 'auto' ? 'off' : 'auto')}
              >
                {planning === 'auto' ? '自动' : '关闭'}
              </Button>
            </Badge>
          </Space>
        </Tooltip>
        {taskStatus !== 'idle' && (
          <Space size={8}>
            <Badge status={badge.status} text={badge.text} />
            {taskStatus === 'running' && (
              <Button size="small" icon={<PauseOutlined />} onClick={pauseTask}>暂停</Button>
            )}
            {taskStatus === 'paused' && (
              <Button size="small" type="primary" icon={<CaretRightOutlined />} onClick={resumeTask}>继续</Button>
            )}
            {(taskStatus === 'running' || taskStatus === 'paused') && (
              <Button size="small" danger icon={<StopOutlined />} onClick={stopTask}>停止</Button>
            )}
          </Space>
        )}
        <div style={{ flex: 1 }} />
        {messages.length > 0 && (
          <Button size="small" icon={<PlusOutlined />} onClick={reset}>新对话</Button>
        )}
      </div>

      {error && (
        <Alert type="error" showIcon closable message={error} style={{ marginBottom: 8 }} />
      )}

      {/* 消息流（内部滚动；用户上翻时暂停跟随，右下角出现回到底部按钮） */}
      <div style={{ flex: 1, minHeight: 0, position: 'relative', display: 'flex', flexDirection: 'column' }}>
        <div
          ref={listRef}
          onScroll={onListScroll}
          style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '8px 4px' }}
        >
          {messages.length === 0 ? (
            <Empty
              style={{ marginTop: 80 }}
              description={<Text type="secondary">向 MarkAgent 描述你的任务，例如：「调研 AgentScope 与 Spring AI 的集成方式并给出对比报告」</Text>}
            />
          ) : (
            <ConversationList messages={messages} plan={plan} agents={agents} taskStatus={taskStatus} compactNotice={compactNotice} />
          )}
        </div>
        {showJump && (
          <Button
            size="small"
            shape="circle"
            icon={<ArrowDownOutlined />}
            title="回到底部"
            style={{ position: 'absolute', right: 16, bottom: 12, boxShadow: '0 2px 8px rgba(0,0,0,0.15)' }}
            onClick={() => {
              stickRef.current = true
              setShowJump(false)
              scrollToBottom()
            }}
          />
        )}
      </div>

      {/* 输入区 */}
      <div style={{ padding: '12px 0 16px' }}>
        <TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="输入任务描述，Ctrl+Enter 发送"
          autoSize={{ minRows: 2, maxRows: 6 }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
              e.preventDefault()
              onSend()
            }
          }}
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
          <Button type="primary" onClick={onSend} loading={taskStatus === 'running'} disabled={!input.trim()}>
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
