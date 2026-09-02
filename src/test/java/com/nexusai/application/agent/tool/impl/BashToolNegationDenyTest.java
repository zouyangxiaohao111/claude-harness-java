package com.nexusai.application.agent.tool.impl;

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
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 安全漏洞回归测试：{@code ! cmd} deny 绕过。
 *
 * <p>WHY（测试验证意图而非行为）：CC ast.ts:567-577 negated_command 递归剥 {@code !} 产出真实
 * argv —— {@code ! rm -rf /} 实际执行 {@code rm -rf /}。若权限匹配不剥 {@code !}，legacy 前缀
 * 匹配看命令原文 {@code "! rm -rf /"} 不以 {@code rm} 开头 → {@code Bash(rm:*)} deny 不命中 →
 * 用户 deny 被绕过、命令真实执行（fail-open 安全洞）。本测试锁死：deny 匹配路径（G4 argv +
 * legacy 双保险）对前导 {@code !} 递归剥离后再匹配。
 */
@DisplayName("P0-2 `! cmd` deny 绕过回归")
class BashToolNegationDenyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT = UUID.randomUUID();

    private static PermissionRule rule(PermissionBehavior behavior, String content) {
        return new PermissionRule(PermissionRuleSource.USER_SETTINGS, behavior,
            PermissionRuleValue.withContent("Bash", content));
    }

    private static ToolPermissionContext ctx(PermissionMode mode,
            Set<PermissionRule> allow, Set<PermissionRule> deny, Set<PermissionRule> ask) {
        Map<PermissionRuleSource, Set<PermissionRule>> allowMap = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> denyMap = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> askMap = new EnumMap<>(PermissionRuleSource.class);
        allowMap.put(PermissionRuleSource.USER_SETTINGS, allow);
        denyMap.put(PermissionRuleSource.USER_SETTINGS, deny);
        askMap.put(PermissionRuleSource.USER_SETTINGS, ask);
        return ToolPermissionContext.of(mode, allowMap, denyMap, askMap, Map.of());
    }

    private static ToolUseContext toolCtx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT, "sess-" + UUID.randomUUID().toString().substring(0, 8),
            permCtx.mode(), List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), permCtx, permCtx.mode());
    }

    private static ObjectNode bashInput(String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return input;
    }

    @Test
    @DisplayName("`! rm -rf /` 命中 Bash(rm:*) deny → Deny（legacy 前缀匹配剥 ! 双保险）")
    void negatedBang_denyRuleMatches() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "rm:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("! rm -rf /"), toolCtx(permCtx));

        assertThat(result).as("! rm -rf / 必须命中 Bash(rm:*) deny，不得降级/放行").isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("双重否定 `! ! rm -rf /` 递归剥两个 ! 后命中 deny → Deny")
    void negatedDoubleBang_denyRuleMatches() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "rm:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("! ! rm -rf /"), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("无 `!` 的 `rm -rf /` 保持原 deny 行为（基线）")
    void plainRm_denyRuleStillMatches() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "rm:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("rm -rf /"), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("`echo ! rm`（! 是参数非否定）不误伤 —— 无 rm deny 时非 Deny（基线放行/Ask 语义）")
    void bangAsArgument_notDenied() {
        BashTool tool = new BashTool();
        // echo 无 deny 规则 → 不应因 ! 误触发 rm deny
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "rm:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("echo ! rm"), toolCtx(permCtx));

        assertThat(result).as("echo ! rm 不含 rm 命令，不得命中 rm deny").isNotInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("preparePermissionMatcher（hook if 匹配器）：`! rm -rf /` 命中 Bash(rm:*)（P0-1 G4 argv 剥 !）")
    void preparePermissionMatcher_negatedBang_firesRmHook() {
        // P0-1: preparePermissionMatcher 走 G4 parseForSecurity —— negated_command 剥 `!` 产出
        //   argv=['rm','-rf','/'] → subcommand "rm -rf /" → Bash(rm:*) 前缀命中 → 运行 hook。
        //   旧 splitForSecurity 文本路径看 "! rm -rf /" 不以 rm 开头 → hook 被跳过（if 不匹配 = 不运行）
        //   → 安全 hook 被否定命令绕过。本断言锁死 G4 argv 语义。
        BashTool tool = new BashTool();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "! rm -rf /");
        java.util.function.Predicate<String> matcher = tool.preparePermissionMatcher(input);

        // HookMatcherEngine 消费 preparePermissionMatcher 谓词时传 parsed.ruleContent()
        // （HookMatcherEngine.java:586，去工具名前缀）—— 即 "rm:*" / "git *"，非 "Bash(rm:*)"。
        assertThat(matcher.test("rm:*")).as("! rm -rf / 的 G4 argv 必须命中 rm:* hook").isTrue();
        assertThat(matcher.test("git *")).as("! rm -rf / 不得命中 git * hook").isFalse();
    }
}
