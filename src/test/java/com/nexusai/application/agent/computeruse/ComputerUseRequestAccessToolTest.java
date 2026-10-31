package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mcp__computer-use__request_access} 单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：这个工具是 computer-use 的<b>唯一审批入口</b>。
 * 三条语义必须锁死：
 * <ul>
 *   <li><b>幂等</b>：已授权的应用再次请求 ⇒ <b>不弹窗</b>（否则模型每次都骚扰用户）。</li>
 *   <li><b>策略优先</b>：策略黑名单的应用<b>不进弹窗</b>、<b>拿不到授权</b> ——
 *       用户批准也不能放行（CCB 的「无逃生口」）。</li>
 *   <li><b>返回结构</b>：模型要靠返回 JSON 判断"拿到了哪些授权、哪些被拒"，
 *       字段缺失 ⇒ 模型会反复重试。</li>
 * </ul>
 */
class ComputerUseRequestAccessToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    @TempDir
    Path tempDir;

    private SessionMapper sessionMapper;
    private SessionRecord sessionRecord;
    private ComputerUseGrantStore grantStore;
    private SQLiteDataSource ds;
    private ComputerUseLockService lockService;

    @BeforeEach
    void setUp() throws Exception {
        sessionMapper = mock(SessionMapper.class);
        sessionRecord = new SessionRecord();
        sessionRecord.setId(SESSION);
        sessionRecord.setComputerUseGrants(null);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(sessionRecord);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);

        grantStore = new ComputerUseGrantStore(sessionMapper);

        ds = ComputerUseTestSqlite.create(tempDir, "cu-request-access.db");
        ComputerUseTestSqlite.createLockTable(ds);
        lockService = new ComputerUseLockService(ds, true);
    }

    private ComputerUseRequestAccessTool newTool() {
        return new ComputerUseRequestAccessTool(grantStore, lockService);
    }

    private static ObjectNode input(String... apps) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode arr = node.putArray("apps");
        for (String a : apps) {
            arr.add(a);
        }
        node.put("reason", "给用户看的一句话理由");
        return node;
    }

    private ToolUseContext ctx(UUID agentId) {
        return ToolUseContext.of(agentId, SESSION);
    }

    @Test
    @DisplayName("已授权的应用再次请求 ⇒ Allow（不弹窗，幂等跳过）")
    void alreadyGrantedAppDoesNotPromptAgain() {
        // 会话已有 chrome 授权
        sessionRecord.setComputerUseGrants(
                new ComputerUseGrants(List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "full")),
                        ComputerUseGrantFlags.DEFAULT).toJson());

        PermissionResult result = newTool().checkPermissions(input("Chrome"), ctx(null));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("有新应用 ⇒ Passthrough（⇒ 3 层转 Ask ⇒ 弹审批气泡），消息含应用名与理由")
    void newAppFallsThroughToAsk() {
        PermissionResult result = newTool().checkPermissions(input("Notepad"), ctx(null));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        PermissionResult.Passthrough pass = (PermissionResult.Passthrough) result;
        assertThat(pass.message()).contains("Notepad").contains("给用户看的一句话理由");
    }

    @Test
    @DisplayName("策略黑名单优先：策略拒绝的应用不进弹窗（无逃生口）")
    void policyDeniedAppNeverPrompts() {
        PermissionResult result = newTool().checkPermissions(input("Netflix"), ctx(null));

        // 没有任何可问的应用 ⇒ 直接 Allow，不弹窗
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("执行后返回 CCB 形状的 JSON：granted / granted_count / denied_count / screenshotFiltering")
    void executeReturnsCcbShapedJson() {
        ComputerUseRequestAccessTool tool = newTool();
        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), input("Notepad"));

        var result = tool.execute(call, ctx(null));

        JsonNode data = ((ToolResult<?>) result).data() instanceof JsonNode node ? node : null;
        assertThat(data).isNotNull();
        assertThat(data.has("granted")).isTrue();
        assertThat(data.has("denied")).isTrue();
        assertThat(data.path("granted_count").asInt()).isEqualTo(1);
        assertThat(data.path("denied_count").asInt()).isEqualTo(0);
        assertThat(data.path("screenshotFiltering").asText())
                .as("[Phase 1b · B] 本仓不做白名单式截图过滤 ⇒ 报 \"none\"（CCB-Windows 同款）；"
                        + "\"native\" 属于对模型说谎")
                .isEqualTo("none");
        assertThat(data.path("granted").get(0).path("appId").asText()).isEqualTo("notepad");
    }

    @Test
    @DisplayName("策略拒绝的应用出现在返回 JSON 的 policyDenied 里，且拿不到授权")
    void policyDeniedAppearsInResult() {
        ComputerUseRequestAccessTool tool = newTool();
        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), input("Netflix", "Notepad"));

        var result = tool.execute(call, ctx(null));
        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();

        assertThat(data.has("policyDenied")).isTrue();
        assertThat(data.path("policyDenied").path("apps").get(0).path("requestedName").asText())
                .isEqualTo("Netflix");
        assertThat(data.path("policyDenied").path("guidance").asText()).contains("blocked by policy");
        // 只有 Notepad 拿到授权
        assertThat(data.path("granted").size()).isEqualTo(1);
        assertThat(data.path("granted").get(0).path("appId").asText()).isEqualTo("notepad");
    }

    @Test
    @DisplayName("解析后的应用列表注塑进入参 resolvedApps（前端 Phase 1b 读它）")
    void resolvedAppsAreInjectedIntoInput() {
        ObjectNode in = input("Chrome", "Netflix");
        newTool().checkPermissions(in, ctx(null));

        JsonNode resolved = in.get(ComputerUseRequestAccessTool.RESOLVED_APPS_KEY);
        assertThat(resolved).isNotNull();
        assertThat(resolved.size()).isEqualTo(2);
        assertThat(resolved.get(0).path("appId").asText()).isEqualTo("chrome");
        assertThat(resolved.get(0).path("tier").asText()).isEqualTo("read");
        assertThat(resolved.get(0).path("alreadyGranted").asBoolean()).isFalse();
        assertThat(resolved.get(1).path("policyDenied").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("锁被别的会话持有 ⇒ 直接 Deny（连弹窗都不弹）")
    void blockedByOtherSessionDeniesWithoutPrompt() {
        assertThat(lockService.tryAcquire("sess-other")).isTrue();

        PermissionResult result = newTool().checkPermissions(input("Notepad"), ctx(null));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).message()).contains("Another session");
    }

    @Test
    @DisplayName("授权位随整组批准（与 CCB 一致：CCB CLI 弹窗即整组授予，逐位勾选是 CCB 自己的 follow-up）")
    void flagsAreGrantedWithTheWholeSet() {
        ObjectNode in = input("Notepad");
        in.put("clipboardRead", true);
        ComputerUseRequestAccessTool tool = newTool();

        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), in), ctx(null));
        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();

        assertThat(data.path("granted_count").asInt()).isEqualTo(1);
        // 落库后授权位为 true
        assertThat(grantStore.read(SESSION).flags().clipboardRead()).isTrue();
    }

    @Test
    @DisplayName("子代理执行授权申请时不抢锁（主线程独占锁生命周期）")
    void subagentDoesNotAcquireLock() {
        ComputerUseRequestAccessTool tool = newTool();
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input("Notepad")),
                ctx(UUID.randomUUID()));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("granted_count").asInt()).isEqualTo(1);
        // 锁仍空闲（子代理没拿）
        assertThat(lockService.check(SESSION).free()).isTrue();
    }

    @Test
    @DisplayName("工具名与 schema 对齐 CCB（apps + reason 必填，三个授权位可选布尔）")
    void toolNameAndSchemaMatchCcb() {
        ComputerUseRequestAccessTool tool = newTool();
        assertThat(tool.name()).isEqualTo("mcp__computer-use__request_access");
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("required").toString()).contains("apps").contains("reason");
        assertThat(schema.path("properties").has("clipboardRead")).isTrue();
        assertThat(schema.path("properties").has("clipboardWrite")).isTrue();
        assertThat(schema.path("properties").has("systemKeyCombos")).isTrue();
        // ⛔ 不声明后端注入字段（模型不得自称授权结果）
        assertThat(schema.path("properties").has(ComputerUseRequestAccessTool.RESOLVED_APPS_KEY)).isFalse();
    }
}
