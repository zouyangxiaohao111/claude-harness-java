/**
 * 知识库「独立窗口」（用户 2026-11-09 裁定：面板要能拖到另一个屏幕）。
 *
 * <h2>为什么需要独立窗口，而不是把面板拖出主窗口</h2>
 * NexusAI 是 Tauri 桌面应用（`src-tauri/tauri.conf.json`），主窗口是一个 WebView。
 * 页面里的元素再怎么做 `position:fixed` / `transform`，**物理上都只能活在主窗口的
 * WebView 画布内** —— 移出窗口边界只会被裁掉，不可能出现在另一块显示器上。
 * 唯一的做法是**新开一个 Tauri 窗口**（独立的 WebView），让系统去管它的位置。
 *
 * <h2>状态同步：为什么这个方案可行</h2>
 * 实测 `KnowledgeBasePage` 及其子组件**不依赖 chat store / session / STOMP**，
 * 只依赖 `knowledgeApi`（HTTP）。所以独立窗口不需要重连推送、不需要同步登录态 ——
 * 它自己发 HTTP 就能工作。跨窗同步用 `localStorage` + `storage` 事件（同源，
 * Tauri 的多个窗口共享同一 origin）。
 *
 * <h2>与 `standalonePreview.ts` 的关系</h2>
 * 同一套模式（localStorage 描述符 + `WebviewWindow` 复用 + hash 路由分流），
 * 但**刻意不复用那份代码**：预览窗口的载荷是「一个文件描述符」，
 * 知识库需要的是「当前选中哪个库 / 哪个 tab」，两者结构没有共性，
 * 硬塞进同一个 payload 只会让两边都难改。
 */

/** 独立知识库窗口路由（main.tsx 据此渲染独立视图）。 */
const KB_WINDOW_ROUTE = '#/knowledge-window'
/** Tauri 窗口 label（getByLabel 复用它，避免开出多个）。 */
const KB_WINDOW_LABEL = 'knowledge-window'
/** 浏览器兜底时的 tab 名（同名 window.open 会复用）。 */
const BROWSER_TAB_NAME = 'nexusai-knowledge-window'
/** 跨窗同步描述符的 localStorage 键。 */
export const KB_WINDOW_LS_KEY = 'nexusai.kb.window'

/**
 * 独立窗口要恢复的上下文。
 *
 * <p>只存「看到哪」这类**导航状态**，⛔ 不存任何业务数据（库列表、文档列表都自己拉）——
 * 存了就会两边不一致，且要处理失效。
 */
export interface KbWindowState {
  v: 1
  /** 场景：知识库 / 共享空间（对应 `kbScene`） */
  scene: 'knowledge' | 'shared'
  /** 当前打开的库 id（null = 停在列表页） */
  baseId: string | null
  /** 详情页 tab（null = 不在详情页） */
  tab: 'docs' | 'wiki' | 'graph' | 'gallery' | 'qa' | null
}

/** 是否 Tauri 运行时（与 standalonePreview 同一判定方式）。 */
function isTauri(): boolean {
  return typeof window !== 'undefined'
    && !!(window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
}

/** 是否为「独立知识库窗口」路由。 */
export function isKbWindowRoute(): boolean {
  try {
    return window.location.hash.startsWith(KB_WINDOW_ROUTE)
  } catch {
    return false
  }
}

/** 读独立窗口应恢复的上下文；没有（首次打开 / 已清空）返回 null。 */
export function readKbWindowState(): KbWindowState | null {
  try {
    const raw = window.localStorage.getItem(KB_WINDOW_LS_KEY)
    if (!raw) return null
    const p = JSON.parse(raw) as KbWindowState
    if (!p || p.v !== 1) return null
    return p
  } catch {
    return null
  }
}

/** 写上下文（主窗口在打开前、以及状态变化时调用）。 */
export function writeKbWindowState(s: Omit<KbWindowState, 'v'>): void {
  try {
    window.localStorage.setItem(KB_WINDOW_LS_KEY, JSON.stringify({ ...s, v: 1 }))
  } catch {
    // quota 只会因超大字符串触发，这里载荷是几十字节 ⇒ 失败即可忽略（窗口会退回列表页）
  }
}

function kbWindowUrl(): string {
  const base = window.location.href.split('#')[0]
  return `${base}${KB_WINDOW_ROUTE}`
}

/**
 * 打开（或聚焦）知识库独立窗口。
 *
 * @param state 当前上下文，新窗口据此恢复到同一位置
 * @return true = 窗口已开/已聚焦；false = 没打开（弹窗被拦等）——
 *         调用方据此决定**要不要关掉主窗口里的面板**。
 *         ⚠️ 先开窗成功、再关面板：反过来会让「弹窗被拦」变成「点了没反应，面板也没了」。
 */
export async function openKnowledgeWindow(state: Omit<KbWindowState, 'v'>): Promise<boolean> {
  writeKbWindowState(state)

  if (isTauri()) {
    try {
      const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
      const existing = await WebviewWindow.getByLabel(KB_WINDOW_LABEL)
      if (existing) {
        // 已开着：只把它提到前面，不重建（重建会丢掉窗口位置，用户可能正摆在第二块屏上）
        try { await existing.setFocus() } catch { /* 正在关闭中 */ }
        return true
      }
      new WebviewWindow(KB_WINDOW_LABEL, {
        title: '知识库',
        url: kbWindowUrl(),
        width: 1280,
        height: 860,
        minWidth: 720,
        minHeight: 480,
        // ⚠️ 不指定 center：让系统按上次位置/默认位置摆，用户拖到副屏后重开不会跳回主屏
        focus: true,
        // ⚠️ 2026-11-13 改判：原先 `decorations: true`（2026-11-09 裁定「必须能原生拖拽/关闭」），
        //   但系统标题栏的底色不受页面 CSS 控制，用户在独立窗口里看到顶上一整条异色，
        //   反馈「看着很别扭」。现改为无边框 + 前端自绘标题栏
        //   （`KbWindowTitleBar`：同样有拖拽 / 最小化 / 最大化 / 关闭，只是颜色跟内容一致）。
        decorations: false,
        // 无边框 + 不透明：页面自己铺满（`.kb-window-root` 已是 `background:#fff`）
        transparent: false,
      })
      return true
    } catch {
      // 缺 capability / 非桌面环境：落到下面的浏览器分支
    }
  }

  // 浏览器（dev / 非 Tauri）：同名 window.open 复用已开的独立页
  const win = window.open(kbWindowUrl(), BROWSER_TAB_NAME)
  if (!win) {
    // 弹窗被拦截（自动化/无痕/拦截器）⇒ 如实返回失败，让调用方**别关面板**
    return false
  }
  try { win.focus() } catch { /* ignore */ }
  return true
}

/** 知识库独立窗口可否用（当前环境是否支持）。 */
export function canOpenKnowledgeWindow(): boolean {
  return isTauri() || typeof window !== 'undefined'
}
