/**
 * 人工审批弹框：ask_user 工具广播 ASK_USER 事件入列后弹出，用户点选选项后经
 * REST 应答（POST /tasks/{taskId}/asks/{callId}/respond）唤醒阻塞的工具线程。
 * 挂在 MainLayout：任务后台运行期间切到其他页面弹框仍然可见。
 * 倒计时与服务端钳制后的 timeoutSeconds 同步：归零说明服务端已按超时策略
 * （auto-approve / 中止）收尾，本地直接出列，无需再应答。
 */
import { useEffect, useRef, useState } from 'react'
import { Button, Modal, Space, Tag, Typography } from 'antd'
import { ExclamationCircleOutlined } from '@ant-design/icons'
import { useChatStore } from '../stores/chatStore'
import type { PendingAskUi } from '../stores/replay'

const { Paragraph } = Typography

/** 单个待决审批卡片：问题 + 剩余时间倒计时 + 选项按钮 */
function AskCard({ ask }: { ask: PendingAskUi }) {
  const answerAsk = useChatStore((s) => s.answerAsk)
  const dismissAsk = useChatStore((s) => s.dismissAsk)
  const [left, setLeft] = useState(ask.timeoutSeconds)
  const [picking, setPicking] = useState(false)
  const answered = useRef(false)

  // 每秒递减；归零时本地出列（服务端同时按超时策略唤醒工具线程）
  useEffect(() => {
    if (left <= 0) {
      dismissAsk(ask.callId)
      return
    }
    const timer = setTimeout(() => setLeft((v) => v - 1), 1000)
    return () => clearTimeout(timer)
  }, [left, ask.callId, dismissAsk])

  const pick = async (opt: string) => {
    if (answered.current) return
    answered.current = true
    setPicking(true)
    // 应答失败（已取消/超时）也不恢复：服务端已收尾，本地出列即可
    await answerAsk(ask.callId, opt)
  }

  const options = ask.options.length > 0 ? ask.options : ['同意', '拒绝']

  return (
    <div>
      <Space align="start" style={{ display: 'flex' }}>
        <ExclamationCircleOutlined style={{ color: 'var(--amber)', marginTop: 4 }} />
        <div>
          <Paragraph style={{ marginBottom: 4, whiteSpace: 'pre-wrap' }}>{ask.question}</Paragraph>
          <Tag color="orange" style={{ marginInlineEnd: 0, fontFamily: 'var(--font-mono)' }}>
            {left}s 后按超时处理
          </Tag>
        </div>
      </Space>
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        {options.map((opt) => (
          <Button key={opt} onClick={() => pick(opt)} loading={picking} disabled={picking}>
            {opt}
          </Button>
        ))}
      </div>
    </div>
  )
}

/** 待决审批弹框：closable=false，用户必须点选（或等超时/暂停任务），避免随手关闭后忘记 */
export default function AskDialog() {
  const pendingAsks = useChatStore((s) => s.pendingAsks)

  return (
    <Modal
      open={pendingAsks.length > 0}
      title={
        <Space>
          <ExclamationCircleOutlined style={{ color: 'var(--amber)' }} />
          人工审批
        </Space>
      }
      footer={null}
      closable={false}
      maskClosable={false}
      keyboard={false}
      centered
      width={520}
    >
      {pendingAsks.map((ask, i) => (
        <div
          key={ask.callId}
          style={{
            paddingTop: i > 0 ? 12 : 0,
            marginTop: i > 0 ? 12 : 0,
            borderTop: i > 0 ? '1px dashed var(--line-2)' : 'none',
          }}
        >
          <AskCard ask={ask} />
        </div>
      ))}
    </Modal>
  )
}
