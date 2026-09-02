package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-12] CheckLayer1e 原样透传工具 Ask + CheckLayer2a bypass 直通（对齐 CC permissions.ts）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li><b>1e 透传</b>：CC permissions.ts:1230-1236 —— {@code return toolPermissionResult}
 *       原样返回工具自决的 Ask（message/reason/suggestions/updatedInput 全保留）。旧 Java 实现
 *       自建 message（"Tool 'X' requires explicit user interaction (cannot bypass)."）+
 *       reason=Other("requiresUserInteraction") + 空 suggestions，弹窗文案/归因/updatedInput 均偏离
 *       CC（OPD-WF3-DC-v4-01 / OPD-WF3-01-20 拍板：对齐 CC 透传）。</li>
 *   <li><b>2a bypass 直通</b>：CC permissions.ts:1268-1271 —— {@code shouldBypass =
 *       mode==='bypassPermissions' || (mode==='plan' && isBypassPermissionsModeAvailable)}。
 *       bypass 分支<b>不查 available</b>直通；仅 plan 分支查 available。旧 Java 实现两种 mode
 *       都要求 available=true（Java 安全底线选择），偏离 CC（OPD-WF3-01-12 拍板：对齐 CC 宽松）。</li>
 * </ul>
 */
@DisplayName("[IMP-12] 1e 原样透传工具 Ask + 2a bypass 直通（对齐 CC permissions.ts:1230-1236/:1268-1271）")
class CheckLayer1e2aCcAlignTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 普通工具：requiresUserInteraction=false + checkPermissions 默认 allow。 */
    private static final class PlainTool implements Tool {
        private final String name;

        PlainTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
    }

    /** 交互工具：requiresUserInteraction=true + checkPermissions 返回预置 toolAsk（1c 侧）。 */
    private static final class InteractionTool implements Tool {
        private final String name;
        final PermissionResult toolAsk;

        InteractionTool(String name, PermissionResult toolAsk) {
            this.name = name;
            this.toolAsk = toolAsk;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
        @Override public boolean requiresUserInteraction() { return true; }
        @Override public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
            return toolAsk;
        }
    }

    private static ToolUseContext ctx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode());
    }

    // ─────────────────────── 1e：原样透传工具 Ask（OPD-WF3-DC-v4-01） ───────────────────────

    @Test
    @DisplayName("1e 必须原样透传工具 Ask 同一实例（message/reason/updatedInput 全保留，不自建）")
    void layer1e_passesThroughToolAsk_unchanged() {
        // CC permissions.ts:1230-1236 —— return toolPermissionResult，弹窗文案/归因/updatedInput
        // 与工具自决一致。旧实现自建 message + Other('requiresUserInteraction') + 空 suggestions。
        ToolCheckCache.clear();
        try {
            ObjectNode input = JSON.createObjectNode();
            input.put("prompt", "are you sure?");
            ObjectNode updatedInput = JSON.createObjectNode();
            updatedInput.put("prompt", "are you sure? (user edited)");
            PermissionResult toolAsk = new PermissionResult.Ask(
                "Answer questions?",
                new PermissionDecisionReason.Other("AskUserQuestion checkPermissions ask"),
                List.of(), null, updatedInput, null, false, null, List.of());
            InteractionTool tool = new InteractionTool("AskUserQuestion", toolAsk);
            ToolPermissionContext permCtx = ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS);

            // 1c 已把工具 Ask 存入共享 cache（同 call 内 1e 复用，ToolCheckCache Javadoc）
            ToolCheckCache.put(tool.name(), toolAsk);

            CheckLayer1e_RequiresUserInteraction layer = new CheckLayer1e_RequiresUserInteraction();
            PermissionResult r = layer.check(tool,
                new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input),
                input, ctx(permCtx), permCtx);

            assertThat(r)
                .as("1e 原样透传同一工具 Ask 实例（CC :1235 return toolPermissionResult），而非自建新 Ask")
                .isSameAs(toolAsk);
            assertThat(((PermissionResult.Ask) r).message())
                .as("透传后 message = 工具原始文案 'Answer questions?'（旧自建 'requires explicit user interaction' 已删）")
                .isEqualTo("Answer questions?");
            assertThat(((PermissionResult.Ask) r).reason())
                .as("透传后 reason = 工具原始归因（非旧 Other('requiresUserInteraction')）")
                .isEqualTo(new PermissionDecisionReason.Other("AskUserQuestion checkPermissions ask"));
            assertThat(((PermissionResult.Ask) r).updatedInput())
                .as("透传后 updatedInput = 工具回写 input（OPD-WF3-01-02 updatedInput 回填语义保留）")
                .isSameAs(updatedInput);
        } finally {
            ToolCheckCache.clear();
        }
    }

    // ─────────────────────── 2a：bypass 直通（OPD-WF3-01-12） ───────────────────────

    @Test
    @DisplayName("mode=BYPASS_PERMISSIONS + available=false → 仍 Allow（bypass 直通不查 available）")
    void layer2a_bypassMode_directAllow_evenWhenAvailableFalse() {
        // CC permissions.ts:1268-1271 —— shouldBypass = mode==='bypassPermissions' ||
        //   (mode==='plan' && isBypassPermissionsModeAvailable)。bypass 分支不查 available。
        //   旧实现要求两种 mode 都查 available → BYPASS+available=false 返回 null（偏离 CC）。
        CheckLayer2a_BypassMode layer = new CheckLayer2a_BypassMode();
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.BYPASS_PERMISSIONS, Map.of(), Map.of(), Map.of(), Map.of(),
            false /* isBypassPermissionsModeAvailable=false */, false, Map.of(), false, false, null);
        ObjectNode input = JSON.createObjectNode();
        input.put("cmd", "echo hi");

        PermissionResult r = layer.check(new PlainTool("Bash"),
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input), input, ctx(permCtx), permCtx);

        assertThat(r)
            .as("mode=BYPASS_PERMISSIONS 直通 Allow，即使 isBypassPermissionsModeAvailable=false（CC :1268-1269）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) r).reason())
            .as("bypass Allow 归因 = Mode(bypassPermissions)（CC :1276-1279）")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.BYPASS_PERMISSIONS));
    }

    @Test
    @DisplayName("mode=BYPASS_PERMISSIONS + available=true → Allow（回归，直通仍然生效）")
    void layer2a_bypassMode_availableTrue_allows() {
        CheckLayer2a_BypassMode layer = new CheckLayer2a_BypassMode();
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.BYPASS_PERMISSIONS, Map.of(), Map.of(), Map.of(), Map.of(),
            true, false, Map.of(), false, false, null);
        ObjectNode input = JSON.createObjectNode();

        PermissionResult r = layer.check(new PlainTool("Bash"),
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input), input, ctx(permCtx), permCtx);

        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("mode=PLAN 仅 available=true 时 Allow（available=false → null 落 2b/3）")
    void layer2a_planMode_requiresAvailable() {
        // CC permissions.ts:1270-1271 —— plan 分支才查 isBypassPermissionsModeAvailable。
        CheckLayer2a_BypassMode layer = new CheckLayer2a_BypassMode();
        ObjectNode input = JSON.createObjectNode();
        input.put("cmd", "git status");

        ToolPermissionContext planAvailable = new ToolPermissionContext(
            PermissionMode.PLAN, Map.of(), Map.of(), Map.of(), Map.of(),
            true, false, Map.of(), false, false, null);
        PermissionResult allowed = layer.check(new PlainTool("Bash"),
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input), input,
            ctx(planAvailable), planAvailable);
        assertThat(allowed)
            .as("PLAN + available=true → Allow（CC :1270-1271 plan && isAvailable）")
            .isInstanceOf(PermissionResult.Allow.class);

        ToolPermissionContext planUnavailable = new ToolPermissionContext(
            PermissionMode.PLAN, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        PermissionResult notBypass = layer.check(new PlainTool("Bash"),
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input), input,
            ctx(planUnavailable), planUnavailable);
        assertThat(notBypass)
            .as("PLAN + available=false → null（不 bypass，落 2b/3；CC :1268 shouldBypass=false）")
            .isNull();
    }

    @Test
    @DisplayName("mode=DEFAULT → null（非 bypass/plan，不命中 2a）")
    void layer2a_defaultMode_returnsNull() {
        CheckLayer2a_BypassMode layer = new CheckLayer2a_BypassMode();
        ToolPermissionContext permCtx = ToolPermissionContext.strict(PermissionMode.DEFAULT);
        ObjectNode input = JSON.createObjectNode();

        PermissionResult r = layer.check(new PlainTool("Bash"),
            new ToolUseBlock(UUID.randomUUID().toString(), "Bash", input), input, ctx(permCtx), permCtx);

        assertThat(r).isNull();
    }
}
