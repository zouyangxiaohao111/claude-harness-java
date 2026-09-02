package com.nexusai.application.agent.workflow;

import java.util.List;

/**
 * 脚本 {@code export const meta = {...}} 的纯字面量形状 · CC original: {@code WorkflowMeta}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:5-10)。
 *
 * <p>由 {@code extractMeta} 用正则定位后以无参 Function 求值，引用任何标识符 → ReferenceError
 * （即 meta 必须是纯字面量，不能引用变量）。{@code whenToUse} / {@code phases} 可选。
 *
 * <p><b>W-1d 自包含编译声明</b>：W-1a 类型底座子集（RunStarted 事件载荷所需），
 * W-1a 合入后以规范版本为准 reconcile。
 *
 * @param name        CC original: {@code name} (types.ts:6) — 工作流名（run_started 与面板标题用）
 * @param description CC original: {@code description} (types.ts:7) — 描述（store 的 RunProgress.description）
 * @param whenToUse   CC original: {@code whenToUse?} (types.ts:8) — 何时使用（提示模型）
 * @param phases      CC original: {@code phases?: Array<{title, detail?}>} (types.ts:9) —
 *                    声明期阶段列表；无 meta 时 {@code []}
 */
public record WorkflowMeta(String name, String description, String whenToUse, List<PhaseMeta> phases) {

    /**
     * {@code phases[].{title, detail}} 子元素 · CC original: types.ts:9。
     *
     * @param title  CC original: {@code title} — 阶段标题（入 store.declaredPhases，面板展示 pending ○）
     * @param detail CC original: {@code detail?} — 阶段细节
     */
    public record PhaseMeta(String title, String detail) {
    }
}
