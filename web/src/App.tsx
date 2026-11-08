import { useEffect, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import { ApiError, get } from './api/client'
import { useAuthStore } from './stores/authStore'
import type { UserInfo } from './stores/authStore'
import MainLayout from './layouts/MainLayout'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import TasksPage from './pages/TasksPage'
import SessionDetailPage from './pages/SessionDetailPage'
import MemoryPage from './pages/MemoryPage'
import SkillsPage from './pages/SkillsPage'
import McpPage from './pages/McpPage'
import SettingsPage from './pages/SettingsPage'

export default function App() {
  const token = useAuthStore((s) => s.token)
  const setAuth = useAuthStore((s) => s.setAuth)
  const logout = useAuthStore((s) => s.logout)
  const [checking, setChecking] = useState(!!token)

  // 启动时校验本地 token：服务重启后 JWT 密钥轮换（随机密钥）会让旧 token 失效，
  // 刷新页面应立即跳登录页，而不是等到点功能按钮报 401 才跳。
  // 校验通过则顺带刷新用户信息；网络错误（服务未起）保留登录态，等服务恢复。
  useEffect(() => {
    if (!token) return
    setChecking(true)
    let cancelled = false
    get<UserInfo>('/auth/me')
      .then((user) => {
        if (!cancelled) setAuth(token, user)
      })
      .catch((e) => {
        // 401：client.ts 已清原始 token 并跳转 /login；这里同步清 zustand 持久化副本
        if (!cancelled && e instanceof ApiError && e.status === 401) logout()
      })
      .finally(() => {
        if (!cancelled) setChecking(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  return (
    <BrowserRouter>
      {checking ? (
        <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Spin tip="正在验证登录状态…" size="large">
            <div style={{ width: 120, height: 60 }} />
          </Spin>
        </div>
      ) : (
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={token ? <MainLayout /> : <Navigate to="/login" replace />}
          >
            <Route index element={<ChatPage />} />
            <Route path="tasks" element={<TasksPage />} />
            <Route path="tasks/:sessionId" element={<SessionDetailPage />} />
            <Route path="memory" element={<MemoryPage />} />
            <Route path="skills" element={<SkillsPage />} />
            <Route path="mcp" element={<McpPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      )}
    </BrowserRouter>
  )
}
