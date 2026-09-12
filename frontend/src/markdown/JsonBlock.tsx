// 可折叠 JSON 块（对话侧；与 RPC 面板的 PayloadJson 各自独立，避免跨面板耦合）。
// 移植自 deepseek-harness/packages/client/ui-primitives/src/markdown/JsonBlock.tsx
// （MIT License, Copyright (c) 2026 DeepSeek）。
// nexusai 侧改造（2026-09-12）：CSS Module → globals.css 全局类 .json-block*；删 truncatedLabel
// prop（本仓无 i18n 设施，文案内置）。payload 无 null 守卫 —— 由调用方先挡（见 PermissionBubble）。

import { useMemo, useState } from 'react'

const MAX_CHARS = 20_000

/** 默认截断脚注（本仓无 i18n，文案内置）。 */
function truncatedLabel(total: number): string {
  return `… 已截断，共 ${total} 字符`
}

export function JsonBlock({ label, payload, defaultOpen = false }: {
  label: string
  payload: unknown
  defaultOpen?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  const body = useMemo(() => {
    if (!open) return ''
    let s: string
    try {
      // lib typing hides stringify's undefined arm (undefined/function/symbol payloads).
      // oxlint-disable-next-line typescript/no-unnecessary-condition
      s = JSON.stringify(payload, null, 2) ?? String(payload)
    } catch {
      s = String(payload)
    }
    return s.length > MAX_CHARS ? `${s.slice(0, MAX_CHARS)}\n${truncatedLabel(s.length)}` : s
  }, [open, payload])
  return (
    <div className="json-block">
      <button type="button" className="json-block-toggle" onClick={() => { setOpen(v => !v) }}>
        {open ? '▾' : '▸'} {label}
      </button>
      {open && <pre className="json-block-body">{body}</pre>}
    </div>
  )
}
