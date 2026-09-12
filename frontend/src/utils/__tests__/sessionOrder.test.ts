import { describe, expect, it } from 'vitest'
import {
  compareSessions,
  compareGroups,
  latestUpdatedAt,
  type SessionGroupOrderKey,
  type SessionOrderKey,
} from '../sessionOrder'

const s = (id: string, updatedAt?: string | null): SessionOrderKey => ({ id, updatedAt })
const ids = (list: SessionOrderKey[]) => [...list].sort(compareSessions).map((x) => x.id)

describe('compareSessions', () => {
  it('不同 updatedAt → 降序（最近活动在前）· WHY：左栏「最近在前」是唯一用户预期，且必须与后端 ORDER BY updated_at DESC 同口径，否则新建/刷新两套序会打架', () => {
    const list = [
      s('sess-b', '2026-09-12T10:00:00.100000+08:00'),
      s('sess-a', '2026-09-12T11:00:00.200000+08:00'),
      s('sess-c', '2026-09-11T09:00:00.300000+08:00'),
    ]
    expect(ids(list)).toEqual(['sess-a', 'sess-b', 'sess-c'])
  })

  it('同 updatedAt → 按 id 升序（tie-breaker）· WHY：同值时 SQLite 不保证相对顺序，排序只是偏序、行序仍会漂 —— 补 id 才成为全序，这正是「顺序不固定」的根因', () => {
    const t = '2026-09-12T10:00:00.123456+08:00'
    expect(ids([s('sess-c', t), s('sess-a', t), s('sess-b', t)])).toEqual(['sess-a', 'sess-b', 'sess-c'])
    // 与输入顺序无关（全序；仅靠 Array#sort 稳定性是做不到的 —— 稳定只保证「等价类内保持输入序」）
    expect(ids([s('sess-b', t), s('sess-c', t), s('sess-a', t)])).toEqual(['sess-a', 'sess-b', 'sess-c'])
  })

  it('updatedAt 为普通字符串比较（非 localeCompare）· WHY：id 是 sess- ASCII，localeCompare 受 locale 影响、跨环境可能给出不同序', () => {
    const t = '2026-09-12T10:00:00.123456+08:00'
    // 大写 id 在朴素字节序中排在小写之前（'S'(0x53) < 's'(0x73)）；localeCompare 在部分 locale 下会反过来
    expect(ids([s('sess-a', t), s('SESS-a', t)])).toEqual(['SESS-a', 'sess-a'])
  })

  it('ISO 字符串尾随零被 trim 时文本序仍等于时间序 · WHY：OffsetDateTime.toString() 会 trim 尾零，若文本序在此失真，前端序就会与后端 SQL 的文本序不一致', () => {
    // .5 > .05
    expect(compareSessions(s('x', '2026-09-12T10:00:00.5+08:00'), s('y', '2026-09-12T10:00:00.05+08:00'))).toBeLessThan(0)
    // 10:00:00 无小数（= .0）< .5 → 无形者排后
    expect(compareSessions(s('x', '2026-09-12T10:00:00+08:00'), s('y', '2026-09-12T10:00:00.5+08:00'))).toBeGreaterThan(0)
  })

  it('updatedAt 可空（后端 parseDateTime 失败 → null）· WHY：SessionDto.updatedAt 可空，空值不得让比较退化成 NaN/随机序', () => {
    // 空值视作空串 → 降序沉底；两个空值再按 id 升序（仍确定）
    expect(ids([s('sess-b', null), s('sess-a', '2026-09-12T10:00:00+08:00'), s('sess-c', undefined)]))
      .toEqual(['sess-a', 'sess-b', 'sess-c'])
    expect(compareSessions(s('sess-a', null), s('sess-a', null))).toBe(0)
  })

  it('空数组 / 单元素 · WHY：首页空态（welcome hero）与单会话是常态，排序不得抛异常或改动元素', () => {
    expect([].sort(compareSessions)).toEqual([])
    const one = [s('sess-only', '2026-09-12T10:00:00+08:00')]
    expect(ids(one)).toEqual(['sess-only'])
  })

  it('新建会话（updatedAt 最新）排序后自然置顶 · WHY：App 三处由「无条件前插」改为「重排」，新会话仍必须在最前 —— 否则改法就破坏了新建体验', () => {
    const old = [
      s('sess-b', '2026-09-12T10:00:00.100000+08:00'),
      s('sess-c', '2026-09-12T09:00:00.100000+08:00'),
    ]
    const created = s('sess-new', '2026-09-12T12:00:00.500000+08:00')
    expect(ids([created, ...old])).toEqual(['sess-new', 'sess-b', 'sess-c'])
  })

  it('【混合精度】DB 补零的 9 位 / Jackson 裁零的 7·6·1 位混排仍得正确时间序 · WHY：前端消费的【不是】DB 里的字面量（Jackson 规范化裁掉尾随零，真库 13 行里 12 行两侧字面不同）—— 一致性靠「两端各自保序」，若将来有人改 Jackson 配置（如加 @JsonFormat）改输出精度，本用例必须变红而不是静默破序', () => {
    // 同一时刻的两种写法：DB 原样 9 位补零 vs Jackson 裁零后 7 位（取自真库实测值）
    const dbPadded = s('sess-pad', '2026-09-08T15:17:25.779593700+08:00')
    const jacksonTrimmed = s('sess-trim', '2026-09-08T15:17:25.7795937+08:00')
    // 时间轴：.0 < .7795937(=dbPadded) < .78 < .8
    const noFraction = s('sess-none', '2026-09-08T15:17:25+08:00')
    const late7 = s('sess-l7', '2026-09-08T15:17:25.78+08:00')
    const late1 = s('sess-l1', '2026-09-08T15:17:25.8+08:00')

    const order = ids([noFraction, late1, jacksonTrimmed, late7, dbPadded])
    expect(order).toEqual(['sess-l1', 'sess-l7', 'sess-pad', 'sess-trim', 'sess-none'])
    // 关键不变量（比具体顺序更重要）：两者是【同一时刻】，谁先谁后都合时序，但绝不能出现
    // 「更早的排在更晚的之前」的倒置 —— 用真实时间戳交叉验证整条序列单调不增。
    const times = order.map((id) => new Date(
      ({ 'sess-l1': '2026-09-08T15:17:25.8+08:00', 'sess-l7': '2026-09-08T15:17:25.78+08:00',
         'sess-pad': '2026-09-08T15:17:25.7795937+08:00', 'sess-trim': '2026-09-08T15:17:25.7795937+08:00',
         'sess-none': '2026-09-08T15:17:25+08:00' })[id]!).getTime())
    for (let i = 1; i < times.length; i++) {
      expect(times[i]).toBeLessThanOrEqual(times[i - 1])
    }
  })
})

