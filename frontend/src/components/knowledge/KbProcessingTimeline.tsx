/**
 * 知识处理时间线 —— 源站 `frontend/src/components/knowledge-processing-timeline.vue`
 * 的 React 移植（源站 script `:1-1548`、template `:1551-2067`）。
 *
 * <h2>这个组件在展示什么</h2>
 * 一篇文档的一次解析 = 一棵 span 树：五个主管线阶段（docreader / chunking / embedding /
 * multimodal / postprocess）+ postprocess 后面异步跑的子任务（summary / question /
 * graph.chunk[i] / wiki）。时间线把整棵树画成 Langfuse 风格瀑布图：左侧名字列、中间耗时、
 * 右侧条形。底部是选中行的详情面板。
 *
 * <h2>照抄的三件硬约束（⛔ 别"优化"）</h2>
 * <ol>
 *   <li><b>阶段恒定五段</b>：`traceRoot` 用恒定的 `stages[]` 覆盖 `trace.children`
 *       （源站 `:672`）—— 后端补的占位段在这里生效，界面上永远 5 行，不会因为后端少发一段
 *       就变成 3 行。</li>
 *   <li><b>条形下限 0.4%</b>：1ms 的 span 在 10s 的轴上若按真实比例是 0.01%，肉眼看不见；
 *       下限 0.4% 才能看见它存在（源站 `:825`）。右侧还要 clamp 到 `100 - left`，
 *       否则条形会溢出轨道（源站同处）。</li>
 *   <li><b>轮询是"一个 setInterval 到底"</b>（源站 `:138-159` 的注释）：源站历史上试过
 *       自递归 setTimeout / watcher 链 / fetchInFlight 路由 tick 回调，全都在某些边角上
 *       静默死掉、而 LIVE 徽标还在闪。所以这里同样只有一个 2s 的 interval，回调里读状态
 *       决定这次要不要发请求；没有任何 re-arm。</li>
 * </ol>
 *
 * <h2>取数为什么是注入的</h2>
 * 本批（R2-B / 批 4）只做组件，⛔ 不改 `api/knowledge.ts`。所以 `getSpans` 通过
 * `fetchSpans` prop 注入，不传时用内部默认实现直接返回 `{ success: false }`
 * （⇒ 界面落「暂无解析记录」，不会偷偷打网络）。批 6 注入真实 API。
 *
 * <h2>字段名口径</h2>
 * 后端 `KnowledgeSpansData` 返回的是 <b>snake_case</b>（`span_id` / `parse_status` /
 * `duration_ms` / `last_activity_at` …）。⛔ 不要改成 camelCase —— 改了全空。
 */

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import {
  groupPostprocessGraphSpans,
  knowledgeSpansPayloadHasTrace,
  postprocessElapsedMs,
  summarizePostprocessTasks,
  type KnowledgeTraceNode,
  type SpansResponse,
} from '@/utils/knowledgeTrace'
import {
  isPollingStatus,
  resolveTimelineHeaderStatus,
  shouldShowProcessingError,
} from '@/utils/knowledgeProcessingStatus'
import { shownStall, stalledMinutes, STALLED_POLL_INTERVAL_MS } from '@/utils/knowledgeProcessingStall'
import { axisGridStepPct, buildAxisTicks, computeTraceAxis } from '@/utils/traceAxis'
import { knowledgeApi } from '@/api/knowledge'
import { AssistantActionButton } from './AssistantActionButton'

type SpanNode = KnowledgeTraceNode

/** 五个主管线阶段。⛔ 顺序即展示顺序，也是 `currentStageIndex` 的分母。 */
const STAGES = ['docreader', 'chunking', 'embedding', 'multimodal', 'postprocess'] as const

const POLL_INTERVAL_MS = 2000

/**
 * trace 静默后仍继续轮询的宽限窗口（源站 `:317`）。
 *
 * postprocess 的异步子任务在主管线收口<b>之后</b>才开 span（wiki 有 30s 的
 * `wikiIngestDelay`）。那一刻 `parse_status` 已经是 `completed`、现有 span 全是 `done`，
 * `isLive` 立刻翻假 ⇒ 轮询停摆，用户就盯着半渲染的树直到手动刷新。
 * 这个窗口让「刚静默」的那段时间继续轮询，把迟到的子 span 自动捞上来；2 分钟既盖过 30s
 * 的 wiki 延迟 + 一次典型入库，又不至于打开几天前的旧文档还在白轮询。
 */
const QUIESCE_GRACE_MS = 2 * 60 * 1000

// ===================== 文案表（源站 zh-CN.ts `knowledgeStages` 段，逐字） =====================

/** `knowledgeStages.stage.*` */
const STAGE_LABEL: Record<string, string> = {
  docreader: '文档解析',
  chunking: '分块',
  embedding: '向量化',
  multimodal: '多模态识别',
  postprocess: '后处理',
}

/** `knowledgeStages.status.*`（七个状态） */
const STATUS_LABEL: Record<string, string> = {
  pending: '等待中',
  running: '进行中',
  finalizing: '优化中',
  done: '已完成',
  failed: '失败',
  skipped: '已跳过',
  cancelled: '已取消',
}

/**
 * `knowledgeStages.errorCode.*`
 *
 * ⚠️ `DOCREADER_*` 三个码本仓<b>当前不会产出</b>：源站的 DocReader 是一个独立的 Python
 * gRPC 解析服务，本仓的解析器是进程内调用。键名与文案照抄保留，是为了与源站的错误码空间
 * 保持一致（后端将来下发同名码时前端无需再改）。
 */
const ERROR_TITLE: Record<string, string> = {
  DOCREADER_UNAVAILABLE: '文档解析服务不可用',
  DOCREADER_TIMEOUT: '文档解析超时',
  DOCREADER_PARSE_FAILED: '文档解析失败',
  TASK_STALLED: '长时间无进展，已自动终止',
}

const ERROR_SUGGESTION: Record<string, string> = {
  DOCREADER_UNAVAILABLE:
    '无法连接 DocReader，或连接已中断。请检查解析服务是否启动、是否反复重启以及网络是否正常；服务恢复后再重试，无需重复上传文件。',
  DOCREADER_TIMEOUT: '请检查 DocReader 的健康状态和负载；服务正常后再重试，必要时拆分大文件。',
  DOCREADER_PARSE_FAILED: '请检查文件格式，并让管理员查看本次解析对应的 DocReader 日志。',
  TASK_STALLED:
    '处理超过阈值仍没有任何进展，且队列中已无对应任务，已被系统标记为失败。请点击「重试」；如反复出现，请检查该阶段依赖的服务（文档解析、模型、向量库）是否正常。',
}

const UNKNOWN_SUGGESTION = '请查看应用日志获取详细信息。'

function stageLabel(name: string): string {
  return STAGE_LABEL[name] ?? name
}

/** 源站 `localizedStatus`：认不出的状态原样显示，⛔ 不要显示空。 */
function statusLabel(status: string): string {
  return STATUS_LABEL[status] ?? status
}

function localizedErrorTitle(code?: string): string {
  if (!code) return ''
  return ERROR_TITLE[code] ?? code
}

function localizedErrorSuggestion(code?: string): string {
  if (!code) return ''
  return ERROR_SUGGESTION[code] ?? UNKNOWN_SUGGESTION
}

// ===================== 纯函数（源站 `:189-226` / `:634-1135`） =====================

