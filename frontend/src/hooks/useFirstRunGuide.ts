import { useCallback, useEffect, useRef } from 'react'
import type { AppSettings, Provider } from '@/api/types'
import type { SettingsTab } from '@/types'
import { useTour } from '@/components/startup/tour/useTour'
import type { TourStep } from '@/components/startup/tour/TourOverlay'

/**
 * useFirstRunGuide — L4b：把 Spotlight 首启引导接进「真实设置面板」（提供商 → 添加模型 → 模型档位 顺序向导）。
 *
 * 触发口径：全新安装且从未配过提供商（providers 空 & 未 onboarded & 主模型未绑）时，
 * 自动打开「设置 → 提供商」并启动向导。老用户 / 已 onboarded / providers 非空绝不弹。
 *
 * 推进口径（L4b-2 补「添加模型」步）：
 *  - 提供商步：无「下一步」按钮，靠用户真实保存提供商（ProvidersPanel 成功派发
 *    `nexusai:provider-saved`，detail=新建 Provider）；
 *  - 保存后若该提供商已自带模型（防御）→ 直接进模型档位；否则切回「提供商」tab 补一步
 *    spotlight「＋添加模型」（无下一步，靠 `nexusai:model-added` 真实推进）；
 *  - 模型步：主模型卡需真实绑定（读 settings.mainModelName），其余档位卡可选讲解；
 *  - 完成/跳过：写一次 localStorage `nexusai-onboarded` 并关闭设置弹窗。
 */
export const ONBOARDED_KEY = 'nexusai-onboarded'

/** 打开设置弹窗后，等待弹窗/面板渲染出真实控件再启动向导（settings-modal 入场动画约 200ms） */
const GUIDE_OPEN_DELAY_MS = 250

export interface UseFirstRunGuideOptions {
  providers: Provider[]
  providersLoading: boolean
  providersError: string | null
  appSettings: AppSettings | null
  /** 设置弹窗当前是否打开（向导自动打开设置后，用户手动关掉 → 结束向导） */
  settingsOpen: boolean
  /** 打开设置并停靠到指定 tab（需保证：设置已开时只切 tab、未开时才打开） */
  openSettingsAt: (tab: SettingsTab) => void
  /** 关闭设置弹窗 */
  closeSettings: () => void
}

export interface FirstRunGuide {
  active: boolean
  done: boolean
  stepIndex: number
  steps: TourStep[]
  onNext: () => void
  onSkip: () => void
}

const readOnboarded = (): boolean => {
  try {
    return localStorage.getItem(ONBOARDED_KEY) === '1'
  } catch {
    return false
  }
}

const markOnboarded = () => {
  try {
    localStorage.setItem(ONBOARDED_KEY, '1')
  } catch {
    /* 隐私模式/存储不可用时静默：最坏只是下次再弹一次引导 */
  }
}

