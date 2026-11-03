/**
 * Generic form modal used by all settings panels — craft-studio redesign.
 * Pass section definitions + initial values; get back the edited values on save.
 *
 * Each field is one of:
 *   { type: 'text' | 'mono' | 'password', name, label, placeholder?, hint? }
 *   { type: 'textarea', name, label, placeholder?, hint?, rows? }
 *   { type: 'select', name, label, options: { value, label }[], hint? }
 *   { type: 'toggle', name, label, hint? }
 *   { type: 'number', name, label, min?, max?, hint? }
 *   { type: 'locked', label, render: () => ReactNode }   // read-only display, no value tracked
 *   { type: 'custom', name, label, hint?, render: (value: unknown, set) => ReactNode }  // 受控自定义控件
 *
 * Each section can either declare `fields` (1-per-row default) or `rows`
 * (FormField[][] for explicit side-by-side layout).
 *
 * Visual cues:
 *   - header status dot (green when 'enabled' is truthy; muted when falsy)
 *   - serif title + mono subtitle
 *   - section title with accent dot prefix
 *   - toggle renders as a pill (● 启用 / ○ 停用), not a switch
 *   - password renders with eye icon to reveal/hide
 *   - footer: destructive (left) · spacer · cancel · primary save
 */
import { useRef, useState, type ReactNode } from 'react'

export type FormField =
  | { type: 'text' | 'mono'; name: string; label: string; placeholder?: string; hint?: string }
  | { type: 'password'; name: string; label: string; placeholder?: string; hint?: string }
  | { type: 'textarea'; name: string; label: string; placeholder?: string; hint?: string; rows?: number }
  | { type: 'json'; name: string; label: string; placeholder?: string; hint?: string; rows?: number }
  | { type: 'select'; name: string; label: string; options: { value: string; label: string }[]; hint?: string }
  /**
   * 卡片式单选 · 选项铺成网格（每项一张小卡：名称 + 说明 + 选中态圆点）。
   *
   * <p><b>何时用</b>：选项本身是「**能力/概念**」而非枚举常量时（如模型类型：Chat / Vision /
   * Embedding / Rerank …）。原生 `<select>` 把它们塞进一个系统下拉里，用户要展开再逐行找，
   * 根本看不出「Embedding 和 Rerank 有什么区别」。卡片式把全部选项摊在一屏，且每项可带中文说明。
   *
   * <p>值仍走表单 state（`name` 键）⇒ 保存语义与其它字段一致。
   */
  | {
      type: 'radio-cards'
      name: string
      label: string
      options: { value: string; label: string; desc?: string }[]
      hint?: string
      /** 网格列数（默认 3）；选项少时传 2 更好看 */
      columns?: number
    }
  /**
   * 紧凑列表式单选 · 单列，每行「圆点 + 名称 + 说明」。
   *
   * <p><b>何时用</b>：与 radio-cards 同样的语义，但选项<b>很多</b>（纵向更省空间）或
   * 名称较长（卡片里会换行）。点击目标高度 26px，比 radio-cards 的 44px 小。
   */
  | {
      type: 'radio-list'
      name: string
      label: string
      options: { value: string; label: string; desc?: string }[]
      hint?: string
    }
  /**
   * 分段控件单选 · 横排 pill 按钮组（每项：主标题 + 可选副标题）。
   *
   * <p><b>何时用</b>：选项**少而短**（2–4 个）且彼此是「同类互斥的协议/模式」时
   * （协议类型：OpenAI 兼容 / Anthropic 原生 / OpenAI Responses）。此时卡片式太重、
   * 原生下拉又要展开才能看全。
   *
   * <p><b>禁用项</b>：<code>options[].disabled</code> 的项渲染为置灰不可点（用于「暂不支持」
   * 的协议模式）—— 但**仍要显示出来**，让用户知道「有这一种、只是现在不能用」，
   * 而不是把整条能力从界面上抹掉。
   */
  | {
      type: 'radio-segments'
      name: string
      label: string
      options: { value: string; label: string; desc?: string; disabled?: boolean }[]
      hint?: string
    }
  | { type: 'toggle'; name: string; label: string; hint?: string }
  | { type: 'number'; name: string; label: string; placeholder?: string; min?: number; max?: number; hint?: string; nullable?: boolean }
  | { type: 'locked'; label: string; render: () => ReactNode }
  // 受控自定义控件：值仍走表单 state（name 键），渲染交给调用方 —— 供 KvEditor 这类复合控件使用。
  // 刻意用 unknown 而非 any：调用方必须显式把 value 收窄成自己的类型，避免「忽略入参、自己拿外部
  // state」那种写法静默通过编译 —— 值一旦不在表单 state 里，保存时 payload 就会静默丢字段。
  | { type: 'custom'; name: string; label: string; hint?: string; render: (value: unknown, set: (v: unknown) => void) => ReactNode }

