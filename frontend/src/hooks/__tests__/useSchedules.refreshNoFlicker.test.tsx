// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSchedules, type UseSchedules } from '../useSchedules'
import { scheduleApi } from '@/api/schedules'
import type { Schedule } from '@/api/types'

/**
 * ⭐ 守「轮询刷新不能让页面闪」这个**意图**（规则九）。
 *
 * <b>为什么这条测试重要</b>（WHY）：本 hook 每 2s 轮询一次。若每次刷新都把
 * `loading` 置 true 再置 false，任务页顶部那行「加载中…」会**每两秒出现一次再消失**
 * —— 内容上下跳一下，就是用户报的「页面会闪烁」。刷新拿的是同一份数据的新版本，
 * 应当原地更新，只有**首次**（还没有任何数据）才显示加载态。
 *
 * ⇒ 断言：第二次刷新**在途**时 `loading` 仍为 false。
 * 旧实现（`refresh` 开头无条件 `setLoading(true)`）会让它变成 true ⇒ 测试红。
 *
 * <p>⚠️ 必须用「挂起的 promise」制造在途态 —— 若 `await` 整个 refresh 再断言，
 * 中间那次 `loading=true` 的渲染会被批处理吞掉，测试对旧实现也会绿（无判别力）。
 */
vi.mock('@/api/schedules', () => ({
  scheduleApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    runNow: vi.fn(),
  },
}))

const listMock = scheduleApi.list as unknown as ReturnType<typeof vi.fn>

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

let latest: UseSchedules | null = null

function Probe() {
  latest = useSchedules(null)
  return null
}

describe('useSchedules · 轮询刷新不触发加载态', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    latest = null
    listMock.mockReset()
    container = document.createElement('div')
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
  })

  it('第二次刷新在途时不回到 loading（否则任务页每 2 秒闪一次）', async () => {
    // ① 首次加载：真正需要加载态
    const first = deferred<Schedule[]>()
    listMock.mockImplementationOnce(() => first.promise)
    await act(async () => {
      root.render(<Probe />)
    })
    expect(latest!.loading).toBe(true)
    await act(async () => {
      first.resolve([])
      await first.promise
    })
    expect(latest!.loading).toBe(false)

    // ② 轮询刷新：数据在途时也不能变成「加载中…」
    const second = deferred<Schedule[]>()
    listMock.mockImplementationOnce(() => second.promise)
    await act(async () => {
      void latest!.refresh()
    })
    expect(latest!.loading).toBe(false)

    await act(async () => {
      second.resolve([])
      await second.promise
    })
    expect(latest!.loading).toBe(false)
  })
})
