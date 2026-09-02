package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TeammateMailbox 协议字段层 P1-P9 定向测试 · 对齐 CC teammateMailbox.ts:394-1095。
 *
 * <p>WHY (规则九)：结构化消息（permission_request / sandbox_permission_request /
 * plan_approval_request / shutdown_* / mode_set_request / idle_notification）是 leader 与
 * teammate 跨进程权限/协调的<b>协议契约</b>。若字段名偏离 CC（如 permission_request 应 snake_case，
 * 其余 camelCase），CC 生态消费侧（useInboxPoller / attachments.ts）将无法解析，权限请求会被
 * 当作原始 LLM 上下文吞掉，权限委派整条链路失效。
 *
 * <p>每项测试断言 wire 格式（toCompactJson 后 parse 回）与 is* 解析器双向一致。
 */
class TeammateMailboxProtocolTest {

    private final ObjectMapper json = new ObjectMapper();

    // ── P-7 permission_request / permission_response（snake_case）──────────────

    @Test
    void createPermissionRequestMessage_usesSnakeCaseWireFormat() throws Exception {
        // WHY: CC PermissionRequestMessage (:453-462) 字段名对齐 SDK can_use_tool，snake_case
        //      （request_id/agent_id/tool_name/tool_use_id/permission_suggestions）。
        //      若误用 camelCase（requestId 等），leader 侧 isPermissionRequest 解析不出字段。
        TeammateMailbox.PermissionRequestMessage m = TeammateMailbox.createPermissionRequestMessage(
            "req-1", "alice", "Bash", "toolu-1", "run ls",
            Map.of("command", "ls"), List.of());
        JsonNode p = json.readTree(TeammateMailbox.toCompactJson(m));
        assertEquals("permission_request", p.path("type").asText());
        assertEquals("req-1", p.path("request_id").asText());
        assertEquals("alice", p.path("agent_id").asText());
        assertEquals("Bash", p.path("tool_name").asText());
        assertEquals("toolu-1", p.path("tool_use_id").asText());
        assertEquals("run ls", p.path("description").asText());
        assertEquals("ls", p.path("input").path("command").asText());
        assertTrue(p.path("permission_suggestions").isArray());
        assertFalse(p.has("requestId"), "permission_request 不得用 camelCase requestId");
    }

    @Test
    void isPermissionRequest_roundTrips() {
        TeammateMailbox.PermissionRequestMessage m = TeammateMailbox.createPermissionRequestMessage(
            "req-2", "alice", "Edit", "toolu-2", "edit file", Map.of("path", "/x"), null);
        TeammateMailbox.PermissionRequestMessage parsed =
            TeammateMailbox.isPermissionRequest(TeammateMailbox.toCompactJson(m));
        assertNotNull(parsed);
        assertEquals("req-2", parsed.requestId());
        assertEquals("Edit", parsed.toolName());
        assertTrue(parsed.permissionSuggestions().isEmpty(), "缺省 permission_suggestions 应为空数组");
        assertNull(TeammateMailbox.isPermissionRequest("not json"), "非 JSON 返回 null");
        assertNull(TeammateMailbox.isPermissionRequest("{\"type\":\"other\"}"), "非 permission_request 返回 null");
    }

    @Test
    void createPermissionResponseMessage_successAndErrorVariants() throws Exception {
        // WHY: CC PermissionResponseMessage (:468-483) 为成功/错误并集：success 带 response.updated_input/
        //      permission_updates；error 带 error 字符串。两变体 shape 不同，不得混淆。
        TeammateMailbox.PermissionResponseMessage ok = TeammateMailbox.createPermissionResponseMessage(
            "req-1", "success", null, Map.of("command", "ls"), List.of());
        JsonNode okNode = json.readTree(TeammateMailbox.toCompactJson(ok));
        assertEquals("success", okNode.path("subtype").asText());
        assertEquals("ls", okNode.path("response").path("updated_input").path("command").asText());
        assertFalse(okNode.has("error"), "success 变体不得含 error");

        TeammateMailbox.PermissionResponseMessage err = TeammateMailbox.createPermissionResponseMessage(
            "req-1", "error", "Permission denied", null, null);
        JsonNode errNode = json.readTree(TeammateMailbox.toCompactJson(err));
        assertEquals("error", errNode.path("subtype").asText());
        assertEquals("Permission denied", errNode.path("error").asText());
        assertFalse(errNode.has("response"), "error 变体不得含 response");

        // 缺省 error 文案对齐 CC :524 'Permission denied'
        TeammateMailbox.PermissionResponseMessage errDefault = TeammateMailbox.createPermissionResponseMessage(
            "req-1", "error", null, null, null);
        assertEquals("Permission denied", json.readTree(TeammateMailbox.toCompactJson(errDefault)).path("error").asText());
    }