export interface FormSection {
  title: string
  count?: number | string
  action?: { label: string; onClick: () => void }
  fields?: FormField[]
  rows?: FormField[][]
  /**
   * 按当前表单值决定整节是否渲染（返回 false 时隐藏）。
   * 用途：同一表单按「类型」切换配置页（如 chat / embedding / rerank 各显示各自的参数）。
   */
  visibleWhen?: (values: Record<string, any>) => boolean
  /**
   * 折叠态：默认收起，点标题才展开。
   *
   * <p><b>何时用</b>：**极少用**的高级配置（如 extraBody 这种「按 key 合并进请求体」的 JSON）——
   * 一直摊开会让常见字段（名称/类型/参数）被推到很远，用户每次编辑都得往下滚。
   * 默认收起后，高级选项仍可发现（标题 + 折叠箭头），但不占版面。
   *
   * <p>折叠只影响**渲染**，字段值始终在表单 state 里 ⇒ 不会因为收起而丢数据。
   * 也可传 `{ defaultOpen: true }` 强制初始展开（例如该字段已有非空值时才展开）。
   */
  collapsible?: boolean | { defaultOpen?: boolean }
  /** 折叠态的一句话提示（收起时显示在标题右侧，告诉用户这里配的是什么） */
  collapsedHint?: string
}

interface FormModalProps<T extends Record<string, any>> {
  title: string
  subtitle?: string
  enabled?: boolean              // hide header status dot when undefined/false; default true
  initial: T
  sections: FormSection[]
  onSave: (v: T) => void
  onCancel: () => void
  extra?: ReactNode
  destructiveLabel?: string
  onDestructive?: () => void
}

const EyeOpen = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" style={{ width: 13, height: 13 }}>
    <path d="M2 7C3.5 4.5 5.2 3.2 7 3.2C8.8 3.2 10.5 4.5 12 7C10.5 9.5 8.8 10.8 7 10.8C5.2 10.8 3.5 9.5 2 7Z" />
    <circle cx="7" cy="7" r="2" />
  </svg>
)

const EyeOff = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" style={{ width: 13, height: 13 }}>
    <path d="M2 2L12 12" />
    <path d="M4.2 4.5C3.4 5.2 2.6 6 2 7C3.5 9.5 5.2 10.8 7 10.8C7.7 10.8 8.4 10.6 9 10.3" />
    <path d="M6.2 3.5C6.5 3.4 6.7 3.3 7 3.3C8.8 3.3 10.5 4.5 12 7C11.7 7.4 11.4 7.8 11 8.1" />
  </svg>
)

