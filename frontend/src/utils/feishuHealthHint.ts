/**
 * 飞书创建后自检 / 平台策略错误（21001）的用户可读提示。
 *
 * <p><b>WHY</b>：一键创建成功只代表应用建出来了；豆包账号创建的自建应用会被飞书平台策略
 * 拒绝换票（{@code code=21001}）。不在这里讲清楚，用户会去点「同步」（0 个会话）和「授权」
 * （飞书授权页显示「无需授权任何权限」），误以为已经授权、只是没同步。
 */

export interface FeishuProbeHealth {
  ok: boolean
  code?: string | null
  message?: string | null
  botName?: string | null
  chatVisible?: string | null
}

/** 平台策略拒绝的标记（飞书 token 换取返回 21001）。 */
export function isFeishuPlatformPolicyDenied(text: string | null | undefined): boolean {
  return !!text && text.includes('21001')
}

/**
 * 创建后自检失败 → 一句能照做的提示。
 *
 * <p>21001 单独成句：它不是配置错，改 scope/重授权都没用，只能换账号主体或让管理员手动建应用。
 */
export function feishuProbeFailureHint(h: FeishuProbeHealth): string {
  const raw = h.message ?? ''
  if (h.code === '21001' || isFeishuPlatformPolicyDenied(raw)) {
    return '飞书平台策略拒绝了该应用（21001）：豆包账号无法为自建应用换取令牌。'
      + '请换飞书个人/企业账号重新一键创建，或让管理员手动建应用后填写 App ID / Secret。'
  }
  return `飞书凭证自检失败：${raw || h.code || '未知错误'}`
}

/** 同步/发送返回文本里带 21001 时追加的短提示（长文案只在创建自检时出现一次）。 */
export function feishuPolicySuffix(text: string | null | undefined): string {
  return isFeishuPlatformPolicyDenied(text)
    ? '；飞书平台策略拒绝（21001）：豆包账号无法换票，请换飞书账号/租户'
    : ''
}
