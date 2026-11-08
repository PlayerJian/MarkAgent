import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Layout, Menu, Dropdown, Avatar, theme } from 'antd'
import {
  CommentOutlined,
  UnorderedListOutlined,
  DatabaseOutlined,
  ToolOutlined,
  ApiOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '../stores/authStore'
import { setToken } from '../api/client'

const { Sider, Content, Header } = Layout

const menuItems = [
  { key: '/', icon: <CommentOutlined />, label: '对话' },
  { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务' },
  { key: '/memory', icon: <DatabaseOutlined />, label: '记忆' },
  { key: '/skills', icon: <ToolOutlined />, label: '技能' },
  { key: '/mcp', icon: <ApiOutlined />, label: 'MCP' },
  { key: '/settings', icon: <SettingOutlined />, label: '设置' },
]

/** 移动端底部导航（设置入口收进顶栏用户菜单） */
const mobileItems = menuItems.filter((m) => m.key !== '/settings')

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuthStore()
  const { token: themeToken } = theme.useToken()

  const selectedKey = menuItems
    .map((m) => m.key)
    .filter((k) => (k === '/' ? location.pathname === '/' : location.pathname.startsWith(k)))
    .sort((a, b) => b.length - a.length)[0]

  return (
    <Layout style={{ height: '100vh' }}>
      {/* 桌面侧边栏 */}
      <Sider theme="light" width={200} className="desktop-only" style={{ borderRight: '1px solid #f0f0f0' }}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 700,
            fontSize: 18,
            color: themeToken.colorPrimary,
          }}
        >
          🤖 MarkAgent
        </div>
        <Menu mode="inline" selectedKeys={[selectedKey]} items={menuItems} onClick={(e) => navigate(e.key)} />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 16px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <Dropdown
            menu={{
              items: [
                { key: 'settings', icon: <SettingOutlined />, label: '设置' },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
              ],
              onClick: ({ key }) => {
                if (key === 'settings') {
                  navigate('/settings')
                } else if (key === 'logout') {
                  setToken(null)
                  logout()
                  navigate('/login')
                }
              },
            }}
          >
            <span style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ marginRight: 8 }} />
              {user?.displayName ?? user?.username}
            </span>
          </Dropdown>
        </Header>
        <Content style={{ overflow: 'auto' }} className="mobile-pad">
          <Outlet />
        </Content>
      </Layout>

      {/* 移动端底部导航（<768px） */}
      <div className="mobile-nav mobile-only">
        {mobileItems.map((m) => (
          <div
            key={m.key}
            className={`mobile-nav-item${selectedKey === m.key ? ' active' : ''}`}
            onClick={() => navigate(m.key)}
          >
            {m.icon}
            <span>{m.label}</span>
          </div>
        ))}
      </div>
    </Layout>
  )
}