    @Test
    void isPermissionResponse_roundTrips() {
        TeammateMailbox.PermissionResponseMessage ok = TeammateMailbox.createPermissionResponseMessage(
            "req-9", "success", null, Map.of("x", 1), null);
        TeammateMailbox.PermissionResponseMessage parsed =
            TeammateMailbox.isPermissionResponse(TeammateMailbox.toCompactJson(ok));
        assertNotNull(parsed);
        assertEquals("success", parsed.subtype());
        assertEquals(1, parsed.response().updatedInput().get("x"));
        assertNull(TeammateMailbox.isPermissionResponse("{\"type\":\"permission_request\"}"), "非 response 返回 null");
    }

    // ── P-1 / P-2 sandbox_permission_request / response（camelCase）─────────────

    @Test
    void createSandboxPermissionRequestMessage_usesCamelCase() throws Exception {
        // WHY: CC SandboxPermissionRequestMessage (:576-592) 用 camelCase（requestId/workerId/hostPattern/createdAt）。
        //      与 permission_request 的 snake_case 不同 —— 混用会导致 leader 侧解析失败。
        TeammateMailbox.SandboxPermissionRequestMessage m =
            TeammateMailbox.createSandboxPermissionRequestMessage("sb-1", "alice@team", "alice", "red", "example.com");
        JsonNode p = json.readTree(TeammateMailbox.toCompactJson(m));
        assertEquals("sandbox_permission_request", p.path("type").asText());
        assertEquals("sb-1", p.path("requestId").asText());
        assertEquals("alice@team", p.path("workerId").asText());
        assertEquals("alice", p.path("workerName").asText());
        assertEquals("example.com", p.path("hostPattern").path("host").asText());
        assertTrue(p.path("createdAt").asLong() > 0, "createdAt 应为毫秒时间戳");
    }

    @Test
    void sandboxPermissionRoundTrips() {
        TeammateMailbox.SandboxPermissionRequestMessage req =
            TeammateMailbox.createSandboxPermissionRequestMessage("sb-2", "w", "alice", null, "h");
        TeammateMailbox.SandboxPermissionRequestMessage parsedReq =
            TeammateMailbox.isSandboxPermissionRequest(TeammateMailbox.toCompactJson(req));
        assertNotNull(parsedReq);
        assertEquals("h", parsedReq.hostPattern().host());
        assertNull(TeammateMailbox.isSandboxPermissionRequest("{}"), "非 sandbox_permission_request 返回 null");

        TeammateMailbox.SandboxPermissionResponseMessage resp =
            TeammateMailbox.createSandboxPermissionResponseMessage("sb-2", "h", true);
        TeammateMailbox.SandboxPermissionResponseMessage parsedResp =
            TeammateMailbox.isSandboxPermissionResponse(TeammateMailbox.toCompactJson(resp));
        assertNotNull(parsedResp);
        assertTrue(parsedResp.allow());
        assertEquals("h", parsedResp.host());
    }

    // ── P-3 / P-4 plan_approval_request / response ────────────────────────────

