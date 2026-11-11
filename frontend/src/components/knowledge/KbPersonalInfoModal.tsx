import { useEffect } from 'react'
import type { JSX } from 'react'

/**
 * 知识库场景 · 「个人信息」弹窗（只读）。
 *
 * <h2>为什么整个弹窗是只读的</h2>
 * 用户 2026-11-08 明确：知识库场景左下角用户区点开后是向上抽屉，抽屉里
 * 「个人设置」→ 打开一个<b>简单的个人信息弹窗</b>。
 * 但本仓当前是<b>单机单主体</b>，用户体系（账号 / 成员 / 登录态）<b>暂未接入</b>：
 * 没有后端接口可读写昵称与邮箱，也没有账号归属对象可以保存修改。
 * ⇒ 这里只做<b>展示</b>，不提供输入框、不提供保存按钮，避免做出一个「填了也存不下来」的假表单。
 *
 * <h2>结构依据（逐层对齐源站，不自造 class）</h2>
 * - 外壳：源侧 `SettingsModalShell.vue:4-11` 的 `.settings-modal-shell.settings-overlay`
 *   ＋ `.settings-modal` ＋ `.close-btn` ＋ `.settings-container`，与
 *   `KbCreateModal.tsx:405-418` 完全同构（两个外壳类缺一不可，否则只有半套样式）。
 * - 用户区：源侧 `UserMenu.vue:53-71`（头像圆 + 姓名行 + 邮箱行）。其 `.dropdown-user-*`
 *   类在本仓 `src/styles` 里 **0 条规则**（已 grep 确认）⇒ 这里用行内排布复现形态，
 *   不写 CSS、也不引用空类名。
 * - 三行信息：源侧设置面板通用骨架 `.settings-group > .setting-row >`
 *   `.setting-info(label + .desc) + .setting-control`，与 `KbSettingsAccount.tsx:102-112` 一致。
 *
 * <h2>⚠️ 为什么外层要自己包一层 `.kb-scope`</h2>
 * `.settings-modal-shell` / `.settings-modal` / `.close-btn` / `.setting-row` 等规则
 * 全部写成 `.kb-scope .xxx`（**后代**选择器：`kb-shared.css:658-734`、`kb-06.css:279-281`），
 * ⇒ 必须存在 `.kb-scope` 祖先才有样式。
 * 而 `.kb-scope` 本人在 `kb-scope.css` 里<b>只有 CSS 变量、没有任何布局属性</b>
 * ⇒ 不给它加 `position/inset`（那是自造布局，还会改变 fixed 的包含块判定）。
 * 因此照本仓既有做法（`MemoryEditorModal.tsx:291`、`TraceView.tsx:341`）用
 * `display:contents` 包裹：不生成盒子、不参与布局，只把变量与作用域带给子树。
 *
 * <h2>⚠️ 三处行内复位（是把「被提升的规则」还原成源站值，不是新造样式）</h2>
 * 1. `section-header`：`kb-shared.css:722` 把源站<b>只有</b> `.kb-chunking-settings` 用的
 *    sticky 吸顶页头提升成了全局规则（`position:sticky; top:-24px;
 *    padding:24px 32px 12px; margin:-24px -32px 16px`）。本弹窗没有
 *    `.content-wrapper` 的 22px 内边距托底，照搬会把标题顶出弹窗并被 `overflow:hidden` 裁掉
 *    ⇒ 复位成普通块级流（同 `KbCreateModal.tsx:31-40` 的 `SECTION_HEADER_FLAT`）。
 * 2. `settings-container`：规则是 `display:flex`（为「侧栏 + 内容」两栏准备的），
 *    本弹窗没有侧栏 ⇒ 行内改回块级。
 * 3. `setting-control`：见 {@link CONTROL_STYLE}（`min-width:300px` 会把右列挤出 480px 弹窗）。
 */

/** 弹窗入参：`user` 缺省时用单机默认账号（本仓无用户体系，由调用方决定是否覆盖）。 */
export interface KbPersonalInfoModalProps {
  open: boolean
  onClose: () => void
  /** 展示用的用户信息（本仓暂无用户体系，由调用方给默认值） */
  user?: { name: string; email: string }
}

/** 单机默认账号：与知识库场景左下角用户区（`admin` / `admin@weknora.local`）保持一致。 */
const DEFAULT_USER = { name: 'admin', email: 'admin@weknora.local' }

/** 本仓没有任何用户头像图片资源 ⇒ 按源站「无头像时」的做法用姓名首字母（绿底白字圆）。 */
const AVATAR_STYLE = {
  width: 48,
  height: 48,
  borderRadius: '50%',
  background: '#07c05f',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 20,
  fontWeight: 600,
  flexShrink: 0,
} as const

/**
 * 右列复位。
 *
 * WHY（按 `index.css` 的导入顺序读级联后的实际生效值，不是猜）：
 * - 最后一批是 `kb-shared.css`，其中<b>最后一条</b>声明 `min-width` 的是
 *   `:716` 的 `.kb-scope .setting-control{width:45%;min-width:300px}`
 *   （来源是 bundle 里未加作用域的那条，`kb-06.css:317` 有同类注释）
 *   ⇒ 实际生效 `min-width:300px`；同时 `:766` 的 `flex:0 0 55%` 生效。
 * - 本弹窗 `maxWidth:480`，减左右内边距 32×2 ⇒ 内容宽 416px。
 *   左列 `flex:0 0 40%`（166px）+ 24px 右内边距 = 190px，行 `gap:28px`（`:714` 未被覆盖），
 *   右列 300px ⇒ 合计 518px > 416px，右列会被 `.settings-modal{overflow:hidden}` 裁掉。
 * ⇒ 行内改成可收缩（取源站紧凑形态 `kb-10.css:42` 的 `flex:1 1 auto;min-width:0`）并解除 55% 上限。
 */
