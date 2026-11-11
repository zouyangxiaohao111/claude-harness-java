import { beforeEach, describe, expect, it } from 'vitest'

import {
  askQuestionSummary,
  extractAskQuestions,
  isAskUserQuestion,
  markAskUserQuestionNotified,
  resetAskUserQuestionNotified,
} from '../askUserNotice'

/**
 * 「工具在等你回答」的判定与去重契约。
 *
 * <p><b>为什么值得测</b>：这套判据错了不会报错，只是**提示不出现**或**提示重复出现**
 * —— 用户只会说「右下角没提示」/「怎么弹了三条」。故钉住三条：
 * <ol>
 *   <li>判据 = {@code toolInput.questions} 非空（与后端诊断日志、PermissionBubble 渲染同口径），
 *       **不是**工具名 —— 否则新增别的提问型工具就漏判；</li>
 *   <li>入参可能是对象也可能是 JSON 字符串（PermissionBubble 两种都处理）⇒ 判定也两种都收；</li>
 *   <li>去重按 requestId —— 同一次提问会被实时三通道 + REST 补拉<b>重复送达</b>，
 *       不去重就会连着弹 N 条一模一样的系统通知。</li>
 * </ol>
 */
describe('extractAskQuestions · 入参两种形状都要收', () => {
  it('对象入参 → 取到 questions', () => {
    const qs = [{ question: '选哪个？', header: 'H', options: [] }]
    expect(extractAskQuestions({ questions: qs })).toEqual(qs)
  })

  it('JSON 字符串入参 → 解析后取到 questions（后端 toolInput 可能是序列化过的串）', () => {
    const qs = [{ question: '选哪个？', header: 'H', options: [] }]
    expect(extractAskQuestions(JSON.stringify({ questions: qs }))).toEqual(qs)
  })

  it('非法 JSON 字符串 → 空数组，不抛', () => {
    expect(extractAskQuestions('{不是 json')).toEqual([])
  })

  it('null / undefined / 非对象 → 空数组', () => {
    expect(extractAskQuestions(null)).toEqual([])
    expect(extractAskQuestions(undefined)).toEqual([])
    expect(extractAskQuestions(42)).toEqual([])
  })

  it('questions 不是数组 → 空数组（不把对象当数组用）', () => {
    expect(extractAskQuestions({ questions: { a: 1 } })).toEqual([])
  })
})

describe('isAskUserQuestion · 判据是 questions 非空', () => {
  it('questions 非空 → 是提问', () => {
    expect(isAskUserQuestion({ questions: [{ question: 'A' }] })).toBe(true)
  })

  it('questions 为空数组 → 不是提问', () => {
    expect(isAskUserQuestion({ questions: [] })).toBe(false)
  })

  it('没有 questions 字段 → 不是提问（普通权限请求，如 Bash 写入）', () => {
    expect(isAskUserQuestion({ command: 'ls' })).toBe(false)
  })
})

describe('askQuestionSummary · 通知文案用第一题', () => {
  it('取第一题的 question 文本', () => {
    expect(askQuestionSummary({ questions: [{ question: '选哪个方案？' }] })).toBe('选哪个方案？')
  })

  it('超长 → 截断加省略号', () => {
    const long = '问'.repeat(100)
    const s = askQuestionSummary({ questions: [{ question: long }] })
    expect(s!.length).toBeLessThanOrEqual(61)
    expect(s!.endsWith('…')).toBe(true)
  })

  it('取不到问题文本 → null（调用方决定兜底文案，不编造）', () => {
    expect(askQuestionSummary({ questions: [{ header: 'H' }] })).toBeNull()
    expect(askQuestionSummary({})).toBeNull()
  })
})

describe('markAskUserQuestionNotified · 去重（同一请求只通知一次）', () => {
  beforeEach(() => {
    resetAskUserQuestionNotified()
  })

  it('首次遇到 → true（应当发通知）', () => {
    expect(markAskUserQuestionNotified('r1', { questions: [{ question: 'A' }] })).toBe(true)
  })

  it('同一 requestId 再来 → false（不重复发通知）', () => {
    markAskUserQuestionNotified('r1', { questions: [{ question: 'A' }] })
    expect(markAskUserQuestionNotified('r1', { questions: [{ question: 'A' }] })).toBe(false)
  })

  it('不同 requestId → 各自 true（两个会话同时提问 ⇒ 两条通知）', () => {
    expect(markAskUserQuestionNotified('r1', { questions: [{ question: 'A' }] })).toBe(true)
    expect(markAskUserQuestionNotified('r2', { questions: [{ question: 'B' }] })).toBe(true)
  })

  it('不是提问型请求 → false，且**不**占用去重名额', () => {
    expect(markAskUserQuestionNotified('r1', { command: 'ls' })).toBe(false)
    // 之后同一个 requestId 若真的变成提问型（形状变化）仍应能通知
    expect(markAskUserQuestionNotified('r1', { questions: [{ question: 'A' }] })).toBe(true)
  })

  it('空 requestId → false（无 id 无法去重，不发）', () => {
    expect(markAskUserQuestionNotified('', { questions: [{ question: 'A' }] })).toBe(false)
  })
})
