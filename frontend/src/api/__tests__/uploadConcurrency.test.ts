import { afterEach, describe, expect, it, vi } from 'vitest'
import { UPLOAD_CONCURRENCY, knowledgeApi, runPool, type Knowledge, type UploadManyItem } from '@/api/knowledge'

/**
 * 知识库多文件上传 · 并发池判据（对齐源站 `uploadTasksCore.ts` 的 CONCURRENCY = 3）。
 *
 * <h2>为什么必须断言「峰值 === 3」而不只是「≤ 3」</h2>
 * 旧的 `for-await` 串行实现峰值恒为 1，必然满足 `≤ 3` —— 只写上限会得到一个
 * 改动前后都绿的假绿断言。所以这里同时钉住「真的跑满 3 条」。
 */

const tick = (ms = 5) => new Promise<void>((resolve) => setTimeout(resolve, ms))

describe('runPool · 有界并发 + 失败隔离', () => {
  it('⭐ 10 个任务 / 上限 3 ⇒ 峰值恰好 3（串行实现会得到 1 ⇒ 必红）', async () => {
    let inFlight = 0
    let peak = 0
    const outcomes = await runPool([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 3, async (n) => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await tick()
      inFlight--
      return n * 2
    })
    expect(peak).toBe(3)
    expect(outcomes.every((o) => o.ok)).toBe(true)
    expect(outcomes.map((o) => o.value)).toEqual([2, 4, 6, 8, 10, 12, 14, 16, 18, 20])
  })

  it('⭐ 单个任务抛错不中断其它任务，且全部都被尝试过', async () => {
    const started: number[] = []
    const outcomes = await runPool([1, 2, 3, 4, 5, 6], 3, async (n) => {
      started.push(n)
      await tick()
      if (n === 2 || n === 5) throw new Error(`boom-${n}`)
      return n
    })
    expect(started.sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6])
    expect(outcomes.map((o) => o.ok)).toEqual([true, false, true, true, false, true])
    expect(outcomes[1].error).toBeInstanceOf(Error)
    expect((outcomes[1].error as Error).message).toBe('boom-2')
    expect(outcomes.filter((o) => o.ok).map((o) => o.value)).toEqual([1, 3, 4, 6])
  })

  it('outcomes 与输入同序（不是完成顺序）', async () => {
    const outcomes = await runPool([30, 10, 20], 3, async (n) => {
      await tick(n)
      return n
    })
    expect(outcomes.map((o) => o.value)).toEqual([30, 10, 20])
  })

  it('onSettled 每个任务回调一次，final done = total', async () => {
    const seen: Array<[number, number]> = []
    await runPool([1, 2, 3, 4, 5], 3, async () => tick(), {
      onSettled: (done, total) => seen.push([done, total]),
    })
    expect(seen.map(([done]) => done)).toEqual([1, 2, 3, 4, 5])
    expect(seen.every(([, total]) => total === 5)).toBe(true)
  })

  it('worker 数取 min(上限, 任务数)；空列表直接返回', async () => {
    let inFlight = 0
    let peak = 0
    const track = async (n: number) => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await tick()
      inFlight--
      return n
    }
    await runPool([1, 2], 10, track)
    expect(peak).toBe(2)
    await expect(runPool([], 3, track)).resolves.toEqual([])
  })
})

describe('knowledgeApi.uploadMany · 单文件 POST × 并发池', () => {
  afterEach(() => vi.restoreAllMocks())

  /** 只有 `name` 参与展示/断言；uploadKnowledge 被 mock，不需要真 File。 */
  const fakeFile = (name: string) => ({ name, size: 1 }) as unknown as File

  it('⭐ 失败项进 failures（带可重试的 item），成功项进 results，其它文件继续传', async () => {
    let inFlight = 0
    let peak = 0
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockImplementation(async (_baseId, file) => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await tick()
      inFlight--
      if (file.name === 'bad-1.pdf' || file.name === 'bad-2.pdf') {
        throw new Error(`拒绝：${file.name}`)
      }
      return { id: file.name } as Knowledge
    })

    const items: UploadManyItem[] = Array.from({ length: 8 }, (_, i) => ({ file: fakeFile(`f-${i}.pdf`) }))
    // 把两个失败项塞进中间，确认不会阻断后面的文件
    items[2] = { file: fakeFile('bad-1.pdf') }
    items[5] = { file: fakeFile('bad-2.pdf') }

    const progress: Array<[number, number]> = []
    const r = await knowledgeApi.uploadMany('kb-1', items, {
      onProgress: (done, total) => progress.push([done, total]),
    })

    expect(r.ok).toBe(6)
    expect(r.results).toHaveLength(6)
    expect(r.failures.map((f) => f.name)).toEqual(['bad-1.pdf', 'bad-2.pdf'])
    expect(r.failures.map((f) => f.message)).toEqual(['拒绝：bad-1.pdf', '拒绝：bad-2.pdf'])
    // ⭐ 失败项带着原始 item ⇒ 上层可「重试失败项」
    expect(r.failures[0].item.file.name).toBe('bad-1.pdf')
    expect(progress[progress.length - 1]).toEqual([8, 8])
    expect(peak).toBe(UPLOAD_CONCURRENCY)
  })

  it('文件夹项用 fileName 作为失败清单里的展示名（保留目录信息）', async () => {
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockRejectedValue(new Error('磁盘忙'))
    const r = await knowledgeApi.uploadMany('kb-1', [
      { file: fakeFile('a.md'), fileName: '规范/运维/a.md' },
    ])
    expect(r.ok).toBe(0)
    expect(r.failures).toHaveLength(1)
    expect(r.failures[0].name).toBe('规范/运维/a.md')
    expect(r.failures[0].message).toBe('磁盘忙')
  })

  it('⭐ 两个入口同时上传 ⇒ 全局在飞仍恰好 3（模块级槽位，不是每个池各 3）', async () => {
    let inFlight = 0
    let peak = 0
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockImplementation(async (_baseId, file) => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await tick()
      inFlight--
      return { id: file.name } as Knowledge
    })

    const batch = (prefix: string): UploadManyItem[] =>
      Array.from({ length: 5 }, (_, i) => ({ file: fakeFile(`${prefix}-${i}.pdf`) }))

    const [a, b] = await Promise.all([
      knowledgeApi.uploadMany('kb-a', batch('a')),
      knowledgeApi.uploadMany('kb-b', batch('b')),
    ])
    expect(a.ok).toBe(5)
    expect(b.ok).toBe(5)
    expect(peak).toBe(UPLOAD_CONCURRENCY)
  })

  it('默认并发上限是 3（对齐源站 CONCURRENCY）', () => {
    expect(UPLOAD_CONCURRENCY).toBe(3)
  })
})
