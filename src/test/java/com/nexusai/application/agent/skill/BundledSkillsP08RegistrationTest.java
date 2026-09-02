package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-8 注册测试 · 3 缺失 skill 实例化 + KeybindingsSkill 修正（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>3 缺失 skill 是 CC bundled 注册契约的一部分</b>（CC index.ts:26/29/70-72）——Java
 *       改造前 3 类（NexusaiInChromeSkill/KeybindingsSkill/LoremIpsumSkill）零实例化（grep 0 命中），
 *       模型目录（SkillRegistry/SkillCatalog）缺这 3 个命令，与 CC 对齐目标相悖。断言注册即证明
 *       BundledSkillsBootstrapper 补齐实例化链。</li>
 *   <li><b>KeybindingsSkill 两处值必须对齐 CC</b>——name 'keybindings-help'（keybindings.ts:294）+
 *       userInvocable=false（keybindings.ts:298）。若仍用旧名 'keybindings' 或硬编码 true，
 *       模型会看到与 CC 不同的命令，此断言必红。</li>
 *   <li><b>门控按 CC 语义注入</b>——nexusai-in-chrome 走 shouldAutoEnable 门控（index.ts:70-72）、
 *       lorem-ipsum 走 ant 门控（loremIpsum.ts:235-237）。测试注入两个 gate=true 使注册链真实执行，
 *       同时验证门控关闭时技能不出现（见 {@link #gateClosedSkipsRegistration}）。</li>
 * </ol>
 */
class BundledSkillsP08RegistrationTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("run() 注册 keybindings-help(userInvocable=false) + nexusai-in-chrome + lorem-ipsum")
    void registersThreeMissingSkills() {
        // 门控 supplier 注入：nexusai-in-chrome 门控开 + lorem-ipsum ant 门控开
        BundledSkillsBootstrapper bootstrapper =
            new BundledSkillsBootstrapper(() -> true, () -> true);
        bootstrapper.run(null);

        Map<String, Command> byName = BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity()));

        // keybindings-help：name 修正 + userInvocable=false + allowedTools=['Read']
        // （对齐 CC keybindings.ts:294/:297/:298）
        Command keybindings = byName.get("keybindings-help");
        assertThat(keybindings).as("keybindings-help 必须已注册").isNotNull();
        assertThat(keybindings.getUserInvocable())
            .as("keybindings-help 应对齐 CC keybindings.ts:298 userInvocable=false")
            .isFalse();
        assertThat(keybindings.getAllowedTools())
            .as("keybindings-help 应对齐 CC keybindings.ts:297 allowedTools=['Read']")
            .containsExactly("Read");

        // nexusai-in-chrome：注册 + userInvocable=true（对齐 CC claudeInChrome.ts:18/:24）
        Command nexusaiInChrome = byName.get("nexusai-in-chrome");
        assertThat(nexusaiInChrome).as("nexusai-in-chrome 必须已注册（门控开）").isNotNull();
        assertThat(nexusaiInChrome.getUserInvocable())
            .as("nexusai-in-chrome 应对齐 CC claudeInChrome.ts:24 userInvocable=true")
            .isTrue();

        // lorem-ipsum：注册 + argumentHint + userInvocable=true（对齐 CC loremIpsum.ts:240/:243/:244）
        Command loremIpsum = byName.get("lorem-ipsum");
        assertThat(loremIpsum).as("lorem-ipsum 必须已注册（ant 门控开）").isNotNull();
        assertThat(loremIpsum.getArgumentHint())
            .as("lorem-ipsum 应对齐 CC loremIpsum.ts:243 argumentHint='[token_count]'")
            .isEqualTo("[token_count]");
        assertThat(loremIpsum.getUserInvocable())
            .as("lorem-ipsum 应对齐 CC loremIpsum.ts:244 userInvocable=true")
            .isTrue();
    }

    @Test
    @DisplayName("门控关闭时 nexusai-in-chrome / lorem-ipsum 不注册（对齐 CC index.ts:70-72 / loremIpsum.ts:235-237）")
    void gateClosedSkipsRegistration() {
        // 门控 supplier 注入：nexusai-in-chrome 门控关 + lorem-ipsum ant 门控关
        BundledSkillsBootstrapper bootstrapper =
            new BundledSkillsBootstrapper(() -> false, () -> false);
        bootstrapper.run(null);

        Map<String, Command> byName = BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity()));

        // 门控关 → 技能绝不出现（防止无条件注册破坏 CC 门控语义）
        assertThat(byName).doesNotContainKey("nexusai-in-chrome");
        assertThat(byName).doesNotContainKey("lorem-ipsum");

        // keybindings-help 无条件注册（CC index.ts:26 always-on），与门控无关
        assertThat(byName).containsKey("keybindings-help");
    }
}
