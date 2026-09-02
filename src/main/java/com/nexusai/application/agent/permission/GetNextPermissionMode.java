package com.nexusai.application.agent.permission;

import com.nexusai.infra.util.AutoModeState;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * GetNextPermissionMode · 对齐 CC utils/permissions/getNextPermissionMode.ts.
 *
 * <p>L1 语义: Shift+Tab cycling permission mode 决定 next mode。
 * <ul>
 *   <li>{@link #getNextPermissionMode(ModeCycle, boolean, boolean, boolean)} → next mode</li>
 *   <li>{@link #transitionPermissionMode} → 模式转换单一入口（CC permissionSetup.ts:597-646）</li>
 *   <li>{@link #cyclePermissionMode} → 决定 next mode + 应用转换（CC getNextPermissionMode.ts:88-101）</li>
 * </ul>
 *
 * <p><b>[S10] transitionPermissionMode 等价</b>: CC 全部模式切换路径（CLI Shift+Tab、
 * SDK control messages）都收敛到 {@code transitionPermissionMode}（permissionSetup.ts:581-596
 * 注释 "Centralises side-effects so that every activation path behaves identically"）。
 * Java 侧同样收敛到 {@link #transitionPermissionMode}：进入 auto 剥离危险规则并
 * {@link AutoModeState#setAutoModeActive}，离开 auto 触发 S04 交付的
 * {@link DangerousPatternDetector#restoreDangerousPermissions}（stash 幂等恢复）。
 *
 * <p>CC 真源（permissionSetup.ts:597-646，逐条对照见方法 javadoc）：
 * <ul>
 *   <li>{@code fromMode === toMode} → 原样返回（:602-603）</li>
 *   <li>{@code handlePlanModeTransition} / {@code handleAutoModeTransition}
 *       （bootstrap/state.ts:1349-1399）—— plan_mode/auto_mode 消息 attachment 标志；
 *       Java 无 LLM attachment 机制，N/A（见 {@link #transitionPermissionMode} javadoc）</li>
 *   <li>{@code setHasExitedPlanMode}（state.ts:1337-1339）—— plan 退出一次性通知标志；
 *       Java 无 attachment 消费面，N/A</li>
 *   <li>feature('TRANSCRIPT_CLASSIFIER') 块（:612-638）：进入 plan → prepareContextForPlanMode；
 *       离开 classifier 侧（auto / plan+active）→ setAutoModeActive(false) + restore</li>
 *   <li>{@code prePlanMode} 清理（:640-643）：离开 plan 且有 prePlanMode → 置 undefined</li>
 * </ul>
 *
 * <p><b>[R30-P1-6] 不存在 {@code cyclePermissionMode}</b>: 原 javadoc 引用是 stale
 * （历史上规划的简化版 API，从未实际实现）。S10 按 CC getNextPermissionMode.ts:88-101
 * 补齐 {@link #cyclePermissionMode} —— 模式转换单一入口（决定 next + 应用转换）。
 *
 * <p><b>[R31-D2.7] 嵌套 enum 已重命名</b>: 本类嵌套的 {@link ModeCycle}（7 个常量）原命名
 * {@code PermissionMode}，与顶层 {@code com.nexusai.application.agent.permission.PermissionMode}
 * （7 个常量: DEFAULT/ACCEPT_EDITS/BYPASS_PERMISSIONS/DONT_ASK/PLAN/AUTO/BUBBLE）同名异用
 * 造成 import 消歧歧义 — R31 重命名为 {@code ModeCycle} 彻底消歧。
 *
 * <p><b>[R31-D2.5] bubble mode 已加入</b>: CC 注释
 * "bubble is ant-only internal; UI 不暴露"，cycling 走回 {@code defaultMode}。
 *
 * <p>CC 注释:
 * - default → acceptEdits (external) / bypassPermissions (ant if avail) / auto (ant) / default (ant)
 * - acceptEdits → plan
 * - plan → bypassPermissions / auto / default
 * - bypassPermissions → auto / default
 * - dontAsk → default (UI 不暴露)
 * - auto → default (CC 注释: covers auto + future)
 * - bubble → default (CC 注释: ant-only internal, UI 不暴露)
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 静态方法 + 嵌套 {@code ModeCycle} enum (7 个常量) + TransitionResult record</li>
 *   <li><b>A2 Golden Trace</b>: default+ext→acceptEdits;default+ant+bypass→bypass;default+ant+auto→auto;plan→auto (if avail);bypass→auto (if avail);bypass default→default;auto→default;bubble→default</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: null mode→default;unknown mode→default</li>
 *   <li><b>A5 业务场景</b>: Shift+Tab cycling UI 用户切换 permission mode</li>
 * </ul>
 *
 * <p>L3 升级: TS PermissionMode union → Java enum;
 * TS function feature 注入 → Java BooleanSupplier 注入;
 * TS record 返回 → Java TransitionResult record.
 */
public final class GetNextPermissionMode {

    /**
     * 嵌套 enum · 用于 cycling 决策表（7 个常量，对齐 CC 全部 mode）。
     *
     * <p><b>[R31-D2.7]</b> R31 重命名自 {@code PermissionMode} → {@code ModeCycle}，
     * 与顶层 {@code com.nexusai.application.agent.permission.PermissionMode}（7 个常量）彻底消歧。
     *
     * <p><b>[R31-D2.5]</b> 新增 {@code bubble}（CC 注释: ant-only internal, UI 不暴露，
     * cycling 走回 {@code defaultMode}）。现共 7 个常量：
     * {@code defaultMode, acceptEdits, plan, bypassPermissions, dontAsk, auto, bubble}。
     */
    public enum ModeCycle { defaultMode, acceptEdits, plan, bypassPermissions, dontAsk, auto, bubble }

    public record TransitionResult(ModeCycle nextMode) {}

    /**
     * 模式转换的依赖注入配置 · 对齐 CC transitionPermissionMode 的模块级依赖
     * （permissionSetup.ts:612-638）：
     * <ul>
     *   <li>{@code feature('TRANSCRIPT_CLASSIFIER')}（:612）→ {@link #transcriptClassifierFeature}</li>
     *   <li>{@code isAutoModeGateEnabled()}（:628）→ {@link #autoModeGateEnabled}</li>
     *   <li>{@code shouldPlanUseAutoMode()}（:1468）→ {@link #planUsesAutoMode}</li>
     *   <li>{@code stripDangerousPermissionsForAutoMode} / {@code restoreDangerousPermissions}
     *       （:632/:636）→ {@link #detector}（S04 交付 API）</li>
     * </ul>
     *
     * <p>null 语义：feature/门 supplier 为 null = 对应 CC 判定为 false（特性关闭/门未开，
     * 与 AutoModeGate 配置默认 false 一致）；detector 必填（进入/离开 auto 必须能剥离/恢复）。
     *
     * @param transcriptClassifierFeature feature('TRANSCRIPT_CLASSIFIER') 等价（可为 null = 关闭）
     * @param autoModeGateEnabled         isAutoModeGateEnabled() 等价（可为 null = 门未开）
     * @param planUsesAutoMode            shouldPlanUseAutoMode() 等价（可为 null = false）
     * @param detector                    危险规则剥离/恢复器（S04 交付，必填）
     */
    public record TransitionConfig(
            BooleanSupplier transcriptClassifierFeature,
            BooleanSupplier autoModeGateEnabled,
            BooleanSupplier planUsesAutoMode,
            DangerousPatternDetector detector
    ) {
        public TransitionConfig {
            Objects.requireNonNull(detector, "TransitionConfig.detector is required (S04 strip/restore API)");
        }
    }

    /**
     * cycling 结果 · 对齐 CC {@code cyclePermissionMode} 返回
     * {@code { nextMode, context }}（getNextPermissionMode.ts:91-100）。
     *
     * @param nextMode 下一个模式（CC nextMode）
     * @param context  已应用转换的上下文（CC context —— 调用方负责把 mode 设为 nextMode，
     *                 CC :590-591 注释 "Caller is responsible for setting the mode"）
     */
    public record CycleResult(ModeCycle nextMode, ToolPermissionContext context) {}

    private GetNextPermissionMode() {}

    /**
     * Determine the next permission mode when cycling through modes.
     *
     * @param currentMode       current mode
     * @param isAnt             USER_TYPE==='ant' (skip acceptEdits, plan; cycle to auto/bypass)
     * @param isBypassAvailable bypassPermissions feature enabled
     * @param canCycleToAuto    can enter auto mode (per classifier + settings)
     */
    public static ModeCycle getNextPermissionMode(
        ModeCycle currentMode,
        boolean isAnt,
        boolean isBypassAvailable,
        boolean canCycleToAuto) {
        if (currentMode == null) return ModeCycle.defaultMode;
        return switch (currentMode) {
            case defaultMode -> {
                // Ants skip acceptEdits and plan
                if (isAnt) {
                    if (isBypassAvailable) yield ModeCycle.bypassPermissions;
                    if (canCycleToAuto) yield ModeCycle.auto;
                    yield ModeCycle.defaultMode;
                }
                yield ModeCycle.acceptEdits;
            }
            case acceptEdits -> ModeCycle.plan;
            case plan -> {
                if (isBypassAvailable) yield ModeCycle.bypassPermissions;
                if (canCycleToAuto) yield ModeCycle.auto;
                yield ModeCycle.defaultMode;
            }
            case bypassPermissions -> {
                if (canCycleToAuto) yield ModeCycle.auto;
                yield ModeCycle.defaultMode;
            }
            case dontAsk -> ModeCycle.defaultMode;
            // [R31-D2.5] bubble 是 ant-only internal mode，UI 不暴露；cycling 走回 defaultMode
            case bubble -> ModeCycle.defaultMode;
            // auto (when TRANSCRIPT_CLASSIFIER enabled) and any future modes
            default -> ModeCycle.defaultMode;
        };
    }

    /**
     * 模式转换单一入口 · 对齐 CC {@code transitionPermissionMode}
     * （{@code permissionSetup.ts:597-646}，CC 原名 {@code transitionPermissionMode}）。
     *
     * <p>所有模式切换路径（cycling / SDK control messages / 设置变更）必须收敛到本方法，
     * 保证副作用（auto 激活/剥离/恢复）只发生一次。
     *
     * <h3>CC 语义逐条对照（:597-646）</h3>
     * <ol>
     *   <li>{@code fromMode === toMode} → 原样返回（:602-603）</li>
     *   <li>{@code handlePlanModeTransition} / {@code handleAutoModeTransition}
     *       （state.ts:1349-1399）与 {@code setHasExitedPlanMode}（:608-610）—— 均为
     *       plan_mode/auto_mode/plan_mode_exit/auto_mode_exit 消息 attachment 一次性标志；
     *       渲染消费面已存在（AgentLoopContext.renderHookAttachmentForLlm：auto_mode/
     *       auto_mode_exit case 批次 G GLB-03 新增 :3380/:3407；plan_mode/plan_mode_exit case
     *       批次 B CTX-05 既有 :3252/:3367）→ Java 仅缺 producer（permissions 域 future 接线：
     *       sessions.auto_mode_enabled 会话列门控 §2.3；CC 产源 state.ts:1349-1399
     *       handleAutoModeTransition + attachments.ts:1336-1373 getAutoModeAttachments）
     *       → 防御纯渲染不虚构，待 permissions 域接线后由产源注入（不新增 CC 没有的 Java 能力）</li>
     *   <li>feature('TRANSCRIPT_CLASSIFIER') 开启时（:612）：
     *     <ol>
     *       <li>进入 plan（toMode=plan, fromMode≠plan）→
     *           {@link #prepareContextForPlanMode}（:613-615，CC :1462-1493）并提前返回</li>
     *       <li>{@code fromUsesClassifier} = fromMode==='auto' || (fromMode==='plan' && isAutoModeActive())
     *           （:621-624）；{@code toUsesClassifier} = toMode==='auto'（:625）</li>
     *       <li>进入 auto（toUses && !fromUses，:627-632）：门未开 → throw
     *           "Cannot transition to auto mode: gate is not enabled"（:628-630）；
     *           {@link AutoModeState#setAutoModeActive}(true) + stripDangerousPermissionsForAutoMode</li>
     *       <li>离开 classifier 侧（fromUses && !toUses，:633-637）：
     *           setAutoModeActive(false) + restoreDangerousPermissions（S04 API 消费）</li>
     *     </ol>
     *   </li>
     *   <li>{@code prePlanMode} 清理（:640-643）：离开 plan 且原 ctx 有 prePlanMode → 置 null
     *       （Java 用 null 表达 CC 的 {@code undefined}）</li>
     * </ol>
     *
     * <p><b>不设置 mode</b>：对齐 CC 注释 "Caller is responsible for setting the mode on
     * the returned context"（:590-591）—— 本方法只做副作用与上下文变换，mode 写入由调用方完成。
     *
     * @param fromMode 当前模式（CC fromMode）
     * @param toMode   目标模式（CC toMode）
     * @param ctx      当前工具权限上下文（CC context）
     * @param config   依赖注入（feature 门 / auto 门 / plan-auto 判定 / S04 detector）
     * @return 变换后的上下文（未改变时保持引用相等，CC "Only spread if there's something to clear" :640）
     * @throws IllegalStateException 进入 auto 时 auto 门未开（CC :628-630 等价）
     */
    public static ToolPermissionContext transitionPermissionMode(
            PermissionMode fromMode,
            PermissionMode toMode,
            ToolPermissionContext ctx,
            TransitionConfig config) {
        Objects.requireNonNull(ctx, "ctx is required");
        PermissionMode from = fromMode != null ? fromMode : PermissionMode.DEFAULT;
        PermissionMode to = toMode != null ? toMode : PermissionMode.DEFAULT;
        // CC :602-603 —— plan→plan (SDK set_permission_mode) 会误入 leave 分支，先短路
        if (from == to) {
            return ctx;
        }

        // CC :612-638 —— feature('TRANSCRIPT_CLASSIFIER') 块
        boolean featureOn = config.transcriptClassifierFeature() != null
                && config.transcriptClassifierFeature().getAsBoolean();
        if (featureOn) {
            // CC :613-615 —— 进入 plan 提前返回 prepareContextForPlanMode
            if (to == PermissionMode.PLAN && from != PermissionMode.PLAN) {
                return prepareContextForPlanMode(from, ctx, config);
            }

            // CC :621-625 —— isAutoModeActive() 是权威信号（prePlanMode/strippedDangerousRules
            //   都是不可靠代理：auto 可能在 plan 中途被关闭而字段残留）
            boolean fromUsesClassifier = from == PermissionMode.AUTO
                    || (from == PermissionMode.PLAN && AutoModeState.isAutoModeActive());
            boolean toUsesClassifier = to == PermissionMode.AUTO;

            if (toUsesClassifier && !fromUsesClassifier) {
                // CC :628-630 —— 门未开时抛错（阻止静默失败：Shift+Tab 处理器若静默失败
                //   会把用户困在当前模式，permissionSetup.ts:13-16 注释）
                if (config.autoModeGateEnabled() == null
                        || !config.autoModeGateEnabled().getAsBoolean()) {
                    throw new IllegalStateException(
                            "Cannot transition to auto mode: gate is not enabled");
                }
                // CC :631-632 —— setAutoModeActive(true) + 剥离危险规则（stash 于上下文）
                AutoModeState.setAutoModeActive(true);
                return config.detector().stripDangerousPermissionsForAutoMode(ctx);
            }
            if (fromUsesClassifier && !toUsesClassifier) {
                // CC :633-637 —— 离开 classifier 侧：清 auto 激活 + restore（S04 幂等恢复）
                AutoModeState.setAutoModeActive(false);
                return config.detector().restoreDangerousPermissions(ctx);
            }
        }

        // CC :640-643 —— 离开 plan 且原 ctx 有 prePlanMode → 清理（Java null = CC undefined）
        if (from == PermissionMode.PLAN && to != PermissionMode.PLAN
                && ctx.prePlanMode() != null) {
            return withPrePlanMode(ctx, null);
        }
        return ctx;
    }

    /**
     * 决定下一个模式并应用转换 · 对齐 CC {@code cyclePermissionMode}
     * （{@code getNextPermissionMode.ts:88-101}）。
     *
     * <p>CC 顺序：{@code nextMode = getNextPermissionMode(...)}（:92）→
     * {@code context = transitionPermissionMode(currentMode, nextMode, context)}（:93-99）。
     *
     * @param ctx              当前权限上下文（mode 为当前模式）
     * @param isAnt            USER_TYPE==='ant'
     * @param isBypassAvailable bypassPermissions 可用
     * @param canCycleToAuto    可进入 auto（per classifier + settings）
     * @param config            转换依赖注入
     * @return nextMode + 应用转换后的上下文（mode 未设置，由调用方写入 —— CC :590-591）
     */
    public static CycleResult cyclePermissionMode(
            ToolPermissionContext ctx,
            boolean isAnt,
            boolean isBypassAvailable,
            boolean canCycleToAuto,
            TransitionConfig config) {
        ModeCycle current = toCycle(ctx.mode());
        ModeCycle next = getNextPermissionMode(current, isAnt, isBypassAvailable, canCycleToAuto);
        ToolPermissionContext nextCtx = transitionPermissionMode(
                toMode(current), toMode(next), ctx, config);
        return new CycleResult(next, nextCtx);
    }

    /**
     * 进入 plan 模式的上下文准备 · 对齐 CC {@code prepareContextForPlanMode}
     * （{@code permissionSetup.ts:1462-1493}）。
     *
     * <p>stash 当前 mode 为 {@code prePlanMode} 供 ExitPlanMode 恢复；用户已 opt-in auto 时
     * plan 期间保持 auto 语义（分类器继续运行）。
     *
     * <p><b>[WF-13 接线] 生产统一入口</b>：EnterPlanModeTool 直调本方法（对齐 CC
     * EnterPlanModeTool.ts:91 {@code prepareContextForPlanMode(prev.toolPermissionContext)}）——
     * plan 进入统一走本入口，不再各工具自实现简化版（OD-WF1-CFG-01）。
     *
     * <h3>CC 语义逐条对照（:1462-1493）</h3>
     * <ol>
     *   <li>{@code currentMode === 'plan'} → 原样返回（:1466，本方法自带守卫，直调安全）</li>
     *   <li>feature 开启时（:1467）：
     *     <ol>
     *       <li>{@code currentMode === 'auto'}（:1469）：planAutoMode → 仅记 prePlanMode=auto
     *           （auto 保持激活，:1470-1472）；否则 setAutoModeActive(false) + restore +
     *           prePlanMode=auto（:1473-1478）</li>
     *       <li>{@code planAutoMode && currentMode !== 'bypassPermissions'}（:1480-1486）：
     *           setAutoModeActive(true) + strip + prePlanMode=currentMode</li>
     *     </ol>
     *   </li>
     *   <li>其余 → {@code {...ctx, prePlanMode: currentMode}}（:1492，纯 plan 进入）</li>
     * </ol>
     *
     * <p>setNeedsAutoModeExitAttachment（:1474）为 auto_mode_exit attachment 标志 —— Java N/A。
     *
     * @param fromMode 进入 plan 前的模式（= ctx.mode()，CC currentMode）
     * @param ctx      当前上下文
     * @param config   依赖注入
     * @return prePlanMode 已写入的上下文（可能同时完成 strip/restore 副作用）
     */
    public static ToolPermissionContext prepareContextForPlanMode(
            PermissionMode fromMode,
            ToolPermissionContext ctx,
            TransitionConfig config) {
        // CC :1466 —— currentMode === 'plan' → return context（原样返回，含直调场景）
        if (fromMode == PermissionMode.PLAN) {
            return ctx;
        }
        // CC :1467 —— feature('TRANSCRIPT_CLASSIFIER') 块：auto/planAuto 分支仅在 classifier 开启时执行
        //   （Java 生产 feature 恒 false → 退化 plain 分支，对齐外部构建语义）。
        boolean featureOn = config.transcriptClassifierFeature() != null
                && config.transcriptClassifierFeature().getAsBoolean();
        if (featureOn) {
            boolean planAutoMode = config.planUsesAutoMode() != null
                    && config.planUsesAutoMode().getAsBoolean();
            if (fromMode == PermissionMode.AUTO) {
                if (planAutoMode) {
                    // CC :1470-1472 —— auto 在 plan 期间保持激活，仅记 prePlanMode
                    return withPrePlanMode(ctx, PermissionMode.AUTO);
                }
                // CC :1473-1478 —— 退出 auto 语义：清激活 + restore + prePlanMode=auto
                AutoModeState.setAutoModeActive(false);
                return withPrePlanMode(
                        config.detector().restoreDangerousPermissions(ctx), PermissionMode.AUTO);
            }
            if (planAutoMode && fromMode != PermissionMode.BYPASS_PERMISSIONS) {
                // CC :1480-1486 —— plan 期间激活 auto：setAutoModeActive(true) + strip + prePlanMode
                AutoModeState.setAutoModeActive(true);
                return withPrePlanMode(
                        config.detector().stripDangerousPermissionsForAutoMode(ctx), fromMode);
            }
        }
        // CC :1488-1492 —— 纯 plan 进入（feature 关或非 auto/bypass）
        return withPrePlanMode(ctx, fromMode);
    }

    /**
     * 重建上下文并替换 {@code prePlanMode}（其余字段不变）。
     *
     * <p>对齐 CC spread 语义 {@code {...context, prePlanMode: ...}}（permissionSetup.ts:642/:1471）。
     *
     * @param ctx         原上下文
     * @param prePlanMode 新的 prePlanMode（null = CC undefined）
     * @return 新上下文
     */
    private static ToolPermissionContext withPrePlanMode(
            ToolPermissionContext ctx, PermissionMode prePlanMode) {
        return new ToolPermissionContext(
                ctx.mode(),
                ctx.alwaysAllowRules(),
                ctx.alwaysDenyRules(),
                ctx.alwaysAskRules(),
                ctx.additionalWorkingDirectories(),
                ctx.isBypassPermissionsModeAvailable(),
                ctx.isAutoModeAvailable(),
                ctx.strippedDangerousRules(),
                ctx.shouldAvoidPermissionPrompts(),
                ctx.awaitAutomatedChecksBeforeDialog(),
                prePlanMode);
    }

    /** {@link PermissionMode} → {@link ModeCycle}（7↔7 全映射）。null → defaultMode。 */
    static ModeCycle toCycle(PermissionMode mode) {
        if (mode == null) return ModeCycle.defaultMode;
        return switch (mode) {
            case DEFAULT -> ModeCycle.defaultMode;
            case ACCEPT_EDITS -> ModeCycle.acceptEdits;
            case PLAN -> ModeCycle.plan;
            case BYPASS_PERMISSIONS -> ModeCycle.bypassPermissions;
            case DONT_ASK -> ModeCycle.dontAsk;
            case AUTO -> ModeCycle.auto;
            case BUBBLE -> ModeCycle.bubble;
        };
    }

    /** {@link ModeCycle} → {@link PermissionMode}（7↔7 全映射）。null → DEFAULT。 */
    static PermissionMode toMode(ModeCycle cycle) {
        if (cycle == null) return PermissionMode.DEFAULT;
        return switch (cycle) {
            case defaultMode -> PermissionMode.DEFAULT;
            case acceptEdits -> PermissionMode.ACCEPT_EDITS;
            case plan -> PermissionMode.PLAN;
            case bypassPermissions -> PermissionMode.BYPASS_PERMISSIONS;
            case dontAsk -> PermissionMode.DONT_ASK;
            case auto -> PermissionMode.AUTO;
            case bubble -> PermissionMode.BUBBLE;
        };
    }
}
