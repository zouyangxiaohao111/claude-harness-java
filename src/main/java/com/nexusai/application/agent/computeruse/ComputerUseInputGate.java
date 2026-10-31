package com.nexusai.application.agent.computeruse;

/**
 * 输入动作的<b>前台应用白名单 + 档位</b>门控 · computer-use 各输入工具共用。
 *
 * <p><b>为什么抽出来</b>：Phase 1a 只有 {@code left_click} 一个输入工具，门控逻辑内联在它里面；
 * 本批新增 {@code type} / {@code key} 两个键盘输入工具 ⇒ 同一段门控要被三处调用 ⇒ 抽成
 * 单一入口，避免三份拷贝各自漂移。
 *
 * <p><b>语义（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:443-560}
 * 的 {@code runInputActionGates}）</b>：
 * <ol>
 *   <li>取<b>前台应用</b>（{@code foregroundAppResolver.currentAppId()}）；
 *       <b>取不到 ⇒ 直接放行</b>（CCB {@code toolCalls.ts:502-506}：
 *       “No frontmost app (rare — login window?). Let it through.”）；</li>
 *   <li>前台应用<b>不在白名单</b> ⇒ 拒绝（CCB {@code toolCalls.ts:578-583}）；</li>
 *   <li>在白名单但<b>档位不够</b> ⇒ 拒绝（CCB {@code tierSatisfies} + {@code toolCalls.ts:520-560}）。</li>
 * </ol>
 *
 * <p><b>与 CCB-Windows 的差异（有意为之，见 {@link ComputerUseRequestAccessResult} 类注释）</b>：
 * CCB 在 Windows 上因 {@code screenshotFiltering === 'none'} <b>整段跳过</b>前台门控
 * （{@code toolCalls.ts:470-477}）；本仓<b>照旧执行</b>（更严），已在 Phase 1a 记录为有意增强。
 */
public final class ComputerUseInputGate {

    private ComputerUseInputGate() {
    }

    /**
     * 门控判定。
     *
     * @param sessionId           会话 id（读授权位用）
     * @param grantStore          会话授权位存储（null ⇒ 跳过门控，PoC/单测路径）
     * @param foregroundAppResolver 前台应用解析器（null ⇒ 跳过门控）
     * @param actionKind          动作类别（决定所需档位）
     * @return 拒绝原因（英文，直接给模型看）；<b>放行 ⇒ null</b>
     */
    public static String check(String sessionId,
                               ComputerUseGrantStore grantStore,
                               ComputerUseForegroundAppResolver foregroundAppResolver,
                               ComputerUseActionKind actionKind) {
        if (grantStore == null || foregroundAppResolver == null) {
            // 未接授权体系（PoC / 单测路径）⇒ 不门控
            return null;
        }
        String foregroundAppId = foregroundAppResolver.currentAppId();
        if (foregroundAppId == null || foregroundAppId.isBlank()) {
            // CCB toolCalls.ts:502-506 —— 取不到前台应用 ⇒ 放行
            return null;
        }
        ComputerUseGrants grants = grantStore.read(sessionId);
        ComputerUseGrant grant = grants.findByAppId(foregroundAppId);
        if (grant == null) {
            // CCB toolCalls.ts:578-583
            return "\"" + foregroundAppId + "\" is not in the allowed applications and is currently in front. "
                    + "Take a new screenshot — it may have appeared since your last one.";
        }
        if (!ComputerUseTierGate.tierSatisfies(grant.tier(), actionKind)) {
            return ComputerUseTierGate.tierInsufficientMessage(grant.displayName(), actionKind, grant.tier());
        }
        return null;
    }
}
