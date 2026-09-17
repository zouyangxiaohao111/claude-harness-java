/**
 * 前端日志 / 心跳上报（OBS1）。
 *
 * 背景：2026-09-17 用户遇到「前端整屏完全静止」，服务端查不出根因 —— 前端侧无任何日志、
 * 无上报通道，叠加后端 chunk 推送也无痕，三条盲区使「后端没推」与「前端没渲染」无法分辨。
 * 本模块把前端侧的错误与心跳经 Tauri IPC 送到壳的 `{data_dir}/logs/frontend.log`（与后端日志同目录）。
 *
 * ⭐ 心跳：每 10s 一跳，seq 自增；**日志里心跳断档 = 前端停了/僵了** —— 这是「整屏静止」最需要的信号。
 * ⭐ 心跳额外带 **入站帧计数**（framesIn / 最后收帧 / 末帧类型，OBS2）：心跳只证明「JS 活着」，
 *   不证明「还在收帧」—— 补上后，「心跳在跳但 framesIn 不涨」= 通道死了（而非渲染坏了），
 *   这是把「没推」与「没渲染」分开的唯一手段。
 *
 * ⛔ 本通道自身绝不能成为新故障源：所有 invoke 都 catch 且不上报；上报过程由 `reporting` 门禁挡住递归。
 *
 * 安装方式：模块顶层副作用（`import './utils/frontLog'` 即生效，见 main.tsx 首个 import）。
 */
import { invoke } from '@tauri-apps/api/core'
// ⛔ 零依赖叶子模块（只含可变计数对象），不会把聊天 hook / store 依赖图拉进启动最前 —— 见该文件 Javadoc
import { inboundFrameStats } from './inboundFrameStats'

/** 限流窗口（毫秒）：同一 tag+msg 在该窗口内只上报一次，防同一错误刷屏把日志撑爆。 */
const RATE_LIMIT_MS = 10_000
/** 限流去重键里 msg 的参与长度：超长 msg 只比前 N 个字符，避免超长文本比对开销。 */
const DEDUP_KEY_CHARS = 200
/** 心跳间隔（毫秒）。 */
const HEARTBEAT_MS = 10_000
/**
 * `console.log` 专用限流窗口（毫秒）—— 比 {@link RATE_LIMIT_MS} 狠得多。
 *
 * <p>⚠️ 取舍（实测依据）：`console.log` 在前端是**最高频**通道（本仓 dev 诊断、第三方库内部都打它），
 * 而 error/warn 稀疏。给两者同一个 10s 窗口，等于放开一条能刷爆日志的通道 —— 日志通道变成新的
 * 性能/噪声问题，比它没上报更糟。故：30s 窗口 + 关键字白名单（见 {@link CONSOLE_LOG_KEYWORDS}），
 * 两道闸叠加。
 */
const CONSOLE_LOG_RATE_LIMIT_MS = 30_000
/**
 * `console.log` 上报关键字白名单（大小写不敏感）。
 *
 * <p>⚠️ 为什么必须有这层过滤：`console.log` 的入参若都做 `JSON.stringify` 后上报，高频路径上
 * 序列化开销本身就是问题。故只对**首参是字符串/Error 且命中关键字**的调用做完整序列化 + 上报，
 * 其余直接放过（原实现照常执行）。
 *
 * <p>关键词来历（⛔ 不是拍脑袋列的）：`@stomp/stompjs` 的 `parser.js` 用
 * `console.log('Ignoring an exception thrown by a frame handler. Original exception: ', e)`
 * **吞掉帧处理异常** —— 这正是「帧收到了却没渲染」那一类故障的唯一痕迹，`exception` 必须进白名单。
 * 其余 error/fail/stomp/warn/异常/失败 覆盖常规错误文案。
 */
const CONSOLE_LOG_KEYWORDS = /error|exception|fail|stomp|warn|ignor|异常|失败/i

/** 上报递归门禁：上报自身（invoke 的 catch / 任何内部路径）绝不能再触发上报。 */
let reporting = false
/** 限流状态：`tag+msg前缀` → { 上次放行时刻, 期间被丢弃条数 }。 */
const seen = new Map<string, { at: number; dropped: number }>()

