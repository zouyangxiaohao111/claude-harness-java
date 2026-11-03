package com.nexusai.application.agent.computeruse;

/**
 * 元素目标解析 · <b>逐条对齐 dsh {@code service.ts:435-478} 的 {@code actNow} 前半段</b>。
 *
 * <h2>dsh 的原始校验顺序（本类照做）</h2>
 * <ol>
 *   <li>{@code requireObservation(observationId)} ⇒ 未知 / 过期 / 代际不符 ⇒
 *       {@code COMPUTER_STALE_OBSERVATION}；</li>
 *   <li>{@code index} 给了但不在该观察里 ⇒ {@code COMPUTER_ELEMENT_UNAVAILABLE}
 *       （"element N is not part of observation ..."）；</li>
 *   <li>{@code allowRebind} 但没给 {@code targetHandle} ⇒ {@code COMPUTER_TARGET_UNAVAILABLE}；</li>
 *   <li>{@code handle} 未知（不属于该观察）⇒ {@code COMPUTER_TARGET_UNAVAILABLE}；</li>
 *   <li>{@code index} 与 {@code handle} 指向<b>不同元素</b> ⇒ {@code COMPUTER_TARGET_UNAVAILABLE}
 *       （"elementIndex and targetHandle select different elements"）；</li>
 *   <li>都没有 ⇒ {@code COMPUTER_ELEMENT_UNAVAILABLE}（"&lt;kind&gt; requires elementIndex or targetHandle"）。</li>
 * </ol>
 *
 * <p>本仓额外保留 {@code name/role/automationId} 路径（Windows 的既有用法：
 * name/role 现查 UIA 树），但<b>macOS 优先 observationId + index</b>。
 */
final class ComputerUseElementResolver {

    private ComputerUseElementResolver() {
        throw new AssertionError("utility class");
    }

    /**
     * 解析结果。
     *
     * @param element   选中的观察元素（可空 = 走 name/role 现查路径）
     * @param locator   provider 定位键（macOS = 路径；Windows = AutomationId）
     * @param stateHash 该观察的状态指纹（动作侧做陈旧校验；空 = 不校验）
     * @param errorCode dsh 同名的错误码（null = 成功）
     * @param error     给模型看的错误说明（null = 成功）
     */
    record Resolution(ComputerUseUiSnapshotStore.ObservedElement element,
                      String locator,
                      String stateHash,
                      String errorCode,
                      String error,
                      ComputerUseTargetResolver.Resolution targetResolution) {

        static Resolution ok(ComputerUseUiSnapshotStore.ObservedElement element, String stateHash) {
            return new Resolution(element, element == null ? null : element.locator(), stateHash, null, null, null);
        }

        static Resolution ok(ComputerUseUiSnapshotStore.ObservedElement element,
                             String stateHash,
                             ComputerUseTargetResolver.Resolution targetResolution) {
            return new Resolution(element, element == null ? null : element.locator(), stateHash,
                    null, null, targetResolution);
        }

        static Resolution fail(String code, String message) {
            return new Resolution(null, null, null, code, message, null);
        }

        boolean failed() {
            return errorCode != null;
        }
    }

