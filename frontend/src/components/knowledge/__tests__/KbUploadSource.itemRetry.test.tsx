// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  knowledgeApi,
  type Knowledge,
  type UploadManyItem,
  type UploadManyResult,
} from '@/api/knowledge'
import { KbUploadSource } from '../KbUploadSource'

/**
 * `KbUploadSource` 面板的三条**接线**判据：item 级重试只重跑一条、409 重复单列、
 * 上传完成后面板显示解析完成度。
 *
 * <h2>WHY 必须真的渲染并点一次，而不是只测 `knowledgeApi.uploadMany`</h2>
 * 这三条都坏在**接线**上，纯函数层永远测不出来：
 * <ol>
 *   <li><b>item 级重试</b>：`retryOne(key)` 若把整个 `tracked` 都喂回并发池，
 *       纯函数全对、按钮也渲染了，但「点一条重试 ⇒ 两个文件同时被重传」——
 *       对后端就是重复 409、对用户是「明明只点了一个」。只有点击后核对
 *       `uploadMany` 收到的 items 才能抓住。</li>
 *   <li><b>409 重复</b>：若重复项混进失败清单，界面会多出「重试」按钮，
 *       而这个按钮点几次都只会再 409 —— 必须断言「没有重试入口」。</li>
 *   <li><b>解析完成度</b>：头部的「（解析完成 y）」依赖轮询回填的 id 集合，
 *       接线断了就永远不显示（或恒 0），而列表刷新等其它行为全部正常。</li>
 * </ol>
 *
 * <p>环境与写法沿用本仓既有模式（jsdom + `createRoot` + `act`，不引入
 * `@testing-library/react`）：见 `markdown/__tests__/jsonBlock.test.tsx`。
 */

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  vi.restoreAllMocks()
})