    @Test
    void planApprovalRoundTrips() {
        String req = "{\"type\":\"plan_approval_request\",\"from\":\"alice\",\"timestamp\":\"t\","
            + "\"planFilePath\":\"/p/plan.md\",\"planContent\":\"content\",\"requestId\":\"p-1\"}";
        TeammateMailbox.PlanApprovalRequestMessage parsedReq = TeammateMailbox.isPlanApprovalRequest(req);
        assertNotNull(parsedReq);
        assertEquals("/p/plan.md", parsedReq.planFilePath());
        assertEquals("p-1", parsedReq.requestId());
        assertNull(TeammateMailbox.isPlanApprovalRequest("{}"), "非 plan_approval_request 返回 null");

        String resp = "{\"type\":\"plan_approval_response\",\"requestId\":\"p-1\",\"approved\":true,"
            + "\"timestamp\":\"t\",\"permissionMode\":\"default\"}";
        TeammateMailbox.PlanApprovalResponseMessage parsedResp = TeammateMailbox.isPlanApprovalResponse(resp);
        assertNotNull(parsedResp);
        assertTrue(parsedResp.approved());
        assertEquals("default", parsedResp.permissionMode());
        assertNull(TeammateMailbox.isPlanApprovalResponse("{}"), "非 plan_approval_response 返回 null");
    }

    // ── P-5 shutdown_rejected（+ request/approved）─────────────────────────────

    @Test
    void shutdownRejected_roundTrips() throws Exception {
        // WHY: P-5 shutdown_rejected 是 teammate 拒绝 leader shutdown 请求的协议消息
        //      （teammateMailbox.ts:755-767），reason 必填。缺 reason 则 leader 无法感知拒绝原因。
        TeammateMailbox.ShutdownRejectedMessage m =
            TeammateMailbox.createShutdownRejectedMessage("s-1", "alice", "still working");
        JsonNode p = json.readTree(TeammateMailbox.toCompactJson(m));
        assertEquals("shutdown_rejected", p.path("type").asText());
        assertEquals("s-1", p.path("requestId").asText());
        assertEquals("still working", p.path("reason").asText());

        TeammateMailbox.ShutdownRejectedMessage parsed =
            TeammateMailbox.isShutdownRejected(TeammateMailbox.toCompactJson(m));
        assertNotNull(parsed);
        assertEquals("still working", parsed.reason());
        assertNull(TeammateMailbox.isShutdownRejected("{}"), "非 shutdown_rejected 返回 null");
    }

    @Test
    void shutdownRequestAndApproved_roundTrips() {
        TeammateMailbox.ShutdownRequestMessage req =
            TeammateMailbox.createShutdownRequestMessage("s-2", "team-lead", "wrap up");
        TeammateMailbox.ShutdownRequestMessage parsedReq =
            TeammateMailbox.isShutdownRequest(TeammateMailbox.toCompactJson(req));
        assertNotNull(parsedReq);
        assertEquals("wrap up", parsedReq.reason());

        TeammateMailbox.ShutdownApprovedMessage app =
            TeammateMailbox.createShutdownApprovedMessage("s-2", "alice", "pane-1", "tmux");
        TeammateMailbox.ShutdownApprovedMessage parsedApp =
            TeammateMailbox.isShutdownApproved(TeammateMailbox.toCompactJson(app));
        assertNotNull(parsedApp);
        assertEquals("pane-1", parsedApp.paneId());
    }

    // ── P-6 mode_set_request ─────────────────────────────────────────────────

    @Test
    void modeSetRequest_roundTrips() throws Exception {
        TeammateMailbox.ModeSetRequestMessage m = TeammateMailbox.createModeSetRequestMessage("acceptEdits", "team-lead");
        JsonNode p = json.readTree(TeammateMailbox.toCompactJson(m));
        assertEquals("mode_set_request", p.path("type").asText());
        assertEquals("acceptEdits", p.path("mode").asText());

        TeammateMailbox.ModeSetRequestMessage parsed =
            TeammateMailbox.isModeSetRequest(TeammateMailbox.toCompactJson(m));
        assertNotNull(parsed);
        assertEquals("acceptEdits", parsed.mode());
        assertNull(TeammateMailbox.isModeSetRequest("{}"), "非 mode_set_request 返回 null");
    }

    // ── P-8 idle_notification（含 completedTaskId）─────────────────────────────

