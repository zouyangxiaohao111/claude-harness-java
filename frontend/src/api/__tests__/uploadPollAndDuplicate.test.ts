import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  UPLOAD_POLL_CHUNK_SIZE,
  UPLOAD_POLL_CONCURRENCY,
  UPLOAD_POLL_INTERVAL_MS,
  knowledgeApi,
  pollParseStatuses,
  type Knowledge,
  type UploadManyItem,
} from '@/api/knowledge'
import { ApiError } from '@/api/rest'

/**
 * 上传后的解析状态轮询（收敛即停 / 分批 / 并发上限）、
 * 「后端 409 ⇒ 重复（不计失败）」以及「取消全部」的分类判据。
 *
 * <h2>WHY 轮询要钉「查了几次」而不是只断言最终状态</h2>
 * 最终状态表在「多查一轮」「多睡一次」时完全一样 —— 只断言状态表的话，
 * 一个永不停止的循环（例如漏掉 `pending.size === 0` 的 break）照样全绿；
 * 而真实后果是后端每 3 秒被问一次、连接永不放，直到用户关掉面板。
 * 所以这里的判据是<b>调用次数</b>（`query` / `sleep` 各被调了几次）。
 *
 * <h2>WHY 409 要单独一条判据</h2>
 * 重复文件是**上传成功过才有的状态**（同库同 hash 已存在）。若把它算进 `failures`，
 * 界面会报「失败」并给出一个必然再次 409 的重试按钮 —— 用户点几次都只是重复同一个错误。
 */

const tick = (ms = 5) => new Promise<void>((resolve) => setTimeout(resolve, ms))

const fakeFile = (name: string) => ({ name, size: 1 }) as unknown as File

describe('轮询参数 · 对齐源站 uploadTasksCore.ts', () => {
  it('间隔 3000ms / 单次 40 个 id / 同时在飞 ≤ 4', () => {
    expect(UPLOAD_POLL_INTERVAL_MS).toBe(3000)
    expect(UPLOAD_POLL_CHUNK_SIZE).toBe(40)
    expect(UPLOAD_POLL_CONCURRENCY).toBe(4)
  })
})

describe('pollParseStatuses · 收敛后停止', () => {
  it('⭐ 首轮就全部 settled ⇒ 只查 1 次、一次都不等', async () => {
    const query = vi.fn(async (ids: string[]) =>
      ids.map((id) => ({ id, parse_status: 'completed' }))
    )
    const sleep = vi.fn(async () => {})

    const statuses = await pollParseStatuses(['a', 'b', 'c'], { query, sleep })

    expect(statuses).toEqual({ a: 'completed', b: 'completed', c: 'completed' })
    expect(query).toHaveBeenCalledTimes(1)
    // 全 settled ⇒ 不该再 sleep（多睡一次就是「停慢了 3 秒」）
    expect(sleep).toHaveBeenCalledTimes(0)
  })

  it('⭐ 多轮收敛：3 轮后全 settled ⇒ 查 3 次、只等 2 次（finalizing 仍要盯）', async () => {
    // 每轮每个 id 的答案；缺席 = 本轮不返回该行
    const plan: Array<Record<string, string>> = [
      { a: 'pending', b: 'processing' },
      { a: 'completed', b: 'finalizing' },
      // finalizing 不算终态（下一轮才会落成 completed）⇒ 第 3 轮只问 b
      { b: 'completed' },
    ]
    let round = 0
    const query = vi.fn(async (ids: string[]) =>
      ids
        .filter((id) => plan[Math.min(round, plan.length - 1)][id] !== undefined)
        .map((id) => ({ id, parse_status: plan[Math.min(round, plan.length - 1)][id] }))
    )
    const sleep = vi.fn(async () => {})

    const statuses = await pollParseStatuses(['a', 'b'], {
      query,
      sleep,
      onUpdate: () => {
        round++
      },
    })

    expect(statuses).toEqual({ a: 'completed', b: 'completed' })
    expect(query).toHaveBeenCalledTimes(3)
    // 前两轮后仍是 pending ⇒ 各等 1 次；第 3 轮全 settled ⇒ 不再等、不再查
    expect(sleep).toHaveBeenCalledTimes(2)
  })

  it('⭐ 一次查询最多 40 个 id（90 个 id ⇒ 40/40/10 三批）', async () => {
    const sizes: number[] = []
    const query = vi.fn(async (ids: string[]) => {
      sizes.push(ids.length)
      return ids.map((id) => ({ id, parse_status: 'completed' }))
    })

    const ids = Array.from({ length: 90 }, (_, i) => `k-${i}`)
    await pollParseStatuses(ids, { query, sleep: async () => {} })

    expect(sizes.sort((a, b) => a - b)).toEqual([10, 40, 40])
    expect(sizes.every((n) => n <= UPLOAD_POLL_CHUNK_SIZE)).toBe(true)
  })

  it('⭐ 状态查询并发恰好跑满 4（9 批 / 上限 4 ⇒ 峰值 4；串行实现会得到 1 ⇒ 必红）', async () => {
    let inFlight = 0
    let peak = 0
    const query = vi.fn(async (ids: string[]) => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await tick()
      inFlight--
      return ids.map((id) => ({ id, parse_status: 'completed' }))
    })

    const ids = Array.from({ length: 90 }, (_, i) => `k-${i}`)
    await pollParseStatuses(ids, { query, sleep: async () => {}, chunkSize: 10 })

    expect(query).toHaveBeenCalledTimes(9)
    expect(peak).toBe(UPLOAD_POLL_CONCURRENCY)
  })

  it('缺席 3 轮才判 deleted（1 轮缺席不判死），判死后立刻停止轮它', async () => {
    const query = vi.fn(async () => [] as Array<{ id: string; parse_status: string }>)
    const sleep = vi.fn(async () => {})

    const statuses = await pollParseStatuses(['gone'], {
      query,
      sleep,
      maxRounds: 20,
      onUpdate: (snapshot) => {
        // 前两轮缺席不能落成 deleted（否则一次抖动就把活文档标成已删除）
        if (query.mock.calls.length < 3) expect(snapshot.gone).toBeUndefined()
      },
    })

    expect(statuses).toEqual({ gone: 'deleted' })
    expect(query).toHaveBeenCalledTimes(3)
  })

  it('单次查询失败（返回 null）⇒ 跳过本轮、不把条目判死', async () => {
    let round = 0
    const query = vi.fn(async (ids: string[]): Promise<Array<{ id: string; parse_status: string }> | null> => {
      round++
      if (round === 1) return null
      return ids.map((id) => ({ id, parse_status: 'completed' }))
    })

    const statuses = await pollParseStatuses(['a'], { query, sleep: async () => {} })

    expect(statuses).toEqual({ a: 'completed' })
    expect(query).toHaveBeenCalledTimes(2)
  })

  it('abort 后不再查询（取消轮询）', async () => {
    const controller = new AbortController()
    controller.abort()
    const query = vi.fn(async (ids: string[]) => ids.map((id) => ({ id, parse_status: 'pending' })))

    await pollParseStatuses(['a'], { query, sleep: async () => {}, signal: controller.signal })

    expect(query).toHaveBeenCalledTimes(0)
  })
})

