/**
 * Cross-cutting React hooks shared by App + its children.
 *
 * State strategy:
 *   - sessionReducer owns activeSession / centerTabId / openSessions
 *   - uiReducer owns transient UI flags (toast, modals, menus, search query, …)
 *   - everything else stays as useState in App
 *
 * The hooks here are side-effect free (useTheme, useClickOutside) or pure
 * dispatch helpers (useToastDispatcher).
 */

import { useCallback, useEffect, useRef } from 'react'
import type { Dispatch } from 'react'
import type { FontSize, ThemeMode, ToastType } from './types'
import type { UIAction } from './reducers'

/* ------------------------------------------------------------------ */
/*  Toast                                                              */
/* ------------------------------------------------------------------ */

/**
 * Build a `showToast(msg, type)` function bound to a UI reducer dispatch.
 *
 * <p>展示时长：成功类 2000ms；错误/信息类（含发送失败原因）<b>6000ms</b>。
 *
 * <p><b>WHY 分开计时（2026-11-01 用户反馈「发送失败一闪而逝」）</b>：此前一律 2s ——
 * 「已保存」这类一句话提示 2s 足够，但「编辑并重新发送失败：&lt;后端原因&gt;」往往两三十个字，
 * 2 秒根本读不完，用户只看到闪了一下，不知道发生了什么、更不知道该怎么处理。
 * 失败信息的价值全在「能读完」⇒ 按类型给时长，而不是一刀切。
 *
 * <p>返回值（计时器 id）可忽略：既有调用点全部按 fire-and-forget 使用。
 */
export function useToastDispatcher(dispatch: Dispatch<UIAction>) {
  return useCallback(
    (msg: string, type: ToastType = 'info') => {
      dispatch({ type: 'SHOW_TOAST', msg, toastType: type })
      window.setTimeout(() => dispatch({ type: 'HIDE_TOAST' }), type === 'success' ? 2000 : 6000)
    },
    [dispatch],
  )
}

/* ------------------------------------------------------------------ */
/*  Generic event hooks                                                */
/* ------------------------------------------------------------------ */

/**
 * Calls `onClose` when a click/touch lands outside `ref`.
 * Only attaches the listener while `active` is true.
 */
export function useClickOutside(
  ref: React.RefObject<HTMLElement | null>,
  active: boolean,
  onClose: () => void,
) {
  useEffect(() => {
    if (!active) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [ref, active, onClose])
}

/**
 * Closes any open transient panel when Escape is pressed.
 * `active` lets the hook know when it's worth attaching the listener.
 */
export function useEscapeKey(active: boolean, onClose: () => void) {
  useEffect(() => {
    if (!active) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active, onClose])
}

/**
 * Closes a popup when a click anywhere outside it is registered.
 * Used for the right-click project context menu.
 */
export function useCloseOnAway(active: boolean, onClose: () => void) {
  useEffect(() => {
    if (!active) return
    const close = () => onClose()
    window.addEventListener('click', close)
    return () => window.removeEventListener('click', close)
  }, [active, onClose])
}

/* ------------------------------------------------------------------ */
/*  Global keyboard (Cmd+K toggles palette; Esc closes panels)        */
/* ------------------------------------------------------------------ */

export function useGlobalKeys(opts: {
  onToggleSearch: () => void
  onCloseAll: () => void
}) {
  const { onToggleSearch, onCloseAll } = opts
  // Keep latest callbacks in a ref so we don't re-bind the listener.
  const cbRef = useRef(opts)
  cbRef.current = opts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        cbRef.current.onToggleSearch()
      } else if (e.key === 'Escape') {
        cbRef.current.onCloseAll()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onToggleSearch, onCloseAll])
}

/* ------------------------------------------------------------------ */
/*  Theme / fontSize / animations — apply to the .app root element    */
/* ------------------------------------------------------------------ */

function getAppRoot(): HTMLElement | null {
  return document.querySelector('.app')
}

export function useTheme(theme: ThemeMode) {
  useEffect(() => {
    const root = getAppRoot()
    if (!root) return
    const apply = (mode: 'light' | 'dark') => {
      if (mode === 'dark') root.setAttribute('data-theme', 'dark')
      else root.removeAttribute('data-theme')
    }
    if (theme === 'auto') {
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      apply(mq.matches ? 'dark' : 'light')
      const onChange = (e: MediaQueryListEvent) => apply(e.matches ? 'dark' : 'light')
      mq.addEventListener('change', onChange)
      return () => mq.removeEventListener('change', onChange)
    }
    apply(theme)
  }, [theme])
}

export function useFontSize(size: FontSize) {
  useEffect(() => {
    const root = getAppRoot()
    if (!root) return
    root.setAttribute('data-fontsize', size)
  }, [size])
}

export function useAnimations(enabled: boolean) {
  useEffect(() => {
    const root = getAppRoot()
    if (!root) return
    if (enabled) root.classList.remove('no-animations')
    else root.classList.add('no-animations')
  }, [enabled])
}
