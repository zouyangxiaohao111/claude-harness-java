// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * 左栏分组标题右侧的「N 运行中 / N 待处理」角标（方案 A）。
 *
 * <b>WHY</b>：只显示组内总数时，组收起后看不到行，用户必须逐组展开才知道哪个组里有会话在跑。
 * 口径与行内状态点一致：等待权限/回答（pending）优先于运行中，同一会话只算一次。
 */

const mkSession = (id: string, updatedAt: string, pinned?: boolean): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId: 'p-1',
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 1,
  createdAt: updatedAt,
  updatedAt,
  pinned,
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

/** 按标题文本取该分组标题右侧的角标文本数组；无角标返回空数组。 */
const badgesFor = (name: string): string[] => {
  const label = Array.from(container.querySelectorAll('.label')).find(
    (el) => el.querySelector('.group-path')?.textContent === name,
  )
  return Array.from(label?.querySelectorAll('.run-badge') ?? []).map((e) => (e.textContent ?? '').trim())
}

describe('SessionList · 分组运行角标', () => {
  it('项目分组：组内有运行中会话 ⇒ 「N 运行中」', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[
            mkSession('s-run', '2026-11-02T00:00:00Z'),
            mkSession('s-idle', '2026-11-01T00:00:00Z'),
          ]}
          projectNameFor={() => '项目A'}
          runningSessionIds={new Set(['s-run'])}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('项目A')).toEqual(['1 运行中'])
  })

  it('等待权限/回答的会话 ⇒ 「N 待处理」', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s-pend', '2026-11-01T00:00:00Z')]}
          projectNameFor={() => '项目A'}
          pendingSessionIds={new Set(['s-pend'])}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('项目A')).toEqual(['1 待处理'])
  })

  it('运行与待处理分开计数（不同会话）', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[
            mkSession('s-run', '2026-11-04T00:00:00Z'),
            mkSession('s-pend', '2026-11-03T00:00:00Z'),
            mkSession('s-idle', '2026-11-01T00:00:00Z'),
          ]}
          projectNameFor={() => '项目A'}
          runningSessionIds={new Set(['s-run'])}
          pendingSessionIds={new Set(['s-pend'])}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('项目A')).toEqual(['1 运行中', '1 待处理'])
  })

  it('⭐ 同一会话同时出现在运行与待处理集合 ⇒ 只算「待处理」（与行内点 pending 优先一致）', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s-both', '2026-11-01T00:00:00Z')]}
          projectNameFor={() => '项目A'}
          runningSessionIds={new Set(['s-both'])}
          pendingSessionIds={new Set(['s-both'])}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('项目A')).toEqual(['1 待处理'])
  })

  it('置顶块与 IM 块标题也显示角标', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[
            mkSession('s-pin', '2026-11-02T00:00:00Z', true),
            mkSession('s-im', '2026-11-01T00:00:00Z'),
          ]}
          projectNameFor={() => '项目A'}
          imSessionIds={new Set(['s-im'])}
          runningSessionIds={new Set(['s-pin', 's-im'])}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('置顶')).toEqual(['1 运行中'])
    expect(badgesFor('IM 会话')).toEqual(['1 运行中'])
  })

  it('组内无活跃会话 ⇒ 不渲染角标（0 不显示）', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s-idle', '2026-11-01T00:00:00Z')]}
          projectNameFor={() => '项目A'}
          runningSessionIds={new Set()}
          workspaceRedesign
        />,
      )
    })
    expect(badgesFor('项目A')).toEqual([])
    expect(container.querySelectorAll('.run-badge').length).toBe(0)
  })
})
