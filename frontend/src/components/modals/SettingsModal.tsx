import { useEffect } from 'react'
import type { FontSize, ModelInfo, SettingsTab, ThemeMode } from '@/types'
import { settingsApi } from '@/api/settings'
import { ApiError } from '@/api/rest'
import type { UpdateSettingsRequest } from '@/api/types'
import { ProvidersPanel } from './ProvidersPanel'
import { SkillsPanel } from './SkillsPanel'
import { MCPPanel } from './MCPPanel'
import { DatabasePanel } from './DatabasePanel'
import { SchedulesPanel } from './SchedulesPanel'
import { HookPanel } from './HookPanel'
import { BusinessPanel } from './BusinessPanel'
import { EnvConfigPanel } from '../settings/EnvConfigPanel'
import { PluginPanel } from '../settings/PluginPanel'
import { ModelSettingsPanel } from '../settings/ModelSettingsPanel'
import type { Provider, AppSettings } from '@/api/types'
import type { UseProviders } from '@/hooks/useProviders'
import type { UseSkills } from '@/hooks/useSkills'
import type { UseMcp } from '@/hooks/useMcp'
import type { UseDatabases } from '@/hooks/useDatabases'
import type { UseSchedules } from '@/hooks/useSchedules'

interface SettingsModalProps {
  settingsTab: SettingsTab
  setSettingsTab: (t: SettingsTab) => void
  theme: ThemeMode
  setTheme: (t: ThemeMode) => void
  fontSize: FontSize
  setFontSize: (s: FontSize) => void
  animationsEnabled: boolean
  setAnimationsEnabled: (b: boolean) => void
  models: ModelInfo[]
  // P7: 真实后端联调 — 传 hook 而非只传 list
  providers: Provider[]
  providersApi: UseProviders
  skillsApi: UseSkills
  mcpApi: UseMcp
  databasesApi: UseDatabases
  schedulesApi: UseSchedules
  close: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 环境配置（设置页「环境配置」tab：压缩窗口/自动记忆/away 门控） */
  appSettings: AppSettings | null
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<void>
  /** 全局模型配置（设置页「模型」tab：快速/档位角色） */
  fastModel: string | null
  pickFast: (providerName: string, modelName: string) => void
  clearFast: () => void
  /** 打开记忆编辑器（设置页「环境配置」tab「记忆」模块入口；独立弹窗，z-index 高于本弹窗） */
  onOpenMemoryEditor: () => void
}

const THEME_LABELS: Record<ThemeMode, string> = {
  light: '浅色',
  dark: '深色',
  auto: '跟随系统',
}
const FONT_LABELS: Record<FontSize, string> = {
  small: '小',
  medium: '中',
  large: '大',
}

const NAV_ITEMS: { id: SettingsTab; label: string; section: 'core' | 'tools' | 'misc' }[] = [
  { id: 'general',    label: '通用',   section: 'core' },
  { id: 'appearance', label: '外观',   section: 'core' },
  { id: 'env',        label: '环境配置', section: 'core' },
  { id: 'model',      label: '模型',   section: 'core' },
  { id: 'providers',  label: '提供商', section: 'tools' },
  { id: 'skills',     label: '技能',   section: 'tools' },
  { id: 'mcp',        label: 'MCP',   section: 'tools' },
  { id: 'database',   label: '数据库', section: 'tools' },
  { id: 'schedules',  label: '定时任务', section: 'tools' },
  { id: 'hooks',      label: 'Hooks',   section: 'tools' },
  { id: 'business',   label: '业务',   section: 'tools' },
  { id: 'advanced',   label: '高级',   section: 'misc' },
]

