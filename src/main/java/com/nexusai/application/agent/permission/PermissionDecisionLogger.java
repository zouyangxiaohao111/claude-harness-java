package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.mcp.McpUrlNormalizer;
import com.nexusai.application.agent.mcp.OfficialMcpRegistry;
import com.nexusai.application.agent.telemetry.McpServerToolSanitizer;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限决策遥测 · 对齐 CC {@code hooks/toolPermission/permissionLogging.ts:181-235}
 * {@code logPermissionDecision} 单点总入口.
 *
 * <p>CC 真源职责 (permissionLogging.ts:181-235):
 * <ol>
 *   <li>按 source 分发 approval / rejection 事件 (granted_in_config / denied_in_config /
 *       granted_by_classifier / granted_in_prompt_permanent|temporary /
 *       granted_by_permission_hook / rejected_in_prompt)</li>
 *   <li>{@code waiting_for_user_permission_ms} — 仅当用户真的被弹窗 (非自动批准) 才带</li>
 *   <li>code-edit counter (Edit / Write / NotebookEdit → OTel counter)</li>
 *   <li>{@code toolUseContext.toolDecisions.set(toolUseID, {source, decision, timestamp})}
 *       — 下游 tool_result telemetry 读 decision_source / decision_type</li>
 *   <li>OTel {@code tool_decision} 事件</li>
 * </ol>
 *
 * <p><b>Java 特有说明 (toolDecisions 链路拆分)</b>: CC 直接 mutate
 * {@code toolUseContext.toolDecisions} Map; Java 的 ToolUseContext 是 immutable record,
 * 本 logger 返回 {@link ToolDecisionInfo} (含时间戳), 由 ToolPermissionGate 嵌入
 * {@code DecisionResult.decisionInfo()}, 最终 StreamingToolExecutor.injectDecisionInfo
 * 写入 executor 持有的 decisions map — 端到端等价 CC 的 set 语义.
 *
 * <p><b>waiting_for_user_permission_ms 语义</b>: {@code permissionPromptStartTimeMs} 非 null
 * 表示用户确实被弹窗 (interactive 分支起点打点), 自动批准路径 (config / classifier 命中)
 * 传 null → 不输出 waitMs (对齐 CC baseMetadata :91-104).
 *
 * @see ToolPermissionGate
 * @since Session H9
 *
 * <p><b>[H9-GAP-2] Spring bean</b>: 本类标注 {@code @Component} 让 {@link ToolPermissionGate}
 * 的 {@code @Autowired} 构造器能注入真实 telemetry bean — 消除 gate @Component 注解与
 * 实际实例化路径 (createSpringBean 静态工厂 fallback) 不一致的问题. {@code Telemetry}
 * 经 {@code @Autowired(required=false)} 注入 (无 telemetry bean 时降级为仅返回归因,
 * 与 {@code new PermissionDecisionLogger(null)} 行为一致).
 */
@Component
public final class PermissionDecisionLogger {

    private static final Logger log = LoggerFactory.getLogger(PermissionDecisionLogger.class);

    /** CC CODE_EDITING_TOOLS = ['Edit', 'Write', 'NotebookEdit'] (permissionLogging.ts:33). */
    private static final List<String> CODE_EDITING_TOOLS = List.of("Edit", "Write", "NotebookEdit");

    /**
     * 决策来源 · 对齐 CC PermissionApprovalSource | PermissionRejectionSource
     * (PermissionContext.ts:45-53) 判别联合.
     */
    public sealed interface Source
            permits Source.Config, Source.Hook, Source.User, Source.Classifier,
                    Source.UserAbort, Source.UserReject {
        /** CC { type: 'config' } — settings allow/deny 规则自动批准/拒绝. */
        record Config() implements Source {}
        /** CC { type: 'hook', permanent? } — PermissionRequest hook 决策. */
        record Hook(boolean permanent) implements Source {}
        /** CC { type: 'user', permanent } — 用户在弹窗中的 allow. */
        record User(boolean permanent) implements Source {}
        /** CC { type: 'classifier' } — 分类器自动放行. */
        record Classifier() implements Source {}
        /** CC { type: 'user_abort' } — 用户中止 (Esc / 新消息打断). */
        record UserAbort() implements Source {}
        /** CC { type: 'user_reject', hasFeedback } — 用户拒绝弹窗. */
        record UserReject(boolean hasFeedback) implements Source {}
    }

    /** 可为 null — null 时仅返回 ToolDecisionInfo, 不发射任何 telemetry (测试/降级). */
    private final Telemetry telemetry;

    /** official MCP registry（RES-07d isOfficial 判定源）· required=false：无 bean 时降级为全脱敏。 */
    private final OfficialMcpRegistry officialRegistry;

    /**
     * MCP server 配置源 · [IMP-E1 DC-2] McpServerInfo 不再承载 serverUrl，official URL 判定
     * 改由 McpToolPool 配置层提供（对齐 CC getLoggingSafeMcpBaseUrl，metadata.ts:102-116）。
     * required=false：无 bean 时降级为全脱敏。
     */
    private final com.nexusai.application.agent.mcp.McpToolPool mcpToolPool;

