// @vitest-environment jsdom
import { act, createElement, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import type { ChatMessageDto } from '@/api/types'

/**
 * [打字性能] 可测判据：打字（父级 state 变化）**不得**触发 MessageList 重渲。
 *
 * <p>WHY 这样测：MessageList 渲染时会遍历 messages（groups / flatRows 两个 useMemo + 取末条 id）。
 * 于是把 messages 包成 **Proxy 统计属性访问次数** —— 重渲必然遍历，bail 则一次都不碰。
 * 这比「断言 DOM 没变」强得多：DOM 在「重渲了但内容相同」与「没重渲」两种情况下**都不会变**，分不出来。
 *
 * <p>背景：打字状态 composerText 在 App 里（App.tsx:162 的 useState）⇒ 每敲一键 App 重渲；
 * 而 App 的重子组件此前**一个都没 memo**（MessageList / SessionList / RightPanel / Composer）
 * ⇒ 敲键要把 N 行消息整表过一遍，N 随窗口增长而变大 = 用户报的「窗口越大越卡」。
 * 本用例是那条修复的**唯一判据** —— 谁把 memo 去掉，它必须变红。
 */

const noop = () => {}

function manyMsgs(n: number): ChatMessageDto[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `m${i}`, sessionId: 'sess-1', role: 'user', author: '你', content: `内容 ${i}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null, error: null,
    errorDetails: null, matchedRule: null,
  }))
}

let visits = 0
/** 统计属性访问次数的只读代理。⚠️ 引用必须**稳定**（每次 render 新建会让 memo 因 props 换引用而失效，
 *  那样测到的就不是「父级重渲是否打穿 memo」了）。 */
function counting(list: ChatMessageDto[]): ChatMessageDto[] {
  return new Proxy(list, {
    get(t, p, r) { visits++; return Reflect.get(t, p, r) },
  })
}

let root: Root | null = null
let typeChar: ((v: string) => void) | null = null

/** 复刻 App 的结构：**同一个组件里**持有「打字文本」state（= App 的 composerText），
 *  并把稳定 props 传给 MessageList。 */
function Harness({ messages }: { messages: ChatMessageDto[] }) {
  const [text, setTextLocal] = useState('')
  typeChar = setTextLocal
  return createElement(
    'div', null,
    createElement('input', {
      value: text,
      onChange: (e: { target: { value: string } }) => setTextLocal(e.target.value),
    }),
    createElement(MessageList, {
      sessionId: 'sess-1', messages, onDelete: noop, conversationId: null,
      onNearBottomChange: noop, onLoadOlder: noop,
    }),
  )
}

describe('MessageList 打字重渲防线（memo）', () => {
  beforeEach(() => { visits = 0 })
  afterEach(() => { act(() => { root?.unmount() }); root = null })

  it('父级因打字重渲时 MessageList 不得重渲（WHY：窗口内 N 行整表遍历就是「越大越卡」的直接成本）', () => {
    const messages = counting(manyMsgs(120))
    const container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => { root!.render(createElement(Harness, { messages })) })

    const atMount = visits
    // 恒绿防线：首挂载必须真的遍历过 messages，否则本用例什么都没测
    expect(atMount).toBeGreaterThan(0)

    for (let i = 1; i <= 5; i++) {
      act(() => { typeChar!('x'.repeat(i)) })   // 连打 5 个字符
    }
    expect(visits).toBe(atMount)                 // 打字期间一次都没碰 messages
  })
})
