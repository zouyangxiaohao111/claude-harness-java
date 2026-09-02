import { useEffect, useState } from 'react'
import { ApiError } from '@/api/rest'
import { projectApi, toProject, type ProjectDto } from '@/api/projects'
import { isAbsolutePath, normalizePath } from '@/utils/path'
import type { Project } from '@/types'

interface AddProjectPanelProps {
  /** App 仍按原 props 传入（mock 过滤列表）——面板数据源已切到真实 projectApi.list()，故此处不再消费 */
  availableProjects: Project[]
  addSearch: string
  setAddSearch: (v: string) => void
  closePanel: () => void
  handleBind: (p: Project) => void
}

const FolderSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
  </svg>
)

export function AddProjectPanel({
  addSearch,
  setAddSearch,
  closePanel,
  handleBind,
}: AddProjectPanelProps) {
  const [projects, setProjects] = useState<ProjectDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  // 挂载即拉取真实项目列表（替换 mock availableProjects）
  useEffect(() => {
    let alive = true
    setLoading(true)
    projectApi
      .list()
      .then((list) => { if (alive) setProjects(list) })
      .catch((e) => { if (alive) setError(e instanceof ApiError ? e.userMessage() : String(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const refresh = async () => {
    try {
      setProjects(await projectApi.list())
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    }
  }

  const q = addSearch.trim().toLowerCase()
  const filtered = projects.filter(
    (p) =>
      q === '' ||
      p.name.toLowerCase().includes(q) ||
      (p.path ?? '').toLowerCase().includes(q),
  )

  const handleCreate = async () => {
    const raw = addSearch.trim()
    if (!raw || busy) return
    // 后端已校验路径（转绝对 + 目录必须存在）：手动输入也必须绝对路径，
    //   相对路径/目录名（如「抓包流程」）会污染会话 cwd → 前端硬校验 + 明确提示
    if (!isAbsolutePath(raw)) {
      setError('请输入绝对路径，如 D:/code/ai_project/nexusai-backend')
      return
    }
    setBusy(true)
    try {
      const path = normalizePath(raw) // 反斜杠 → 正斜杠，统一后端契约
      // 输入视为路径（placeholder 即"搜索或输入项目路径"），name 取路径最后一段
      const segs = path.split('/').filter(Boolean)
      const name = segs[segs.length - 1] ?? path
      const created = await projectApi.create({ name, path })
      await refresh()
      setAddSearch('') // 清空搜索，让新项目立即可见
      handleBind(toProject(created))
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setBusy(false)
    }
  }

  const handleRemove = async (p: ProjectDto) => {
    if (busy) return
    if (!confirm(`删除项目 "${p.name}"？此操作不可恢复`)) return
    setBusy(true)
    try {
      await projectApi.remove(p.id)
      await refresh()
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="overlay-backdrop" onClick={closePanel}>
      <div className="add-project-panel" onClick={(e) => e.stopPropagation()}>
        <div className="add-panel-header">
          <span className="add-panel-title">绑定项目到当前会话</span>
          <button className="add-panel-close" onClick={closePanel}>
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '12px', height: '12px' }}>
              <path d="M3 3L9 9M9 3L3 9" />
            </svg>
          </button>
        </div>
        <div className="add-panel-search">
          <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" className="search-icon">
            <circle cx="6" cy="6" r="4" />
            <path d="M10 10L13 13" />
          </svg>
          <input
            type="text"
            placeholder="搜索或输入项目路径..."
            autoFocus
            value={addSearch}
            onChange={(e) => setAddSearch(e.target.value)}
          />
        </div>
        <div className="add-panel-list">
          {error ? (
            <div className="add-panel-empty">项目列表加载失败：{error}</div>
          ) : loading ? (
            <div className="add-panel-empty">加载中…</div>
          ) : filtered.length === 0 ? (
            <div className="add-panel-empty">
              {q ? (
                <>
                  没有匹配的项目，<span className="link" onClick={() => void handleCreate()}>创建 "{addSearch.trim()}"</span>
                </>
              ) : (
                '暂无项目，输入名称或路径创建'
              )}
            </div>
          ) : (
            filtered.map((p) => (
              <div key={p.id} className="add-panel-item" onClick={() => handleBind(toProject(p))}>
                <div className="add-panel-item-icon">
                  <FolderSvg />
                </div>
                <div className="add-panel-item-info">
                  <div className="add-panel-item-name">{p.name}</div>
                  <div className="add-panel-item-path">{p.path ?? '—'}</div>
                </div>
                <button
                  className="add-panel-close"
                  style={{ width: 20, height: 20 }}
                  title="删除项目"
                  onClick={(e) => { e.stopPropagation(); void handleRemove(p) }}
                >
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10 }}>
                    <path d="M3 3L9 9M9 3L3 9" />
                  </svg>
                </button>
                <div className="add-panel-item-badge">
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '10px', height: '10px' }}>
                    <path d="M6 2V10M2 6H10" />
                  </svg>
                  绑定
                </div>
              </div>
            ))
          )}
        </div>
        <div className="add-panel-footer">
          <span className="add-panel-hint">选择项目绑定到当前会话 · 不影响其他会话</span>
        </div>
      </div>
    </div>
  )
}
