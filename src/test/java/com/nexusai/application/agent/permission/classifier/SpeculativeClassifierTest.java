package com.nexusai.application.agent.permission.classifier;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [U6-RW2] SpeculativeClassifier 容器 + 可随时开启门控测试 · 对齐 CC bashPermissions.ts:1483-1544
 * + bashClassifier.ts:24-26/36-38/40-53。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：投机分类器是 CC 的"提前启动 + 竞速消费"优化结构，
 * 但 CC 外部构建（ANT-ONLY stub）下 {@code isClassifierPermissionsEnabled()} 恒 false →
 * 首闸恒拦截 → 投机永不启动；且 {@code getBashPromptAllowDescriptions} 是 checked-in stub
 * 恒返 []（bashClassifier.ts:36-38）。本测试锁定的不变量是：<b>方法与接线存在（可随时开启），
 * 首闸后主体（3 guard + allowDescriptions + classifyBashCommand + Map 存入）为真实代码</b> ——
 * 但外部构建下恒命中 guard 4（allowDescriptions 空）而 {@code return false}，
 * 而非硬编码 {@code return false}。
 *
 * <p>核心断言：
 * <ol>
 *   <li>默认（flag false）{@code start} 返回 false 且 {@code peek} 仍 null（首闸拦截）</li>
 *   <li>翻转 flag true + prompt 规则 ctx → {@code start} false（guard4，allowDescriptions 恒空）</li>
 *   <li>翻转 flag true + AUTO mode → {@code start} false（guard2，transcript flag）</li>
 *   <li>翻转 flag true + BYPASS_PERMISSIONS mode → {@code start} false（guard3）</li>
 *   <li>{@code consume} 缺席幂等（无 key 恒 null，不抛异常）</li>
 *   <li>{@code clear} 空 Map 幂等（重复调用无副作用）</li>
 *   <li>{@code classifyBashCommand} stub 返回 matches:false + reason='This feature is disabled'</li>
 * </ol>
 */
@DisplayName("[U6-RW2] SpeculativeClassifier：可随时开启门控 + start 主体真实代码（CC stub 恒空描述源，不接 YoloClassifier）")
class SpeculativeClassifierTest {

    private static final String CMD = "git status";

    @BeforeEach
    void resetFlags() {
        // WHY: 静态 flag 会跨测试泄漏 —— 翻转测试后必须复位，否则后续"默认 false"断言红。
        SpeculativeClassifier.setClassifierPermissionsEnabled(false);
        SpeculativeClassifier.setTranscriptClassifierEnabled(true);
        SpeculativeClassifier.clearSpeculativeChecks();
    }

