package com.nexusai.model.team.dto;

/**
 * POST /api/v1/teams 请求体 · 对齐 TeamCreateTool 输入 {team_name, description?, agent_type?}
 * （TeamCreateTool.ts:37-49）。
 *
 * @param teamName    CC original: team_name（必填；团队名）
 * @param description CC original: description（可空）
 * @param agentType   CC original: agent_type（可空；lead agent 类型）
 * @param sessionId   session 锚定（可空；空 → query ?sessionId= / MDC 兜底，仍空 → 建 team 不落会话列，
 *                    fail-soft）
 */
public record TeamCreateRequest(String teamName, String description, String agentType,
                                String sessionId) {}