describe('knowledgeApi.uploadMany · 409 计重复 / 取消不误判失败', () => {
  afterEach(() => vi.restoreAllMocks())

  const conflict = () =>
    new ApiError('已存在', {
      status: 409,
      title: 'Conflict',
      detail: '同名或同内容文件已存在：运维手册.pdf',
    })

  it('⭐ 409 ⇒ duplicates 单列，不计失败、不计成功、settled 标 duplicate', async () => {
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockRejectedValue(conflict())

    const items: UploadManyItem[] = [{ file: fakeFile('a.pdf') }]
    const r = await knowledgeApi.uploadMany('kb-1', items)

    expect(r.duplicates).toHaveLength(1)
    expect(r.duplicates[0].name).toBe('a.pdf')
    // ⛔ 重复不是失败：混进 failures 会给出一个必然再 409 的重试按钮
    expect(r.failures).toHaveLength(0)
    expect(r.ok).toBe(0)
    expect(r.settled[0].state).toBe('duplicate')
    expect(r.settled[0].message).toContain('已存在')
  })

  it('409 与真实失败并存 ⇒ 两类各自单列（统计不互相污染）', async () => {
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockImplementation(async (_baseId, file) => {
      if (file.name === 'dup.pdf') throw conflict()
      if (file.name === 'bad.pdf') throw new Error('磁盘忙')
      return { id: file.name } as Knowledge
    })

    const r = await knowledgeApi.uploadMany('kb-1', [
      { file: fakeFile('dup.pdf') },
      { file: fakeFile('bad.pdf') },
      { file: fakeFile('ok.pdf') },
    ])

    expect(r.ok).toBe(1)
    expect(r.duplicates.map((d) => d.name)).toEqual(['dup.pdf'])
    expect(r.failures.map((f) => f.name)).toEqual(['bad.pdf'])
    expect(r.settled.map((s) => s.state)).toEqual(['duplicate', 'failed', 'uploaded'])
  })

  it('⭐ 取消全部：中断在飞请求 ⇒ 未传完的进 cancelled，已成功的不回滚', async () => {
    const controller = new AbortController()
    vi.spyOn(knowledgeApi, 'uploadKnowledge').mockImplementation(
      async (_baseId, file, _fileName, signal) => {
        if (file.name === 'ok.pdf') return { id: 'k-ok' } as Knowledge
        // 在飞项：等 signal 中断（等价于浏览器的 AbortError）
        await new Promise<never>((_resolve, reject) => {
          const fail = () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
          if (signal?.aborted) return fail()
          signal?.addEventListener('abort', fail, { once: true })
        })
        throw new Error('unreachable')
      }
    )

    const pending = knowledgeApi.uploadMany(
      'kb-1',
      [
        { file: fakeFile('ok.pdf') },
        { file: fakeFile('slow-1.pdf') },
        { file: fakeFile('slow-2.pdf') },
      ],
      { signal: controller.signal }
    )
    // 等 ok.pdf 落地、两个慢项进入在飞状态后再取消
    await tick(10)
    controller.abort()
    const r = await pending

    expect(r.ok).toBe(1)
    expect(r.results.map((k) => k.id)).toEqual(['k-ok'])
    expect(r.cancelled.map((i) => i.file.name)).toEqual(['slow-1.pdf', 'slow-2.pdf'])
    // ⛔ 取消不是失败：否则面板会显示「2 个失败」并给出重试
    expect(r.failures).toHaveLength(0)
    expect(r.settled.map((s) => s.state)).toEqual(['uploaded', 'cancelled', 'cancelled'])
  })
})
