package com.nexusai.model.team.dto;

/**
 * POST /api/v1/teams/{teamName}/members/spawn 请求体 · 前端 teamsApi.spawnMember 传入
 * （nexusai/src/api/teams.ts:18-19）。
 *
 * @param name         CC original: name（必填；成员唯一显示名，agentId = formatAgentId(name, teamName) =
 *                     name@team，spawnInProcess.ts:112）
 * @param subagentType CC original: subagentType（可空；子代理类型，空 → 端点回落 "general-purpose"；
 *                     合法值 = 内置（general-purpose/Explore/Plan 等）+ 用户 .claude/agents/*.md 自定义）
 * @param prompt       CC original: prompt（可空；成员任务提示词）
 */
public record SpawnMemberRequest(String name, String subagentType, String prompt) {}
