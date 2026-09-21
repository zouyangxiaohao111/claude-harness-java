/**
 * SendUserMessage（CC BriefTool）前端载荷解析 + 轮次正文裁剪。
 *
 * <p><b>CC 真源</b>（Open-ClaudeCode v2.1.88，逐行读过）:
 * <ul>
 *   <li>工具名 {@code BRIEF_TOOL_NAME='SendUserMessage'} / 旧名 {@code LEGACY_BRIEF_TOOL_NAME='Brief'}
 *       （tools/BriefTool/prompt.ts:1-2）；UI 渲染 {@code renderToolResultMessage}（tools/BriefTool/UI.tsx:15-68）
 *       默认分支：<b>无工具边框、无 gutter 标记</b>，正文 Markdown + 附件列表
 *       （{@code [image]}/{@code [file]} + 路径 + 大小，UI.tsx:69-100 {@code AttachmentList}）。</li>
 *   <li>轮次正文裁剪 {@code dropTextInBriefTurns}（components/Messages.tsx:169-206）：
 *       轮 = 一条非 meta 的 user 消息；助手 text 记轮号；助手 tool_use 命中 SendUserMessage → 标记该轮；
 *       无命中轮则原样返回；命中轮的助手 text 丢弃（对齐「答案已在该工具里」的设计意图）。</li>
 * </ul>
 *
 * <p><b>WHY 需要本模块</b>：CC 的 SendUserMessage 是「用户的可见输出通道」——模型把答案写进它、
 * 轮内其他助手正文只是工作笔记（CC 注释逐字：{@code the model's text is working-notes that
 * duplicate the SendUserMessage content}）。本仓原先无任何 SendUserMessage 特殊渲染 ⇒ 该工具落成
 * 一张无名折叠卡（{@code userFacingName()=''} 接不住 ⇒ 标题空），答案被埋。
 *
 * <p><b>载荷双源（前端）</b>：
 * <ol>
 *   <li>结构化 output（后端 {@code tool_calls.result} · 投递时实时推送 + 重拉同源）=
 *       {@code {message, attachments:[{path,size,isImage}], sentAt}} —— 与 CC {@code Output} 同形。</li>
 *   <li>回落 {@code tool.arguments}（轮询/实时早期、以及<b>历史行</b>：改动前落库的 result 是人类文案
 *       {@code 'Message delivered to user. …'}）→ 取 {@code arguments.message} 与原始附件路径串。</li>
 * </ol>
 * 双源保证：拿不到结构化载荷时<b>不空白</b>，仍能渲染正文（附件退化为无大小的路径列表）。
 */
import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/** CC 工具名 + 旧名（prompt.ts:1-2）；历史 transcript 里的 {@code Brief} 也须识别。 */
const BRIEF_TOOL_NAMES = ['SendUserMessage', 'Brief']

/** 该 tool_call 是否 SendUserMessage（含旧名 Brief）。 */
export function isSendUserMessage(tool: { name?: string | null } | null | undefined): boolean {
  const name = tool?.name
  return name != null && BRIEF_TOOL_NAMES.includes(name)
}

/** 附件（对齐 CC Output.attachments 元素：path/size/isImage；size/isImage 缺失=null 表示仅知路径）。 */
export interface BriefAttachment {
  path: string
  size: number | null
  isImage: boolean | null
}

/** SendUserMessage 的可见载荷（message 正文 + 附件列表）。 */
export interface BriefPayload {
  message: string
  attachments: BriefAttachment[]
}

function parseJsonObject(raw: string | null | undefined): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const v: unknown = JSON.parse(raw)
    return v != null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/** 结构化 output → BriefPayload；非本工具载荷（无 message 且无附件）→ null。 */
function fromStructured(result: string | null | undefined): BriefPayload | null {
  const o = parseJsonObject(result)
  if (!o) return null
  const message = typeof o.message === 'string' ? o.message : ''
  const attachments: BriefAttachment[] = []
  if (Array.isArray(o.attachments)) {
    for (const item of o.attachments) {
      if (item != null && typeof item === 'object' && typeof (item as { path?: unknown }).path === 'string') {
        const a = item as { path: string; size?: unknown; isImage?: unknown }
        attachments.push({
          path: a.path,
          size: typeof a.size === 'number' ? a.size : null,
          isImage: typeof a.isImage === 'boolean' ? a.isImage : null,
        })
      }
    }
  }
  if (message === '' && attachments.length === 0) return null
  return { message, attachments }
}

