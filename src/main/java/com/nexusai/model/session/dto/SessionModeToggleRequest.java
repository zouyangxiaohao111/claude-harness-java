package com.nexusai.model.session.dto;

/**
 * 会话模式开关请求（V329）· PUT /api/v1/sessions/{id}/mode/{unattended|brief}。
 *
 * @param enabled 目标状态（必填；null = 缺字段，控制器返回业务错误，⛔ 不当成 false）
 * @param cascadeExitUnattended [Q8] 关闭 Brief 且无人值守开启时，true = 用户已确认
 *                              「同时退出无人值守」（一次原子写入两列）；缺省/false = 返回冲突错误
 */
public record SessionModeToggleRequest(Boolean enabled, Boolean cascadeExitUnattended) {
}
