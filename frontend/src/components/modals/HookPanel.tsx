import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { hooksApi } from '@/api/hooks'
import type { HookCommandConfig, HookItem } from '@/api/types'

/** 事件名展示：SNAKE_CASE 枚举 → CC PascalCase（如 "SESSION_START" → "SessionStart"） */
const formatEvent = (event: string): string =>
  event
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join('')

/** 来源展示标签 · 对齐后端 HookSource 枚举 */
const SOURCE_LABELS: Record<string, string> = {
  USER_SETTINGS: '用户设置',
  PROJECT_SETTINGS: '项目设置',
  LOCAL_SETTINGS: '本地设置',
  POLICY_SETTINGS: '策略',
  PLUGIN_HOOK: '插件',
  SESSION_HOOK: '会话',
  BUILTIN_HOOK: '内置',
}

/** 来源优先级（高→低）· 同一事件组内排序，对齐 CC sortMatchersByPriority 语义 */
const SOURCE_PRIORITY: Record<string, number> = {
  POLICY_SETTINGS: 6,
  SESSION_HOOK: 5,
  LOCAL_SETTINGS: 4,
  PROJECT_SETTINGS: 3,
  USER_SETTINGS: 2,
  PLUGIN_HOOK: 1,
  BUILTIN_HOOK: 0,
}

/** hook 名 · 对齐 CC getHookDisplayText：优先 statusMessage，其次子类型命令串 */
const hookDisplayText = (c: HookCommandConfig): string => {
  if (c.statusMessage?.trim()) return c.statusMessage.trim()
  if (c.type === 'command' && c.command) return c.command
  if ((c.type === 'prompt' || c.type === 'agent') && c.prompt) return c.prompt
  if (c.type === 'http' && c.url) return c.url
  return c.type
}

const sourceLabel = (h: HookItem): string =>
  h.source ? SOURCE_LABELS[h.source] ?? h.source : '未知来源'

/**
 * HookPanel · FE-04 只读展示
 *
 * <p>按 hook 事件分组展示全部 hook（事件名 + hook 名 + 来源 + 组内优先级排序）。
 * <p>只读面板，不编辑；数据来自 hooksApi.getAllHooks()（后端 getAllHooks 合并多 source）。
 * <p>⚠ 后端 REST 端点尚未就绪时走显式错误态 + 重试（不静默）。
 */
export function HookPanel() {
  const [hooks, setHooks] = useState<HookItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    hooksApi
      .getAllHooks()
      .then((list) => setHooks(list ?? []))
      .catch((e) => setError(e instanceof ApiError ? e.userMessage() : String(e)))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  // 按事件分组（保持后端返回序），组内按来源优先级降序
  const groups = useMemo(() => {
    const map = new Map<string, HookItem[]>()
    for (const h of hooks) {
      const arr = map.get(h.event) ?? []
      arr.push(h)
      map.set(h.event, arr)
    }
    return [...map.entries()].map(([event, items]) => ({
      event,
      items: [...items].sort(
        (a, b) =>
          (SOURCE_PRIORITY[b.source ?? ''] ?? -1) - (SOURCE_PRIORITY[a.source ?? ''] ?? -1)
      ),
    }))
  }, [hooks])

  return (
    <div className="hook-list">
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">
            <HookGlyph />
            Hook 规则
          </div>
          <div className="settings-row-desc">
            按事件归类的 hook 配置 · 来自用户/项目/本地设置与运行时会话
            {loading && ' · 正在汇总…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
      </div>

      {loading && (
        <div className="fm-model-empty" style={{ padding: '28px 12px' }}>
          正在拉取全部 hook 规则…
        </div>
      )}

      {error && !loading && (
        <div className="fm-model-empty" style={{ padding: '24px 12px', fontStyle: 'normal' }}>
          <div style={{ color: 'var(--error)' }}>Hook 列表加载失败：{error}</div>
          <div style={{ color: 'var(--ink-faint)', marginTop: 6, fontSize: 11 }}>
            请求 GET /api/v1/hooks 失败 · 请确认后端已启动（含最新改动需重启生效）。
          </div>
          <button
            className="settings-add-btn"
            style={{ marginTop: 10 }}
            onClick={() => load()}
          >
            重试
          </button>
        </div>
      )}

      {!loading && !error && groups.length === 0 && (
        <div className="fm-model-empty" style={{ padding: '28px 12px' }}>
          暂无 hook 配置 · 有 hook 运行时，会按事件归到这里
        </div>
      )}

      {!loading && !error &&
        groups.map((g) => (
          <div key={g.event} style={{ marginBottom: 14 }}>
            <div className="schedule-name" style={{ margin: '2px 2px 6px' }}>
              {formatEvent(g.event)}
              <span className="schedule-cron">{g.items.length}</span>
            </div>
            {g.items.map((h, i) => (
              <div key={`${h.event}-${i}`} className="schedule-row" style={{ marginBottom: 4 }}>
                <div className="schedule-info">
                  <div className="schedule-name">
                    {hookDisplayText(h.config)}
                    <span
                      style={{
                        fontSize: 10.5,
                        padding: '1px 6px',
                        background: 'var(--surface-3)',
                        color: 'var(--ink-muted)',
                        borderRadius: 'var(--r-pill)',
                        fontWeight: 500,
                      }}
                    >
                      {sourceLabel(h)}
                    </span>
                  </div>
                  {(h.matcher || h.pluginName) && (
                    <div className="schedule-desc">
                      {h.matcher && (
                        <>
                          匹配 <code className="schedule-cron">{h.matcher}</code>
                        </>
                      )}
                      {h.pluginName && <> · 插件 {h.pluginName}</>}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        ))}
    </div>
  )
}

/** 钩子形状的 SVG 图标（craft 风格 · 反 emoji） */
function HookGlyph() {
  return (
    <svg
      viewBox="0 0 14 14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      style={{ width: 13, height: 13, marginRight: 6, color: 'var(--accent)' }}
    >
      <path d="M7 2V9.5M7 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5Z" />
      <path d="M4 6h6" />
      <path d="M2.5 9h9" />
    </svg>
  )
}
