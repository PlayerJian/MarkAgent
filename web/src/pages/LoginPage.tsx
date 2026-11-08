import { useState } from 'react'
import { Card, Form, Input, Button, Tabs, App } from 'antd'
import { UserOutlined, LockOutlined, SmileOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { post, setToken } from '../api/client'
import { useAuthStore } from '../stores/authStore'

interface AuthResp {
  token: string
  user: { id: string; username: string; displayName: string; createdAt: string }
}

export default function LoginPage() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const setAuth = useAuthStore((s) => s.setAuth)
  const [loading, setLoading] = useState(false)

  const onFinish = async (mode: 'login' | 'register', values: Record<string, string>) => {
    setLoading(true)
    try {
      const resp = await post<AuthResp>(
        mode === 'login' ? '/auth/login' : '/auth/register',
        {
          username: values.username,
          password: values.password,
          displayName: values.displayName,
        },
      )
      setToken(resp.token)
      setAuth(resp.token, resp.user)
      message.success(mode === 'login' ? '登录成功' : '注册成功')
      navigate('/')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #e6f4ff 0%, #f0f5ff 50%, #f9f0ff 100%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 4px 24px rgba(0,0,0,0.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 32 }}>🤖</div>
          <h2 style={{ margin: '8px 0 4px' }}>MarkAgent</h2>
          <div style={{ color: '#999' }}>企业级智能助手</div>
        </div>
        <Tabs
          centered
          items={[
            {
              key: 'login',
              label: '登录',
              children: (
                <Form onFinish={(v) => onFinish('login', v)}>
                  <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
                    <Input prefix={<UserOutlined />} placeholder="用户名" autoFocus />
                  </Form.Item>
                  <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined />} placeholder="密码" />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block loading={loading}>
                    登录
                  </Button>
                </Form>
              ),
            },
            {
              key: 'register',
              label: '注册',
              children: (
                <Form onFinish={(v) => onFinish('register', v)}>
                  <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }, { min: 3, message: '至少 3 个字符' }]}>
                    <Input prefix={<UserOutlined />} placeholder="用户名（至少 3 字符）" />
                  </Form.Item>
                  <Form.Item name="displayName">
                    <Input prefix={<SmileOutlined />} placeholder="昵称（可选）" />
                  </Form.Item>
                  <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '至少 6 个字符' }]}>
                    <Input.Password prefix={<LockOutlined />} placeholder="密码（至少 6 字符）" />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block loading={loading}>
                    注册并登录
                  </Button>
                </Form>
              ),
            },
          ]}
        />
      </Card>
    </div>
  )
}
