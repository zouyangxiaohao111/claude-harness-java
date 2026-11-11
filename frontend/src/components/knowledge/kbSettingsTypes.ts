/**
 * 知识库场景设置 · 共享类型与常量（多组件共用，单一真源）。
 *
 * <p><b>为什么单独一个文件</b>：设置弹窗外壳（导航）和 5 个面板分属不同组件，
 * 若各自硬编码 navKey 字符串，改名时必然漏改一处 ⇒ 面板永远渲染不出来。
 * 这里把 key / 分组 / 标签集中定义，外壳与面板都 import 它。
 *
 * <p><b>分组口径</b>（对齐源站 WeKnora `views/settings/Settings.vue:409-450`）：
 * 源站 7 组 = 账户 / 空间 / 模型 / 发布集成 / 数据与扩展 / 系统管理 / 平台。
 * <ul>
 *   <li><b>「模型」组本仓不显示</b> —— 用户 2026-11-08 明确：我们有独立的模型管理页面。</li>
 *   <li>其余三组（发布集成 / 系统管理 / 平台）显示，但组内各项点击为「暂未开发」。</li>
 *   <li>账户 / 空间两组：用户体系暂不接入（当前为单机单主体）。</li>
 *   <li>数据与扩展：向量存储 / 解析引擎 / 存储引擎**真接后端**；沙箱暂未开发；
 *       网络搜索**只读展示** `settings.websearchEngine`（配置入口在客户端「环境配置」）。</li>
 * </ul>
 *
 * <p>⛔ <b>与客户端全局设置是两份，互不影响</b>：客户端那份是
 * `components/modals/SettingsModal.tsx`（`.settings-backdrop` + `.settings-tabs`
 * 的自造结构）。本文件服务的是**知识库场景内**的设置窗（`.kb-scope` 子树），
 * 两类 DOM / 样式各归各的。
 */

/** 设置分组 key。 */
export type KbSettingsGroupKey =
  | 'account'
  | 'workspace'
  | 'integrations'
  | 'data_extensions'
  | 'system_administration'
  | 'platform'

/** 单个设置项（导航里的一个可点行）。 */
export interface KbSettingsNavItem {
  /** 唯一 key，同时决定右侧渲染哪个面板。 */
  key: string
  /** 导航文字。 */
  label: string
  /** tdesign 图标名（形如 `user` / `control-platform`，渲染为 `#t-icon-<icon>`）。 */
  icon: string
}

/** 一个分组（导航里的一段）。 */
export interface KbSettingsNavGroup {
  key: KbSettingsGroupKey
  label: string
  items: KbSettingsNavItem[]
}

/**
 * 导航分组 · 顺序与项名逐字对齐源站。
 *
 * <p>⚠️ 源站「模型」组（`models` / `ollama` / `weknoracloud`）**有意不列**，
 * 原因见文件头。这是与源站的**有意偏离**，⛔ 不要「补全」回去。
 */
export const KB_SETTINGS_NAV: KbSettingsNavGroup[] = [
  {
    key: 'account',
    label: '账户',
    items: [
      { key: 'general', label: '通用', icon: 'setting' },
      { key: 'userprofile', label: '用户信息', icon: 'user' },
      { key: 'assistant', label: '助手', icon: 'assistant' },
      { key: 'mymemory', label: '我的记忆', icon: 'bookmark' },
      { key: 'envvars', label: '环境变量', icon: 'key' },
    ],
  },
  {
    key: 'workspace',
    label: '空间',
    items: [
      { key: 'tenant', label: '空间信息', icon: 'user-circle' },
      { key: 'members', label: '成员管理', icon: 'usergroup' },
      { key: 'defaults', label: '默认配置', icon: 'setting-1' },
      { key: 'chathistory', label: '消息管理', icon: 'chat' },
      { key: 'memory', label: '长期记忆', icon: 'bulletpoint' },
    ],
  },
  {
    key: 'integrations',
    label: '发布集成',
    items: [
      { key: 'integration-claw', label: 'Claw', icon: 'integration' },
    ],
  },
  {
    key: 'data_extensions',
    label: '数据与扩展',
    items: [
      { key: 'vectorstore', label: '向量存储', icon: 'data-base' },
      { key: 'parser', label: '解析引擎', icon: 'file-search' },
      { key: 'storage', label: '存储引擎', icon: 'cloud' },
      { key: 'sandbox', label: '沙箱', icon: 'code' },
      { key: 'websearch', label: '网络搜索', icon: 'search' },
    ],
  },
  {
    key: 'system_administration',
    label: '系统管理',
    items: [
      { key: 'system-global', label: '系统设置', icon: 'server' },
      { key: 'model-catalog', label: '模型目录', icon: 'control-platform' },
      { key: 'runtime-queues', label: '任务队列', icon: 'queue' },
      { key: 'platform-api-keys', label: 'API 密钥', icon: 'secured' },
      { key: 'system-audit-log', label: '审计日志', icon: 'history' },
    ],
  },
  {
    key: 'platform',
    label: '平台',
    items: [
      { key: 'system', label: '版本信息', icon: 'info-circle' },
    ],
  },
]

/** 默认选中的项（打开设置窗时）。 */
export const KB_SETTINGS_DEFAULT_KEY = 'general'

/**
 * 「暂未开发」的统一文案 —— 所有占位面板共用一个说法，避免各写各的。
 *
 * <p>用户 2026-11-08 定的口径：<b>「后端有的则对接，没有点击则是暂未开发」</b>。
 */
export const KB_SETTING_NOT_READY = '暂未开发'

/**
 * 判断某一项是否「后端已有能力、可真接」。
 *
 * @param key 设置项 key
 * @returns true = 有真实后端对接；false = 渲染「暂未开发」占位
 */
export function isKbSettingWired(key: string): boolean {
  switch (key) {
    // 真接：三个下拉元数据端点 + 长期记忆（16 端点 · V272 已迁移）
    case 'vectorstore':
    case 'parser':
    case 'storage':
    case 'memory':
      return true
    // 只读展示（settings.websearchEngine），不算「接线」，也不标暂未开发
    case 'websearch':
      return true
    default:
      return false
  }
}
