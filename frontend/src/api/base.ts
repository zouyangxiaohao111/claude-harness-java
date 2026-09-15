/**
 * 后端地址 · 全前端**单一来源**（收口前散落在 10 处常量里写死 host:port）。
 *
 * <h2>为什么必须分环境（⛔ 不是画蛇添足）</h2>
 * <p><b>Tauri 打包后 WebView 不在 vite dev server 上</b>：{@code front/src-tauri/tauri.conf.json} 的
 * {@code build.frontendDist = "../dist"} ⇒ 打包版页源是 {@code tauri://}（Windows 上
 * {@code http://tauri.localhost}），<b>不是</b> dev server。此时相对路径 {@code /api/...} 会指向
 * WebView 自己而不是后端 ⇒ <b>打包版全废</b>。故：
 * <ul>
 *   <li><b>dev</b>（{@code vite dev} / {@code tauri dev}，页源 {@code http://localhost:3000}）：
 *       走<b>相对路径</b>，由 {@code vite.config.ts} 的 {@code server.proxy} 转发到本机后端 3458；</li>
 *   <li><b>打包</b>（{@code vite build} / {@code tauri build}）：{@code import.meta.env.DEV === false}
 *       ⇒ 导出<b>绝对地址</b>，行为与收口前逐字相同。</li>
 * </ul>
 *
 * <h2>判据来源（实测，非推测）</h2>
 * <p>{@code tauri.conf.json}：{@code build.beforeBuildCommand = "npm run build"}，而
 * {@code front/package.json} 的 {@code build = "tsc && vite build"} —— {@code vite build} 是
 * <b>生产模式</b> ⇒ {@code import.meta.env.DEV === false}（Vite 内置语义：DEV = 非 build 模式）。
 * 与之相对 {@code build.devUrl = "http://localhost:3000"} + {@code beforeDevCommand = "npm run dev"}。
 *
 * <h2>⛔ WebSocket 不适用裸相对路径</h2>
 * <p>{@code new WebSocket('/ws')} 抛 {@code DOMException: TypeError: Invalid URL}
 * （实测 Node 24；浏览器同 —— WebSocket 构造器要求带 scheme 的绝对 URL）。
 * 故 dev 下拼成 {@code ws://${location.host}/ws} 交给 vite proxy 的 {@code ws: true} 转发升级。
 */

/** 后端绝对地址 · ⛔ 全前端**唯一**一处写死 host:port（改端口只改这里） */
const BACKEND_ORIGIN = 'http://localhost:3458'

/** 后端 WS 绝对地址（http→ws / https→wss 确定性变换，不重复写死） */
export const BACKEND_WS_ORIGIN = BACKEND_ORIGIN.replace(/^http/, 'ws')

/** 一次解析出的全部地址 */
export interface ResolvedBase {
  /** 无版本前缀的 API 根（agent / command / market / agents 文本端点） */
  apiBase: string
  /** 带 v1 版本前缀的 API 根（rest.ts 主客户端 / LaunchGate 后端就绪探活） */
  apiV1Base: string
  /** STOMP 原生 WebSocket 端点（socket.ts 首选通道） */
  wsBase: string
  /** SockJS 回退端点（socket.ts 原生 WS 失败后的降级通道） */
  sockjsBase: string
}

/**
 * 纯函数：按「是否 dev / 页源 host / 页源是否 https」解析全部地址。
 *
 * <p><b>WHY 抽成纯函数</b>：{@code import.meta.env.DEV} 是模块加载期求值的常量，
 * 运行期无法翻转 ⇒ 若把逻辑直接内联在常量里，「打包分支」永远测不到（零鉴别力）。
 * 抽成纯函数后两个分支都可被<b>确定性</b>单测，反向实验（改坏 ternary）必红。
 *
 * @param dev       dev 模式（= {@code import.meta.env.DEV}）
 * @param pageHost  当前页源 host:port（dev 下 = vite dev server）
 * @param pageSecure 页源是否 https（决定 ws:/wss: 与 http:/https:）
 */
export function resolveBase(dev: boolean, pageHost: string, pageSecure: boolean): ResolvedBase {
  const httpScheme = pageSecure ? 'https:' : 'http:'
  const wsScheme = pageSecure ? 'wss:' : 'ws:'
  return {
    apiBase: dev ? '/api' : `${BACKEND_ORIGIN}/api`,
    apiV1Base: dev ? '/api/v1' : `${BACKEND_ORIGIN}/api/v1`,
    // WebSocket 构造器要求绝对 URL ⇒ dev 下必须带 scheme + 页源 host（见类 javadoc）
    wsBase: dev ? `${wsScheme}//${pageHost}/ws` : `${BACKEND_WS_ORIGIN}/ws`,
    sockjsBase: dev ? `${httpScheme}//${pageHost}/ws-sockjs` : `${BACKEND_ORIGIN}/ws-sockjs`,
  }
}

const RESOLVED = resolveBase(
  import.meta.env.DEV,
  typeof location !== 'undefined' ? location.host : '',
  typeof location !== 'undefined' && location.protocol === 'https:',
)

/** 无版本前缀的 API 根 */
export const API_BASE = RESOLVED.apiBase
/** 带 v1 版本前缀的 API 根 */
export const API_V1_BASE = RESOLVED.apiV1Base
/** STOMP 原生 WebSocket 端点 */
export const WS_BASE = RESOLVED.wsBase
/** SockJS 回退端点 */
export const SOCKJS_BASE = RESOLVED.sockjsBase