describe('compareGroups', () => {
  const g = (key: string, updatedAtList: (string | null | undefined)[]): SessionGroupOrderKey =>
    ({ key, sessions: updatedAtList.map((u, i) => s(`${key}-${i}`, u)) })
  const order = (list: SessionGroupOrderKey[]) => [...list].sort(compareGroups).map((x) => x.key)

  it("'__unbound__' 恒末尾（无论其组内最新 updatedAt 多大）· WHY：未分组不是一个项目，混在项目组之间会让左栏『工作区』语义错乱", () => {
    const t = '2026-09-12T10:00:00.100000+08:00'
    // unbound 的最新 updatedAt 最大，仍必须排最后
    expect(order([g('__unbound__', ['2026-09-12T23:59:59.999999+08:00']), g('p1', [t])])).toEqual(['p1', '__unbound__'])
    expect(order([g('p1', [t]), g('__unbound__', ['2026-09-12T23:59:59.999999+08:00'])])).toEqual(['p1', '__unbound__'])
    // 只有 unbound
    expect(order([g('__unbound__', [t])])).toEqual(['__unbound__'])
  })

  it('组内最新 updatedAt 降序（最近活动的组在前）· WHY：原实现 comparator 对两个非 unbound 组恒返回 0 → 组间序随 Map 插入序漂移，这就是「排序不固定」的同一处', () => {
    const early = g('p-early', ['2026-09-12T09:00:00.100000+08:00'])
    const late = g('p-late', ['2026-09-12T20:00:00.100000+08:00'])
    const mid = g('p-mid', ['2026-09-12T12:00:00.100000+08:00'])
    // 任一输入顺序都得同一结果
    expect(order([early, late, mid])).toEqual(['p-late', 'p-mid', 'p-early'])
    expect(order([mid, early, late])).toEqual(['p-late', 'p-mid', 'p-early'])
    // 组内取的是【最新】一条，而不是第一条/最后一条
    expect(order([g('p1', ['2026-09-12T09:00:00.100000+08:00', '2026-09-12T22:00:00.100000+08:00']), g('p2', ['2026-09-12T12:00:00.100000+08:00'])]))
      .toEqual(['p1', 'p2'])
  })

  it('组内最新 updatedAt 同值时按组键升序 · WHY：与 compareSessions 的 id 兜底同源 —— 同值只有偏序，组间序会回落到 Map 插入序', () => {
    const t = '2026-09-12T10:00:00.100000+08:00'
    expect(order([g('p-b', [t]), g('p-a', [t]), g('p-c', [t])])).toEqual(['p-a', 'p-b', 'p-c'])
    expect(order([g('p-c', [t]), g('p-b', [t]), g('p-a', [t])])).toEqual(['p-a', 'p-b', 'p-c'])
    // 全空 updatedAt 也走同一条兜底（不抛、不依赖插入序）
    expect(order([g('p-b', [null]), g('p-a', [undefined])])).toEqual(['p-a', 'p-b'])
  })

  it('全序性：500 次乱序输入只产出同一种组序 · WHY：把「顺序确定」固化为不变量 —— 偏序（恒 0 版）在乱序输入下会产出多种组序', () => {
    const t = '2026-09-12T10:00:00.100000+08:00'
    const base = [g('p-1', [t]), g('p-2', [t]), g('p-3', [t]), g('p-4', [t]), g('__unbound__', [t])]
    const expected = ['p-1', 'p-2', 'p-3', 'p-4', '__unbound__']
    const seen = new Set<string>()
    for (let i = 0; i < 500; i++) {
      const shuffled = [...base]
      for (let j = shuffled.length - 1; j > 0; j--) {
        const k = Math.floor(Math.random() * (j + 1))
        ;[shuffled[j], shuffled[k]] = [shuffled[k], shuffled[j]]
      }
      seen.add(order(shuffled).join(','))
    }
    expect([...seen]).toEqual([expected.join(',')])
  })
})

describe('latestUpdatedAt', () => {
  it('取组内最新 updatedAt · WHY：分组标题序依赖它（最近活动的组在前），取错则组间序漂移', () => {
    expect(latestUpdatedAt([
      s('a', '2026-09-12T10:00:00.100000+08:00'),
      s('b', '2026-09-12T11:00:00.100000+08:00'),
      s('c', '2026-09-12T09:00:00.100000+08:00'),
    ])).toBe('2026-09-12T11:00:00.100000+08:00')
    // 空数组 / 全空值 → ''（降序比较时排最后，不抛）
    expect(latestUpdatedAt([])).toBe('')
    expect(latestUpdatedAt([s('a', null), s('b', undefined)])).toBe('')
  })
})