    /**
     * @Autowired 构造器 · Telemetry / OfficialMcpRegistry / McpToolPool 经 required=false 注入
     * (无 bean 时降级, 见类 JavaDoc)。测试直接 {@code new PermissionDecisionLogger(telemetry)} 不受影响.
     */
    @Autowired
    public PermissionDecisionLogger(@Autowired(required = false) Telemetry telemetry,
                                    @Autowired(required = false) OfficialMcpRegistry officialRegistry,
                                    @Autowired(required = false) com.nexusai.application.agent.mcp.McpToolPool mcpToolPool) {
        this.telemetry = telemetry;
        this.officialRegistry = officialRegistry;
        this.mcpToolPool = mcpToolPool;
    }

    /** 1 参便捷构造器（测试/降级沿用）· officialRegistry/mcpToolPool = null → isOfficial 判定降级为全脱敏。 */
    public PermissionDecisionLogger(Telemetry telemetry) {
        this(telemetry, null, null);
    }

    /**
     * 工具名 telemetry 脱敏 · 对齐 CC isAnalyticsToolDetailsLoggingEnabled（metadata.ts:102-116）:
     * MCP server base URL 命中 official registry → telemetry 保留真实 MCP 工具名（directory
     * connector，非用户特定配置）；custom/未知 URL → 脱敏 {@code mcp_tool}（PII 防护，
     * sanitizeToolNameForAnalytics metadata.ts:70-77）。非 MCP 工具 → 原样。
     */
    private String sanitizeToolName(Tool tool) {
        if (tool == null) {
            return null;
        }
        McpServerInfo info = tool.mcpInfo();
        if (info != null && officialRegistry != null) {
            String baseUrl = resolveMcpServerBaseUrl(info.serverName());
            if (baseUrl != null) {
                String normalized = McpUrlNormalizer.normalizeOfficial(baseUrl);
                if (normalized != null && officialRegistry.isOfficial(normalized)) {
                    return tool.name();
                }
            }
        }
        return McpServerToolSanitizer.sanitize(tool.name());
    }

    /**
     * 取 MCP server base URL · [IMP-E1 DC-2] McpServerInfo 仅 {serverName,toolName}，URL 由
     * McpToolPool 配置层提供（对齐 CC getLoggingSafeMcpBaseUrl）。无 pool / 未装配 → null
     * （fail-closed：不判 official → 脱敏）。
     *
     * @param serverName MCP server 名
     * @return server base URL；不可得 → null
     */
    private String resolveMcpServerBaseUrl(String serverName) {
        if (mcpToolPool == null || serverName == null) {
            return null;
        }
        return mcpToolPool.getServerBaseUrl(serverName);
    }

    /**
     * 记录一次权限决策 · 对齐 CC {@code logPermissionDecision}
     * (Open-ClaudeCode/src/hooks/toolPermission/permissionLogging.ts:181-235).
     *
     * @param tool                       工具实例 (telemetry 归因)
     * @param input                      工具输入 (code-edit counter 语言提取预留)
     * @param ctx                        工具调用上下文 (local-only, 仅读)
     * @param toolUseId                  工具调用 ID (CC original: toolUseID)
     * @param decision                   决策行为 ({@code "accept"} / {@code "reject"})
     * @param source                     决策来源 (CC PermissionApprovalSource/RejectionSource)
     * @param permissionPromptStartTimeMs 弹窗起点时间戳 (null = 未被弹窗 → 无 waitMs;
     *                                    CC original: permissionPromptStartTimeMs)
     * @return 时间戳归因 ({@code {source, decision, timestamp}} · CC permissionLogging.ts:224-228)
     */
    public ToolDecisionInfo logPermissionDecision(
            Tool tool, JsonNode input, ToolUseContext ctx, String toolUseId,
            String decision, Source source, Long permissionPromptStartTimeMs) {
        if (tool == null || decision == null || source == null) {
            return null;
        }
        String sourceString = sourceToString(source);
        // CC :189-192 — waitMs 仅当用户被弹窗时输出
        Long waitMs = permissionPromptStartTimeMs != null
            ? Long.valueOf(System.currentTimeMillis() - permissionPromptStartTimeMs)
            : null;

        emitEvent(tool, toolUseId, decision, source, waitMs);

        // CC :211-218 — code-edit counter (Edit/Write/NotebookEdit)
        if (isCodeEditingTool(tool.name()) && telemetry != null) {
            try {
                telemetry.incrementCodeEditCounter(tool.name(), decision, sourceString, decision);
            } catch (Throwable th) {
                log.warn("权限决策 code-edit counter 失败: tool={} err={}", tool.name(), th.toString());
            }
        }

        // CC :230-234 — OTel tool_decision
        if (telemetry != null) {
            try {
                telemetry.logOTelEvent("tool_decision", Map.of(
                    "decision", decision,
                    "source", sourceString,
                    "tool_name", sanitizeToolName(tool)));
            } catch (Throwable th) {
                log.warn("权限决策 tool_decision 事件失败: tool={} err={}", tool.name(), th.toString());
            }
        }

        // CC :220-228 — toolDecisions.set(toolUseID, {source, decision, timestamp})
        //   Java 链路: 返回值 → DecisionResult.decisionInfo() → executor 写入 decisions map
        return new ToolDecisionInfo(sourceString, decision);
    }

