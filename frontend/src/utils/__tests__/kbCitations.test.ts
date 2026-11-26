import { describe, expect, it } from 'vitest'
import type { CitationItem } from '../../api/types'
import { pickCitationsForTurn, STUB_ASSISTANT_ID } from '../kbCitations'

/**
 * 引用兜底的真实场景（2026-11-26 用户报「知识库对话没有引用了」）：
 * 后端在**生成前**推 citations，那时 assistant 消息还没落库 ⇒ 事件里 id 恒为 `msg-stub-pending`；
 * 落库后消息 id 是 `msg-xxxx`。前端若只按真实 id 查，引用永远为空。
 */
const cite = (n: number): CitationItem => ({
  ref_index: n,
  chunk_id: `c${n}`,
  knowledge_id: 'k1',
  knowledge_base_id: 'kb1',
  knowledge_title: '租金支付0831（测试）.xls',
  snippet: '...',
  chunk_index: n,
})

describe('pickCitationsForTurn', () => {
  it('真实 id 有引用时直接用（落库后、历史回放补齐的正常态）', () => {
    const map = { 'msg-real': [cite(1)], [STUB_ASSISTANT_ID]: [cite(9)] }
    expect(pickCitationsForTurn(map, 'msg-real')).toHaveLength(1)
    expect(pickCitationsForTurn(map, 'msg-real')[0].ref_index).toBe(1)
  })

  it('真实 id 查不到时回退占位桶（实时事件刚到的唯一形态）', () => {
    const map = { [STUB_ASSISTANT_ID]: [cite(1), cite(2)] }
    expect(pickCitationsForTurn(map, 'msg-real')).toHaveLength(2)
  })

  it('两边都没有 ⇒ 空数组（页面显示「引用」入口为 0，不报错）', () => {
    expect(pickCitationsForTurn({}, 'msg-real')).toEqual([])
    expect(pickCitationsForTurn(undefined, undefined)).toEqual([])
  })
})