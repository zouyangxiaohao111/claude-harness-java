package com.nexusai.application.agent.computeruse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 元素目标解析 · <b>逐条对齐 dsh {@code target-resolver.ts}</b>
 * （{@code resolveComputerTarget}）。
 *
 * <h2>dsh 的三级解析（fail-closed，绝不猜）</h2>
 * <ol>
 *   <li><b>exact-locator</b>（confidence 1）：locator 完全相同 <b>且</b>稳定字段一致
 *       ⇒ 命中，且 {@code targetChanged=false}；</li>
 *   <li><b>native-identifier</b>（confidence 1）：locator 变了，但
 *       {@code AXIdentifier} 相同 ⇒ 要求<b>恰好 1 个</b>命中
 *       （多个 ⇒ {@code COMPUTER_TARGET_AMBIGUOUS}），且稳定字段一致；</li>
 *   <li><b>semantic-rebind</b>（confidence 0.9）：语义唯一命中 ——
 *       角色 + 可访问名 + 动作集 + 祖先指纹全同 ⇒ 要求<b>恰好 1 个</b>命中
 *       （多个 ⇒ {@code COMPUTER_TARGET_AMBIGUOUS}；0 个 ⇒ {@code COMPUTER_TARGET_LOW_CONFIDENCE}）。</li>
 * </ol>
 *
 * <h2>WHY 必须 fail-closed</h2>
 * <p>macOS 的 AX 元素<b>没有稳定 id</b>（{@code AXIdentifier} 实测基本为空）⇒ 语义重绑是
 * 常见路径。而「重绑到错误的元素」= <b>点击了用户没打算点的按钮</b>
 * ⇒ 多命中时必须<b>拒绝并报歧义</b>，让模型重新观察，而不是挑一个。
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li>dsh 的 {@code normalizedFrame} 用「元素 frame − 窗口 frame」；
 *       本仓 macOS <b>拿不到坐标</b>（解 {@code AXValue} 会让 JXA 崩溃）⇒ 该项恒缺省，
 *       坐标不参与同一性判定（dsh 在 frame 缺失时同样退化为「两者都缺 ⇒ 视为相同」）。</li>
 *   <li>dsh 的 {@code ancestorFingerprint} 取最近 4 层祖先；本仓同款（深度 4）。</li>
 * </ul>
 */
public final class ComputerUseTargetResolver {

    private ComputerUseTargetResolver() {
        throw new AssertionError("utility class");
    }

    /** 置信度常量 · dsh {@code target-resolver.ts:9-14}。 */
    public static final double CONFIDENCE_EXACT_LOCATOR = 1.0d;
    public static final double CONFIDENCE_NATIVE_IDENTIFIER = 1.0d;
    public static final double CONFIDENCE_SEMANTIC = 0.9d;
    /** 语义重绑的置信度阈值 · dsh {@code semanticThreshold}。 */
    public static final double SEMANTIC_THRESHOLD = 0.9d;

    /** 歧义错误码。 */
    public static final String ERR_TARGET_AMBIGUOUS = "COMPUTER_TARGET_AMBIGUOUS";
    /** 低置信度错误码。 */
    public static final String ERR_TARGET_LOW_CONFIDENCE = "COMPUTER_TARGET_LOW_CONFIDENCE";

    /**
     * 解析结果。
     *
     * @param mode           解析模式（{@code exact-locator} / {@code native-identifier} / {@code semantic-rebind}）
     * @param confidence     置信度
     * @param candidateCount 候选数
     * @param targetChanged  目标是否已改变（rebind ⇒ true）
     * @param element        命中的元素
     * @param errorCode      错误码（null = 成功）
     * @param error          错误说明（null = 成功）
     */
    public record Resolution(String mode,
                             double confidence,
                             int candidateCount,
                             boolean targetChanged,
                             ComputerUseUiSnapshotStore.ObservedElement element,
                             String errorCode,
                             String error) {

        public boolean failed() {
            return errorCode != null;
        }
    }

