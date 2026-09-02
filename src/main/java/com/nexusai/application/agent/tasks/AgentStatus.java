package com.nexusai.application.agent.tasks;

import java.util.List;

/**
 * 团队 agent 忙闲状态（基于任务所有权）· 对齐 CC tasks.ts:706-712 AgentStatus
 *
 * <p>CC 真源（grep 自验，非注释）：
 * <pre>
 * export type AgentStatus = {                    // tasks.ts:706-712
 *   agentId: string                              // tasks.ts:707
 *   name: string                                 // tasks.ts:708
 *   agentType?: string                           // tasks.ts:709
 *   status: 'idle' | 'busy'                      // tasks.ts:710
 *   currentTasks: string[] // task IDs the agent owns  // tasks.ts:711
 * }
 * </pre>
 *
 * <p>由 {@link TaskService#getAgentStatuses} 返回：读 team 成员文件（readTeamMembers）→
 * listTasks(sanitizeName(teamName)) → 未完成任务按 owner 双键（name + agentId）分组 →
 * 构造 idle/busy（对齐 CC tasks.ts:763-798）。CC 侧 getAgentStatuses 为导出 API 但自身 0 调用
 * （死代码，U-6 对称补齐备用），语义以 CC tasks.ts:706-712 为准。
 *
 * @param agentId      团队成员 agent ID（CC original: agentId, tasks.ts:707）
 * @param name         团队成员显示名（CC original: name, tasks.ts:708）
 * @param agentType    成员类型，可选（CC original: agentType?, tasks.ts:709）
 * @param status       忙闲状态：'idle' 无未完成任务 / 'busy' 持有至少一个未完成任务
 *                     （CC original: status, tasks.ts:710）
 * @param currentTasks 该 agent 持有的未完成任务 ID 列表（去重，name+agentId 双键并集；
 *                     CC original: currentTasks, tasks.ts:711）
 */
public record AgentStatus(
    String agentId,
    String name,
    String agentType,
    String status,
    List<String> currentTasks
) {}
