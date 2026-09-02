package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.GlobTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-09] R26 权限管线语义等价测试 · 对齐 CC {@code hasPermissionsToUseTool}
 * (Open-ClaudeCode/src/utils/permissions/permissions.ts:473-517).
 *
 * <p><b>WHY（OD-SS-01 硬前置）</b>: 删除 R26 6 个内置 PreToolUse Hook（1a/1b/1c-1e/1f/
 * 1g/2a-2b 层 hook 形态）前，必须证明 PermissionPipeline + ToolPermissionGate 单链
 * （gate.check 6 参 forceDecision=null）覆盖 6 hook 的全部可观测语义。本测试驱动
 * <b>删除后的单链</b>（不注册任何 hook），断言与 6 hook 参与时一致的决策。
 *
 * <p><b>RED→GREEN 设计</b>: 当前单链存在 3 个语义缺口（均由 6 hook 承载）：
 * <ol>
 *   <li><b>DONT_ASK ask→deny 变换缺失</b>（CC permissions.ts:503-517）——旧 R26 hook 层
 *       DONT_ASK 分支承载；单链无此变换 → dontAsk_noAllowRule_denyWithModeReason FAIL</li>
 *   <li><b>content allow 缺失</b>（CC 2b 无 content allow；allow 在工具 checkPermissions 内，
 *       filesystem.ts:1160-1176 / bashPermissions.ts:1124-1139）——旧 R26 hook 层 2b'
 *       承载 → dontAsk_readTranscript_contentAllow_allow / dontAsk_bashContentAllowRule_allow FAIL</li>
 *   <li><b>文件工具 checkPermissions 缺 CC 委托</b>（GlobTool/GrepTool 应委托
 *       checkReadPermissionForTool，CC GlobTool.ts:135-140）——旧 6 hook 的 1g/2b' 由
 *       CheckLayer 承载但 1c 默认 Allow 短路 → dontAsk_noAllowRule_denyWithModeReason FAIL</li>
 * </ol>
 *
 * <p>prompter 桩 fail-closed（恒 Deny）——任何"应当被 DONT_ASK 变换拦截"的 Ask 落到弹窗
 * 会返回 Deny；断言以<b>决策 reason</b> 区分"弹窗降级 Deny"与"DONT_ASK 变换 Deny"
 * （后者 reason = {@code Mode(DONT_ASK)}，CC permissions.ts:509-516）。
 */
