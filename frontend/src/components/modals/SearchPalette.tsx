import type { SearchItem } from '@/types'

interface SearchPaletteProps {
  searchQuery: string
  setSearchQuery: (v: string) => void
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

export function SearchPalette({
  searchQuery,
  setSearchQuery,
  filteredItems,
  close,
  onSessionPick,
  onFilePick,
  onModelPick,
  onAddProjectPick,
  onSettingsPick,
  showToast,
}: SearchPaletteProps) {
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
            placeholder="搜索会话、项目、文件、命令..."
            autoFocus
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
          <kbd>esc</kbd>
        </div>
        <div className="search-results">
          {filteredItems.length === 0 ? (
            <div className="search-empty">没有匹配结果</div>
          ) : (
            filteredItems.map((item, i) => (
              <div
                key={`${item.type}-${item.id}-${i}`}
                className="search-item"
                onClick={() => {
                  if (item.type === 'session') onSessionPick(item.id)
                  else if (item.type === 'file') onFilePick(item.id)
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
            ))
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
