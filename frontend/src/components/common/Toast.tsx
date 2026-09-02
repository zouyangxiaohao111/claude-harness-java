import type { Toast as ToastModel } from '@/types'

export function Toast({ toast }: { toast: ToastModel | null }) {
  if (!toast) return null
  return (
    <div className={`toast ${toast.type}`}>
      {toast.type === 'success' && (
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
          <circle cx="7" cy="7" r="6" />
          <path d="M4.5 7L6.5 9L9.5 5" />
        </svg>
      )}
      <span>{toast.msg}</span>
    </div>
  )
}
