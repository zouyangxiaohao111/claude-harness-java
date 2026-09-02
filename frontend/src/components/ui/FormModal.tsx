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
  | { type: 'toggle'; name: string; label: string; hint?: string }
  | { type: 'number'; name: string; label: string; placeholder?: string; min?: number; max?: number; hint?: string; nullable?: boolean }
  | { type: 'locked'; label: string; render: () => ReactNode }

export interface FormSection {
  title: string
  count?: number | string
  action?: { label: string; onClick: () => void }
  fields?: FormField[]
  rows?: FormField[][]
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

function renderField(
  field: FormField,
  value: any,
  set: (name: string, v: any) => void,
): ReactNode {
  switch (field.type) {
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
          {sections.map((section, si) => {
            const layout: FormField[][] = section.rows ?? (section.fields ?? []).map((f) => [f])
            return (
              <div key={si}>
                <div className="fm-section-title">
                  <span>{section.title}</span>
                  {section.count !== undefined && <span className="section-count">({section.count})</span>}
                  {section.action && (
                    <button className="section-action" onClick={section.action.onClick}>
                      {section.action.label}
                    </button>
                  )}
                </div>
                {layout.map((row, ri) => (
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