/** 回落：工具入参的 message + 原始附件路径（无 size/isImage —— 入参只有路径字符串）。 */
function fromArguments(raw: string | null | undefined): BriefPayload | null {
  const o = parseJsonObject(raw)
  if (!o) return null
  const message = typeof o.message === 'string' ? o.message : ''
  const attachments: BriefAttachment[] = Array.isArray(o.attachments)
    ? o.attachments
        .filter((p): p is string => typeof p === 'string')
        .map((path) => ({ path, size: null, isImage: null }))
    : []
  if (message === '' && attachments.length === 0) return null
  return { message, attachments }
}

/**
 * 失败态文案 · 对齐 CC {@code FallbackToolUseErrorMessage.tsx:30-48} + 调度点
 * {@code UserToolResultMessage.tsx:71-86}（{@code if (param.is_error) return <UserToolErrorMessage/>}）。
 *
 * <p><b>WHY 必须有这一层</b>：CC 里 is_error 的 tool_result <b>从不</b>走
 * {@code renderToolResultMessage} —— BriefTool 也没定义 {@code renderToolUseErrorMessage}
 * （BriefTool.ts:185-186 只导出 renderToolUseMessage/renderToolResultMessage）⇒ 落
 * FallbackToolUseErrorMessage 的红字。本仓若只按 {@code arguments} 兜底渲染正文，
 * 附件 TOCTOU 失败（文件在 validate 与 execute 之间被移走）会被<b>渲染成投递成功</b>：
 * 正文照出、附件行退化成无大小的 {@code [file]} 路径，用户看不到任何失败信号。
 *
 * <p>文案取值同 CC：非 string（null / 未产出）→ {@code 'Tool execution failed'}；否则原样展示
 * （后端 error 串已带 {@code 'Error: '} 前缀，见 BriefTool.execute 错误分支）。
 *
 * @returns 失败文案；非失败态 → null
 */
export function briefErrorMessage(tool: ToolCallDto): string | null {
  if (tool.isError !== true) return null
  const r = tool.result
  return typeof r === 'string' && r.trim() !== '' ? r : 'Tool execution failed'
}

/**
 * 解析一条 SendUserMessage 调用的可见载荷（结构化 output 优先，arguments 兜底）；非本工具 → null。
 *
 * <p><b>失败态一律 null</b>（对齐 CC：is_error 的 tool_result 不渲染工具 output）——
 * 否则 arguments 兜底会把「没投递出去的消息」当正文渲染成投递成功（见 {@link #briefErrorMessage}）。
 */
export function briefPayload(tool: ToolCallDto): BriefPayload | null {
  if (!isSendUserMessage(tool)) return null
  if (tool.isError === true) return null
  return fromStructured(tool.result) ?? fromArguments(tool.arguments)
}

/**
 * 含 SendUserMessage 的轮次里丢掉冗余助手正文 · 逐条对齐 CC
 * {@code components/Messages.tsx:169-206 dropTextInBriefTurns}。
 *
 * <p><b>与 CC 的差异（本仓结构决定，非随意发明）</b>：CC 的归一化消息是「一块一消息」，故直接
 * {@code filter} 掉 text 消息；本仓一条 assistant 消息<b>同时</b>承载正文与 toolCalls，删行会连
 * 工具卡一起丢掉 ⇒ 命中轮只清 {@code content}（正文消失、工具卡保留），轮外消息原样返回。
 *
 * <p><b>轮边界与 CC 同源</b>：CC 用「非 meta 的 user 消息且首块非 tool_result」；本仓 tool_result
 * 是 {@code role='tool'} 消息（不是 user）⇒ 结构上等价，判据只需 {@code role==='user' && !isMeta}。
 *
 * @returns 命中轮正文被清空后的消息数组（无命中轮则<b>原引用</b>返回）
 */
export function dropTextInBriefTurns(messages: ChatMessageDto[]): ChatMessageDto[] {
  const briefTurns = new Set<number>()
  const textTurn = new Map<number, number>()
  let turn = 0
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i]!
    if (m.role === 'user' && m.isMeta !== true) {
      turn++
      continue
    }
    if (m.role !== 'assistant') continue
    if (m.content) textTurn.set(i, turn)
    if ((m.toolCalls ?? []).some((t) => isSendUserMessage(t))) briefTurns.add(turn)
  }
  if (briefTurns.size === 0) return messages
  return messages.map((m, i) => {
    const t = textTurn.get(i)
    if (t === undefined || !briefTurns.has(t)) return m
    return { ...m, content: '' }
  })
}

/** 流式块是否处于「本轮调用了 SendUserMessage」态（实时侧 dropText 判据 · CC 同语义）。 */
export function isBriefBlock(toolCalls: { name?: string | null }[] | null | undefined): boolean {
  return (toolCalls ?? []).some((t) => isSendUserMessage(t))
}
