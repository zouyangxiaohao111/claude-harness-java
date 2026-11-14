import { Fragment, useEffect, useState } from 'react'
import type { JSX } from 'react'
import { useEscapeKey } from '@/hooks'
import { KB_SETTINGS_DEFAULT_KEY, KB_SETTINGS_NAV } from './kbSettingsTypes'
import { KbSettingsAccount } from './KbSettingsAccount'
import { KbSettingsWorkspace } from './KbSettingsWorkspace'
import { KbSettingsDataExt } from './KbSettingsDataExt'
import { KbSettingsPlaceholder } from './KbSettingsPlaceholders'
import { IngestConcurrencySection } from './KbSettingsModal'

/**
 * 知识库场景 · 「全部设置」弹窗外壳（左 6 组导航 + 右面板）。
 *
 * <h2>这是什么 / 与谁区分</h2>
 * 本组件是**场景级**设置（一个窗口里放全部设置项），入参只有 `open` / `onClose` / `initialKey`。
 * ⛔ 同目录的 `KbSettingsModal.tsx` 是**单个知识库**的设置（入参 `base: KnowledgeBase`，
 * 只改那一个库的基本信息 / 分块 / 模型），两者不是一回事，互不 import。
 *
 * <h2>结构真源</h2>
 * 逐层照源站 `D:/tmp/WeKnora/frontend/src/components/SettingsModalShell.vue`：
 * <pre>
 * SettingsModalShell.vue:4    .settings-modal-shell.settings-overlay（@click.self="emit('close')"）
 * SettingsModalShell.vue:6    .settings-modal（role=dialog aria-modal aria-label=title）
 * SettingsModalShell.vue:11   button.close-btn —— .settings-modal 的**直接子元素**（绝对定位）
 * SettingsModalShell.vue:17   .settings-container
 * SettingsModalShell.vue:18   aside.settings-sidebar
 * SettingsModalShell.vue:19-22    .sidebar-header > h2.sidebar-title
 * SettingsModalShell.vue:23   nav.settings-nav
 * SettingsModalShell.vue:26   .nav-group-title（分组标题，**直接子元素**，不是包一层 div）
 * SettingsModalShell.vue:27   .nav-item（源站用 active 类表达选中）
 * SettingsModalShell.vue:41-44    .settings-content > .settings-body > 插槽内容
 * </pre>
 * 样式真源：`styles/kb/kb-shared.css:658-695`（`.kb-scope .settings-modal-shell ...` 全套）。
 * 所有规则都挂在 `.kb-scope` 下 ⇒ 外层必须有 `.kb-scope` 包裹，否则一条都不生效。
 *
 * <h2>与派单模板的两处有意偏离（都按 CSS 实测选，理由见各处以 ⚠️ 标注）</h2>
 * <ol>
 *   <li>分组标题用 `nav-group-title`，⛔ 不是 `settings-nav-section`；</li>
 *   <li>选中项用 `active`，⛔ 不是 `is-active`。</li>
 * </ol>
 */

/**
 * 按 navKey 归属的组，把它路由到对应面板组件。
 *
 * <p>⚠️ 路由表里**没有「模型」组**：用户 2026-11-08 明确本仓有独立模型管理页，
 * 所以 `KB_SETTINGS_NAV` 有意不列该组，这里也就⛔ 不要「补上」。
 *
 * @param navKey 导航项唯一 key（`kbSettingsTypes.ts` 的 KB_SETTINGS_NAV 里定义）
 */
