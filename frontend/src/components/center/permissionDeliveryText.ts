/**
 * [D 未送达上抛] 把一条投递失败折成一句人话（普通权限卡与桌面控制卡共用）。
 *
 * <p>抽成独立模块的唯一原因：两张卡都要展示同一句话，⛔ 不能各写一份
 * （那种重复迟早会分叉成「一个卡说未激活、另一个卡说失败」）。
 */

/** 渠道中文名（只这 4 个平台；未知平台原样显示）。 */
const CHANNEL_LABEL: Record<string, string> = {
  wechat: '微信',
  feishu: '飞书',
  wecom: '企业微信',
  dingtalk: '钉钉',
}

/**
 * 一条投递失败 → 一句人话。
 *
 * <p>微信 {@code IM_REPLY_TOKEN_MISSING} 是「没先给机器人发过消息 ⇒ 没有 context_token」，
 * 必须告诉用户**下一步做什么**（发一条消息），而不是只说"没发出去"。
 */
export function deliveryFailureText(f: {
  channel?: string | null
  peerName?: string | null
  reasonCode?: string | null
  reason?: string | null
}): string {
  const ch = f.channel ? (CHANNEL_LABEL[f.channel] ?? f.channel) : '渠道'
  const who = f.peerName?.trim()
  const base = who ? `${ch}「${who}」` : ch
  if (f.reasonCode === 'IM_REPLY_TOKEN_MISSING') {
    return `${base}未激活：需先在${ch}里给机器人发一条消息`
  }
  return `${base}未送达${f.reason ? `：${f.reason}` : ''}`
}
