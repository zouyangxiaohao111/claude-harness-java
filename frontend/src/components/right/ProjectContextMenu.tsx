import type { ContextMenuState, Project } from '@/types'

interface ProjectContextMenuProps {
  contextMenu: ContextMenuState | null
  mainProject: Project
  handlePromote: (p: Project) => void
  unbindProject: (name: string) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

export function ProjectContextMenu({
  contextMenu,
  mainProject,
  handlePromote,
  unbindProject,
  showToast,
}: ProjectContextMenuProps) {
  if (!contextMenu) return null
  return (
    <div
      className="project-context-menu"
      style={{ left: contextMenu.x, top: contextMenu.y }}
      onClick={() => showToast('已选择', 'info')}
    >
      {contextMenu.project.name !== mainProject.name && (
        <div className="ctx-menu-item" onClick={() => handlePromote(contextMenu.project)}>
          <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M7 2L7 12M4 5L7 2L10 5" />
          </svg>
          <span>设为主项目</span>
          <span className="shortcut">⌘⇧P</span>
        </div>
      )}
      <div className="ctx-menu-item">
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M2 7H12M7 2V12" />
        </svg>
        <span>在新 Tab 中打开</span>
      </div>
      <div className="ctx-menu-divider"></div>
      <div
        className="ctx-menu-item"
        onClick={() => showToast(`已复制路径: ${contextMenu.project.path}`, 'info')}
      >
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
          <rect x="2" y="2" width="10" height="10" rx="1" />
          <path d="M5 7H9" />
        </svg>
        <span>复制路径</span>
        <span className="shortcut">⌘⇧C</span>
      </div>
      <div className="ctx-menu-item">
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
          <rect x="2" y="2" width="10" height="10" rx="1" />
          <path d="M4 7H10M7 4V10" />
        </svg>
        <span>在 Finder 中显示</span>
      </div>
      <div className="ctx-menu-divider"></div>
      {contextMenu.project.name !== mainProject.name && (
        <div className="ctx-menu-item danger" onClick={() => unbindProject(contextMenu.project.name)}>
          <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M3 3L11 11M11 3L3 11" />
          </svg>
          <span>解除绑定</span>
        </div>
      )}
    </div>
  )
}
