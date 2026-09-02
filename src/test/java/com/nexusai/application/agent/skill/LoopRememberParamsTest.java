package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-6 loop/remember isEnabled/whenToUse/argumentHint 真实生效（RED→GREEN）· 对齐 CC
 * loop.ts:79-83 + remember.ts:68-71 + commands.ts:484 isCommandEnabled 过滤。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>真实 gate 供应而非硬编码桩</b>——改造前 Bootstrapper:219 注入 {@code () -> false} 桩、:223
 *       注入 {@code true, () -> false} 桩，使 loop/remember 的 Command.enabled 恒 false——即便
 *       CC 默认 isKairosCronEnabled=isAutoMemoryEnabled=true（loop.ts:83 / remember.ts:71）。断言
 *       默认构造（2 参 ant=true）注册后两命令 enabled=true：若仍用桩，必红。</li>
 *   <li><b>remember ant 早返真实生效</b>——CC remember.ts:5-7 {@code USER_TYPE !== 'ant'} 早返；
 *       改造前 Bootstrapper:223 硬编码 isAntUser=true 恒注册，ant 门控是死参数。断言
 *       isAntSupplier=false 时不注册：若仍硬编码 true，必红。</li>
 *   <li><b>SkillRegistry enabled 过滤链消费</b>——CC commands.ts:484 getCommands 过滤
 *       {@code isCommandEnabled(_)}；改造前 SkillRegistry getAllCommands/findCommand/getModelInvocableCommands
 *       grep enabled|isEnabled 0 命中，Command.enabled 是死字段。断言 enabled=false 的 bundled command
 *       不可查找/不可调用：若无过滤，必红。</li>
 *   <li><b>Command.isCommandEnabled 惰性求值</b>——CC types/command.ts:214-215
 *       {@code isCommandEnabled = cmd.isEnabled?.() ?? true}：惰性函数每次新鲜求值、覆盖 enabled 兜底；
 *       且 getAllCommands 每次调用都新鲜求值（commands.ts:478 注释）。断言 supplier 翻转即时生效 +
 *       每调用重求值。</li>
 * </ol>
 */
class LoopRememberParamsTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("默认 gate 供应：loop/remember Command.enabled=true（真实 isKairosCronEnabled/isAutoMemoryEnabled，非 ()->false 桩）")
    void defaultGatesEnableLoopAndRemember() {
        // ant=true 使 remember 经 ant 早返注册（USER_TYPE 门控开）；chrome=关
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(() -> false, () -> true);
        bootstrapper.run(null);

        Map<String, Command> byName = BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity()));

        Command loop = byName.get("loop");
        assertThat(loop).as("loop 必须已注册（DEFAULTS agentTriggers=true 门控通过）").isNotNull();
        assertThat(loop.getEnabled())
            .as("CC loop.ts:83 isEnabled: isKairosCronEnabled 默认 true（非 Bootstrapper:219 ()->false 桩）")
            .isTrue();

        Command remember = byName.get("remember");
        assertThat(remember).as("remember 必须已注册（ant 门控开）").isNotNull();
        assertThat(remember.getEnabled())
            .as("CC remember.ts:71 isEnabled: () => isAutoMemoryEnabled() 默认 true（非 Bootstrapper:223 ()->false 桩）")
            .isTrue();
    }

    @Test
    @DisplayName("remember ant 早返真实生效：isAntSupplier=false 时不注册（CC remember.ts:5-7 USER_TYPE!=='ant' return）")
    void rememberSkippedWhenNotAnt() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(() -> false, () -> false);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName)
            .collect(Collectors.toList());

        assertThat(names)
            .as("CC remember.ts:5-7 ant-only 早返：非 ant 用户 remember 不注册（Bootstrapper:223 硬编码 true 必须删除）")
            .doesNotContain("remember");
    }

    @Test
    @DisplayName("SkillRegistry enabled 过滤：enabled=false 的 bundled command 不可查找/不可调用（CC commands.ts:484 isCommandEnabled）")
    void skillRegistryExcludesDisabledCommands() {
        Command enabledCmd = new Command();
        enabledCmd.setId("bundled-enabled-x");
        enabledCmd.setName("enabled-x");
        enabledCmd.setSource(CommandSource.BUNDLED);
        enabledCmd.setEnabled(Boolean.TRUE);
        BundledSkills.register(enabledCmd);

        Command disabledCmd = new Command();
        disabledCmd.setId("bundled-disabled-y");
        disabledCmd.setName("disabled-y");
        disabledCmd.setSource(CommandSource.BUNDLED);
        disabledCmd.setEnabled(Boolean.FALSE);
        BundledSkills.register(disabledCmd);

        SkillRegistry registry = new SkillRegistry("C:/nonexistent-skills-root-p26");

        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .as("CC commands.ts:484 isCommandEnabled 过滤：禁用命令不出现在聚合结果")
            .contains("enabled-x")
            .doesNotContain("disabled-y");

        assertThat(registry.findCommand("disabled-y"))
            .as("CC SkillTool.getAllCommands→findCommand：禁用 skill 不可经 SkillTool 调用（disabled→null）")
            .isNull();
        assertThat(registry.findCommand("enabled-x"))
            .as("启用命令仍可查找")
            .isNotNull();

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .as("CC getSkillToolCommands 消费已 enabled 过滤的 getCommands：禁用命令不入模型可调用清单")
            .doesNotContain("disabled-y");
    }

    @Test
    @DisplayName("Command.isCommandEnabled 惰性求值：isEnabled supplier 覆盖 enabled 兜底 + 每调用新鲜求值（CC types/command.ts:214-215）")
    void commandIsEnabledLazyResolution() {
        Command cmd = new Command();
        cmd.setName("lazy-x");
        cmd.setEnabled(Boolean.TRUE); // 兜底 true

        // supplier 显式 false → 覆盖 enabled 兜底（CC isEnabled?.() 优先）
        cmd.setIsEnabled(() -> false);
        assertThat(cmd.isCommandEnabled())
            .as("CC isCommandEnabled = isEnabled?.() ?? true：supplier false 覆盖 enabled=true")
            .isFalse();

        // supplier 显式 true → 即使 enabled=false 也启用（CC isEnabled?.() 优先）
        cmd.setEnabled(Boolean.FALSE);
        cmd.setIsEnabled(() -> true);
        assertThat(cmd.isCommandEnabled())
            .as("CC isCommandEnabled：supplier true 覆盖 enabled=false（惰性函数优先）")
            .isTrue();

        // 无 supplier → 回退 enabled（CC ?? true）
        cmd.setIsEnabled(null);
        cmd.setEnabled(Boolean.FALSE);
        assertThat(cmd.isCommandEnabled()).as("无 supplier → 回退 enabled=false").isFalse();
        cmd.setEnabled(Boolean.TRUE);
        assertThat(cmd.isCommandEnabled()).as("无 supplier → 回退 enabled=true").isTrue();

        // 惰性：getAllCommands 每次调用新鲜求值 supplier（CC commands.ts:478 注释，auth 变更即时生效）
        AtomicInteger evaluations = new AtomicInteger(0);
        Command fresh = new Command();
        fresh.setName("fresh-z");
        fresh.setSource(CommandSource.BUNDLED);
        fresh.setEnabled(Boolean.TRUE);
        fresh.setIsEnabled(() -> evaluations.incrementAndGet() > 0);
        BundledSkills.register(fresh);

        SkillRegistry registry = new SkillRegistry("C:/nonexistent-skills-root-p26-fresh");
        registry.getAllCommands();
        registry.getAllCommands();
        registry.getAllCommands();
        assertThat(evaluations.get())
            .as("CC isEnabled checks run fresh every call（commands.ts:478 注释）：3 次 getAllCommands → supplier 求值 ≥3 次（raw 仍 memoize，过滤新鲜）")
            .isGreaterThanOrEqualTo(3);
    }
}
