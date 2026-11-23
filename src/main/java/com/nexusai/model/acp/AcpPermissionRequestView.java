package com.nexusai.model.acp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ACP {@code session/request_permission} 的归一视图（server → client 请求）。
 *
 * <p>wire 形状：{@code {sessionId, toolCall:{toolCallId,title,kind,rawInput,...}, options[]}}。
 * 本类只保留我们审批需要的字段。
 *
 * @param toolCall  待审批的工具调用
 * @param options   外部 agent 给出的选项（可能为空 ⇒ 只能拒绝）
 * @param rawParams 原始 params（透传给合成 Tool 的 input 时避免二次解析丢失字段）
 */
public record AcpPermissionRequestView(
        AcpToolCallView toolCall,
        List<AcpPermissionOption> options,
        JsonNode rawParams
) {
}