    /**
     * 按 observationId + index / targetHandle 解析（macOS 主路径）。
     *
     * @param store         观察存储
     * @param sessionId     会话 id
     * @param observationId 观察 id（macOS 必填）
     * @param index         元素序号（-1 = 未给）
     * @param handle        targetHandle（可空）
     * @param allowRebind   是否允许按 locator 重绑
     * @param kind          动作名（拼进错误文案，如 {@code click}）
     * @return 解析结果
     */
    static Resolution resolveObserved(ComputerUseUiSnapshotStore store,
                                      String sessionId,
                                      String observationId,
                                      int index,
                                      String handle,
                                      boolean allowRebind,
                                      String kind) {
        if (store == null) {
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION,
                    "Element targeting requires observation storage, which is not configured.");
        }
        ComputerUseUiSnapshotStore.Observation observation = store.require(sessionId, observationId);
        if (observation == null) {
            // dsh service.ts:718
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION,
                    "observation " + (observationId == null ? "(missing)" : observationId)
                            + " is unknown or expired. Call observe again and use the new observationId.");
        }
        boolean hasHandle = handle != null && !handle.isEmpty();
        ComputerUseUiSnapshotStore.ObservedElement byIndex =
                index >= 0 ? observation.elementAt(index) : null;
        if (index >= 0 && byIndex == null) {
            // dsh service.ts:449-451
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE,
                    "element " + index + " is not part of observation " + observationId
                            + " (it had " + observation.elements().size() + " elements). Call observe again.");
        }
        if (allowRebind && !hasHandle) {
            // dsh service.ts:459-461
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_TARGET_UNAVAILABLE,
                    "allowRebind requires a targetHandle from the referenced observation");
        }
        ComputerUseUiSnapshotStore.ObservedElement byHandle =
                hasHandle ? observation.elementByHandle(handle) : null;
        if (hasHandle && byHandle == null) {
            // dsh service.ts:463-465
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_TARGET_UNAVAILABLE,
                    "targetHandle is unknown or does not belong to the referenced observation");
        }
        if (byIndex != null && byHandle != null && !byIndex.locator().equals(byHandle.locator())) {
            // dsh service.ts:466-471
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_TARGET_UNAVAILABLE,
                    "elementIndex and targetHandle select different elements");
        }
        ComputerUseUiSnapshotStore.ObservedElement selected = byIndex != null ? byIndex : byHandle;
        if (selected == null) {
            // dsh service.ts:477-479
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE,
                    kind + " requires elementIndex or targetHandle");
        }
        if (selected.locator().isEmpty()) {
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE,
                    "element " + selected.index() + " is the tree root and has no locator");
        }
        // 不允许重绑 ⇒ 直接用观察里的 locator（执行前由后端做 expectedStateHash 校验）
        if (!allowRebind) {
            return Resolution.ok(selected, observation.stateHash());
        }
        // 允许重绑 ⇒ 交给调用方用<b>新抓的树</b>做 dsh 三级解析
        //   （本方法没有 UIA 通道，拿不到新树 ⇒ 只把「期望描述」带出去）
        return Resolution.ok(selected, observation.stateHash());
    }

    /**
     * 用<b>新抓的树</b>解析原目标（dsh {@code service.ts:509-513} 的重绑段）。
     *
     * <p>dsh 的语义：动作执行前重新 {@code observe} 一次，用新树按
     * {@code exact-locator → native-identifier → semantic-rebind} 三级解析原目标。
     *
     * @param original     原观察（提供期望的稳定字段）
     * @param fresh        新观察
     * @param expected     原观察里的目标元素
     * @param allowRebind  是否允许重绑
     * @param sameApp      应用身份是否一致
     * @param sameWindow   窗口身份是否一致
     * @return 三级解析结果
     */
    static ComputerUseTargetResolver.Resolution rebind(ComputerUseUiSnapshotStore.Observation original,
                                                       ComputerUseUiSnapshotStore.Observation fresh,
                                                       ComputerUseUiSnapshotStore.ObservedElement expected,
                                                       boolean allowRebind,
                                                       boolean sameApp,
                                                       boolean sameWindow) {
        return ComputerUseTargetResolver.resolve(original, fresh, expected, allowRebind, sameApp, sameWindow);
    }

    /**
     * 按 automationId / locator 直接解析（Windows 既有路径 / 显式给路径的兼容路径）。
     *
     * @param automationId 定位键
     * @return 解析结果
     */
    static Resolution byLocator(String automationId) {
        if (automationId == null || automationId.isEmpty()) {
            return Resolution.fail(ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE,
                    "automationId is empty");
        }
        return new Resolution(null, automationId, null, null, null, null);
    }
}
