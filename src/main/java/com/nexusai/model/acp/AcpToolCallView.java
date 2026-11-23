package com.nexusai.model.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ACP 工具调用的只读视图 · 由 {@code session/update} 的 {@code tool_call} /
 * {@code tool_call_update} 归一而来。
 *
 * <p>⛔ ACP 的工具在<b>外部进程</b>里执行，我们只做展示与审批，不代执行。
 *
 * @param toolCallId 外部 agent 的工具调用 id（贯穿 tool_call → tool_call_update）
 * @param title      人类可读标题（工具卡显示名）
 * @param kind       类别：read/edit/delete/move/search/execute/think/fetch/switch_mode/other
 * @param status     状态：pending/in_progress/completed/failed
 * @param rawInput   原始入参 JSON（可空；execute 类通常含 command）
 * @param outputText 工具已产出的文本（可空；来自 content 块的 text）
 */
public record AcpToolCallView(
        String toolCallId,
        String title,
        String kind,
        String status,
        JsonNode rawInput,
        String outputText
) {
}
