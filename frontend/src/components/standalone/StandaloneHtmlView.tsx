/**
 * HTML 代码块「另起页面」的独立查看器（整屏，无聊天 UI）。
 * main.tsx 在 location.hash 命中 `#/standalone-html` 时渲染本组件而非整 App，
 * 因此它不订阅 STOMP/不打扰主会话——对话侧继续跑，本窗口独立存在。
 *
 * 内容经 sandbox iframe 运行（allow-scripts/modals/forms/popups，与右栏 RightPreview 一致），
 * 模型 HTML 不离开 iframe。更新联动：监听 `storage`（对话侧每次「运行」写入
 * nexusai.html.standalone）+ focus 回读 → 窗口开着就自动刷到最新。
 */
import { useEffect, useState } from 'react'
import { HTML_STANDALONE_LS_KEY, currentStandaloneHtml } from '@/utils/htmlStandalone'

export function StandaloneHtmlView() {
  const [html, setHtml] = useState(currentStandaloneHtml)

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === HTML_STANDALONE_LS_KEY && e.newValue !== null) setHtml(e.newValue)
    }
    const onFocus = () => setHtml(currentStandaloneHtml())
    window.addEventListener('storage', onStorage)
    window.addEventListener('focus', onFocus)
    return () => {
      window.removeEventListener('storage', onStorage)
      window.removeEventListener('focus', onFocus)
    }
  }, [])

  return (
    <div className="shtml">
      <div className="shtml-bar">
        <span className="shtml-title">HTML 运行预览</span>
        <span className="shtml-hint">对话里再次点击该代码块的「运行」会自动刷新本页</span>
        <span className="spacer" />
        <button
          type="button"
          className="shtml-reload"
          title="重新加载（回读最新内容）"
          onClick={() => setHtml(currentStandaloneHtml())}
        >
          ↻
        </button>
      </div>
      <iframe
        className="shtml-frame"
        sandbox="allow-scripts allow-modals allow-forms allow-popups"
        srcDoc={html}
        title="HTML 运行预览"
      />
    </div>
  )
}
