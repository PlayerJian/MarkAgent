import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Drawer, Empty, Form, Input, List, Modal, Space, Tag, Typography, Upload, message,
} from 'antd'
import { DeleteOutlined, EyeOutlined, InboxOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { del, get, post, ApiError } from '../api/client'
import { getToken } from '../api/client'

const { Text } = Typography

interface SkillRecord {
  name: string
  description: string
  version: string
  path: string
  enabled: boolean
}

interface SkillDetail extends SkillRecord {
  content: string
}

const SKILL_TEMPLATE = `---
name: my-skill
description: 一句话说明这个技能做什么
version: 1.0.0
---

# 技能名（my-skill）

在这里写技能指引：执行步骤、要求、输出格式等。
模型会在相关任务中调用 skill_invoke 读取本内容并照做。
`

/** 技能管理：SKILL.md 目录式仓库（列表/详情/新建/删除/重载/zip 上传） */
export default function SkillsPage() {
  const [skills, setSkills] = useState<SkillRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<SkillDetail | null>(null)
  const [creating, setCreating] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSkills(await get<SkillRecord[]>('/skills'))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const showDetail = async (name: string) => {
    try {
      setDetail(await get<SkillDetail>(`/skills/${name}`))
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : String(e))
    }
  }

  const onCreate = async () => {
    const values = await form.validateFields()
    try {
      await post('/skills', { name: values.name, content: values.content })
      message.success(`技能 ${values.name} 已保存`)
      setCreating(false)
      form.resetFields()
      void load()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : String(e))
    }
  }

  const onDelete = async (name: string) => {
    Modal.confirm({
      title: `删除技能 ${name}？`,
      content: '将删除该技能目录（含 SKILL.md 与资源文件）。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/skills/${name}`)
          message.success('已删除')
          void load()
        } catch (e) {
          message.error(e instanceof ApiError ? e.message : String(e))
        }
      },
    })
  }

  const onReload = async () => {
    await load()
    message.success('仓库已重载')
  }

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>技能仓库</Typography.Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={onReload}>重载</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>新建技能</Button>
        </Space>
      </Space>

      <Typography.Paragraph type="secondary">
        技能是「SKILL.md + 资源文件」的目录式指引，模型通过 skill_invoke 工具按需加载执行。上传 zip 时每个含 SKILL.md 的顶层目录注册为一个技能。
      </Typography.Paragraph>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}

      {/* zip 上传 */}
      <Upload.Dragger
        style={{ marginBottom: 16, padding: '12px 0' }}
        multiple={false}
        accept=".zip"
        showUploadList={false}
        customRequest={async ({ file, onSuccess, onError }) => {
          const body = new FormData()
          body.append('file', file as File)
          const token = getToken()
          try {
            const res = await fetch('/api/v1/skills/upload', {
              method: 'POST',
              headers: token ? { Authorization: `Bearer ${token}` } : {},
              body,
            })
            if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message ?? '上传失败')
            const data = (await res.json()) as { installed: string[] }
            message.success(`已安装技能：${data.installed.join(', ') || '（无有效技能包）'}`)
            onSuccess?.(data)
            void load()
          } catch (e) {
            message.error(String(e))
            onError?.(e as Error)
          }
        }}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">点击或拖拽技能 zip 包到此处上传</p>
      </Upload.Dragger>

      <List
        loading={loading}
        dataSource={skills}
        locale={{ emptyText: <Empty description="尚无技能 —— 点击右上角「新建技能」或上传 zip" /> }}
        renderItem={(skill) => (
          <List.Item
            actions={[
              <Button key="view" size="small" icon={<EyeOutlined />} onClick={() => showDetail(skill.name)}>查看</Button>,
              <Button key="del" size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(skill.name)}>删除</Button>,
            ]}
          >
            <List.Item.Meta
              title={
                <Space size={8}>
                  <Text code>{skill.name}</Text>
                  <Tag color="blue">v{skill.version}</Tag>
                  {skill.enabled ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>}
                </Space>
              }
              description={skill.description}
            />
          </List.Item>
        )}
      />

      {/* 详情抽屉 */}
      <Drawer
        title={detail ? `技能：${detail.name}` : '技能详情'}
        width={560}
        open={detail !== null}
        onClose={() => setDetail(null)}
      >
        {detail && (
          <>
            <Space style={{ marginBottom: 8 }}>
              <Tag color="blue">v{detail.version}</Tag>
              <Text type="secondary">{detail.path}/</Text>
            </Space>
            <Typography.Paragraph type="secondary">{detail.description}</Typography.Paragraph>
            <Card size="small" title="SKILL.md" style={{ marginTop: 8 }}>
              <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 13 }}>{detail.content}</pre>
            </Card>
          </>
        )}
      </Drawer>

      {/* 新建弹窗 */}
      <Modal
        title="新建技能"
        open={creating}
        onOk={onCreate}
        onCancel={() => setCreating(false)}
        okText="保存"
        width={640}
      >
        <Form form={form} layout="vertical" initialValues={{ name: '', content: SKILL_TEMPLATE }}>
          <Form.Item name="name" label="技能名（目录名）" rules={[{ required: true, message: '请输入技能名' }]}>
            <Input placeholder="如 report-writer" />
          </Form.Item>
          <Form.Item name="content" label="SKILL.md 内容（含 frontmatter）" rules={[{ required: true, message: '请输入内容' }]}>
            <Input.TextArea rows={14} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
