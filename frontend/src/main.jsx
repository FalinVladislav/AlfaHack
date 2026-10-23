import React from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from 'antd'
import ruRU from 'antd/locale/ru_RU'
import App from './App.jsx'
import 'antd/dist/reset.css'
import './style.css'
import './components/applications/workflow.css'
import './pages/staff/staff.css'

const theme = {
  token: {
    colorPrimary: '#ef3124',
    colorInfo: '#ef3124',
    colorText: '#171717',
    colorTextSecondary: '#737373',
    colorBgLayout: '#f3f4f6',
    colorBorder: '#e5e7eb',
    borderRadius: 10,
    borderRadiusLG: 18,
    controlHeight: 44,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  },
  components: {
    Button: {
      borderRadius: 8,
      fontWeight: 600,
    },
    Input: {
      activeBorderColor: '#ef3124',
      hoverBorderColor: '#ff5a4f',
    },
    Select: {
      activeBorderColor: '#ef3124',
      hoverBorderColor: '#ff5a4f',
      optionSelectedBg: '#fff1f0',
    },
    Slider: {
      trackBg: '#ef3124',
      trackHoverBg: '#c51f17',
      handleColor: '#ef3124',
      handleActiveColor: '#c51f17',
      railBg: '#d9d9d9',
      railHoverBg: '#bfbfbf',
    },
  },
}

createRoot(document.getElementById('root')).render(
  <ConfigProvider locale={ruRU} theme={theme} wave={{ disabled: true }}>
    <App />
  </ConfigProvider>,
)
