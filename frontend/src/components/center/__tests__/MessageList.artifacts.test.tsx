// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, SessionDto } from '@/api/types'
import type { SessionArtifacts, SessionArtifactDto } from '@/api/artifacts'

/**
 * [产物卡片] 会话最后一条 assistant 下方展示后端抽取的交付物。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：产物是「最终交付物」，不是过程文件 —— 用户只在**本轮结束时**、
 * 在**正文末尾**看到最多 3 张成果卡片，超出部分交给「查看所有产物 (N)」入口。
 *
 * <p>[2026-10-31 产物卡片改版] 正文卡片与右栏「产物」tab 是**同一套**组件（{@code ArtifactCards}）：
 *   · 断言落在 {@code .artifact-card} / {@code .ac-name} / {@code .ac-size}（同一套 DOM），
 *     ⇒ 右栏改版若不同步，正文这组断言必红；
 *   · 点卡片回调的是**产物 DTO**（走 App.openArtifact 的按类型分发），不再回调 localPath
 *     —— ⛔ 产物的 localPath 是归档副本绝对路径，不是「打开通道」。
 */

const SID = 'sess-artifacts'
const noop = () => {}

function baseMsg(id: string, role: 'user' | 'assistant', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, createdAt: undefined, toolCallId: null, assistantMessageId: null,
    userMessageId: role === 'assistant' ? 'u1' : id, subtype: null, isMeta: false, isApiErrorMessage: false,
    apiError: null, error: null, errorDetails: null, matchedRule: null,
  }
}

const userMsg = (id: string): ChatMessageDto => ({ ...baseMsg(id, 'user', '用户消息'), userMessageId: id })
const assistantMsg = (id: string): ChatMessageDto => baseMsg(id, 'assistant', `助手回复 ${id}`)

function art(id: string, title: string, localPath: string | null, sizeBytes: number | null = 2048): SessionArtifactDto {
  return { id, kind: 'deliverable', title, localPath, url: null, sizeBytes, summary: null, updatedAt: null }
}

let container: HTMLDivElement
let root: Root

function renderList(
  messages: ChatMessageDto[],
  artifacts?: SessionArtifacts,
  onOpenArtifact?: (a: SessionArtifactDto) => void,
) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList
        sessionId={SID}
        messages={messages}
        onDelete={noop}
        conversationId={null}
        onNearBottomChange={noop}
        onLoadOlder={noop}
        onOpenRightTab={noop}
        onOpenArtifact={onOpenArtifact}
        artifacts={artifacts}
      />,
    )
  })
}

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

describe('[产物卡片] 正文最多 3 张 + 全量计数', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {}, changedFiles: {},
      sessions: [{ id: SID, modelName: 'GLM-5.3-Flash' } as unknown as SessionDto],
    })
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('4 条产物 → 正文只渲染 3 张，计数仍是 4', () => {
    const deliverables = [
      art('a1', '报告.html', 'D:/out/报告.html'),
      art('a2', '清单.xlsx', 'D:/out/清单.xlsx'),
      art('a3', '图纸.pdf', 'D:/out/图纸.pdf'),
      art('a4', '附录.docx', 'D:/out/附录.docx'),
    ]
    renderList([userMsg('u1'), assistantMsg('m1')], { deliverables, sources: [] })
    const cards = container.querySelectorAll('.artifact-card')
    expect(cards).toHaveLength(3)
    expect(Array.from(cards).map((c) => c.querySelector('.ac-name')?.textContent))
      .toEqual(['报告.html', '清单.xlsx', '图纸.pdf'])
    // 卡片区在 TurnFooter 之前（同一 wrapper 内，卡片先出现）
    const artifactsBox = container.querySelector('.msg-artifacts')
    const footer = container.querySelector('.msg-links')
    expect(artifactsBox).not.toBeNull()
    expect(footer).not.toBeNull()
    expect(artifactsBox!.compareDocumentPosition(footer!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(footer!.textContent).toContain('(4)')
    // 大小一行（无 summary 时只有大小）
    expect(cards[0]!.querySelector('.ac-size')?.textContent).toBe('2KB')
  })

  it('点卡片 → 回调产物 DTO（与右栏同一个按类型分发的打开函数）', () => {
    const onOpenArtifact = vi.fn()
    renderList([userMsg('u1'), assistantMsg('m1')], {
      deliverables: [art('a1', '有路径.html', 'D:/out/a.html'), art('a2', '无路径.html', null)],
      sources: [],
    }, onOpenArtifact)
    const cards = container.querySelectorAll('.artifact-card')
    click(cards[0]!)
    click(cards[1]!)
    expect(onOpenArtifact).toHaveBeenCalledTimes(2)
    expect(onOpenArtifact.mock.calls[0][0].title).toBe('有路径.html')
    expect(onOpenArtifact.mock.calls[1][0].title).toBe('无路径.html')
  })

  it('按类型画不同图标（正文与右栏同一套 ⇒ 同一个 .ac-ico.k-* 底色类）', () => {
    renderList([userMsg('u1'), assistantMsg('m1')], {
      deliverables: [art('a1', '报告.html', 'D:/out/a.html'), art('a2', '清单.xlsx', 'D:/out/b.xlsx')],
      sources: [],
    })
    expect(container.querySelector('.ac-ico.k-html')).not.toBeNull()
    expect(container.querySelector('.ac-ico.k-xlsx')).not.toBeNull()
  })

  it('[2026-10-29] 无产物 → 不渲染卡片区，但入口恒显示并计 0（产物/变更/来源是一组）', () => {
    renderList([userMsg('u1'), assistantMsg('m1')])
    expect(container.querySelectorAll('.artifact-card')).toHaveLength(0)
    // 入口永远在：没有数据也要让用户看到「(0)」，否则会以为功能缺失
    expect(container.textContent).toContain('查看所有产物')
    expect(container.textContent).toContain('(0)')
  })
})
