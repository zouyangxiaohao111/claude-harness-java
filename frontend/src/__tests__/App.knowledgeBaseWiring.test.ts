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
    // 2026-11-06 用户裁定：知识库是**独立场景**，用浮层承载，⛔ 不再换页
    expect(line).toContain('setShowKnowledge(true)')
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
    // 面板内先渲染场景侧栏（KbSceneNav）再渲染页面 ⇒ 放宽窗口，但必须两者都在
    expect(app).toMatch(/kb-scene-panel[\s\S]{0,2000}<KnowledgeBasePage/)
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
