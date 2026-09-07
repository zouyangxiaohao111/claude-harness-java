import { useCallback, useEffect, useRef, useState } from 'react'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'
import { settingsApi } from '@/api/settings'
import { ApiError } from '@/api/rest'
import type { ToastType } from '@/types'

/**
 * useTierInheritGuide — 模型档位「一键套用主模型」引导弹窗（Task-B）。
 *
 * 用户语义：主模型配置好后，若目标六档（快速/子代理/弱/中等/最强/权限分类器）在 settings
 * 里全部为空 → 自然弹一次窗，询问是否把这六档一键设成与主模型一致；
 * 多模态 / 降级 / TTS / ASR 不在一键范围内（各自有独立配置），仅当它们也全部为空时，
 * 弹窗内附一句「需到 设置→模型 单独配置」的提示。
 *
 * 去重口径：
 *  - localStorage `nexusai-tier-inherit-dismissed` = 1（点「暂不」或「一键套用」成功后写入）→ 后续不再弹；
 *  - 会话内 firedRef 置位 → 同一会话只弹一次；
 *  - 首启向导（firstRunGuide.active）进行中压制本弹窗，等向导真正结束（active=false）后再判，
 *    避免「主模型刚绑定 → 此刻档位全空」与向导抢弹。
 */
export const TIER_INHERIT_DISMISSED_KEY = 'nexusai-tier-inherit-dismissed'

/** 参与一键套用的六档（settings 字段名 + 中文名 + 图标 · 与 ModelSettingsPanel 档位文案对齐） */
export const TIER_ITEMS = [
  { key: 'fastModelName', label: '快速模型', glyph: '⚡' },
  { key: 'subagentModelName', label: '子代理', glyph: '◎' },
  { key: 'weakModelName', label: '弱模型', glyph: '◇' },
  { key: 'mediumModelName', label: '中等模型', glyph: '◈' },
  { key: 'strongModelName', label: '最强模型', glyph: '◆' },
  { key: 'classifierModel', label: '权限分类器', glyph: '◬' },
] as const

/** 六档字段名（判空触发用） */
const TIER_FIELD_KEYS = TIER_ITEMS.map((t) => t.key)
/** 不参与一键、仅在全部未配置时提示的字段：多模态 / 降级 / TTS / ASR */
const EXTRA_FIELD_KEYS = ['multimodalModelName', 'fallbackModelName', 'ttsModelName', 'asrModelName'] as const

const readDismissed = (): boolean => {
  try {
    return localStorage.getItem(TIER_INHERIT_DISMISSED_KEY) === '1'
  } catch {
    return false
  }
}

const markDismissed = () => {
  try {
    localStorage.setItem(TIER_INHERIT_DISMISSED_KEY, '1')
  } catch {
    /* 隐私模式 / 存储不可用时静默：最坏只是下次再弹一次 */
  }
}

export interface UseTierInheritGuideOptions {
  appSettings: AppSettings | null
  /** 首启向导是否运行中（含完成卡阶段）。向导进行时压制本弹窗，向导结束后再判。 */
  guideActive: boolean
  showToast: (msg: string, type?: ToastType) => void
  /** settings PUT 成功后回调（App 用它把最新 settings 写回 appSettings state，让面板实时刷新） */
  onSettingsUpdated: (updated: AppSettings) => void
}

export interface TierInheritGuide {
  /** 弹窗当前是否应展示 */
  active: boolean
  /** 主模型全名（active 时必非空；供弹窗标题展示） */
  mainModelName: string | null
  /** 多模态/降级/TTS/ASR 是否全部未配置（true 时弹窗附一句单独配置提示） */
  extrasAllUnset: boolean
  /** 「一键套用」：把六档统一 PUT 成主模型，成功后写 dismissed 并关窗 + toast */
  apply: () => Promise<void>
  /** 「暂不」/ 点遮罩关闭：只写 dismissed，不改 settings */
  dismiss: () => void
}

export function useTierInheritGuide(opts: UseTierInheritGuideOptions): TierInheritGuide {
  // 每次渲染刷新 opts 引用：回调/effect 总读到最新值（对齐 useFirstRunGuide 的 refs 风格）
  const optsRef = useRef(opts)
  optsRef.current = opts

  const dismissedRef = useRef<boolean>(readDismissed())
  const firedRef = useRef(false)
  const [open, setOpen] = useState(false)

  const { appSettings } = opts
  const mainModelName = appSettings?.mainModelName?.trim() || null
  // 六档全空（appSettings 未就绪时按 false 处理，避免白屏期误弹）
  const allTiersEmpty =
    !!appSettings && TIER_FIELD_KEYS.every((f) => !appSettings?.[f])
  const extrasAllUnset =
    !!appSettings && EXTRA_FIELD_KEYS.every((f) => !appSettings?.[f])

  // 触发判定：主模型已配 & 六档全空 & 未 dismissed & 向导不在运行 → 弹一次
  const canFire =
    !dismissedRef.current &&
    !firedRef.current &&
    !opts.guideActive &&
    !!appSettings &&
    !!mainModelName &&
    allTiersEmpty

  useEffect(() => {
    if (!canFire) return
    firedRef.current = true
    setOpen(true)
  }, [canFire])

  /** 「一键套用」：单次 PUT 把六档字段都设成主模型；成功→写 dismissed、关窗、刷新 App settings、toast */
  const apply = useCallback(async () => {
    const { appSettings: s, onSettingsUpdated, showToast } = optsRef.current
    const main = s?.mainModelName?.trim()
    if (!main) return
    const req: UpdateSettingsRequest = {
      fastModelName: main,
      subagentModelName: main,
      weakModelName: main,
      mediumModelName: main,
      strongModelName: main,
      classifierModel: main,
    }
    try {
      const updated = await settingsApi.update(req)
      markDismissed()
      dismissedRef.current = true
      firedRef.current = true
      setOpen(false)
      onSettingsUpdated(updated)
      showToast('已套用：各档位已跟随主模型', 'success')
    } catch (e) {
      // fail loud：更新失败停留弹窗，便于重试（错误以 toast 呈现）
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [])

  /** 「暂不」/ 点遮罩 / X：只记 dismissed，不改 settings */
  const dismiss = useCallback(() => {
    markDismissed()
    dismissedRef.current = true
    firedRef.current = true
    setOpen(false)
  }, [])

  return {
    active: open,
    mainModelName,
    extrasAllUnset,
    apply,
    dismiss,
  }
}
