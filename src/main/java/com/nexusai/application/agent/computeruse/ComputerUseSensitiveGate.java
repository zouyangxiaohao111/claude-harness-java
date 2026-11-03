package com.nexusai.application.agent.computeruse;

import java.util.Map;

/**
 * 敏感动作的<b>一次性确认校验</b> · {@code click_element} / {@code type_into_element} 共用。
 *
 * <h2>语义（对齐 dsh {@code confirmations.ts:89-113} 的 {@code consume}）</h2>
 * <ol>
 *   <li>{@code sensitive} 非 true ⇒ <b>直接放行</b>；但若仍传了 {@code confirmationToken}
 *       ⇒ <b>报错</b>（dsh {@code confirmations.ts:95-99}：
 *       "confirmationToken is valid only when sensitive is true"）—— 避免令牌被误用；</li>
 *   <li>{@code sensitive} 为 true 且<b>没有</b>令牌 ⇒ 拒绝
 *       （"sensitive action requires a token from computer_confirm"）；</li>
 *   <li>令牌<b>一次性</b>：{@link ComputerUseConfirmationStore#consume} 先删后校验
 *       ⇒ 失败的尝试也消耗令牌（防止拿错配令牌反复试）。</li>
 * </ol>
 *
 * <h2>WHY 动作工具要自己校验（而不是信任模型）</h2>
 * <p>{@code sensitive=true} 是<b>模型自己声明</b>的。若动作工具不强制校验令牌，
 * 模型只要不声明 {@code sensitive} 就能绕过确认 ⇒ 这条防线形同虚设。
 * <br>⇒ 校验放在<b>动作执行路径</b>上，且「声明了 sensitive 就必须有令牌」。
 */
final class ComputerUseSensitiveGate {

    private ComputerUseSensitiveGate() {
        throw new AssertionError("utility class");
    }

    /**
     * 校验结果。
     *
     * @param rejection 拒绝原因（null = 放行）
     */
    record Gate(boolean allowed, String rejection) {

        static Gate allow() {
            return new Gate(true, null);
        }

        static Gate deny(String reason) {
            return new Gate(false, reason);
        }
    }

    /**
     * 校验敏感动作的确认令牌。
     *
     * @param store           令牌存储（null ⇒ 无法校验 ⇒ 敏感动作一律拒绝）
     * @param snapshotStore   观察存储（取该观察的应用标识）
     * @param sessionId       会话 id
     * @param sensitive       模型是否声明该动作敏感
     * @param confirmationToken 令牌（可空）
     * @param observationId   观察 id
     * @param index           元素序号
     * @param targetHandle    元素句柄
     * @param action          动作种类
     * @param payload         动作载荷
     * @return 校验结果
     */
    static Gate check(ComputerUseConfirmationStore store,
                      ComputerUseUiSnapshotStore snapshotStore,
                      String sessionId,
                      boolean sensitive,
                      String confirmationToken,
                      String observationId,
                      int index,
                      String targetHandle,
                      String action,
                      String payload) {
        if (!sensitive) {
            if (confirmationToken != null && !confirmationToken.isEmpty()) {
                // dsh confirmations.ts:95-99
                return Gate.deny(ComputerUseConfirmationStore.ERR_CONFIRMATION_REQUIRED
                        + ": confirmationToken is valid only when sensitive is true");
            }
            return Gate.allow();
        }
        if (confirmationToken == null || confirmationToken.isEmpty()) {
            return Gate.deny(ComputerUseConfirmationStore.ERR_CONFIRMATION_REQUIRED
                    + ": sensitive action requires a token from computer_confirm");
        }
        if (store == null) {
            return Gate.deny(ComputerUseConfirmationStore.ERR_CONFIRMATION_REQUIRED
                    + ": confirmation tokens are not available in this configuration");
        }
        String appKey = appKeyOf(snapshotStore, sessionId, observationId);
        Map<String, String> canonical = ComputerUseConfirmationStore.canonicalParams(
                observationId, index, targetHandle, action, payload);
        String actionHash = ComputerUseConfirmationStore.actionHash(canonical);
        String rejection = store.consume(sessionId, confirmationToken, appKey, observationId, actionHash);
        return rejection == null
                ? Gate.allow()
                : Gate.deny(ComputerUseConfirmationStore.ERR_CONFIRMATION_REQUIRED + ": " + rejection);
    }

    /**
     * 取观察对应的应用标识（{@code bundleId:pid}）。
     *
     * <p>优先用<b>指定观察</b>的应用身份；无该观察 ⇒ 回落到最近一次观察
     * （与 {@link ComputerUseConfirmTool#resolveAppKey} 同款约定 ⇒ 两侧 appKey 一致）。
     */
    private static String appKeyOf(ComputerUseUiSnapshotStore snapshotStore,
                                   String sessionId,
                                   String observationId) {
        if (snapshotStore == null) {
            return "";
        }
        ComputerUseUiSnapshotStore.Observation observation =
                observationId == null || observationId.isEmpty()
                        ? snapshotStore.latest(sessionId)
                        : snapshotStore.require(sessionId, observationId);
        if (observation == null) {
            observation = snapshotStore.latest(sessionId);
        }
        if (observation == null) {
            return "";
        }
        return observation.bundleId() + ":" + observation.pid();
    }
}
