import type { SearchItem } from '@/types'
import type { SessionDto } from '@/api/types'

interface SearchPaletteProps {
  searchQuery: string
  setSearchQuery: (v: string) => void
  /** 会话搜索（后端模糊 + 分页）：本页命中 / 总数 / 当前页 / 是否加载中 / 错误文案。 */
  sessionHits: SessionDto[]
  sessionTotal: number
  sessionPage: number
  sessionPageSize: number
  sessionLoading: boolean
  sessionError: string | null
  onSessionPage: (page: number) => void
  /** 非会话结果（命令 / 项目 / 文件；会话已由后端真搜索承担）。 */
  filteredItems: SearchItem[]
  close: () => void
  onSessionPick: (id: string) => void
  onFilePick: (id: string) => void
  onModelPick: () => void
  onAddProjectPick: () => void
  onSettingsPick: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const TYPE_LABELS: Record<SearchItem['type'], string> = {
  session: '会话',
  project: '项目',
  file: '文件',
  command: '命令',
}

/** 会话副行：消息条数 · 最近活跃（解析失败只显示能显示的部分）。 */
function sessionSub(s: SessionDto): string {
  const parts: string[] = []
  if (s.messageCount != null) parts.push(`${s.messageCount} 条消息`)
  if (s.updatedAt) {
    const d = new Date(s.updatedAt)
    if (!Number.isNaN(d.getTime())) {
      parts.push(d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }))
    }
  }
  return parts.join(' · ')
}

export function SearchPalette({
  searchQuery,
  setSearchQuery,
  sessionHits,
  sessionTotal,
  sessionPage,
  sessionPageSize,
  sessionLoading,
  sessionError,
  onSessionPage,
  filteredItems,
  close,
  onSessionPick,
  onFilePick,
  onModelPick,
  onAddProjectPick,
  onSettingsPick,
  showToast,
}: SearchPaletteProps) {
  const totalPages = Math.max(1, Math.ceil(sessionTotal / Math.max(1, sessionPageSize)))
  const page = Math.min(Math.max(1, sessionPage), totalPages)

  return (
    <div
      className="search-backdrop"
      onClick={() => {
        close()
        setSearchQuery('')
      }}
    >
      <div className="search-palette" onClick={(e) => e.stopPropagation()}>
        <div className="search-header">
          <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" className="search-icon">
            <circle cx="6" cy="6" r="4" />
            <path d="M10 10L13 13" />
          </svg>
          <input
            type="text"
            placeholder="搜索会话标题 / 会话 ID / 命令..."
            autoFocus
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
          <kbd>esc</kbd>
        </div>
        <div className="search-results">
          <div className="search-section-head">
            会话<span className="search-section-count">{sessionTotal}</span>
          </div>
          {sessionLoading ? (
            <div className="search-empty">搜索中…</div>
          ) : sessionError ? (
            <div className="search-empty">{sessionError}</div>
          ) : sessionHits.length === 0 ? (
            <div className="search-empty">没有匹配的会话</div>
          ) : (
            sessionHits.map((s) => (
              <div
                key={s.id}
                className="search-item"
                title={s.id}
                onClick={() => {
                  onSessionPick(s.id)
                  close()
                  setSearchQuery('')
                }}
              >
                <span className="search-item-type">会话</span>
                <div className="search-item-info">
                  <span className="search-item-title">{s.title?.trim() || '未命名会话'}</span>
                  <span className="search-item-sub">{sessionSub(s)}</span>
                </div>
                {s.model && <span className="search-item-tag">{s.model}</span>}
              </div>
            ))
          )}
          {!sessionLoading && !sessionError && sessionTotal > sessionPageSize && (
            <div className="search-pager">
              <button
                type="button"
                className="search-pager-btn"
                disabled={page <= 1}
                onClick={() => onSessionPage(page - 1)}
              >
                上一页
              </button>
              <span className="search-pager-info">第 {page} / {totalPages} 页 · 共 {sessionTotal} 条</span>
              <button
                type="button"
                className="search-pager-btn"
                disabled={page >= totalPages}
                onClick={() => onSessionPage(page + 1)}
              >
                下一页
              </button>
            </div>
          )}

          {filteredItems.length > 0 && (
            <>
              <div className="search-section-head">其它</div>
              {filteredItems.map((item, i) => (
                <div
                  key={`${item.type}-${item.id}-${i}`}
                  className="search-item"
                  onClick={() => {
                    if (item.type === 'file') onFilePick(item.id)
                    else if (item.id === 'switch-model') onModelPick()
                    else if (item.id === 'bind-project') onAddProjectPick()
                    else if (item.id === 'open-settings') onSettingsPick()
                    else showToast(`执行: ${item.title}`, 'info')
                    close()
                    setSearchQuery('')
                  }}
                >
                  <span className="search-item-type">{TYPE_LABELS[item.type]}</span>
                  <div className="search-item-info">
                    <span className="search-item-title">{item.title}</span>
                    <span className="search-item-sub">{item.sub}</span>
                  </div>
                  <span className="search-item-tag">{item.tag}</span>
                </div>
              ))}
            </>
          )}
        </div>
        <div className="search-footer">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> 选择
          </span>
          <span>
            <kbd>↵</kbd> 打开
          </span>
          <span>
            <kbd>esc</kbd> 关闭
          </span>
        </div>
      </div>
    </div>
  )
}
