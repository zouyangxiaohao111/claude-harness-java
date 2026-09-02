package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-12 verify 负向门控单测（IMP-03 返工补测）· 对齐 CC {@code verify.ts:13-15}
 * {@code if (process.env.USER_TYPE !== 'ant') return}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）:
 * <ol>
 *   <li><b>非 ant 环境 verify 必须不注册</b>——CC verify.ts:13-15 早返是 ant-only 门控（非 ant 部署
 *       不注册 /verify 命令）。P2-12 之前 Java 用 {@code "ant"::equals} 恒真 → verify 在<b>所有</b>环境
 *       注册，与 CC 相悖。Bootstrapper 注入真实 {@code isAntSupplier} 后（loremIpsum/stuck/skillify
 *       同款 USER_TYPE 判定），非 ant 环境 verify 不得出现在 BundledSkills 注册表。此断言防回归：
 *       若未来有人把 registerVerifySkill 的 isAntSupplier 换回恒 true 桩，必红。</li>
 *   <li><b>register() 返回 false 是契约信号</b>——CC 早返即无副作用（return）；Java
 *       {@code register()} 返回 false 表达同一语义。调用方（Bootstrapper registerSkill 包装）据此
 *       log warn 而非假装注册成功。</li>
 *   <li><b>与 ant=true 正向对照</b>——同一测试类内 ant=true 注册、ant=false 不注册，形成行为对比，
 *       证明门控以 USER_TYPE 真实判定而非恒定。</li>
 * </ol>
 */
class VerifySkillRegistrarNegativeGatingTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("registrar 级：isAntUser=()->false → register 返回 false 且 registrar 不被调用（CC verify.ts:13-15 早返）")
    void register_nonAntReturnsFalseAndSkipsRegistrar() {
        // CC verify.ts:13-15 if (process.env.USER_TYPE !== 'ant') return —— 非 ant 无副作用
        int[] registrarCalls = {0};
        boolean registered = new VerifySkillRegistrar().register(
            def -> registrarCalls[0]++,
            () -> false,
            "Verify description", "# Verify body", Map.of("examples/cli.md", "x"));

        assertThat(registered)
            .as("非 ant → register() 必须返回 false（CC verify.ts:13-15 早返）")
            .isFalse();
        assertThat(registrarCalls[0])
            .as("非 ant → registrar 回调不得执行（CC 早返无副作用）")
            .isZero();
    }

    @Test
    @DisplayName("registrar 级对照：isAntUser=()->true → register 返回 true 且 registrar 被调用（ant 注册）")
    void register_antReturnsTrueAndInvokesRegistrar() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        boolean registered = new VerifySkillRegistrar().register(
            def -> holder[0] = def,
            () -> true,
            "Verify description", "# Verify body", Map.of());

        assertThat(registered).as("ant 用户 → register() 必须返回 true").isTrue();
        assertThat(holder[0]).as("ant 用户 → registrar 必须收到 definition").isNotNull();
        assertThat(holder[0].name()).isEqualTo("verify");
    }

    @Test
    @DisplayName("bootstrapper 级：isAntSupplier=()->false → BundledSkills 不在册 verify（P2-12 核心）")
    void bootstrapper_nonAntVerifyNotInRegistry() {
        // CC verify.ts:13-15 USER_TYPE!=='ant' 早返 → 非 ant 环境 /verify 命令不存在
        new BundledSkillsBootstrapper(() -> false, () -> false).run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        assertThat(names)
            .as("P2-12：非 ant 环境 verify 不得注册（CC verify.ts:13-15）")
            .doesNotContain("verify");
    }

    @Test
    @DisplayName("bootstrapper 级对照：isAntSupplier=()->true → verify 在册（ant 环境注册）")
    void bootstrapper_antVerifyInRegistry() {
        new BundledSkillsBootstrapper(() -> false, () -> true).run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        assertThat(names)
            .as("ant 环境 → verify 注册（CC verify.ts:20 registerBundledSkill）")
            .contains("verify");
    }
}
