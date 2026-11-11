// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * ⭐ [2026-11-10 用户裁定] **接线守门测试**：`SessionList` 必须真的把
 * `projectCreatedAtFor` 交给 `compareGroups`。
 *
 * <b>WHY 必须有这一层（规则九 · 测试验证意图）</b>：`sessionOrder.test.ts` 只测纯函数
 * `compareGroups` —— 把 `SessionList.tsx` 里那行 `projectCreatedAt: projectCreatedAtFor?.(a)`
 * 删掉，纯函数测试照样全绿（实测过这种假绿：纯函数绿 ≠ 调用点接上）。
 * 而「在后面的项目里新建会话 → 该项目窜到最前」这个用户实际报的缺陷，
 * 恰恰是由**调用点是否传了项目注册时间**决定的。故本文件渲染真组件、断言 DOM 顺序。
 *
 * <b>反向实验</b>：删掉 `SessionList.tsx` 里的 `projectCreatedAt: projectCreatedAtFor?.(...)`
 * ⇒ 三组注册时间退化成同一兜底 ⇒ 顺序变成组键升序，本文件必红。
 */

const mkSession = (id: string, mainProjectId: string, updatedAt: string): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId,
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 1,
  createdAt: updatedAt,
  updatedAt,
})

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

/** 读渲染结果里项目组标题的文字顺序（DOM 顺序即视觉顺序）。 */
function groupTitles(): string[] {
  return [...container.querySelectorAll('.left-section > .label .group-path')]
    .map((el) => el.textContent ?? '')
}

describe('SessionList · 项目分组序 = 项目注册先后（接线）', () => {
  it('在最后注册的项目里新建会话（updatedAt 最新）不得把该组顶到最前 · WHY：用户实际报的现象就是这个', () => {
    // ⚠️ 组键刻意取成与注册先后**相反**的字典序（'p-z-first' 先注册、'p-a-last' 后注册）：
    //   若测试用 'p-first'/'p-last' 这种「键序恰好 == 注册序」的写法，删掉 projectCreatedAt 接线后
    //   会退化成组键升序兜底 —— 结果**一样**，测试变假绿（本文件初版就踩了这个坑，反向实验实测 2 passed）。
    //   反差键序 + 同时校验「会话 updatedAt 也不影响」⇒ 只有真正读了注册时间才能通过。
    const sessions = [
      mkSession('sess-a', 'p-z-first', '2026-09-01T09:00:00Z'),
      mkSession('sess-b', 'p-a-last', '2026-09-12T23:59:59Z'),
    ]
    const createdAt: Record<string, string> = {
      'p-z-first': '2020-01-01T00:00:01Z',
      'p-a-last': '2020-01-01T00:00:02Z',
    }
    act(() => {
      root.render(
        <SessionList
          sessions={sessions}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={(pid) => (pid === 'p-z-first' ? '先注册' : '后注册')}
          projectCreatedAtFor={(pid) => (pid ? createdAt[pid] : undefined)}
        />,
      )
    })
    // 注册先后：「先注册」在前 —— 既不是组键升序（会给「后注册」在前），也与组内会话 updatedAt 无关
    expect(groupTitles()).toEqual(['先注册', '后注册'])
  })

  it('注册时间缺失的项目沉底 · WHY：项目信息未拉到时不得冒充「最早注册」排到最前（后端 ASC NULLS LAST 同口径）', () => {
    // 同理反着取键：'p-a-unknown' 键序在前，但注册时间缺失 ⇒ 必须沉底
    const sessions = [
      mkSession('sess-a', 'p-a-unknown', '2026-09-12T09:00:00Z'),
      mkSession('sess-b', 'p-z-known', '2026-09-01T09:00:00Z'),
    ]
    act(() => {
      root.render(
        <SessionList
          sessions={sessions}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={(pid) => (pid === 'p-z-known' ? '有注册时间' : '无注册时间')}
          projectCreatedAtFor={(pid) => (pid === 'p-z-known' ? '2020-01-01T00:00:01Z' : undefined)}
        />,
      )
    })
    expect(groupTitles()).toEqual(['有注册时间', '无注册时间'])
  })
})
