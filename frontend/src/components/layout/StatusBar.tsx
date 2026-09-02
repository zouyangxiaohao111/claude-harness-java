import { useChatStore } from '@/stores/chatStore'
import type { Project } from '@/types'

const FolderSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
  </svg>
)

/** WS 连接徽标元信息（对齐 chatStore.connection 四态：idle 为尚未建立连接）。 */
const CONN_META = {
  connected: { text: '已连接', color: 'var(--success)' },
  connecting: { text: '连接中', color: 'var(--warning)' },
  disconnected: { text: '已断开', color: 'var(--error)' },
  idle: { text: '未连接', color: 'var(--ink-faint)' },
} as const

export function StatusBar({ mainProject }: { mainProject: Project }) {
  const connection = useChatStore((s) => s.connection)
  const conn = CONN_META[connection]
  return (
    <div className="statusbar">
      <div className="item">
        <FolderSvg />
        <span>{mainProject.name}</span>
        <span style={{ color: 'var(--ink-faint)' }}>·</span>
        <span>{mainProject.branch}</span>
      </div>
      <div className="right">
        <div className="item" title={connection === 'disconnected' ? '实时连接断开，STOMP 自动重连中…' : '实时连接（STOMP）'}>
          <span className="dot" style={{ background: conn.color }}></span>
          <span>{conn.text}</span>
        </div>
        <div className="item">{mainProject.agents} agents 运行中</div>
      </div>
    </div>
  )
}