    private static ToolPermissionContext promptRuleContext(String promptDescription) {
        PermissionRule promptRule = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS,
            PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent("Bash", "prompt: " + promptDescription));
        Map<PermissionRuleSource, Set<PermissionRule>> allowRules =
            Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(promptRule));
        return ToolPermissionContext.of(
            PermissionMode.DEFAULT, allowRules, Map.of(), Map.of(), Map.of());
    }

    @Test
    @DisplayName("默认（flag false）start 返回 false 且 peek 仍 null（首闸拦截，对齐 bashPermissions.ts:1504）")
    void start_defaultFlag_returnsFalse_andPeekStillNull() {
        // WHY: start 首闸 isClassifierPermissionsEnabled() 默认 false → return false 且不 speculativeChecks.set。
        //      若 start 错误地填充 Map（接 YoloClassifier 异步分类），peek 会返回非 null 并污染 gate 竞速。
        boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
            CMD, promptRuleContext("Run npm build"), AbortController.NOOP, false);
        assertThat(started)
            .as("startSpeculativeClassifierCheck 首闸默认 false → 恒 return false（bashPermissions.ts:1504）")
            .isFalse();

        CompletableFuture<SpeculativeClassifier.SpeculativeClassifierResult> peeked =
            SpeculativeClassifier.peekSpeculativeClassifierCheck(CMD);
        assertThat(peeked)
            .as("首闸拦截后 speculativeChecks 恒空 → peek 恒 null（bashPermissions.ts:1491-1494）")
            .isNull();
    }

    @Test
    @DisplayName("翻转 flag true + prompt 规则 ctx → allowDescriptions 恒空 → guard4 return false + peek null（对齐 CC stub）")
    void start_flippedFlag_returnsFalse_becauseAllowDescriptionsAlwaysEmpty() {
        // WHY: CC getBashPromptAllowDescriptions 是 checked-in stub 恒返 []（bashClassifier.ts:36-38），
        //      即便翻转 isClassifierPermissionsEnabled()=true 且 ctx 含 prompt: 规则，
        //      guard4（allowDescriptions 空）仍拦截 → return false → peek 恒 null。
        //      这是 CC 外部构建真实行为：投机分类器永不启动（除非未来提供非空描述源）。
        SpeculativeClassifier.setClassifierPermissionsEnabled(true);

        boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
            CMD, promptRuleContext("Run npm build"), AbortController.NOOP, false);
        assertThat(started)
            .as("allowDescriptions 恒空（CC stub）→ guard4 返回 false（bashPermissions.ts:1511）")
            .isFalse();

        assertThat(SpeculativeClassifier.peekSpeculativeClassifierCheck(CMD))
            .as("guard4 拦截后 speculativeChecks 恒空 → peek 恒 null（bashPermissions.ts:1491-1494）")
            .isNull();
    }

    @Test
    @DisplayName("翻转 flag true + transcriptClassifierEnabled true + AUTO mode → guard2 return false（bashPermissions.ts:1505-1506）")
    void start_flippedFlag_autoMode_returnsFalse() {
        // WHY: guard2 = feature('TRANSCRIPT_CLASSIFIER') && mode === 'auto' → return false。
        //      auto 模式由异步分类器接管，投机检查不启动 —— 锁定 guard2 先于 guard4 拦截的守卫顺序。
        SpeculativeClassifier.setClassifierPermissionsEnabled(true);
        SpeculativeClassifier.setTranscriptClassifierEnabled(true);

        ToolPermissionContext autoCtx = ToolPermissionContext.of(
            PermissionMode.AUTO, Map.of(), Map.of(), Map.of(), Map.of());
        boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
            CMD, autoCtx, AbortController.NOOP, false);
        assertThat(started)
            .as("auto mode + transcript flag → guard2 返回 false（bashPermissions.ts:1505-1506）")
            .isFalse();
    }

    @Test
    @DisplayName("翻转 flag true + transcriptClassifierEnabled false + AUTO mode → 跳过 guard2 落 guard4 return false（区分守卫顺序）")
    void start_flippedFlag_autoMode_transcriptDisabled_returnsFalseAtGuard4() {
        // WHY: guard2 需 transcriptClassifierEnabled=true 才命中；transcriptClassifierEnabled=false 时
        //      auto mode 跳过 guard2，落 guard4（allowDescriptions 恒空）仍 return false。
        //      此用例区分 guard2（transcript flag 命中）与 guard4（allowDescriptions 空）两条不同路径。
        SpeculativeClassifier.setClassifierPermissionsEnabled(true);
        SpeculativeClassifier.setTranscriptClassifierEnabled(false);

        ToolPermissionContext autoCtx = ToolPermissionContext.of(
            PermissionMode.AUTO, Map.of(), Map.of(), Map.of(), Map.of());
        boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
            CMD, autoCtx, AbortController.NOOP, false);
        assertThat(started)
            .as("transcriptClassifierEnabled=false 跳过 guard2 → guard4（allowDescriptions 恒空）返回 false")
            .isFalse();
    }

    @Test
    @DisplayName("翻转 flag true + BYPASS_PERMISSIONS mode → guard3 return false（bashPermissions.ts:1507）")
    void start_flippedFlag_bypassMode_returnsFalse() {
        // WHY: guard3 = mode === 'bypassPermissions' → return false。bypass 已授权无需分类器竞速，
        //      投机检查不启动。
        SpeculativeClassifier.setClassifierPermissionsEnabled(true);

        ToolPermissionContext bypassCtx = ToolPermissionContext.of(
            PermissionMode.BYPASS_PERMISSIONS, Map.of(), Map.of(), Map.of(), Map.of());
        boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
            CMD, bypassCtx, AbortController.NOOP, false);
        assertThat(started)
            .as("bypassPermissions mode → guard3 返回 false（bashPermissions.ts:1507）")
            .isFalse();
    }

    @Test
    @DisplayName("consume 缺席幂等（无 key 恒 null，不抛异常，对齐 bashPermissions.ts:1533-1540）")
    void consume_absentIdempotent() {
        CompletableFuture<SpeculativeClassifier.SpeculativeClassifierResult> consumed =
            SpeculativeClassifier.consumeSpeculativeClassifierCheck(CMD);
        assertThat(consumed)
            .as("speculativeChecks 空 → consume 恒 null（命中即删语义，缺席幂等）")
            .isNull();

        // null key 也幂等（防御性边界）
        assertThat(SpeculativeClassifier.consumeSpeculativeClassifierCheck(null)).isNull();
        assertThat(SpeculativeClassifier.peekSpeculativeClassifierCheck(null)).isNull();
    }

    @Test
    @DisplayName("clear 空 Map 幂等（重复调用无副作用，对齐 bashPermissions.ts:1543-1545）")
    void clear_emptyMapIdempotent() {
        SpeculativeClassifier.clearSpeculativeChecks();
        // 幂等性：连续两次 clear 不抛异常
        SpeculativeClassifier.clearSpeculativeChecks();

        assertThat(SpeculativeClassifier.peekSpeculativeClassifierCheck(CMD)).isNull();
        assertThat(SpeculativeClassifier.consumeSpeculativeClassifierCheck(CMD)).isNull();
    }

    @Test
    @DisplayName("classifyBashCommand stub 返回 matches:false + reason='This feature is disabled'（bashClassifier.ts:40-53）")
    void classifyBashCommand_stubReturnsDisabled() {
        // WHY: CC 投机用 classifyBashCommand stub（非 YOLO-LLM）；结果必须恒 matches:false + 禁用 reason，
        //      若接回 YoloClassifier（返回真实 matches）则偏离 CC 外部构建语义。
        CompletableFuture<SpeculativeClassifier.SpeculativeClassifierResult> future =
            SpeculativeClassifier.classifyBashCommand(
                CMD, SpeculativeClassifier.getCwd(null), java.util.List.of("Run npm build"),
                "allow", AbortController.NOOP, false);
        SpeculativeClassifier.SpeculativeClassifierResult result = future.join();
        assertThat(result.matches())
            .as("stub 恒不产生放行（matches:false）· bashClassifier.ts:48-49")
            .isFalse();
        assertThat(result.reason())
            .as("stub reason 逐字对齐 CC 'This feature is disabled'")
            .isEqualTo("This feature is disabled");
        assertThat(result.confidence())
            .as("stub confidence 恒 'high'（bashClassifier.ts:50）")
            .isEqualTo("high");
        assertThat(result.matchedDescription())
            .as("stub 无命中描述（matchedDescription 可 null）")
            .isNull();
    }

    @Test
    @DisplayName("getCwd(sessionId) 会话感知接线：无会话回落 user.dir，override 层返回会话 cwd（CC bashPermissions.ts:1513 + cwd.ts:26-32）")
    void getCwd_sessionAware_wiringToCwdResolution(@TempDir Path sessionCwd) {
        // WHY: P-2 接线意图 —— 分类器 cwd 必须经 CwdResolution.getCwd(sessionId)（override ?? sessionCwd ??
        //      boundProject ?? user.dir 兜底链，对齐 CC getCwd 三层），而非直读 System.getProperty("user.dir")。
        //      worktree/绑定项目会话下 user.dir 恒为 JVM 启动目录，直读会把 worktree 会话分类到错误 cwd。
        //      本用例锁定：无会话零行为变化（回落 user.dir）+ 会话 override 层生效（非 user.dir）。
        // 1) 无 override/session → 回落 user.dir（零行为变化）
        CwdResolution.clearCurrentOverride();
        assertThat(SpeculativeClassifier.getCwd(null))
            .as("无会话 → CwdResolution.getCwd(null) 回落 user.dir（对齐 CC 无会话兜底）")
            .isEqualTo(CwdResolution.getCwd(null))
            .isEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir", ".")));

        // 2) override 层（对齐 CC cwdOverrideStorage AsyncLocalStorage · cwd.ts:4）→ 会话显式 cwd 生效
        CwdResolution.setCurrentOverride(sessionCwd.toString());
        try {
            assertThat(SpeculativeClassifier.getCwd("test-session"))
                .as("override 层命中 → getCwd(sessionId) 返回会话 override cwd（非 user.dir）")
                .isEqualTo(CwdResolution.normalizeCwd(sessionCwd.toString()));
        } finally {
            CwdResolution.clearCurrentOverride();
        }
    }
}
