import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
// Inter 字体本地引入（400/500/600/700，避免运行时网络拉取）
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
