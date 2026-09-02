package com.nexusai.apis.mcp;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 入站 MCP 调用权限上下文工厂 · 对齐 CC {@code Open-ClaudeCode/src/entrypoints/mcp.ts:102}
 * CallTool 的 {@code getEmptyToolPermissionContext()}（Tool.ts:140-148）。
 *
 * <h2>v4 对齐（OPD-WF8-02-GS-01 拍板，2026-08-18）</h2>
 * <p>回归 CC 空上下文：<b>不合并全量 {@code PermissionSourceLoader} 规则</b>、mode=default、
 * 三桶（allow/deny/ask）为空、{@code shouldAvoidPermissionPrompts} 未置位（false）。
 * 旧实现按 S06 文档 I-11 合并 7 个 loader 规则 + 置 headless auto-deny 位，偏离 CC 真源
 * （EV-WF8-GS-106）；本次按用户拍板「对齐 CC 空上下文」移除。
 *
 * <p>CC 空上下文语义（Tool.ts:140-148，逐字段）：
 * <ul>
 *   <li>{@code mode: 'default'} —— {@link PermissionMode#DEFAULT}</li>
 *   <li>{@code alwaysAllowRules/alwaysDenyRules/alwaysAskRules: {}} —— 三桶空</li>
 *   <li>{@code additionalWorkingDirectories: new Map()} —— 空</li>
 *   <li>{@code isBypassPermissionsModeAvailable: false}</li>
 *   <li>{@code isAutoModeAvailable / shouldAvoidPermissionPrompts / awaitAutomatedChecksBeforeDialog}
 *       —— 未置位（undefined → false）；{@code prePlanMode} undefined → null</li>
 * </ul>
 *
 * <p>非交互 ask 的中止由调用方承载：{@link InboundMcpToolProvider#buildToolUseContext}
 * 置 {@code isNonInteractiveSession=true}，Ask 结果经 {@code WebSocketPermissionPrompter} 的
 * isNonInteractiveSession 分支拒绝（对齐 CC mcp.ts:121 + interactive 语义），而非在上下文
 * 置 headless 位。
 *
 * <p>每调用新鲜 {@link #build()}（对齐 CC per-call {@code getEmptyToolPermissionContext()}，
 * 无状态 → 无缓存一致性负担）。
 *
 * @see InboundMcpToolProvider
 */
@Component
public class InboundPermissionContextFactory {

    private static final Logger log = LoggerFactory.getLogger(InboundPermissionContextFactory.class);

    public InboundPermissionContextFactory() {
        if (log.isInfoEnabled()) {
            log.info("InboundPermissionContextFactory initialized（对齐 CC getEmptyToolPermissionContext 空上下文，"
                + "不合并全量规则）");
        }
    }

    /**
     * 构建入站权限上下文 · 每调用新鲜构建（CC per-call {@code getEmptyToolPermissionContext()}）。
     *
     * <p>返回 CC 空上下文等价物：DEFAULT mode + 空三桶 + shouldAvoidPermissionPrompts=false
     * （headless 位不置，非交互 ask 由 isNonInteractiveSession 兜底）。与旧 S06 契约
     * （合并全量 rules + headless auto-deny）决裂，以 CC 真源为准。
     *
     * @return CC getEmptyToolPermissionContext 等价的空上下文
     */
    public ToolPermissionContext build() {
        if (log.isDebugEnabled()) {
            log.debug("InboundPermissionContextFactory.build: 返回 CC 空上下文（mode=DEFAULT，三桶空，"
                + "shouldAvoidPermissionPrompts=false）");
        }
        // CC Tool.ts:140-148 getEmptyToolPermissionContext 逐字段等价：
        //   mode='default' + 三桶 {} + additionalWorkingDirectories=new Map() +
        //   isBypassPermissionsModeAvailable=false + 未置位字段（isAutoModeAvailable/
        //   shouldAvoidPermissionPrompts/awaitAutomatedChecksBeforeDialog → false；prePlanMode → null）
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(),
            Map.of(),                     // additionalWorkingDirectories（CC new Map()）
            false,                        // isBypassPermissionsModeAvailable（CC :147）
            false,                        // isAutoModeAvailable（CC 未置位 → undefined → false）
            Map.of(),                     // strippedDangerousRules（CC 未置位 → 空）
            false,                        // shouldAvoidPermissionPrompts（CC 未置位 → false）
            false,                        // awaitAutomatedChecksBeforeDialog（CC 未置位 → false）
            null);                        // prePlanMode（CC 未置位 → undefined → null）
    }
}
