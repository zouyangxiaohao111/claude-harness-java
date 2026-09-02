package com.nexusai.application.agent.team;

/**
 * Teammate identity · 对齐 CC tasks/InProcessTeammateTask/types.ts:13-20 TeammateIdentity。
 *
 * <p>CC 真源（grep 实证 types.ts:13-20）：
 * <pre>
 * export type TeammateIdentity = {
 *   agentId: string // e.g., "researcher@my-team"            // types.ts:14
 *   agentName: string // e.g., "researcher"                  // types.ts:15
 *   teamName: string                                         // types.ts:16
 *   color?: string                                           // types.ts:17
 *   planModeRequired: boolean                                // types.ts:18
 *   parentSessionId: string // Leader's session ID           // types.ts:19
 * }
 * </pre>
 *
 * <p>说明：CC 注释「Same shape as TeammateContext (runtime) but stored as plain data」
 * （types.ts:8-12）——identity 是存入 AppState 的纯数据，TeammateContext 才是
 * AsyncLocalStorage 运行时载体。Java 侧对应关系：本 record 为纯数据载体；
 * {@link TeammateContext} 为 ThreadLocal 运行时载体。
 *
 * @param agentId          完整 agent ID（"name@team"，CC types.ts:14）
 * @param agentName        agent 名（无 @ 后缀，CC types.ts:15）
 * @param teamName         team 名（CC types.ts:16）
 * @param color            可选 UI 颜色（CC types.ts:17）
 * @param planModeRequired 是否必须先进入 plan 模式（CC types.ts:18）
 * @param parentSessionId  Leader 的 session ID（CC types.ts:19）
 */
public record TeammateIdentity(
    String agentId,
    String agentName,
    String teamName,
    String color,
    boolean planModeRequired,
    String parentSessionId
) {}
