/** REST API 薄封装：自动附带 JWT，统一错误处理 */
const TOKEN_KEY = 'markagent_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  code: string
  status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`/api/v1${path}`, { ...options, headers })
  if (res.status === 401) {
    setToken(null)
    window.location.href = '/login'
    throw new ApiError('UNAUTHORIZED', '登录已过期', 401)
  }
  if (!res.ok) {
    let body: { code?: string; message?: string } = {}
    try {
      body = await res.json()
    } catch {
      /* 非 JSON 响应 */
    }
    throw new ApiError(body.code ?? 'HTTP_' + res.status, body.message ?? res.statusText, res.status)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export const get = <T>(path: string) => api<T>(path)
export const put = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
export const del = <T>(path: string) => api<T>(path, { method: 'DELETE' })
