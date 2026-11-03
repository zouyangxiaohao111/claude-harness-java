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
 * {@code mcp__computer-use__list_apps} · 运行中应用列表
 * （对齐 dsh {@code dsh-computer-use} 的 {@code computer_list_apps}）。
 *
 * <h2>WHY 需要它</h2>
 * <p>dsh 的 {@code list_apps} 描述：<i>"Use this only when the task does not already identify a
 * unique bundle id."</i> —— 当任务说不清是哪个应用时，先列出来让模型选。
 * <p>它还携带每个应用的<b>权限状态</b>（accessibility / screenRecording），
 * 让模型知道<b>哪些应用现在可控</b>（辅助功能没授权的应用，AX 树读不到）。
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li>dsh 的权限状态是<b>逐应用</b>的（每个应用单独探测 AX 可读性）；
 *       本仓的辅助功能 / 屏幕录制授权是<b>进程级</b>（macOS TCC 的粒度就是本进程）
 *       ⇒ 本工具给的是<b>全局</b>授权状态，不是逐应用的。</li>
 *   <li>dsh 额外给 {@code granted}（该应用是否已获授权）；本仓给 {@code granted} ——
 *       依据会话的 computer-use 授权表（{@link ComputerUseGrants#findByAppId}）。</li>
 * </ul>
 */
public final class ComputerUseListAppsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseListAppsTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "list_apps");

    /** 工具描述（对齐 dsh 的取用场景说明）。 */
    private static final String DESCRIPTION =
            "List running user-facing apps with their bundle id, pid, whether they are frontmost, "
                    + "and whether they are already granted for computer use. Use this only when the "
                    + "task does not already identify a unique bundle id.";

    /** 输入 schema。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final MacOsAppCatalog catalog;
    private final ComputerUseGrantStore grantStore;
    private final MacOsTccChecker tcc;

    /**
     * @param catalog    应用目录
     * @param grantStore 授权存储（判「已授权」；可空）
     * @param tcc        TCC 检查（给全局权限状态；可空）
     */
    public ComputerUseListAppsTool(MacOsAppCatalog catalog,
                                   ComputerUseGrantStore grantStore,
                                   MacOsTccChecker tcc) {
        this.catalog = catalog;
        this.grantStore = grantStore;
        this.tcc = tcc;
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
                    "[computer-use] list_apps inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use list_apps is read-only"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        try {
            List<MacOsAppCatalog.RunningApp> apps = catalog.listRunningWithPid();
            ComputerUseGrants grants = grantStore != null
                    ? grantStore.read(sessionId)
                    : ComputerUseGrants.empty();
            boolean accessibility = tcc != null && tcc.accessibilityGranted();
            boolean screenRecording = tcc != null && tcc.screenRecordingGranted();

            ArrayNode array = JSON.createArrayNode();
            for (MacOsAppCatalog.RunningApp app : apps) {
                if (app.bundleId().isEmpty()) {
                    // 没有 bundle id 的进程无法被授权/定位 ⇒ 不列（dsh 同款：要求精确 bundle id）
                    continue;
                }
                ObjectNode node = array.addObject();
                node.put("bundleId", app.bundleId());
                node.put("name", app.displayName());
                node.put("pid", app.pid());
                node.put("frontmost", app.frontmost());
                node.put("hidden", app.hidden());
                node.put("granted", grants.findByAppId(app.bundleId()) != null);
                node.put("accessibility", accessibility ? "granted" : "denied");
                node.put("screenRecording", screenRecording ? "granted" : "denied");
            }

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: session={} 应用数={}",
                        TOOL_NAME, sessionId, array.size());
            }
            return ToolResult.success(call.id(), array.toPrettyString());
        } catch (Exception e) {
            log.error("[computer-use] {} 失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "枚举运行中应用失败: " + e.getMessage());
        }
    }
}
