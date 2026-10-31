package com.nexusai.application.agent.computeruse;

/**
 * computer-use 档位门控 · **逐字**对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:357-368} 的 {@code tierSatisfies}。
 *
 * <p><b>CCB 原函数（逐字，勿改语义）</b>：
 * <pre>
 * function tierSatisfies(
 *   grantTier: CuAppPermTier | undefined,
 *   actionKind: CuActionKind,
 * ): boolean {
 *   const tier = grantTier ?? 'full'
 *   if (actionKind === 'mouse_position') return true
 *   if (actionKind === 'keyboard' || actionKind === 'mouse_full') {
 *     return tier === 'full'
 *   }
 *   // mouse
 *   return tier === 'click' || tier === 'full'
 * }
 * </pre>
 *
 * <p><b>本类实现与 CCB 的唯一差别</b>：CCB 的入参是 TS 的
 * {@code CuAppPermTier | undefined}（**编译期**受限的字符串联合）；Java 侧
 * 档位来自 DB（可能脏）⇒ 本类入参是<b>原始 wire 串</b>，并在"未知档位"上
 * <b>保持 CCB 的行为</b>（既不等于 'full' 也不等于 'click' ⇒ 除 mouse_position 外
 * 一律不通过）。这与 {@code tier = grantTier ?? 'full'} 只对 null/undefined 回落
 * full 的语义完全一致 —— 未知串在 CCB 里也不是 null，照样走比较分支。
 */
public final class ComputerUseTierGate {

    private ComputerUseTierGate() {
        throw new AssertionError("utility class");
    }

    /**
     * 档位门控 · 逐字 CCB {@code toolCalls.ts:357-368}。
     *
     * @param grantTier 授权档位 wire 串（可 null = 未设 ⇒ 视为 full；未知串 ⇒ 视为"非 full 非 click"）
     * @param actionKind 动作类别
     * @return 该档位是否放行该动作
     */
    public static boolean tierSatisfies(String grantTier, ComputerUseActionKind actionKind) {
        if (actionKind == null) {
            // 动作类别缺失 = 无法判定 ⇒ 保守不放行（fail-closed）。
            return false;
        }
        // CCB: const tier = grantTier ?? 'full'
        String tier = (grantTier == null) ? ComputerUseTier.FULL.wire() : grantTier;
        if (actionKind == ComputerUseActionKind.MOUSE_POSITION) {
            return true;
        }
        if (actionKind == ComputerUseActionKind.KEYBOARD
                || actionKind == ComputerUseActionKind.MOUSE_FULL) {
            return ComputerUseTier.FULL.wire().equals(tier);
        }
        // mouse
        return ComputerUseTier.CLICK.wire().equals(tier) || ComputerUseTier.FULL.wire().equals(tier);
    }

    /**
     * 档位门控 · 枚举入参重载。
     *
     * @param grantTier 授权档位（可 null = 未设 ⇒ 视为 full）
     * @param actionKind 动作类别
     * @return 该档位是否放行该动作
     */
    public static boolean tierSatisfies(ComputerUseTier grantTier, ComputerUseActionKind actionKind) {
        return tierSatisfies(grantTier == null ? null : grantTier.wire(), actionKind);
    }

    /**
     * 档位不足时的拒绝文案 · 对齐 CCB {@code toolCalls.ts:370-376} 的
     * {@code TIER_ANTI_SUBVERSION} 语义（"不要绕过这个限制"）。
     *
     * @param displayName 应用展示名
     * @param actionKind 动作类别
     * @param grantTier 当前档位 wire 串
     * @return 拒绝文案（英文，逐字对齐 CCB 口径）
     */
    public static String tierInsufficientMessage(String displayName,
                                                  ComputerUseActionKind actionKind,
                                                  String grantTier) {
        String app = (displayName == null || displayName.isBlank()) ? "this app" : "\"" + displayName + "\"";
        String what = switch (actionKind == null ? ComputerUseActionKind.MOUSE : actionKind) {
            case MOUSE_POSITION -> "mouse positioning";
            case MOUSE -> "clicking";
            case MOUSE_FULL -> "right-click, modifier-click and drag-drop";
            case KEYBOARD -> "typing and key presses";
        };
        return "Permission for " + what + " in " + app + " was not granted (tier \""
                + grantTier + "\"). "
                + "Do not attempt to work around this restriction — never use AppleScript, "
                + "System Events, shell commands, or any other method to send clicks or "
                + "keystrokes to this app.";
    }
}