function renderPanel(navKey: string): JSX.Element {
  const group = KB_SETTINGS_NAV.find((g) => g.items.some((i) => i.key === navKey))

  // 找不到归属（导航与路由表不同步）：交给占位面板的兜底分支，⛔ 不返回 null（否则白屏）
  if (!group) return <KbSettingsPlaceholder groupKey="" navKey={navKey} />

  switch (group.key) {
    case 'account':
      return <KbSettingsAccount navKey={navKey} />
    case 'workspace':
      return <KbSettingsWorkspace navKey={navKey} />
    case 'data_extensions':
      return <KbSettingsDataExt navKey={navKey} />
    case 'system_administration':
      // ⭐「任务队列」= 入库流水线六池并发的配置入口（用户 2026-11-14 指定放这里）。
      //   其余系统管理项后端没有能力 ⇒ 仍走下面的占位面板。
      if (navKey === 'runtime-queues') {
        return (
          <div className="section">
            <div className="section-header" style={{ margin: '0 0 16px' }}>
              <h2>任务队列</h2>
              <p className="section-description">
                知识库文档入库流水线各阶段的工作线程数。该配置是全局的，对所有知识库生效。
              </p>
            </div>
            <IngestConcurrencySection showToast={() => {}} />
          </div>
        )
      }
      return <KbSettingsPlaceholder groupKey={group.key} navKey={navKey} />
    // 发布集成 / 系统管理 / 平台：后端没有对应能力 ⇒ 统一「暂未开发」占位面板
    // （组 key 原样传下去，占位面板自己按两级 key 找标题）
    default:
      return <KbSettingsPlaceholder groupKey={group.key} navKey={navKey} />
  }
}

export interface KbSceneSettingsModalProps {
  open: boolean
  onClose: () => void
  /** 初始选中的 navKey，缺省 KB_SETTINGS_DEFAULT_KEY */
  initialKey?: string
}

