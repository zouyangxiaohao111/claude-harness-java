import { useState } from 'react'
import { KnowledgeBasePage, KbSceneNav } from '@/components/knowledge/KnowledgeBasePage'
import { KbNewChatPane } from '@/components/knowledge/KbNewChatPane'
import { KbSessionList } from '@/components/knowledge/KbSessionList'
import { KbWindowTitleBar } from '@/components/knowledge/KbWindowTitleBar'
import { readKbWindowState } from '@/utils/kbWindow'

/**
 * 知识库**独立窗口**视图（用户 2026-11-09：「面板要能拖到另一个屏幕」）。
 *
 * <h2>与主窗口里那个浮层的区别</h2>
 * 主窗口的浮层是 `.kb-scene-overlay > .kb-scene-panel`（可拖动、非模态）；
 * 这里**没有 overlay、没有面板外壳** —— 整个窗口就是知识库，所以直接铺满，
 * 不需要拖动条，也没有 ✕（关窗 = 关掉这个系统窗口）。
 *
 * <h2>为什么能不带 chat store</h2>
 * `KnowledgeBasePage` 只依赖 `knowledgeApi`（HTTP），不碰 session / STOMP
 * ⇒ 独立 WebView 里直接渲染它就能工作，无需任何状态同步脚手架。
 *
 * <h2>上下文恢复</h2>
 * 从 localStorage 的 `nexusai.kb.window` 读「上次停在哪个库 / 哪个 tab」。
 * 读不到就退回列表页（⛔ 不猜、不报错 —— 那是最合理的默认态）。
 */
export function KbWindowView() {
  // ⚠️ useState 惰性初始化：只在挂载时读一次（中途变化由本窗自己维护，不该被 localStorage 拽回去）
  const [initial] = useState(() => readKbWindowState())
  const [scene, setScene] = useState<'knowledge' | 'shared'>(initial?.scene ?? 'knowledge')
  /** 主区视图：知识库内容 / 新对话（本窗内换页；对话走自建发送通道，见 KbNewChatPane） */
  const [view, setView] = useState<'knowledge' | 'chat'>('knowledge')
  /** 左栏历史对话里点开的会话（null = 尚未选，交给 KbNewChatPane 自己复用/新建） */
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  /** 「新对话」信号：自增 ⇒ 弹窗内强制进入空白会话（有则复用、无则新建） */
  const [newChatToken, setNewChatToken] = useState(0)

  return (
    <div className="kb-window-root">
      {/* 自绘标题栏 —— 系统标题栏（`decorations:true`）在 Windows 上会给整条染上
          不受页面 CSS 控制的底色，用户 2026-11-13 反馈「最顶上还有颜色、很别扭」。
          这里用无边框 + 自绘条，颜色与内容一致；拖拽/最小化/最大化/关闭都在组件内补齐。 */}
      <KbWindowTitleBar />
      <div className="kb-scene-panel kb-scope kb-window-panel">
        <KbSceneNav
          activeKey={view === 'chat' ? 'chat' : (scene === 'shared' ? 'shared' : 'knowledge')}
          // ⭐ 独立窗口里的「离开场景」类入口：其余项在本窗没有意义（会打不开或跳到主窗才有的页面）
          //   ⇒ 只保留「新对话」「返回知识库列表」与「共享空间」，其余不传（KbSceneNav 里按钮会无 handler 但不会崩）。
          onNewChat={() => { setNewChatToken((t) => t + 1); setView('chat') }}
          onOpenSharedSpace={() => { setView('knowledge'); setScene('shared') }}
          onBackToList={() => { setView('knowledge'); setScene('knowledge') }}
          sessionsSlot={(
            <KbSessionList
              activeSessionId={activeSessionId}
              onPick={(id) => { setActiveSessionId(id); setView('chat') }}
              onDeleted={() => { setActiveSessionId(null); setNewChatToken((t) => t + 1) }}
            />
          )}
        />
        <div className="kb-scene-main">
          {view === 'chat' ? (
            <KbNewChatPane
              initialSessionId={activeSessionId}
              onSessionChange={setActiveSessionId}
              newChatToken={newChatToken}
              showToast={() => { /* 独立窗无全局 toast 容器，静默 */ }}
            />
          ) : (
            <KnowledgeBasePage showToast={() => { /* 独立窗无全局 toast 容器，静默 */ }} scene={scene} />
          )}
        </div>
      </div>
    </div>
  )
}
