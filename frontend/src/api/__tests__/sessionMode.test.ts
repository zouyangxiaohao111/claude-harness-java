import { describe, expect, it, vi, beforeEach } from 'vitest'
import { sessionApi } from '../sessions'
import { ApiError } from '../rest'
import { API_V1_BASE } from '../base'

/**
 * [V329] 会话模式端点：前端只认 `success === true && code === 0`。
 *
 * <p>WHY：业务失败（无渠道 / Q8 冲突）是 HTTP 200 + `success:false` + 数字码；
 * 若拆包只看 HTTP 状态，会把失败当成功，入口显示「已开启」而模型工具池没变。
 */
describe('sessionApi mode (V329)', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  const json = (body: unknown) =>
    new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

  it('getMode 拆 CommonResult：code 0 + success true → 返回 data', async () => {
    const data = { sessionId: 'sess-1', unattended: true, brief: true, channels: 1, canEnableUnattended: true }
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ code: 0, msg: '成功', success: true, data }))

    await expect(sessionApi.getMode('sess-1')).resolves.toEqual(data)
  })

  it('setUnattended 业务失败（200 + success:false + code 400）→ 抛 ApiError，带后端 msg', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({
      code: 400, msg: '请先配置渠道', success: false, data: null,
    }))

    await expect(sessionApi.setUnattended('sess-1', true)).rejects.toMatchObject({
      status: 200, message: '请先配置渠道',
    })
  })

  it('setBrief 在无人值守下关闭（code 409）→ 抛 ApiError（需要用户确认），不静默成功', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({
      code: 409, msg: '无人值守正在开启，关闭回答直达会同时退出无人值守。请确认后再关闭。', success: false, data: null,
    }))

    const p = sessionApi.setBrief('sess-1', false)
    await expect(p).rejects.toBeInstanceOf(ApiError)
    await expect(p).rejects.toThrow('请确认后再关闭')
  })

  it('[Q8] 确认关闭 ⇒ body 带 cascadeExitUnattended=true（一次请求原子级联）', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({
      code: 0, success: true,
      data: { sessionId: 'sess-1', unattended: false, brief: false, channels: 1, canEnableUnattended: true },
    }))
    await sessionApi.setBrief('sess-1', false, true)
    expect(spy).toHaveBeenCalledWith(
      `${API_V1_BASE}/sessions/sess-1/mode/brief`,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ enabled: false, cascadeExitUnattended: true }),
      }),
    )
  })

  it('PUT 到正确路径与 body {enabled}', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({
      code: 0, success: true, data: { sessionId: 'sess-1', unattended: false, brief: false, channels: 0, canEnableUnattended: false },
    }))
    await sessionApi.setUnattended('sess-1', false)
    expect(spy).toHaveBeenCalledWith(
      `${API_V1_BASE}/sessions/sess-1/mode/unattended`,
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ enabled: false }) }),
    )
  })
})
