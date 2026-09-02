package com.nexusai.application.agent.permission;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具权限上下文 · 对齐 CC {@code Tool.ts:123-138} (ToolPermissionContext)
 *
 * <h2>职责</h2>
 * <p>把"8 source 规则合并结果 + 当前 mode + 工作目录"打包成一个 record，
 * 让 10 层检查函数（{@code CheckLayer}）只需一个 {@code permCtx} 参数。
 *
 * <h2>字段对应</h2>
 * <ul>
 *   <li>{@link #mode} — 当前 PermissionMode（驱动 2a 层 bypass 检查）</li>
 *   <li>{@link #alwaysAllowRules} — 8 source 的 allow 规则（合并）</li>
 *   <li>{@link #alwaysDenyRules} — 8 source 的 deny 规则（驱动 1a 层）</li>
 *   <li>{@link #alwaysAskRules} — 8 source 的 ask 规则（驱动 1b / 1f 层）</li>
 *   <li>{@link #additionalWorkingDirectories} — path sandbox 扩展目录</li>
 *   <li>{@link #isBypassPermissionsModeAvailable} — 是否允许 BYPASS_PERMISSIONS mode</li>
 *   <li>{@link #isAutoModeAvailable} — 是否允许 auto mode（CC Tool.ts:130）</li>
 *   <li>{@link #strippedDangerousRules} — 被剥离的危险规则（auto mode 入口，退出时恢复）</li>
 *   <li>{@link #shouldAvoidPermissionPrompts} — 是否避免弹窗</li>
 *   <li>{@link #awaitAutomatedChecksBeforeDialog} — 是否等待自动化检查完成</li>
 *   <li>{@link #prePlanMode} — 进入 plan mode 前的原始 mode</li>
 * </ul>
 *
 * <h2>Phase 1 简化</h2>
 * <p>本 record 是 PR 1 占位字段 + PR 3 正式定义。Phase 2 加 {@code hookRegistry}、
 * {@code yoloClassifier} 等字段。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code mode} 必填</li>
 *   <li>4 个 Map / Set 字段 null → 规范化为空集合（防御性默认）</li>
 *   <li>4 个字段做防御性 copy（{@code Map.copyOf} / {@code Set.copyOf}）</li>
 * </ul>
 */
public record ToolPermissionContext(
        PermissionMode mode,
        Map<PermissionRuleSource, Set<PermissionRule>> alwaysAllowRules,
        Map<PermissionRuleSource, Set<PermissionRule>> alwaysDenyRules,
        Map<PermissionRuleSource, Set<PermissionRule>> alwaysAskRules,
        Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
        boolean isBypassPermissionsModeAvailable,
        /** 是否允许 auto mode（对齐 CC {@code Tool.ts:130 isAutoModeAvailable}）。 */
        boolean isAutoModeAvailable,
        // ── CC types/permissions.ts:427-441 补齐的 4 个可选字段 ──
        /** 被剥离的危险规则（auto mode 入口，用于退出时恢复）。 */
        Map<PermissionRuleSource, Set<PermissionRule>> strippedDangerousRules,
        /** 是否避免弹窗（auto mode 下分类器已决策时跳过弹窗）。 */
        boolean shouldAvoidPermissionPrompts,
        /** 是否等待自动化检查完成后再弹窗（投机分类器场景）。 */
        boolean awaitAutomatedChecksBeforeDialog,
        /** 进入 plan mode 前的原始 mode（用于退出 plan mode 时恢复）。 */
        PermissionMode prePlanMode
) {

    /**
     * compact constructor：不变量保护 + 防御性 copy。
     *
     * <p>WHY:
     * <ul>
     *   <li>mode=null 让 10 层第 2a 层 NPE</li>
     *   <li>Map/Set 为 null 让 {@code getOrDefault} 复杂化，统一空集合</li>
     *   <li>外部 mutate Map/Set 会污染会话级规则，故 copy</li>
     * </ul>
     */
    public ToolPermissionContext {
        if (mode == null) {
            throw new IllegalArgumentException("ToolPermissionContext.mode is required");
        }
        // null → 空集合 + 防御性 copy（保留 EnumMap source order）
        alwaysAllowRules = enumMapCopy(alwaysAllowRules);
        alwaysDenyRules  = enumMapCopy(alwaysDenyRules);
        alwaysAskRules   = enumMapCopy(alwaysAskRules);
        additionalWorkingDirectories = additionalWorkingDirectories == null
            ? Map.of()
            : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(additionalWorkingDirectories));
        strippedDangerousRules = enumMapCopy(strippedDangerousRules);
    }

    /**
     * 便利构造：空规则集 + 默认 bypass 不可用（最严格模式）。
     *
     * @param mode 当前 PermissionMode
     * @return ToolPermissionContext
     */
    public static ToolPermissionContext strict(PermissionMode mode) {
        return new ToolPermissionContext(
            mode, Map.of(), Map.of(), Map.of(), Map.of(), false, false,
            Map.of(), false, false, null
        );
    }

    /**
     * 便利构造：完整规则集 + bypass 模式可用（开发态）。
     *
     * @param mode                         当前 mode
     * @param alwaysAllowRules             8 source 合并后的 allow 规则
     * @param alwaysDenyRules              8 source 合并后的 deny 规则
     * @param alwaysAskRules               8 source 合并后的 ask 规则
     * @param additionalWorkingDirectories path sandbox 扩展目录
     * @return ToolPermissionContext
     */
    public static ToolPermissionContext of(
            PermissionMode mode,
            Map<PermissionRuleSource, Set<PermissionRule>> alwaysAllowRules,
            Map<PermissionRuleSource, Set<PermissionRule>> alwaysDenyRules,
            Map<PermissionRuleSource, Set<PermissionRule>> alwaysAskRules,
            Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories
    ) {
        return new ToolPermissionContext(
            mode,
            alwaysAllowRules,
            alwaysDenyRules,
            alwaysAskRules,
            additionalWorkingDirectories,
            true, true,
            Map.of(), false, false, null
        );
    }

    /**
     * [P2 #9 修补]: 防御性 copy + 保 EnumMap source order.
     *
     * <p>{@code Map.copyOf} 用 {@code HashMap}-like 内部表示,丢弃 {@code EnumMap} 顺序。
     * 这里改用 {@link Collections#unmodifiableMap} 包装 {@link EnumMap} 副本,保留 enum
     * 声明顺序作为 rule 命中顺序的 fallback(PermissionContextBuilder 也提供 enum-order)。
     *
     * <p>实现细节:
     * <ul>
     *   <li>{@code source == null} → {@link Map#of()} (空)
     *   <li>{@code source.isEmpty()} → {@link Map#of()} (避免 EnumMap 内存开销)
     *   <li>{@code source instanceof EnumMap} → 复制构造(同 enum 类型已对齐)
     *   <li>其他情况 → 用源 entries 构造新 EnumMap (按 source enum 常量顺序)
     * </ul>
     *
     * @param source 输入规则 map (可能为 null)
     * @return        不可变 EnumMap (按 PermissionRuleSource enum 顺序); null 输入返回空
     */
    private static Map<PermissionRuleSource, Set<PermissionRule>> enumMapCopy(
            Map<PermissionRuleSource, Set<PermissionRule>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        EnumMap<PermissionRuleSource, Set<PermissionRule>> copy;
        if (source instanceof EnumMap) {
            copy = new EnumMap<>(source);
        } else {
            copy = new EnumMap<>(PermissionRuleSource.class);
            copy.putAll(source);
        }
        return Collections.unmodifiableMap(copy);
    }
}
