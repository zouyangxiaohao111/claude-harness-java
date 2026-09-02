package com.nexusai.application.agent.tasks;

import java.util.List;

/**
 * Team 成员文件解析结果 · 对齐 CC tasks.ts:724-753 readTeamMembers 返回值
 *
 * <p>CC 真源（grep 自验，非注释）：
 * <pre>
 * async function readTeamMembers(teamName): Promise&lt;{ leadAgentId: string; members: TeamMember[] } | null&gt; {
 *   // 读 {configHome}/teams/{sanitizeName(teamName)}/config.json（tasks.ts:727-728）
 *   // ENOENT → null（tasks.ts:745-747）；其他错误 → logForDebugging + null（tasks.ts:748-751）
 * }
 * </pre>
 *
 * <p>由 {@link TaskService#readTeamMembers} 返回：{@code leadAgentId} 团队 lead agent ID +
 * {@code members} 团队成员列表（对齐 CC tasks.ts:735-741）。CC 侧 readTeamMembers 为模块私有
 * 函数（0 导出），Java 对称补齐为 task-store API 备用，语义以 CC tasks.ts:724-753 为准。
 *
 * @param leadAgentId 团队 lead agent ID（CC original: leadAgentId, tasks.ts:735）
 * @param members     团队成员列表（CC original: members, tasks.ts:736-741）
 */
public record TeamMembers(
    String leadAgentId,
    List<TeamMember> members
) {}
