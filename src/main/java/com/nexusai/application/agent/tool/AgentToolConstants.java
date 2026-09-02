package com.nexusai.application.agent.tool;

import java.util.Set;

/**
 * AgentToolConstants · 对齐 CC tools/AgentTool/constants.ts.
 *
 * <p>L1 语义: AgentTool / Sub-agent 子系统的全局命名常量集。
 * <ul>
 *   <li>{@link #AGENT_TOOL_NAME} = {@code "Agent"} — 当前 tool name (SubagentTool.java:62 NAME)</li>
 *   <li>{@link #LEGACY_AGENT_TOOL_NAME} = {@code "Task"} — 旧 wire name (权限规则/hooks/恢复的会话)</li>
 *   <li>{@link #VERIFICATION_AGENT_TYPE} = {@code "verification"} — verification agent 子类型</li>
 *   <li>{@link #ONE_SHOT_BUILTIN_AGENT_TYPES} = {@code {"Explore","Plan"}} — 一次性运行不写 agentId/SendMessage/usage trailer</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 public static final String 常量 + 1 static final Set 常量,与 CC 字段一致</li>
 *   <li><b>A2 Golden Trace</b>: 读取即得值,内容恒等</li>
 *   <li><b>A3 不可变</b>: {@code final} 编译期常量;Set 不可变 (Set.of)</li>
 *   <li><b>A4 边界</b>: 常量非空;Set 不含 null</li>
 *   <li><b>A5 业务场景</b>: permission rule 引用 LEGACY_AGENT_TOOL_NAME 'Task' 兼容旧会话;Explore/Plan sub-agent 忽略 trailer</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code export const} → Java {@code public static final};
 * TS {@code ReadonlySet<string>} → Java {@link java.util.Set#of} 不可变 Set。
 */
public final class AgentToolConstants {

    public static final String AGENT_TOOL_NAME = "Agent";
    /** Legacy wire name (CC 注: backward compat with permission rules/hooks/resumed sessions). */
    public static final String LEGACY_AGENT_TOOL_NAME = "Task";
    /** Sub-agent type for the verification built-in agent. */
    public static final String VERIFICATION_AGENT_TYPE = "verification";
    /**
     * Built-in agents that run once and return a report — skip the
     * agentId/SendMessage/usage trailer to save tokens.
     */
    public static final Set<String> ONE_SHOT_BUILTIN_AGENT_TYPES = Set.of("Explore", "Plan");

    private AgentToolConstants() {
        // 常量容器
    }
}
