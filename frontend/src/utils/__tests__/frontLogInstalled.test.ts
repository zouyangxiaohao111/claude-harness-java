import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 守护「前端日志通道**被安装**」这一接线（OBS1）。
 *
 * WHY：`utils/frontLog.ts` 是靠 `main.tsx` 顶部的 `import` 以**顶层副作用**安装的
 * （劫持 console.error/warn、挂 error/unhandledrejection、起 10s 心跳）。
 * 这层接线一旦被删掉、注释掉，或挪到业务 import 之后（安装晚于早期错误 ⇒ 那些错误永久无痕），
 * **整条前端日志通道静默失效**：没有任何运行时报错，也没有任何功能测试会变红 ——
 * 正好复现本批要消灭的那类盲区（同类先例：窗口钩子接线、spawn_blocking，都是「接线拆掉测试全绿」）。
 *
 * ⚠️ 读取方式：直接用 node `fs` 读源码文本（vitest `environment: 'node'`，见 vite.config.ts），
 * **绝不 import main.tsx** —— 后者会真的执行 `ReactDOM.createRoot(...).render()`，在 node 环境必炸。
 * ⚠️ 路径以 `import.meta.url` 相对本测试文件解析，不依赖 vitest 的 cwd。
 * ⚠️ `main.tsx` 是 **CRLF** ⇒ 读入后统一归一为 LF，否则带 `$` 锚点的断言会无端变红。
 */

const MAIN_TSX = fileURLToPath(new URL('../../main.tsx', import.meta.url))
const FRONTLOG_TS = fileURLToPath(new URL('../frontLog.ts', import.meta.url))

/** 读源码文本并归一换行（CRLF / CR → LF）。 */
function readSource(file: string): string {
  return readFileSync(file, 'utf8').replace(/\r\n?/g, '\n')
}

/** main.tsx 的全部顶层 import 语句（含 `import './x'` 这种 side-effect 形式），按出现顺序。 */
function mainImports(): string[] {
  return readSource(MAIN_TSX)
    .split('\n')
    .filter((line) => /^\s*import\b/.test(line))
}

/** frontLog 的 import 在 main.tsx 所有 import 里的序号（0 起）；-1 = 不存在。 */
function frontLogImportIndex(): number {
  return mainImports().findIndex((line) => /utils\/frontLog['"]/.test(line))
}

describe('frontLog 安装接线（源码级守护）', () => {
  it('main.tsx 必须 import utils/frontLog —— 否则通道静默失效（无报错、无日志）', () => {
    expect(existsSync(MAIN_TSX), `找不到 ${MAIN_TSX}`).toBe(true)
    expect(
      frontLogImportIndex(),
      'main.tsx 未 import utils/frontLog ⇒ console.error/warn、未捕获异常、unhandledrejection ' +
        '与心跳全部无人上报，且不会有任何报错（用户只会看到「什么都没记下来」）',
    ).toBeGreaterThanOrEqual(0)
  })

  it('frontLog 的 import 必须排在所有业务 import 之前（安装晚于早期错误 ⇒ 那些错误永久无痕）', () => {
    const imports = mainImports()
    const frontLogIdx = frontLogImportIndex()
    expect(frontLogIdx, 'main.tsx 未 import frontLog').toBeGreaterThanOrEqual(0)

    // 硬参照点：业务入口 './App' 必须晚于 frontLog
    const appIdx = imports.findIndex((line) => /from\s+['"]\.\/App['"]/.test(line))
    expect(
      appIdx,
      "main.tsx 里找不到 `import App from './App'`：参照点已失效，本测试需同步更新",
    ).toBeGreaterThanOrEqual(0)
    expect(frontLogIdx, 'frontLog 的 import 必须早于业务入口 ./App').toBeLessThan(appIdx)

    // 且必须落在最靠前的 3 个 import 内。
    // ⚠️ 这里刻意**不**写成「必须在 import React 之后」：本仓实现把 frontLog 放在**第一位**
    //    （比 React 更早安装，严格强于「React 之后」）。用「序号足够靠前」表达意图，
    //    才不会把更强的正确实现判红。
    expect(
      frontLogIdx,
      `frontLog 必须在前 3 个 import 内，实际序号 ${frontLogIdx}：${imports[frontLogIdx] ?? '(无)'}`,
    ).toBeLessThanOrEqual(2)
  })

  it('frontLog.ts 必须在模块顶层调用 install() —— 同一根线的另一端：import 在但没装，一样静默失效', () => {
    const calledAtTopLevel = readSource(FRONTLOG_TS)
      .split('\n')
      .some((line) => /^\s*install\(\)\s*$/.test(line))
    // ⚠️ 局限（如实登记，非完备守护）：这是行级源码断言，只拦「调用被整行删除 / 改名」这类变异，
    //    拦不住 `if (false && …)` 这种等价改写 —— 那属于「条件被改」而非「接线被拆」。
    expect(
      calledAtTopLevel,
      'frontLog.ts 顶层必须调用 install()：若只剩 `function install()` 定义而无人调用，' +
        'main.tsx 的 import 就退化成 no-op —— 与删掉 import 是同一种静默失效',
    ).toBe(true)
  })
})
