import { useState } from 'react'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

interface PluginPanelProps {
  settings: AppSettings | null
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<void>
}

/**
 * 插件管理面板（设置页「环境配置」tab 内嵌模块 · V61 契约）
 * 后端 GET/PUT /api/v1/settings：
 *   - settings.enabledPlugins：插件启停映射（Record<插件ID, boolean> · enabled_plugins JSON 列，前端写）
 *   - settings.pluginClaudeFallback：插件双读开关（plugin_claude_fallback 0/1 · null=回落默认 true）
 * 注意：PUT /settings 对 enabledPlugins 是整表替换 merge（非逐 key）→ 开关 / 导入都发送完整合并 map。
 */
export function PluginPanel({ settings, onSaveSettings }: PluginPanelProps) {
  const plugins = settings?.enabledPlugins ?? {}
  const entries = Object.entries(plugins).sort(([a], [b]) => a.localeCompare(b))
  const [importText, setImportText] = useState('')
  const [importError, setImportError] = useState('')

  /** 切换单个插件开关 → 发送完整合并 map（PUT merge 整表替换，null 不覆盖其它字段） */
  const togglePlugin = (key: string, on: boolean) => {
    void onSaveSettings({ enabledPlugins: { ...plugins, [key]: on } }).catch(() => {})
  }

  /** 手动导入 CC settings 的 enabledPlugins JSON：解析 + 逐项校验 + 合并 → PUT */
  const importPlugins = () => {
    const raw = importText.trim()
    if (!raw) {
      setImportError('请先粘贴 enabledPlugins JSON')
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      setImportError('JSON 解析失败，请检查格式')
      return
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      setImportError('期望一个对象，如 {"zjkycode@zjkycode": true}')
      return
    }
    const record: Record<string, boolean> = {}
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v !== 'boolean') {
        setImportError(`字段 ${k} 的值必须是布尔值（true/false）`)
        return
      }
      record[k] = v
    }
    // 合并：导入项覆盖同名插件，其余保留现有（enabledPlugins 整表替换 → 必须带全量）
    const merged = { ...plugins, ...record }
    void onSaveSettings({ enabledPlugins: merged }).catch(() => {})
    setImportText('')
    setImportError('')
  }

  /** 直接导入 CC settings.json 文件（用户拍板 2026-09-01）：选文件 → 解析 enabledPlugins
   *  （支持整个 settings.json 或直接 enabledPlugins map）→ 合并 → PUT。保留粘贴 JSON 通道。 */
  const importSettingsFile = (file: File) => {
    const reader = new FileReader()
    reader.onload = () => {
      try {
        const text = String(reader.result ?? '')
        const parsed: unknown = JSON.parse(text)
        // 支持两种形状：整个 settings.json（取 enabledPlugins 键）或直接 enabledPlugins map
        const raw = (parsed && typeof parsed === 'object' && !Array.isArray(parsed))
          ? ((parsed as Record<string, unknown>).enabledPlugins ?? parsed)
          : parsed
        if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
          setImportError('settings.json 中未找到有效的 enabledPlugins 对象')
          return
        }
        const record: Record<string, boolean> = {}
        for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
          if (typeof v !== 'boolean') {
            setImportError(`字段 ${k} 的值必须是布尔值（true/false）`)
            return
          }
          record[k] = v
        }
        // 合并：导入项覆盖同名插件，其余保留（enabledPlugins 整表替换 → 必须带全量）
        const merged = { ...plugins, ...record }
        void onSaveSettings({ enabledPlugins: merged }).catch(() => {})
        setImportError(`已从 settings.json 导入 ${Object.keys(record).length} 个插件`)
      } catch {
        setImportError('文件解析失败，请确认是合法 JSON（settings.json）')
      }
    }
    reader.onerror = () => setImportError('文件读取失败')
    reader.readAsText(file)
  }

  return (
    <div className="envc-card">
      <div className="envc-card-title">插件</div>
      <div className="envc-desc" style={{ marginBottom: 12 }}>
        管理插件启停（enabledPlugins）：开关即时保存；也可粘贴 CC settings.json 的 enabledPlugins 一键导入合并。
      </div>

      {/* 已配置插件列表：插件ID → 开关 */}
      {entries.length === 0 ? (
        <div className="envc-row">
          <div className="envc-label-group">
            <span className="envc-name">暂无已配置插件</span>
            <span className="envc-desc">使用下方「手动导入 CC settings」粘贴 enabledPlugins JSON 添加插件</span>
          </div>
        </div>
      ) : (
        entries.map(([key, on]) => (
          <div className="envc-row" key={key}>
            <div className="envc-label-group">
              <span className="envc-name" style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}>{key}</span>
              <span className="envc-desc">{on ? '已启用' : '已禁用（显式关闭）'}</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!on}
                  onChange={(e) => togglePlugin(key, e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>
        ))
      )}

      <div className="envc-divider" />

      {/* 插件双读开关（pluginClaudeFallback）：CC settings.json 兜底双读 */}
      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">插件双读（CC settings 兜底）</span>
          <span className="envc-desc">开启后插件启停同时读 nexusai 与 CC 的 ~/.claude/settings.json（enabledPlugins 合并，nexusai 优先）；关闭则只读 nexusai（默认开启）</span>
        </div>
        <div className="envc-control">
          <label className="settings-switch">
            <input
              type="checkbox"
              checked={settings?.pluginClaudeFallback ?? true}
              onChange={(e) => void onSaveSettings({ pluginClaudeFallback: e.target.checked }).catch(() => {})}
            />
            <span></span>
          </label>
        </div>
      </div>

      <div className="envc-divider" />

      {/* 手动导入 CC settings：文本框粘贴 enabledPlugins JSON → 解析 + 合并 → PUT */}
      <div className="envc-row" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
        <div className="envc-label-group">
          <span className="envc-name">手动导入 CC settings</span>
          <span className="envc-desc">粘贴或直接选择 ~/.claude/settings.json 文件（自动提取 enabledPlugins），导入项覆盖同名插件，其余保留</span>
        </div>
        <textarea
          className="settings-input"
          style={{ width: '100%', minHeight: 88, resize: 'vertical', fontSize: 12.5, lineHeight: 1.5 }}
          placeholder='{"zjkycode@zjkycode": true}'
          value={importText}
          onChange={(e) => {
            setImportText(e.target.value)
            setImportError('')
          }}
          spellCheck={false}
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <label className="envc-save" style={{ cursor: 'pointer' }}>
            导入 settings.json 文件
            <input
              type="file"
              accept=".json,application/json"
              style={{ display: 'none' }}
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) importSettingsFile(f)
                e.target.value = ''
              }}
            />
          </label>
          <button className="envc-save" onClick={importPlugins}>导入并合并</button>
        </div>
      </div>

      {/* 本地校验失败内联提示（fail loud · 后端错误由 App onSaveSettings toast 承担） */}
      {importError && (
        <div className="envc-row">
          <span className="envc-desc" style={{ color: 'var(--error)' }}>{importError}</span>
        </div>
      )}
    </div>
  )
}
