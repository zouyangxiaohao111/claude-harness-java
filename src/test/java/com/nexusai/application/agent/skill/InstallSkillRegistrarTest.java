package com.nexusai.application.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /install-skill 技能测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：用户 2026-10-26 裁定「提供技能即可」——让模型自己浏览市场并安装技能。
 * 本技能是安全敏感能力，测试必须钉住三件事：
 * <ol>
 *   <li>它是 <b>userInvocable + 模型可调用</b>（否则用户/模型都触发不了）；</li>
 *   <li>提示词里带<b>真实市场清单地址与安装落点</b>（模型不需要猜路径）；</li>
 *   <li>提示词强制<b>先问用户、不执行包内脚本</b>（安装=第三方代码，不能静默）。</li>
 * </ol>
 */
@DisplayName("/install-skill：市场浏览与技能安装（提示型技能）")
class InstallSkillRegistrarTest {

    @Test
    @DisplayName("注册为 userInvocable 且模型可调用，工具集覆盖下载/解压/确认")
    void registersModelInvocableSkill() {
        BundledSkillDefinition def = new InstallSkillRegistrar().register();

        assertThat(def.name()).isEqualTo("install-skill");
        assertThat(def.userInvocable()).isTrue();
        assertThat(def.disableModelInvocation()).isFalse();
        assertThat(def.allowedTools())
            .contains("Bash", "WebFetch", "AskUserQuestion", "Read", "Write");
    }

    @Test
    @DisplayName("提示词包含市场清单地址、包地址规则、skills 安装根、确认与不执行脚本约束")
    void promptContainsMarketAndSafetyRules() {
        String userSkillsRoot = Path.of("C:/tmp/nexusai-home", "skills").toString();
        String prompt = InstallSkillRegistrar.buildPrompt(userSkillsRoot, ".nexusai/skills");

        assertThat(prompt)
            .contains(InstallSkillRegistrar.DEFAULT_MARKET_MANIFEST_URL)
            .contains("plugin.zip")
            .contains("SKILL.md")
            .contains("category: \"skill\"")
            .contains("建科院")
            .contains("keyword")
            .contains("GitHub")
            .contains("raw.githubusercontent.com")
            .contains("AskUserQuestion")
            .contains("Do not execute")
            .contains("never overwrite silently")
            .contains(userSkillsRoot)
            .contains(".nexusai/skills");
    }

    @Test
    @DisplayName("提示词不得残留 Claude/claude 品牌")
    void promptHasNoClaudeBranding() {
        String prompt = InstallSkillRegistrar.buildPrompt("C:/h/skills", ".nexusai/skills");

        assertThat(prompt).doesNotContain("Claude");
        assertThat(prompt).doesNotContain("claude");
    }
}
