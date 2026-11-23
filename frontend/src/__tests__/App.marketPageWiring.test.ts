import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 市场整页接线（源码级）。
 *
 * <p><b>WHY（用户 2026-11-23 终稿）</b>：「连接器 技能市场 整体页面不要弹窗了 直接显示
 * 就和 IM 频道一样」。入口必须切到 `workspacePage='market'` 的**主区换页**，
 * 不能退回弹窗或 toast 占位；对话面由 CSS 隐藏（不卸载，保住 Composer 草稿）。
 *
 * <p>为什么是源码断言：App.tsx 是巨型组件，渲染成本高；被测的是「状态分支 + 一条 CSS
 * + 入口回调」—— 失效方式全是静默的（点了还弹窗 / 页面与对话重叠）。
 */
const APP_TSX = fileURLToPath(new URL('../App.tsx', import.meta.url))
const CSS = fileURLToPath(new URL('../styles/globals.css', import.meta.url))
const SESSION_LIST_TSX = fileURLToPath(new URL('../components/left/SessionList.tsx', import.meta.url))
const KB_PANE_TSX = fileURLToPath(new URL('../components/knowledge/KbNewChatPane.tsx', import.meta.url))
const MARKET_PAGE_TSX = fileURLToPath(new URL('../components/market/MarketPage.tsx', import.meta.url))

function src(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

describe('市场整页接入（源码级）', () => {
  it('workspacePage 联合类型含 market，并派生出 showMarketPage', () => {
    const app = src(APP_TSX)
    expect(app).toMatch(/useState<'chat' \| 'schedules' \| 'knowledge' \| 'im' \| 'market'>/)
    expect(app).toContain("const showMarketPage = workspacePage === 'market'")
  })

  it('data-page 绑定 market，且 .center 内真的渲染 <MarketPage>', () => {
    const app = src(APP_TSX)
    expect(app).toContain("data-page={showMarketPage ? 'market'")
    const center = app.slice(app.indexOf('<div className="center">'), app.indexOf('<WorkspaceHeader'))
    expect(center).toContain('<MarketPage')
  })

  it('CSS 用 display:none 隐藏对话面（不是条件卸载）', () => {
    const css = src(CSS)
    expect(css).toContain('.app.workspace-surface[data-page="market"] .center > :not(.market-page) { display: none; }')
  })

  it('⭐ 主窗口不再有 showMarket 弹窗链路（KB 独立窗除外）', () => {
    const app = src(APP_TSX)
    // ⚠️ 不能用 includes('showMarket')：showMarketPage 里也含这串
    expect(app).not.toContain('setShowMarket')
    expect(app).not.toContain('useEscapeKey(showMarket')
    expect(app).not.toContain('<SkillMarketModal')
    // KB 独立窗必须保留弹窗（主窗口的整页在它那里不存在）
    expect(src(KB_PANE_TSX)).toContain('<SkillMarketModal')
    expect(src(MARKET_PAGE_TSX)).toContain('<MarketBrowser')
  })

  it('左栏入口是真实换页（ws-nav-market），不是 toast/window.open', () => {
    const list = src(SESSION_LIST_TSX)
    expect(list).toContain('ws-nav-market')
    expect(list).not.toContain("toast('技能市场")
    const app = src(APP_TSX)
    expect(app).toMatch(/onOpenAgentMarket=\{\(\) => setWorkspacePage\('market'\)\}/)
  })

  it('三条退出路径：Esc 通用守卫 / 点会话 / 新建会话', () => {
    const app = src(APP_TSX)
    expect(app).toContain("if (workspacePage !== 'chat')")
    expect(app).toMatch(/const switchSession = useCallback[\s\S]{0,600}setWorkspacePage\('chat'\)/)
    expect(app).toMatch(/onCreateSession=\{\(\) => \{\s*setWorkspacePage\('chat'\)/)
  })
})