export function useFirstRunGuide(opts: UseFirstRunGuideOptions): FirstRunGuide {
  const tour = useTour()

  // refs：一次性注册的监听/定时器始终读到最新值，避免因依赖变化反复解绑/重注册
  const openSettingsAtRef = useRef(opts.openSettingsAt)
  openSettingsAtRef.current = opts.openSettingsAt
  const closeSettingsRef = useRef(opts.closeSettings)
  closeSettingsRef.current = opts.closeSettings
  const appSettingsRef = useRef(opts.appSettings)
  appSettingsRef.current = opts.appSettings
  const settingsOpenRef = useRef(opts.settingsOpen)
  settingsOpenRef.current = opts.settingsOpen
  const tourRef = useRef(tour)
  tourRef.current = tour

  const launchedRef = useRef(false)
  /** 当前是否停在「初始提供商步」（index 0，steps=[providerStep]）——只在此步处理 provider-saved */
  const atInitialStepRef = useRef(false)
  /** 当前是否停在「补首个模型」步——只在此步处理 model-added */
  const needModelStepRef = useRef(false)

  /** ① 提供商步：真实操作为推进（不给 nextLabel → Overlay 无「下一步」按钮） */
  const buildProviderStep = useCallback((): TourStep => ({
    getTarget: () => document.querySelector('[data-tour="add-provider"]'),
    title: '先添加一个提供商',
    desc: '对话能力需要一个模型提供商来驱动。点上方「＋ 添加提供商」，填好名称、Base URL 与 API Key 并保存 —— 保存成功后我们继续添加模型并绑定档位。',
  }), [])

  /** ② 补首个模型步（仅在刚保存的提供商无 models 时插入）：真实添加模型→保存 为推进 */
  const buildAddModelStep = useCallback((): TourStep => ({
    getTarget: () => document.querySelector('[data-tour="add-model"]'),
    title: '给提供商添加第一个模型',
    desc: '这个提供商还没有可用的模型。点它卡片上的「＋ 添加模型」，把「模型名称」填成你账号真实支持的模型 id，例如 claude-sonnet-4-5 / deepseek-chat。保存后我带你绑定档位。',
  }), [])

  /** ③ 模型档位步（主/最强/快速/弱/子代理）：canNext 每次渲染求值时读 ref → settings 变化后 App 重渲染即刷新禁用态 */
  const buildModelPhaseSteps = useCallback((): TourStep[] => [
    {
      getTarget: () => document.querySelector('[data-tour="role-main"]'),
      title: '绑定主模型',
      desc: '主模型是日常对话的默认主力。先点一下这张「主模型」卡片，再从下方模型列表点选一个模型，看到「已配置」后即可继续。',
      nextLabel: '下一步',
      canNext: () => !!appSettingsRef.current?.mainModelName,
    },
    {
      getTarget: () => document.querySelector('[data-tour="role-strong"]'),
      title: '最强模型（可选）',
      desc: '遇到复杂规划或高难度推理时，可以让它来负责。点卡片再点选一个模型即可绑定；暂时不配也行。',
      nextLabel: '下一步',
    },
    {
      getTarget: () => document.querySelector('[data-tour="role-fast"]'),
      title: '快速模型（可选）',
      desc: '命名、摘要、轻量查询这类「跑腿活儿」用它更省更快。可以现在配，也可以以后在设置里补。',
      nextLabel: '下一步',
    },
    {
      getTarget: () => document.querySelector('[data-tour="role-weak"]'),
      title: '弱模型（可选）',
      desc: '给「快速回答」这类轻量场景准备的小模型。想省 token 就配一个，否则跳过即可。',
      nextLabel: '下一步',
    },
    {
      getTarget: () => document.querySelector('[data-tour="role-sub"]'),
      title: '子代理（可选）',
      desc: '被派去独立干活的小助手默认用这个模型跑。可配可不配 —— 点完「下一步」引导就完成了。',
      nextLabel: '下一步',
    },
  ], [])

  /** ②+③：补模型步 + 模型档位步（无 models 分支用） */
  const buildAddModelPhaseSteps = useCallback(
    (): TourStep[] => [buildAddModelStep(), ...buildModelPhaseSteps()],
    [buildAddModelStep, buildModelPhaseSteps],
  )

  // 首次触发：全新安装（providers 空 & 未 onboarded & 主模型未绑 & 后端已就绪无错误）→ 打开提供商 tab 并启动
  const readyToLaunch =
    !opts.providersLoading &&
    !opts.providersError &&
    opts.providers.length === 0 &&
    !!opts.appSettings &&
    !opts.appSettings.mainModelName &&
    !readOnboarded()

  useEffect(() => {
    if (!readyToLaunch || launchedRef.current) return
    openSettingsAtRef.current('providers')
    const t = window.setTimeout(() => {
      // StrictMode 下 effect 会「setup→cleanup→setup」连跑两次：launchedRef 必须在真正
      // 启动时（定时器触发）才置位，否则第一次 setup 置位会让第二次 setup 直接 return → 永不启动。
      // 等待窗口期若用户已手动关掉设置 → 本次不再启动（下次启动仍会引导）
      if (launchedRef.current || !settingsOpenRef.current) return
      launchedRef.current = true
      atInitialStepRef.current = true
      needModelStepRef.current = false
      tourRef.current.start([buildProviderStep()])
    }, GUIDE_OPEN_DELAY_MS)
    return () => window.clearTimeout(t)
  }, [readyToLaunch, buildProviderStep])

  // 用户手动关闭设置弹窗（X / Esc）而向导仍激活 → 直接结束向导（不写完成标记，
  // 下次启动时 providers 若仍为空会再次引导，符合「全新安装 → 弹」）
  useEffect(() => {
    const t = tourRef.current
    if (t.active && !t.done && !opts.settingsOpen) {
      atInitialStepRef.current = false
      needModelStepRef.current = false
      t.skip()
    }
  }, [opts.settingsOpen])

  // 提供商真实保存成功：仍在初始提供商步才处理。
  //  - detail.models 非空（自带模型/后端自动发现）→ 直接进模型档位（切「模型」tab）；
  //  - 否则补「添加模型」步（停「提供商」tab，spotlight 该 provider 行的 +添加模型）。
  useEffect(() => {
    const onProviderSaved = (e: Event) => {
      const t = tourRef.current
      if (!t.active || t.done) return
      if (t.stepIndex !== 0 || !atInitialStepRef.current) return
      atInitialStepRef.current = false
      const detail = (e as CustomEvent<Provider | undefined>).detail
      const hasModels = !!detail && Array.isArray(detail.models) && detail.models.length > 0
      if (hasModels) {
        needModelStepRef.current = false
        openSettingsAtRef.current('model')
        t.start(buildModelPhaseSteps())
      } else {
        needModelStepRef.current = true
        openSettingsAtRef.current('providers')
        t.start(buildAddModelPhaseSteps())
      }
    }
    window.addEventListener('nexusai:provider-saved', onProviderSaved)
    return () => window.removeEventListener('nexusai:provider-saved', onProviderSaved)
  }, [buildModelPhaseSteps, buildAddModelPhaseSteps])

  // 模型真实创建成功：停在「补模型」步才处理 → 切「模型」tab 并推进到主模型绑定
  useEffect(() => {
    const onModelAdded = () => {
      const t = tourRef.current
      if (!t.active || t.done) return
      if (!needModelStepRef.current) return
      needModelStepRef.current = false
      openSettingsAtRef.current('model')
      t.next()
    }
    window.addEventListener('nexusai:model-added', onModelAdded)
    return () => window.removeEventListener('nexusai:model-added', onModelAdded)
  }, [])

  const onNext = useCallback(() => {
    const t = tourRef.current
    if (t.done) {
      // 完成卡「开始使用」：写一次完成标记并关闭设置
      markOnboarded()
      atInitialStepRef.current = false
      needModelStepRef.current = false
      closeSettingsRef.current()
    }
    t.next()
  }, [])

  const onSkip = useCallback(() => {
    markOnboarded()
    atInitialStepRef.current = false
    needModelStepRef.current = false
    closeSettingsRef.current()
    tourRef.current.skip()
  }, [])

  return {
    active: tour.active,
    done: tour.done,
    stepIndex: tour.stepIndex,
    steps: tour.steps,
    onNext,
    onSkip,
  }
}
