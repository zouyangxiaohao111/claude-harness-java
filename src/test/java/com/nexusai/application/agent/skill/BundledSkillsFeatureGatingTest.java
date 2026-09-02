package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-4 注册级 feature flag 门控测试（RED→GREEN）· 对齐 CC bundled/index.ts:47/:56/:64。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>门控机制真实生效</b>——CC {@code feature('AGENT_TRIGGERS'|'AGENT_TRIGGERS_REMOTE'|
 *       'BUILDING_CLAUDE_APPS')} 是编译期门控（生产 bundle cli.js G15 编译 true → loop/schedule/
 *       claude-api 无条件注册，E6）；Java 用 Spring 运行时 flags 等价。全 false → 三 skill 都不注册，
 *       证明门控存在且能阻断注册（RED 阶段 BundledSkillFeatureFlags 类型不存在 → 编译失败）。</li>
 *   <li><b>默认值匹配 CC 生产注册集</b>——{@link BundledSkillFeatureFlags#DEFAULTS} 的
 *       agentTriggers/agentTriggersRemote/buildingClaudeApps 全 true → 三 skill 均注册
 *       （14 skill 注册集不变）；mcpSkills 默认 false（P1-9，对齐 CC 生产 DCE，不影响
 *       BundledSkillsBootstrapper 的 bundled skill 注册）。若前三个 flag 默认值被误改 false，
 *       此断言必红（防过度删除守卫）。</li>
 *   <li><b>partial 独立门控</b>——agentTriggers=false 仅 loop 缺、schedule/claude-api 在，证明三个 flag
 *       是独立开关而非整体开关。</li>
 * </ol>
 */
class BundledSkillsFeatureGatingTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("featureFlags 全 false → loop/schedule/claude-api 均不注册（CC 门控生效）")
    void allFlagsDisabledSkipsThreeSkills() {
        // isAntSupplier=() -> true：P2-6 后 remember 走真实 ant 早返（CC remember.ts:5-7），
        // 本测试验证 feature-flag 门控不影响 always-on skill，故 ant 门控开（remember 经 ant 注册）。
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true,
            new BundledSkillFeatureFlags(false, false, false, true));
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        // 三 skill 被门控阻断（CC index.ts:47/:56/:64 feature=false 语义）
        assertThat(names).doesNotContain("loop", "schedule", "claude-api");
        // 无条件注册的 skill 仍在（门控不影响 always-on，CC index.ts:25-34；remember 另走 ant 门控）
        assertThat(names).contains("batch", "debug", "remember", "simplify");
    }

    @Test
    @DisplayName("DEFAULTS 三 flag true + mcpSkills false → loop/schedule/claude-api 均注册（匹配 CC 生产注册集）")
    void defaultsRegisterAllThree() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> false, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        assertThat(names).contains("loop", "schedule", "claude-api");
    }

    @Test
    @DisplayName("partial：agentTriggers=false 仅 loop 缺，schedule/claude-api 独立在")
    void partialFlagIndependent() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> false,
            new BundledSkillFeatureFlags(false, true, true, true));
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        assertThat(names).doesNotContain("loop");
        assertThat(names).contains("schedule", "claude-api");
    }

    @Test
    @DisplayName("Config @Bean 工厂注入 flags：生产接线路径真实生效（非仅测试构造器）")
    void configBeanFactoryWiresFlags() {
        // 直接驱动 BundledSkillFeatureFlagsConfig#bundledSkillsBootstrapper（生产 @Bean 工厂），
        // 验证 Spring 接线路径把 yml 绑定 flags 注入 Bootstrapper —— 若工厂未传 flags（硬编码 DEFAULTS），
        // 全 false 时三 skill 仍会注册，此断言必红。
        BundledSkillFeatureFlagsConfig config =
            new BundledSkillFeatureFlagsConfig(BundledSkillFeatureFlags.DEFAULTS);
        // [拍板#9 part2] 生产 @Bean 工厂签名新增 SessionMemoryService 参（skillify 会话 memory 通道）；测试传 null
        BundledSkillsBootstrapper bootstrapper =
            config.bundledSkillsBootstrapper(
                new BundledSkillFeatureFlags(false, false, false, true), null);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        assertThat(names).doesNotContain("loop", "schedule", "claude-api");
    }
}