const CONTROL_STYLE = { flex: '1 1 auto', minWidth: 0, maxWidth: 'none' } as const

/** 只读值：右列窄，长文案必须允许断行（源站 `.info-value{word-break:break-word;text-align:right}`）。 */
const VALUE_STYLE = { textAlign: 'right', wordBreak: 'break-word', minWidth: 0 } as const

/** 源站 `.section-header` 的普通块级流形态（复位 `kb-shared.css:722` 的 sticky 提升）。 */
const SECTION_HEADER_FLAT = {
  position: 'static',
  top: 'auto',
  zIndex: 'auto',
  display: 'block',
  padding: 0,
  borderBottom: 'none',
  margin: '0 0 20px',
} as const

/**
 * 「个人信息」弹窗。
 *
 * @param open    是否打开；`false` 时返回 `null`（完全不渲染，避免残留遮罩层拦截场景点击）
 * @param onClose 关闭回调（点遮罩 / 点 ✕ / 按 Esc 都会触发）
 * @param user    展示用账号；缺省走 {@link DEFAULT_USER}
 */
export function KbPersonalInfoModal({ open, onClose, user }: KbPersonalInfoModalProps): JSX.Element | null {
  // Esc 关闭（本仓 KbSettingsModal 的关闭按钮也是这个语义）。⚠️ 必须在 return null 之前调用，
  // 否则就是条件式 hook 调用，React 会直接报错。
  useEffect(() => {
    if (!open) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  // 关闭状态下不渲染任何节点：否则 `position:fixed; inset:0` 的遮罩会一直盖在场景上
  if (!open) return null

  const account = user ?? DEFAULT_USER

  // 首字母：取展示名第一个字符并大写（源站 `userInitial` 同义）；空名兜底 'A'，避免头像空白
  const initial = (account.name.trim().charAt(0) || 'A').toUpperCase()

  return (
    /* `.kb-scope` 只做作用域与变量宿主（该 class 自身无布局规则）⇒ display:contents 不产生盒子 */
    <div className="kb-scope" style={{ display: 'contents' }}>
      {/* 点遮罩关闭；点弹窗主体时阻止冒泡，避免误关 */}
      <div
        className="settings-modal-shell settings-overlay"
        style={{ zIndex: 1100 }}
        onClick={onClose}
      >
        <div
          className="settings-modal"
          style={{ maxWidth: 480, height: 'auto' }}
          role="dialog"
          aria-modal="true"
          aria-label="个人信息"
          onClick={(e) => e.stopPropagation()}
        >
          {/* close-btn 是 .settings-modal 的直接子元素（源侧 SettingsModalShell.vue:11，绝对定位） */}
          <button type="button" className="close-btn" onClick={onClose} aria-label="关闭" title="关闭">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M15 5L5 15M5 5L15 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </button>

          {/* `.settings-container` 在源站是「侧栏 + 内容」两栏 flex；本弹窗无侧栏 ⇒ 行内改块级。
              内边距原由 `.content-wrapper` 提供，本弹窗只有 `.settings-container > .section`，
              故在 `.section` 上补同量级内边距（取 kb-shared/css 小档附近的 24/32），避免文字贴边。 */}
          <div className="settings-container" style={{ display: 'block', height: 'auto' }}>
            <div className="section" style={{ margin: 0, padding: '24px 32px' }}>
              <div className="section-header" style={SECTION_HEADER_FLAT}>
                <h2>个人信息</h2>
              </div>

              <div className="settings-group">
                {/* ── 头像 + 姓名 + 邮箱（源侧 UserMenu.vue:53-71 的用户区形态，行内排布）── */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '16px 0',
                    borderBottom: '1px solid var(--td-component-stroke)',
                  }}
                >
                  {/* 首字母头像；aria-hidden —— 下方「用户名」一行已读出同样的名字 */}
                  <div style={AVATAR_STYLE} aria-hidden="true">
                    {initial}
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div
                      style={{
                        fontSize: 'var(--app-text-lg)',
                        fontWeight: 500,
                        color: 'var(--td-text-color-primary)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {account.name}
                    </div>
                    {account.email ? (
                      <p
                        className="desc"
                        style={{
                          margin: 0,
                          fontSize: 'var(--app-text-md)',
                          color: 'var(--td-text-color-secondary)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {account.email}
                      </p>
                    ) : null}
                  </div>
                </div>

                {/* ── 三行只读信息（源侧设置面板通用骨架）──────────────────────── */}

                <div className="setting-row">
                  <div className="setting-info">
                    <label>用户名</label>
                    <p className="desc">当前登录账号</p>
                  </div>
                  <div className="setting-control" style={CONTROL_STYLE}>
                    <span style={VALUE_STYLE}>{account.name}</span>
                  </div>
                </div>

                <div className="setting-row">
                  <div className="setting-info">
                    <label>邮箱</label>
                    <p className="desc">用于接收系统通知</p>
                  </div>
                  <div className="setting-control" style={CONTROL_STYLE}>
                    <span style={VALUE_STYLE}>{account.email}</span>
                  </div>
                </div>

                <div className="setting-row">
                  <div className="setting-info">
                    <label>账号体系</label>
                    <p className="desc">
                      本仓当前为单机单主体，用户体系（账号、成员、登录态）暂未接入，因此以上资料仅作展示，不可在此修改。
                    </p>
                  </div>
                  <div className="setting-control" style={CONTROL_STYLE}>
                    <span style={VALUE_STYLE}>单机模式（用户体系暂未接入）</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
