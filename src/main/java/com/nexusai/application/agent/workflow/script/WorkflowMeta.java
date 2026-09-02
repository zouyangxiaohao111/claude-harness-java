package com.nexusai.application.agent.workflow.script;

import java.util.List;

/**
 * 工作流元数据（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/types.ts:5-10 WorkflowMeta）。
 *
 * <pre>{@code
 * export type WorkflowMeta = {
 *   name: string
 *   description: string
 *   whenToUse?: string
 *   phases?: Array<{ title: string; detail?: string }>
 * }
 * }</pre>
 *
 * <p>运行时只强校验 name/description 必须为 string（validateMeta，engine/script.ts:95-104）；
 * whenToUse/phases <b>不深度校验</b>，形状信任脚本作者（CC 类型断言放行，script-doc §1.4）。</p>
 *
 * <p>包名注记：P0 计划 W-1a 另在 com.nexusai.application.agent.workflow 建同名 WorkflowMeta（DEC-P0-01 双候选）。
 * 本包为 script-doc §7 候选包，合并时以 DEC-P0-01 拍板结果统一。</p>
 *
 * @param name        工作流名（必填 string，面板展示）
 * @param description 工作流描述（必填 string）
 * @param whenToUse   何时使用提示（可选，CC 面板选择辅助）
 * @param phases      声明性阶段列表（可选，面板左栏展示用；与运行时 phase() hook 产生的 phase_started/phase_done 事件流是两套独立机制）
 */
public record WorkflowMeta(String name, String description, String whenToUse, List<PhaseDef> phases) {

    /** 声明性阶段定义（CC types.ts:10 phases 元素：{title, detail?}）。 */
    public record PhaseDef(String title, String detail) {}
}
