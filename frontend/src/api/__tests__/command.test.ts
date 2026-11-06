import { describe, expect, it, vi, beforeEach } from 'vitest'
import { commandApi, isPromptExecuteResult } from '../command'
import { API_BASE } from '../base'

describe('commandApi', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  it('list 走 /api/command 前缀（非 /api/v1）· 根地址取自单一来源 base.ts', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    await commandApi.list()
    expect(spy).toHaveBeenCalledWith(`${API_BASE}/command`, expect.objectContaining({ method: 'GET' }))
  })

  /**
   * <b>WHY（2026-11-02 用户实测「/compact 报 JSON 解析异常」）</b>：
   * `POST /builtins/compact/execute` 的字面端点在 Spring 侧声明
   * `produces="text/plain;charset=UTF-8"`，且字面路由**优先于** `{name}` 路径变量 ⇒
   * /compact 实际命中纯文本端点。此前本 API 硬编码 `Accept: application/json`
   * ⇒ 内容协商失败 406 ⇒ 错误体是 HTML ⇒ 前端 `JSON.parse` 抛 `Unexpected token 'W'`。
   * 这条断言锁住「Accept 必须放宽」这一契约，防止日后又被收紧回 json。
   */
  it('executeBuiltin：Accept 必须放宽（/compact 端点返回 text/plain，写死 json 会 406）', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('Compacted 12k -> 3k', { status: 200, headers: { 'Content-Type': 'text/plain;charset=UTF-8' } }),
    )
    const out = await commandApi.executeBuiltin('compact', 'sess-1')
    const init = spy.mock.calls[0]![1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers.Accept).toBe('*/*')
    expect(headers.Accept).not.toBe('application/json')
    // 且纯文本响应必须原样返回，不能被当成 JSON 解析
    expect(out).toBe('Compacted 12k -> 3k')
  })

  /**
   * <b>WHY（2026-11-04 用户实测「发了 /init 结果后端没执行」）</b>：
   * `/init` 是 `type=prompt` 命令，价值全在提示词正文。后端旧实现只回命令元数据
   * （`BuiltInCommandDto`，无 content）⇒ 前端弹「已执行 /init」但模型从未收到指令。
   * 后端现已改为回 `{type:'prompt', content}`，前端必须**能认出这个形态**并把它当
   * user 消息注入对话；认错（当成元数据/纯文本）就等于回到「静默假成功」。
   */
  it('isPromptExecuteResult：只认 {type:"prompt" + 非空 content}，元数据/纯文本都不算', () => {
    expect(isPromptExecuteResult({ type: 'prompt', name: 'init', content: 'Set up a minimal CLAUDE.md' })).toBe(true)
    // 元数据薄触发形态（旧故障）→ 必须不认
    expect(isPromptExecuteResult({ name: 'init', type: 'prompt', description: 'x', aliases: [] })).toBe(false)
    // /compact 的 text/plain 响应 → 不认
    expect(isPromptExecuteResult('Compacted 12k -> 3k')).toBe(false)
    // content 空 → 不认（注入了等于没注入）
    expect(isPromptExecuteResult({ type: 'prompt', name: 'init', content: '' })).toBe(false)
    expect(isPromptExecuteResult(null)).toBe(false)
    expect(isPromptExecuteResult(undefined)).toBe(false)
  })
})
