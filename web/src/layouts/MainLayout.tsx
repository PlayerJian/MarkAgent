import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Layout, Menu, Dropdown, Avatar } from 'antd'
import {
  CommentOutlined,
  UnorderedListOutlined,
  DatabaseOutlined,
  ToolOutlined,
  ApiOutlined,
  FolderOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '../stores/authStore'
import { setToken } from '../api/client'
import SessionListPanel from '../components/SessionListPanel'

const { Sider, Content, Header } = Layout

/** 桌面侧边菜单：任务已并入下方历史对话面板（豆包/千问/Codex 式） */
const menuItems = [
  { key: '/', icon: <CommentOutlined />, label: '对话' },
  { key: '/memory', icon: <DatabaseOutlined />, label: '记忆' },
  { key: '/skills', icon: <ToolOutlined />, label: '技能' },
  { key: '/mcp', icon: <ApiOutlined />, label: 'MCP' },
  { key: '/docs', icon: <FolderOutlined />, label: '文档库' },
  { key: '/settings', icon: <SettingOutlined />, label: '设置' },
]

/** 移动端底部导航：设置入口收进顶栏用户菜单；移动端无侧边栏历史面板，保留任务记录入口 */
const mobileItems = [
  ...menuItems.filter((m) => m.key !== '/settings'),
  { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务' },
]

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuthStore()

  // /chat/:sessionId 属于「对话」分区
  const navKeys = [...menuItems.map((m) => m.key), '/tasks']
  const selectedKey = navKeys
    .filter((k) =>
      k === '/' ? location.pathname === '/' || location.pathname.startsWith('/chat') : location.pathname.startsWith(k))
    .sort((a, b) => b.length - a.length)[0]

  return (
    <Layout style={{ height: '100vh' }}>
      {/* 桌面侧边栏：Logo + 菜单 + 历史对话面板（面板占满剩余空间，内部滑动窗口） */}
      <Sider theme="dark" width={240} className="desktop-only" style={{ borderRight: '1px solid var(--line-2)' }}>
        <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <div
            style={{
              height: 56,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 9,
            }}
          >
            <svg width="25" height="25" viewBox="0 0 40 40" aria-hidden="true">
              <polygon
                points="20,4 34,12 34,28 20,36 6,28 6,12"
                fill="none"
                stroke="#39e6ff"
                strokeWidth="1.6"
              />
              <circle cx="20" cy="20" r="3.4" fill="#39e6ff" />
            </svg>
            <span
              style={{
                fontFamily: 'var(--font-display)',
                fontWeight: 700,
                fontSize: 14.5,
                letterSpacing: '.14em',
                color: 'var(--cyan)',
              }}
            >
              MARKAGENT
            </span>
          </div>
          <Menu
            mode="inline"
            selectedKeys={selectedKey ? [selectedKey] : []}
            items={menuItems}
            onClick={(e) => navigate(e.key)}
          />
          <SessionListPanel />
        </div>
      </Sider>

      <Layout>
        <Header
          style={{
            background: 'transparent',
            padding: '0 16px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            borderBottom: '1px solid var(--line-2)',
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
