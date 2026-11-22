package com.nexusai.model.session.dto;

/**
 * 会话「无人值守 / Brief」状态（V329）· GET /api/v1/sessions/{id}/mode 的 data。
 *
 * @param sessionId           会话 id
 * @param unattended          本会话是否显式开启无人值守（sessions.unattended_mode）
 * @param brief               本会话 Brief 是否开启（sessions.brief_mode）
 * @param channels            当前已接入的 IM 渠道实例数（全局，仅供界面展示）
 * @param canEnableUnattended 本会话是否已绑定渠道（开启无人值守的唯一硬前置；与退出判据同源）
 */
public record SessionModeDto(
        String sessionId,
        boolean unattended,
        boolean brief,
        int channels,
        boolean canEnableUnattended) {
}
