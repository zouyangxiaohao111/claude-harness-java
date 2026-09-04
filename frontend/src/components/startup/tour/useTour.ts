import { useCallback, useState } from 'react'
import type { TourStep } from './TourOverlay'

/**
 * useTour — 首次引导步进轻量 hook，配合 <TourOverlay> 使用。
 *
 * 用法（真实设置面板接入示例）：
 * ```tsx
 * const tour = useTour()
 * const startTour = () => tour.start([
 *   { getTarget: () => document.querySelector('.providers-add-btn'), title: '添加提供商',
 *     desc: '点击「＋添加提供商」，填入 Base URL / API Key。', nextLabel: '下一步',
 *     canNext: () => providers.length > 0 },
 *   { getTarget: () => null, desc: '都齐了，去发第一条消息吧。', nextLabel: '开始使用' },
 * ])
 * return (<>
 *   <SettingsModal … />  // 真实面板照常渲染
 *   <TourOverlay active={tour.active} stepIndex={tour.stepIndex} steps={tour.steps}
 *                onNext={tour.next} onSkip={tour.skip} done={tour.done} />
 * </>)
 * ```
 *
 * 步进口径：
 *  - start(steps) 重置并开始；
 *  - next() 到末步后置 done=true（Overlay 显示「引导完成」卡，点“开始使用”再关闭）；
 *  - 需要“以真实操作为推进”的步骤：不给 nextLabel（Overlay 隐藏“下一步”），
 *    由父级监听真实操作完成后手动调 next()；
 *  - skip()/reset() 均为结束并清空（可随时重来）。
 */
export interface UseTourResult {
  active: boolean
  done: boolean
  stepIndex: number
  steps: TourStep[]
  /** 当前步（供父级只读展示/调试） */
  step: TourStep | undefined
  start: (steps: TourStep[]) => void
  next: () => void
  skip: () => void
  reset: () => void
}

export function useTour(): UseTourResult {
  const [active, setActive] = useState(false)
  const [done, setDone] = useState(false)
  const [stepIndex, setStepIndex] = useState(0)
  const [steps, setSteps] = useState<TourStep[]>([])

  const start = useCallback((nextSteps: TourStep[]) => {
    setSteps(nextSteps)
    setStepIndex(0)
    setDone(false)
    setActive(true)
  }, [])

  const stop = useCallback(() => {
    setActive(false)
    setDone(false)
    setStepIndex(0)
    setSteps([])
  }, [])

  const next = useCallback(() => {
    // 完成卡上点「开始使用」/ 空步骤防御 → 结束关闭
    if (done || steps.length === 0) {
      stop()
      return
    }
    setStepIndex((i) => {
      if (i >= steps.length - 1) {
        setDone(true)
        return i
      }
      return i + 1
    })
  }, [done, steps.length, stop])

  const skip = stop
  const reset = stop

  return {
    active,
    done,
    stepIndex,
    steps,
    step: steps[stepIndex],
    start,
    next,
    skip,
    reset,
  }
}
