import { useEffect, useState } from 'react'
import { Card, Descriptions, Spin, Typography } from 'antd'
import { get } from '../api/client'

interface StatusInfo {
  version: string
  provider: string
  defaultModel: string
  storageRoot: string
  runningTasks: number
}

/** 设置：LLM provider 切换等（后端 /settings/llm 在 M2 提供） */
export default function SettingsPage() {
  const [status, setStatus] = useState<StatusInfo | null>(null)

  useEffect(() => {
    get<StatusInfo>('/status').then(setStatus).catch(() => setStatus(null))
  }, [])

  return (
    <div style={{ padding: 24 }}>
      <Typography.Title level={4}>设置</Typography.Title>
      <Card title="系统状态" style={{ maxWidth: 640 }}>
        {status ? (
          <Descriptions column={1}>
            <Descriptions.Item label="版本">{status.version}</Descriptions.Item>
            <Descriptions.Item label="LLM 通道">{status.provider}</Descriptions.Item>
            <Descriptions.Item label="默认模型">{status.defaultModel}</Descriptions.Item>
            <Descriptions.Item label="存储根目录">{status.storageRoot}</Descriptions.Item>
            <Descriptions.Item label="运行中任务">{status.runningTasks}</Descriptions.Item>
          </Descriptions>
        ) : (
          <Spin tip="加载中..." />
        )}
      </Card>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        LLM 通道切换与 embedding 配置将在 M2 里程碑提供。
      </Typography.Paragraph>
    </div>
  )
}
