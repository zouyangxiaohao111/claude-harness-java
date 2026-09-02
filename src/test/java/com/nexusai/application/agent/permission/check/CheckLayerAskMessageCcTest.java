package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [F4a] ask 弹窗消息统一收敛到 {@link PermissionMessageGenerator} · 对齐 CC
 * {@code createPermissionRequestMessage}（Open-ClaudeCode/src/utils/permissions/permissions.ts:137-211）。
 *
 * <p><b>WHY</b>：原 CheckLayer1b/3 内联 {@code String.format("Permission to use tool '%s'
 * requires approval ...")} 是简化文案，与 CC 11 分支 per-reason switch 不一致。本测试
 * 钉死"ask 消息经生成器生成、逐 reason 对齐 CC 文案"，防止回归回简化文案。
 */
@DisplayName("[F4a] ask 消息经 PermissionMessageGenerator 生成对齐 CC 11 分支")
class CheckLayerAskMessageCcTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PermissionMessageGenerator generator = new PermissionMessageGenerator();

    /** 桩工具：默认 checkPermissions=Allow，name 可指定。 */
    private static final class DefaultAllowTool implements Tool {
        private final String name;

        DefaultAllowTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return null;
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode inputSchema() {
            return new ObjectMapper().createObjectNode();
        }
    }

    // ─────────────────────────── 1. 生成器 11 分支逐 reason 对齐 CC ───────────────────────────

    @Test
    @DisplayName("生成器 11 分支逐 reason 文案对齐 CC permissions.ts:137-211")
    void messageGenerator_allReasonTypes_alignCc() {
        // classifier（CC :143-148）— classifier 字段承载 'auto-mode'（CC permissions.ts:907 构造侧）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Classifier("auto-mode", "some reason")))
            .isEqualTo("Classifier 'auto-mode' requires approval for this Bash command: some reason");

        // hook 带 reason（CC :150-152 blocked 分支）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Hook("PreToolUse:Bash", "user", "blocked reason")))
            .isEqualTo("Hook 'PreToolUse:Bash' blocked this action: blocked reason");

        // hook 无 reason（CC :153-154 requires approval 分支）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Hook("PreToolUse:Bash", "user", null)))
            .isEqualTo("Hook 'PreToolUse:Bash' requires approval for this Bash command");

        // rule（CC :156-163）
        PermissionRule rule = new PermissionRule(PermissionRuleSource.USER_SETTINGS,
            PermissionBehavior.ASK, PermissionRuleValue.wholeTool("Bash"));
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Rule(rule)))
            .isEqualTo("Permission rule 'Bash' from user settings requires approval for this Bash command");

        // subcommandResults 有需批准子命令（CC :165-183，单元素 n=1 复数路径）
        Map<String, PermissionResult> subOne = Map.of(
            "git status", new PermissionResult.Passthrough("m", null, null, null, null));
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.SubcommandResults(subOne)))
            .isEqualTo("This Bash command contains multiple operations. The following part requires approval: git status");

        // subcommandResults 无 ask/passthrough（CC :184-186 兜底句）
        Map<String, PermissionResult> subNone = Map.of(
            "git status", new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("x"), null, false, null, List.of()));
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.SubcommandResults(subNone)))
            .isEqualTo("This Bash command contains multiple operations that require approval");

        // permissionPromptTool（CC :187-188）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.PermissionPromptTool("mcp__tool", null)))
            .isEqualTo("Tool 'mcp__tool' requires approval for this Bash command");

        // sandboxOverride（CC :189-190）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.SandboxOverride(
                PermissionDecisionReason.SandboxOverride.SandboxOverrideReason.EXCLUDED_COMMAND)))
            .isEqualTo("Run outside of the sandbox");

        // workingDir（CC :191-192 → reason 原文）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.WorkingDir("/outside")))
            .isEqualTo("/outside");

        // safetyCheck（CC :193-194 → reason 原文）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.SafetyCheck("dangerous path", true)))
            .isEqualTo("dangerous path");

        // other（CC :193-194 → reason 原文）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Other("default fallback (no rule matched)")))
            .isEqualTo("default fallback (no rule matched)");

        // mode（CC :195-198）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.Mode(PermissionMode.AUTO)))
            .isEqualTo("Current permission mode (Auto mode) requires approval for this Bash command");

        // asyncAgent（CC :199-200 → reason 原文）
        assertThat(generator.createPermissionRequestMessage("Bash",
            new PermissionDecisionReason.AsyncAgent("agent reason")))
            .isEqualTo("agent reason");

        // 默认句（decisionReason=null，CC :204-207）
        assertThat(generator.createPermissionRequestMessage("Bash", null))
            .isEqualTo("Claude requested permissions to use Bash, but you haven't granted it yet.");
    }

    // ─────────────────────────── 2. 1b 层 ask 消息对齐 CC rule 分支 ───────────────────────────

    private ToolPermissionContext askRuleCtx(String toolName) {
        Map<PermissionRuleSource, Set<PermissionRule>> ask =
            new EnumMap<>(PermissionRuleSource.class);
        ask.put(PermissionRuleSource.USER_SETTINGS,
            Set.of(new PermissionRule(PermissionRuleSource.USER_SETTINGS,
                PermissionBehavior.ASK, PermissionRuleValue.wholeTool(toolName))));
        return ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), ask, Map.of());
    }

    private ToolUseContext ctx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode());
    }

    @Test
    @DisplayName("1b 层 ask 消息 = CC 默认句（createPermissionRequestMessage 不传 reason，OPD-WF3-01-06）")
    void checkLayer1b_askMessage_alignsCcRuleBranch() {
        // [OPD-WF3-01-06] CC hasPermissionsToUseToolInner 1b（permissions.ts:1195-1203）：
        //   message = createPermissionRequestMessage(tool.name) —— <b>不传</b> decisionReason
        //   → 落通用默认句；decisionReason 字段仍为 Rule(askRule)（归因）。旧实现传 ruleReason
        //   走 rule 分支句（"Permission rule ... from ... requires approval"），偏离 CC 文案。
        CheckLayer1b_AskRule layer = new CheckLayer1b_AskRule();
        Tool tool = new DefaultAllowTool("Bash");
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = layer.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input),
            input, ctx(permCtx), permCtx);

        assertThat(r)
            .as("非 Bash 名不匹配 BASH_TOOL_NAME 但 sandboxManager=null → 无 fall-through → Ask")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) r).message())
            .as("对齐 CC permissions.ts:1202 createPermissionRequestMessage(tool.name) 默认句")
            .isEqualTo("Claude requested permissions to use Bash, but you haven't granted it yet.");
        assertThat(((PermissionResult.Ask) r).reason())
            .as("1b ask 归因仍为 Rule(askRule)（CC :1198-1200，message 与归因分离）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ─────────────────── 3. 3 层兜底 ask 消息 = passthrough 实际 reason 派生 ───────────────────

    @Test
    @DisplayName("3 层兜底 ask 无 reason → 通用默认句（对齐 CC permissions.ts:1299-1310 + :207-209）")
    void checkLayer3_fallbackMessage_noReason_defaultSentence() {
        ToolCheckCache.clear();
        CheckLayer3_PassthroughToAsk layer = new CheckLayer3_PassthroughToAsk();
        Tool tool = new DefaultAllowTool("Bash");
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = layer.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input),
            input, ctx(permCtx), permCtx);

        assertThat(r).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) r).message())
            .as("passthrough 无 reason → CC 通用默认句（createPermissionRequestMessage null 分支）")
            .isEqualTo("Claude requested permissions to use Bash, but you haven't granted it yet.");
        assertThat(((PermissionResult.Ask) r).reason())
            .as("无 reason 时 decisionReason 为 null（对齐 CC decisionReason 可选）")
            .isNull();
    }

    @Test
    @DisplayName("3 层兜底 ask 带 reason → 消息用 passthrough.reason 原文")
    void checkLayer3_fallbackMessage_withReason_usesPassthroughReason() {
        ToolCheckCache.clear();
        PermissionDecisionReason classifierReason = new PermissionDecisionReason.Classifier(
            "auto-mode", "dangerous command");
        ToolCheckCache.put("Bash", new PermissionResult.Passthrough(
            "tool passthrough", classifierReason, null, null, null));
        try {
            CheckLayer3_PassthroughToAsk layer = new CheckLayer3_PassthroughToAsk();
            Tool tool = new DefaultAllowTool("Bash");
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
            ObjectNode input = JSON.createObjectNode();
            input.put("command", "git status");

            PermissionResult r = layer.check(tool,
                new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input),
                input, ctx(permCtx), permCtx);

            assertThat(r).isInstanceOf(PermissionResult.Ask.class);
            assertThat(((PermissionResult.Ask) r).message())
                .as("带 reason → createPermissionRequestMessage classifier 分支原文")
                .isEqualTo("Classifier 'auto-mode' requires approval for this Bash command: dangerous command");
            assertThat(((PermissionResult.Ask) r).reason()).isEqualTo(classifierReason);
        } finally {
            ToolCheckCache.clear();
        }
    }

    // ─────────────── 4. getRuleBehaviorDescription 共享文案（DRY）对齐 CC ───────────────

    @Test
    @DisplayName("getRuleBehaviorDescription 三值文案对齐 CC PermissionResult.ts:24-35（HookRegistry 复用）")
    void getRuleBehaviorDescription_alignsCc() {
        assertThat(PermissionMessageGenerator.getRuleBehaviorDescription(PermissionBehavior.ALLOW))
            .isEqualTo("allowed");
        assertThat(PermissionMessageGenerator.getRuleBehaviorDescription(PermissionBehavior.DENY))
            .isEqualTo("denied");
        assertThat(PermissionMessageGenerator.getRuleBehaviorDescription(PermissionBehavior.ASK))
            .isEqualTo("asked for confirmation for");
    }
}
