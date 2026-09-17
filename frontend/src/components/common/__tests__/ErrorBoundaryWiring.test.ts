import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * OBS2 · main.tsx 顶层接线守护（**源码级**）。
 *
 * <p><b>WHY（这是本批唯一能守住这条接线的手段）</b>：行为级测试（`components/center/__tests__/
 * ErrorBoundary.render.test.tsx`）证明的是「边界本身有效」，**证明不了 main.tsx 真的挂上了它**。
 * 而这条接线的失效方式**全部是静默的**：删掉 `<ErrorBoundary>`、删掉 createRoot 的三个错误回调 ——
 * 编译通过、功能正常、所有行为测试照样全绿，只有真出事那一刻防线不在。
 * 本仓已有同款先例与同一套取舍：`utils/__tests__/frontLogInstalled.test.ts` 用源码断言守
 * main.tsx 的 frontLog 安装接线，并明确登记了「行级源码断言拦不住 `if (false && …)`」这一局限。
 *
 * <p><b>为什么单独一个文件（而不并进 jsdom 的行为测试）</b>：本文件用 `import.meta.url` 定位 main.tsx，
 * 需要 `file:` 形式的 URL —— jsdom 环境下 `import.meta.url` 是 http 形式，`fileURLToPath` 会抛
 * `TypeError: The URL must be of scheme file`（实测踩到）。故本文件**不加** `@vitest-environment` 指令，
 * 沿用 vite.config.ts 的全局 `node` 环境（与 frontLogInstalled.test.ts 同款）。
 *
 * <p>⚠️ 路径以 `import.meta.url` 相对本测试文件解析，**不依赖 vitest 的 cwd**；main.tsx 是 CRLF，
 * 读入后归一为 LF（否则带 `$` 锚点的断言会无端变红）。
 */

const MAIN_TSX = fileURLToPath(new URL('../../../main.tsx', import.meta.url))

/** 读 main.tsx 源码文本并归一换行（CRLF / CR → LF）。 */
function mainSource(): string {
  return readFileSync(MAIN_TSX, 'utf8').replace(/\r\n?/g, '\n')
}

/**
 * 取某回调的实现体（从 `onXxx:` 那行起，含其后 3 行）。
 *
 * <p>⚠️ 不能只看那一行：回调体是多行箭头函数（`onXxx: (a, b) => {` 换行后才是调用），
 * 只看单行会把正确实现判红（实测踩到）。
 */
function callbackBody(src: string, cb: string): string {
  const lines = src.split('\n')
  const i = lines.findIndex((l) => new RegExp(`^\\s*${cb}\\s*:`).test(l))
  return i < 0 ? '' : lines.slice(i, i + 3).join('\n')
}

describe('OBS2 · main.tsx 顶层接线（源码级守护）', () => {
  it('createRoot 必须传全三个 React 19 错误回调 —— 少一个就多一块永久盲区', () => {
    const src = mainSource()
    for (const cb of ['onUncaughtError', 'onCaughtError', 'onRecoverableError']) {
      expect(
        new RegExp(`^\\s*${cb}\\s*:`, 'm').test(src),
        `main.tsx 的 createRoot 缺少 ${cb}：该类错误将永久无痕。三者互不重叠 ——`
          + 'onUncaughtError=边界之外的未捕获错误（React 会卸载整棵树）/ '
          + 'onCaughtError=被 ErrorBoundary 接住的 / onRecoverableError=React 自动恢复的。',
      ).toBe(true)
    }
  })

  it('三个回调都必须真的把错误交给 reportFrontendError（挂空函数等于没接）', () => {
    const src = mainSource()
    for (const cb of ['onUncaughtError', 'onCaughtError', 'onRecoverableError']) {
      // 断言「实现行里引用了上报函数」：既拦「回调被清空」，也拦「改成 console.error 自成一派」
      expect(
        callbackBody(src, cb),
        `${cb} 的实现必须调用 reportFrontendError（否则错误只留在 devtools 里，日志通道拿不到）`,
      ).toContain('reportFrontendError')
    }
  })

  it('根组件必须被 ErrorBoundary 包住，且包的必须是 <Root />', () => {
    const src = mainSource()
    expect(
      /<ErrorBoundary\b/.test(src),
      'main.tsx 未用 ErrorBoundary 包住 <Root />：React 18+ 未捕获的渲染错误会把整棵树卸载成白屏，'
        + '而这条路径不会有任何运行时报错，也不会有任何行为测试变红',
    ).toBe(true)
    // 硬参照点：包了别的（例如只包某段 UI）等于没包 —— 必须真的包住根组件
    expect(
      src,
      'ErrorBoundary 必须包住 <Root />（包错对象 = 顶层防线不成立）',
    ).toMatch(/<ErrorBoundary[\s\S]*?<Root \/>/)
  })
})
