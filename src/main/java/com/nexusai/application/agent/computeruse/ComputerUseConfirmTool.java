package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

/**
 * {@code mcp__computer-use__confirm} · <b>敏感动作的一次性确认</b>
 * （对齐 dsh {@code dsh-computer-use} 的 {@code computer_confirm}）。
 *
 * <h2>WHY 需要它</h2>
 * <p>本仓的授权模型是「{@code request_access} 一次授权整会话」（CCB 路线）：
 * 用户批准某应用后，该会话内可对它持续输入。
 * <p>但有一类动作<b>影响超出该应用</b>（输入密码、确认付款、删除数据、提交表单），
 * 对它们「会话级长期授权」太宽。
 * <p>⇒ dsh 的做法：这类动作标记 {@code sensitive=true}，必须先调 {@code computer_confirm}
 * 拿一个<b>绑定到具体动作</b>的一次性令牌，缺令牌就拒绝执行。
 *
 * <h2>令牌绑定什么（对齐 dsh {@code confirmations.ts:78-86}）</h2>
 * <ul>
 *   <li><b>应用</b>（{@code bundleId:pid}）—— 不能用在别的应用上；</li>
 *   <li><b>观察 id</b> —— 不能用在别的观察上（目标变了就不该沿用旧确认）；</li>
 *   <li><b>动作哈希</b> —— 确认的是「这一个具体动作」，不是「一类动作」。</li>
 * </ul>
 * 令牌<b>一次性</b>（用掉即失效）、<b>有 TTL</b>（默认 5 分钟）、<b>失败尝试也消耗</b>
 * （对齐 dsh 的先删后校验）。
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li>dsh 的令牌由 {@code ctx.approval.request()} 直接弹窗；本仓走<b>既有的 STOMP 审批气泡</b>
 *       （{@code checkPermissions} 返回 {@code Passthrough} ⇒ 权限管线第 3 层转 {@code Ask}），
 *       与 {@link ComputerUseRequestAccessTool} 同款通道，<b>不改 STOMP 事件、不改前端</b>。</li>
 *   <li>dsh 的 {@code sensitive} 由 <b>Skill 侧</b>判定（模型自己声明高影响）；
 *       本仓同样由模型在动作参数里声明 {@code sensitive=true}，
 *       但<b>动作工具会强制校验令牌</b>，不信任模型的自我声明。</li>
 * </ul>
 */
