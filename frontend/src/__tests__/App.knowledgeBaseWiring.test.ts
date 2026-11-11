import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 知识库入口必须是**真实页面**，不能退回 toast 占位（**源码级**断言）。
 *
 * <p><b>WHY（用户原话）</b>：「还剩下什么 知识库 点击还是显示建设中 前端没有复刻啊」。
 * 旧实现 `onOpenKnowledgeBase={() => showToast('知识库建设中，敬请期待', 'info')}` ——
 * 点下去只有一句提示，用户看不出这是「没做」还是「坏了」。
 *
 * <p><b>为什么是源码断言而不是渲染测试</b>：App.tsx 是 3000 行巨型组件，jsdom 挂载要 mock
 * settings / chat socket / 会话列表 / LaunchGate；而被测逻辑只是「一个 state 分支 + 一次三元」。
 * 失效方式又是**静默**的：把入口改回 toast、或漏掉 `data-page="knowledge"`，
 * 编译通过、其余测试全绿，只有用户点下去发现页面不出现。
 * 本仓已有同款先例：`App.traceToggleWiring.test.ts`。
 */

const APP_TSX = fileURLToPath(new URL('../App.tsx', import.meta.url))
const PAGE_TSX = fileURLToPath(new URL('../components/knowledge/KnowledgeBasePage.tsx', import.meta.url))
/** 文档区（2026-11-06 拆出：文件夹树/筛选/标签/网格列表/批量全在这里） */
const DOC_PANEL_TSX = fileURLToPath(new URL('../components/knowledge/KnowledgeDocPanel.tsx', import.meta.url))
const CSS = fileURLToPath(new URL('../styles/globals.css', import.meta.url))
/** 知识库独立窗口（Tauri 系统窗口，可拖到别的屏幕） */
const KB_WINDOW_UTIL = fileURLToPath(new URL('../utils/kbWindow.ts', import.meta.url))