/** 把任意值转成可读字符串；转换失败绝不抛出（本通道不得成为故障源）。 */
function stringify(v: unknown): string {
  try {
    if (typeof v === 'string') return v
    if (v instanceof Error) return `${v.name}: ${v.message}`
    if (typeof v === 'object' && v !== null) return JSON.stringify(v) ?? String(v)
    return String(v)
  } catch {
    return '[不可序列化的值]'
  }
}

function stringifyArgs(args: unknown[]): string {
  return args.map(stringify).join(' ')
}

/**
 * 上报一条前端日志（限流 + 防递归）。
 *
 * 限流：同 `tag+msg`（截断后比较）在 `windowMs` 内只上报一次；被丢弃的次数累计，
 * 下次放行时在**同一条**里带上「（期间丢弃 N 条）」—— 既不刷屏，也不丢量级信息。
 *
 * @param windowMs 限流窗口（毫秒），缺省 {@link RATE_LIMIT_MS}；`console.log` 传
 *                 {@link CONSOLE_LOG_RATE_LIMIT_MS}（越高频的通道用越狠的窗口）
 */
function report(level: string, tag: string, msg: string, windowMs: number = RATE_LIMIT_MS): void {
  if (reporting) return

  const key = `${tag}\u0000${msg.slice(0, DEDUP_KEY_CHARS)}`
  const now = Date.now()
  const prev = seen.get(key)
  if (prev && now - prev.at < windowMs) {
    prev.dropped++
    return
  }
  const dropped = prev ? prev.dropped : 0
  seen.set(key, { at: now, dropped: 0 })

  reporting = true
  const text = dropped > 0 ? `${msg}（期间丢弃 ${dropped} 条）` : msg
  // ⛔ catch 内绝不再上报（否则失败即递归）；finally 复位门禁且不抛出
  void invoke('frontend_log', { level, tag, msg: text })
    .catch(() => {
      /* 日志通道失败静默：它本身不能再成为故障源 */
    })
    .finally(() => {
      reporting = false
    })
}

