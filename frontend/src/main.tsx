// [OBS1] 必须放在**最前**（所有业务 import 之前）：该模块以顶层副作用安装前端错误捕获
// （console.error/warn、未捕获异常、unhandledrejection）与 10s 心跳上报，
// 越早安装越能抓住启动期异常；仅 Tauri 环境生效，浏览器 dev 自动跳过。
import './utils/frontLog'
import React, { useState } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { ErrorBoundary } from '@/components/common/ErrorBoundary'
import { LaunchGate } from '@/components/startup/LaunchGate'
import { StandalonePreviewView } from '@/components/standalone/StandalonePreviewView'
import { isStandalonePreviewRoute } from '@/utils/standalonePreview'
// [OBS2] 错误上报出口：frontLog 以顶层副作用装好了通道，这里取它的上报函数交给 React 19 的错误回调。
// ⛔ 必须放在 `import './utils/frontLog'` 之后（那一条负责安装通道）。
import { reportFrontendError } from '@/utils/frontLog'
// Inter 字体本地引入（400/500/600/700，避免运行时网络拉取）
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'

/** 顶层分流：独立预览查看器路由渲染极简视图（跳过 LaunchGate/后端就绪门），其余走完整 App。 */
function Root() {
  const [standalone] = useState(isStandalonePreviewRoute)
  return standalone ? (
    <StandalonePreviewView />
  ) : (
    <LaunchGate>
      <App />
    </LaunchGate>
  )
}

// [OBS2] React 19 的 createRoot 错误回调 —— 三条都送进 frontLog：
//   onUncaughtError：ErrorBoundary 之外的未捕获错误（React 会卸载整棵树）；
//   onCaughtError：被 ErrorBoundary 捕获的错误（与 ErrorBoundary.componentDidCatch 双报，便于对照）；
//   onRecoverableError：可恢复错误（hydration 不匹配等）—— 平时无害，但「整屏不更新」时要能查到它。
// ⛔ 每个回调内部不得抛出（抛出会变成 React 自己的错误循环）；reportFrontendError 内部全 catch。
const root = ReactDOM.createRoot(document.getElementById('root')!, {
  onUncaughtError: (error, errorInfo) => {
    reportFrontendError('react.onUncaughtError', error, errorInfo.componentStack ?? '(无 componentStack)')
  },
  onCaughtError: (error, errorInfo) => {
    reportFrontendError('react.onCaughtError', error, errorInfo.componentStack ?? '(无 componentStack)')
  },
  onRecoverableError: (error, errorInfo) => {
    reportFrontendError('react.onRecoverableError', error, errorInfo.componentStack ?? '(无 componentStack)')
  },
})

root.render(
  <React.StrictMode>
    {/* [OBS2] 顶层边界：兜住 Root 整棵子树的渲染错误 —— React 18+ 未捕获的渲染错误会把整棵树卸载成
        白屏，这里至少留下兜底 UI + 一条带 componentStack 的上报。⛔ 防御性兜底，不是本批根因修复。 */}
    <ErrorBoundary
      tag="react.ErrorBoundary.Root"
      title="界面显示出错了"
      hint="页面在渲染时遇到问题。点「重试」重新渲染；若反复出现，请重新打开应用。"
    >
      <Root />
    </ErrorBoundary>
  </React.StrictMode>,
)