/** 源站 `:189`：`<1000 → Nms` / `<60000 → X.XXs` / 其余 `XmY.Ys`。 */
function formatDuration(ms?: number | null): string {
  if (ms === undefined || ms === null || Number.isNaN(ms) || ms < 0) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(2)}s`
  const mins = Math.floor(ms / 60000)
  const rem = ((ms % 60000) / 1000).toFixed(1)
  return `${mins}m${rem}s`
}

/** 源站 `:634`：`rowKey(node, fallback) = node.span_id || fallback`（算法 1）。 */
function rowKey(node: SpanNode, fallback: string): string {
  return node.span_id || fallback
}

function parseTime(s?: string | null): number | null {
  if (!s) return null
  const t = Date.parse(s)
  return Number.isNaN(t) ? null : t
}

function nodeStart(node: SpanNode): number | null {
  return parseTime(node.started_at)
}

/**
 * 源站 `:648`（算法 2）：先认 `finished_at`；否则用 `started_at + duration_ms`。
 * ⚠️ 判据是 `duration_ms > 0`，<b>不是</b> `>= 0` —— `0` 意味着「没有可信的耗时」，
 * 此时退回 `started_at + 0` 会把一根 0 宽的条画在起点上，看起来像"这个阶段没跑"。
 */
function nodeEnd(node: SpanNode): number | null {
  const e = parseTime(node.finished_at)
  if (e !== null) return e
  const s = nodeStart(node)
  if (s !== null && typeof node.duration_ms === 'number' && node.duration_ms > 0) {
    return s + node.duration_ms
  }
  return null
}

function collectStarts(node: SpanNode | undefined, out: number[]): void {
  if (!node) return
  const s = nodeStart(node)
  if (s !== null) out.push(s)
  for (const c of node.children || []) collectStarts(c, out)
}

function collectEnds(node: SpanNode | undefined, out: number[]): void {
  if (!node) return
  const e = nodeEnd(node)
  if (e !== null) out.push(e)
  for (const c of node.children || []) collectEnds(c, out)
}

/** 子树里最晚的结束时刻（源站 `:851`）。 */
function descendantMaxEnd(node: SpanNode): number | null {
  const ends: number[] = []
  for (const c of node.children || []) collectEnds(c, ends)
  if (ends.length === 0) return null
  return Math.max(...ends)
}

/** 源站 `:907`（算法 9 前半）：没有 span_id 也没有开始时间 ⇒ 这是后端补的占位段。 */
function isPlaceholder(node: SpanNode): boolean {
  return !node.span_id && !node.started_at
}

/** 源站 `:907` 之后的状态词表。 */
const HARD_TERMINAL = new Set(['cancelled', 'failed', 'completed'])

function isPolling(status?: string): boolean {
  // finalizing 是 postprocess 派发窗口：子 span（summary / question / graph.chunk[*]）
  // 还在持续产出事件，所以必须继续轮询并画 LIVE。
  // 判据与「能否取消解析」共用同一个纯函数，⛔ 别在这里另写一份词表。
  return isPollingStatus(status)
}

function isHardTerminal(status?: string): boolean {
  return HARD_TERMINAL.has(status ?? '')
}

/** 树里还有没有 running/pending 的节点（源站 `:247`）。 */
function spanTreeActive(node?: SpanNode | null): boolean {
  if (!node) return false
  const s = node.status
  if (s === 'running' || s === 'pending') return true
  for (const kid of node.children || []) {
    if (spanTreeActive(kid)) return true
  }
  return false
}

/** 树里最新的一次活动时刻（源站 `:282`），供静默宽限窗判断"刚完成还是老完成"。 */
function spanTreeLastActivity(node?: SpanNode | null): number {
  if (!node) return 0
  let max = 0
  const extra = node as unknown as { updated_at?: string | null; created_at?: string | null }
  const stamps: Array<string | null | undefined> = [
    extra.updated_at,
    node.finished_at,
    node.started_at,
    extra.created_at,
  ]
  for (const s of stamps) {
    const t = parseTime(s ?? undefined)
    if (t !== null && t > max) max = t
  }
  for (const kid of node.children || []) {
    const t = spanTreeLastActivity(kid)
    if (t > max) max = t
  }
  return max
}

function isObjectWithKeys(v: unknown): boolean {
  return !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length > 0
}

function hasContent(v: unknown): boolean {
  if (v === null || v === undefined || v === '') return false
  if (Array.isArray(v)) return v.length > 0
  if (typeof v === 'object') return Object.keys(v as object).length > 0
  return true
}

function prettyJSON(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

/** 源站 `:1057`：本地时间的 `MM-DD HH:mm:ss.SSS`；跨年才带年份。 */
function formatTime(s?: string | null): string {
  if (!s) return '—'
  const ts = Date.parse(s)
  if (Number.isNaN(ts)) return s
  const d = new Date(ts)
  const ms = String(d.getMilliseconds()).padStart(3, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  const now = new Date()
  const yyyy = d.getFullYear()
  const mo = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const datePart = yyyy === now.getFullYear() ? `${mo}-${dd}` : `${yyyy}-${mo}-${dd}`
  return `${datePart} ${hh}:${mm}:${ss}.${ms}`
}

function humanizeKey(k: string): string {
  return k
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b([a-z])/g, (_, c: string) => c.toUpperCase())
}

type KvKind = 'scalar' | 'bool' | 'array' | 'object'

interface KvEntry {
  key: string
  label: string
  kind: KvKind
  display: string
  raw: unknown
  /**
   * 短载荷 ⇒ 直接内联展开。长载荷保持折叠，否则一个几百条的 output 会把详情面板
   * 撑到没有底。
   */
  defaultExpanded: boolean
}

const KV_INLINE_ARRAY_LIMIT = 8
const KV_INLINE_OBJECT_KEY_LIMIT = 8
const KV_INLINE_JSON_BYTES_LIMIT = 600

function shouldInlineExpand(value: unknown): boolean {
  if (Array.isArray(value)) {
    if (value.length > KV_INLINE_ARRAY_LIMIT) return false
    try {
      return JSON.stringify(value).length <= KV_INLINE_JSON_BYTES_LIMIT
    } catch {
      return false
    }
  }
  if (value && typeof value === 'object') {
    const keys = Object.keys(value as object)
    if (keys.length > KV_INLINE_OBJECT_KEY_LIMIT) return false
    try {
      return JSON.stringify(value).length <= KV_INLINE_JSON_BYTES_LIMIT
    } catch {
      return false
    }
  }
  return false
}

function toKvEntry(key: string, value: unknown): KvEntry {
  const label = humanizeKey(key)
  if (value === null || value === undefined) {
    return { key, label, kind: 'scalar', display: '—', raw: value, defaultExpanded: false }
  }
  if (typeof value === 'boolean') {
    return { key, label, kind: 'bool', display: value ? 'true' : 'false', raw: value, defaultExpanded: false }
  }
  if (typeof value === 'number') {
    return { key, label, kind: 'scalar', display: value.toLocaleString(), raw: value, defaultExpanded: false }
  }
  if (typeof value === 'string') {
    return { key, label, kind: 'scalar', display: value, raw: value, defaultExpanded: false }
  }
  if (Array.isArray(value)) {
    return {
      key,
      label,
      kind: 'array',
      display: `Array · ${value.length}`,
      raw: value,
      defaultExpanded: shouldInlineExpand(value),
    }
  }
  if (typeof value === 'object') {
    const n = Object.keys(value as object).length
    return {
      key,
      label,
      kind: 'object',
      display: `Object · ${n} keys`,
      raw: value,
      defaultExpanded: shouldInlineExpand(value),
    }
  }
  return { key, label, kind: 'scalar', display: String(value), raw: value, defaultExpanded: false }
}

function buildKvEntries(obj: unknown): KvEntry[] {
  if (!isObjectWithKeys(obj)) return []
  return Object.entries(obj as Record<string, unknown>).map(([key, value]) => toKvEntry(key, value))
}

function attemptGlyph(status: string): { ch: string; cls: string } {
  switch (status) {
    case 'done':
      return { ch: '✓', cls: 'kp-glyph-done' }
    case 'failed':
      return { ch: '✗', cls: 'kp-glyph-failed' }
    case 'running':
    case 'pending':
    case 'processing':
      return { ch: '●', cls: 'kp-glyph-running' }
    default:
      return { ch: '–', cls: 'kp-glyph-unknown' }
  }
}

// ===================== 本次解析配置（源站 `:1492-1548`） =====================

/** 单条「文件类型 → 解析引擎」规则（源站 `types/knowledgeProcess.ts`）。 */
export interface ParserEngineRule {
  file_types?: string[]
  engine?: string
  xlsx_first_row_as_header?: boolean
}

/** 本次解析的分块覆盖配置。 */
export interface ChunkingConfigOverride {
  chunk_size?: number
  chunk_overlap?: number
  separators?: string[]
  parser_engine_rules?: ParserEngineRule[]
  enable_parent_child?: boolean
  parent_chunk_size?: number
  child_chunk_size?: number
  strategy?: string
  token_limit?: number
  languages?: string[]
  table_metadata_instructions?: string
}

/** 本次解析的多模态覆盖配置。 */
export interface VLMConfigOverride {
  enabled?: boolean
  model_id?: string
  description_language?: string
  custom_instructions?: string
}

/** 本次解析的语音识别覆盖配置。 */
export interface ASRConfigOverride {
  enabled?: boolean
  model_id?: string
  language?: string
}

/** 本次解析的问句生成覆盖配置。 */
export interface QuestionGenerationConfigOverride {
  model_id?: string
  enabled?: boolean
  question_count?: number
  custom_instructions?: string
}

/** 本次解析的知识图谱抽取覆盖配置（只用到 enabled）。 */
export interface ExtractConfigOverride {
  enabled?: boolean
  text?: string
  tags?: string[]
  custom_instructions?: string
}

/**
 * `knowledge.metadata.process_overrides` 的结构（源站 `types/knowledgeProcess.ts:65-89`）。
 *
 * ⚠️ 本仓当前**没有任何代码写** `process_overrides`（全仓 grep 零命中）⇒ 真机上恒走
 * 「使用知识库默认配置」分支。这不是缺陷：源站无覆盖时显示的也是同一行。
 */
export interface KnowledgeProcessOverrides {
  summary_enabled?: boolean
  parser_engine_rules?: ParserEngineRule[]
  chunking_config?: ChunkingConfigOverride
  enable_multimodel?: boolean
  vlm_config?: VLMConfigOverride
  asr_config?: ASRConfigOverride
  question_generation_config?: QuestionGenerationConfigOverride
  graph_enabled?: boolean
  extract_config?: ExtractConfigOverride
  image_attrs_enabled?: boolean
  image_pipeline?: string
  image_pipeline_params?: Record<string, unknown>
  image_vector_enabled?: boolean
  image_actions?: Record<string, unknown>
  parser_engine_overrides?: Record<string, string>
}

/** 源站 `normalizeFileType`：去空白、去开头的点、小写。 */
export function normalizeFileType(value: string): string {
  return String(value || '')
    .trim()
    .replace(/^\./, '')
    .toLowerCase()
}

/** 源站 `getFileTypeFromName`：从文件名/标题的最后一个点之后取扩展名。 */
export function getFileTypeFromName(name: string): string {
  const clean = String(name || '').split(/[?#]/)[0]
  const dot = clean.lastIndexOf('.')
  return dot >= 0 ? clean.slice(dot + 1) : ''
}

/**
 * 从 `knowledge.metadata` 的**原始 JSON 字符串**里取 `process_overrides`
 * （源站 `fetchProcessOverrides` 的同款判据：`metadata?.process_overrides ?? null`）。
 *
 * <p>容错四档：`null`/`''` ⇒ null；坏 JSON ⇒ null；顶层不是对象 ⇒ null；
 * 没有 `process_overrides`（或它不是对象）⇒ null。`process_overrides: {}` 原样返回空对象
 * （后续 `processConfigLines` 会落回「使用知识库默认配置」）。
 */
export function parseProcessOverrides(
  metadata?: string | null,
): KnowledgeProcessOverrides | null {
  if (!metadata) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(metadata)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const raw = (parsed as { process_overrides?: unknown }).process_overrides
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  return raw as KnowledgeProcessOverrides
}

/**
 * 解析引擎规则的一行文本（源站 `formatParserRulesForCurrentFile`）：
 * 命中当前文件类型 ⇒ `type→engine`；否则全列表 `a/b→engine, c→engine`。
 * 空数组 ⇒ `''`。
 */
export function formatParserRules(rules: ParserEngineRule[], currentType?: string): string {
  const list = Array.isArray(rules) ? rules : []
  const type = normalizeFileType(currentType ?? '')
  if (type) {
    const matched = list.find((rule) =>
      (rule?.file_types || []).some((ft) => normalizeFileType(ft) === type),
    )
    if (matched?.engine) return `${type}→${matched.engine}`
  }
  return list.map((r) => `${(r?.file_types || []).join('/')}→${r?.engine}`).join(', ')
}

const PROCESS_CONFIG_KB_DEFAULT = '使用知识库默认配置'
/** 源站 `uploadConfirm.statusOn` —— 「已开启」 */
const PROCESS_CONFIG_ON = '已开启'
/** 源站 `uploadConfirm.statusOff` —— 「未开启」 */
const PROCESS_CONFIG_OFF = '未开启'

/**
 * 「本次解析配置」弹层的多行文本（源站 `:1509-1548` 逐条移植）。
 *
 * <h2>⭐ 文案出处（源站 `knowledgeStages.processConfig` 只有 3 个键）</h2>
 * 源站 Vue 用 `t('knowledgeStages.processConfig.${key}')` 动态拼键，但 zh-CN.ts 里
 * **只有 `title` / `kbDefault` / `graph`**（`:8414-8418`，对象已闭合），其余 11 个键
 * 在 zh-CN / en-US 全部 locale 中都不存在 ⇒ 源站真机会把 key 原文直接渲染成
 * `knowledgeStages.processConfig.chunking`（源站自身的缺陷，已实测确认）。
 *
 * <p>本仓**不照抄这个缺陷**，改用源站真实存在的中文词汇，出处逐条标注：
 * <ul>
 *   <li>`已开启` / `未开启` ← `uploadConfirm.statusOn` / `statusOff`</li>
 *   <li>分块数字 ← `uploadConfirm.navChunkingSummary`（`分块 {size}`）</li>
 *   <li>`父子分块` ← `uploadConfirm.summaryParentChildShort`</li>
 *   <li>`解析引擎` ← `knowledgeBase.parserEngine`（zh-CN.ts:7240）</li>
 *   <li>`多模态` / `语音识别` ← `uploadConfirm.multimodalSetupHint` / `asrSetupHint`</li>
 *   <li>`{n} 个` ← `uploadConfirm.summaryQuestionCountValue`</li>
 *   <li>`文档摘要` ← `uploadConfirm.documentSummary`；`知识图谱` ← `processConfig.graph`</li>
 * </ul>
 *
 * <p>全空 ⇒ 单行「使用知识库默认配置」（源站同款兜底）。
 */
export function processConfigLines(
  overrides: KnowledgeProcessOverrides | null,
  fileType?: string,
): string[] {
  if (!overrides) return [PROCESS_CONFIG_KB_DEFAULT]
  const onOff = (v: boolean) => (v ? PROCESS_CONFIG_ON : PROCESS_CONFIG_OFF)
  const lines: string[] = []

  const cc = overrides.chunking_config
  if (cc) {
    const parts: string[] = []
    if (cc.chunk_size != null) parts.push(String(cc.chunk_size))
    if (cc.enable_parent_child != null) {
      parts.push(`父子分块 ${onOff(cc.enable_parent_child)}`)
    }
    if (parts.length) lines.push(`分块: ${parts.join(' · ')}`)
  }

  const rules = overrides.parser_engine_rules || cc?.parser_engine_rules
  if (rules?.length) lines.push(`解析引擎: ${formatParserRules(rules, fileType)}`)

  const mm = overrides.vlm_config?.enabled ?? overrides.enable_multimodel
  if (mm != null) lines.push(`多模态: ${onOff(mm)}`)

  if (overrides.asr_config?.enabled != null) {
    lines.push(`语音识别: ${onOff(overrides.asr_config.enabled)}`)
  }

  const qg = overrides.question_generation_config
  if (overrides.summary_enabled != null) {
    lines.push(`文档摘要: ${onOff(overrides.summary_enabled)}`)
  }
  if (qg?.enabled != null) {
    lines.push(`问答: ${qg.enabled ? `${qg.question_count ?? 3} 个` : PROCESS_CONFIG_OFF}`)
  }

  const graph = overrides.graph_enabled ?? overrides.extract_config?.enabled
  if (graph != null) lines.push(`知识图谱: ${onOff(graph)}`)

  return lines.length ? lines : [PROCESS_CONFIG_KB_DEFAULT]
}

// ===================== 契约 =====================

/** `GET /knowledge/{id}/spans` 的取数结果（与源站 `res?.success && res.data` 判据一致）。 */
export interface SpansFetchResult {
  success: boolean
  data?: SpansResponse
  /** 失败原因，用于「处理记录加载失败：{原因}」。 */
  message?: string
}

export type FetchSpansFn = (knowledgeId: string, attempt?: number) => Promise<SpansFetchResult>

export interface TimelineSummary {
  totalMs: number
  status: string
  stageIndex: number
  stageTotal: number
  stageLabel: string
}

export interface KbProcessingTimelineProps {
  knowledgeId: string
  /** 文档级 parse_status（无 spans 时的兜底判定）。 */
  parseStatus?: string
  /** 默认 true。 */
  autoPoll?: boolean
  /** 默认 false；true ⇒ 只渲染五个状态点 + 一行摘要（源站的隐藏挂载用它）。 */
  compact?: boolean
  /**
   * 默认 true。两种轮询口径的开关：
   * <ul>
   *   <li>true —— 跟着「主管线 + 任何还在跑的 subspan + 静默后的宽限窗」走。
   *       用户可见的抽屉要这个：迟到的子 span（wiki 有 30s 去抖）能自动浮出来。</li>
   *   <li>false —— 严格模式：只在 parse_status 非终态时轮询，忽略管线后的异步子任务。
   *       后台隐藏挂载要用这个，否则用户关掉抽屉后那个隐藏挂载还会每 2s 打一次
   *       /spans 直到 wiki 跑完。</li>
   * </ul>
   */
  gracePoll?: boolean
  /** 抽屉主标题（文档名）。 */
  docTitle?: string
  /** 显示关闭按钮（二级抽屉用）。 */
  showClose?: boolean
  /**
   * 取数函数（本批注入，不改 `api/knowledge.ts`）。不传 ⇒ 内部返回 `{ success: false }`。
   * 抛异常也按失败处理，界面落「暂无解析记录」，轮询循环照常活着。
   */
  fetchSpans?: FetchSpansFn
  /** 源站 `emit('update:hasSpans')`：控制抽屉头部的「查看 Trace」按钮显隐。 */
  onHasSpans?: (has: boolean) => void
  /** 源站 `emit('update:summary')`。 */
  onSummary?: (summary: TimelineSummary) => void
  /** 源站 `emit('close')`。 */
  onClose?: () => void
  /** 「重新解析」。不传 ⇒ 工具栏与错误卡里的重试按钮都不渲染（避免点不动的死按钮）。 */
  onRetryParse?: (knowledgeId: string) => Promise<void> | void
  /** 复制到剪贴板。不传 ⇒ 走 `navigator.clipboard`。 */
  onCopy?: (text: string) => void
  /** 停止解析后的提示（本仓无 MessagePlugin，由宿主传入）。不传 ⇒ 不出提示。 */
  showToast?: (msg: string, type?: 'success' | 'info') => void
  /** 「诊断」按钮：带问题打开助手面板。宿主不传 ⇒ 不渲染该按钮。 */
  onOpenAssistant?: (question: string, context?: string) => void
}

type DetailTab = 'overview' | 'input' | 'output' | 'metadata' | 'raw'

interface FlatRow {
  key: string
  depth: number
  node: SpanNode
  hasChildren: boolean
  isRoot: boolean
  isStage: boolean
  parentKey?: string
}

interface AttemptTab {
  n: number
  status: string
  active: boolean
}

interface HeadStat {
  key: string
  label: string
  value: string
  mono: boolean
  note?: boolean
}

interface IdentityField {
  key: string
  label: string
  value: string
  mono: boolean
  copyable: boolean
}

interface StageRowSummary {
  name: string
  label: string
  status: string
  duration_ms?: number
  pct: number
}

// ===================== 树 → 阶段 / 行（算法 3 + flatRows :763） =====================

/** 恒定的五段：后端没发的段补占位（pending + 无 span_id），顺序永远按 STAGES。 */
function stagesFromTrace(trace?: SpanNode | null): SpanNode[] {
  const children = trace?.children || []
  const byName = new Map<string, SpanNode>()
  for (const c of children) {
    if (c && c.kind === 'stage' && c.name) {
      // postprocess 的 graph.chunk[i] 先折叠成一行再上树（utils 里的纯函数）。
      byName.set(c.name, c.name === 'postprocess' ? groupPostprocessGraphSpans(c) : c)
    }
  }
  return STAGES.map(
    (n) => byName.get(n) || ({ name: n, kind: 'stage', status: 'pending' } as SpanNode),
  )
}

/** 算法 3（源站 :672）：用恒定五段覆盖 trace.children，占位段在这里生效。 */
function synthTraceRoot(trace?: SpanNode | null, stages?: SpanNode[]): SpanNode | null {
  if (!trace) return null
  return {
    ...trace,
    name: trace.name || 'knowledge_processing',
    kind: trace.kind || 'root',
    children: stages ?? stagesFromTrace(trace),
  }
}

/**
 * 源站 :763 的扁平化：
 *
 * root  (depth 0, isRoot=true, 永远显示)
 *  └ stage (depth 1, isStage=true)   —— 只有 expandedRows 里有它才展开子级
 *     └ walk(...)                    —— 自己的 chevron 也受 expandedRows 控制
 *
 * 子层那个 gate 不能省：少了它，点子 span 的箭头只会转个方向、子行照样全渲染，
 * 用户看到的就是「点了没反应」。
 */
function buildFlatRows(root: SpanNode | null, expanded: Set<string>): FlatRow[] {
  if (!root) return []
  const rows: FlatRow[] = []
  const rootKey = rowKey(root, '__root__')
  rows.push({
    key: rootKey,
    depth: 0,
    node: root,
    hasChildren: (root.children || []).length > 0,
    isRoot: true,
    isStage: false,
  })

  for (const stage of root.children || []) {
    const stageKey = rowKey(stage, `stage:${stage.name}`)
    const stageChildren = stage.children || []
    rows.push({
      key: stageKey,
      depth: 1,
      node: stage,
      hasChildren: stageChildren.length > 0,
      isRoot: false,
      isStage: true,
      parentKey: rootKey,
    })
    if (!expanded.has(stageKey)) continue

    const walk = (n: SpanNode, depth: number, idxPath: string, parentKeyValue: string) => {
      const key = rowKey(n, `${idxPath}:${n.name}`)
      const kids = n.children || []
      rows.push({
        key,
        depth,
        node: n,
        hasChildren: kids.length > 0,
        isRoot: false,
        isStage: false,
        parentKey: parentKeyValue,
      })
      if (!expanded.has(key)) return
      kids.forEach((c, i) => walk(c, depth + 1, `${idxPath}/${i}`, key))
    }
    stageChildren.forEach((c, i) => walk(c, 2, `${stageKey}/${i}`, stageKey))
  }

  return rows
}

/** 不传取数函数时的默认实现：本批没有 /spans 客户端，直接如实报「没取到」。 */
const defaultFetchSpans: FetchSpansFn = async () => ({ success: false })

// ===================== 停止解析（源站 `:523-542` / `:1619-1634` / `:1681-1694`） =====================

/** 源站 `knowledgeBase.cancelParse` / `common.cancel` / `cancelParseSubmitted` / `cancelParseFailed`，逐字。 */
const CANCEL_PARSE_LABEL = '停止解析'
const CANCEL_PARSE_CANCEL = '取消'
const CANCEL_PARSE_SUBMITTED = '已停止解析'
const CANCEL_PARSE_FAILED = '停止失败，请稍后再试'

/** 源站 `knowledgeBase.cancelParseConfirmBody`，逐字（`{title}` 替换）。 */
function cancelParseConfirmBody(title: string): string {
  return `确认停止解析"${title}"？已写入的分块会保留，可稍后通过"重建"重新触发；优化阶段（摘要 / 问答 / 知识图谱）的待执行任务会被立即丢弃。`
}

interface CancelParseControlProps {
  /** `icon` = 工具栏关闭圆圈图标；`stall` = 卡住提示里的带字按钮。 */
  variant: 'icon' | 'stall'
  confirmBody: string
  cancelling: boolean
  onConfirm: () => void
}

/**
 * 手写的 tdesign `t-popconfirm`（本仓无 tdesign 组件库）。
 *
 * <p>结构照派单书 §2.3 给的 tdesign DOM 快照；`.wiki-popconfirm` 是
 * `position:absolute; z-index:3100`（`kb-03.css:345`）⇒ 这里用 inline style 给外层补
 * `position:relative`（⛔ 不改 CSS 文件）。点外部 / Esc 关闭。
 */
function CancelParseControl({
  variant,
  confirmBody,
  cancelling,
  onConfirm,
}: CancelParseControlProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLSpanElement | null>(null)

  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (ev: MouseEvent) => {
      const root = rootRef.current
      if (!root) return
      if (ev.target instanceof Node && root.contains(ev.target)) return
      setOpen(false)
    }
    const onDocKeyDown = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    document.addEventListener('keydown', onDocKeyDown)
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown)
      document.removeEventListener('keydown', onDocKeyDown)
    }
  }, [open])

  const confirm = () => {
    setOpen(false)
    onConfirm()
  }

  const triggerIcon = variant === 'icon' ? 15 : 14
  const triggerButton =
    variant === 'icon' ? (
      <button
        type="button"
        className={`kp-icon-btn kp-icon-btn-danger${cancelling ? ' kp-icon-btn-spin' : ''}`}
        disabled={cancelling}
        title={CANCEL_PARSE_LABEL}
        aria-label={CANCEL_PARSE_LABEL}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {cancelling ? (
          <svg width={triggerIcon} height={triggerIcon} className="t-icon t-icon-loading" aria-hidden="true">
            <use href="#t-icon-refresh" />
          </svg>
        ) : (
          <svg width={triggerIcon} height={triggerIcon} aria-hidden="true">
            <use href="#t-icon-close-circle" />
          </svg>
        )}
      </button>
    ) : (
      <button
        type="button"
        className="kp-stall-btn"
        disabled={cancelling}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {cancelling ? (
          <svg width={triggerIcon} height={triggerIcon} className="t-icon t-icon-loading" aria-hidden="true">
            <use href="#t-icon-refresh" />
          </svg>
        ) : (
          <svg width={triggerIcon} height={triggerIcon} aria-hidden="true">
            <use href="#t-icon-close-circle" />
          </svg>
        )}
        <span>{CANCEL_PARSE_LABEL}</span>
      </button>
    )

  return (
    <span
      ref={rootRef}
      style={{ position: 'relative', display: 'inline-flex' }}
      onClick={(ev) => ev.stopPropagation()}
    >
      {triggerButton}
      {open ? (
        <div
          className="wiki-popconfirm"
          role="dialog"
          aria-label={CANCEL_PARSE_LABEL}
          style={variant === 'icon' ? { top: '100%', right: 0 } : { top: '100%', left: 0 }}
        >
          <div className="t-popup__content">
            <div className="t-popconfirm__content">
              <div className="t-popconfirm__body">
                <span
                  className="t-popconfirm__icon--warning"
                  aria-hidden="true"
                  style={{ fontSize: 20, lineHeight: '22px', flexShrink: 0, marginRight: 8 }}
                >
                  !
                </span>
                <div className="t-popconfirm__inner">{confirmBody}</div>
              </div>
              <div className="t-popconfirm__buttons">
                <button
                  type="button"
                  className="t-button t-button--variant-base t-button--theme-default t-size-s"
                  onClick={() => setOpen(false)}
                >
                  <span className="t-button__text">{CANCEL_PARSE_CANCEL}</span>
                </button>
                <button
                  type="button"
                  className="t-button t-button--variant-base t-button--theme-danger t-size-s"
                  onClick={confirm}
                >
                  <span className="t-button__text">{CANCEL_PARSE_LABEL}</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </span>
  )
}

// ===================== 组件 =====================

export function KbProcessingTimeline(props: KbProcessingTimelineProps) {
  const {
    knowledgeId,
    parseStatus,
    autoPoll = true,
    compact = false,
    gracePoll = true,
    docTitle = '',
    showClose = false,
    fetchSpans: fetchSpansImpl,
    onRetryParse,
    onCopy,
    showToast,
    onOpenAssistant,
  } = props

  const [data, setData] = useState<SpansResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [selectedAttempt, setSelectedAttempt] = useState<number | undefined>(undefined)
  const [expandedRows, setExpandedRows] = useState<Set<string>>(() => new Set(['__root__']))
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null)
  const [expandedJsonKeys, setExpandedJsonKeys] = useState<Set<string>>(() => new Set())
  const [nowTick, setNowTick] = useState<number>(() => Date.now())
  const [detailTab, setDetailTab] = useState<DetailTab>('overview')
  const [lastFetchedAt, setLastFetchedAt] = useState(0)
  const [lastFetchOk, setLastFetchOk] = useState(true)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [attemptStatuses, setAttemptStatuses] = useState<Map<number, string>>(() => new Map())
  const [showProcessConfig, setShowProcessConfig] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [processOverrides, setProcessOverrides] = useState<KnowledgeProcessOverrides | null>(null)
  const [knowledgeFileType, setKnowledgeFileType] = useState('')

  // 这几个必须有 ref 版本：fetchSpans 在 await 之后才读它们，而闭包里拿到的是发起请求
  // 那一刻的值；auto-expand / 自动选中必须读到「这次请求的结果」而不是上一次的。
  const expandedRef = useRef<Set<string>>(expandedRows)
  const selectedSpanIdRef = useRef<string | null>(null)
  const selectedAttemptRef = useRef<number | undefined>(undefined)
  /** 用户手动展开/收起过的行。进过这个集合的行，后续轮询的 auto-expand 永远不碰它。 */
  const userToggledRows = useRef<Set<string>>(new Set())
  /** 一次性自动选中守卫：用户关掉面板后，2s 的轮询不许把它再弹开。 */
  const autoSelectPending = useRef(true)
  const fetchInFlight = useRef(false)
  const unmounted = useRef(false)
  const lastFetchedAtRef = useRef(0)
  const scrollRef = useRef<HTMLDivElement | null>(null)

  const writeExpanded = useCallback((next: Set<string>) => {
    expandedRef.current = next
    setExpandedRows(next)
  }, [])

  const writeSelected = useCallback((key: string | null) => {
    selectedSpanIdRef.current = key
    setSelectedSpanId(key)
  }, [])

  // 回调也可能每次渲染都换引用（父组件内联箭头），用 ref 转一手，避免 effect 空转。
  const emitRef = useRef({
    onHasSpans: props.onHasSpans,
    onSummary: props.onSummary,
    onClose: props.onClose,
  })
  useEffect(() => {
    emitRef.current = {
      onHasSpans: props.onHasSpans,
      onSummary: props.onSummary,
      onClose: props.onClose,
    }
  })

  // ---------------- 树 / 状态 ----------------

  const stages = useMemo(() => stagesFromTrace(data?.trace), [data])
  const traceRoot = useMemo(() => synthTraceRoot(data?.trace, stages), [data, stages])
  const traceActive = useMemo(() => spanTreeActive(data?.trace), [data])

  /**
   * 同时驱动 LIVE 徽标与轮询（源站 :265）：
   * 有数据 ⇒ 硬终态优先压过残留的 traceActive（取消/不可恢复的失败会把子 span 卡在
   * running，不能因此永远轮询）；没数据 ⇒ 信父组件的 parseStatus 提示。
   */
  const isLive = useMemo(() => {
    if (data) {
      if (isHardTerminal(data.parse_status)) return false
      return isPolling(data.parse_status) || traceActive
    }
    if (isHardTerminal(parseStatus)) return false
    return isPolling(parseStatus)
  }, [data, parseStatus, traceActive])

  const lastTraceActivityAt = useMemo(() => spanTreeLastActivity(data?.trace), [data])

  const isWithinQuiesceGrace = useMemo(() => {
    if (isLive) return false
    if (!lastTraceActivityAt) return false
    return nowTick - lastTraceActivityAt < QUIESCE_GRACE_MS
  }, [isLive, lastTraceActivityAt, nowTick])

  /** 每个 tick 的「这次要不要发请求」（源站 :355）。 */
  const shouldPollNow = (): boolean => {
    if (!data) return isPolling(parseStatus)
    if (gracePoll) return isLive || isWithinQuiesceGrace
    return isPolling(data.parse_status)
  }

  /**
   * 「现在」在瀑布图上的位置来源。终态的解析可能把还没收口的 running 子 span 留在最后
   * 一次响应里；轮询一停，就该把它显示的耗时冻在那次响应上，而不是没有新证据还一直涨。
   */
  const postprocessDisplayNow = (): number =>
    shouldPollNow() ? nowTick : lastFetchedAtRef.current || nowTick

  const formatRelativeTime = (ts: number): string => {
    if (!ts) return '—'
    const sec = Math.max(0, Math.floor((nowTick - ts) / 1000))
    if (sec < 1) return '刚刚'
    if (sec < 60) return `${sec} 秒前`
    return `${Math.floor(sec / 60)} 分钟前`
  }

  /**
   * skipped / pending 的阶段没有执行过，那点记账区间不是处理时间，
   * 不能当多模态耗时显示（源站 :209）。
   */
  const formatSpanDuration = (node: SpanNode, includeChildren = false): string => {
    if (node.status === 'skipped' || node.status === 'pending') return '—'
    if (includeChildren) {
      const elapsed = postprocessElapsedMs(node, postprocessDisplayNow())
      if (elapsed !== null) return formatDuration(elapsed)
    }
    return formatDuration(node.duration_ms)
  }

  const currentStageLabel = useMemo(() => {
    const running = stages.find((s) => s.status === 'running')
    const failed = stages.find((s) => s.status === 'failed')
    const target =
      failed || running || stages.find((s) => s.status === 'pending') || stages[stages.length - 1]
    return target ? stageLabel(target.name) : ''
  }, [stages])

  const currentStageIndex = useMemo(() => {
    const idx = stages.findIndex((s) => s.status === 'running' || s.status === 'failed')
    if (idx >= 0) return idx + 1
    const traversed = stages.filter((s) => s.status === 'done' || s.status === 'skipped').length
    return Math.min(traversed + 1, stages.length)
  }, [stages])

  // ---------------- 时间轴（算法 4 / 5 / 6） ----------------

  const t0 = useMemo<number | null>(() => {
    if (!traceRoot) return null
    const direct = nodeStart(traceRoot)
    if (direct !== null) return direct
    const all: number[] = []
    collectStarts(traceRoot, all)
    if (all.length === 0) return null
    return Math.min(...all)
  }, [traceRoot])

  const tEnd = useMemo<number | null>(() => {
    if (!traceRoot) return null
    const direct = parseTime(traceRoot.finished_at)
    const all: number[] = []
    collectEnds(traceRoot, all)
    let candidate: number | null = direct
    if (all.length > 0) {
      const max = Math.max(...all)
      candidate = candidate === null ? max : Math.max(candidate, max)
    }
    // 还在产出 span 就把右边界推到「现在」—— 解析在途、以及 postprocess 异步子任务在
    // root 收口后继续跑，两种都由 isLive 覆盖。
    if (isLive) {
      const now = nowTick
      candidate = candidate === null ? now : Math.max(candidate, now)
    }
    return candidate
  }, [traceRoot, isLive, nowTick])

  const totalMs = useMemo(() => {
    if (t0 === null || tEnd === null) return 0
    const observed = Math.max(0, tEnd - t0)
    const traceDur = data?.trace?.duration_ms
    // trace 自己的 duration_ms 只覆盖到 FinalizeAttempt；异步子 span 在 root 收口之后
    // 还在产行，轴必须按「观测到的最晚后代」缩放，否则它们的条会被右边界切掉。
    if (typeof traceDur === 'number' && traceDur > 0) return Math.max(traceDur, observed)
    return observed
  }, [t0, tEnd, data])

  const showRuler = totalMs >= 50
  const timeAxis = useMemo(() => computeTraceAxis(totalMs), [totalMs])
  const axisMaxMs = timeAxis.maxMs
  const gridStepPct = axisGridStepPct(timeAxis)
  const rulerTicks = useMemo(() => (showRuler ? buildAxisTicks(timeAxis) : []), [showRuler, timeAxis])

  /** 「现在」在轴上的位置：LIVE 时画一根游标，让人看见时间在走。 */
  const nowMarkerPct = useMemo<number | null>(() => {
    if (!isLive || !t0 || !axisMaxMs) return null
    const pct = ((nowTick - t0) / axisMaxMs) * 100
    return Math.max(0, Math.min(100, pct))
  }, [isLive, t0, axisMaxMs, nowTick])

  // ---------------- 行 ----------------

  const flatRows = useMemo(() => buildFlatRows(traceRoot, expandedRows), [traceRoot, expandedRows])
  const selectedRow = useMemo(
    () => (selectedSpanId ? flatRows.find((r) => r.key === selectedSpanId) || null : null),
    [selectedSpanId, flatRows],
  )
  const detailOpen = selectedSpanId !== null && selectedRow !== null

  /**
   * 算法 7（源站 :825）：条形定位。
   * - 轴退化 / 没有起点 ⇒ display:none（不是画一条 0 宽的条）；
   * - 宽度下限 0.4%：1ms 的 span 按真实比例是 0.01%，肉眼等于不存在；
   * - 右侧 clamp 到 100 - left：否则条形会溢出轨道右缘。
   */
  const barStyle = (node: SpanNode): Record<string, string> => {
    const total = axisMaxMs
    if (!total || t0 === null) return { display: 'none' }
    const start = nodeStart(node)
    if (start === null) return { display: 'none' }
    // 没有 finished_at 的 span：只要「整体还在 live」或「它自己的状态说在跑」，就用 now
    // 当终点。少了第二个条件，熬过 parse_status='completed' 的 postprocess 子 span 会塌成 0 宽。
    const liveBar = isLive || node.status === 'running' || node.status === 'pending'
    const end = nodeEnd(node) ?? (liveBar ? nowTick : start)
    const leftPct = ((start - t0) / total) * 100
    const widthPct = Math.max(0.4, ((end - start) / total) * 100)
    return {
      left: `${Math.max(0, Math.min(100, leftPct))}%`,
      width: `${Math.min(100 - Math.max(0, leftPct), widthPct)}%`,
    }
  }

  /**
   * 算法 8（源站 :860）：子代比父晚结束时画一圈淡外框 —— postprocess 阶段 9ms 就收口，
   * 它的异步子任务还在跑几十秒。外框让「这个阶段的下游一共花了 N 秒」一眼可见，
   * 又不跟阶段自耗时混为一谈。只有晚 >= 50ms 才画，否则外框与实心条重合、纯噪声。
   */
  const wrapStyle = (node: SpanNode): Record<string, string> | null => {
    const total = axisMaxMs
    if (!total || t0 === null) return null
    const start = nodeStart(node)
    if (start === null) return null
    const selfEnd = nodeEnd(node) ?? start
    const childEnd = descendantMaxEnd(node)
    if (childEnd === null) return null
    if (childEnd - selfEnd < 50) return null
    const leftPct = ((start - t0) / total) * 100
    const widthPct = Math.max(0.4, ((childEnd - start) / total) * 100)
    return {
      left: `${Math.max(0, Math.min(100, leftPct))}%`,
      width: `${Math.min(100 - Math.max(0, leftPct), widthPct)}%`,
    }
  }

  const wrapDurationMs = (node: SpanNode): number => {
    const start = nodeStart(node)
    const childEnd = descendantMaxEnd(node)
    if (start === null || childEnd === null) return 0
    return Math.max(0, childEnd - start)
  }

  const barOffsetPct = (node: SpanNode): number | null => {
    const total = axisMaxMs
    if (!total || t0 === null) return null
    const start = nodeStart(node)
    if (start === null) return null
    return Math.max(0, Math.min(100, ((start - t0) / total) * 100))
  }

  const barOffsetMs = (node: SpanNode): number => {
    const start = nodeStart(node)
    if (start === null || t0 === null) return 0
    return Math.max(0, start - t0)
  }

  const liveElapsedMs = (node: SpanNode): number => {
    const s = nodeStart(node)
    if (s === null) return 0
    return Math.max(0, nowTick - s)
  }

  /** trace 已到终态、且没有任何东西还在跑。 */
  const traceTerminal = isHardTerminal(data?.parse_status) && !traceActive

  /**
   * 算法 9（源站 :921）：终态后仍标 pending 的合成段 = 永远没跑（docreader 挂了，
   * 后面的 chunking/embedding 就被放弃了）。v1 把它们画成普通 pending 行 + 贴在轨道
   * 右缘的虚线残桩，看着像渲染垃圾。这里改成写「未执行」并让整行退后。
   */
  const isNotRun = (node: SpanNode): boolean =>
    traceTerminal && node.status === 'pending' && isPlaceholder(node)

  const isRowExpanded = (key: string): boolean => expandedRows.has(key)

  const treeToggleAriaLabel = (row: FlatRow): string => {
    if (!row.hasChildren || row.isRoot) return ''
    return isRowExpanded(row.key) ? '收起子项' : '展开子项'
  }

  const rowLabel = (row: FlatRow): string => {
    if (row.isRoot) return '知识处理'
    if (row.isStage) return stageLabel(row.node.name)
    if (row.node.name === 'postprocess.graph') return '知识图谱'
    const graphChunk = /^postprocess\.graph\.chunk\[(\d+)\]$/.exec(row.node.name)
    if (graphChunk) return `知识图谱 #${Number(graphChunk[1]) + 1}`
    return row.node.name
  }

  /** v1 每行都盖 ROOT / STAGE / SPAN，只有 generation（一次 LLM/VLM 调用）值得一个 chip。 */
  const rowKindLabel = (row: FlatRow): string => {
    if (row.isRoot || row.isStage) return ''
    return row.node.kind === 'generation' ? 'generation' : ''
  }

  // ---------------- 头部状态 ----------------

  const latestAttempt = data?.latest_attempt || 0
  const activeAttempt = selectedAttempt ?? data?.attempt ?? latestAttempt
  const viewingLatestAttempt = latestAttempt <= 1 ? true : activeAttempt === latestAttempt

  /**
   * 头部徽标的权威状态：异步后处理窗口里最新一次尝试的 root span 已经收口
   * （trace.status = 'done'），而 summary/question/wiki 可能还在跑、或者之后又失败 ——
   * 所以最新一次尝试一律以知识行的 parse_status 为准。历史尝试沿用自己落库的 root 状态。
   */
  const headerStatus = data
    ? resolveTimelineHeaderStatus({
        parseStatus: data.parse_status,
        traceStatus: data.trace?.status,
        isLatestAttempt: viewingLatestAttempt,
      })
    : ''

  const headerStatusText = headerStatus ? statusLabel(headerStatus) : ''
  const headerStatusTheme = (() => {
    switch (headerStatus) {
      case 'done':
      case 'completed':
        return 'success'
      case 'failed':
        return 'danger'
      case 'running':
      case 'processing':
      case 'pending':
      case 'finalizing':
        return 'warning'
      default:
        return 'default'
    }
  })()

  const showLastError = shouldShowProcessingError(
    Boolean(data?.last_error),
    data?.parse_status,
    data?.trace?.status,
  )

  const failedStage = stages.find((s) => s.status === 'failed') || null

  /**
   * v1 的标题是 localizedErrorTitle(code)，翻不出来就落回原始码 ⇒ 卡片上
   * DOCREADER_PARSE_FAILED 当标题出现一次、旁边的 chip 又一次，而真正解释失败的那句
   * 反而缩在底部 11px 灰字里。现在标题说「哪一步坏了」，错误码只出现一次，
   * 错误信息提到卡片主行。
   */
  const errorHeadline = (() => {
    const err = data?.last_error
    const localized = localizedErrorTitle(err?.error_code)
    if (localized && localized !== err?.error_code) return localized
    const name = failedStage?.name || STAGES.find((n) => n === err?.name)
    if (name) return `${stageLabel(name)}阶段失败`
    return '失败'
  })()

  /** 失败但没有 last_error 可挂卡内按钮时，重试入口留在工具栏。 */
  const showToolbarRetry = data?.parse_status === 'failed' && !showLastError

  const stagesStatDisplay = useMemo(() => {
    const total = stages.length
    const completedCount = stages.filter((s) => s.status === 'done' || s.status === 'skipped').length
    const inProgress = stages.some(
      (s) => s.status === 'running' || s.status === 'failed' || s.status === 'pending',
    )
    if (inProgress) return { label: '当前阶段', value: `${currentStageIndex}/${total}` }
    return { label: '主流程阶段', value: `${completedCount}/${total}` }
  }, [stages, currentStageIndex])

  const postprocessTaskStats = useMemo(
    () => summarizePostprocessTasks(data?.trace ?? undefined),
    [data],
  )

  const stalledMin = useMemo(() => {
    const d = data
    if (!d) return 0
    if (d.attempt !== (d.latest_attempt || d.attempt)) return 0
    return stalledMinutes(d, nowTick)
  }, [data, nowTick])

  const stalledStageLabel = (() => {
    const stage = data?.current_stage
    return stage && (STAGES as readonly string[]).includes(stage) ? stageLabel(stage) : ''
  })()

  const stallVerdict = shownStall(data?.stall_state, stalledMin)

  /**
   * v1 把五个互不相干的事实粘成一句灰字：总耗时 494ms · 当前阶段 1/5 · 第 1 次尝试。
   * 读起来像流水账、数字全被词埋了。改成标签/值成对的统计条，数字走等宽表列。
   */
  const headStats: HeadStat[] = (() => {
    if (!data) return []
    const out: HeadStat[] = []
    if (totalMs > 0) {
      out.push({ key: 'duration', label: '耗时', value: formatDuration(totalMs), mono: true })
    }
    out.push({
      key: 'stages',
      label: stagesStatDisplay.label,
      value: stagesStatDisplay.value,
      mono: true,
    })
    if (postprocessTaskStats.total > 0) {
      out.push({
        key: 'tasks',
        label: '后台任务',
        value: `运行中 ${postprocessTaskStats.running} · 失败 ${postprocessTaskStats.failed} · 已完成 ${postprocessTaskStats.completed}`,
        mono: false,
        note: postprocessTaskStats.running > 0 || postprocessTaskStats.failed > 0,
      })
    }
    if (latestAttempt <= 1 && data.current_attempt) {
      out.push({ key: 'attempt', label: '尝试', value: `#${data.current_attempt}`, mono: true })
    }
    const lastActivity = parseTime(data.last_activity_at)
    if (lastActivity !== null && isLive) {
      out.push({
        key: 'progress',
        label: '最近进展',
        value: formatRelativeTime(lastActivity),
        mono: false,
        note: stalledMin > 0,
      })
    }
    if (lastFetchedAt && isLive) {
      let updated = formatRelativeTime(lastFetchedAt)
      if (!lastFetchOk) updated += ' (刷新失败)'
      out.push({ key: 'updated', label: '更新于', value: updated, mono: false, note: !lastFetchOk })
    }
    return out
  })()

  const primaryHeadTitle = docTitle || '处理流水线'

  const traceMetadata = hasContent(data?.trace?.metadata) ? data?.trace?.metadata : null

  const stageBreakdown: StageRowSummary[] = (() => {
    const total = totalMs || 1
    return stages.map((s) => ({
      name: s.name,
      label: stageLabel(s.name),
      status: s.status,
      duration_ms: s.duration_ms,
      pct:
        typeof s.duration_ms === 'number' && s.duration_ms > 0
          ? Math.min(100, (s.duration_ms / total) * 100)
          : 0,
    }))
  })()

  const attemptTabs: AttemptTab[] = (() => {
    if (latestAttempt <= 1) return []
    const out: AttemptTab[] = []
    for (let n = 1; n <= latestAttempt; n++) {
      out.push({ n, status: attemptStatuses.get(n) || 'unknown', active: n === activeAttempt })
    }
    return out
  })()

  /**
   * 本次解析配置（源站 `:1509-1548`）。
   *
   * `processOverrides` 来自 `GET /knowledge/{id}` 的 `metadata.process_overrides`。
   * ⚠️ 本仓当前没有任何代码写它 ⇒ 真机恒走「使用知识库默认配置」；这不是缺陷（源站无覆盖时
   * 显示同一行）。⛔ 别为了看见多行去造数据。
   */
  const configLines: string[] = processConfigLines(processOverrides, knowledgeFileType)

  const sectionTab: 'input' | 'output' | 'metadata' | null =
    detailTab === 'input' || detailTab === 'output' || detailTab === 'metadata' ? detailTab : null
  const sectionValue =
    sectionTab && selectedRow
      ? (selectedRow.node as unknown as Record<string, unknown>)[sectionTab]
      : undefined

  // ---------------- 交互 ----------------

  const scrollRowIntoView = (key: string) => {
    const root = scrollRef.current
    if (!root) return
    const safeKey =
      typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(key) : key.replace(/"/g, '\\"')
    const el = root.querySelector(`.kp-row[data-span-key="${safeKey}"]`)
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }

  const selectRow = (row: FlatRow) => {
    if (selectedSpanIdRef.current === row.key) return
    writeSelected(row.key)
    setDetailTab('overview')
    scrollRowIntoView(row.key)
  }

  const closeDetail = () => {
    writeSelected(null)
    // 用户明确关掉了 ⇒ 2s 的轮询不许把它再弹开。
    autoSelectPending.current = false
  }

  /**
   * v1 打开时什么都没选中：短 trace 上面几行、下面半屏空白，也没提示行可点。
   * 打开就选中读者最关心的那行：失败的那行，否则 root（它的详情就是阶段分解）。
   */
  const autoSelectSpan = (rows: FlatRow[]) => {
    if (compact) return
    if (!autoSelectPending.current || selectedSpanIdRef.current) return
    if (rows.length === 0) return
    const failed = rows.find((r) => !r.isRoot && r.node.status === 'failed')
    const key = (failed || rows[0]).key
    writeSelected(key)
    setDetailTab('overview')
    autoSelectPending.current = false
  }

  const toggleTree = (row: FlatRow, ev?: { stopPropagation: () => void }) => {
    if (ev) ev.stopPropagation()
    if (!row.hasChildren) return
    const wasExpanded = expandedRef.current.has(row.key)
    const next = new Set(expandedRef.current)
    if (next.has(row.key)) next.delete(row.key)
    else next.add(row.key)
    writeExpanded(next)
    // 每一层的手动操作都要记下来，否则用户收起的噪声子 span（比如带十几个 page[*]
    // 的 postprocess.wiki）会在下一个 2s tick 被 auto-expand 重新弹开。
    const touched = new Set(userToggledRows.current)
    touched.add(row.key)
    userToggledRows.current = touched
    // 用箭头展开时顺便把这行选中，否则点名字列只会展开树、不出详情。
    if (!wasExpanded && next.has(row.key)) selectRow(row)
  }

  const toggleJsonKey = (section: string, key: string) => {
    const k = `${selectedSpanIdRef.current || ''}::${section}::${key}`
    const next = new Set(expandedJsonKeys)
    if (next.has(k)) next.delete(k)
    else next.add(k)
    setExpandedJsonKeys(next)
  }

  const isJsonExpanded = (section: string, key: string): boolean =>
    expandedJsonKeys.has(`${selectedSpanIdRef.current || ''}::${section}::${key}`)

  const tabHasContent = (tab: 'input' | 'output' | 'metadata'): boolean => {
    const node = selectedRow?.node
    if (!node) return false
    return hasContent((node as unknown as Record<string, unknown>)[tab])
  }

  const copyText = (text: string) => {
    if (onCopy) {
      onCopy(text)
      return
    }
    try {
      void navigator.clipboard?.writeText(text)
    } catch {
      // 剪贴板不可用（无权限 / 非安全上下文）时静默；不影响任何数据展示。
    }
  }

  const copyValue = (value: unknown) => {
    copyText(typeof value === 'string' ? value : prettyJSON(value))
  }

  const identityFields = (row: FlatRow): IdentityField[] => {
    const out: IdentityField[] = []
    const node = row.node
    out.push({ key: 'name', label: '名称', value: rowLabel(row), mono: false, copyable: false })
    out.push({ key: 'kind', label: '类型', value: rowKindLabel(row), mono: true, copyable: false })
    out.push({
      key: 'status',
      label: '状态',
      value: statusLabel(node.status),
      mono: false,
      copyable: false,
    })
    if (row.isStage) {
      const idx = stages.findIndex((s) => s.name === node.name)
      if (idx >= 0) {
        out.push({
          key: 'stageIndex',
          label: '阶段顺序',
          value: `${idx + 1} / ${stages.length}`,
          mono: true,
          copyable: false,
        })
      }
    }
    if (row.hasChildren) {
      out.push({
        key: 'children',
        label: '子 span 数',
        value: String((node.children || []).length),
        mono: true,
        copyable: false,
      })
    }
    if (node.span_id) {
      out.push({ key: 'span_id', label: 'span_id', value: node.span_id, mono: true, copyable: true })
    }
    if (node.parent_span_id) {
      out.push({
        key: 'parent_span_id',
        label: 'parent_span_id',
        value: node.parent_span_id,
        mono: true,
        copyable: true,
      })
    }
    if (data?.knowledge_id) {
      out.push({
        key: 'knowledge_id',
        label: 'knowledge_id',
        value: data.knowledge_id,
        mono: true,
        copyable: true,
      })
    }
    if (data?.current_attempt) {
      out.push({
        key: 'attempt',
        label: '尝试',
        value: `#${data.current_attempt}`,
        mono: true,
        copyable: false,
      })
    }
    return out
  }

  const onAttemptChange = (n: number) => {
    if (Number.isNaN(n)) return
    selectedAttemptRef.current = n
    setSelectedAttempt(n)
    writeSelected(null)
    autoSelectPending.current = true
    // 换一次尝试：忘掉每行的用户选择，让 auto-expand 干净地按新树重算。
    userToggledRows.current = new Set()
    writeExpanded(new Set(['__root__']))
    void fetchSpans()
  }

  const onManualRefresh = () => {
    if (refreshing || loading) return
    void fetchSpans({ manual: true })
  }

  const onRetry = async () => {
    if (!onRetryParse || !knowledgeId) return
    try {
      await onRetryParse(knowledgeId)
      // reparse 成功后重置四件，再重新取数（源站 :484）。
      selectedAttemptRef.current = undefined
      setSelectedAttempt(undefined)
      setAttemptStatuses(new Map())
      writeSelected(null)
      autoSelectPending.current = true
      await fetchSpans()
    } catch {
      // 重试失败由调用方提示，这里不吞掉界面状态。
    }
  }

  /**
   * 源站 `:523-526`：能否「停止解析」。判据与后端 CancelKnowledgeParse 的门一致
   * （pending / processing / finalizing）。优先用最新的 span 数据，落库前信父组件的提示。
   */
  const canCancelParse = isPollingStatus(data?.parse_status ?? parseStatus)

  /** 源站 `:528-542`：停止解析 → success toast → 立刻重取 spans。 */
  const onCancelParseConfirm = async () => {
    if (cancelling || !knowledgeId) return
    setCancelling(true)
    try {
      await knowledgeApi.cancelParse(knowledgeId)
      showToast?.(CANCEL_PARSE_SUBMITTED, 'success')
      await fetchSpans({ manual: true })
    } catch (e) {
      showToast?.(e instanceof Error ? e.message : CANCEL_PARSE_FAILED, 'info')
    } finally {
      setCancelling(false)
    }
  }

  // ---------------- 取数（源站 :363-457） ----------------

  /**
   * 取一次 spans。
   *
   * 三条硬约束（plan §7.1）：
   * 1. fetchInFlight 只防重入、不参与轮询决策 —— 调度权归 tick 回调；
   * 2. 成功后做全深度 auto-expand：有子节点且用户没碰过 ⇒ 展开。只展开 stage 的话，
   *    postprocess.wiki 自己的 chevron 会「点了没反应」；
   * 3. finally 里无论成败都更新 lastFetchedAt + 失败态 —— 否则「更新于 X 秒前」会冻住。
   */
  const fetchSpans = async (opts: { manual?: boolean } = {}): Promise<void> => {
    const id = knowledgeId
    if (!id) return
    if (fetchInFlight.current) return
    fetchInFlight.current = true
    if (opts.manual) setRefreshing(true)
    if (!data) setLoading(true)
    let attemptOk = false
    let errorText: string | null = null
    try {
      const res = await (fetchSpansImpl ?? defaultFetchSpans)(id, selectedAttemptRef.current)
      if (res?.success && res.data) {
        const payload = res.data
        setData(payload)
        attemptOk = true
        if (selectedAttemptRef.current === undefined) {
          selectedAttemptRef.current = payload.attempt
          setSelectedAttempt(payload.attempt)
        }

        const expanded = new Set(expandedRef.current)
        expanded.add('__root__')
        const autoExpand = (n: SpanNode) => {
          const key = n.span_id || `stage:${n.name}`
          const kids = n.children || []
          if (kids.length > 0 && !userToggledRows.current.has(key)) expanded.add(key)
          for (const c of kids) autoExpand(c)
        }
        for (const stage of payload.trace?.children || []) autoExpand(stage)
        writeExpanded(expanded)

        const latest = payload.latest_attempt || payload.attempt || 0
        const tabStatus =
          resolveTimelineHeaderStatus({
            parseStatus: payload.parse_status,
            traceStatus: payload.trace?.status,
            isLatestAttempt: payload.attempt === latest,
          }) || 'running'
        setAttemptStatuses((prev) => new Map(prev).set(payload.attempt, tabStatus))
        void ensureAttemptStatuses(payload, latest)

        emitRef.current.onHasSpans?.(
          knowledgeSpansPayloadHasTrace({
            trace: payload.trace ?? undefined,
            current_attempt: payload.current_attempt,
          }),
        )

        // 自动选中必须用「这次请求的结果」算，不能等下一次渲染 —— 否则会选中上一棵树的行。
        const freshRoot = synthTraceRoot(payload.trace, stagesFromTrace(payload.trace))
        autoSelectSpan(buildFlatRows(freshRoot, expanded))
      } else {
        errorText = res?.message ?? null
        emitRef.current.onHasSpans?.(false)
      }
    } catch (e) {
      // 这里静默过一次，就是轮询停摆那个 bug 藏了很久的原因 —— 必须留痕。
      console.warn('[KnowledgeTimeline] fetchSpans failed', e)
      errorText = e instanceof Error ? e.message : String(e)
      emitRef.current.onHasSpans?.(false)
    } finally {
      const now = Date.now()
      lastFetchedAtRef.current = now
      setLastFetchedAt(now)
      setLastFetchOk(attemptOk)
      setFetchError(attemptOk ? null : errorText)
      setLoading(false)
      setRefreshing(false)
      fetchInFlight.current = false
    }
  }

  /** 源站 :440：历史尝试各取一次状态，供 attempt tab 的字形着色。 */
  const ensureAttemptStatuses = async (payload: SpansResponse, latest: number): Promise<void> => {
    if (latest <= 1) return
    for (let n = 1; n <= latest; n++) {
      if (n === payload.attempt) continue
      try {
        const res = await (fetchSpansImpl ?? defaultFetchSpans)(knowledgeId, n)
        if (res?.success && res.data?.trace) {
          const status =
            resolveTimelineHeaderStatus({
              parseStatus: res.data.parse_status,
              traceStatus: res.data.trace?.status,
              isLatestAttempt: n === latest,
            }) || 'running'
          setAttemptStatuses((prev) => (prev.has(n) ? prev : new Map(prev).set(n, status)))
        }
      } catch {
        // 历史尝试的状态只影响一个字形，失败不打扰用户。
      }
    }
  }

  // ---------------- 轮询（源站 :138-159 / :595-630） ----------------

  /**
   * tick 回调要读「最新一次渲染」的判定函数。state 在 setInterval 的闭包里是冻的，
   * 所以每渲染一次就把当次判定函数塞进这个 ref；2s 的 tick 一定读到新值。
   */
  const latestRef = useRef({
    shouldPollNow,
    stalledMin,
    lastFetchedAt: 0,
    fetchSpans,
    knowledgeId,
  })
  useEffect(() => {
    latestRef.current = {
      shouldPollNow,
      stalledMin,
      lastFetchedAt: lastFetchedAtRef.current,
      fetchSpans,
      knowledgeId,
    }
  })

  useEffect(() => {
    unmounted.current = false
    const nowTimer = setInterval(() => {
      setNowTick(Date.now())
    }, 1000)
    return () => {
      unmounted.current = true
      clearInterval(nowTimer)
    }
  }, [])

  /**
   * 一个 setInterval 到底（源站注释：自递归 setTimeout / watcher 链 / 把 fetchInFlight
   * 路由进 tick 回调的写法，全都在某些边角上静默死掉、而 LIVE 徽标还在闪）。
   * 永不 re-arm：回调里读状态决定这次要不要发请求，一个 2s 的空转只有三次 ref 读。
   */
  useEffect(() => {
    if (!autoPoll) return
    const pollTimer = setInterval(() => {
      if (unmounted.current) return
      if (fetchInFlight.current) return
      if (!latestRef.current.shouldPollNow()) return
      // 卡住的 trace 什么都没在动，就降低访问频率再来看。
      const stalled = latestRef.current.stalledMin
      if (stalled > 0 && Date.now() - latestRef.current.lastFetchedAt < STALLED_POLL_INTERVAL_MS) {
        return
      }
      void latestRef.current.fetchSpans()
    }, POLL_INTERVAL_MS)
    return () => clearInterval(pollTimer)
  }, [autoPoll])

  // 挂载即取数（源站 onMounted）。
  useEffect(() => {
    void fetchSpans()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [knowledgeId])

  /**
   * 本次解析配置的覆盖项（源站 `fetchProcessOverrides`）：详情接口的 `metadata` 是
   * **原始 JSON 字符串**（可能 null / 坏 JSON）⇒ 纯函数容错解析，失败落回
   * 「使用知识库默认配置」。`compact`（抽屉的隐藏挂载）不取，与源站 `if (props.compact) return` 一致。
   */
  useEffect(() => {
    setProcessOverrides(null)
    setKnowledgeFileType('')
    if (compact || !knowledgeId) return
    let alive = true
    void (async () => {
      try {
        const res = await knowledgeApi.getKnowledge(knowledgeId)
        if (!alive) return
        setProcessOverrides(parseProcessOverrides(res?.metadata))
        setKnowledgeFileType(
          normalizeFileType(
            res?.file_type || getFileTypeFromName(res?.file_name || res?.title || ''),
          ),
        )
      } catch {
        if (!alive) return
        setProcessOverrides(null)
        setKnowledgeFileType('')
      }
    })()
    return () => {
      alive = false
    }
  }, [knowledgeId, compact])

  // knowledgeId 变化：清掉上一次的树与用户选择（源站 watch(props.knowledgeId)）。
  const prevKnowledgeId = useRef(knowledgeId)
  useEffect(() => {
    if (prevKnowledgeId.current === knowledgeId) return
    prevKnowledgeId.current = knowledgeId
    selectedAttemptRef.current = undefined
    setSelectedAttempt(undefined)
    setData(null)
    writeExpanded(new Set(['__root__']))
    writeSelected(null)
    userToggledRows.current = new Set()
    autoSelectPending.current = true
    setAttemptStatuses(new Map())
    // 取数由上面的 [knowledgeId] effect 触发（重置同一拍里不能用旧闭包发请求）。
  }, [knowledgeId, writeExpanded, writeSelected])

  // Esc 关闭详情（源站 :573）。
  useEffect(() => {
    const onKeydown = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape' && selectedSpanIdRef.current) {
        writeSelected(null)
        autoSelectPending.current = false
      }
    }
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  }, [writeSelected])

  // metadata 标签在没有内容时自动退回概览（源站 :1423）。
  useEffect(() => {
    if (detailTab === 'metadata' && !hasContent(selectedRow?.node.metadata)) {
      setDetailTab('overview')
    }
  }, [selectedSpanId, detailTab, selectedRow])

  // 把摘要 emit 给上游（源站 :1392 watch immediate）⇒ 抽屉能用一行状态胶囊，
  // 不用再挂第二棵树来算。
  useEffect(() => {
    emitRef.current.onSummary?.({
      totalMs,
      status: headerStatus,
      stageIndex: currentStageIndex,
      stageTotal: stages.length,
      stageLabel: currentStageLabel,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [totalMs, headerStatus, currentStageIndex, currentStageLabel, stages.length])

  // ---------------- 渲染 ----------------

  return (
    <div className={compact ? 'kp-timeline kp-compact' : 'kp-timeline'}>
      {compact ? (
        <>
          {/* 紧凑模式：五个状态点 + 一行摘要（源站 :1556-1571，卡片 hover 用的隐藏挂载）。 */}
          <div className="kp-compact-row">
            {stages.map((s) => (
              <span
                key={s.name}
                className={`kp-dot kp-dot-${s.status}`}
                title={`${stageLabel(s.name)} · ${statusLabel(s.status)}`}
              />
            ))}
          </div>
          <div className="kp-compact-caption">
            {totalMs > 0 ? (
              <span>{`总耗时：${formatDuration(totalMs)}`}</span>
            ) : (
              <>
                <span>处理流水线：</span>
                <span className="kp-stage-emph">{`${currentStageIndex}/${stages.length}`}</span>
                <span>{` · ${currentStageLabel}`}</span>
              </>
            )}
          </div>
        </>
      ) : (
        <div className="kp-shell">
          {/* ============== 头部 ============== */}
          <div className="kp-head">
            <div className="kp-head-toolbar">
              <div className="kp-head-titles">
                <div className="kp-head-overline">处理流水线</div>
                <div className="kp-head-title-row">
                  <h2 className="kp-head-doc-title" title={primaryHeadTitle}>
                    {primaryHeadTitle}
                  </h2>
                  {data && headerStatusText ? (
                    <span className="kp-head-status-tag" data-theme={headerStatusTheme}>
                      {headerStatusText}
                    </span>
                  ) : null}
                  {isLive ? (
                    <span className="kp-live-badge" title="解析进行中，每 2 秒自动刷新一次">
                      <span className="kp-live-dot" />
                      <span className="kp-live-text">LIVE</span>
                    </span>
                  ) : null}
                </div>
              </div>

              <div className="kp-head-actions">
                <span
                  className="kp-proccfg-anchor"
                  onMouseEnter={() => setShowProcessConfig(true)}
                  onMouseLeave={() => setShowProcessConfig(false)}
                >
                  <button
                    type="button"
                    className="kp-icon-btn"
                    title="本次解析配置"
                    aria-label="本次解析配置"
                    onFocus={() => setShowProcessConfig(true)}
                    onBlur={() => setShowProcessConfig(false)}
                  >
                    ⓘ
                  </button>
                  {showProcessConfig ? (
                    <div className="kp-proccfg-pop">
                      <div className="kp-proccfg-title">本次解析配置</div>
                      {configLines.map((line, index) => (
                        <div key={index} className="kp-proccfg-line">
                          {line}
                        </div>
                      ))}
                    </div>
                  ) : null}
                </span>

                <button
                  type="button"
                  className={[
                    'kp-icon-btn',
                    refreshing ? 'kp-icon-btn-spin' : '',
                    isLive && !refreshing ? 'kp-icon-btn-autoflow' : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  disabled={loading || refreshing}
                  title={isLive ? '自动刷新中' : '立即刷新'}
                  aria-label={isLive ? '自动刷新中' : '立即刷新'}
                  onClick={onManualRefresh}
                >
                  ↻
                </button>

                {canCancelParse ? (
                  <CancelParseControl
                    variant="icon"
                    confirmBody={cancelParseConfirmBody(docTitle || knowledgeId)}
                    cancelling={cancelling}
                    onConfirm={() => void onCancelParseConfirm()}
                  />
                ) : null}

                {showToolbarRetry && onRetryParse ? (
                  <button
                    type="button"
                    className="kp-btn kp-btn-danger kp-btn-outline kp-btn-small"
                    onClick={() => void onRetry()}
                  >
                    <span>↻</span>
                    <span style={{ marginLeft: 4 }}>重新解析</span>
                  </button>
                ) : null}

                {showClose ? (
                  <button
                    type="button"
                    className="kp-icon-btn"
                    aria-label="关闭"
                    title="关闭"
                    onClick={() => emitRef.current.onClose?.()}
                  >
                    ✕
                  </button>
                ) : null}
              </div>
            </div>

            {headStats.length > 0 ? (
              <div className="kp-stat-strip">
                {headStats.map((stat) => (
                  <span key={stat.key} className="kp-stat">
                    <span className="kp-stat-label">{stat.label}</span>
                    <span
                      className={[
                        'kp-stat-value',
                        !stat.mono ? 'kp-stat-value-text' : '',
                        stat.note ? 'kp-stat-note' : '',
                      ]
                        .filter(Boolean)
                        .join(' ')}
                    >
                      {stat.value}
                    </span>
                  </span>
                ))}
              </div>
            ) : null}

            {attemptTabs.length > 0 ? (
              <div className="kp-attempts">
                {attemptTabs.map((tab) => (
                  <button
                    key={tab.n}
                    type="button"
                    className={tab.active ? 'kp-attempt kp-attempt-active' : 'kp-attempt'}
                    onClick={() => onAttemptChange(tab.n)}
                  >
                    <span className="kp-attempt-num kp-mono">{`#${tab.n}`}</span>
                    <span className={`kp-attempt-glyph ${attemptGlyph(tab.status).cls}`}>
                      {attemptGlyph(tab.status).ch}
                    </span>
                  </button>
                ))}
              </div>
            ) : null}

            {stallVerdict === 'queued' ? (
              <div className="kp-stall kp-stall-queued" role="status">
                <span className="kp-stall-icon">◷</span>
                <div className="kp-stall-body">
                  <div className="kp-stall-title">{`已 ${stalledMin} 分钟没有进展，任务仍在排队`}</div>
                  <div className="kp-stall-hint">
                    这篇文档还有任务在队列中等待处理，通常是任务积压导致，稍后会自动继续，一般无需操作。
                  </div>
                </div>
              </div>
            ) : stallVerdict === 'stalled' ? (
              <div className="kp-stall" role="status">
                <span className="kp-stall-icon">◷</span>
                <div className="kp-stall-body">
                  <div className="kp-stall-title">{`已 ${stalledMin} 分钟没有进展，可能已卡住`}</div>
                  <div className="kp-stall-hint">
                    {stalledStageLabel
                      ? `当前停在「${stalledStageLabel}」阶段。可以继续等待，或停止解析后重建知识。长时间仍无进展时，系统会自动将其标记为失败。`
                      : '可以继续等待，或停止解析后重建知识。长时间仍无进展时，系统会自动将其标记为失败。'}
                  </div>
                </div>
                {canCancelParse ? (
                  <CancelParseControl
                    variant="stall"
                    confirmBody={cancelParseConfirmBody(docTitle || knowledgeId)}
                    cancelling={cancelling}
                    onConfirm={() => void onCancelParseConfirm()}
                  />
                ) : null}
              </div>
            ) : null}

            {showLastError && data?.last_error ? (
              <div className="kp-last-error" role="alert">
                <div className="kp-err-top">
                  <span className="kp-err-glyph" aria-hidden="true">
                    !
                  </span>
                  <span className="kp-err-where">{errorHeadline}</span>
                  {data.last_error.error_code ? (
                    <span className="kp-err-code kp-mono">{data.last_error.error_code}</span>
                  ) : null}
                  <button
                    type="button"
                    className="kp-err-copy"
                    title="复制错误信息"
                    aria-label="复制错误信息"
                    onClick={() => copyValue(data.last_error)}
                  >
                    ⧉
                  </button>
                </div>
                {data.last_error.error_message ? (
                  <div className="kp-err-message">{data.last_error.error_message}</div>
                ) : null}
                {localizedErrorSuggestion(data.last_error.error_code) ? (
                  <div className="kp-err-hint">
                    {localizedErrorSuggestion(data.last_error.error_code)}
                  </div>
                ) : null}
                <div className="kp-err-actions">
                  {onOpenAssistant ? (
                    <AssistantActionButton
                      label="分析原因"
                      query={`请检查文档「${docTitle || knowledgeId}」（ID：${knowledgeId}）的处理情况，结合附带的错误说明原因并建议下一步。先不要重试、重建或修改文档。`}
                      selection={{
                        field: errorHeadline,
                        text: [data.last_error.error_code, data.last_error.error_message]
                          .filter(Boolean)
                          .join('\n'),
                      }}
                      onOpenAssistant={onOpenAssistant}
                    />
                  ) : null}
                  {data.parse_status === 'failed' && onRetryParse ? (
                    <button type="button" className="kp-err-btn" onClick={() => void onRetry()}>
                      <span>↻</span>
                      <span>重新解析</span>
                    </button>
                  ) : null}
                </div>
              </div>
            ) : null}
          </div>

          {/* ============== 主体（瀑布图）============== */}
          <div className={detailOpen ? 'kp-body kp-body-with-detail' : 'kp-body'}>
            {loading && !data ? (
              <div className="kp-state">
                <span className="kp-loading" aria-label="加载中" />
              </div>
            ) : !data ? (
              <div className="kp-state kp-state-empty">
                {!lastFetchOk && fetchError ? (
                  <span>{`处理记录加载失败：${fetchError}`}</span>
                ) : (
                  <>
                    <span>暂无解析记录</span>
                    <span className="kp-state-hint">
                      这篇文档还没有处理记录。重新解析一次即可看到分阶段耗时。
                    </span>
                  </>
                )}
              </div>
            ) : (
              <>
                {/* 刻度尺在滚动区之外：时间轴不能跟着滚走，也不能在 overflow 里跟
                    position:sticky 打架。 */}
                {showRuler ? (
                  <div className="kp-ruler">
                    <div className="kp-ruler-spacer-name" />
                    <div className="kp-ruler-spacer-meta" />
                    <div className="kp-ruler-track">
                      {rulerTicks.map((tick, i) => (
                        <span
                          key={i}
                          className={[
                            'kp-tick',
                            i === 0 ? 'kp-tick-first' : '',
                            i === rulerTicks.length - 1 ? 'kp-tick-last' : '',
                          ]
                            .filter(Boolean)
                            .join(' ')}
                          style={{ left: tick.left }}
                        >
                          <span className="kp-tick-line" />
                          <span className="kp-tick-label kp-mono">{tick.label}</span>
                        </span>
                      ))}
                    </div>
                  </div>
                ) : null}

                <div ref={scrollRef} className="kp-scroll">
                  <div
                    className={showRuler ? 'kp-rows' : 'kp-rows kp-rows-gridless'}
                    style={{ '--kp-grid-step': gridStepPct } as CSSProperties}
                  >
                    {flatRows.map((row) => (
                      <div
                        key={row.key}
                        data-span-key={row.key}
                        className={[
                          'kp-row',
                          selectedSpanId === row.key ? 'kp-row-active' : '',
                          row.isRoot ? 'kp-row-root' : '',
                          row.isStage ? 'kp-row-stage' : '',
                          !row.isRoot && !row.isStage ? 'kp-row-span' : '',
                          row.hasChildren && !row.isRoot ? 'kp-row-expandable' : '',
                          isNotRun(row.node) ? 'kp-row-inert' : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                        title={
                          row.hasChildren && !row.isRoot
                            ? '点击查看详情；左侧箭头展开或收起子项'
                            : undefined
                        }
                        onClick={() => selectRow(row)}
                      >
                        <div className="kp-cell-name">
                          <div className="kp-name-inner">
                            {Array.from({ length: row.depth }).map((_, d) => (
                              <span key={d} className="kp-name-guide" aria-hidden="true" />
                            ))}
                            {row.hasChildren && !row.isRoot ? (
                              <button
                                type="button"
                                className="kp-tree-toggle"
                                aria-expanded={isRowExpanded(row.key)}
                                aria-label={treeToggleAriaLabel(row)}
                                onClick={(ev) => toggleTree(row, ev)}
                              >
                                {isRowExpanded(row.key) ? '⌄' : '›'}
                              </button>
                            ) : (
                              <span className="kp-tree-toggle-spacer" />
                            )}
                            <span
                              className={[
                                'kp-status-dot',
                                `kp-dot-${row.node.status}`,
                                isPlaceholder(row.node) ? 'kp-dot-placeholder' : '',
                              ]
                                .filter(Boolean)
                                .join(' ')}
                            />
                            <span
                              className={[
                                'kp-name-text',
                                row.isRoot ? 'kp-name-root' : '',
                                !row.isRoot && !row.isStage ? 'kp-name-mono' : '',
                              ]
                                .filter(Boolean)
                                .join(' ')}
                              title={rowLabel(row)}
                            >
                              {rowLabel(row)}
                            </span>
                            {rowKindLabel(row) ? (
                              <span className="kp-name-kind">{rowKindLabel(row)}</span>
                            ) : null}
                          </div>
                        </div>

                        <div
                          className={isNotRun(row.node) ? 'kp-cell-dur kp-dur-inert' : 'kp-cell-dur kp-mono'}
                        >
                          {isNotRun(row.node) ? (
                            <span>未执行</span>
                          ) : row.node.status === 'running' ? (
                            <span className="kp-running-time">
                              {formatDuration(liveElapsedMs(row.node))}
                            </span>
                          ) : (
                            <span
                              title={
                                postprocessElapsedMs(row.node, postprocessDisplayNow()) !== null
                                  ? '含子任务'
                                  : undefined
                              }
                            >
                              {formatSpanDuration(row.node, true)}
                            </span>
                          )}
                        </div>

                        <div className="kp-cell-bar">
                          {nowMarkerPct !== null && row.isRoot ? (
                            <span
                              className="kp-now-marker"
                              style={{ left: `${nowMarkerPct}%` }}
                            />
                          ) : null}
                          {isPlaceholder(row.node) && !isNotRun(row.node) ? (
                            <div className="kp-bar kp-bar-placeholder" />
                          ) : (
                            <>
                              {wrapStyle(row.node) ? (
                                <div
                                  className={`kp-bar-wrap kp-bar-wrap-${row.node.status}`}
                                  style={wrapStyle(row.node) ?? undefined}
                                >
                                  <span className="kp-bar-tip">
                                    <span className="kp-bar-tip-name">{rowLabel(row)}</span>
                                    <span className="kp-bar-tip-sep">·</span>
                                    <span className="kp-mono">
                                      {formatDuration(wrapDurationMs(row.node))}
                                    </span>
                                    <span className="kp-bar-tip-sep">·</span>
                                    <span>含子任务</span>
                                  </span>
                                </div>
                              ) : null}
                              <div
                                className={[
                                  'kp-bar',
                                  `kp-bar-${row.node.status}`,
                                  row.node.status === 'running' ? 'kp-bar-running-anim' : '',
                                ]
                                  .filter(Boolean)
                                  .join(' ')}
                                style={barStyle(row.node)}
                              >
                                <span className="kp-bar-tip">
                                  <span className="kp-bar-tip-name">{rowLabel(row)}</span>
                                  <span className="kp-bar-tip-sep">·</span>
                                  <span className="kp-mono">
                                    {row.node.status === 'running'
                                      ? formatDuration(liveElapsedMs(row.node))
                                      : formatSpanDuration(row.node)}
                                  </span>
                                  <span className="kp-bar-tip-sep">·</span>
                                  <span>{statusLabel(row.node.status)}</span>
                                </span>
                              </div>
                              {barOffsetPct(row.node) !== null && barOffsetMs(row.node) > 0 ? (
                                <span
                                  className="kp-bar-offset kp-mono"
                                  style={
                                    {
                                      '--kp-offset-position': `${barOffsetPct(row.node)}%`,
                                    } as CSSProperties
                                  }
                                  title={`+${formatDuration(barOffsetMs(row.node))}`}
                                >
                                  {`+${formatDuration(barOffsetMs(row.node))}`}
                                </span>
                              ) : null}
                            </>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </>
            )}
          </div>

          {/* ============== 详情面板 ============== */}
          <div className={detailOpen ? 'kp-detail kp-detail-open' : 'kp-detail'}>
            {selectedRow ? (
              <>
                <div className="kp-detail-head">
                  <div className="kp-detail-title">
                    <span
                      className={`kp-status-dot kp-detail-dot kp-dot-${selectedRow.node.status}`}
                    />
                    <span className="kp-detail-name">{rowLabel(selectedRow)}</span>
                    <span className="kp-detail-kind">{rowKindLabel(selectedRow)}</span>
                    <span className={`kp-status-chip kp-chip-${selectedRow.node.status}`}>
                      {statusLabel(selectedRow.node.status)}
                    </span>
                  </div>
                  <div className="kp-detail-actions">
                    <button
                      type="button"
                      className="kp-icon-btn"
                      title="复制详情"
                      onClick={(ev) => {
                        ev.stopPropagation()
                        copyValue(selectedRow.node)
                      }}
                    >
                      ⧉
                    </button>
                    <button
                      type="button"
                      className="kp-icon-btn"
                      title="关闭"
                      onClick={(ev) => {
                        ev.stopPropagation()
                        closeDetail()
                      }}
                    >
                      ✕
                    </button>
                  </div>
                </div>

                <div className="kp-tabs">
                  <button
                    type="button"
                    className={detailTab === 'overview' ? 'kp-tab kp-tab-active' : 'kp-tab'}
                    onClick={() => setDetailTab('overview')}
                  >
                    概览
                  </button>
                  <button
                    type="button"
                    className={[
                      'kp-tab',
                      detailTab === 'input' ? 'kp-tab-active' : '',
                      !tabHasContent('input') ? 'kp-tab-empty' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onClick={() => setDetailTab('input')}
                  >
                    输入
                  </button>
                  <button
                    type="button"
                    className={[
                      'kp-tab',
                      detailTab === 'output' ? 'kp-tab-active' : '',
                      !tabHasContent('output') ? 'kp-tab-empty' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onClick={() => setDetailTab('output')}
                  >
                    输出
                  </button>
                  {tabHasContent('metadata') ? (
                    <button
                      type="button"
                      className={detailTab === 'metadata' ? 'kp-tab kp-tab-active' : 'kp-tab'}
                      onClick={() => setDetailTab('metadata')}
                    >
                      元数据
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className={detailTab === 'raw' ? 'kp-tab kp-tab-active' : 'kp-tab'}
                    onClick={() => setDetailTab('raw')}
                  >
                    原始 JSON
                  </button>
                </div>

                <div className="kp-detail-body">
                  {detailTab === 'overview' ? (
                    <>
                      {/* 时序 */}
                      <div className="kp-section">
                        <div className="kp-section-title">时序</div>
                        <div className="kp-kv">
                          <div className="kp-kv-row">
                            <span className="kp-kv-key">开始</span>
                            <span className="kp-kv-val kp-mono">
                              {formatTime(selectedRow.node.started_at)}
                            </span>
                          </div>
                          <div className="kp-kv-row">
                            <span className="kp-kv-key">结束</span>
                            <span className="kp-kv-val kp-mono">
                              {selectedRow.node.status === 'running' ? (
                                <span className="kp-kv-running">进行中</span>
                              ) : (
                                formatTime(selectedRow.node.finished_at)
                              )}
                            </span>
                          </div>
                          <div className="kp-kv-row">
                            <span className="kp-kv-key">耗时</span>
                            <span className="kp-kv-val kp-mono">
                              {selectedRow.node.status === 'running' ? (
                                <>
                                  {formatDuration(liveElapsedMs(selectedRow.node))}
                                  <span className="kp-kv-tag-live">已用</span>
                                </>
                              ) : (
                                formatSpanDuration(selectedRow.node)
                              )}
                            </span>
                          </div>
                          {postprocessElapsedMs(selectedRow.node, postprocessDisplayNow()) !== null ? (
                            <div className="kp-kv-row">
                              <span className="kp-kv-key">含子任务</span>
                              <span className="kp-kv-val kp-mono">
                                {formatDuration(
                                  postprocessElapsedMs(selectedRow.node, postprocessDisplayNow()) ?? 0,
                                )}
                              </span>
                            </div>
                          ) : null}
                          {!selectedRow.isRoot && barOffsetMs(selectedRow.node) > 0 ? (
                            <div className="kp-kv-row">
                              <span className="kp-kv-key">起点偏移</span>
                              <span className="kp-kv-val kp-mono">
                                {`+${formatDuration(barOffsetMs(selectedRow.node))}`}
                              </span>
                            </div>
                          ) : null}
                        </div>
                      </div>

                      {/* 身份信息 */}
                      <div className="kp-section">
                        <div className="kp-section-title">身份信息</div>
                        <div className="kp-kv">
                          {identityFields(selectedRow).map((entry) => (
                            <div key={entry.key} className="kp-kv-row">
                              <span className="kp-kv-key">{entry.label}</span>
                              <span
                                className={[
                                  'kp-kv-val',
                                  entry.mono ? 'kp-mono' : '',
                                  entry.copyable ? 'kp-kv-truncate' : '',
                                ]
                                  .filter(Boolean)
                                  .join(' ')}
                              >
                                <span className="kp-kv-text">{entry.value}</span>
                                {entry.copyable ? (
                                  <button
                                    type="button"
                                    className="kp-kv-copy"
                                    title="复制"
                                    onClick={(ev) => {
                                      ev.stopPropagation()
                                      copyValue(entry.value)
                                    }}
                                  >
                                    ⧉
                                  </button>
                                ) : null}
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>

                      {traceMetadata ? (
                        <div className="kp-section">
                          <div className="kp-section-title">Trace 级元数据</div>
                          <p className="kp-section-desc">
                            记录与 Langfuse 等可观测性系统关联的辅助字段（如 trace
                            ID）。各阶段的业务入参/出参在「输入」「输出」标签中查看。
                          </p>
                          <div className="kp-kv">
                            {buildKvEntries(traceMetadata).map((entry) => (
                              <div
                                key={entry.key}
                                className="kp-kv-row kp-kv-row-multiline"
                              >
                                <span className="kp-kv-key kp-mono">{entry.key}</span>
                                <span className="kp-kv-val kp-kv-scalar">{entry.display}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      ) : null}

                      {/* 阶段分解（只在 root 上） */}
                      {selectedRow.isRoot ? (
                        <div className="kp-section">
                          <div className="kp-section-title">阶段分解</div>
                          <div className="kp-breakdown">
                            {stageBreakdown.map((s) => (
                              <div
                                key={s.name}
                                className={`kp-breakdown-row kp-breakdown-${s.status}`}
                              >
                                <span className="kp-breakdown-label">
                                  <span className={`kp-status-dot kp-dot-${s.status}`} />
                                  {s.label}
                                </span>
                                <div className="kp-breakdown-track">
                                  <div
                                    className={`kp-breakdown-bar kp-bar-${s.status}`}
                                    style={{ width: `${s.pct}%` }}
                                  />
                                </div>
                                <span className="kp-breakdown-dur kp-mono">
                                  {s.status === 'skipped' || s.status === 'pending'
                                    ? '—'
                                    : formatDuration(s.duration_ms)}
                                </span>
                              </div>
                            ))}
                          </div>
                        </div>
                      ) : null}

                      {/* 错误 */}
                      {(selectedRow.node.status === 'failed' ||
                        selectedRow.node.status === 'cancelled') &&
                      (selectedRow.node.error_code || selectedRow.node.error_message) ? (
                        <div className="kp-error-block">
                          <div className="kp-error-head">
                            <span className="kp-error-glyph">!</span>
                            <span className="kp-error-title">
                              {localizedErrorTitle(selectedRow.node.error_code) || '错误'}
                            </span>
                            {selectedRow.node.error_code ? (
                              <span className="kp-error-code kp-mono">
                                {selectedRow.node.error_code}
                              </span>
                            ) : null}
                          </div>
                          {selectedRow.node.error_message ? (
                            <pre className="kp-error-msg kp-mono">
                              {selectedRow.node.error_message}
                            </pre>
                          ) : null}
                        </div>
                      ) : null}

                      {!selectedRow.node.span_id && !selectedRow.node.started_at ? (
                        <div className="kp-detail-hint">
                          此阶段未记录详细 span，仅展示推断状态。
                        </div>
                      ) : null}
                    </>
                  ) : sectionTab !== null ? (
                    !tabHasContent(sectionTab) ? (
                      <div className="kp-detail-empty">
                        <span>
                          {sectionTab === 'metadata'
                            ? '当前 span 没有元数据。阶段与子任务的入参/出参请查看「输入」「输出」；若已接入 Langfuse，trace 级字段会显示在「概览」。'
                            : '暂无数据'}
                        </span>
                      </div>
                    ) : (
                      <div className="kp-section">
                        <div className="kp-section-bar">
                          <span className="kp-section-title">
                            {sectionTab === 'input' ? '输入' : sectionTab === 'output' ? '输出' : '元数据'}
                          </span>
                          <button
                            type="button"
                            className="kp-section-action"
                            onClick={() => copyValue(sectionValue)}
                          >
                            <span>⧉</span>
                            <span>复制</span>
                          </button>
                        </div>

                        {isObjectWithKeys(sectionValue) ? (
                          <div className="kp-kv">
                            {buildKvEntries(sectionValue).map((entry) => (
                              <div key={entry.key} className="kp-kv-row kp-kv-row-multiline">
                                <span className="kp-kv-key kp-mono">{entry.key}</span>
                                <div className="kp-kv-val kp-kv-multiline">
                                  {entry.kind === 'bool' ? (
                                    <span
                                      className={[
                                        'kp-mono',
                                        entry.raw ? 'kp-bool-true' : 'kp-bool-false',
                                      ].join(' ')}
                                    >
                                      {entry.display}
                                    </span>
                                  ) : entry.kind === 'scalar' ? (
                                    <span className="kp-kv-scalar">{entry.display}</span>
                                  ) : entry.defaultExpanded ? (
                                    <div className="kp-kv-inline">
                                      <span className="kp-kv-summary kp-mono kp-kv-summary-static">
                                        {entry.display}
                                      </span>
                                      <pre className="kp-json kp-mono">{prettyJSON(entry.raw)}</pre>
                                    </div>
                                  ) : (
                                    <div className="kp-kv-collapsible">
                                      <button
                                        type="button"
                                        className="kp-kv-toggle"
                                        onClick={(ev) => {
                                          ev.stopPropagation()
                                          toggleJsonKey(sectionTab, entry.key)
                                        }}
                                      >
                                        <span className="kp-kv-summary kp-mono">{entry.display}</span>
                                        <span className="kp-kv-toggle-label">
                                          {isJsonExpanded(sectionTab, entry.key)
                                            ? '收起 JSON'
                                            : '展开 JSON'}
                                        </span>
                                      </button>
                                      {isJsonExpanded(sectionTab, entry.key) ? (
                                        <pre className="kp-json kp-mono">
                                          {prettyJSON(entry.raw)}
                                        </pre>
                                      ) : null}
                                    </div>
                                  )}
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <pre className="kp-json kp-mono">{prettyJSON(sectionValue)}</pre>
                        )}
                      </div>
                    )
                  ) : (
                    <div className="kp-section">
                      <div className="kp-section-bar">
                        <span className="kp-section-title">原始 JSON</span>
                        <button
                          type="button"
                          className="kp-section-action"
                          onClick={() => copyValue(selectedRow.node)}
                        >
                          <span>⧉</span>
                          <span>复制</span>
                        </button>
                      </div>
                      <pre className="kp-json kp-mono kp-json-large">
                        {prettyJSON(selectedRow.node)}
                      </pre>
                    </div>
                  )}
                </div>
              </>
            ) : null}
          </div>
        </div>
      )}
    </div>
  )
}