function PasswordField({
  value,
  placeholder,
  onChange,
}: {
  value: string
  placeholder?: string
  onChange: (v: string) => void
}) {
  const [show, setShow] = useState(false)
  return (
    <div className="fm-password-wrap">
      <input
        className="fm-input mono"
        type={show ? 'text' : 'password'}
        value={value ?? ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
      <button
        type="button"
        className="fm-password-toggle"
        onClick={() => setShow((s) => !s)}
        title={show ? '隐藏' : '显示'}
      >
        {show ? <EyeOff /> : <EyeOpen />}
      </button>
    </div>
  )
}

/**
 * 单选组渲染（radio-cards / radio-list 共用）。
 *
 * <p>两种形态共用同一套**值语义**（value = 选项 value 字符串），只有排布不同。
 * 选中态由「当前表单值 === 选项 value」推导 ⇒ 无需额外 state。
 */
function renderRadio(
  field: Extract<FormField, { type: 'radio-cards' | 'radio-list' }>,
  value: any,
  set: (name: string, v: any) => void,
): ReactNode {
  const current = value ?? ''
  const isCards = field.type === 'radio-cards'
  const cols = isCards ? (field.columns ?? 3) : 1
  return (
    <div
      className={isCards ? 'fm-radio-cards' : 'fm-radio-list'}
      style={isCards ? { gridTemplateColumns: `repeat(${cols}, 1fr)` } : undefined}
      role="radiogroup"
      aria-label={field.label}
    >
      {field.options.map((o) => {
        const on = current === o.value
        return (
          <div
            key={o.value}
            className={`fm-radio-item ${on ? 'on' : ''} ${isCards ? 'card' : 'row'}`}
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            onClick={() => set(field.name, o.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                set(field.name, o.value)
              }
            }}
          >
            <span className="fm-radio-dot" />
            <span className="fm-radio-name">{o.label}</span>
            {o.desc && <span className="fm-radio-desc">{o.desc}</span>}
          </div>
        )
      })}
    </div>
  )
}

/**
 * 分段控件渲染（radio-segments）· 横排 pill 按钮组。
 *
 * <p>与 radio-cards / radio-list 共用同一套**值语义**（value = 选项 value 字符串）。
 * 禁用项仍渲染但不可点 —— 让用户知道「有这一种模式，只是暂不支持」，而不是把它从界面抹掉。
 */
function renderSegments(
  field: Extract<FormField, { type: 'radio-segments' }>,
  value: any,
  set: (name: string, v: any) => void,
): ReactNode {
  const current = value ?? ''
  return (
    <div className="fm-segments" role="radiogroup" aria-label={field.label}>
      {field.options.map((o) => {
        const on = current === o.value
        const disabled = o.disabled === true
        return (
          <button
            key={o.value}
            type="button"
            className={`fm-segment ${on ? 'on' : ''} ${disabled ? 'disabled' : ''}`}
            role="radio"
            aria-checked={on}
            disabled={disabled}
            title={disabled ? `${o.label} · 暂不支持` : o.desc}
            onClick={() => { if (!disabled) set(field.name, o.value) }}
          >
            <span className="fm-segment-label">{o.label}</span>
            {o.desc && <span className="fm-segment-desc">{o.desc}</span>}
          </button>
        )
      })}
    </div>
  )
}

