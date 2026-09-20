import { describe, expect, it } from 'vitest'
import {
  PERMISSION_MODE_DESCRIPTIONS,
  PERMISSION_MODE_LABELS,
  type PermissionMode,
} from '@/api/types'

/**
 * [批 A4c 追加项] 权限模式文案必须与**真实语义**一致 —— 重中之重是 `dontAsk`。
 *
 * <h2>WHY（真缺陷 · 实测）</h2>
 * <p>修前 `PERMISSION_MODE_DESCRIPTIONS.dontAsk = '不询问，自动批准一切'` —— 这是
 * **`bypassPermissions` 的语义**，与本模式**完全相反**。CC 的定义逐字（`claude-code-best/src/
 * entrypoints/sdk/coreSchemas.ts:353`）：
 * <pre>'dontAsk' - Don't prompt for permissions, deny if not pre-approved.</pre>
 * <p>⇒ 真语义 = <b>不弹窗，未预先批准的一律「拒绝」</b>（严格模式）。后端实现与 CC 一致
 * （`ToolPermissionGate.applyDontAskTransform`：Ask → Deny），测试
 * `R26PermissionPipelineEquivalenceTest.dontAsk_noAllowRule_denyWithModeReason` 断言的正是
 * 「无 allow 规则 ⇒ deny」。<b>只有前端文案是反的</b> —— 用户读到「自动批准一切」会以为选它能
 * 免打扰，实际是把所有未授权操作拒掉。
 *
 * <h2>RED tooth</h2>
 * <p>把 `dontAsk` 的说明改回「不询问，自动批准一切」（或任何含「自动批准 / 放行 / 一律允许」的措辞）
 * ⇒ 第 1、2 条断言变红；把短标签改回裸「不询问」⇒ 第 3 条变红。
 *
 * <h2>为什么用「词表」而不是整句相等</h2>
 * <p>整句相等会锁死措辞（改一个字就红），而真正要防的是<b>语义反向</b>；所以断言「不得出现
 * 自动放行族词汇」+「必须出现拒绝族词汇」—— 措辞可以改，语义不能反。
 */

/** 自动放行族：出现即说明文案在描述「不询问就放行」。 */
const AUTO_APPROVE_WORDS = ['自动批准', '自动放行', '自动允许', '一律批准', '全部批准', '批准一切', '自动通过']
/** 拒绝族：dontAsk 的文案必须至少命中一个。 */
const DENY_WORDS = ['拒绝', '被拒', '不放行']

const ALL_MODES: PermissionMode[] = ['default', 'plan', 'acceptEdits', 'bypassPermissions', 'dontAsk', 'auto']

describe('[批 A4c] dontAsk 文案不得与语义相反', () => {
  it('dontAsk 说明：不得含「自动放行」族词汇（那是 bypassPermissions 的语义）', () => {
    const desc = PERMISSION_MODE_DESCRIPTIONS.dontAsk
    for (const word of AUTO_APPROVE_WORDS) {
      expect(desc, `dontAsk 说明误写成自动放行语义（命中「${word}」）: ${desc}`).not.toContain(word)
    }
  })

  it('dontAsk 说明：必须说清「未预先批准的会被拒绝」', () => {
    const desc = PERMISSION_MODE_DESCRIPTIONS.dontAsk
    expect(DENY_WORDS.some((w) => desc.includes(w)), `dontAsk 说明必须点明会拒绝: ${desc}`).toBe(true)
    expect(desc, `dontAsk 说明必须点明「不弹窗」这一半语义: ${desc}`).toMatch(/不弹窗|不询问|不问/)
  })

  it('dontAsk 短标签：Composer 芯片只显示标签 ⇒ 不得单独被读成「不问就放行」', () => {
    const label = PERMISSION_MODE_LABELS.dontAsk
    for (const word of AUTO_APPROVE_WORDS) {
      expect(label, `dontAsk 短标签误写成自动放行语义: ${label}`).not.toContain(word)
    }
    expect(label, `dontAsk 短标签必须自带「拒绝」以免与 bypass 混淆: ${label}`)
      .toMatch(/拒绝|拒/)
  })

  it('对照：bypassPermissions 才拥有「绕过/不检查」语义 —— 两条不得互换', () => {
    expect(PERMISSION_MODE_DESCRIPTIONS.bypassPermissions).toContain('绕过')
    expect(PERMISSION_MODE_DESCRIPTIONS.dontAsk).not.toContain('绕过')
    // 两条文案必须不同（防复制粘贴把语义抹平）
    expect(PERMISSION_MODE_DESCRIPTIONS.dontAsk).not.toBe(PERMISSION_MODE_DESCRIPTIONS.bypassPermissions)
    expect(PERMISSION_MODE_LABELS.dontAsk).not.toBe(PERMISSION_MODE_LABELS.bypassPermissions)
  })
})

describe('[批 A4c] 6 个权限模式文案完整性', () => {
  it('标签 / 说明对 6 个模式全覆盖，且均非空、两两不同', () => {
    for (const mode of ALL_MODES) {
      expect(PERMISSION_MODE_LABELS[mode], `缺标签: ${mode}`).toBeTruthy()
      expect(PERMISSION_MODE_DESCRIPTIONS[mode], `缺说明: ${mode}`).toBeTruthy()
    }
    expect(Object.keys(PERMISSION_MODE_LABELS).sort()).toEqual([...ALL_MODES].sort())
    expect(Object.keys(PERMISSION_MODE_DESCRIPTIONS).sort()).toEqual([...ALL_MODES].sort())
    expect(new Set(Object.values(PERMISSION_MODE_LABELS)).size).toBe(ALL_MODES.length)
    expect(new Set(Object.values(PERMISSION_MODE_DESCRIPTIONS)).size).toBe(ALL_MODES.length)
  })

  it('其余 5 条与 CC 语义一致（default 询问 / plan 只读 / acceptEdits 接受编辑 / bypass 绕过 / auto 自动）', () => {
    expect(PERMISSION_MODE_DESCRIPTIONS.default).toContain('询问')
    expect(PERMISSION_MODE_DESCRIPTIONS.plan).toMatch(/只读|不执行/)
    expect(PERMISSION_MODE_DESCRIPTIONS.acceptEdits).toMatch(/编辑/)
    expect(PERMISSION_MODE_DESCRIPTIONS.bypassPermissions).toContain('绕过')
    expect(PERMISSION_MODE_DESCRIPTIONS.auto).toMatch(/自动/)
    // ⛔ 除 bypassPermissions 外，任何一条都不得声称「绕过权限」
    for (const mode of ALL_MODES.filter((m) => m !== 'bypassPermissions')) {
      expect(PERMISSION_MODE_DESCRIPTIONS[mode], `${mode} 误称绕过`).not.toContain('绕过')
    }
  })
})
