package com.nexusai.application.agent.tool;

/**
 * UI attribution payload (Java 自定义 UI 载体) · 原 {@code AttributionState} 改名.
 *
 * <p><b>改名原因 (IMP-H4, TR-H3 DEL-3)</b>: Java 旧类名 {@code AttributionState} 与 CC
 * {@code utils/commitAttribution.ts:173} 的 {@code AttributionState} 类型同名不同义 ——
 * CC 类型是 commit 归因追踪器 ({@code fileStates/sessionBaselines/surface/startingHeadSha/
 * promptCount/...}), 与本 Java UI 载体 (归属文本 + 置信度) 无字段交集, 借名会造成
 * 命名污染 (探查 EV-H3-026)。按 owner 拍板改为 {@code UiAttribution}, 不再蹭 CC 类型名。
 *
 * <p>本载体仅作前端 UI 渲染的 payload transport (React 集成由 nexusai 前端负责,
 * Java 端仅作为类型契约 + payload transport)。
 */
public record UiAttribution(
        // WHY: 归属文本 (例如 "由 claude-3-opus 生成"), UI 显示在消息底部
        String attributionText,
        // WHY: 置信度 [0.0, 1.0], UI 据此决定透明度 / 是否显示 "AI 生成" 标记
        double confidence
) { }