function renderField(
  field: FormField,
  value: any,
  set: (name: string, v: any) => void,
): ReactNode {
  switch (field.type) {
    case 'radio-cards':
    case 'radio-list':
      return renderRadio(field, value, set)
    case 'radio-segments':
      return renderSegments(field, value, set)
    case 'text':
      return (
        <input
          className="fm-input"
          type="text"
          value={value ?? ''}
          placeholder={field.placeholder}
          onChange={(e) => set(field.name, e.target.value)}
          autoFocus
        />
      )
    case 'mono':
      return (
        <input
          className="fm-input mono"
          type="text"
          value={value ?? ''}
          placeholder={field.placeholder}
          onChange={(e) => set(field.name, e.target.value)}
        />
      )
    case 'password':
      return (
        <PasswordField
          value={value ?? ''}
          placeholder={field.placeholder}
          onChange={(v) => set(field.name, v)}
        />
      )
    case 'textarea':
      return (
        <textarea
          className="fm-textarea"
          rows={field.rows ?? 3}
          value={value ?? ''}
          placeholder={field.placeholder}
          onChange={(e) => set(field.name, e.target.value)}
        />
      )
    case 'select':
      return (
        <select
          className="fm-select"
          value={value ?? ''}
          onChange={(e) => set(field.name, e.target.value)}
        >
          {field.options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      )
    case 'json': {
      const raw = (value ?? '') as string
      let isValid = true
      let parseError = ''
      if (raw.trim() !== '') {
        try {
          JSON.parse(raw)
        } catch (e: any) {
          isValid = false
          parseError = (e?.message ?? 'JSON 解析错误').split('\n')[0]
        }
      }
      return (
        <>
          <label className="fm-field-label">{field.label}</label>
          <textarea
            className={`fm-textarea mono ${isValid ? '' : 'invalid'}`}
            rows={field.rows ?? 3}
            value={raw}
            placeholder={field.placeholder}
            spellCheck={false}
            onChange={(e) => set(field.name, e.target.value)}
          />
          {field.hint && (
            <div className={`fm-field-hint ${isValid ? '' : 'error'}`}>
              {isValid ? field.hint : `✗ ${parseError}`}
            </div>
          )}
        </>
      )
    }
    case 'toggle': {
      const isOn = !!value
      return (
        <div className="fm-toggle-row">
          <div
            className={`toggle-pill ${isOn ? 'on' : ''}`}
            onClick={() => set(field.name, !isOn)}
            role="switch"
            aria-checked={isOn}
          >
            <span className="pill-dot"></span>
            <span>{isOn ? '启用' : '停用'}</span>
          </div>
          <div className="toggle-label-block">
            <span className="fm-field-label">{field.label}</span>
            {field.hint && <span className="fm-field-hint">{field.hint}</span>}
          </div>
        </div>
      )
    }
    case 'number': {
      const nullable = field.nullable === true
      // For nullable fields, render empty string when value is null/undefined
      // so the user can clear the input. Non-nullable defaults to 0 on clear.
      const displayValue =
        value === null || value === undefined
          ? ''
          : String(value)
      return (
        <input
          className="fm-input mono"
          type="number"
          value={displayValue}
          placeholder={field.placeholder ?? (nullable ? '不填' : '0')}
          min={field.min}
          max={field.max}
          onChange={(e) => {
            const raw = e.target.value
            if (raw === '') {
              set(field.name, nullable ? null : 0)
            } else {
              const n = Number(raw)
              set(field.name, isNaN(n) ? (nullable ? null : 0) : n)
            }
          }}
        />
      )
    }
    case 'locked': {
      // Read-only display. Renders the label + the caller-supplied JSX.
      // No value is tracked in the form state.
      return (
        <>
          <label className="fm-field-label">{field.label}</label>
          {field.render()}
        </>
      )
    }
    case 'custom': {
      // 受控自定义控件：值存在表单 state 的 field.name 键下，渲染完全交给调用方。
      // label/hint 由外层渲染（本类型不在 isFullRender 里），与普通字段观感一致。
      return field.render(value, (v) => set(field.name, v))
    }
  }
}

export function FormModal<T extends Record<string, any>>({
  title,
  subtitle,
  enabled = true,
  initial,
  sections,
  onSave,
  onCancel,
  extra,
  destructiveLabel,
  onDestructive,
}: FormModalProps<T>) {
  const [values, setValues] = useState<T>(initial)
  const set = (name: string, v: any) => setValues((p) => ({ ...p, [name]: v }))

  // 折叠态 section（如 extraBody 这类高级配置）：key 用 section 标题 ⇒ 同一表单内标题唯一即可。
  //   初始态：collapsible === true → 收起；{ defaultOpen: true } → 展开。用户点标题切换。
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    const init: Record<string, boolean> = {}
    for (const s of sections) {
      if (s.collapsible) init[s.title] = typeof s.collapsible === 'object' && !!s.collapsible.defaultOpen
    }
    return init
  })

  // header status dot: dim when 'enabled' field is explicitly false
  const dotDisabled = values['enabled'] === false

  // 防误关：只有「按下和松开都在 backdrop」才关闭（modal 内按下拖到外部松开 → 不取消）
  const backdropDownRef = useRef(false)

  return (
    <div
      className="fm-backdrop"
      onMouseDown={(e) => { backdropDownRef.current = e.target === e.currentTarget }}
      onClick={() => {
        if (!backdropDownRef.current) return
        backdropDownRef.current = false
        onCancel()
      }}
    >
      <div className="fm-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          {enabled && (
            <span className={`fm-status-dot ${dotDisabled ? 'disabled' : ''}`}></span>
          )}
          <span className="fm-title">{title}</span>
          {subtitle && <span className="fm-subtitle">{subtitle}</span>}
        </div>
        <div className="fm-body">
          {sections.filter((section) => !section.visibleWhen || section.visibleWhen(values)).map((section, si) => {
            const layout: FormField[][] = section.rows ?? (section.fields ?? []).map((f) => [f])
            const isCollapsible = !!section.collapsible
            const isOpen = isCollapsible ? !!expanded[section.title] : true
            return (
              <div
                key={si}
                className={`fm-section${isCollapsible ? ` fm-section-collapsible${isOpen ? ' open' : ''}` : ''}`}
              >
                <div className={`fm-section-title${isCollapsible ? ' collapsible' : ''}`}>
                  {isCollapsible ? (
                    <>
                      <button
                        type="button"
                        className="fm-section-toggle"
                        aria-expanded={isOpen}
                        onClick={() => setExpanded((p) => ({ ...p, [section.title]: !p[section.title] }))}
                      >
                        <svg className="fm-section-caret" viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M9 6l6 6-6 6" />
                        </svg>
                        <span>{section.title}</span>
                        {section.count !== undefined && <span className="section-count">({section.count})</span>}
                        {!isOpen && section.collapsedHint && (
                          <span className="fm-collapsed-hint">{section.collapsedHint}</span>
                        )}
                      </button>
                      {section.action && (
                        <button className="section-action" onClick={section.action.onClick}>
                          {section.action.label}
                        </button>
                      )}
                    </>
                  ) : (
                    <>
                      <span>{section.title}</span>
                      {section.count !== undefined && <span className="section-count">({section.count})</span>}
                      {section.action && (
                        <button className="section-action" onClick={section.action.onClick}>
                          {section.action.label}
                        </button>
                      )}
                    </>
                  )}
                </div>
                {isOpen && layout.map((row, ri) => (
                  <div className="fm-row" key={ri}>
                    {row.map((field) => {
                      const isFullRender = field.type === 'toggle' || field.type === 'json' || field.type === 'locked'
                      // 'locked' fields have no `name`; use label for key fallback
                      const fieldKey = field.type === 'locked' ? `locked-${field.label}` : field.name
                      return (
                        <div className="fm-field" key={fieldKey}>
                          {isFullRender ? (
                            renderField(field, field.type === 'locked' ? undefined : values[field.name], set)
                          ) : (
                            <>
                              <label className="fm-field-label">{field.label}</label>
                              {renderField(field, values[field.name], set)}
                              {field.hint && <div className="fm-field-hint">{field.hint}</div>}
                            </>
                          )}
                        </div>
                      )
                    })}
                  </div>
                ))}
              </div>
            )
          })}
          {extra}
        </div>
        <div className="fm-footer">
          {destructiveLabel && onDestructive && (
            <button className="fm-btn danger" onClick={onDestructive}>
              {destructiveLabel}
            </button>
          )}
          <span className="spacer"></span>
          {extra}
          <button className="fm-btn" onClick={onCancel}>取消</button>
          <button className="fm-btn primary" onClick={() => onSave(values)}>保存</button>
        </div>
      </div>
    </div>
  )
}
