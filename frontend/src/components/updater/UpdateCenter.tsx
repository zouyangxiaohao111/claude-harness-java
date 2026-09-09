import { useCallback, useEffect, useRef, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  DEFAULT_UPDATER_SOURCES,
  updateCheck,
  updateDownload,
  updateInstall,
  onUpdateProgress,
  onUpdateReady,
  type UpdateInfo,
} from '@/api/updater'

type Stage = 'idle' | 'checking' | 'found' | 'notes' | 'dl' | 'ready' | 'fail' | 'latest' | 'installing'

/**
 * 自动更新中心（自包含）：右下角悬浮「更新」入口 + 发现新版自动弹窗。
 * 状态机对应 docs/.../autoupdate.html（latest 化：对外只说“有新版本”，真实版本在说明/关于）。
 * dev(浏览器) 无 Tauri → 不渲染。
 */
export function UpdateCenter() {
  const tauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
  const [stage, setStage] = useState<Stage>('idle')
  const [current, setCurrent] = useState('')
  const [info, setInfo] = useState<UpdateInfo | null>(null)
  const [err, setErr] = useState('')
  const [dlDone, setDlDone] = useState(0)
  const [dlTotal, setDlTotal] = useState<number | null>(null)
  const [readyPath, setReadyPath] = useState('')
  const autoTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const busy = useRef(false)

  const check = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    setStage('checking')
    setErr('')
    try {
      const ver = current || (await invoke<string>('app_version'))
      setCurrent(ver)
      const found = await updateCheck(ver)
      if (found.available) {
        setInfo(found)
        setStage('found')
      } else {
        setStage('latest')
      }
    } catch (e) {
      setErr(String(e))
      setStage('fail')
    } finally {
      busy.current = false
    }
  }, [current])

  useEffect(() => {
    if (!tauri) return
    let unsub: Array<() => void> = []
    onUpdateProgress((done, total) => { setDlDone(done); setDlTotal(total ?? null) }).then((u) => unsub.push(u))
    onUpdateReady((path) => { setReadyPath(path); setStage('ready') }).then((u) => unsub.push(u))
    // 启动约 3s 后自动检查一次（只此一次；进入任何状态即取消，避免打断下载）
    autoTimer.current = setTimeout(() => void check(), 3000)
    return () => { if (autoTimer.current) clearTimeout(autoTimer.current); unsub.forEach((f) => f()) }
  }, [tauri, check])

  // 一旦离开 idle（手动打开/下载等）取消待触发的自动检查
  useEffect(() => {
    if (stage !== 'idle' && autoTimer.current) {
      clearTimeout(autoTimer.current)
      autoTimer.current = null
    }
  }, [stage])

  if (!tauri) return null

  const download = async () => {
    if (!info) return
    setStage('dl'); setDlDone(0); setDlTotal(null)
    try {
      const path = await updateDownload(info)
      setReadyPath(path); setStage('ready')
    } catch (e) { setErr(String(e)); setStage('fail') }
  }
  const install = async () => {
    if (!readyPath) return
    setStage('installing')
    try {
      await updateInstall(readyPath)
      setTimeout(() => { void getCurrentWindow().close() }, 400)
    } catch (e) { setErr(String(e)); setStage('fail') }
  }
  const close = () => { setStage('idle'); setInfo(null) }

  const open = stage !== 'idle'
  const pct = dlTotal ? Math.min(100, Math.round((dlDone / dlTotal) * 100)) : 0

  return (
    <>
      {/* 悬浮入口 */}
      <button
        className="ud-fab"
        title="检查更新"
        onClick={() => { if (stage === 'idle') void check() }}
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M12 3v11m0 0 4-4m-4 4-4-4M4 17v1a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1" />
        </svg>
      </button>

      {open && (
        <div className="overlay-backdrop" onClick={stage === 'found' || stage === 'notes' || stage === 'latest' ? close : undefined}>
          <div className="ud-dlg" onClick={(e) => e.stopPropagation()}>
            <div className="ud-head">
              <div className={`ud-ic ${stage === 'fail' ? 'warn' : stage === 'ready' || stage === 'latest' ? 'ok' : 'brand'}`}>
                {stage === 'ready' || stage === 'latest' ? '✓' : stage === 'fail' ? '!' : '↓'}
              </div>
              <div className="ud-title">
                <b>{stage === 'found' || stage === 'notes' ? '发现新版本' : stage === 'dl' ? '正在下载新版本' : stage === 'ready' ? '新版本已就绪' : stage === 'fail' ? '更新出错了' : stage === 'latest' ? '已是最新版本' : '检查更新中…'}</b>
                {info && (stage === 'found' || stage === 'notes' || stage === 'ready' || stage === 'installing') && (
                  <span className="ud-ver">{info.version}</span>
                )}
              </div>
              <button className="ud-x" onClick={close}>✕</button>
            </div>

            <div className="ud-body">
              {stage === 'checking' && <p className="ud-muted">正在对比更新源…</p>}

              {stage === 'found' && info && (
                <>
                  <p className="ud-muted">当前版本有新版本可用。更新会覆盖安装，不影响你的数据。</p>
                  <div className="ud-notes"><pre>{previewNotes(info.notes)}</pre></div>
                </>
              )}

              {stage === 'notes' && info && (
                <div className="ud-notes full"><pre>{info.notes || '（本次暂无更新说明）'}</pre></div>
              )}

              {stage === 'dl' && (
                <>
                  <div className="ud-bar"><i style={{ width: `${pct}%` }} /></div>
                  <div className="ud-meta"><span>{fmt(dlDone)} / {dlTotal ? fmt(dlTotal) : '…'}</span><span>{pct}%</span></div>
                </>
              )}

              {stage === 'ready' && (
                <p className="ud-muted">下载完成并已校验。重启后即可用上新版本——请先保存手头工作。</p>
              )}

              {stage === 'installing' && <p className="ud-muted">正在启动安装器…应用即将自动重启。</p>}

              {stage === 'latest' && <p className="ud-muted">没有更新的版本。</p>}

              {stage === 'fail' && (
                <>
                  <p className="ud-muted">检查/下载失败：{err}</p>
                  <p className="ud-muted">可到设置里修改更新源，或稍后重试。</p>
                </>
              )}
            </div>

            <div className="ud-foot">
              {(stage === 'found' || stage === 'notes') && (
                <>
                  <button className="ud-btn ghost" onClick={() => { if (stage === 'found') setStage('notes'); else setStage('found') }}>{stage === 'found' ? '查看说明' : '返回'}</button>
                  {stage === 'found' && <button className="ud-btn primary" onClick={() => void download()}>下载并更新</button>}
                  {stage === 'notes' && <button className="ud-btn primary" onClick={() => void download()}>下载并更新</button>}
                </>
              )}
              {stage === 'dl' && <button className="ud-btn ghost" onClick={close}>后台下载</button>}
              {stage === 'ready' && (
                <>
                  <button className="ud-btn ghost" onClick={close}>稍后提醒</button>
                  <button className="ud-btn primary" onClick={() => void install()}>重启并安装</button>
                </>
              )}
              {(stage === 'fail' || stage === 'latest') && (
                <>
                  <button className="ud-btn ghost" onClick={close}>关闭</button>
                  {stage === 'fail' && <button className="ud-btn primary" onClick={() => void check()}>重试</button>}
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function previewNotes(n: string) {
  const lines = (n || '').split('\n').filter(Boolean)
  return lines.slice(0, 4).join('\n') + (lines.length > 4 ? '\n…' : '')
}
function fmt(b: number) {
  const mb = b / 1024 / 1024
  return mb > 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(1)} MB`
}

// 类型补丁：确保 DEFAULT_UPDATER_SOURCES 被引用（后续设置可写回）
export const _updaterSourcesRef = DEFAULT_UPDATER_SOURCES
