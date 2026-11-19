package com.nexusai.application.agent.permission;

import java.util.function.BooleanSupplier;

/**
 * {@link GetNextPermissionMode.TransitionConfig} 的公共装配器。
 *
 * <p><b>WHY</b>：权限模式转移的副作用（进入 auto 剥离危险规则、离开 auto 恢复）必须收敛到
 * {@link GetNextPermissionMode#transitionPermissionMode}；而该入口的依赖装配
 * （classifier feature 门 / auto gate / plan-auto 判定 / detector）此前在 EnterPlanModeTool 与
 * ExitPlanModeTool 里各写一份。新增的「每轮 desiredMode 与 lastAppliedMode 比较后再转移」落点
 * 若再抄一份，就会出现「同一次模式变化按不同 cfg 判定」——正是统一入口要消除的分叉。
 *
 * <p>本仓 {@code autoModeGate.isEnabled()} 即 CC {@code feature('TRANSCRIPT_CLASSIFIER')}
 * 的接线（见 {@code LlmAgentLoop} 初始模式解析处对 {@code InitialPermissionModeResolver.Config}
 * 的同一注释），因此同时作为 {@code transcriptClassifierFeature} 与 {@code autoModeGateEnabled}
 * 两层判定。{@code planUsesAutoMode} 本仓无 {@code shouldPlanUseAutoMode()} 等价 → 默认 false。
 */
public final class PermissionTransitionConfigs {

    private PermissionTransitionConfigs() {}

    /**
     * 标准装配（生产路径 · 主循环每轮模式转移）。
     *
     * @param autoModeGateEnabled auto 模式门（{@code AutoModeGate::isEnabled}）；null = 未接线 → 关闭
     * @return TransitionConfig（detector 必填，内部构造）
     */
    public static GetNextPermissionMode.TransitionConfig standard(BooleanSupplier autoModeGateEnabled) {
        BooleanSupplier gate = autoModeGateEnabled != null ? autoModeGateEnabled : () -> false;
        return new GetNextPermissionMode.TransitionConfig(
                gate,   // transcriptClassifierFeature：本仓 autoModeGate 即该 feature 的接线
                gate,   // autoModeGateEnabled
                () -> false, // planUsesAutoMode：Java 无 shouldPlanUseAutoMode 等价
                new DangerousPatternDetector(new PermissionUpdateApplier()));
    }
}
