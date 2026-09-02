package com.nexusai.application.agent.workflow.agent;

import com.nexusai.application.agent.workflow.AgentRunParams;

import java.util.function.Predicate;

/**
 * 路由规则：决定哪些 params 走哪个 adapter · CC original: {@code AdapterRouteRule}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:72-79)。
 *
 * <p>按<b>插入顺序</b>匹配，首个命中胜出（agentAdapter.ts:74 注释 "Matched in insertion order;
 * first hit wins"）。
 *
 * <pre>{@code
 * export type AdapterRouteRule =
 *   | { kind: 'agentType'; agentType: string; adapter: string }
 *   | { kind: 'model';     pattern: string; adapter: string }
 *   | { kind: 'custom';    match: (params: AgentRunParams) => boolean; adapter: string }
 * }</pre>
 *
 * <p>Java sealed interface + 3 record，{@code switch} pattern matching 替代 CC 判别联合。
 * 三变体公共字段 {@link #adapter()} = 命中时路由到的 adapter id。
 */
public sealed interface AdapterRouteRule
        permits AdapterRouteRule.AgentType, AdapterRouteRule.Model, AdapterRouteRule.Custom {

    /**
     * 命中时路由到的 adapter id · CC original: adapter (agentAdapter.ts:74)。
     *
     * @return 目标 adapter id
     */
    String adapter();

    /**
     * agentType 精确匹配 · CC original: {@code {kind:'agentType', agentType, adapter}} (agentAdapter.ts:73)。
     *
     * @param agentType 匹配的 agentType 值 · CC original: agentType
     * @param adapter   路由目标 adapter id · CC original: adapter
     */
    record AgentType(String agentType, String adapter) implements AdapterRouteRule {
    }

    /**
     * model 前缀匹配 · CC original: {@code {kind:'model', pattern, adapter}} (agentAdapter.ts:74)。
     *
     * <p>matchRule 用 {@code params.model.startsWith(pattern)}（agentAdapter.ts:160-162），
     * 即 model 串必须以 pattern 开头（如 "claude-"）。
     *
     * @param pattern model 前缀 · CC original: pattern
     * @param adapter 路由目标 adapter id · CC original: adapter
     */
    record Model(String pattern, String adapter) implements AdapterRouteRule {
    }

    /**
     * 自定义谓词匹配 · CC original: {@code {kind:'custom', match, adapter}} (agentAdapter.ts:75)。
     *
     * @param match   自定义判定 · CC original: match — (params) => boolean
     * @param adapter 路由目标 adapter id · CC original: adapter
     */
    record Custom(Predicate<AgentRunParams> match, String adapter) implements AdapterRouteRule {
    }
}
