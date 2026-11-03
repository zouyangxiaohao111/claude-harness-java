package com.nexusai.model.command.dto;

/**
 * [goal V253] {@code /goal} 执行请求体。
 *
 * <p><b>WHY 需要独立 DTO</b>：通用端点 {@code POST /api/command/builtins/{name}/execute} 的
 * 请求体类型是 {@link ResumeExecuteRequest}（只有 agentId/prompt）⇒ 前端传的
 * {@code {args:"目标条件"}} 会被 Jackson <b>静默丢弃</b>（未配 FAIL_ON_UNKNOWN_PROPERTIES）
 * ⇒ 「/goal e2e 全绿」的<b>条件文本会丢</b>，目标会被设成空。
 *
 * <p>⇒ {@code /goal} 走独立字面端点（与 {@code /compact} 同款做法：
 * {@code CommandController:446} 的显式路由注释即记录了这个教训）。
 *
 * @param args 目标条件原文（空 = 显示当前目标；clear/stop/off/reset/none/cancel = 清空）
 */
public record GoalExecuteRequest(
    String args
) {
}