/** 知识库场景的「全部设置」弹窗 · 左导航 + 右面板。 */
export function KbSceneSettingsModal({
  open,
  onClose,
  initialKey,
}: KbSceneSettingsModalProps): JSX.Element | null {
  const [activeKey, setActiveKey] = useState<string>(initialKey ?? KB_SETTINGS_DEFAULT_KEY)

  // Esc 关闭：复用仓库既有 hook（`src/hooks.ts:71`，App.tsx 里 14 处同款用法），
  // ⛔ 不自造 keydown 监听。⚠️ 必须在 `if (!open) return null` 之前调用（Hooks 规则：
  // 早退会让 hook 调用数随 open 变化，React 会直接抛错）。
  useEscapeKey(open, onClose)

  /**
   * 每次「重新打开」都把选中项复位到 initialKey / 默认项。
   *
   * <p>为什么必须做：组件在关闭时并不卸载（父组件一直挂着它、只切 `open`），
   * 若不复位，上次停在「审计日志」下次打开还停在「审计日志」，与「刚打开设置窗」的
   * 预期不符（源站 `Settings.vue` 的 `currentSection` 由父级在打开前重置）。
   */
  useEffect(() => {
    if (!open) return
    setActiveKey(initialKey ?? KB_SETTINGS_DEFAULT_KEY)
  }, [open, initialKey])

  if (!open) return null

  return (
    /* `.kb-scope` 必须包在最外层：`kb-shared.css:658-695` 的每条规则都是
       `.kb-scope .settings-modal-shell ...`，少这层包裹整窗会没样式。
       ⚠️ 定位结论：`.kb-scope` 自身只定义 CSS 变量（`kb-scope.css:7-74`，全部是 --xxx: 声明），
       没有任何 position / transform / filter / contain 规则；祖先链
       `.kb-scene-overlay`（globals.css:16981，只有 position:fixed）/
       `.kb-scene-panel`（16988，overflow:hidden 但不含 transform）也都不构成
       fixed 的包含块 ⇒ `position:fixed` 仍然相对视口，**不需要**把 shell 的定位内联，
       直接用源站的 `.settings-modal-shell.settings-overlay` 类即可。 */
    <div className="kb-scope">
      {/* 源站 SettingsModalShell.vue:4 —— `@click.self` 等价于 `e.target === e.currentTarget`；
          ⛔ 不加 stopPropagation（仓库内 KbCreateModal.tsx:407 / KbSettingsModal.tsx:153 同款）。
          zIndex 1200：高于 `.kb-scene-overlay` 的 45，低于助手浮窗 2400。 */}
      <div
        className="settings-modal-shell settings-overlay"
        style={{ zIndex: 1200 }}
        onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
      >
        {/* 源站 SettingsModalShell.vue:6 —— role/aria 是源站就有的，不是本仓新增 */}
        <div className="settings-modal" role="dialog" aria-modal="true" aria-label="设置">
          {/* 源站 SettingsModalShell.vue:11 —— close-btn 是 .settings-modal 的直接子元素（绝对定位） */}
          <button type="button" className="close-btn" onClick={onClose} title="关闭（Esc）" aria-label="关闭">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M15 5L5 15M5 5L15 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </button>

          <div className="settings-container">
            <aside className="settings-sidebar">
              {/* 源站 SettingsModalShell.vue:19-22 —— 侧栏抬头（标题由消费方以 title 传入，
                  Settings.vue:2 传的是 $t('general.settings') = 设置）。 */}
              <div className="sidebar-header">
                <h2 className="sidebar-title">设置</h2>
              </div>
              <nav className="settings-nav">
                {KB_SETTINGS_NAV.map((group) => (
                  /* ⚠️ 用 Fragment 而非包一层 div：`kb-shared.css:93-94` 是
                     `.settings-nav > .nav-group-title:first-child` / `:not(:first-child)`
                     这类**直接子元素**选择器；包 div 会让分组标题退成孙节点，
                     首组标题 2px、后续标题 8px 的上内边距全部失效。
                     （KbCreateModal.tsx:425 同样用 Fragment 的理由） */
                  <Fragment key={group.key}>
                    {/* ⚠️ 用 `nav-group-title` 而不是派单模板里的 `settings-nav-section`：
                         后者只有 globals.css:4854（客户端全局设置的自造类，硬编码 #999 / 20px 外边距），
                         不是源站类名；源站与 kb 样式表用的都是 `nav-group-title`
                         （SettingsModalShell.vue:26、kb-shared.css:672）。 */}
                    <div className="nav-group-title">{group.label}</div>
                    {group.items.map((item) => {
                      const active = activeKey === item.key
                      return (
                        /* ⚠️ 用 `active` 而不是派单模板里的 `is-active`：
                           `grep -rn "nav-item.is-active" src/styles/` = 0 命中，
                           而 `.nav-item.active` 在 kb-shared.css:97 / 676 有完整规则；
                           写 is-active 的话选中态**完全没有视觉反馈**。 */
                        <div
                          key={item.key}
                          className={`nav-item${active ? ' active' : ''}`}
                          role="button"
                          tabIndex={0}
                          aria-current={active ? 'page' : undefined}
                          onClick={() => setActiveKey(item.key)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault()
                              setActiveKey(item.key)
                            }
                          }}
                        >
                          {/* 源站 SettingsModalShell.vue:31 是 `<t-icon :name="item.icon">`（tdesign 图标 sprite）。
                              本仓没有 tdesign 运行时、也没有这批图标的 symbol 定义
                              （`grep -rn 'id="t-icon-' src/` 只有 IconSprite.tsx 的 8 个文件类型图标），
                              `<use>` 取不到东西 ⇒ 图标位渲染为空白方框。
                              ⚠️ 显式给 width/height：缺 symbol 的 svg 会按 CSS 默认尺寸撑成 300×150
                              把整行顶高（KbCreateModal.tsx:819-823 实测结论）。
                              ⛔ 不自造 SVG path 伪装成源站图标。 */}
                          <svg
                            className={`t-icon t-icon-${item.icon} nav-icon`}
                            width="16"
                            height="16"
                            viewBox="0 0 24 24"
                            aria-hidden="true"
                          >
                            <use href={`#t-icon-${item.icon}`} />
                          </svg>
                          <span className="nav-label">{item.label}</span>
                        </div>
                      )
                    })}
                  </Fragment>
                ))}
              </nav>
            </aside>

            {/* 源站 SettingsModalShell.vue:41-44 —— 三层缺一不可，少一层就没有内边距
                （`.content-wrapper` 的 padding 在 kb-10.css:601） */}
            <div className="settings-content">
              <div className="settings-body">
                <div className="content-wrapper">{renderPanel(activeKey)}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
