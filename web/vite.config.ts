import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'icons.svg'],
      manifest: {
        name: 'MarkAgent · 企业级智能助手',
        short_name: 'MarkAgent',
        description: '企业级 AI Agent 聊天助手：规划 / 工具调用 / 记忆 / 多智能体 / MCP',
        lang: 'zh-CN',
        theme_color: '#1677ff',
        background_color: '#f5f5f5',
        display: 'standalone',
        start_url: '/',
        icons: [
          { src: '/pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // 新版本上线即生效（新 SW skipWaiting + clientsClaim）
        skipWaiting: true,
        clientsClaim: true,
        // 静态资源缓存，API/SSE 一律走网络
        navigateFallbackDenylist: [/^\/api\//],
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      // 开发态代理到 markagent-server（SSE 需要 proxy 支持流式透传，vite 默认开启）
      // 注意：8080 会被本机 ERP(Frappe) 容器占用，联调期指 8090；ERP 不占 8080 时改回
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 第三方依赖分片：antd 全家桶与 React 生态各自独立 chunk，控制单文件体积并利于长效缓存
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('@ant-design/icons')) return 'vendor-icons'
          if (
            id.includes('@ant-design') ||
            id.includes('/antd/') ||
            id.includes('antd/es') ||
            /[\\/]rc-[a-z-]+[\\/]/.test(id) ||
            id.includes('dayjs')
          ) {
            return 'vendor-antd'
          }
          if (
            id.includes('react-markdown') ||
            id.includes('remark-gfm') ||
            /[\\/](mdast|micromark|unified|hast|unist|remark)[\\/-]/.test(id)
          ) {
            return 'vendor-markdown'
          }
          if (
            /[\\/](react|react-dom)[\\/]/.test(id) ||
            id.includes('react-router') ||
            id.includes('zustand') ||
            id.includes('scheduler')
          ) {
            return 'vendor-react'
          }
          return undefined
        },
      },
    },
    // antd 为单体库（tree-shaken 后仍 ~1MB），上限放宽；自有代码控制在 36KB
    chunkSizeWarningLimit: 1200,
  },
})
