package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02] 1b 层 CC 语义测试：fall-through 仅 Bash + sandbox auto-allow。
 *
 * <p>对齐 CC {@code hasPermissionsToUseToolInner} 1b 层
 * （Open-ClaudeCode/src/utils/permissions/permissions.ts:1184-1206，同语义亦见
 * checkRuleBasedPermissions :1091-1111）：
 * <pre>
 *   if (askRule) {
 *     canSandboxAutoAllow =
 *       tool.name === BASH_TOOL_NAME &&
 *       SandboxManager.isSandboxingEnabled() &&
 *       SandboxManager.isAutoAllowBashIfSandboxedEnabled() &&
 *       shouldUseSandbox(input);
 *     if (!canSandboxAutoAllow) → return ask;
 *     // fall through → 1c tool.checkPermissions（Bash 沙箱内命令自动放行）
 *   }
 * </pre>
 *
 * <p>验收标准：
 * <ol>
 *   <li>非 Bash 工具：whole-tool ask 规则命中时不得被工具默认 Allow 覆盖 → Ask 分发</li>
 *   <li>Bash + sandbox auto-allow：fall-through 保留（BashTool.checkPermissions
 *       的 sandbox auto-allow 自动放行）</li>
 *   <li>PowerShell（S05 前默认 Allow 工具）在 ask 规则命中时同样不得被放行</li>
 * </ol>
 *
 * <p>直接断言 {@link PermissionPipeline#check} 的返回（1b 层语义的最小可观测面；
 * gate 分发层已有 R26PermissionPipelineEquivalenceTest 覆盖）。
 */
@DisplayName("[S02] 1b 层 CC 语义：fall-through 仅 Bash+sandbox auto-allow")
class CheckLayer1bFallthroughTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 桩工具：默认 checkPermissions=Allow（对齐 Tool.java:238-246 default），name 可指定。 */
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
            return null; // 桩工具：权限测试不执行
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }
        // checkPermissions 走 Tool 默认实现 → Allow（默认 Allow 工具语义）
    }

    /** 构造含 whole-tool ask 规则的权限上下文（USER_SETTINGS source）。 */
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

    private PermissionResult check(PermissionPipeline pipeline, Tool tool,
                                   JsonNode input, ToolPermissionContext permCtx) {
        return pipeline.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input),
            input, ctx(permCtx), permCtx);
    }

    private PermissionPipeline pipeline(SandboxManager sandboxManager) {
        return new PermissionPipeline(sandboxManager);
    }

    /** 反射注入 BashTool 私有 sandboxManager 字段（BashTool 仅 @Autowired 无 setter）。 */
    private void injectSandboxManager(BashTool tool, SandboxManager sandboxManager) {
        try {
            Field f = BashTool.class.getDeclaredField("sandboxManager");
            f.setAccessible(true);
            f.set(tool, sandboxManager);
        } catch (Exception e) {
            throw new IllegalStateException("反射注入 BashTool.sandboxManager 失败", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1/3：非 Bash 工具（默认 Allow）不得覆盖 ask 规则
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 Bash 默认 Allow 工具 + whole-tool ask 规则 → Ask（不被静默放行）")
    void nonBashDefaultAllowTool_askRule_returnsAsk() {
        DefaultAllowTool tool = new DefaultAllowTool("SomeTool");
        ToolPermissionContext permCtx = askRuleCtx("SomeTool");
        ObjectNode input = JSON.createObjectNode();
        input.put("x", 1);

        PermissionResult r = check(pipeline(null), tool, input, permCtx);

        assertThat(r)
            .as("CC permissions.ts:1195-1203: canSandboxAutoAllow=false（非 Bash）→ ask")
            .isInstanceOfSatisfying(PermissionResult.Ask.class,
                ask -> {
                    assertThat(ask.reason()).isInstanceOf(PermissionDecisionReason.Rule.class);
                    assertThat(((PermissionDecisionReason.Rule) ask.reason()).rule().ruleValue().toolName())
                        .isEqualTo("SomeTool");
                });
    }

    @Test
    @DisplayName("PowerShellTool（S05 前默认 Allow）+ whole-tool ask 规则 → Ask")
    void powershellTool_askRule_notSilentlyAllowed() {
        PowerShellTool tool = new PowerShellTool();
        ToolPermissionContext permCtx = askRuleCtx("PowerShell");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "Get-Process *");

        PermissionResult r = check(pipeline(null), tool, input, permCtx);

        assertThat(r)
            .as("PowerShell ≠ BASH_TOOL_NAME → 无 sandbox fall-through，ask 规则必须 Ask")
            .isInstanceOfSatisfying(PermissionResult.Ask.class,
                ask -> assertThat(ask.reason()).isInstanceOf(PermissionDecisionReason.Rule.class));
    }

    @Test
    @DisplayName("Bash 名小写变体（非 BASH_TOOL_NAME 等价组）+ ask 规则 → Ask")
    void bashNameVariant_askRule_returnsAsk() {
        DefaultAllowTool tool = new DefaultAllowTool("bash");
        ToolPermissionContext permCtx = askRuleCtx("bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = check(pipeline(new SandboxManager(true, true)), tool, input, permCtx);

        assertThat(r)
            .as("CC tool.name === BASH_TOOL_NAME 精确比较：'bash' ≠ 'Bash' → ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2：Bash + sandbox auto-allow → fall-through 保留
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Bash + sandbox 激活 + auto-allow + 可沙箱命令 → fall-through 到工具 Allow")
    void bashTool_sandboxAutoAllow_fallthrough_toolAllow() {
        BashTool tool = new BashTool();
        SandboxManager sandbox = new SandboxManager(true, true);
        injectSandboxManager(tool, sandbox);
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = check(pipeline(sandbox), tool, input, permCtx);

        assertThat(r)
            .as("CC permissions.ts:1205-1206 + bashPermissions.ts:1829-1843: "
                + "fall-through 到 BashTool.checkPermissions 的 sandbox auto-allow → allow")
            .isInstanceOfSatisfying(PermissionResult.Allow.class,
                allow -> assertThat(allow.reason())
                    .isInstanceOf(PermissionDecisionReason.Other.class));
    }

    // ════════════════════════════════════════════════════════════════════
    // canSandboxAutoAllow 四条件逐个击穿 → Ask
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Bash + sandboxManager 未接线（null）→ Ask（sandbox 语义关闭）")
    void bashTool_noSandboxManager_askRule_returnsAsk() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = check(pipeline(null), tool, input, permCtx);

        assertThat(r)
            .as("sandboxManager==null → canSandboxAutoAllow=false → ask（与 HookPermissionResolver 同模式）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Bash + sandbox 关闭（enabled=false）→ Ask")
    void bashTool_sandboxDisabled_askRule_returnsAsk() {
        BashTool tool = new BashTool();
        SandboxManager sandbox = new SandboxManager(false, false);
        injectSandboxManager(tool, sandbox);
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = check(pipeline(sandbox), tool, input, permCtx);

        assertThat(r)
            .as("isSandboxingEnabled()=false → canSandboxAutoAllow=false → ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Bash + sandbox 激活但 auto-allow 关闭 → Ask")
    void bashTool_autoAllowDisabled_askRule_returnsAsk() {
        BashTool tool = new BashTool();
        SandboxManager sandbox = new SandboxManager(true, false);
        injectSandboxManager(tool, sandbox);
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "git status");

        PermissionResult r = check(pipeline(sandbox), tool, input, permCtx);

        assertThat(r)
            .as("isAutoAllowBashIfSandboxed()=false → canSandboxAutoAllow=false → ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Bash + sandbox auto-allow 但命令不可沙箱化（curl，excludedCommands）→ Ask")
    void bashTool_commandNotSandboxable_askRule_returnsAsk() {
        // WHY: IMP-B2 已删除 canSandbox 硬编码 curl 黑名单（curl 默认可沙箱化）——测试意图
        //      "命令不可沙箱化 → canSandboxAutoAllow=false → ask" 需显式经
        //      settings.sandbox.excludedCommands 让 curl 不可沙箱化（CC shouldUseSandbox.ts:147-150），
        //      否则 sandbox auto-allow（S01 预检无内容 deny/ask 命中）会正确放行 curl。
        BashTool tool = new BashTool();
        SandboxManager sandbox = new SandboxManager(true, true);
        sandbox.setExcludedCommands(List.of("curl:*"));  // curl 不可沙箱化
        injectSandboxManager(tool, sandbox);
        ToolPermissionContext permCtx = askRuleCtx("Bash");
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "curl http://example.com");

        PermissionResult r = check(pipeline(sandbox), tool, input, permCtx);

        assertThat(r)
            .as("shouldUseSandbox(input)=false（curl 被 excludedCommands 排除）→ canSandboxAutoAllow=false → ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("无 ask 规则 → 1b 不命中，后续层接管（兜底 Ask）")
    void noAskRule_pipelineContinues() {
        DefaultAllowTool tool = new DefaultAllowTool("SomeTool");
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        ObjectNode input = JSON.createObjectNode();
        input.put("x", 1);

        PermissionResult r = check(pipeline(null), tool, input, permCtx);

        assertThat(r)
            .as("1b 未命中 → 1c 默认 Allow → 快速放行（工具默认决策仅在无 ask 规则时生效）")
            .isInstanceOf(PermissionResult.Allow.class);
    }
}
