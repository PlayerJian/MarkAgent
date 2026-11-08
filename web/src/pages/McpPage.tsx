import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Drawer,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { del, get, post, put } from '../api/client'

interface McpServer {
  id: string
  name: string
  transport: 'stdio' | 'sse'
  command: string | null
  args: string[]
  url: string | null
  enabled: boolean
  status: { connected: boolean; error: string | null; toolCount?: number }
}

interface McpTool {
  name: string
  description: string
  inputSchema: unknown
}

const EMPTY_FORM = { name: '', transport: 'stdio', command: '', argsText: '', url: '' }

/** MCP 服务器管理：stdio/sse 配置 + 连接状态 + 工具清单 */
export default function McpPage() {
  const [servers, setServers] = useState<McpServer[]>([])
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<McpServer | null>(null)
  const [form] = Form.useForm()
  const [tools, setTools] = useState<McpTool[]>([])
  const [toolsOpen, setToolsOpen] = useState(false)
  const [messageApi, contextHolder] = message.useMessage()

  const refresh = useCallback(async () => {
    try {
      setServers(await get<McpServer[]>('/mcp/servers'))
    } catch {
      /* 401 统一处理 */
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const save = async () => {
    const values = await form.validateFields()
    const payload = {
      name: values.name,
      transport: values.transport,
      command: values.transport === 'stdio' ? values.command : null,
      args: values.transport === 'stdio'
        ? (values.argsText ?? '').split(/\s+/).filter(Boolean)
        : [],
      url: values.transport === 'sse' ? values.url : null,
      enabled: values.enabled,
    }
    try {
      if (editing) {
        await put(`/mcp/servers/${editing.id}`, payload)
      } else {
        await post('/mcp/servers', payload)
      }
      messageApi.success(editing ? '已更新' : '已添加')
      setModalOpen(false)
      refresh()
    } catch (e) {
      messageApi.error((e as Error).message)
    }
  }

  const toggleEnabled = async (server: McpServer, enabled: boolean) => {
    await put(`/mcp/servers/${server.id}`, {
      name: server.name,
      transport: server.transport,
      command: server.command,
      args: server.args,
      url: server.url,
      enabled,
    })
    refresh()
  }

  const remove = async (id: string) => {
    await del(`/mcp/servers/${id}`)
    messageApi.success('已删除')
    refresh()
  }

  const showTools = async (id: string) => {
    setTools(await get<McpTool[]>(`/mcp/servers/${id}/tools`))
    setToolsOpen(true)
  }

  const test = async (id: string) => {
    try {
      const res = await post<{ ok: boolean; toolCount?: number; error?: string }>(`/mcp/servers/${id}/test`)
      if (res.ok) messageApi.success(`连接正常，${res.toolCount} 个工具可用`)
      else messageApi.error(res.error ?? '连接失败')
    } catch (e) {
      messageApi.error((e as Error).message)
    }
  }

  return (
    <div style={{ padding: 24 }}>
      {contextHolder}
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>MCP 服务器</Typography.Title>
        <Button
          type="primary"
          onClick={() => {
            setEditing(null)
            form.setFieldsValue({ ...EMPTY_FORM, enabled: true })
            setModalOpen(true)
          }}
        >
          添加服务器
        </Button>
      </Space>

      <Table
        rowKey="id"
        style={{ marginTop: 16 }}
        dataSource={servers}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name' },
          {
            title: '传输',
            dataIndex: 'transport',
            render: (t: string) => <Tag color={t === 'stdio' ? 'blue' : 'purple'}>{t}</Tag>,
          },
          {
            title: '状态',
            render: (_, s) =>
              s.status.connected ? (
                <Badge status="success" text={`已连接（${s.status.toolCount ?? 0} 工具）`} />
              ) : (
                <Badge status="error" text={s.status.error ? '失败' : '未连接'} />
              ),
          },
          {
            title: '启用',
            render: (_, s) => (
              <Switch checked={s.enabled} onChange={(v) => toggleEnabled(s, v)} size="small" />
            ),
          },
          {
            title: '操作',
            render: (_, s) => (
              <Space size={4}>
                <Button size="small" onClick={() => test(s.id)} disabled={!s.status.connected}>测试</Button>
                <Button size="small" onClick={() => showTools(s.id)} disabled={!s.status.connected}>
                  工具
                </Button>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(s)
                    form.setFieldsValue({
                      name: s.name,
                      transport: s.transport,
                      command: s.command ?? '',
                      argsText: (s.args ?? []).join(' '),
                      url: s.url ?? '',
                      enabled: s.enabled,
                    })
                    setModalOpen(true)
                  }}
                >
                  编辑
                </Button>
                <Popconfirm title="删除该服务器？" onConfirm={() => remove(s.id)}>
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editing ? '编辑服务器' : '添加服务器'}
        open={modalOpen}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ ...EMPTY_FORM, enabled: true }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="如 filesystem" />
          </Form.Item>
          <Form.Item name="transport" label="传输方式">
            <Select
              options={[
                { value: 'stdio', label: 'stdio（本地命令进程）' },
                { value: 'sse', label: 'sse（远程端点）' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(a, b) => a.transport !== b.transport}>
            {({ getFieldValue }) =>
              getFieldValue('transport') === 'stdio' ? (
                <>
                  <Form.Item name="command" label="命令" rules={[{ required: true }]}>
                    <Input placeholder="node / npx / 可执行文件路径" />
                  </Form.Item>
                  <Form.Item name="argsText" label="参数（空格分隔）">
                    <Input placeholder="-y @modelcontextprotocol/server-filesystem /path" />
                  </Form.Item>
                </>
              ) : (
                <Form.Item name="url" label="SSE 端点" rules={[{ required: true }]}>
                  <Input placeholder="https://host/mcp/sse" />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="enabled" label="保存后立即连接" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title="MCP 工具清单" open={toolsOpen} onClose={() => setToolsOpen(false)} width={520}>
        <List
          dataSource={tools}
          renderItem={(t) => (
            <List.Item>
              <List.Item.Meta
                title={<Typography.Text code>{t.name}</Typography.Text>}
                description={t.description || '（无描述）'}
              />
            </List.Item>
          )}
        />
      </Drawer>
    </div>
  )
}