public final class ComputerUseConfirmTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseConfirmTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "confirm");

    /** 工具描述。 */
    private static final String DESCRIPTION =
            "Request one-time user confirmation for a sensitive desktop action (entering a password, "
                    + "confirming a payment, deleting data, submitting a form). Call this immediately "
                    + "before the sensitive action, then pass the returned confirmationToken to that "
                    + "action together with sensitive=true. The token is bound to one app, one "
                    + "observation and one exact action — it cannot be reused for anything else, and it "
                    + "expires.";

    /** 输入 schema。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "observationId": {
                  "type": "string",
                  "description": "observationId of the observation the sensitive action targets."
                },
                "index": {
                  "type": "integer",
                  "description": "Element index from that observation (the [#N] prefix)."
                },
                "targetHandle": {
                  "type": "string",
                  "description": "Opaque element handle from that observation."
                },
                "action": {
                  "type": "string",
                  "description": "The action kind about to run, e.g. \\"click\\" or \\"type\\". Must match the action tool exactly."
                },
                "payload": {
                  "type": "string",
                  "description": "The action payload, e.g. the text to type. Must match the action tool exactly."
                },
                "reason": {
                  "type": "string",
                  "description": "Why this action needs explicit confirmation (shown to the user)."
                },
                "target": {
                  "type": "string",
                  "description": "Human-readable description of what will be affected (shown to the user)."
                }
              },
              "required": ["action"]
            }
            """;

    private final ComputerUseConfirmationStore store;
    private final ComputerUseUiSnapshotStore snapshotStore;

    /**
     * @param store         令牌存储
     * @param snapshotStore 观察存储（取最近一次观察的应用标识，令牌必须绑定到具体应用）
     */
    public ComputerUseConfirmTool(ComputerUseConfirmationStore store,
                                  ComputerUseUiSnapshotStore snapshotStore) {
        this.store = store;
        this.snapshotStore = snapshotStore;
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
                    "[computer-use] confirm inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读（发令牌不改桌面）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * ⭐ <b>必须弹窗</b>：本工具的全部意义就是「问用户一次」⇒ 返回 {@code Passthrough}
     * ⇒ 权限管线第 3 层转 {@code Ask} ⇒ 走 STOMP 审批气泡
     * （与 {@link ComputerUseRequestAccessTool} 同款通道）。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        String message = buildApprovalMessage(input);
        return new PermissionResult.Passthrough(
                message,
                new PermissionDecisionReason.Other(message),
                List.of(), null, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String sessionId = ctx != null ? ctx.sessionId() : null;
        String observationId = input.path("observationId").isTextual()
                ? input.path("observationId").asText() : null;
        int index = input.path("index").asInt(-1);
        String targetHandle = input.path("targetHandle").isTextual()
                ? input.path("targetHandle").asText() : null;
        String action = input.path("action").isTextual() ? input.path("action").asText() : "";
        String payload = input.path("payload").isTextual() ? input.path("payload").asText() : null;
        if (action.isEmpty()) {
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }

        // 应用标识：取该会话最近一次观察的应用（令牌必须绑定到具体应用）
        String appKey = resolveAppKey(sessionId);

        Map<String, String> canonical = ComputerUseConfirmationStore.canonicalParams(
                observationId, index, targetHandle, action, payload);
        String actionHash = ComputerUseConfirmationStore.actionHash(canonical);

        ComputerUseConfirmationStore.Issued issued =
                store.issue(sessionId, appKey, observationId, actionHash);

        ObjectNode out = JSON.createObjectNode();
        out.put("confirmationToken", issued.token());
        out.put("observationId", issued.observationId());
        out.put("actionHash", actionHash);
        out.put("expiresAt", issued.expiresAt() == Long.MAX_VALUE
                ? "never" : String.valueOf(issued.expiresAt()));

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 已发放一次性确认令牌: session={} app={} action={} 观察={}",
                    TOOL_NAME, sessionId, appKey, action, observationId);
        }
        return ToolResult.success(call.id(), out.toPrettyString());
    }

    /**
     * 取该会话最近一次观察的应用标识（{@code bundleId:pid}）。
     *
     * <p>令牌<b>必须</b>绑定到具体应用（dsh {@code confirmations.ts:105} 比对
     * {@code record.app.bundleId/pid} 与当前应用）⇒ 否则「给 A 应用的确认」
     * 就能用在 B 应用上。
     * <p>无观察 ⇒ 空串（令牌仍发放，但只对空 appKey 有效 ⇒ 动作侧会因不匹配而拒绝）。
     */
    private String resolveAppKey(String sessionId) {
        if (snapshotStore == null) {
            return "";
        }
        ComputerUseUiSnapshotStore.Observation latest = snapshotStore.latest(sessionId);
        if (latest == null) {
            return "";
        }
        return latest.bundleId() + ":" + latest.pid();
    }

    /** 审批气泡上给用户看的话。 */
    private static String buildApprovalMessage(JsonNode input) {
        String reason = input.path("reason").isTextual() ? input.path("reason").asText() : "";
        String target = input.path("target").isTextual() ? input.path("target").asText() : "";
        String action = input.path("action").isTextual() ? input.path("action").asText() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("Confirm sensitive desktop action");
        if (!action.isEmpty()) {
            sb.append(" (").append(action).append(')');
        }
        if (!target.isEmpty()) {
            sb.append(": ").append(target);
        }
        if (!reason.isEmpty()) {
            sb.append(" — ").append(reason);
        }
        return sb.toString();
    }
}
