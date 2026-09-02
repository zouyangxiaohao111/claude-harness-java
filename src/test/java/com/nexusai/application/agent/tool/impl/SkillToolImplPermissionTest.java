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
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.check.CheckLayer1c_ToolCheck;
import com.nexusai.application.agent.permission.check.CheckLayer1d_ToolDeny_Immune;
import com.nexusai.application.agent.permission.check.CheckLayer3_PassthroughToAsk;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-3] SkillToolImpl.checkPermissions 五段流程对齐 CC 测试 ·
 * 对齐 CC {@code Open-ClaudeCode/src/tools/SkillTool/SkillTool.ts:432-578 checkPermissions}.
 *
 * <p>规则九（验证意图）: 旧实现 SkillToolImpl.java:264-266 对全部 Skill 调用无条件返回
 * Allow（"技能调用默认允许"）—— 用户配置的 Skill deny/allow 权限规则永不生效，任何技能
 * （含带 allowedTools / hooks 的敏感技能）都直接放行，违反 CC 权限安全语义
 * （SkillTool.ts:470-486 deny 阻断 + :507-523 allow 规则 + :529-538 30 白名单 auto-allow
 * + :570-577 默认 Ask）。本测试先钉住 CC 语义，实施后转绿。
 *
 * <p>RED 前提: 实施前以下 ①/②/④/⑤ 断言全红（无条件 Allow 无 Deny/无 Ask），③ 恰好绿
 * （无条件 Allow == 纯安全属性 auto-allow）。实施后 5 段流程全部 GREEN。
 */
@DisplayName("[P0-3] SkillToolImpl.checkPermissions 五段流程对齐 CC SkillTool.ts:432-578")
class SkillToolImplPermissionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void clearToolCheckCache() {
        // 1c/1d 共享 ThreadLocal per-call cache（ToolCheckCache），避免跨用例污染
        ToolCheckCache.clear();
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    /** 构造 Skill 工具输入 {skill: name}. */
    private ObjectNode skillInput(String skill) {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("skill", skill);
        return in;
    }

