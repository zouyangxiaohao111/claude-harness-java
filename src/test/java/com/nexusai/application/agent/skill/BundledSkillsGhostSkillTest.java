package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-15 幽灵 skill 空壳登记测试（RED→GREEN）· dream / hunter / runSkillGenerator。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）:
 * <ol>
 *   <li><b>Java 明确不注册（意图）</b>——CC bundled/index.ts:35-77 有 3 个 feature-gated 注册块
 *       （dream：KAIROS|KAIROS_DREAM / hunter：REVIEW_ARTIFACT / runSkillGenerator：
 *       RUN_SKILL_GENERATOR），但源文件 dream.ts / hunter.ts / runSkillGenerator.ts 本 checkout 缺失
 *       （DCE 剔除，CC 上游缺陷：flag 开则 CC 懒 require 抛 MODULE_NOT_FOUND）。Java 以
 *       {@link DreamSkillRegistrar} / {@link HunterSkillRegistrar} / {@link RunSkillGeneratorSkillRegistrar}
 *       空壳类登记该缺陷，但<b>不进 {@link BundledSkillsBootstrapper} 注册列表</b>。若未来有人误把
 *       空壳接入注册链、或新增隐藏别名入注册表，②/③ 的『BundledSkills.getAll() 不含三幽灵名』
 *       断言必红。</li>
 *   <li><b>元数据防漂移</b>——三空壳的 NAME / SLASH_COMMAND / FEATURE_FLAGS（顺序与内容）/
 *       CC_SOURCE_FILE 必须与 CC 证据一致，改错任何一项 ① 断言必红。</li>
 *   <li><b>空壳不可实例化（机制层防线）</b>——final + private 构造器 ⇒ 无法被 new 进注册表，
 *       从机制上排除『顺手实例化注册』（RED 阶段三空壳类不存在 → 编译失败，等价失败证据，仿
 *       BundledSkillsFeatureGatingTest RED 惯例）。</li>
 * </ol>
 */
class BundledSkillsGhostSkillTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("① 三空壳元数据对齐 CC（NAME/SLASH_COMMAND/FEATURE_FLAGS 顺序/CC_SOURCE_FILE）")
    void ghostSkillMetadataAlignedToCc() {
        assertThat(DreamSkillRegistrar.NAME).isEqualTo("dream");
        assertThat(DreamSkillRegistrar.SLASH_COMMAND).isEqualTo("/dream");
        // CC bundled/index.ts:35：if (feature('KAIROS') || feature('KAIROS_DREAM'))
        assertThat(DreamSkillRegistrar.FEATURE_FLAGS).containsExactly("KAIROS", "KAIROS_DREAM");
        assertThat(DreamSkillRegistrar.CC_SOURCE_FILE).isEqualTo("bundled/dream.js");

        assertThat(HunterSkillRegistrar.NAME).isEqualTo("hunter");
        assertThat(HunterSkillRegistrar.SLASH_COMMAND).isEqualTo("/hunter");
        // CC bundled/index.ts:41：if (feature('REVIEW_ARTIFACT'))
        assertThat(HunterSkillRegistrar.FEATURE_FLAGS).containsExactly("REVIEW_ARTIFACT");
        assertThat(HunterSkillRegistrar.CC_SOURCE_FILE).isEqualTo("bundled/hunter.js");

        assertThat(RunSkillGeneratorSkillRegistrar.NAME).isEqualTo("runSkillGenerator");
        assertThat(RunSkillGeneratorSkillRegistrar.SLASH_COMMAND).isEqualTo("/run-skill-generator");
        // CC bundled/index.ts:73：if (feature('RUN_SKILL_GENERATOR'))
        assertThat(RunSkillGeneratorSkillRegistrar.FEATURE_FLAGS).containsExactly("RUN_SKILL_GENERATOR");
        assertThat(RunSkillGeneratorSkillRegistrar.CC_SOURCE_FILE).isEqualTo("bundled/runSkillGenerator.js");
    }

    @Test
    @DisplayName("② DEFAULTS 注册集不含 dream/hunter/runSkillGenerator（Java 明确不注册，无隐藏别名）")
    void defaultsDoNotRegisterGhostSkills() {
        // 显式 5 参构造器：chrome=false / ant=true / DEFAULTS / kairosCron=true / autoMemory=true，
        // 使注册集与 env 无关（P2-12 后 verify 按真实 USER_TYPE 门控，ant=true 保证 verify 在册；
        // 避免依赖外部 env，同 ③ 惯例）。
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS, () -> true, () -> true);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        // 三幽灵名绝不在注册表（CC index.ts:35-77 feature-gated 块 Java 不实现）
        assertThat(names).doesNotContain("dream", "hunter", "runSkillGenerator");
        // 注册链未因此被掏空/替换：always-on skill 仍在（CC index.ts:25-34 + keybindings/lorem-ipsum）
        assertThat(names).contains("batch", "debug", "verify", "keybindings-help");
    }

    @Test
    @DisplayName("③ 确定性注册集保持 16（chrome 门控关 ant 开 DEFAULTS）· 注册集不变证明")
    void deterministicRegisterSetUnchanged() {
        // 显式 5 参构造器：chrome=false / ant=true / DEFAULTS / kairosCron=true / autoMemory=true，
        // 使注册集与 env 无关（avoid 依赖 CLAUDE_CODE_DISABLE_AUTO_MEMORY 等外部 env）。
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS, () -> true, () -> true);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        // 注册集 = 16 skill（本批次新增 ultracode/cron-list/cron-delete 3 个 bundled skill；17 全集减
        // nexusai-in-chrome：chrome 门控关，CC index.ts:70-72 等价；dream/hunter/runSkillGenerator 三幽灵不注册）
        assertThat(names).doesNotContain("dream", "hunter", "runSkillGenerator");
        assertThat(BundledSkills.count()).isEqualTo(16);
        assertThat(names).contains("batch", "claude-api", "debug", "loop", "remember", "schedule",
            "simplify", "skillify", "stuck", "update-config", "verify", "keybindings-help", "lorem-ipsum",
            "ultracode", "cron-list", "cron-delete");
    }

    @Test
    @DisplayName("④ 三空壳 final + private 构造器（不可实例化 → 不可被误注册）")
    void ghostSkillsAreNonInstantiableShells() throws Exception {
        assertShellIsFinalPrivateConstructor(DreamSkillRegistrar.class);
        assertShellIsFinalPrivateConstructor(HunterSkillRegistrar.class);
        assertShellIsFinalPrivateConstructor(RunSkillGeneratorSkillRegistrar.class);
    }

    private void assertShellIsFinalPrivateConstructor(Class<?> shellClass) throws Exception {
        assertThat(Modifier.isFinal(shellClass.getModifiers()))
            .as("%s 应为 final 空壳类", shellClass.getSimpleName())
            .isTrue();
        Constructor<?> ctor = shellClass.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers()))
            .as("%s 构造器应为 private（不可被 new 注册）", shellClass.getSimpleName())
            .isTrue();
    }
}
