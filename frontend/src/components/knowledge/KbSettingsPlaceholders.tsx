import type { JSX } from 'react'
import { KB_SETTINGS_NAV, KB_SETTING_NOT_READY } from './kbSettingsTypes'

/**
 * 「发布集成 / 系统管理 / 平台」三组的占位面板。
 *
 * <h2>为什么这三组全是占位</h2>
 * 用户 2026-11-08 定的口径：<b>「后端有的则对接，没有点击则是暂未开发」</b>。
 * 这三组（Claw 集成 / 系统设置 / 模型目录 / 任务队列 / API 密钥 / 审计日志 / 版本信息）
 * 后端目前都没有对应端点，所以统一渲染「暂未开发」，⛔ 不伪造可交互控件。
 *
 * <h2>为什么文案从 `KB_SETTINGS_NAV` 反查而不是各写一份</h2>
 * 左侧导航与右侧标题必须逐字一致。若这里再硬编码一份 label，
 * 改名时必然漏改一处 ⇒ 左边叫「审计日志」右边叫「操作日志」。
 *
 * <h2>类名依据</h2>
 * 与账户组一致，沿用源站通用面板骨架（样式在 `styles/kb/kb-10.css`）：
 * `.section > .section-header(h2) + .settings-group >
 * .setting-row > .setting-info(label + .desc) + .setting-control > .ext-tag`。
 */

/** 占位项的统一说明文案（不随项变化，所以只写一处）。 */
const PLACEHOLDER_DESC = '后端尚未提供该能力。'

/** 找不到 groupKey / navKey 时的兜底标题。 */
const UNKNOWN_TITLE = '未知设置项'

/**
 * 「发布集成 / 系统管理 / 平台」三组的占位面板。
 *
 * @param groupKey 组 key（integrations / system_administration / platform）
 * @param navKey   组内项 key
 */
export function KbSettingsPlaceholder({
  groupKey,
  navKey,
}: {
  groupKey: string
  navKey: string
}): JSX.Element {
  // 先按组 key 找组，再按项 key 找项 —— 两级都查，避免别组的 key 撞名时渲染错标题。
  const group = KB_SETTINGS_NAV.find((g) => g.key === groupKey)
  const item = group?.items.find((i) => i.key === navKey)

  // 兜底：groupKey 或 navKey 任一找不到 ⇒ 渲染可读的空面板。
  // ⛔ 不抛异常、不返回 null —— 抛出会让整个设置弹窗崩掉，返回 null 会白屏。
  if (!group || !item) {
    return (
      <div className="section">
        <div className="section-header">
          <h2>{UNKNOWN_TITLE}</h2>
          <p className="section-description">
            {group ? `当前项「${navKey}」不在「${group.label}」组内。` : `当前分组「${groupKey}」不存在。`}
          </p>
        </div>
      </div>
    )
  }

  // 标题 = 导航项 label（与左侧逐字一致）；说明只写一次，避免同一句话在页面上出现两遍。
  return (
    <div className="section">
      <div className="section-header">
        <h2>{item.label}</h2>
      </div>
      <div className="settings-group">
        <div className="setting-row">
          <div className="setting-info">
            <label>{item.label}</label>
            <p className="desc">{PLACEHOLDER_DESC}</p>
          </div>
          <div className="setting-control">
            <span className="ext-tag">{KB_SETTING_NOT_READY}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
