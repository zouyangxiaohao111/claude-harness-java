import { describe, expect, it } from 'vitest'
import { resolveBase, BACKEND_WS_ORIGIN } from '../base'

/**
 * §一 硬编码收口的守门测试。
 *
 * <p><b>为什么必须守</b>：dev 下走相对路径这件事**只有在打包版仍然拿到绝对地址时才是对的**
 * —— Tauri 打包后 WebView 的页源是 tauri://（不是 vite dev server），相对路径会指到 WebView 自己
 * ⇒ 打包版全废。所以「分环境」的两条分支<b>都必须被钉住</b>，只测 dev 一侧等于没测
 * （正是本仓「只覆盖一侧」的前科）。
 *
 * <p><b>为什么测纯函数而不是测常量</b>：`import.meta.env.DEV` 在模块加载期求值，
 * 运行期无法翻转 ⇒ 直接断言导出的常量只能覆盖当前 vitest 的那一侧（DEV=true），
 * 打包分支永远测不到 = 零鉴别力写法。
 */
describe('api/base resolveBase', () => {
  it('打包分支（dev=false）：全部为后端绝对地址 —— 守住「打包不破」', () => {
    const r = resolveBase(false, 'tauri.localhost', false)
    expect(r.apiBase).toBe('http://localhost:3458/api')
    expect(r.apiV1Base).toBe('http://localhost:3458/api/v1')
    expect(r.wsBase).toBe('ws://localhost:3458/ws')
    expect(r.sockjsBase).toBe('http://localhost:3458/ws-sockjs')
    // 打包分支⛔ 不得出现任何相对路径（否则 WebView 会解析到 tauri:// 自己）
    for (const v of [r.apiBase, r.apiV1Base, r.wsBase, r.sockjsBase]) {
      expect(v).not.toMatch(/^\//)
    }
  })

  it('dev 分支（dev=true）：HTTP 走相对路径 + WS 带页源 host', () => {
    const r = resolveBase(true, 'localhost:3000', false)
    // HTTP 相对路径 —— 由 vite proxy 转发（相对路径对页源 host 不敏感，含 TAURI_DEV_HOST 局域网场景）
    expect(r.apiBase).toBe('/api')
    expect(r.apiV1Base).toBe('/api/v1')
    // WS 必须是绝对 URL：`new WebSocket('/ws')` 抛 Invalid URL（实测 Node 24 / 浏览器同）
    expect(r.wsBase).toBe('ws://localhost:3000/ws')
    expect(r.sockjsBase).toBe('http://localhost:3000/ws-sockjs')
  })

  it('https 页源 ⇒ wss / https（本地不起 TLS，但语义必须对）', () => {
    const r = resolveBase(true, 'example.test', true)
    expect(r.wsBase).toBe('wss://example.test/ws')
    expect(r.sockjsBase).toBe('https://example.test/ws-sockjs')
  })

  it('端口唯一来源：改 BACKEND_ORIGIN 一处即全量生效（打包分支四址同源）', () => {
    const r = resolveBase(false, '', false)
    expect(BACKEND_WS_ORIGIN).toBe('ws://localhost:3458')
    for (const v of [r.apiBase, r.apiV1Base, r.sockjsBase]) {
      expect(v.startsWith('http://localhost:3458/')).toBe(true)
    }
    expect(r.wsBase.startsWith('ws://localhost:3458/')).toBe(true)
  })
})
