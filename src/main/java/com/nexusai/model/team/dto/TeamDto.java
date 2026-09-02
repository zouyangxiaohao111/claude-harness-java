package com.nexusai.model.team.dto;

import com.nexusai.application.agent.team.TeamDiscovery;

import java.util.List;

/**
 * Team 详情 DTO · GET /api/v1/teams 列表 / GET /api/v1/teams/{name} 详情 / 状态 STOMP 事件 team 字段。
 *
 * <p>config.json 解析态（TeamStatusPublisher.toDto 装配）：
 * <ul>
 *   <li>{@code name/description/createdAt/leadAgentId/leadSessionId} —— config.json 顶层字段
 *       （TeamCreateTool.buildConfigJson 落盘）；config.json 无 description 时 null；</li>
 *   <li>{@code members} —— members 数组映射（{@link TeamMemberDto}）；</li>
 *   <li>{@code teammateStatuses} —— {@link TeamDiscovery#getTeammateStatuses} 输出（排除 team-lead），
 *       供前端成员网格渲染 running/idle/mode/color。</li>
 * </ul>
 */
public record TeamDto(
    String name,
    String description,
    Long createdAt,
    String leadAgentId,
    String leadSessionId,
    List<TeamMemberDto> members,
    List<TeamDiscovery.TeammateStatus> teammateStatuses) {}