function src(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

describe('知识库入口接线（源码级）', () => {
  it('⭐ 左栏入口必须打开知识库浮层，不能再是 toast 占位', () => {
    const app = src(APP_TSX)
    const line = app
      .split('\n')
      .find((l) => l.includes('onOpenKnowledgeBase'))
    expect(line, '找不到 onOpenKnowledgeBase（入口被删？）').toBeTruthy()
    // 2026-11-06：知识库是**独立场景**，⛔ 不换页（原先用浮层承载）。
    // 2026-11-09 用户改判：「点开默认就是独立窗口」⇒ 入口改为开 Tauri 系统窗口，
    //   浮层只作为「独立窗开不出来」的兜底。所以这里断言的是「不再是 toast 占位」，
    //   具体的入口形态由下面那条「默认开独立窗口」用例守。
    expect(line, '入口退回 toast 占位 = 用户报过的缺陷复发').not.toContain('建设中')
  })

  it('⭐⭐ 知识库必须是浮层，⛔ 不能替换主区（否则用户看不到主对话）', () => {
    const app = src(APP_TSX)
    // 独立 state 承载
    expect(app).toMatch(/const \[showKnowledge, setShowKnowledge\] = useState\(false\)/)
    // 浮层容器 + 面板
    expect(app).toContain('kb-scene-overlay')
    expect(app).toContain('kb-scene-panel')
    // 面板里真的渲染页面，并传了关闭回调
    // 面板内先渲染场景侧栏（KbSceneNav）再渲染页面 ⇒ 必须两者都在，且顺序如此
    // ⚠️ 窗口别卡太死：2026-11-09 在 KbSceneNav 之前插了拖动把手 + ✕ 按钮，
    //    原来 2000 字符窗口就不够了（这不是结构退化，只是多了两个控件）。
    expect(app).toMatch(/kb-scene-panel[\s\S]{0,4000}<KnowledgeBasePage/)
    expect(app, '场景侧栏缺失 ⇒ 弹窗里没有设计稿那 7 个菜单项').toContain('<KbSceneNav')
    expect(app).toContain('onRequestClose={() => setShowKnowledge(false)}')
  })

  it('⭐ 主区不再因知识库换页（data-page 只留 chat / schedules）', () => {
    const app = src(APP_TSX)
    const line = app
      .split('\n')
      .find((l) => /^\s*data-page=\{/.test(l))
    expect(line, '找不到 data-page 属性绑定').toBeTruthy()
    expect(line, 'data-page 还带 knowledge ⇒ 说明仍是换页形态').not.toContain("'knowledge'")
    // 主区不得再渲染 KnowledgeBasePage（只允许在浮层里）
    const centerBlock = app.slice(app.indexOf('<div className="center">'), app.indexOf('<WorkspaceHeader'))
    expect(centerBlock).not.toContain('<KnowledgeBasePage')
  })

  it('CSS 有浮层容器样式', () => {
    const css = src(CSS)
    expect(css).toContain('.kb-scene-overlay')
    expect(css).toContain('.kb-scene-panel')
  })
})

/**
 * 非模态可拖动浮层（用户 2026-11-09 裁定）。
 *
 * <p><b>WHY（用户原话）</b>：「知识库弹窗应该用户主动关闭才关闭 并且它不影响我们输入
 * 对话其他住会话的」+「保留弹窗但是能够移动」+「只留 ✕（Esc 也不响应）」。
 *
 * <p>改之前是**全屏黑遮罩 + aria-modal**：主对话既看不见也点不到（遮罩 `inset:0`
 * 吃掉所有点击），用户想「一边聊天一边看知识库」做不到。
 *
 * <p><b>为什么是源码断言</b>：同上一组 —— App.tsx 是巨型组件，jsdom 挂载代价过高，
 * 而被测的是「几个属性有没有写对」。失效方式全是静默的：`pointer-events` 写错
 * ⇒ 点击被吃但界面看起来完全一样；`aria-modal` 留着 ⇒ 读屏软件把主对话标成不可用，
 * 肉眼永远发现不了。
 */
describe('知识库浮层是「非模态 + 可拖动 + 只留 ✕」（2026-11-09 用户裁定）', () => {
  it('⭐ 不能再点遮罩关闭（唯一关闭入口 = ✕）', () => {
    const app = src(APP_TSX)
    // 旧的 `onClick={(e) => { if (e.target === e.currentTarget) setShowKnowledge(false) }}`
    // 是最容易手滑的一条（面板很大，边缘一点就没了）
    const overlayBlock = app.slice(
      app.indexOf('className="kb-scene-overlay"'),
      app.indexOf('className="kb-scene-panel')
    )
    // ⚠️ 判据必须是「这一段里有没有 real onClick」，不能靠切到 `>` 之前 ——
    //    `<div className="..." onClick=...>` 的 onClick 在 `>` **之前**，
    //    切到引号结束就把它切掉了（第一次写这条断言就是这么假绿的）。
    expect(overlayBlock, '遮罩上还挂着点击关闭 ⇒ 用户会手滑丢上下文')
      // ⚠️ 不能用 `[^>]*`：箭头函数 `{(e) => ...}` 里就有 `>`，正则会在这里停住 ⇒ 假绿。
      //    用 `[\s\S]*?` 跨行匹配，并要求「同一个 onClick 属性内」出现关闭调用。
      .not.toMatch(/onClick=\{[\s\S]{0,200}?setShowKnowledge\(false\)/)
    // ✕ 必须在
    expect(app).toContain('kb-scene-close')
    expect(app).toMatch(/kb-scene-close[\s\S]{0,400}setShowKnowledge\(false\)/)
  })

  it('⭐ Esc 不响应（面板里输入框按 Esc 是「取消输入」，不能连带关场景）', () => {
    const app = src(APP_TSX)
    // 允许出现在注释里，但**不能有真实的 hook 调用**
    const liveCalls = app
      .split('\n')
      .filter((l) => l.includes('useEscapeKey(showKnowledge'))
    expect(liveCalls, '还挂着 useEscapeKey(showKnowledge, ...) ⇒ 输入框里按 Esc 会关掉整个场景')
      .toHaveLength(0)
  })

  it('⭐ 非模态：不能写 aria-modal（写了就会误导读屏软件）', () => {
    const app = src(APP_TSX)
    // ⚠️ 不能在源码里找 `aria-modal` 字样：注释里为了说明「为什么不能写」必然会提到它
    //    （第一次写这条断言就是这么假红的）。改为剥离注释后再查。
    const stripped = app
      .replace(/\/\*[\s\S]*?\*\//g, '')     // 块注释
      .replace(/^\s*\/\/.*$/gm, '')        // 行注释
    const i = stripped.indexOf('role="dialog"')
    expect(i, '找不到 role="dialog"').toBeGreaterThan(0)
    const attrBlock = stripped.slice(i, i + 300)
    expect(attrBlock, 'aria-modal 在非模态浮层上是错的').not.toContain('aria-modal=')
  })

  it('⭐ 覆盖层必须 pointer-events:none（否则背景透明也照样吃掉主对话的点击）', () => {
    const css = src(CSS)
    const block = css.slice(
      css.indexOf('.kb-scene-overlay {'),
      css.indexOf('.kb-scene-panel {')
    )
    expect(block, '覆盖层没有 pointer-events:none ⇒ 点不到主对话输入框')
      .toMatch(/pointer-events:\s*none/)
    // 而面板本身必须 auto，否则整个面板都点不动
    const panelCss = css.slice(
      css.indexOf('.kb-scene-panel {'),
      css.indexOf('.kb-scene-panel {') + 600
    )
    expect(panelCss).toMatch(/pointer-events:\s*auto/)
  })

  it('⭐ 遮罩不能再有实色背景（半透明黑会盖住主对话）', () => {
    const css = src(CSS)
    const block = css.slice(
      css.indexOf('.kb-scene-overlay {'),
      css.indexOf('.kb-scene-panel {')
    )
    expect(block).toMatch(/background:\s*transparent/)
    expect(block, '半透明黑回来了 ⇒ 「一边聊天一边看」又做不到').not.toMatch(/rgba\(\s*0\s*,\s*0\s*,\s*0/)
  })

  it('⭐ 面板有拖动位移（transform 由 kbDrag 驱动）', () => {
    const app = src(APP_TSX)
    expect(app).toContain('kb-scene-drag-handle')
    expect(app).toMatch(/const \[kbDrag, setKbDrag\] = useState/)
    expect(app).toMatch(/transform: `translate\(\$\{kbDrag\.x\}px, \$\{kbDrag\.y\}px\)`/)
  })

  it('⭐ 拖动把手必须是面板的子元素（放 overlay 下会按视口定位、拖不动）', () => {
    const app = src(APP_TSX)
    // 实测踩过：把手放在 overlay 下 ⇒ 宽 1876px、x=0，与面板错位且 pointerdown 无效
    const panelIdx = app.indexOf('className="kb-scene-panel kb-scope"')
    const handleIdx = app.indexOf('className="kb-scene-drag-handle"')
    expect(panelIdx, '找不到面板').toBeGreaterThan(0)
    expect(handleIdx, '找不到把手').toBeGreaterThan(0)
    expect(handleIdx, '把手必须在面板标签**之后**（= 面板的子元素）')
      .toBeGreaterThan(panelIdx)
  })

  it('⭐⭐ 拖动必须同时挂 mouse 与 pointer（只挂 pointer ⇒ 某些环境整个拖拽是死的）', () => {
    const app = src(APP_TSX)
    const i = app.indexOf('className="kb-scene-drag-handle"')
    expect(i, '找不到拖动条').toBeGreaterThan(0)
    const block = app.slice(i, i + 600)

    // 实测事故：该渲染环境真实鼠标按下只产生 mousedown，**一条 pointerdown 都没有**
    // ⇒ 只挂 onPointerDown 时「按住拖动」完全没反应（用户报「无法移动」）。
    // 合成 PointerEvent 测试查不出这个问题，所以这里只能做源码级断言。
    expect(block, '缺 onMouseDown ⇒ 不派发 Pointer Events 的环境拖不动')
      .toContain('onMouseDown')
    expect(block, '缺 onPointerDown ⇒ 支持 Pointer 的环境少了高精度路径')
      .toContain('onPointerDown')

    // 两套都会派发的环境里必须去重，否则位移翻倍。去重标志必须是 **ref**（state 是异步的）
    expect(app).toMatch(/kbDragActiveRef/)
    expect(app, '去重不能用 state：同帧内第二次进来时 state 还没更新')
      .toMatch(/const kbDragActiveRef = useRef\(/)
  })

  it('⭐ 拖动必须有边界夹取（否则整块拖出视口就再也点不到）', () => {
    const app = src(APP_TSX)
    expect(app, '没有 clamp ⇒ 面板可被拖到屏幕外').toMatch(/const clamp = /)
    expect(app).toMatch(/window\.innerWidth - baseX/)
  })

  it('⭐⭐ 点「知识库」默认开独立窗口（页面元素无法移出 WebView，必须开系统窗口）', () => {
    const app = src(APP_TSX)
    // ⚠️ 入口已经是一段多行箭头函数，不能只看 `.find()` 那一行（`openKnowledgeWindow`
    //    在下面几行）。取入口到下一个 prop 之间的一段。
    const i = app.indexOf('onOpenKnowledgeBase')
    expect(i, '找不到知识库入口').toBeGreaterThan(0)
    const block = app.slice(i, i + 500)
    // 用户 2026-11-09：「点开默认就是独立窗口」
    expect(block, '入口没走独立窗口 ⇒ 点开还是浮层').toContain('openKnowledgeWindow')
  })

  it('⭐ 独立窗口开不出来时必须退回浮层（⛔ 不能点了没反应）', () => {
    const app = src(APP_TSX)
    const i = app.indexOf('onOpenKnowledgeBase')
    const block = app.slice(i, i + 900)
    expect(block, '缺 !ok 兜底 ⇒ 弹窗被拦时用户点了什么都没有').toMatch(/if \(!ok\)/)
    expect(block).toContain('setShowKnowledge(true)')
  })

  it('⭐ 独立窗口用系统标题栏（否则无边框窗只能 Alt+F4 关、也无法系统级拖到别的屏）', () => {
    const win = src(KB_WINDOW_UTIL)
    expect(win).toMatch(/decorations:\s*true/)
  })
})

describe('知识库页面可退出（⭐ 用户报「点击会话 他给我挡住了会话」）', () => {
  it('⭐ switchSession 必须先回对话页，且在 no-op 提前返回之前', () => {
    const app = src(APP_TSX)
    const lines = app.split('\n')
    const start = lines.findIndex((l) => /const switchSession = useCallback/.test(l))
    expect(start, '找不到 switchSession').toBeGreaterThanOrEqual(0)
    const body = lines.slice(start, start + 40).join('\n')

    const resetIdx = body.indexOf("setWorkspacePage('chat')")
    expect(
      resetIdx,
      'switchSession 里没有回对话页 ⇒ 在知识库页点会话会切不过去（用户报的缺陷）'
    ).toBeGreaterThan(0)

    // ⚠️ 顺序必须是「先回页、后 no-op 返回」：在知识库页点「当前会话」是最常见的回退路径，
    //    若 no-op 在前，这一下会被吞掉，页面回不去。
    const noopIdx = body.indexOf('if (sessionId === activeSessionId) return')
    expect(noopIdx, '找不到 no-op 守卫（被改名？）').toBeGreaterThan(0)
    expect(
      resetIdx < noopIdx,
      'setWorkspacePage 必须在 no-op 返回之前，否则「点当前会话回对话页」失效'
    ).toBe(true)
  })

  it('⭐ Esc 守卫按 workspacePage 判，而不是硬编码某一个页面', () => {
    const app = src(APP_TSX)
    // 旧实现只判 showSchedulesPage ⇒ 知识库页 Esc 无效且会误触发停流
    expect(
      app,
      'Esc 守卫写成只判单个页面 ⇒ 新增页面时会漏（曾漏掉 knowledge）'
    ).toContain("if (workspacePage !== 'chat')")
  })

  it('Esc effect 的依赖数组包含 workspacePage（否则读到旧值）', () => {
    const app = src(APP_TSX)
    const escDepsLine = app
      .split('\n')
      .find((l) => l.includes('canOpenDialogOps, showCommandPalette'))
    expect(escDepsLine, '找不到 Esc effect 的依赖数组').toBeTruthy()
    expect(
      escDepsLine,
      'Esc effect 依赖里没有 workspacePage ⇒ 闭包读到旧值，页面切换后 Esc 行为不更新'
    ).toContain('workspacePage')
    expect(
      escDepsLine,
      '依赖里残留 showSchedulesPage ⇒ 与实现不一致'
    ).not.toContain('showSchedulesPage')
  })

  it('⭐ 三条退出路径都要能回对话页（新建会话 / 点会话 / Esc）', () => {
    const app = src(APP_TSX)
    // ① 全新会话
    expect(
      app,
      '「新建会话」没重置 workspacePage ⇒ 停在知识库页时新建会话，主区还是被挡住'
    ).toMatch(/onCreateSession=\{\(\) => \{\s*setWorkspacePage\('chat'\)/)
    // ② 点已有会话 —— 在 switchSession 内（见上一条用例）
    // ③ Esc —— 按 workspacePage 判（见上一条用例）
  })
})

describe('知识库页面行为（源码级）', () => {
  it('上传走 multipart，⛔ 不能复用会 JSON.stringify 的 api()', () => {
    const api = src(fileURLToPath(new URL('../api/knowledge.ts', import.meta.url)))
    expect(api).toContain('new FormData()')
    expect(api, '手写 Content-Type 会缺 boundary ⇒ 后端解析失败').not.toMatch(
      /uploadKnowledge[\s\S]{0,600}Content-Type['"]?\s*:\s*['"]multipart/
    )
  })

  it('⭐ 只在有非终态条目时轮询（终态后必须停）', () => {
    // 轮询随文档区一起搬到 KnowledgeDocPanel（页头那份列表不再轮询）
    const panel = src(DOC_PANEL_TSX)
    expect(panel).toContain('hasInFlight')
    // 终态集合必须与后端状态机一致
    expect(panel).toContain("['completed', 'failed', 'cancelled']")
    // 停止条件：全部终态 ⇒ 不轮询
    expect(panel).toMatch(/if \(!hasInFlight\) return/)
    // ⛔ 页头不能也起一个定时器 —— 两个定时器会互相打架
    expect(src(PAGE_TSX)).not.toContain('setInterval')
  })

  it('⭐ 空列表文案必须区分「搜索无结果」与「库是空的」', () => {
    const page = src(DOC_PANEL_TSX)
    // 两种成因的文案都要在
    expect(page, '搜索无结果的文案缺失 ⇒ 会误导用户去重复上传').toContain('没有匹配的文档')
    expect(page).toContain('这个知识库还没有文档')
  })

  it('⛔ 不摆依赖未实现端点的假按钮（置顶/复制/分享）', () => {
    // ⚠️ 只检查 **JSX 标签**，不扫全文 —— 文件头注释里写了这些词（说明"为什么不摆"），
    //    用 includes 会假红（实测踩过）。
    const page = src(PAGE_TSX)
    const jsxText = page
      .split('\n')
      .filter((l) => /<button|className="kb-btn|<span className|<div className/.test(l))
      .join('\n')
    for (const fake of ['置顶', '复制知识库', '分享']) {
      expect(jsxText, `后端未实现该端点，按钮点了会 404：${fake}`).not.toContain(fake)
    }
  })

  it('⭐ 检索框真的调 hybrid-search（⛔ 不是摆设）', () => {
    const page = src(DOC_PANEL_TSX)
    expect(page).toContain('knowledgeApi.hybridSearch')
    expect(page).toContain('kb-searchbar')
    // 结果列表必须渲染内容与来源，否则用户不知道命中了什么
    expect(page).toContain('kb-hit-body')
    expect(page).toContain('knowledge_title')
  })

  it('删除/重解析都有确认或明确的动作路径', () => {
    const page = src(DOC_PANEL_TSX)
    expect(page).toContain('knowledgeApi.deleteKnowledge')
    expect(page).toContain('knowledgeApi.reparse')
    expect(page).toContain('window.confirm')
  })
})