    @Test
    void idleNotification_includesCompletedTaskId() throws Exception {
        // WHY: CC IdleNotificationMessage (:394-405) 含 completedTaskId/completedStatus/failureReason，
        //      使 leader 能关联"哪个任务已完成"。缺 completedTaskId 则 leader 无法关联任务。
        TeammateMailbox.IdleNotificationMessage m = TeammateMailbox.createIdleNotification(
            "alice", "available", "dm summary", "task-42", "resolved", null);
        JsonNode p = json.readTree(TeammateMailbox.toCompactJson(m));
        assertEquals("idle_notification", p.path("type").asText());
        assertEquals("alice", p.path("from").asText());
        assertEquals("task-42", p.path("completedTaskId").asText());
        assertEquals("resolved", p.path("completedStatus").asText());
        assertFalse(p.has("failureReason"), "null 可选字段应省略键");

        TeammateMailbox.IdleNotificationMessage parsed =
            TeammateMailbox.isIdleNotification(TeammateMailbox.toCompactJson(m));
        assertNotNull(parsed);
        assertEquals("task-42", parsed.completedTaskId());
        assertNull(TeammateMailbox.isIdleNotification("{\"type\":\"other\"}"), "非 idle_notification 返回 null");
    }

    // ── P-9 isStructuredProtocolMessage ──────────────────────────────────────

    @Test
    void isStructuredProtocolMessage_coversExactlyTenTypes() {
        // WHY: CC isStructuredProtocolMessage (:1073-1095) 精确 10 种 type 路由到 useInboxPoller 的
        //      专用 handler；漏判 → 结构化消息被 attachments 吞成原始上下文；多判 → 误路由。
        List<String> included = List.of(
            "permission_request", "permission_response", "sandbox_permission_request",
            "sandbox_permission_response", "shutdown_request", "shutdown_approved",
            "team_permission_update", "mode_set_request", "plan_approval_request",
            "plan_approval_response");
        for (String type : included) {
            assertTrue(TeammateMailbox.isStructuredProtocolMessage("{\"type\":\"" + type + "\"}"),
                type + " 应为结构化协议消息");
        }
        // CC 精确排除这 3 种（grep 自验 :1080-1091 不含）
        assertFalse(TeammateMailbox.isStructuredProtocolMessage("{\"type\":\"shutdown_rejected\"}"),
            "shutdown_rejected 不在 CC 10 种内");
        assertFalse(TeammateMailbox.isStructuredProtocolMessage("{\"type\":\"idle_notification\"}"),
            "idle_notification 不在 CC 10 种内");
        assertFalse(TeammateMailbox.isStructuredProtocolMessage("{\"type\":\"task_assignment\"}"),
            "task_assignment 不在 CC 10 种内");
        assertFalse(TeammateMailbox.isStructuredProtocolMessage("not json"), "非 JSON 返回 false");
        assertFalse(TeammateMailbox.isStructuredProtocolMessage("{\"x\":1}"), "无 type 返回 false");
    }

    // ── team_permission_update ────────────────────────────────────────────────

    @Test
    void teamPermissionUpdate_roundTrips() {
        String msg = "{\"type\":\"team_permission_update\","
            + "\"permissionUpdate\":{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Read\",\"ruleContent\":\"x\"}],"
            + "\"behavior\":\"allow\",\"destination\":\"session\"},"
            + "\"directoryPath\":\"/d\",\"toolName\":\"Read\"}";
        TeammateMailbox.TeamPermissionUpdateMessage parsed = TeammateMailbox.isTeamPermissionUpdate(msg);
        assertNotNull(parsed);
        assertEquals("allow", parsed.permissionUpdate().behavior());
        assertEquals("Read", parsed.permissionUpdate().rules().get(0).toolName());
        assertEquals("/d", parsed.directoryPath());
        assertNull(TeammateMailbox.isTeamPermissionUpdate("{}"), "非 team_permission_update 返回 null");
    }

    // ── 六类 schema-validated 消息：type 正确但缺必填字段 → null（对齐 CC safeParse）────