function install(): void {
  // 1) console.error / console.warn —— 必须先调用原实现，否则会破坏 devtools 原生行为
  const origError = console.error.bind(console)
  const origWarn = console.warn.bind(console)
  const origLog = console.log.bind(console)
  console.error = (...args: unknown[]): void => {
    origError(...args)
    report('error', 'console.error', stringifyArgs(args))
  }
  console.warn = (...args: unknown[]): void => {
    origWarn(...args)
    report('warn', 'console.warn', stringifyArgs(args))
  }
  // 1b) console.log —— OBS1 只劫持了 error/warn，于是**第三方库用 console.log 吞掉的异常零留痕**：
  //     @stomp/stompjs 的 parser.js 正是 `console.log('Ignoring an exception thrown by a frame
  //     handler. …', e)` 吞帧处理异常 —— 2026-09-17「帧收到了却没渲染」那类故障的唯一痕迹。
  //     ⚠️ 取舍：console.log 极高品 → 先走**廉价预筛**（只取首参字符串 / Error 消息，不 JSON.stringify），
  //     命中关键字才做完整序列化 + 上报，且用 30s 窗口（见 CONSOLE_LOG_KEYWORDS /
  //     CONSOLE_LOG_RATE_LIMIT_MS）。⛔ 非字符串且非 Error 的首参直接放过 —— 高频对象日志不值得为它付
  //     JSON.stringify 的代价（本通道绝不能自己变成性能问题）。
  //     ⛔ 与原实现的关系：仍然**先调用原实现**（devtools 行为不变）。
  console.log = (...args: unknown[]): void => {
    origLog(...args)
    const preview = cheapPreview(args[0])
    if (preview === null) return
    if (!preview.always && !CONSOLE_LOG_KEYWORDS.test(preview.text)) return
    report('log', 'console.log', stringifyArgs(args), CONSOLE_LOG_RATE_LIMIT_MS)
  }

  // 2) 未捕获异常：消息 + 来源文件:行:列 + stack
  //    用 addEventListener('error') 而非覆写 window.onerror —— 后者会顶掉别人已挂的处理器
  window.addEventListener('error', (e: ErrorEvent) => {
    const where = e.filename ? `${e.filename}:${e.lineno}:${e.colno}` : '(无来源)'
    report('error', 'window.onerror', `${e.message} @ ${where}\n${e.error?.stack ?? '(无 stack)'}`)
  })

  // 3) 未处理的 Promise rejection：reason 转字符串 + stack
  window.addEventListener('unhandledrejection', (e: PromiseRejectionEvent) => {
    const reason = e.reason
    const stack = reason instanceof Error ? (reason.stack ?? '(无 stack)') : '(非 Error，无 stack)'
    report('error', 'unhandledrejection', `${stringify(reason)}\n${stack}`)
  })

  // 4) 心跳：每 10s 一跳，note 带可见性（对照「窗口是不是被挂起」）+ **入站帧计数**（对照
  //    「JS 活着 ≠ 还在收帧」）。判读方式（2026-09-17 事故留下的判据）：
  //      心跳在跳 + framesIn 不涨 → 通道死了（半开连接 / 后端没推 / 推给零订阅者）
  //      心跳在跳 + framesIn 在涨 → 帧收到了，问题在渲染链
  let seq = 0
  setInterval(() => {
    seq += 1
    const sinceFrame = inboundFrameStats.lastFrameAt === 0
      ? '从未收帧'
      : `${Date.now() - inboundFrameStats.lastFrameAt}ms 前`
    // ⛔ 用 void + catch 吞掉 rejection：心跳失败若冒泡成 unhandledrejection 会自己触发上报 → 递归
    void invoke('frontend_heartbeat', {
      seq,
      note: `visibility=${document.visibilityState} framesIn=${inboundFrameStats.framesIn} `
        + `最后收帧=${sinceFrame} 末帧类型=${inboundFrameStats.lastEventType || '(无)'}`,
    }).catch(() => {
      /* 心跳失败静默 */
    })
  }, HEARTBEAT_MS)
}

/**
 * 廉价预筛 `console.log` 首参 —— 只做 `typeof` / `instanceof` 判定，**不**序列化后续参数。
 *
 * @returns 命中的预览文本 + 是否绕过关键字闸；返回 `null` = 首参不是字符串也不是 Error
 *          → 高频对象日志直接放过（不值得为它付 JSON.stringify 的代价）
 */
function cheapPreview(first: unknown): { text: string; always: boolean } | null {
  if (typeof first === 'string') return { text: first, always: false }
  if (first instanceof Error) return { text: `${first.name}: ${first.message}`, always: true }
  return null
}

/**
 * 上报一条前端错误（给 React ErrorBoundary / createRoot 的错误回调用）。
 *
 * <p>为什么单独开一个导出：`main.tsx` 已经以顶层副作用装了本模块（`import './utils/frontLog'`），
 * 但 `report` 是模块私有的 —— React 19 的 `createRoot(container, { onUncaughtError, ... })` 与
 * ErrorBoundary 需要**主动**把错误送进来，故最小必要地把这一条通道导出（不暴露 report 本体，
 * 免得调用方绕过 tag/level 约定）。
 *
 * @param tag  来源标签（如 `react.onUncaughtError` / `react.ErrorBoundary`）
 * @param error 错误对象（任意值，内部统一 stringify）
 * @param info  可选补充（如 React 的 componentStack / 边界标识），转成字符串一并带上
 */
export function reportFrontendError(tag: string, error: unknown, info?: unknown): void {
  const stack = error instanceof Error ? (error.stack ?? '(无 stack)') : '(非 Error，无 stack)'
  const extra = info === undefined ? '' : `\n${stringify(info)}`
  report('error', tag, `${stringify(error)}\n${stack}${extra}`)
}

// 仅 Tauri 环境启用（浏览器 dev 不打：否则控制台刷红且 invoke 必然全失败）
if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window) {
  install()
}
