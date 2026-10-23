import { beforeEach, describe, expect, it } from 'vitest'
import { EMPTY_TEAM_SLICE, teamSliceOf, useTeamStore } from '../teamStore'
import type { TeammateMessageEvent } from '@/api/types'

/**
 * ⭐ [2026-10-23] 团队态**按 lead 会话分桶**（对齐 dsh：状态一律实例级）。
 *
 * <b>病情</b>：两个会话各自有团队时，B 的 teammate 消息被追加进同一个全局数组 ⇒
 * 在 A 的团队面板里也能看到 B 的队员发言（且没有任何"当前会话"字段可以筛掉它）。
 *
 * <b>修法（本测试锁死）</b>：`byLead` 以 leadSessionId 为键；addMessage/setTeam/setInbox/
 * markAllRead 都带 lead 参数；`clear()`（无参）只解绑当前指针、不删桶。
 */

const LEAD_A = 'sess-lead-a'
const LEAD_B = 'sess-lead-b'

const msg = (text: string): TeammateMessageEvent =>
  ({ from: 'worker@team', text, timestamp: new Date().toISOString() } as unknown as TeammateMessageEvent)

describe('[teamStore] 团队态按 lead 分桶', () => {
  beforeEach(() => {
    useTeamStore.setState({ leadSessionId: null, byLead: {}, agentSwarms: false })
  })

  it('⭐ B 的 teammate 消息不得出现在 A 的切片里（各自独立计数）', () => {
    const st = useTeamStore.getState()
    st.setTeamName('team-a', LEAD_A)
    st.setTeamName('team-b', LEAD_B)

    st.addMessage(LEAD_A, msg('A 的队员'))
    st.addMessage(LEAD_B, msg('B 的队员'))
    st.addMessage(LEAD_B, msg('B 的队员 2'))

    const a = teamSliceOf(useTeamStore.getState(), LEAD_A)
    const b = teamSliceOf(useTeamStore.getState(), LEAD_B)
    expect(a.messages.map((m) => m.text), 'A 只看到自己团队的发言').toEqual(['A 的队员'])
    expect(a.unread).toBe(1)
    expect(b.messages.map((m) => m.text)).toEqual(['B 的队员', 'B 的队员 2'])
    expect(b.unread).toBe(2)
  })

  it('setTeamName 切换当前指针；同团队重复声明保留消息流', () => {
    const st = useTeamStore.getState()
    st.setTeamName('team-a', LEAD_A)
    st.addMessage(LEAD_A, msg('保留我'))
    st.setTeamName('team-a', LEAD_A)   // 轮询/重挂载重复声明
    expect(teamSliceOf(useTeamStore.getState(), LEAD_A).messages).toHaveLength(1)

    st.setTeamName('team-b', LEAD_B)
    expect(useTeamStore.getState().leadSessionId).toBe(LEAD_B)
    // A 的桶不受影响
    expect(teamSliceOf(useChatStoreState(), LEAD_A).messages).toHaveLength(1)
  })

  it('⭐ clear() 不带参 = 只解绑指针，保留各团队缓存；带 lead = 删该团队的桶', () => {
    const st = useTeamStore.getState()
    st.setTeamName('team-a', LEAD_A)
    st.addMessage(LEAD_A, msg('A 缓存'))
    st.setTeamName('team-b', LEAD_B)

    st.clear()   // 切到「无团队会话」
    expect(useTeamStore.getState().leadSessionId, '指针已解绑').toBeNull()
    expect(teamSliceOf(useTeamStore.getState(), LEAD_A).messages, 'A 的缓存仍在（切回不重拉）').toHaveLength(1)

    st.clear(LEAD_A)  // 团队 A 解散
    expect(teamSliceOf(useTeamStore.getState(), LEAD_A)).toBe(EMPTY_TEAM_SLICE)
    expect(teamSliceOf(useTeamStore.getState(), LEAD_B).teamName, 'B 不受影响').toBe('team-b')
  })

  it('未读只清零目标团队', () => {
    const st = useTeamStore.getState()
    st.setTeamName('team-a', LEAD_A)
    st.setTeamName('team-b', LEAD_B)
    st.addMessage(LEAD_A, msg('a'))
    st.addMessage(LEAD_B, msg('b'))

    st.markAllRead(LEAD_A)
    expect(teamSliceOf(useTeamStore.getState(), LEAD_A).unread).toBe(0)
    expect(teamSliceOf(useTeamStore.getState(), LEAD_B).unread).toBe(1)
  })

  it('空 lead → 动作直接忽略（不猜测归属、不落到"当前团队"）', () => {
    const st = useTeamStore.getState()
    st.setTeamName('team-a', LEAD_A)
    st.addMessage('', msg('无主消息'))
    expect(teamSliceOf(useTeamStore.getState(), LEAD_A).messages).toHaveLength(0)
    expect(useTeamStore.getState().byLead['']).toBeUndefined()
  })
})

/** 小工具：避免每个断言都写 useTeamStore.getState()。 */
function useChatStoreState() {
  return useTeamStore.getState()
}