    @Test
    void schemaValidatedMessages_rejectMissingRequiredFields() {
        // WHY: CC 这六类消息经 zod schema.safeParse 校验（teammateMailbox.ts:684-693/702-711/720-728/
        //      737-746/755-763/1019-1025）。safeParse 与"仅判 type"语义不同：type 对但缺必填字段
        //      （from/timestamp/requestId/planFilePath/planContent/approved/mode 等）→ 校验失败 → null。
        //      若 Java 仅判 type，缺字段时返回残缺 record，消费侧（useInboxPoller）读到 null 字段
        //      仍当作有效消息路由，导致 NPE / 误路由。
        assertNull(TeammateMailbox.isPlanApprovalRequest(
            "{\"type\":\"plan_approval_request\",\"from\":\"alice\"}"),
            "缺 timestamp/planFilePath/planContent/requestId → null");
        assertNull(TeammateMailbox.isPlanApprovalResponse(
            "{\"type\":\"plan_approval_response\",\"requestId\":\"p-1\"}"),
            "缺 approved/timestamp → null");
        assertNull(TeammateMailbox.isShutdownRequest(
            "{\"type\":\"shutdown_request\",\"requestId\":\"s-1\"}"),
            "缺 from/timestamp → null");
        assertNull(TeammateMailbox.isShutdownApproved(
            "{\"type\":\"shutdown_approved\",\"requestId\":\"s-1\"}"),
            "缺 from/timestamp → null");
        assertNull(TeammateMailbox.isShutdownRejected(
            "{\"type\":\"shutdown_rejected\",\"requestId\":\"s-1\",\"from\":\"alice\"}"),
            "缺 reason/timestamp → null");
        assertNull(TeammateMailbox.isModeSetRequest(
            "{\"type\":\"mode_set_request\",\"from\":\"team-lead\"}"),
            "缺 mode → null");
    }

    @Test
    void modeSetRequest_validatesLegalModeEnum() {
        // WHY: CC ModeSetRequestMessageSchema（:1022）mode 为 PermissionModeSchema() 枚举
        //      （coreSchemas.ts:337-348 五值）。非法 mode（如 'admin'）→ safeParse 失败 → null。
        //      若 Java 不校验枚举，非法 mode 静默透传，leader 下发的 mode 设置请求失效却无感知。
        assertNull(TeammateMailbox.isModeSetRequest(
            "{\"type\":\"mode_set_request\",\"mode\":\"admin\",\"from\":\"team-lead\"}"),
            "非法 mode 枚举 → null");
        // 五值全合法
        for (String mode : List.of("default", "acceptEdits", "bypassPermissions", "plan", "dontAsk")) {
            assertNotNull(TeammateMailbox.isModeSetRequest(
                "{\"type\":\"mode_set_request\",\"mode\":\"" + mode + "\",\"from\":\"team-lead\"}"),
                mode + " 应为合法 mode");
        }
    }

    @Test
    void planApprovalResponse_validatesOptionalPermissionMode() {
        // WHY: CC PlanApprovalResponseMessageSchema（:709）permissionMode 为
        //      PermissionModeSchema().optional()：缺省 → 合法；存在但非法 → safeParse 失败 → null。
        assertNull(TeammateMailbox.isPlanApprovalResponse(
            "{\"type\":\"plan_approval_response\",\"requestId\":\"p-1\",\"approved\":true,"
            + "\"timestamp\":\"t\",\"permissionMode\":\"invalid\"}"),
            "非法 permissionMode → null");
        // 缺省 permissionMode → 仍合法（optional）
        TeammateMailbox.PlanApprovalResponseMessage ok = TeammateMailbox.isPlanApprovalResponse(
            "{\"type\":\"plan_approval_response\",\"requestId\":\"p-1\",\"approved\":false,\"timestamp\":\"t\"}");
        assertNotNull(ok);
        assertFalse(ok.approved());
        assertNull(ok.permissionMode());
    }

    @Test
    void permissionResponsePayload_omitsNullKeys() throws Exception {
        // WHY: CC PermissionResponseMessage success 变体 response.updated_input/permission_updates
        //      为可选（teammateMailbox.ts:473-477），JSON.stringify 省略 undefined 键。
        //      若 Java 不省略 null，磁盘写出 "updated_input":null，CC 消费侧读到的形状不同。
        TeammateMailbox.PermissionResponseMessage m = TeammateMailbox.createPermissionResponseMessage(
            "req-1", "success", null, null, null);
        JsonNode response = json.readTree(TeammateMailbox.toCompactJson(m)).path("response");
        assertTrue(response.isObject(), "response 应为对象（即使负载全空）");
        assertFalse(response.has("updated_input"), "null updated_input 应省略键");
        assertFalse(response.has("permission_updates"), "null permission_updates 应省略键");
    }
}
