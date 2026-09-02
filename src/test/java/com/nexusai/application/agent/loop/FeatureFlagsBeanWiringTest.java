package com.nexusai.application.agent.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-02 D-27] W1 接线缺口修复测试 · ReactiveCompactor/ContextCollapse/FeatureFlags bean 接线。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: D-27 是 P0 接线缺口——{@code AgentLoopContextFactory}
 * 的 {@code @Autowired(required=false)} FeatureFlags/ReactiveCompactor/ContextCollapse 生产恒
 * null / ALL_DISABLED（无 bean），reactive/collapse/snip 三能力无法开启（06 W1 / E4-1 / E4-6）。
 * 本测试验证：
 * <ol>
 *   <li><b>FeatureFlags 是 Spring bean 且可配置</b> — {@code FeatureFlags.FeatureFlagsConfig} 提供
 *       {@code @Bean}，经 {@code nexusai.feature.*} 属性开关（默认 false，对齐 CC feature() flag）。</li>
 *   <li><b>ReactiveCompactor/ContextCollapse 已注册 @Bean</b> — ToolRegistrationConfig 提供
 *       {@code reactiveCompactor(TokenCounter, FeatureFlags)} / {@code contextCollapse(FeatureFlags)}，
 *       使 {@code AgentLoopContextFactory} 注入非 null（D-27 修复）。</li>
 *   <li><b>Factory 注入点存在</b> — AgentLoopContextFactory 的 @Autowired 字段 + build() 透传。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert FeatureFlagsConfig（删 @Bean / 不读属性）或删 ToolRegistrationConfig
 * 的 reactiveCompactor/contextCollapse @Bean → 本测试 fail。
 */
class FeatureFlagsBeanWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FeatureFlags.FeatureFlagsConfig.class);

    @Test
    @DisplayName("featureFlags bean 存在且默认全关（对齐 CC flag 默认关闭，含 OD-01 S1 新增 4 门控 + TOKEN_BUDGET + IMP-CM-08 TEAMMEM 开关）")
    void featureFlagsBeanDefaultDisabled() {
        runner.run(ctx -> {
            FeatureFlags flags = ctx.getBean(FeatureFlags.class);
            assertThat(flags).isNotNull();
            assertThat(flags.reactiveCompact())
                .as("REACTIVE_COMPACT flag 默认必须 false（对齐 CC feature() 默认关闭）")
                .isFalse();
            assertThat(flags.contextCollapse())
                .as("CONTEXT_COLLAPSE flag 默认必须 false")
                .isFalse();
            assertThat(flags.historySnip())
                .as("HISTORY_SNIP flag 默认必须 false（关时 snip 模块为 null · CC query.ts:115/401）")
                .isFalse();
            assertThat(flags.budgetAggregateGate())
                .as("tengu_hawthorn_steeple flag 默认必须 false（关时 applyToolResultBudget 跳过 · CC toolResultStorage.ts:451-455）")
                .isFalse();
            assertThat(flags.smSessionMemory())
                .as("tengu_session_memory flag 默认必须 false（与 smCompact AND · CC sessionMemoryCompact.ts:412-415）")
                .isFalse();
            assertThat(flags.smCompact())
                .as("tengu_sm_compact flag 默认必须 false（与 smSessionMemory AND · CC sessionMemoryCompact.ts:416-419）")
                .isFalse();
            assertThat(flags.tokenBudget())
                .as("TOKEN_BUDGET flag 默认必须 false（关时 token_budget section 与 output_token_usage 均不注入 · CC prompts.ts:538/attachments.ts:3829）")
                .isFalse();
            assertThat(flags.teamMem())
                .as("TEAMMEM flag 默认必须 false（OPD-CM3-10/B03 可配置开关模拟 · CC memory/types.ts:9 TeamMem 仅 feature 开启时在值域）")
                .isFalse();
        });
    }

    @Test
    @DisplayName("feature 可开关：nexusai.feature.*=true → bean 反射 true（含 OD-01 S1 新增 4 门控 + TOKEN_BUDGET + IMP-CM-08 team-mem）")
    void featureFlagsToggleableByProperty() {
        runner.withPropertyValues("nexusai.feature.reactive-compact=true",
                "nexusai.feature.context-collapse=true",
                "nexusai.feature.history-snip=true",
                "nexusai.feature.budget-aggregate-gate=true",
                "nexusai.feature.sm-session-memory=true",
                "nexusai.feature.sm-compact=true",
                "nexusai.feature.token-budget=true",
                "nexusai.feature.team-mem=true").run(ctx -> {
            FeatureFlags flags = ctx.getBean(FeatureFlags.class);
            assertThat(flags.reactiveCompact())
                .as("nexusai.feature.reactive-compact=true 必须开启 REACTIVE_COMPACT（feature 可开关 · D-27）")
                .isTrue();
            assertThat(flags.contextCollapse())
                .as("nexusai.feature.context-collapse=true 必须开启 CONTEXT_COLLAPSE（feature 可开关 · D-27）")
                .isTrue();
            assertThat(flags.historySnip())
                .as("nexusai.feature.history-snip=true 必须开启 HISTORY_SNIP（OD-01 S1 门控扩展）")
                .isTrue();
            assertThat(flags.budgetAggregateGate())
                .as("nexusai.feature.budget-aggregate-gate=true 必须开启 tengu_hawthorn_steeple 门控（OD-01 S1）")
                .isTrue();
            assertThat(flags.smSessionMemory())
                .as("nexusai.feature.sm-session-memory=true 必须开启 tengu_session_memory 门控（OD-01 S1）")
                .isTrue();
            assertThat(flags.smCompact())
                .as("nexusai.feature.sm-compact=true 必须开启 tengu_sm_compact 门控（OD-01 S1）")
                .isTrue();
            assertThat(flags.tokenBudget())
                .as("nexusai.feature.token-budget=true 必须开启 TOKEN_BUDGET 门控（ER-IMP-2026-04 P-19 · CC prompts.ts:538）")
                .isTrue();
            assertThat(flags.teamMem())
                .as("nexusai.feature.team-mem=true 必须开启 TEAMMEM 门控（OPD-CM3-10/B03 · CC memory/types.ts:9）")
                .isTrue();
        });
    }

    @Test
    @DisplayName("22 参规范构造器保留显式位 + 未显式门控默认 false（A3 删除 3 参兼容壳后 · 对齐 CC feature() 缺省）")
    void canonicalConstructorPreservesExplicitAndDefaultsGatesOff() {
        // A3：3 参兼容构造器已删（CC bun:bundle feature() 无 Java 兼容壳 · Java-only），
        // 改用 22 参规范构造器，未显式传参的门控补默认 false（对齐 CC feature() 缺省关闭）。
        FeatureFlags flags = new FeatureFlags(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        assertThat(flags.contextCollapse())
            .as("规范构造器必须保留显式 CONTEXT_COLLAPSE 位（contextCollapse=true 显式传入）")
            .isTrue();
        assertThat(flags.promptCacheBreakDetection())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.tenguMothCopse())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.historySnip())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.budgetAggregateGate())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.smSessionMemory())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.smCompact())
            .as("规范构造器未显式门控默认 false")
            .isFalse();
        assertThat(flags.teamMem())
            .as("规范构造器 TEAMMEM 门控默认 false（OPD-CM3-10/B03）")
            .isFalse();
    }

    @Test
    @DisplayName("ToolRegistrationConfig 声明 reactiveCompactor/contextCollapse @Bean + factory 注入点")
    void toolConfigDeclaresBeansAndFactoryAutowires() throws Exception {
        String config = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/config/ToolRegistrationConfig.java"));
        assertThat(config)
            .as("ToolRegistrationConfig 必须声明 ReactiveCompactor @Bean（D-27 修复 W1 接线缺口）")
            .contains("@Bean")
            .contains("ReactiveCompactor reactiveCompactor(");
        assertThat(config)
            .as("ToolRegistrationConfig 必须声明 ContextCollapse @Bean（D-27）")
            .contains("ContextCollapse contextCollapse(");

        String factory = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/loop/AgentLoopContextFactory.java"));
        assertThat(factory)
            .as("AgentLoopContextFactory 必须 @Autowired ReactiveCompactor（注入非 null）")
            .contains("private ReactiveCompactor reactiveCompactor");
        assertThat(factory)
            .as("AgentLoopContextFactory 必须 @Autowired ContextCollapse（注入非 null）")
            .contains("private ContextCollapse contextCollapse");
        assertThat(factory)
            .as("AgentLoopContextFactory 必须 @Autowired FeatureFlags（注入生产配置）")
            .contains("private FeatureFlags featureFlags");
    }

    @Test
    @DisplayName("[IMP2-24 T-5] autoCompactor @Bean 四 feature 门接线（FeatureFlags → 抑制门，§7-13/29 裁决）")
    void autoCompactorBeanWiresFeatureGates() throws Exception {
        // 生产接线存在性：autoCompactor bean 必须调用四 setter（接线 FeatureFlags 属性）
        String config = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/config/ToolRegistrationConfig.java"));
        assertThat(config)
            .as("REACTIVE_COMPACT feature 门必须接线（CC autoCompact.ts:195）")
            .contains("autoCompactor.setReactiveCompactEnabled(");
        assertThat(config)
            .as("CONTEXT_COLLAPSE feature 门必须接线（CC autoCompact.ts:179/215）")
            .contains("autoCompactor.setContextCollapseEnabled(");
        assertThat(config)
            .as("tengu_cobalt_raccoon growthbook 门显式 false 接线（Java 无 GB，对齐 CC 缺省 · autoCompact.ts:196）")
            .contains("autoCompactor.setReactiveOnlyMode(false)");
        assertThat(config)
            .as("context-collapse 运行时门必须接线（CC isContextCollapseEnabled · autoCompact.ts:220）")
            .contains("autoCompactor.setContextCollapseModeEnabled(");

        // 接线语义镜像：FeatureFlags 属性开 → 抑制门生效（竞速防护 · §7-29/风险 14）
        // 镜像生产接线（ToolRegistrationConfig.autoCompactor）的注入方式
        FeatureFlags on = new FeatureFlags(true, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false); // reactiveCompact=true, contextCollapse=true
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setReactiveCompactEnabled(on.reactiveCompact());
        auto.setContextCollapseEnabled(on.contextCollapse());
        auto.setReactiveOnlyMode(false);
        auto.setContextCollapseModeEnabled(on.contextCollapse());
        // CONTEXT_COLLAPSE feature+运行时门双 true → 抑制主动 autocompact（CC autoCompact.ts:215-223）
        assertThat(auto.shouldAutoCompact(List.of(), "user", 0))
            .as("feature 开 + 运行时门开必须抑制 autocompact（collapse 拥有 headroom，风险 14）")
            .isFalse();

        // 对照：默认全关（生产默认 ALL_DISABLED）→ 不抑制 → 超阈照常
        AutoCompactor off = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        assertThat(off.shouldAutoCompact(List.of(), "user", 0))
            .as("默认全关时不得抑制（对齐 CC flag 默认关闭）")
            .isTrue();
    }
}