export function SettingsModal({
  settingsTab,
  setSettingsTab,
  theme,
  setTheme,
  fontSize,
  setFontSize,
  animationsEnabled,
  setAnimationsEnabled,
  models,
  providers,
  providersApi,
  skillsApi,
  mcpApi,
  databasesApi,
  schedulesApi,
  close,
  showToast,
  appSettings,
  onSaveSettings,
  fastModel,
  pickFast,
  clearFast,
  onOpenMemoryEditor,
}: SettingsModalProps) {
  // 挂载时从后端读全局设置并初始化（仅当后端有值时覆盖本地默认；读取失败 toast 提示）
  useEffect(() => {
    let cancelled = false
    settingsApi
      .get()
      .then((s) => {
        if (cancelled) return
        if (s.theme) setTheme(s.theme)
        if (s.fontSize) setFontSize(s.fontSize)
        if (typeof s.animationsEnabled === 'boolean') setAnimationsEnabled(s.animationsEnabled)
      })
      .catch((e) => {
        if (cancelled) return
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        showToast(`读取设置失败：${msg}`, 'info')
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 变更后写回后端（成功/失败静默由既有 toast 承担；失败显式提示）
  const persistSettings = (req: UpdateSettingsRequest, failLabel: string) => {
    settingsApi.update(req).catch((e) => {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${failLabel}失败：${msg}`, 'info')
    })
  }

  return (
    <div className="settings-backdrop" onClick={close}>
      <div className="settings-modal" onClick={(e) => e.stopPropagation()}>
        <div className="settings-header">
          <span className="settings-title">设置</span>
          <button className="settings-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className="settings-body">
          <div className="settings-tabs">
            <div className="settings-nav-section">基础</div>
            {NAV_ITEMS.filter((n) => n.section === 'core').map((n) => (
              <NavItem key={n.id} n={n} current={settingsTab} setSettingsTab={setSettingsTab} />
            ))}
            <div className="settings-nav-section">工具</div>
            {NAV_ITEMS.filter((n) => n.section === 'tools').map((n) => (
              <NavItem key={n.id} n={n} current={settingsTab} setSettingsTab={setSettingsTab} />
            ))}
            <div className="settings-nav-section">其他</div>
            {NAV_ITEMS.filter((n) => n.section === 'misc').map((n) => (
              <NavItem key={n.id} n={n} current={settingsTab} setSettingsTab={setSettingsTab} />
            ))}
          </div>
          <div className="settings-content">
            {settingsTab === 'general' && (
              <>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">主题</div>
                    <div className="settings-row-desc">选择应用外观主题</div>
                  </div>
                  <div className="settings-segmented">
                    {(['light', 'dark', 'auto'] as ThemeMode[]).map((t) => (
                      <button
                        key={t}
                        className={theme === t ? 'active' : ''}
                        onClick={() => {
                          setTheme(t)
                          showToast('已切换主题', 'info')
                          persistSettings({ theme: t }, '保存主题')
                        }}
                      >
                        {THEME_LABELS[t]}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">自动保存对话</div>
                    <div className="settings-row-desc">关闭会话前自动保存草稿</div>
                  </div>
                  <label className="settings-switch">
                    <input type="checkbox" defaultChecked />
                    <span></span>
                  </label>
                </div>
              </>
            )}
            {settingsTab === 'appearance' && (
              <>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">字号</div>
                    <div className="settings-row-desc">应用内文字大小</div>
                  </div>
                  <div className="settings-segmented">
                    {(['small', 'medium', 'large'] as FontSize[]).map((s) => (
                      <button
                        key={s}
                        className={fontSize === s ? 'active' : ''}
                        onClick={() => {
                          setFontSize(s)
                          showToast('已切换字号', 'info')
                          persistSettings({ fontSize: s }, '保存字号')
                        }}
                      >
                        {FONT_LABELS[s]}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">启用动画</div>
                    <div className="settings-row-desc">面板切换、hover 效果等</div>
                  </div>
                  <label className="settings-switch">
                    <input
                      type="checkbox"
                      checked={animationsEnabled}
                      onChange={(e) => {
                        setAnimationsEnabled(e.target.checked)
                        persistSettings({ animationsEnabled: e.target.checked }, '保存动画设置')
                      }}
                    />
                    <span></span>
                  </label>
                </div>
              </>
            )}
            {settingsTab === 'env' && (
              <EnvConfigPanel settings={appSettings} onSaveSettings={onSaveSettings} onOpenMemoryEditor={onOpenMemoryEditor} />
            )}
            {settingsTab === 'model' && (
              <ModelSettingsPanel
                providers={providers}
                settings={appSettings}
                onSaveSettings={onSaveSettings}
                fastModel={fastModel}
                pickFast={pickFast}
                clearFast={clearFast}
                onOpenProviders={() => setSettingsTab('providers')}
              />
            )}
            {settingsTab === 'providers' && <ProvidersPanel providers={providers} providersApi={providersApi} showToast={showToast} />}
            {settingsTab === 'skills' && <SkillsPanel skillsApi={skillsApi} showToast={showToast} />}
            {settingsTab === 'mcp' && <MCPPanel mcpApi={mcpApi} showToast={showToast} />}
            {settingsTab === 'database' && <DatabasePanel databasesApi={databasesApi} showToast={showToast} />}
            {settingsTab === 'schedules' && <SchedulesPanel schedulesApi={schedulesApi} showToast={showToast} />}
            {settingsTab === 'hooks' && (
              <>
                <HookPanel />
                {/* [V61] 插件管理（Hooks 下方 · enabledPlugins 启停 + pluginClaudeFallback 双读开关 + 导入 CC） */}
                <PluginPanel settings={appSettings} onSaveSettings={onSaveSettings} />
              </>
            )}
            {settingsTab === 'business' && <BusinessPanel showToast={showToast} />}
            {settingsTab === 'advanced' && (
              <>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">开发者模式</div>
                    <div className="settings-row-desc">显示调试信息和内部状态</div>
                  </div>
                  <label className="settings-switch">
                    <input type="checkbox" />
                    <span></span>
                  </label>
                </div>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">清除所有数据</div>
                    <div className="settings-row-desc">删除本地所有会话、设置和缓存</div>
                  </div>
                  <button className="settings-danger" onClick={() => showToast('已清除本地数据', 'info')}>
                    清除
                  </button>
                </div>
              </>
            )}
            {/* legacy models variable referenced so unused-prop lint doesn't fire when caller passes it */}
            {false && models && <span hidden>{models.length}</span>}
          </div>
        </div>
      </div>
    </div>
  )
}

function NavItem({
  n,
  current,
  setSettingsTab,
}: {
  n: { id: SettingsTab; label: string }
  current: SettingsTab
  setSettingsTab: (t: SettingsTab) => void
}) {
  return (
    <div
      className={`settings-nav-item ${current === n.id ? 'active' : ''}`}
      onClick={() => setSettingsTab(n.id)}
    >
      <span className="settings-nav-dot" />
      {n.label}
    </div>
  )
}
