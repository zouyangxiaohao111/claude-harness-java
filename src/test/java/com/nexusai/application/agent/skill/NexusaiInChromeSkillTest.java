package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BD-17 nexusai-in-chrome isEnabled 惰性门控 + 注册字段偏移修复（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>CC claudeInChrome.ts:25 双门控语义</b>——registerBundledSkill 的 isEnabled 是
 *       {@code () => shouldAutoEnableClaudeInChrome()} 惰性判定，注册期门控之外还落一条惰性 gate。
 *       Java 旧版 isEnabled=null（仅注册期门控），Command.enabled 恒 false。断言 registerSkill 产出
 *       isEnabled=shouldAutoEnableSupplier 惰性求值即锁定对齐；若退回 null 此断言必红。</li>
 *   <li><b>字段偏移（desc/whenToUse 截断）</b>——CC description 含 "Opens pages in new tabs.../
 *       Requires site-level permissions..." 两句、whenToUse 含 "Always invoke BEFORE attempting..." 句，
 *       Java 旧版截断。断言完整句存在即锁定还原；若再度截断必红。</li>
 * </ol>
 */
class NexusaiInChromeSkillTest {

    @Test
    @DisplayName("registerSkill 产出 isEnabled=shouldAutoEnableSupplier 惰性门控（非 null）")
    void registerSkillWiresLazyIsEnabledGate() {
        AtomicReference<BooleanSupplier> capturedIsEnabled = new AtomicReference<>();
        NexusaiInChromeSkill skill = new NexusaiInChromeSkill(
            () -> "",
            () -> true,
            def -> capturedIsEnabled.set(def.isEnabled()));

        skill.registerSkill(List.of("click", "navigate"));

        assertThat(capturedIsEnabled.get())
            .as("CC claudeInChrome.ts:25 isEnabled: () => shouldAutoEnableClaudeInChrome() 惰性判定")
            .isNotNull();
        assertThat(capturedIsEnabled.get().getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("isEnabled 惰性门控：shouldAutoEnable=false 时求值为 false（对齐 CC 默认禁用）")
    void lazyIsEnabledGateEvaluatesFalse() {
        AtomicReference<BooleanSupplier> capturedIsEnabled = new AtomicReference<>();
        NexusaiInChromeSkill skill = new NexusaiInChromeSkill(
            () -> "", () -> false, def -> capturedIsEnabled.set(def.isEnabled()));

        skill.registerSkill(List.of());

        assertThat(capturedIsEnabled.get()).isNotNull();
        assertThat(capturedIsEnabled.get().getAsBoolean())
            .as("shouldAutoEnableClaudeInChrome()=false → isEnabled 惰性求值为 false")
            .isFalse();
    }

    @Test
    @DisplayName("SKILL_DESCRIPTION / SKILL_WHEN_TO_USE 还原 CC 完整文案（Opens pages / Always invoke）")
    void descriptionAndWhenToUseHaveFullCcText() {
        assertThat(NexusaiInChromeSkill.SKILL_DESCRIPTION)
            .as("CC claudeInChrome.ts:19-20 description 完整两句")
            .contains("Opens pages in new tabs within your existing Chrome session.")
            .contains("Requires site-level permissions before executing (configured in the extension).");
        assertThat(NexusaiInChromeSkill.SKILL_WHEN_TO_USE)
            .as("CC claudeInChrome.ts:21-22 whenToUse 的 Always invoke BEFORE 句")
            .contains("Always invoke BEFORE attempting to use any mcp__nexusai-in-chrome__* tools.");
    }
}
