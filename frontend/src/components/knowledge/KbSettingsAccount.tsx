import type { JSX } from 'react'
import { KB_SETTINGS_NAV, KB_SETTING_NOT_READY } from './kbSettingsTypes'

/**
 * 「账户」组面板 · 按 navKey 渲染子面板。
 *
 * <h2>为什么 5 项全是占位</h2>
 * 用户 2026-11-08 明确：知识库场景里也应该有<b>用户体系</b>，但<b>暂时不接入</b>。
 * 当前是单机单主体，没有成员账号，所以这 5 项（通用 / 用户信息 / 助手 / 我的记忆 /
 * 环境变量）都没有可保存的归属对象。
 *
 * <h2>⚠️ 文案不是「四个字了事」</h2>
 * 每一项都写了<b>具体子项名 + 为什么不可用</b>，因为用户要的是「诚实说明」：
 * 只说「暂未开发」用户无法判断是自己不会用、还是功能真没有。
 *
 * <h2>类名依据</h2>
 * 逐层照源站 `views/settings/Settings.vue` 的通用面板骨架（样式在 `styles/kb/kb-10.css`）：
 * `.section > .section-header(h2 + .section-description) + .settings-group >
 * .setting-row > .setting-info(label + .desc) + .setting-control > .ext-tag`。
 * 未引入任何新类名，也未写 CSS。
 */

/** 单项面板的静态文案（无需加载数据，所以不走 useEffect）。 */
interface AccountPanelCopy {
  /** 分区的语义说明（`.section-description`）。 */
  description: string
  /** 该组一个具体子项名（`label`）。 */
  subItem: string
  /** 为什么不可用的诚实说明（`.desc`）。 */
  subDesc: string
}

/**
 * 5 个账户子面板的文案表。
 *
 * <p>顺序与 `KB_SETTINGS_NAV` 的 account 组一致；label 本身不在这里重复，
 * 统一从 `KB_SETTINGS_NAV` 取（单一真源，改名不会两边漂移）。
 */
const ACCOUNT_PANEL_COPY: Record<string, AccountPanelCopy> = {
  general: {
    description: '个人偏好：主题、语言与字号。',
    subItem: '主题、语言与字号',
    subDesc:
      '用户体系暂未接入（当前为单机单主体），没有账户维度可保存这些偏好，外观仍沿用客户端全局设置。',
  },
  userprofile: {
    description: '昵称、头像、邮箱等身份资料。',
    subItem: '昵称、头像与邮箱',
    subDesc: '用户体系暂未接入（当前为单机单主体），没有成员账号可挂载个人资料。',
  },
  assistant: {
    description: '个人助手的偏好设置。',
    subItem: '个人助手偏好',
    subDesc: '用户体系暂未接入（当前为单机单主体），助手偏好无法按成员维度独立保存。',
  },
  mymemory: {
    description:
      '个人记忆条目管理。与「空间 → 长期记忆」不同：那是工作区级的长期记忆开关，这里是成员自己的记忆条目。',
    subItem: '我的记忆条目',
    subDesc:
      '用户体系暂未接入（当前为单机单主体），个人记忆条目没有归属的成员，因此暂不提供增删改。',
  },
  envvars: {
    description: '成员自己的技能与沙箱密钥。',
    subItem: '技能与沙箱密钥',
    subDesc: '沙箱与技能密钥尚未接入，暂无法为每个成员保存独立的运行密钥。',
  },
}

/** 从导航契约里取该项的显示名，保证与左侧导航逐字一致。 */
function accountLabel(navKey: string): string {
  const accountGroup = KB_SETTINGS_NAV.find((g) => g.key === 'account')
  return accountGroup?.items.find((i) => i.key === navKey)?.label ?? '未知设置项'
}

/**
 * 「账户」组面板。
 *
 * @param navKey 组内项 key：general / userprofile / assistant / mymemory / envvars
 */
export function KbSettingsAccount({ navKey }: { navKey: string }): JSX.Element {
  const copy = ACCOUNT_PANEL_COPY[navKey]

  // 兜底：navKey 不在账户组内时给一个可读的空面板，⛔ 不抛异常、不返回 null（否则白屏）。
  if (!copy) {
    return (
      <div className="section">
        <div className="section-header">
          <h2>未知设置项</h2>
          <p className="section-description">当前 key「{navKey}」不属于「账户」组。</p>
        </div>
      </div>
    )
  }

  return (
    <div className="section">
      <div className="section-header">
        <h2>{accountLabel(navKey)}</h2>
        <p className="section-description">{copy.description}</p>
      </div>
      <div className="settings-group">
        <div className="setting-row">
          <div className="setting-info">
            <label>{copy.subItem}</label>
            <p className="desc">{copy.subDesc}</p>
          </div>
          <div className="setting-control">
            <span className="ext-tag">{KB_SETTING_NOT_READY}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
