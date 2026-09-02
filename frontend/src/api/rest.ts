/**
 * REST API 客户端 · 与 nexusai-backend (Spring Boot) 对接
 *
 * <p>BASE_URL 在 Tauri dev 模式下指向本机后端。
 * <p>生产环境打包后，Tauri WebView 走 tauri:// 协议，需要在 Rust 端用 tauri-plugin-http 代理。
 * <p>v1 只做最小可用：原生 fetch + JSON + 错误归一化（RFC 7807 Problem → ApiError）。
 */

// Phase 7 dev 联调固定 localhost:3458
// 后续做 Phase 7.1（生产环境）时换为 tauri-plugin-http
const BASE_URL = 'http://localhost:3458/api/v1'

export class ApiError extends Error {
  status: number
  type: string
  title: string
  detail?: string
  fieldErrors?: Array<{ field: string; message: string; rejectedValue?: unknown }>

  constructor(
    message: string,
    init: {
      status: number
      type?: string
      title?: string
      detail?: string
      fieldErrors?: Array<{ field: string; message: string; rejectedValue?: unknown }>
    }
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = init.status
    this.type = init.type ?? 'about:blank'
    this.title = init.title ?? 'Error'
    this.detail = init.detail
    this.fieldErrors = init.fieldErrors
  }

  /** 校验错（400）时返回第一个字段错误的中文消息，否则 detail，否则 statusText */
  userMessage(): string {
    if (this.fieldErrors && this.fieldErrors.length > 0) {
      return this.fieldErrors.map((e) => `${e.field}: ${e.message}`).join('; ')
    }
    return this.detail ?? `${this.status} ${this.title}`
  }
}

interface ProblemJson {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  traceId?: string
  errors?: Array<{ field: string; message: string; rejectedValue?: unknown }>
  /** 非 RFC 7807 错误体（如 TeamController 409 返回 {success, message}）· 兜底 message 字段 */
  message?: string
}

async function parseProblem(res: Response): Promise<ApiError> {
  let body: ProblemJson = {}
  try {
    body = (await res.json()) as ProblemJson
  } catch {
    // 非 JSON 错误体
  }
  // 兼容非 Problem JSON：后端 Team 409 返回 {success:false, message}（无 detail）→ message 兜底为 detail
  const detail = body.detail ?? (typeof body.message === 'string' && body.message ? body.message : undefined)
  return new ApiError(detail || body.title || res.statusText, {
    status: res.status,
    type: body.type,
    title: body.title,
    detail,
    fieldErrors: body.errors,
  })
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

export async function api<T>(path: string, opts: RequestOptions = {}, base: string = BASE_URL): Promise<T> {
  const { method = 'GET', body, signal } = opts
  const headers: Record<string, string> = { 'Accept': 'application/json', 'X-Client-Env': 'react' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(`${base}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (e) {
    // 网络层错（断网、CORS 拒绝、DNS）
    throw new ApiError(
      `Network error: ${e instanceof Error ? e.message : String(e)}`,
      { status: 0, title: 'Network Error' }
    )
  }

  if (!res.ok) {
    throw await parseProblem(res)
  }

  // 空响应体（204 或 202 无 body，如 DELETE、POST /cancel）→ undefined
  const raw = await res.text()
  if (!raw) return undefined as T

  // text/plain（如 away-summary 返回纯文本摘要）→ 原样返回，其余 JSON → parse
  const contentType = res.headers.get('Content-Type') ?? ''
  if (contentType.includes('text/plain')) return raw as unknown as T

  return JSON.parse(raw) as T
}

export { BASE_URL }