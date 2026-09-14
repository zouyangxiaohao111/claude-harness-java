import { describe, expect, it } from 'vitest'
import { resolveNewSessionProjectId } from '../newSessionProject'

/** 与 App.tsx `EMPTY_PROJECT` 同形的「无项目」值（id 为空串是它的表示）。 */
const EMPTY_PROJECT_ID = ''

describe('resolveNewSessionProjectId', () => {
  it('(i) 无活跃会话 + 无 per-session 项目 → null · WHY：全新安装（0 项目）点「+ 新会话」时必须拿到「无处可绑」信号，调用方才可能改成引导选项目而不是造一个未绑定会话', () => {
    expect(resolveNewSessionProjectId(undefined, undefined)).toBeNull()
    expect(resolveNewSessionProjectId(null, null)).toBeNull()
  })

  it('(ii) 活跃会话未绑定 + per-session main 是 EMPTY_PROJECT（id===\'\'）→ null · WHY：钉住空串三态 —— `??` 不拦空串，若这里返回 \'\' 就会发出 "mainProjectId":""，落库成未绑定会话', () => {
    // 这是区分「显式收口空串」与「`??` 语义」的唯一装置：(i) 用 `??` 也绿，只有本态会分叉。
    expect(resolveNewSessionProjectId(null, EMPTY_PROJECT_ID)).toBeNull()
    expect(resolveNewSessionProjectId(undefined, EMPTY_PROJECT_ID)).toBeNull()
    // 两个来源同时为空串 —— 仍必须是 null（不得因「两个都没值」而例外放行）
    expect(resolveNewSessionProjectId(EMPTY_PROJECT_ID, EMPTY_PROJECT_ID)).toBeNull()
  })

  it('(ii-b) 纯空白串等同空串 → null · WHY：判据必须与后端 @NotBlank 一致（空白不是有效项目 id）；后端用 @NotBlank 而非 @NotNull，前端不能只挡 \'\'', () => {
    expect(resolveNewSessionProjectId('   ', null)).toBeNull()
    expect(resolveNewSessionProjectId(null, '\t')).toBeNull()
  })

  it('(iii) 活跃会话已绑定项目 → 该 id · WHY：正常路径（已有项目的会话点「+ 新会话」）必须沿用当前项目，且优先于 per-session 缓存', () => {
    expect(resolveNewSessionProjectId('proj-active', null)).toBe('proj-active')
    expect(resolveNewSessionProjectId('proj-active', 'proj-persession')).toBe('proj-active')
  })

  it('(iv) 活跃会话未绑定但 per-session main 有真实 id → 该 id · WHY：切换项目后 activeSession 尚未刷新，退路必须仍能取到真实项目，否则会误判「无项目」而弹引导', () => {
    expect(resolveNewSessionProjectId(null, 'proj-persession')).toBe('proj-persession')
    expect(resolveNewSessionProjectId(undefined, 'proj-persession')).toBe('proj-persession')
  })
})
