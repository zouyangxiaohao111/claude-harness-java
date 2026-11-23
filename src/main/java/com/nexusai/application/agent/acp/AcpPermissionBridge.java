package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/**
 * ACP 权限桥 · 把外部 agent 的 {@code session/request_permission} 接到本仓 10 层权限管线。
 *
 * <p>复用点（影响面 B1）：
 * <ul>
 *   <li>{@link ToolPermissionGate#check} —— 规则 / ask / dontAsk / hook 竞速 / 渠道中继 /
 *       刷新重放全部自动生效；ASK 由 gate 内部同步弹窗后收敛为 ALLOW/DENY。</li>
 *   <li>父 TUC（{@link ToolUseContext}）—— 由调用方透传 {@code effectiveParentTuc}，
 *       提供 sessionId / effectiveCwd / permissionContext。</li>
 * </ul>
 *
 * <p><b>答复映射（MVP · 照 DSH 策略）</b>：允许 ⇒ 第一个
 * {@code allow_once|allow_always} 的 optionId；拒绝 ⇒ {@code {outcome:"cancelled"}}。
 * 精确 optionId 往返（区分「仅此次 / 总是」）列为后续项。
 *
 * <p><b>危险命令先于策略</b>：{@link AcpDangerousCommandPolicy} 命中即回 cancelled，
 * <b>不弹窗、不受 allow/yolo 影响</b>。
 */
public final class AcpPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(AcpPermissionBridge.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 策略：ask（走审批管线） / allow（自动放行） / reject（一律拒绝）。 */
    public static final String POLICY_ASK = "ask";
    public static final String POLICY_ALLOW = "allow";
    public static final String POLICY_REJECT = "reject";

    private final ToolPermissionGate gate;
    private final ToolUseContext parentTuc;
    private final Path cwd;
    private final String policy;

    public AcpPermissionBridge(ToolPermissionGate gate, ToolUseContext parentTuc, Path cwd, String policy) {
        this.gate = gate;
        this.parentTuc = parentTuc;
        this.cwd = cwd;
        this.policy = normalizePolicy(policy);
    }

    /** 处理一次 {@code session/request_permission}，返回 JSON-RPC result 节点。 */
    public JsonNode decide(JsonNode params) {
        JsonNode toolCall = params == null ? null : params.get("toolCall");
        JsonNode options = params == null ? null : params.get("options");

        AcpDangerousCommandPolicy.Decision danger = AcpDangerousCommandPolicy.check(toolCall, cwd);
        if (danger.blocked()) {
            log.warn("[AcpPermissionBridge] 硬阻断外部工具调用: {}", danger.reason());
            return AcpClient.cancelledOutcome();
        }

        if (POLICY_REJECT.equals(policy)) {
            return AcpClient.cancelledOutcome();
        }
        if (POLICY_ALLOW.equals(policy)) {
            String optionId = firstAllowOptionId(options);
            return optionId != null ? AcpClient.selectedOutcome(optionId) : AcpClient.cancelledOutcome();
        }

        // ask：走既有审批管线
        if (gate == null || parentTuc == null) {
            // 没有审批通道 ⇒ 拒绝，绝不在无人确认时放行
            log.warn("[AcpPermissionBridge] 未注入 permissionGate / 父 TUC，外部工具调用默认拒绝");
            return AcpClient.cancelledOutcome();
        }
        String callId = toolCall.path("toolCallId").asText("");
        if (callId.isBlank()) {
            callId = "acp-perm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        String toolName = synthesizeToolName(toolCall);
        JsonNode input = synthesizeInput(toolCall);
        try {
            ToolPermissionGate.DecisionResult result = gate.check(
                    new AcpSyntheticTool(toolName),
                    new ToolUseBlock(callId, toolName, input),
                    input, parentTuc, parentTuc.permissionContext(), null);
            boolean allowed = result != null
                    && result.decision() == ToolPermissionGate.Decision.ALLOW;
            if (allowed) {
                String optionId = firstAllowOptionId(options);
                if (optionId != null) {
                    return AcpClient.selectedOutcome(optionId);
                }
            }
            return AcpClient.cancelledOutcome();
        } catch (Throwable t) {
            // 审批链异常不得把外部进程挂死：按拒绝处理并留诊断
            log.warn("[AcpPermissionBridge] 审批链异常，按拒绝处理: {}", t.toString());
            return AcpClient.cancelledOutcome();
        }
    }

    /** 取第一个 allow 类选项的 optionId；没有则 null。 */
    static String firstAllowOptionId(JsonNode options) {
        if (options == null || !options.isArray()) {
            return null;
        }
        for (JsonNode option : options) {
            String kind = option.path("kind").asText("");
            if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                String optionId = option.path("optionId").asText("");
                if (!optionId.isBlank()) {
                    return optionId;
                }
            }
        }
        return null;
    }

    static String normalizePolicy(String value) {
        if (value == null) {
            return POLICY_ASK;
        }
        String p = value.trim().toLowerCase();
        if ("allow".equals(p) || "yolo".equals(p) || "bypasspermissions".equals(p)
                || "danger-full-access".equals(p)) {
            return POLICY_ALLOW;
        }
        if ("reject".equals(p) || "deny".equals(p)) {
            return POLICY_REJECT;
        }
        return POLICY_ASK;
    }

    static String synthesizeToolName(JsonNode toolCall) {
        String kind = toolCall.path("kind").asText("").toLowerCase();
        String command = AcpDangerousCommandPolicy.extractCommand(toolCall);
        if ("execute".equals(kind) && command != null) {
            return "Bash";
        }
        switch (kind) {
            case "read":
                return "Read";
            case "edit":
                return "Edit";
            case "search":
                return "Grep";
            case "fetch":
                return "WebFetch";
            default:
                break;
        }
        String title = toolCall.path("title").asText("");
        return title.isBlank() ? "acp-" + (kind.isBlank() ? "tool" : kind) : title;
    }

    static JsonNode synthesizeInput(JsonNode toolCall) {
        String command = AcpDangerousCommandPolicy.extractCommand(toolCall);
        if (command != null && "execute".equalsIgnoreCase(toolCall.path("kind").asText(""))) {
            ObjectNode input = JSON.createObjectNode();
            input.put("command", command);
            return input;
        }
        JsonNode rawInput = toolCall.get("rawInput");
        if (rawInput != null && rawInput.isObject()) {
            return rawInput;
        }
        ObjectNode fallback = JSON.createObjectNode();
        fallback.put("title", toolCall.path("title").asText(""));
        fallback.put("kind", toolCall.path("kind").asText(""));
        return fallback;
    }

    /** 只用于权限判定的合成工具 · ⛔ {@code execute} 永不会被调用（工具在外部进程跑）。 */
    private static final class AcpSyntheticTool implements Tool {

        private final String name;

        AcpSyntheticTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "ACP 外部 agent 的工具调用（仅用于权限审批，本进程不执行）";
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.error(call.id(), "ACP 工具由外部 agent 执行，本进程不代执行");
        }
    }
}
