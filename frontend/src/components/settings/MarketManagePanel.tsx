import { useCallback, useEffect, useState } from 'react'
import { marketApi, type MarketManagedPlugin, type MarketVersionEntry } from '@/api/market'
import { ApiError } from '@/api/rest'
import { selectProjectFolder } from '@/utils/projectFolder'

/**
 * 设置页「市场管理」：上架本地技能 / 查看版本历史 / 回滚。
 *
 * <p>解决的问题（用户 2026-10-21）：同一个技能内容更新后指纹会变，市场原先只按名字存一条，
 * 版本一多就分不清「当前是哪版、旧版在哪、能不能回滚」。本页把三样东西摆在一起：
 * 当前生效版本、内容指纹、全部历史版本（可一键回滚，后端只改清单指向、不删包）。
 *
 * <p>版本口径：上架时手填；留空则对当前版本 patch +1（0.1.0 → 0.1.1）。
 * 内容指纹没变 → 后端判 unchanged，不产生新版本。
 */
export function MarketManagePanel({ showToast }: {
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [plugins, setPlugins] = useState<MarketManagedPlugin[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  // 上架表单
  const [dir, setDir] = useState('')
  const [name, setName] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<'skill' | 'expert' | 'connector'>('skill')
  const [tags, setTags] = useState('')
  const [version, setVersion] = useState('')
  const [publishing, setPublishing] = useState(false)
  const [rollbacking, setRollbacking] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const m = await marketApi.manageList()
      setPlugins(m.plugins ?? [])
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.userMessage() : String(e))
      setPlugins([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const pickDir = async () => {
    const sel = await selectProjectFolder('选择要上架的技能目录')
    if (!sel) return
    setDir(sel.path)
    if (!name) {
      // 目录名即默认条目名（与后端一致：name 留空时同样取目录名）
      const last = sel.path.split(/[\\/]/).filter(Boolean).pop() ?? ''
      setName(last)
    }
  }

  const publish = async () => {
    if (!dir.trim()) {
      showToast('请先选择要上架的目录', 'info')
      return
    }
    setPublishing(true)
    try {
      const r = await marketApi.publishPlugin({
        sourcePath: dir.trim(),
        name: name.trim() || undefined,
        displayName: displayName.trim() || undefined,
        description: description.trim() || undefined,
        category,
        tags: tags.split(/[,，\s]+/).map((t) => t.trim()).filter(Boolean),
        version: version.trim() || undefined,
      })
      if (r.status === 'ok') {
        showToast(`已上架 ${r.name} ${r.version}`, 'success')
        setVersion('')
        await load()
      } else if (r.status === 'unchanged') {
        showToast(`内容没有变化，未创建新版本（当前 ${r.version}）`, 'info')
      } else {
        showToast(r.message || '上架失败', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setPublishing(false)
    }
  }

  const rollback = async (pluginName: string, v: string) => {
    setRollbacking(`${pluginName}@${v}`)
    try {
      const r = await marketApi.rollbackPlugin(pluginName, v)
      if (r.status === 'ok') {
        showToast(`已回滚 ${pluginName} → ${v}`, 'success')
        await load()
      } else {
        showToast(r.message || '回滚失败', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setRollbacking(null)
    }
  }

  const toggle = (n: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(n)) next.delete(n)
      else next.add(n)
      return next
    })
  }

  return (
    <div className="mm-root">
      {/* ===== 上架 ===== */}
      <div className="mm-section-title">上架技能</div>
      <div className="mm-form">
        <div className="mm-field mm-field-wide">
          <label>本地目录</label>
          <div className="mm-inline">
            <input className="settings-input mm-input" value={dir} onChange={(e) => setDir(e.target.value)}
              placeholder="C:\Users\...\.nexusai\skills\my-skill" />
            <button className="mm-btn" onClick={() => void pickDir()}>选择目录…</button>
          </div>
          <div className="mm-hint">目录里要有 SKILL.md（或 plugin.json / agents/ / skills/）</div>
        </div>

        <div className="mm-field">
          <label>市场名称</label>
          <input className="settings-input mm-input" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="留空取目录名" />
        </div>
        <div className="mm-field">
          <label>展示名称</label>
          <input className="settings-input mm-input" value={displayName} onChange={(e) => setDisplayName(e.target.value)}
            placeholder="技能卡片上显示的名字" />
        </div>
        <div className="mm-field">
          <label>分类</label>
          <select className="settings-input mm-input" value={category}
            onChange={(e) => setCategory(e.target.value as 'skill' | 'expert' | 'connector')}>
            <option value="skill">技能</option>
            <option value="expert">专家</option>
            <option value="connector">连接器</option>
          </select>
        </div>
        <div className="mm-field">
          <label>版本</label>
          <input className="settings-input mm-input" value={version} onChange={(e) => setVersion(e.target.value)}
            placeholder="留空自动 +1" />
        </div>
        <div className="mm-field mm-field-wide">
          <label>描述</label>
          <input className="settings-input mm-input" value={description} onChange={(e) => setDescription(e.target.value)}
            placeholder="一句话说明这个技能做什么" />
        </div>
        <div className="mm-field mm-field-wide">
          <label>标签</label>
          <input className="settings-input mm-input" value={tags} onChange={(e) => setTags(e.target.value)}
            placeholder="逗号或空格分隔，如：写作 文档 规范" />
        </div>

        <div className="mm-actions">
          <button className="mm-btn mm-btn-primary" disabled={publishing} onClick={() => void publish()}>
            {publishing ? '上架中…' : '上架到市场'}
          </button>
        </div>
      </div>

      {/* ===== 已上架 ===== */}
      <div className="mm-section-title mm-section-title-row">
        <span>已上架 {plugins.length}</span>
        <button className="mm-btn mm-btn-ghost" onClick={() => void load()} disabled={loading}>
          {loading ? '刷新中…' : '刷新'}
        </button>
      </div>

      {loadError && <div className="mm-error">{loadError}</div>}

      {!loading && !loadError && plugins.length === 0 && (
        <div className="mm-empty">市场里还没有插件，先在上面上架一个</div>
      )}

      <div className="mm-list">
        {plugins.map((p) => {
          const open = expanded.has(p.name)
          const versions = p.versions ?? []
          return (
            <div key={p.name} className="mm-card">
              <div className="mm-card-head" onClick={() => toggle(p.name)}>
                <span className={`mm-caret${open ? ' open' : ''}`}>▸</span>
                <span className="mm-card-name">{p.displayName || p.name}</span>
                <span className="mm-badge">{p.category || 'skill'}</span>
                <span className="mm-version">v{p.version || '—'}</span>
                <span className="mm-fp" title={p.fingerprint || ''}>
                  {(p.fingerprint || '').replace('sha256:', '').slice(0, 10) || '无指纹'}
                </span>
                <span className="mm-time">{p.updatedAt || ''}</span>
                <span className="mm-count">{versions.length} 个版本</span>
              </div>

              {open && (
                <div className="mm-versions">
                  {versions.length === 0 && <div className="mm-hint">没有版本记录（旧数据）</div>}
                  {versions.map((v: MarketVersionEntry) => {
                    const current = v.version === p.version
                    return (
                      <div key={v.version} className={`mm-version-row${current ? ' current' : ''}`}>
                        <span className="mm-version">v{v.version}</span>
                        <span className="mm-fp" title={v.fingerprint}>
                          {v.fingerprint.replace('sha256:', '').slice(0, 10)}
                        </span>
                        <span className="mm-time">{v.publishedAt}</span>
                        <span className="mm-size">{v.size ? `${(v.size / 1024).toFixed(1)} KB` : ''}</span>
                        {current
                          ? <span className="mm-current-mark">当前版本</span>
                          : (
                            <button
                              className="mm-btn mm-btn-ghost"
                              disabled={rollbacking === `${p.name}@${v.version}`}
                              onClick={() => void rollback(p.name, v.version)}
                            >
                              {rollbacking === `${p.name}@${v.version}` ? '回滚中…' : '回滚到此版本'}
                            </button>
                          )}
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
