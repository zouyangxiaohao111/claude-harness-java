import React, { useState } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { LaunchGate } from '@/components/startup/LaunchGate'
import { StandalonePreviewView } from '@/components/standalone/StandalonePreviewView'
import { isStandalonePreviewRoute } from '@/utils/standalonePreview'
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

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
)