/** 让挂起的 promise 链落地（上传 → setState → 轮询启动）。 */
async function flush(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

async function mount() {
  const showToast = vi.fn()
  const onDone = vi.fn()
  await act(async () => {
    root.render(
      <KbUploadSource baseId="kb-1" folderPath="" showToast={showToast} onDone={onDone} />
    )
  })
  return { showToast, onDone }
}

/** 模拟用户在隐藏 input 上选中文件（`handleFiles` 读的就是 `e.target.files`）。 */
function fileInput(): HTMLInputElement {
  return container.querySelectorAll<HTMLInputElement>('input[type="file"]')[0]
}

async function pickFiles(names: string[]): Promise<void> {
  const input = fileInput()
  const files = names.map((name) => new File([name], name))
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  await act(async () => {
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
  await flush()
}

function failResult(items: UploadManyItem[], message: string): UploadManyResult {
  return {
    ok: 0,
    results: [],
    duplicates: [],
    cancelled: [],
    failures: items.map((item) => ({ name: item.fileName || item.file.name, message, item })),
    settled: items.map((item) => ({
      item,
      name: item.fileName || item.file.name,
      state: 'failed' as const,
      message,
    })),
  }
}

describe('KbUploadSource · item 级重试', () => {
  it('⭐ 点某一条的「重试」只重跑那一条（不是把整批再传一遍）', async () => {
    const calls: string[][] = []
    vi.spyOn(knowledgeApi, 'uploadMany').mockImplementation(async (_baseId, items) => {
      calls.push(items.map((i) => i.file.name))
      return failResult(items, '磁盘忙')
    })

    await mount()
    await pickFiles(['a.pdf', 'b.pdf'])

    expect(calls).toEqual([['a.pdf', 'b.pdf']])
    // 两条都失败 ⇒ 每条一个「重试」按钮 + 底部「重试失败项」
    const retryButtons = container.querySelectorAll<HTMLButtonElement>('.kb-upload-item-retry')
    expect(retryButtons).toHaveLength(2)

    await act(async () => {
      retryButtons[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    // ⭐ 判据：第二次调用只带 a.pdf 这一条
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual(['a.pdf'])
  })

  it('「重试失败项」把全部失败项一起重跑（上一批的能力保留）', async () => {
    const calls: string[][] = []
    vi.spyOn(knowledgeApi, 'uploadMany').mockImplementation(async (_baseId, items) => {
      calls.push(items.map((i) => i.file.name))
      return failResult(items, '磁盘忙')
    })

    await mount()
    await pickFiles(['a.pdf', 'b.pdf'])

    const allButton = container.querySelector<HTMLButtonElement>('.kb-upload-retry')
    expect(allButton?.textContent).toContain('重试失败项（2）')
    await act(async () => {
      allButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual(['a.pdf', 'b.pdf'])
  })
})

describe('KbUploadSource · 409 重复', () => {
  it('⭐ 重复项单列「重复（已存在）」、不进失败清单、不给重试按钮', async () => {
    vi.spyOn(knowledgeApi, 'uploadMany').mockImplementation(async (_baseId, items) => ({
      ok: 0,
      results: [],
      failures: [],
      cancelled: [],
      duplicates: items.map((item) => ({
        name: item.fileName || item.file.name,
        message: '同名或同内容文件已存在',
        item,
      })),
      settled: items.map((item) => ({
        item,
        name: item.fileName || item.file.name,
        state: 'duplicate' as const,
        message: '同名或同内容文件已存在',
      })),
    }))

    await mount()
    await pickFiles(['dup.pdf'])
    const text = container.textContent ?? ''

    expect(text).toContain('1 个重复')
    expect(text).toContain('重复（已存在）')
    // ⛔ 不计失败：否则会多出一个必然再 409 的重试按钮
    expect(text).not.toContain('个失败')
    expect(container.querySelectorAll('.kb-upload-item-retry')).toHaveLength(0)
  })
})

describe('KbUploadSource · 取消全部', () => {
  it('⭐ 上传中点「取消全部」⇒ AbortController 被中断，未传完的落成「已取消」', async () => {
    let capturedSignal: AbortSignal | undefined
    vi.spyOn(knowledgeApi, 'uploadMany').mockImplementation(async (_baseId, items, options) => {
      capturedSignal = options?.signal
      // 一直挂着，直到组件通过 AbortController 取消（等价于在飞的 POST 被中断）
      await new Promise<void>((resolve) => {
        if (options?.signal?.aborted) return resolve()
        options?.signal?.addEventListener('abort', () => resolve(), { once: true })
      })
      return {
        ok: 0,
        results: [],
        failures: [],
        duplicates: [],
        cancelled: items,
        settled: items.map((item) => ({
          item,
          name: item.fileName || item.file.name,
          state: 'cancelled' as const,
        })),
      }
    })

    await mount()
    await pickFiles(['a.pdf', 'b.pdf'])
    // 还在传 ⇒ 取消入口必须可见（⛔ 隐藏入口 = 用户无法中断）
    const cancelButton = container.querySelector<HTMLButtonElement>('.kb-upload-result-cancel')
    expect(cancelButton?.textContent).toBe('取消全部')

    await act(async () => {
      cancelButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(capturedSignal?.aborted).toBe(true)
    const text = container.textContent ?? ''
    expect(text).toContain('已取消 2 个未传完的文件')
    // ⛔ 取消不是失败：不能出现失败清单与重试入口
    expect(text).not.toContain('个失败')
    expect(container.querySelectorAll('.kb-upload-item-retry')).toHaveLength(0)
  })
})

describe('KbUploadSource · 解析完成度', () => {
  it('⭐ 上传完成后轮询一次即收敛：头部显示「已完成 2 / 2（解析完成 2）」且不再问第二次', async () => {
    vi.spyOn(knowledgeApi, 'uploadMany').mockImplementation(async (_baseId, items) => ({
      ok: items.length,
      results: items.map((item) => ({ id: `k-${item.file.name}` }) as Knowledge),
      failures: [],
      duplicates: [],
      cancelled: [],
      settled: items.map((item) => ({
        item,
        name: item.file.name,
        state: 'uploaded' as const,
        knowledgeId: `k-${item.file.name}`,
      })),
    }))
    const batch = vi
      .spyOn(knowledgeApi, 'batchGetKnowledge')
      .mockImplementation(async (ids) =>
        ids.map((id) => ({ id, parse_status: 'completed' }) as Knowledge)
      )

    await mount()
    await pickFiles(['a.pdf', 'b.pdf'])

    // 两个成功项的 id 都进了那一批查询（40 个/批 ⇒ 一次就够）
    expect(batch).toHaveBeenCalledTimes(1)
    expect([...batch.mock.calls[0][0]].sort()).toEqual(['k-a.pdf', 'k-b.pdf'])

    const text = container.textContent ?? ''
    expect(text).toContain('已完成 2 / 2')
    expect(text).toContain('解析完成 2')
    // 全 settled ⇒ 不再显示「解析中…」
    expect(text).not.toContain('解析中')
  })
})
