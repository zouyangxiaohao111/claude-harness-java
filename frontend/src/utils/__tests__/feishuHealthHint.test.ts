import { describe, expect, it } from 'vitest'
import {
  feishuPolicySuffix,
  feishuProbeFailureHint,
  isFeishuPlatformPolicyDenied,
} from '../feishuHealthHint'

/**
 * [21001] 创建后自检提示。锁两件事：21001 必须被识别并给出「换账号」的可执行文案；
 * 其它错误不得被误判成平台策略（否则用户会去换账号，而实际是凭证填错）。
 */
describe('feishuHealthHint', () => {
  it('code=21001 → 平台策略文案，且明确指向豆包账号/换账号', () => {
    const hint = feishuProbeFailureHint({
      ok: false,
      code: '21001',
      message: 'Access denied due to platform policy.',
    })
    expect(hint).toContain('21001')
    expect(hint).toContain('豆包')
    expect(hint).toContain('换飞书')
  })

  it('只有 message 含 21001 也识别（后端 code 可能为空）', () => {
    expect(feishuProbeFailureHint({
      ok: false, message: '飞书换取 tenant_access_token 失败：code=21001 msg=Access denied due to platform policy.',
    })).toContain('豆包')
  })

  it('其它失败 → 原样原因，不误判为平台策略', () => {
    const hint = feishuProbeFailureHint({
      ok: false, code: '10003', message: 'invalid app_secret',
    })
    expect(hint).toContain('invalid app_secret')
    expect(hint).not.toContain('豆包')
  })

  it('同步返回文本里的 21001 → 追加短提示；其它文本不加', () => {
    expect(feishuPolicySuffix('换取飞书令牌失败：code=21001')).toContain('豆包')
    expect(feishuPolicySuffix('已同步 3 个会话')).toBe('')
    expect(isFeishuPlatformPolicyDenied(null)).toBe(false)
  })
})
