import type { Session } from '@/types'
import { modelNameToTag, tagToClass } from '@/data'

interface CenterTabsProps {
  sessions: Session[]
  openSessions: string[]
  centerTabId: string
  switchCenterTab: (id: string) => void
  closeTab: (id: string) => void
  createSession: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

export function CenterTabs({
  sessions,
  openSessions,
  centerTabId,
  switchCenterTab,
  closeTab,
  createSession,
}: CenterTabsProps) {
  return (
    <div className="tabs">
      {sessions
        .filter((s) => openSessions.includes(s.id))
        .map((session) => {
          const isActive = centerTabId === session.id
          return (
            <div
              key={session.id}
              className={`tab ${isActive ? 'active' : ''}`}
              onClick={() => switchCenterTab(session.id)}
              title={session.title}
            >
              <span className={`tab-tag ${tagToClass(modelNameToTag(session.model))}`}>
                {session.model}
              </span>
              <span className="tab-title">{session.title}</span>
              <span
                className="close"
                onClick={(e) => {
                  e.stopPropagation()
                  closeTab(session.id)
                }}
              >
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '10px', height: '10px' }}>
                  <path d="M3 3L9 9M9 3L3 9" />
                </svg>
              </span>
            </div>
          )
        })}
      <div className="tab add-tab" onClick={createSession} title="新建会话">
        <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '12px', height: '12px' }}>
          <path d="M6 2V10M2 6H10" />
        </svg>
      </div>
    </div>
  )
}
