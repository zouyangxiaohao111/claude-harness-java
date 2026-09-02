package com.nexusai.model.session.dto;

/**
 * away-summary 请求体 · ODF-B1R（2026-08-07）：Web 前端 blur 5min 后 POST
 * {@code /api/agent/away-summary} 时随请求携带 sessionId。
 *
 * <p>WHY: CC 触发层在前端 REPL（{@code Open-ClaudeCode/src/hooks/useAwaySummary.ts:32-125}
 * blur 5min + feature('AWAY_SUMMARY') + flag 'tengu_sedge_lantern' 默认 false），会话上下文由前端持有
 * —— 前端 blur 后调用 REST 端点时，后端 MDC 未必处于该会话链路内，故请求体提供显式 sessionId 通道
 * （请求优先 + MDC 兜底，见 {@code AwaySummaryController#generate}）。字段名 {@code sessionId}
 * 对齐 {@code 待前端对接.md} §8.2 契约。
 *
 * @param sessionId 目标会话 UUID（body JSON {@code {"sessionId": "..."}}；空/缺省时控制器兜底 query
 *                  参数与 {@code RequestContext} MDC）
 */
public record AwaySummaryRequest(
        String sessionId
) {
}
