package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@code mcp__computer-use__list_granted_applications} · 列出本会话已授权应用（对齐 CCB 2.8.4
 * computer-use MCP 的 {@code list_granted_applications}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:861-870}）</h2>
 * <pre>
 * name: 'list_granted_applications',
 * description: 'List the applications currently in the session allowlist, plus the active grant flags and coordinate mode. No side effects.',
 * inputSchema: { type: 'object', properties: {}, required: [] },
 * </pre>
 *
 * <h2>返回形状</h2>
 * <p>CCB 的 handler（{@code toolCalls.ts:3539-3546}）返回：
 * <pre>
 * okJson({ allowedApps: overrides.allowedApps, grantFlags: overrides.grantFlags })
 * </pre>
 * ⇒ 两个键：{@code allowedApps}（{@code AppGrant[]}）+ {@code grantFlags}
 * （{@code {clipboardRead, clipboardWrite, systemKeyCombos}}）。
 *
 * <p><b>本仓比 CCB 多一个键 {@code coordinateMode}</b>：CCB 的<b>描述</b>明写
 * “plus the active grant flags and <b>coordinate mode</b>”，但它的 handler <b>没吐这个键</b>
 * ⇒ CCB 自身描述与实现不一致。本仓按<b>描述</b>补齐（规则十二：描述既然这么写，行为就必须如此），
 * 值取<b>冻结</b>的坐标模式（{@link ComputerUseGates#coordinateMode()}）。
 *
 * <p><b>字段名差异（如实标注）</b>：CCB 的 {@code AppGrant.bundleId} 在 Windows 上无意义
 * （macOS bundle id）⇒ 本仓沿用本仓自己的规范形 {@code appId}（归一后的 exe 名），
 * 与 {@code request_access} 的返回、以及授权列 {@code sessions.computer_use_grants} 同源。
 *
 * <p><b>无副作用</b> ⇒ {@link #isReadOnly} = true、{@link #isConcurrencySafe} = true、
 * 权限自决 {@link PermissionResult#Allow}（免弹窗）。
 */
public final class ComputerUseListGrantedApplicationsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseListGrantedApplicationsTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "list_granted_applications");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:862-863}。 */
    private static final String DESCRIPTION =
            "List the applications currently in the session allowlist, plus the active grant flags "
                    + "and coordinate mode. No side effects.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:864-869}（无参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final ComputerUseGrantStore grantStore;
    private final ComputerUseCoordinateMode coordinateMode;

    /**
     * @param grantStore     会话授权位读写
     * @param coordinateMode <b>冻结</b>的坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     */
    public ComputerUseListGrantedApplicationsTool(ComputerUseGrantStore grantStore,
                                                   ComputerUseCoordinateMode coordinateMode) {
        this.grantStore = grantStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] list_granted_applications inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读（无副作用）⇒ 只读、并发安全。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读工具 · <b>权限自决 Allow（免弹窗）</b>。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use list_granted_applications is read-only"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行列举。
     *
     * @param call 工具调用
     * @param ctx  工具上下文（取 sessionId）
     * @return {@code {allowedApps, grantFlags, coordinateMode}} 的 JSON
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseGrants grants = grantStore == null
                ? ComputerUseGrants.empty()
                : grantStore.read(sessionId);

        ObjectNode root = JSON.createObjectNode();
        ArrayNode apps = root.putArray("allowedApps");
        for (ComputerUseGrant grant : grants.apps()) {
            ObjectNode node = apps.addObject();
            node.put("appId", grant.appId());
            node.put("displayName", grant.displayName());
            node.put("grantedAt", grant.grantedAt());
            if (grant.tier() != null) {
                node.put("tier", grant.tier());
            }
        }
        ObjectNode flags = root.putObject("grantFlags");
        grants.flags().writeTo(flags);
        root.put("coordinateMode", coordinateMode.wire());

        if (log.isDebugEnabled()) {
            log.debug("[computer-use] {}: session={} 应用数={} 坐标模式={}",
                    TOOL_NAME, sessionId, grants.apps().size(), coordinateMode.wire());
        }
        return ToolResult.success(call.id(), root);
    }
}
