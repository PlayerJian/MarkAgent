import { useCallback, useEffect, useState } from 'react'
import {
  Button, Card, Empty, Input, List, Modal, Popconfirm, Space, Table, Tag, Tooltip, Typography, Upload, message,
} from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { del, get, post, upload } from '../api/client'

const { Text } = Typography

interface LibraryItem {
  id: string
  userId: string
  name: string
  createdAt: string
}

interface DocItem {
  id: string
  libraryId: string
  fileName: string
  status: 'PARSING' | 'READY' | 'FAILED'
  parseStage: string | null
  pageCount: number
  error: string | null
  createdAt: string
}

const STATUS_META: Record<DocItem['status'], { color: string; text: string }> = {
  PARSING: { color: 'processing', text: '解析中' },
  READY: { color: 'success', text: '就绪' },
  FAILED: { color: 'error', text: '失败' },
}

/** 解析阶段 → 用户可读文案（queued=本地线程池排队，其余为 MinerU 包装服务协议阶段） */
const STAGE_TEXT: Record<string, string> = {
  queued: '排队中',
  submitting: '提交中',
  prepared: '已受理',
  'waiting-file': '文件校验中',
  pending: '排队等解析',
  running: '解析执行中',
}

/** 文档库管理：建库/删库、上传文档、解析状态、失败重试 */
export default function DocsPage() {
  const [libraries, setLibraries] = useState<LibraryItem[]>([])
  const [selected, setSelected] = useState<string>()
  const [docs, setDocs] = useState<DocItem[]>([])
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [messageApi, contextHolder] = message.useMessage()

  const refreshLibraries = useCallback(() => {
    get<LibraryItem[]>('/docs/libraries').then(setLibraries).catch(() => {})
  }, [])

  const refreshDocs = useCallback((libraryId: string) => {
    get<DocItem[]>(`/docs/libraries/${libraryId}/documents`).then(setDocs).catch(() => {})
  }, [])

  useEffect(() => {
    refreshLibraries()
  }, [refreshLibraries])

  useEffect(() => {
    if (!selected) {
      setDocs([])
      return
    }
    refreshDocs(selected)
  }, [selected, refreshDocs])

  // 有文档在解析时轮询状态（3s 间隔）
  useEffect(() => {
    if (!selected || !docs.some((d) => d.status === 'PARSING')) return
    const timer = setInterval(() => refreshDocs(selected), 3000)
    return () => clearInterval(timer)
  }, [selected, docs, refreshDocs])

  const createLibrary = async () => {
    if (!newName.trim()) return
    await post<LibraryItem>('/docs/libraries', { name: newName.trim() })
    messageApi.success('文档库已创建')
    setNewName('')
    setCreating(false)
    refreshLibraries()
  }

  const deleteLibrary = async (id: string) => {
    await del(`/docs/libraries/${id}`)
    messageApi.success('文档库已删除')
    if (selected === id) setSelected(undefined)
    refreshLibraries()
  }

  const uploadDocs = async (files: File[]) => {
    if (!selected || files.length === 0) return
    const form = new FormData()
    files.forEach((f) => form.append('files', f))
    const res = await upload<DocItem[]>(`/docs/libraries/${selected}/documents`, form)
    messageApi.success(`已受理 ${res.length} 个文档，解析完成后自动建索引`)
    refreshDocs(selected)
  }

  const retryDoc = async (doc: DocItem) => {
    await post(`/docs/documents/${doc.id}/retry`)
    messageApi.success('已重新提交解析')
    refreshDocs(selected!)
  }

  const deleteDoc = async (doc: DocItem) => {
    await del(`/docs/documents/${doc.id}`)
    messageApi.success('文档已删除')
    refreshDocs(selected!)
  }

  return (
    <div style={{ padding: 24 }}>
      {contextHolder}
      <Space align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>文档库</Typography.Title>
        <Button size="small" icon={<ReloadOutlined />} onClick={refreshLibraries}>刷新</Button>
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          新建文档库
        </Button>
      </Space>

      <Card size="small" title="我的文档库" style={{ marginBottom: 16 }}>
        {libraries.length === 0 ? (
          <Empty description="暂无文档库，点击「新建文档库」开始" />
        ) : (
          <List
            dataSource={libraries}
            renderItem={(lib) => (
              <List.Item
                style={{ cursor: 'pointer', background: selected === lib.id ? 'var(--bg-hover)' : undefined }}
                onClick={() => setSelected(lib.id)}
                actions={[
                  <Popconfirm
                    key="del"
                    title="删除该库及全部文档？"
                    onConfirm={(e) => {
                      e?.stopPropagation()
                      deleteLibrary(lib.id)
                    }}
                  >
                    <Button size="small" danger icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()}>
                      删除
                    </Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  title={<Text strong={selected === lib.id}>{lib.name}</Text>}
                  description={`创建于 ${lib.createdAt.slice(0, 19).replace('T', ' ')}`}
                />
              </List.Item>
            )}
          />
        )}
      </Card>

      {selected && (
        <Card
          size="small"
          title="库内文档"
          extra={
            <Upload
              multiple
              showUploadList={false}
              accept=".txt,.md,.csv,.json,.pdf,.docx,.xlsx,.pptx"
              customRequest={({ file, onSuccess, onError }) => {
                uploadDocs([file as File])
                  .then(() => onSuccess?.(null))
                  .catch((e) => {
                    messageApi.error(e instanceof Error ? e.message : '上传失败')
                    onError?.(e as Error)
                  })
              }}
            >
              <Button size="small" icon={<UploadOutlined />}>上传文档</Button>
            </Upload>
          }
        >
          <Table<DocItem>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={docs}
            columns={[
              { title: '文件名', dataIndex: 'fileName', ellipsis: true },
              {
                title: '状态',
                dataIndex: 'status',
                width: 150,
                render: (s: DocItem['status'], doc) => {
                  const meta = STATUS_META[s]
                  const stage = doc.parseStage ? STAGE_TEXT[doc.parseStage] : null
                  return (
                    <Space size={4}>
                      <Tag color={meta.color}>{meta.text}</Tag>
                      {s === 'PARSING' && stage && (
                        <Text type="secondary" style={{ fontSize: 12 }}>{stage}</Text>
                      )}
                    </Space>
                  )
                },
              },
              { title: '页数', dataIndex: 'pageCount', width: 70, render: (n: number) => (n >= 0 ? n : '-') },
              {
                title: '说明',
                dataIndex: 'error',
                ellipsis: true,
                render: (e: string | null) =>
                  e ? <Tooltip title={e}><Text type="danger">{e}</Text></Tooltip> : null,
              },
              {
                title: '操作',
                key: 'actions',
                width: 150,
                render: (_, doc) => (
                  <Space size={4}>
                    {doc.status === 'FAILED' && (
                      <Button size="small" onClick={() => retryDoc(doc)}>重试</Button>
                    )}
                    <Popconfirm title="删除该文档？" onConfirm={() => deleteDoc(doc)}>
                      <Button size="small" danger>删除</Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      )}

      <Modal
        title="新建文档库"
        open={creating}
        onOk={createLibrary}
        onCancel={() => setCreating(false)}
        okText="创建"
        cancelText="取消"
      >
        <Input
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onPressEnter={createLibrary}
          placeholder="库名，如「2024 年报合集」"
          maxLength={100}
        />
      </Modal>
    </div>
  )
}
