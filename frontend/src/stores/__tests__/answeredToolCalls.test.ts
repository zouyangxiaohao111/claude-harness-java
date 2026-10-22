import { describe, expect, it, beforeEach } from 'vitest'
import { useChatStore } from '@/stores/chatStore'

/**
 * ⭐ [2026-10-22 问答收敛] AskUserQuestion 回答后 → 工具卡「已回答 · 等待结果」标记的生命周期。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：回答动作只把答案发给后端；工具卡结果依赖 `tool_result` 事件。
 * 若该事件丢失（断流/块已收口），卡片会停在「执行中」或判成「已中断」，用户以为答案没生效。
 * dsh 用 `question/resolved` 广播统一收敛；本仓等价 = 回答时打标记、结果到达即清。
 *
 * <b>验收条件</b>：mark → 标记存在；`fillToolResult` 无论块是否还在都清除标记
 * （块已收口时也必须清，否则永久残留）；clear 幂等。
 */

const SID = 'sess-answered'

function seedStreamBlock() {
  useChatStore.setState({
    streams: {
      [SID]: [{
        assistantMessageId: 'blk-1',
        userMessageId: 'u1',
        reasoning: '',
        content: '',
        toolCalls: [{ id: 'call-ask-1', name: 'AskUserQuestion', arguments: '{}', result: null, isError: null }],
      }],
    },
    streamOrder: { [SID]: ['blk-1'] },
  })
}

describe('[问答收敛] answeredToolCalls 标记生命周期', () => {
  beforeEach(() => {
    useChatStore.setState({ streams: {}, streamOrder: {}, answeredToolCalls: {}, messages: {} })
  })

  it('markToolAnswered → 标记存在（卡片据此显示「已回答 · 等待结果」）', () => {
    useChatStore.getState().markToolAnswered(SID, 'call-ask-1')
    expect(useChatStore.getState().answeredToolCalls['call-ask-1']).toEqual(
      expect.objectContaining({ sessionId: SID }),
    )
  })

  it('tool_result 到达（块存在）→ 填结果并清除标记', () => {
    seedStreamBlock()
    useChatStore.getState().markToolAnswered(SID, 'call-ask-1')

    useChatStore.getState().fillToolResult(SID, 'call-ask-1', 'User has answered your questions: …', false)

    expect(useChatStore.getState().streams[SID][0].toolCalls[0].result).toContain('User has answered')
    expect(useChatStore.getState().answeredToolCalls['call-ask-1']).toBeUndefined()
  })

  it('tool_result 到达但块已收口 → 仍清除标记（不永久残留）', () => {
    // 不 seed 块：模拟 turn 已 finalize、流式块被清空
    useChatStore.getState().markToolAnswered(SID, 'call-ask-1')

    useChatStore.getState().fillToolResult(SID, 'call-ask-1', 'ok', false)

    expect(useChatStore.getState().answeredToolCalls['call-ask-1']).toBeUndefined()
  })

  it('clearToolAnswered 幂等（重复/未知 id 不报错）', () => {
    useChatStore.getState().clearToolAnswered('nope')
    useChatStore.getState().markToolAnswered(SID, 'call-ask-1')
    useChatStore.getState().clearToolAnswered('call-ask-1')
    useChatStore.getState().clearToolAnswered('call-ask-1')
    expect(useChatStore.getState().answeredToolCalls).toEqual({})
  })
})