    /**
     * 记录工具取消 · 对齐 CC {@code logCancelled}
     * (PermissionContext.ts:132-138, {@code logEvent('tengu_tool_use_cancelled', ...)}).
     *
     * @param tool      工具实例
     * @param ctx       工具调用上下文 (可为 null — 仅日志归因)
     * @param toolUseId 工具调用 ID
     */
    public void logCancelled(Tool tool, ToolUseContext ctx, String toolUseId) {
        if (telemetry == null || tool == null) {
            return;
        }
        try {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("toolName", sanitizeToolName(tool));
            attrs.put("toolUseID", McpServerToolSanitizer.sanitize(toolUseId));
            telemetry.recordEvent("tengu_tool_use_cancelled", attrs);
            telemetry.logOTelEvent("tengu_tool_use_cancelled", attrs);
        } catch (Throwable th) {
            log.warn("权限取消 telemetry 失败: tool={} err={}", tool.name(), th.toString());
        }
    }

    /**
     * 是否为 code-edit 工具 · 对齐 CC {@code isCodeEditingTool} (permissionLogging.ts:35-37).
     *
     * @param toolName 工具名
     * @return Edit / Write / NotebookEdit 之一
     */
    public static boolean isCodeEditingTool(String toolName) {
        return toolName != null && CODE_EDITING_TOOLS.contains(toolName);
    }

    /**
     * 结构化 source → 扁平字符串 · 对齐 CC {@code sourceToString}
     * (permissionLogging.ts:68-89).
     */
    public static String sourceToString(Source source) {
        if (source instanceof Source.Config) {
            return "config";
        }
        if (source instanceof Source.Hook) {
            return "hook";
        }
        if (source instanceof Source.User user) {
            return user.permanent() ? "user_permanent" : "user_temporary";
        }
        if (source instanceof Source.Classifier) {
            return "classifier";
        }
        if (source instanceof Source.UserAbort) {
            return "user_abort";
        }
        if (source instanceof Source.UserReject) {
            return "user_reject";
        }
        return "unknown";
    }

    /**
     * 事件名分发 · 对齐 CC {@code logApprovalEvent} (permissionLogging.ts:107-149) +
     * {@code logRejectionEvent} (permissionLogging.ts:152-176).
     */
    private void emitEvent(Tool tool, String toolUseId, String decision,
                           Source source, Long waitMs) {
        if (telemetry == null) {
            return;
        }
        String eventName = null;
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("toolName", sanitizeToolName(tool));
        attrs.put("toolUseID", McpServerToolSanitizer.sanitize(toolUseId));

        if ("accept".equals(decision)) {
            if (source instanceof Source.Config) {
                // CC :113-119 — config 自动批准无 waitMs
                eventName = "tengu_tool_use_granted_in_config";
            } else if (source instanceof Source.Classifier) {
                eventName = "tengu_tool_use_granted_by_classifier";
                addWaitMs(attrs, waitMs);
            } else if (source instanceof Source.User user) {
                // CC :131-139 — user permanent/temporary 分流
                eventName = user.permanent()
                    ? "tengu_tool_use_granted_in_prompt_permanent"
                    : "tengu_tool_use_granted_in_prompt_temporary";
                addWaitMs(attrs, waitMs);
            } else if (source instanceof Source.Hook hook) {
                // CC :140-145 — hook allow + permanent 属性
                eventName = "tengu_tool_use_granted_by_permission_hook";
                attrs.put("permanent", hook.permanent());
                addWaitMs(attrs, waitMs);
            }
        } else {
            if (source instanceof Source.Config) {
                // CC :158-164 — config 自动拒绝无 waitMs
                eventName = "tengu_tool_use_denied_in_config";
            } else {
                // CC :166-175 — 拒绝共用 rejected_in_prompt, isHook / hasFeedback 区分
                eventName = "tengu_tool_use_rejected_in_prompt";
                if (source instanceof Source.Hook) {
                    attrs.put("isHook", true);
                } else if (source instanceof Source.UserReject ur) {
                    attrs.put("hasFeedback", ur.hasFeedback());
                } else {
                    attrs.put("hasFeedback", false);
                }
                addWaitMs(attrs, waitMs);
            }
        }
        if (eventName == null) {
            log.warn("权限决策事件: 未知 source/decision 组合 decision={} source={}", decision, source);
            return;
        }
        try {
            telemetry.recordEvent(eventName, attrs);
        } catch (Throwable th) {
            log.warn("权限决策事件失败: event={} tool={} err={}", eventName, tool.name(), th.toString());
        }
    }

    private static void addWaitMs(Map<String, Object> attrs, Long waitMs) {
        if (waitMs != null) {
            attrs.put("waiting_for_user_permission_ms", waitMs);
        }
    }
}
