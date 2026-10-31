package com.nexusai.application.agent.computeruse;

/**
 * 绑定窗口的<b>授权位 + 档位</b>门控 · computer-use Phase 2b。
 *
 * <h2>WHY 单独一个门控</h2>
 * <p>Phase 2a 的门控（{@link ComputerUseInputGate}）查的是<b>前台应用</b>。
 * Phase 2b 引入窗口绑定后，窗口定向输入打向的是<b>绑定窗口所属的应用</b> ——
 * 两者可能<b>不是同一个</b>（典型场景：绑定一个后台窗口做后台操作，用户前台在别的应用）。
 * <br>⇒ 绑定窗口的工具必须按<b>绑定窗口的进程</b>查授权，而不是按前台应用查。
 *
 * <p><b>⚠️ 这个洞不补的后果</b>：模型 {@code bind_window} 绑一个未授权的应用 →
 * {@code virtual_keyboard} 就能往那个应用里打字 ⇒ 白名单形同虚设。
 *
 * <h2>放行语义（逐字对齐 CCB）</h2>
 * <ul>
 *   <li><b>解析不到应用</b> ⇒ 放行（CCB {@code toolCalls.ts:502-506}：
 *       “No frontmost app (rare — login window?). Let it through.”）；</li>
 *   <li><b>不在 allowlist</b> ⇒ 拒绝；</li>
 *   <li><b>在 allowlist 但档位不够</b> ⇒ 拒绝（{@link ComputerUseTierGate}）。</li>
 * </ul>
 */
public final class ComputerUseWindowGate {

    private ComputerUseWindowGate() {
        throw new AssertionError("utility class");
    }

    /**
     * 门控判定。
     *
     * @param sessionId   会话 id（读授权位用）
     * @param binding     绑定窗口（null ⇒ 工具自己先报「未绑定」，本方法不判）
     * @param appResolver 进程 → 授权键解析器（null ⇒ 不门控，单测路径）
     * @param grantStore  会话授权位存储（null ⇒ 不门控，单测路径）
     * @param actionKind  动作类别（决定所需档位）
     * @return 拒绝原因（英文，直接给模型看）；<b>放行 ⇒ null</b>
     */
    public static String check(String sessionId,
                               ComputerUseWindowBinding binding,
                               ComputerUseProcessAppResolver appResolver,
                               ComputerUseGrantStore grantStore,
                               ComputerUseActionKind actionKind) {
        if (binding == null || appResolver == null || grantStore == null) {
            return null;
        }
        String appId = appResolver.appIdForPid(binding.window().pid());
        if (appId == null || appId.isBlank()) {
            // 解析不到应用 ⇒ 放行（与 CCB 的前台门控同款语义）
            return null;
        }
        ComputerUseGrants grants = grantStore.read(sessionId);
        ComputerUseGrant grant = grants.findByAppId(appId);
        if (grant == null) {
            return "\"" + appId + "\" is not granted for this session. Call request_access first.";
        }
        if (!ComputerUseTierGate.tierSatisfies(grant.tier(), actionKind)) {
            return ComputerUseTierGate.tierInsufficientMessage(
                    grant.displayName(), actionKind, grant.tier());
        }
        return null;
    }
}