@DisplayName("[IMPL-09] R26 权限管线语义等价（删除 6 hook 后单链）")
class R26PermissionPipelineEquivalenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** fail-closed prompter 桩 · 任何漏网的 Ask 都降级 Deny（不弹窗）。 */
    private static final class FailClosedPrompter implements PermissionPrompter {
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input,
                                       PermissionDecisionReason reason,
                                       ToolUseContext ctx, String requestId) {
            return new PermissionResult.Deny(
                "fail-closed stub denied",
                new PermissionDecisionReason.Other("fail-closed"),
                requestId);
        }
    }

    private final ToolPermissionGate gate = new ToolPermissionGate(
        new PermissionPipeline(),
        new FailClosedPrompter(), null, null, null);

    // ════════════════════════════════════════════════════════════════════
    // permCtx 构造（复用 ExecAgentHook 工厂，保证与生产同源）
    // ════════════════════════════════════════════════════════════════════

    /** DONT_ASK + 父规则(SESSION Bash whole-tool) ∪ Read(/transcriptPath) · 对齐 execAgentHook.ts:141-153. */
    private ToolPermissionContext hookPermCtx() {
        Map<PermissionRuleSource, Set<PermissionRule>> parentAllow =
            new EnumMap<>(PermissionRuleSource.class);
        parentAllow.put(PermissionRuleSource.SESSION,
            Set.of(new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Bash"))));
        ToolPermissionContext parent = ToolPermissionContext.of(
            PermissionMode.DEFAULT, parentAllow, Map.of(), Map.of(), Map.of());
        return ExecAgentHook.buildHookPermissionContext(parent, "sessions/sess-1/transcript.jsonl");
    }

    /** DONT_ASK + SESSION 内容 allow 规则（Bash(git status:*)）· 无 whole-tool 规则。 */
    private ToolPermissionContext bashContentAllowCtx() {
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        allow.put(PermissionRuleSource.SESSION,
            Set.of(new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Bash", "git status:*"))));
        return ToolPermissionContext.of(
            PermissionMode.DONT_ASK, allow, Map.of(), Map.of(), Map.of());
    }

    /** DONT_ASK + 空规则集。 */
    private ToolPermissionContext emptyDontAskCtx() {
        return ToolPermissionContext.of(
            PermissionMode.DONT_ASK, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private ToolUseContext ctx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode());
    }

    private ToolPermissionGate.DecisionResult check(Tool tool, String toolName,
                                                    JsonNode input, ToolPermissionContext permCtx) {
        return gate.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), toolName, input),
            input, ctx(permCtx), permCtx, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1c content allow（收窄 2b' 到工具 checkPermissions 路径 · OD-SS-02）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DONT_ASK + Read(/transcriptPath) content allow → ALLOW（工具 checkPermissions 路径）")
    void dontAsk_readTranscript_contentAllow_allow(@TempDir Path workspace) {
        ToolPermissionContext permCtx = hookPermCtx();
        ReadFileTool tool = new ReadFileTool(
            new PathGuard(workspace), null, new ReadPermissionChecker(new WritePermissionChecker()));
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", "/sessions/sess-1/transcript.jsonl");

        ToolPermissionGate.DecisionResult r = check(tool, "read_file", input, permCtx);

        assertThat(r.decision())
            .as("CC checkReadPermissionForTool step 8 (filesystem.ts:1160-1176): allow rule 命中 → allow")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(r.result())
            .as("allow 决策 reason 必须携带命中规则（CC filesystem.ts:1172-1174）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class,
                allow -> assertThat(allow.reason()).isInstanceOf(PermissionDecisionReason.Rule.class));
    }

    @Test
    @DisplayName("DONT_ASK + Bash 内容 allow 规则 → ALLOW（bashPermissions.ts:1124-1139）")
    void dontAsk_bashContentAllowRule_allow() {
        ToolPermissionContext permCtx = bashContentAllowCtx();
        BashTool tool = new BashTool();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        ToolPermissionGate.DecisionResult r = check(tool, "Bash", input, permCtx);

        assertThat(r.decision())
            .as("CC bashToolCheckPermission step 5 (bashPermissions.ts:1129-1139): matchingAllowRules → allow")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    // ════════════════════════════════════════════════════════════════════
    // DONT_ASK ask→deny 变换（CC permissions.ts:503-517，收自 R26 hook 层）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DONT_ASK + 无任何 allow 规则 → DENY（reason=Mode(DONT_ASK)，CC :509-516）")
    void dontAsk_noAllowRule_denyWithModeReason(@TempDir Path workspace) {
        ToolPermissionContext permCtx = emptyDontAskCtx();
        GlobTool tool = new GlobTool(new PathGuard(workspace));
        tool.setPermissionChecker(new ReadPermissionChecker(new WritePermissionChecker()));
        ObjectNode input = JSON.createObjectNode();
        input.put("pattern", "**/*.java");

        ToolPermissionGate.DecisionResult r = check(tool, "glob", input, permCtx);

        assertThat(r.decision())
            .as("CC dontAsk transform: ask → deny (permissions.ts:505-517)")
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(r.result())
            .as("变换 deny 的 reason 必须是 Mode(DONT_ASK)，与弹窗降级 deny（Other）区分")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.reason())
                    .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.DONT_ASK)));
        assertThat(r.result())
            .as("变换 deny 的 message 对齐 CC DONT_ASK_REJECT_MESSAGE 全文 (messages.ts:237-249)")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.message())
                    .contains("don't ask mode")
                    .contains("IMPORTANT: You *may* attempt to accomplish this action")
                    .contains("Let the user decide how to proceed."));
    }

    // ════════════════════════════════════════════════════════════════════
    // 既有管线层语义（GREEN 基线 · 证明单链覆盖 2a/2b/1c-1g）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DONT_ASK + 父 whole-tool Bash allow → ALLOW（2b，父规则继承）")
    void dontAsk_parentWholeToolBashAllow_allow() {
        ToolPermissionContext permCtx = hookPermCtx();
        BashTool tool = new BashTool();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        ToolPermissionGate.DecisionResult r = check(tool, "Bash", input, permCtx);

        assertThat(r.decision())
            .as("CC 2b (permissions.ts:1284-1297): whole-tool allow rule → allow")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }
    @Test
    @DisplayName("DONT_ASK + 危险命令 + whole-tool allow → ALLOW（[S09] 工具 ask 可被 2b 覆盖，CC 语义）")
    void dontAsk_dangerousBash_wholeToolAllowCoversAsk() {
        ToolPermissionContext permCtx = hookPermCtx();
        BashTool tool = new BashTool();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "rm -rf /");

        ToolPermissionGate.DecisionResult r = check(tool, "Bash", input, permCtx);

        // [S09 语义更新] S09 前 BashTool.checkPermissions 对危险命令直接 Deny → 1d 拦截
        // （本测试旧断言 DENY）。S09 按 CC bashSecurity 对齐为 ask + misparsing 标志
        // （bashPermissions.ts checkCommandAndSuggestRules :1217-1239）后：
        //   工具 ask → 1e（requiresUserInteraction=false 不拦）→ 1f/1g（无内容规则/敏感路径
        //   不拦）→ 2b whole-tool allow → ALLOW。CC hasPermissionsToUseToolInner 同序
        //   （permissions.ts:1214-1228 1c/1d → 2b toolAlwaysAllowedRule），whole-tool allow
        //   覆盖工具 ask 是 CC 行为（用户显式整工具放行）。
        assertThat(r.decision())
            .as("CC 1c ask → 2b whole-tool allow 覆盖（S09 对齐后语义）")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    @Test
    @DisplayName("DEFAULT + 无规则 Bash → 兜底 Ask → prompter 降级 Deny（fail-closed 桩语义）")
    void defaultMode_noRules_fallsBackToAskThenFailClosed() {
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        BashTool tool = new BashTool();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        ToolPermissionGate.DecisionResult r = check(tool, "Bash", input, permCtx);

        assertThat(r.decision())
            .as("10 层第 3 层兜底 Ask → fail-closed 桩降级 Deny（CC :1299-1310 passthrough→ask）")
            .isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    @Test
    @DisplayName("forceDecision 短路管线：DONT_ASK + forceDecision=Allow → ALLOW（CC useCanUseTool.tsx:37）")
    void forceDecisionBypassesDontAskTransform() {
        ToolPermissionContext permCtx = emptyDontAskCtx();
        GlobTool tool = new GlobTool(new PathGuard(Path.of(".")));
        tool.setPermissionChecker(new ReadPermissionChecker(new WritePermissionChecker()));
        ObjectNode input = JSON.createObjectNode();
        input.put("pattern", "**/*.java");
        ToolUseContext c = ctx(permCtx);
        PermissionResult forceAllow = new PermissionResult.Allow(
            input, new PermissionDecisionReason.Other("hook ask"), null, false, null, List.of());

        ToolPermissionGate.DecisionResult r = gate.check(tool,
            new ToolUseBlock("force-1", "glob", input), input, c, permCtx, forceAllow);

        assertThat(r.decision())
            .as("CC useCanUseTool.tsx:37 forceDecision 直接作为决策，跳过管线与 dontAsk 变换")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }
}
