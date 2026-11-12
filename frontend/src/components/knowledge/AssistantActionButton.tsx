/**
 * 「带 query 直接问助手」按钮 —— 复刻源站
 * `frontend/src/components/assistant/AssistantActionButton.vue`（26 行）。
 *
 * <h2>源站行为</h2>
 * `askAssistant()` 三步：
 * 1. `assistant.setPageSelection(selection.text.trim() ? { ...selection } : null)`
 * 2. `assistant.restorePageContext()`
 * 3. `assistant.ask(props.query)`
 *
 * <h2>外观</h2>
 * 类名与源站 `AssistantButton.vue` 逐字一致（`assistant-button` / `is-icon-only` /
 * `data-assistant-ignore` / `.resource-icon.assistant-resource-icon` / `__label`），
 * 样式已在 `front/src/styles/kb/kb-shared.css:893-945` 写全 ⇒ 本文件**不新增任何 CSS**。
 * 本仓已有的同款 DOM 见 `KbDocDrawer.tsx:1267-1277`。
 *
 * <h2>已知偏离（如实登记）</h2>
 * - 与源站偏离：pageAccess 判据在本仓无对应物，故省略。
 *   源站模板是 `<AssistantButton v-if="assistant.pageAccess" …>`，本仓没有「页面是否挂载助手」
 *   的授权/挂载状态 ⇒ 无条件渲染，由宿主决定什么时候放这个按钮。
 * - 与源站偏离：selection 不走全局 store（本仓没有 `setPageSelection` / `restorePageContext`
 *   这套页面上下文机制），改为通过 `onOpenAssistant(question, context)` 的**第二个参数**传出
 *   归一化后的文本，由宿主拼进提问内容。
 * - `title` 仅在 `iconOnly` 时给出 —— 与源站 `:title="iconOnly ? label : undefined"` 一致；
 *   `aria-label` 两个分支都给（源站 `:aria-label="label"`）。
 */

/** 页面选中片段（`field` = 选区来源字段，`text` = 选中文本；对应源站 `AssistantPageSelection`）。 */
export interface AssistantSelection {
  field: string
  text: string
}

export interface AssistantActionButtonProps {
  /** 按钮文案（`iconOnly` 时作 title / aria-label） */
  label: string
  /** 点击后发给助手的问题 */
  query: string
  /** 可选的页面选区；`text` 为空/纯空白时等价于「没有上下文」 */
  selection?: AssistantSelection
  /** 只显示星形图标（抽屉头部 28×28 形态） */
  iconOnly?: boolean
  disabled?: boolean
  /** 宿主接管「打开助手并提问」；`context` 已 trim，无有效选区时为 undefined */
  onOpenAssistant: (question: string, context?: string) => void
}

/**
 * 选区归一化（纯函数，便于直接测）。
 *
 * 源站判据是 `props.selection?.text.trim() ? { ...props.selection } : null`：
 * 只按 **trim 后是否为空** 决定「有没有上下文」，非空时给出 trim 过后的文本。
 */
export function resolveAssistantContext(
  selection?: AssistantSelection | null,
): string | undefined {
  const text = selection?.text
  if (typeof text !== 'string') return undefined
  const trimmed = text.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

export function AssistantActionButton({
  label,
  query,
  selection,
  iconOnly,
  disabled,
  onOpenAssistant,
}: AssistantActionButtonProps) {
  return (
    <button
      type="button"
      className={`assistant-button${iconOnly ? ' is-icon-only' : ''}`}
      disabled={disabled}
      aria-label={label}
      title={iconOnly ? label : undefined}
      data-assistant-ignore=""
      onClick={() => onOpenAssistant(query, resolveAssistantContext(selection))}
    >
      <span className="resource-icon assistant-resource-icon" aria-hidden="true" />
      {!iconOnly && <span className="assistant-button__label">{label}</span>}
    </button>
  )
}