    /** 在 @TempDir 下创建技能目录 (skillsRoot/<skillName>/SKILL.md)，返回注册中心. */
    private SkillRegistry registryWithSkill(Path tempDir, String skillName, String frontmatter) throws Exception {
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), frontmatter);
        return new SkillRegistry(tempDir.toString());
    }

    /** 无技能（空目录）注册中心 —— 命令对象不存在时 checkPermissions 仍按 CC 流程走规则层. */
    private SkillRegistry emptyRegistry(Path tempDir) throws Exception {
        return new SkillRegistry(tempDir.toString());
    }

    /** 构造含单条 Skill(content) 规则的 permCtx（USER_SETTINGS source）· 对齐 CC settings.json 规则. */
    private ToolPermissionContext permCtxWithContentRule(PermissionBehavior behavior, String ruleContent) {
        PermissionRule rule = new PermissionRule(
                PermissionRuleSource.USER_SETTINGS, behavior,
                PermissionRuleValue.withContent("Skill", ruleContent));
        Map<PermissionRuleSource, Set<PermissionRule>> allow = behavior == PermissionBehavior.ALLOW
                ? Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(rule)) : Map.of();
        Map<PermissionRuleSource, Set<PermissionRule>> deny = behavior == PermissionBehavior.DENY
                ? Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(rule)) : Map.of();
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, deny, Map.of(), Map.of());
    }

    /** 无规则 permCtx（最严格模式）. */
    private ToolPermissionContext permCtxNoRules() {
        return ToolPermissionContext.strict(PermissionMode.DEFAULT);
    }

    /** 9 参便利 ctx（permissionContext 带规则; requireCanUseTool=false 默认）. */
    private ToolUseContext ctxWith(ToolPermissionContext permCtx) {
        return ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(),
                permCtx, PermissionMode.DEFAULT);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ① deny 规则 → Deny
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("① deny 规则 Skill(commit:*) 命中 → Deny('blocked by permission rules') (CC :470-486)")
    void denyRule_skillCommit_returnsDeny(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:470-486 deny 循环是权限安全底线 —— 用户配置
        //   Skill(commit:*) deny 规则时, 触发 commit 技能必须被阻断（bypass-immune）,
        //   不能因为"技能由用户主动调用"而无条件放行（旧实现 :264-266 安全偏移）。
        SkillToolImpl tool = new SkillToolImpl(emptyRegistry(tempDir));
        ToolUseContext tuc = ctxWith(permCtxWithContentRule(PermissionBehavior.DENY, "commit:*"));

        PermissionResult result = tool.checkPermissions(skillInput("commit"), tuc);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) result;
        assertThat(deny.message())
                .as("CC SkillTool.ts:479 deny 消息原文")
                .contains("blocked by permission rules");
        assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.Rule.class);
        PermissionDecisionReason.Rule ruleReason = (PermissionDecisionReason.Rule) deny.reason();
        assertThat(ruleReason.rule().ruleValue().ruleContent())
                .as("deny 归因必须带命中规则 (CC SkillTool.ts:480-483)")
                .isEqualTo("commit:*");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ② allow 规则 → Allow
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("② allow 规则 Skill(review:*) 命中 → Allow (CC :507-523)")
    void allowRule_skillReviewPr_returnsAllow(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:507-523 allow 循环 —— 用户配置 Skill(review:*)
        //   allow 规则时, review-pr 技能应放行（ruleMatches 前缀 "review:*" 匹配 "review-pr"）。
        //   旧实现恒 Allow 无法区分 allow/deny 规则; 新实现 allow 规则命中返回带 Rule 归因的 Allow。
        SkillToolImpl tool = new SkillToolImpl(emptyRegistry(tempDir));
        ToolUseContext tuc = ctxWith(permCtxWithContentRule(PermissionBehavior.ALLOW, "review:*"));

        PermissionResult result = tool.checkPermissions(skillInput("review-pr"), tuc);

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.reason()).isInstanceOf(PermissionDecisionReason.Rule.class);
        assertThat(((PermissionDecisionReason.Rule) allow.reason()).rule().ruleValue().ruleContent())
                .as("allow 归因必须带命中规则 (CC SkillTool.ts:517-520)")
                .isEqualTo("review:*");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ③ 纯安全属性技能 → auto-allow
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("③ 纯安全属性技能(无 allowedTools/hooks) → Allow auto-allow (CC :529-538)")
    void safeSkill_noAllowedTools_autoAllow(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:529-538 safe-properties auto-allow —— 技能仅含 30 白名单
        //   属性（name/description/content/skillRoot 等）时免问直接放行。这是 CC 大多数
        //   普通技能免打扰的基础; 带 allowedTools/hooks 的技能才需权限（fail-closed）。
        SkillRegistry registry = registryWithSkill(tempDir, "plain-skill",
                "---\nname: plain-skill\ndescription: 安全属性技能\n---\n# Plain\n\n正文\n");
        SkillToolImpl tool = new SkillToolImpl(registry);
        ToolUseContext tuc = ctxWith(permCtxNoRules());

        PermissionResult result = tool.checkPermissions(skillInput("plain-skill"), tuc);

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
                .as("safe-properties auto-allow 无决策归因 (CC SkillTool.ts:536 decisionReason:undefined)")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // ④ 带 allowedTools 技能 + 无规则 → 默认 Ask
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("④ allowedTools 技能 + 无规则 → Ask('Execute skill: X', 2×AddRules(LOCAL_SETTINGS), metadata.command) (CC :570-577)")
    void allowedToolsSkill_noRule_defaultAsk(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:542-577 默认 Ask —— 技能含非白名单属性(allowedTools)未配置
        //   规则时, 必须弹窗问用户; Ask 携带 2 条 AddRules suggestions(精确 commandName +
        //   commandName:* 前缀, destination=localSettings) 供用户一键授权, 及 metadata.command
        //   (命令对象供 UI 展示)。旧实现无条件 Allow 会让带 Bash 授权的技能静默执行。
        SkillRegistry registry = registryWithSkill(tempDir, "allowed-skill",
                "---\nname: allowed-skill\ndescription: 带 allowed-tools 技能\nallowed-tools: [Bash]\n---\n"
                        + "# Allowed\n\n正文\n");
        SkillToolImpl tool = new SkillToolImpl(registry);
        ToolUseContext tuc = ctxWith(permCtxNoRules());

        PermissionResult result = tool.checkPermissions(skillInput("allowed-skill"), tuc);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.message())
                .as("CC SkillTool.ts:572 default Ask 消息原文")
                .isEqualTo("Execute skill: allowed-skill");
        // 2 条 AddRules suggestions（精确 + 前缀, destination=localSettings, behavior=allow）
        assertThat(ask.suggestions()).hasSize(2);
        PermissionUpdate.AddRules exact = (PermissionUpdate.AddRules) ask.suggestions().get(0);
        PermissionUpdate.AddRules prefix = (PermissionUpdate.AddRules) ask.suggestions().get(1);
        assertThat(exact.destination()).isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(prefix.destination()).isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(exact.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(prefix.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(exact.rules()).extracting(r -> r.ruleValue().ruleContent())
                .as("精确 suggestion: Skill(commandName) (CC SkillTool.ts:544-554)")
                .containsExactly("allowed-skill");
        assertThat(prefix.rules()).extracting(r -> r.ruleValue().ruleContent())
                .as("前缀 suggestion: Skill(commandName:*) (CC SkillTool.ts:556-566)")
                .containsExactly("allowed-skill:*");
        // metadata.command = 命令对象 · [Session H P2-7] 升级为 PermissionMetadata sealed interface
        //   (CC types/permissions.ts:164-169) — Java 侧 CommandMetadata(name, description) 承载
        assertThat(ask.metadata()).isNotNull();
        assertThat(ask.metadata()).isInstanceOf(PermissionResult.PermissionMetadata.CommandMetadata.class);
        PermissionResult.PermissionMetadata.CommandMetadata md =
                (PermissionResult.PermissionMetadata.CommandMetadata) ask.metadata();
        assertThat(md.name())
                .as("CC SkillTool.ts:576 metadata: {command: commandObj}")
                .isEqualTo("allowed-skill");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑤ 前导斜杠归一化 + ruleMatches 精确/前缀边界
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⑤ 前导斜杠 /commit 归一化 + ruleMatches 边界 (commit:* 不匹配 review) (CC :437-440/:451-467)")
    void leadingSlashNormalization_andRuleMatchesBoundary(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:437-440 兼容前导斜杠 —— "/commit" 与 "commit" 归一化到同一
        //   commandName 参与规则匹配; :451-467 ruleMatches 精确 OR :* 前缀。边界验证:
        //   "commit:*" 不得匹配 "review"（前缀必须按剥 :* 后的真实前缀 startsWith）。
        SkillToolImpl tool = new SkillToolImpl(emptyRegistry(tempDir));
        // (a) 前导斜杠归一化: input skill=/commit → commandName=commit → deny 规则命中
        ToolUseContext denyCtx = ctxWith(permCtxWithContentRule(PermissionBehavior.DENY, "commit:*"));
        PermissionResult slashResult = tool.checkPermissions(skillInput("/commit"), denyCtx);
        assertThat(slashResult)
                .as("input '/commit' 归一化为 'commit' 后 deny Skill(commit:*) 命中 (CC :440)")
                .isInstanceOf(PermissionResult.Deny.class);

        // (b) 前缀边界: 无 deny/allow 命中 + 命令对象不存在 → 落到默认 Ask（不得误 Deny）
        ToolUseContext noRuleCtx = ctxWith(permCtxNoRules());
        PermissionResult boundaryResult = tool.checkPermissions(skillInput("review"), noRuleCtx);
        assertThat(boundaryResult)
                .as("Skill(commit:*) 前缀不匹配 'review' → 非 Deny, 落到默认 Ask")
                .isInstanceOf(PermissionResult.Ask.class);

        // (c) 精确匹配: 规则 "commit"（无 :*）精确命中 "commit"
        ToolUseContext exactCtx = ctxWith(permCtxWithContentRule(PermissionBehavior.DENY, "commit"));
        PermissionResult exactResult = tool.checkPermissions(skillInput("commit"), exactCtx);
        assertThat(exactResult).isInstanceOf(PermissionResult.Deny.class);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10 层管线落点: 1c 快速放行 Allow / 1d bypass-immune 捕获 Deny
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 层管线: 1c 无 Allow 早返（DC-04）, 1d bypass-immune 捕获 Skill deny, 3 层透传 Allow")
    void tenLayerPipeline_allowFastPath_denyBypassImmune(@TempDir Path tempDir) throws Exception {
        // WHY: SkillTool deny 的安全价值必须在生产 10 层管线表面化 —— CheckLayer1d
        //   bypass-immune 层（CheckLayer1d_ToolDeny_Immune，即使 BYPASS mode 也强制 deny）。
        //   [WF3-02 DC-04 / OPD-WF3-02-3] 1c <b>不再</b>快速放行 Allow（对齐 CC
        //   permissions.ts:1210-1318 无早返），工具 Allow 流经 2a/2b 归因覆盖后由 3 层
        //   原样透传（CC :1310 toolPermissionResult）。旧断言 "1c 返回 Allow" 已随 DC-04 更新。
        SkillToolImpl tool = new SkillToolImpl(emptyRegistry(tempDir));

        // Deny 场景
        ToolPermissionContext denyCtx = permCtxWithContentRule(PermissionBehavior.DENY, "commit:*");
        ToolUseBlock call = new ToolUseBlock("toolu_1", "Skill", skillInput("commit"));
        PermissionResult oneC = new CheckLayer1c_ToolCheck()
                .check(tool, call, skillInput("commit"), ctxWith(denyCtx), denyCtx);
        assertThat(oneC)
                .as("1c 无早返（DC-04/OPD-WF3-02-3 对齐 CC permissions.ts:1210-1318），恒 null")
                .isNull();
        PermissionResult oneD = new CheckLayer1d_ToolDeny_Immune()
                .check(tool, call, skillInput("commit"), ctxWith(denyCtx), denyCtx);
        assertThat(oneD).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) oneD).message()).contains("blocked by permission rules");

        // Allow 场景（1c 存 cache 返回 null → 3 层原样透传工具 Allow）
        ToolPermissionContext allowCtx = permCtxWithContentRule(PermissionBehavior.ALLOW, "review:*");
        ToolUseBlock call2 = new ToolUseBlock("toolu_2", "Skill", skillInput("review-pr"));
        PermissionResult oneC2 = new CheckLayer1c_ToolCheck()
                .check(tool, call2, skillInput("review-pr"), ctxWith(allowCtx), allowCtx);
        assertThat(oneC2)
                .as("1c 无 Allow 早返，工具 Allow 存 cache 后继续管线")
                .isNull();
        PermissionResult layer3Allow = new CheckLayer3_PassthroughToAsk()
                .check(tool, call2, skillInput("review-pr"), ctxWith(allowCtx), allowCtx);
        assertThat(layer3Allow)
                .as("3 层原样透传 1c 的 Allow（CC permissions.ts:1310 toolPermissionResult）")
                .isInstanceOf(PermissionResult.Allow.class);
    }

    // ────────────────────────────────────────────────────────────────────────
    // gate 级 Ask 表面化（concern #1 / DEC-5 决策前置）· 如实记录生产行为
    // ────────────────────────────────────────────────────────────────────────

    /** hook allow 决策（Java Allow.updatedInput 非空契约）· 对齐 ToolHooksPermissionTest.hookAllow. */
    private PermissionResult hookAllow(JsonNode updatedInput) {
        return new PermissionResult.Allow(
                updatedInput,
                new PermissionDecisionReason.Hook("PreToolUse:Skill", "test-hook", "hook approved"),
                null, false, null, List.of());
    }

    /** recording canUseTool stub · 记录调用次数（不调用则 gate 未把 Ask 转交弹窗）. */
    private static class RecordingCanUseTool implements HookPermissionResolver.CanUseTool {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolPermissionGate.DecisionResult canUse(
                com.nexusai.application.agent.tool.Tool tool, JsonNode input, ToolUseContext ctx,
                String toolUseId, PermissionResult forceDecision) {
            calls.incrementAndGet();
            return ToolPermissionGate.DecisionResult.allow();
        }
    }

    @Test
    @DisplayName("[DEC-5] gate 级: deny 规则经 HookPermissionResolver 1c 表面化为 Deny（非 bypass-immune Ask 除外）")
    void gatePath_skillDeny_surfacesAsDeny(@TempDir Path tempDir) throws Exception {
        // WHY: 生产 gate StreamingToolExecutor→HookPermissionResolver.checkRuleBasedPermissions
        //   在 hook allow 后做规则复检（HookPermissionResolver:171-186），1c tool.checkPermissions
        //   返回 Deny 时 :293-296 直接透传 → Skill deny 规则在 hook 已批准的情况下仍能阻断
        //   （deny 最高优先, CC permissions.ts:1115-1118）。
        SkillToolImpl tool = new SkillToolImpl(emptyRegistry(tempDir));
        JsonNode input = skillInput("commit");
        HookPermissionResolver resolver = new HookPermissionResolver();
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
                hookAllow(input), null, tool, input,
                ctxWith(permCtxWithContentRule(PermissionBehavior.DENY, "commit:*")),
                "toolu_1", canUseTool);

        assertThat(resolved.decision()).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) resolved.decision()).message())
                .as("Skill deny 消息经 gate 表面化 (HookPermissionResolver:293-296)")
                .contains("blocked by permission rules");
    }

    @Test
    @DisplayName("[P1-6] gate 级: SkillTool 默认 Ask(null reason) 非 bypass-immune → hook allow 静默放行（对齐 CC）")
    void gatePath_defaultAsk_notBypassImmune_silentlyAllowed(@TempDir Path tempDir) throws Exception {
        // WHY: SkillTool default Ask 对齐 CC decisionReason: undefined（SkillTool.ts:573）→
        //   Ask.reason=null（P1-6 拍板）→ 非 bypass-immune（isBypassImmuneAsk 只认
        //   Rule(ruleBehavior=ASK) / SafetyCheck, HookPermissionResolver:376-381）→
        //   checkRuleBasedPermissions 1c 分支 :356-367 把非 bypass-immune Ask 视为无异议返回 null
        //   → hook allow 直接放行, 弹窗被静默跳过。与 CC 行为一致（CC 默认 Ask decisionReason
        //   undefined 在 checkRuleBasedPermissions permissions.ts:1119-1128 同样落 null → hook allow 放行）。
        //   ★ 旧占位 Other("skill default ask")（DEC-5 记录）已删除：null 与占位的 bypass-immune
        //   判定一致（均非 immune），仅去除占位字符串工件，弹窗 reason 落通用 "permission requested"。
        SkillRegistry registry = registryWithSkill(tempDir, "allowed-skill",
                "---\nname: allowed-skill\ndescription: 带 allowed-tools 技能\nallowed-tools: [Bash]\n---\n"
                        + "# Allowed\n\n正文\n");
        SkillToolImpl tool = new SkillToolImpl(registry);
        JsonNode input = skillInput("allowed-skill");
        // 直连 checkPermissions 确认这是 default Ask（消息 + 2 条 suggestions 都存在 + reason null）
        PermissionResult direct = tool.checkPermissions(input, ctxWith(permCtxNoRules()));
        assertThat(direct).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) direct).suggestions()).hasSize(2);
        assertThat(((PermissionResult.Ask) direct).reason())
                .as("[P1-6] 默认 Ask decisionReason=null（对齐 CC SkillTool.ts:573 undefined）")
                .isNull();

        HookPermissionResolver resolver = new HookPermissionResolver();
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();
        PermissionResult hookPermission = hookAllow(input);
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
                hookPermission, null, tool, input,
                ctxWith(permCtxNoRules()),
                "toolu_1", canUseTool);

        assertThat(resolved.decision())
                .as("[P1-6] hook allow + SkillTool default Ask(null reason) → gate 静默放行为 hook Allow (HookPermissionResolver:356-367)")
                .isSameAs(hookPermission);
        assertThat(canUseTool.calls.get())
                .as("[P1-6] 非 bypass-immune Ask 未转交弹窗 (canUseTool 零调用)")
                .isZero();
    }
}
