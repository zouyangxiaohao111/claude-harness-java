package com.nexusai.application.agent.tasks;

/**
 * 团队成员信息（TeamFile member 结构子集）· 对齐 CC tasks.ts:697-701 TeamMember
 *
 * <p>CC 真源（grep 自验，非注释）：
 * <pre>
 * export type TeamMember = {     // tasks.ts:697-701
 *   agentId: string              // tasks.ts:698
 *   name: string                 // tasks.ts:699
 *   agentType?: string           // tasks.ts:700
 * }
 * </pre>
 *
 * <p>由 {@link TaskService#readTeamMembers} 从 {@code {configHome}/teams/{sanitized}/config.json}
 * 的 members 数组提取（对齐 CC tasks.ts:737-741 逐成员映射），供
 * {@link TaskService#getAgentStatuses} 汇总团队 agent 忙闲状态。CC 侧为导出 API 但自身 0 调用
 * （死代码，U-6 对称补齐备用），语义以 CC tasks.ts:697-701 为准。
 *
 * @param agentId   团队成员 agent ID（CC original: agentId, tasks.ts:698）
 * @param name      团队成员显示名（CC original: name, tasks.ts:699）
 * @param agentType 成员类型，可选（CC original: agentType?, tasks.ts:700）
 */
public record TeamMember(
    String agentId,
    String name,
    String agentType
) {}
