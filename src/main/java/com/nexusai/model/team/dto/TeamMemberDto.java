package com.nexusai.model.team.dto;

/**
 * Team 成员 DTO · 对齐 TeamCreateTool.buildConfigJson 落盘 members 元素 + TeamDiscovery.Member
 * （camelCase 字段名）。REST 入参（POST /api/v1/teams/{teamName}/members）/ 出参（TeamDto.members）
 * 复用。
 *
 * @param agentId      CC original: agentId（formatAgentId(name, team)）
 * @param name         CC original: name（sanitizedName）
 * @param agentType    CC original: agentType（可空）
 * @param model        CC original: model（可空）
 * @param color        CC original: color（teammateColor，可空）
 * @param mode         CC original: mode（PermissionMode，可空）
 * @param isActive     CC original: isActive（可空；null = 未显式标注 → 判活跃）
 * @param joinedAt     CC original: joinedAt（epochMillis，可空）
 * @param tmuxPaneId   CC original: tmuxPaneId（in-process → 'in-process'）
 * @param cwd          CC original: cwd（可空）
 * @param backendType  CC original: backendType（可空）
 */
public record TeamMemberDto(String agentId, String name, String agentType, String model,
                            String color, String mode, Boolean isActive, Long joinedAt,
                            String tmuxPaneId, String cwd, String backendType) {}
