// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * appName 缓存（`utils/appNameCache`）单测。
 *
 * <b>WHY（规则九 · 测试验证意图而非行为）</b>：这个模块只服务一件事 ——
 * `LaunchGate` 的<b>后端起不来</b>错误卡要写出正确的日志路径（`~/.{appName}/logs/backend.log`），
 * 而那一刻 `GET /settings` <b>结构上拉不到</b>（该页就是显示后端失败的页面）。
 * 两条不变量必须钉死，因为它们错了都不会报错、只会给用户一条指向不存在文件的提示：
 * <ol>
 *   <li><b>无缓存 ⇒ 逐字回落 `.nexusai`</b>：首装即失败（两层缓存都空）时文案与落地前完全一致 ——
 *       这是已登记的已知边界，不允许因为「想要新名字」而猜一个别的值；</li>
 *   <li><b>有缓存 ⇒ 用缓存值</b>：否则场景发行（appName=nexusai-scene）会一直指向
 *       `~/.nexusai/logs/`，用户照提示找不到任何日志。</li>
 * </ol>
 *
 * ⚠️ 模块有<b>内存层</b>（module-level）⇒ 每个用例经 {@link freshModule} 取全新实例
 * （`vi.resetModules`），使断言与用例顺序无关。
 */

/** localStorage 键（与模块导出的 APP_NAME_STORAGE_KEY 同值；此处独立断言，顺带钉住键名不被改）。 */
const KEY = 'nexusai.appName'

/** 取一份**全新**的模块实例（清空内存层），让每个用例互不依赖。 */
async function freshModule() {
  vi.resetModules()
  return await import('@/utils/appNameCache')
}

beforeEach(() => {
  localStorage.clear()
})

describe('appNameCache · 启动页日志路径的目录名', () => {
  it('两层都空（首装即失败）⇒ 回落 .nexusai —— 与落地前逐字相同', async () => {
    const m = await freshModule()
    expect(m.cachedConfigDirLabel()).toBe('.nexusai')
  })

  it('只有 localStorage 有值（跨进程重启）⇒ 用上次成功加载的 appName', async () => {
    const m = await freshModule()
    localStorage.setItem(KEY, 'nexusai-scene')
    expect(m.cachedConfigDirLabel()).toBe('.nexusai-scene')
  })

  it('rememberAppName 写入后 ⇒ 内存与 localStorage 都是新值（同进程 + 跨进程都生效）', async () => {
    const m = await freshModule()
    m.rememberAppName('nexusai-scene')
    expect(m.cachedConfigDirLabel()).toBe('.nexusai-scene')
    expect(localStorage.getItem(KEY)).toBe('nexusai-scene')
  })

  it('⛔ 空白 / null / undefined 不覆盖既有缓存（宁可留旧值，也不写成空串让路径变成 `~/.`）', async () => {
    const m = await freshModule()
    m.rememberAppName('nexusai-scene')
    for (const blank of ['', '   ', null, undefined]) {
      m.rememberAppName(blank)
      expect(m.cachedConfigDirLabel(), `空值 ${JSON.stringify(blank)} 不得覆盖`).toBe('.nexusai-scene')
    }
    expect(localStorage.getItem(KEY)).toBe('nexusai-scene')
  })

  it('两端都读不到（localStorage 抛异常，如隐私模式）⇒ 回落 .nexusai，不抛', async () => {
    const m = await freshModule()
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(() => m.cachedConfigDirLabel()).not.toThrow()
    expect(m.cachedConfigDirLabel()).toBe('.nexusai')
    spy.mockRestore()
  })
})
