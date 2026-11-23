import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider, App as AntApp, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import App from './App'
import './index.css'
import { registerSW } from 'virtual:pwa-register'

// PWA：新版本检测到即更新（skipWaiting + clientsClaim），下次刷新生效
registerSW({ immediate: true })

dayjs.locale('zh-cn')

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#39e6ff',
          colorInfo: '#39e6ff',
          colorSuccess: '#2bffa8',
          colorWarning: '#ffb340',
          colorError: '#ff4d6a',
          colorBgBase: '#05080f',
          colorTextBase: '#d9f4ff',
          borderRadius: 0,
          fontFamily:
            "'IBM Plex Sans', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
          fontFamilyCode: "'IBM Plex Mono', 'SF Mono', Consolas, monospace",
        },
        components: {
          Layout: {
            headerBg: 'rgba(8,13,23,0.92)',
            siderBg: 'rgba(8,13,23,0.95)',
            bodyBg: 'transparent',
          },
          Menu: {
            itemBg: 'transparent',
            itemColor: '#7e9bbd',
            itemSelectedBg: 'rgba(57,230,255,0.12)',
            itemSelectedColor: '#39e6ff',
            itemHoverColor: '#d9f4ff',
          },
          Card: {
            colorBgContainer: 'rgba(15,24,41,0.9)',
            colorBorderSecondary: 'rgba(57,230,255,0.22)',
          },
          Table: {
            colorBgContainer: 'transparent',
            headerBg: '#0f1829',
            headerColor: '#7e9bbd',
            borderColor: 'rgba(57,230,255,0.1)',
          },
          Modal: {
            contentBg: '#0f1829',
            headerBg: '#0f1829',
          },
          Drawer: {
            colorBgElevated: '#0f1829',
          },
          Input: {
            colorBgContainer: '#0b1220',
            colorBorder: 'rgba(57,230,255,0.22)',
          },
          Select: {
            colorBgContainer: '#0b1220',
            colorBgElevated: '#152238',
          },
          Tabs: {
            inkBarColor: '#39e6ff',
            itemSelectedColor: '#39e6ff',
          },
        },
      }}
    >
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