    /**
     * 解析目标（对齐 dsh {@code resolveComputerTarget}）。
     *
     * @param original    原观察（提供期望的稳定字段）
     * @param fresh       新观察（提供候选）
     * @param expected    期望的元素（原观察里的那个）
     * @param allowRebind 是否允许重绑（false ⇒ 只接受 exact-locator）
     * @param sameApp     应用身份是否一致（bundleId + pid）
     * @param sameWindow  窗口身份是否一致
     * @return 解析结果
     */
    public static Resolution resolve(ComputerUseUiSnapshotStore.Observation original,
                                     ComputerUseUiSnapshotStore.Observation fresh,
                                     ComputerUseUiSnapshotStore.ObservedElement expected,
                                     boolean allowRebind,
                                     boolean sameApp,
                                     boolean sameWindow) {
        if (!sameApp) {
            return fail("COMPUTER_STALE_OBSERVATION",
                    "the selected application restarted or resolved to a different process");
        }
        if (!sameWindow) {
            return fail("COMPUTER_STALE_OBSERVATION",
                    "the selected window changed after the referenced observation");
        }

        // 级别 1：exact-locator
        ComputerUseUiSnapshotStore.ObservedElement exact = byLocator(fresh, expected.locator());
        if (exact != null && sameExactIdentity(expected, exact, fresh)) {
            return new Resolution("exact-locator", CONFIDENCE_EXACT_LOCATOR, 1, false,
                    exact, null, null);
        }
        if (!allowRebind) {
            return fail("COMPUTER_STALE_OBSERVATION",
                    "the target locator no longer identifies the selected element and rebinding was not allowed");
        }
        if (fresh.truncated()) {
            return fail(ERR_TARGET_LOW_CONFIDENCE,
                    "target uniqueness cannot be established from a truncated fresh observation; candidateCount=0, confidence=0, required="
                            + SEMANTIC_THRESHOLD);
        }

        // 级别 2：native-identifier（AXIdentifier）
        String nativeId = expected.nativeIdentifier();
        if (nativeId != null && !nativeId.isEmpty()) {
            List<ComputerUseUiSnapshotStore.ObservedElement> nativeMatches = new ArrayList<>();
            for (ComputerUseUiSnapshotStore.ObservedElement candidate : fresh.elements()) {
                if (nativeId.equals(candidate.nativeIdentifier())) {
                    nativeMatches.add(candidate);
                }
            }
            if (nativeMatches.size() > 1) {
                return fail(ERR_TARGET_AMBIGUOUS,
                        "native identifier resolution found " + nativeMatches.size()
                                + " candidates in the selected process and window");
            }
            if (nativeMatches.size() == 1) {
                ComputerUseUiSnapshotStore.ObservedElement match = nativeMatches.get(0);
                if (!sameStableFields(expected, match, fresh)) {
                    return fail(ERR_TARGET_LOW_CONFIDENCE,
                            "the native identifier resolved to an element with different stable semantics; "
                                    + "candidateCount=1, confidence=0, required=" + SEMANTIC_THRESHOLD);
                }
                return new Resolution("native-identifier", CONFIDENCE_NATIVE_IDENTIFIER, 1, true,
                        match, null, null);
            }
        }

        // 级别 3：semantic-rebind
        if (expected.name() == null || expected.name().isEmpty()) {
            return fail(ERR_TARGET_LOW_CONFIDENCE,
                    "the target has no native identifier or accessible name; candidateCount=0, confidence=0, required="
                            + SEMANTIC_THRESHOLD);
        }
        List<ComputerUseUiSnapshotStore.ObservedElement> semanticMatches = new ArrayList<>();
        for (ComputerUseUiSnapshotStore.ObservedElement candidate : fresh.elements()) {
            if (sameSemanticIdentity(expected, candidate, fresh)) {
                semanticMatches.add(candidate);
            }
        }
        if (semanticMatches.size() > 1) {
            return fail(ERR_TARGET_AMBIGUOUS,
                    "semantic resolution found " + semanticMatches.size()
                            + " candidates in the selected process and window");
        }
        if (semanticMatches.isEmpty()) {
            return fail(ERR_TARGET_LOW_CONFIDENCE,
                    "no candidate retained the target role, accessible name, actions, and ancestor fingerprint; "
                            + "candidateCount=0, confidence=0, required=" + SEMANTIC_THRESHOLD);
        }
        if (CONFIDENCE_SEMANTIC < SEMANTIC_THRESHOLD) {
            return fail(ERR_TARGET_LOW_CONFIDENCE,
                    "the deterministic semantic score is below the configured threshold; candidateCount="
                            + semanticMatches.size() + ", confidence=0, required=" + SEMANTIC_THRESHOLD);
        }
        return new Resolution("semantic-rebind", CONFIDENCE_SEMANTIC, semanticMatches.size(), true,
                semanticMatches.get(0), null, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // 同一性判定（对齐 dsh target-resolver.ts:35-99）
    // ══════════════════════════════════════════════════════════════════

    private static ComputerUseUiSnapshotStore.ObservedElement byLocator(
            ComputerUseUiSnapshotStore.Observation observation, String locator) {
        if (observation == null || locator == null) {
            return null;
        }
        for (ComputerUseUiSnapshotStore.ObservedElement element : observation.elements()) {
            if (locator.equals(element.locator())) {
                return element;
            }
        }
        return null;
    }

    /**
     * 精确同一性（dsh {@code sameExactIdentity}）。
     *
     * <p>有 nativeIdentifier 时：两者都有且相等 + 稳定字段一致。
     * <p>无 nativeIdentifier 时：稳定字段一致（坐标不参与 —— 本仓 macOS 拿不到坐标）。
     */
    private static boolean sameExactIdentity(ComputerUseUiSnapshotStore.ObservedElement left,
                                            ComputerUseUiSnapshotStore.ObservedElement right,
                                            ComputerUseUiSnapshotStore.Observation observation) {
        String leftId = left.nativeIdentifier();
        String rightId = right.nativeIdentifier();
        if (leftId != null && !leftId.isEmpty() || rightId != null && !rightId.isEmpty()) {
            return java.util.Objects.equals(leftId, rightId)
                    && sameStableFields(left, right, observation);
        }
        return sameStableFields(left, right, observation);
    }

    /**
     * 语义同一性（dsh {@code sameSemanticIdentity}）：<b>必须有可访问名</b> + 稳定字段一致。
     */
    private static boolean sameSemanticIdentity(ComputerUseUiSnapshotStore.ObservedElement left,
                                                ComputerUseUiSnapshotStore.ObservedElement right,
                                                ComputerUseUiSnapshotStore.Observation observation) {
        if (left.name() == null || left.name().isEmpty()) {
            return false;
        }
        return sameStableFields(left, right, observation);
    }

    /**
     * 稳定字段（dsh {@code sameStableFields}）：角色 + 子角色 + 可访问名 + 动作集 + 祖先指纹。
     */
    private static boolean sameStableFields(ComputerUseUiSnapshotStore.ObservedElement left,
                                             ComputerUseUiSnapshotStore.ObservedElement right,
                                             ComputerUseUiSnapshotStore.Observation observation) {
        return left.role().equals(right.role())
                && java.util.Objects.equals(left.subrole(), right.subrole())
                && java.util.Objects.equals(left.name(), right.name())
                && sameActions(left.actions(), right.actions())
                && sameAncestorFingerprint(left, right, observation);
    }

    /** 动作集相等（dsh {@code sameActions}：顺序敏感，元素已排序）。 */
    private static boolean sameActions(List<String> left, List<String> right) {
        return left.size() == right.size() && left.equals(right);
    }

    /**
     * 祖先指纹相等（dsh {@code sameAncestorFingerprint}）：最近 4 层祖先的角色/子角色/名字全同。
     */
    private static boolean sameAncestorFingerprint(ComputerUseUiSnapshotStore.ObservedElement left,
                                                   ComputerUseUiSnapshotStore.ObservedElement right,
                                                   ComputerUseUiSnapshotStore.Observation observation) {
        List<String> leftChain = ancestorChain(left, observation);
        List<String> rightChain = ancestorChain(right, observation);
        if (leftChain.size() != rightChain.size()) {
            return false;
        }
        for (int i = 0; i < leftChain.size(); i++) {
            if (!leftChain.get(i).equals(rightChain.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取元素的祖先链（最近 4 层，dsh {@code ancestors.slice(-4)}）。
     *
     * <p>祖先链 = 从窗口根到该元素的 locator 前缀序列。
     */
    private static List<String> ancestorChain(ComputerUseUiSnapshotStore.ObservedElement element,
                                              ComputerUseUiSnapshotStore.Observation observation) {
        List<String> chain = new ArrayList<>();
        if (observation == null || element.locator() == null || element.locator().isEmpty()) {
            return chain;
        }
        String[] parts = element.locator().split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1 && i < 4; i++) {
            if (prefix.length() > 0) {
                prefix.append('.');
            }
            prefix.append(parts[i]);
            ComputerUseUiSnapshotStore.ObservedElement ancestor =
                    byLocator(observation, prefix.toString());
            if (ancestor == null) {
                continue;
            }
            chain.add(ancestor.role() + " " + nullToEmpty(ancestor.subrole())
                    + " " + nullToEmpty(ancestor.name()));
        }
        return chain;
    }

    /** 文本归一（dsh {@code normalizedText}：NFKC + trim + 折叠空白）。 */
    public static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Resolution fail(String code, String message) {
        return new Resolution(null, 0d, 0, false, null, code, message);
    }

    /** 供上层判定「这次解析是否改变了目标」。 */
    public static boolean changed(Resolution resolution) {
        return resolution != null && resolution.targetChanged();
    }

    /** 供上层格式化（对齐 dsh 的 mode 命名）。 */
    public static String modeLabel(String mode) {
        return mode == null ? "unknown" : mode.toLowerCase(Locale.ROOT);
    }
}
