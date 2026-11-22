package com.nexusai.application.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /install-skill 技能测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：用户 2026-10-26 裁定「提供技能即可」——让模型自己浏览市场并安装技能；
 * 2026-11-18 扩展为「装能力」总入口（市场插件 / MCP server / 市场外散装技能 / 列出 / 卸载）。
 * 本技能是安全敏感能力，测试必须钉住：
 * <ol>
 *   <li>它是 <b>userInvocable + 模型可调用</b>（否则用户/模型都触发不了）；</li>
 *   <li>提示词里带<b>真实市场清单地址与安装落点</b>（模型不需要猜路径）；</li>
 *   <li>提示词强制<b>先问用户、不执行包内脚本</b>（安装=第三方代码，不能静默）；</li>
 *   <li>提示词把「市场内 → 走工具链 / 市场外 → 才手搓」讲清楚 —— 手搓市场包**不会生效**
 *       （不落记录、不注册 MCP、不刷新注册表），这是本能力最容易走错的一步；</li>
 *   <li>破坏性操作（卸载 / 删除）要求<b>先在会话里确认</b>。</li>
 * </ol>
 */
@DisplayName("/install-skill：装能力总入口（插件 / MCP / 散装技能）")
class InstallPluginRegistrarTest {

    @Test
    @DisplayName("注册为 userInvocable 且模型可调用，工具集覆盖下载/解压/确认与插件/MCP 工具")
    void registersModelInvocableSkill() {
        BundledSkillDefinition def = new InstallPluginRegistrar().register();

        assertThat(def.name()).isEqualTo("install-plugin");
        assertThat(def.aliases()).contains("install-skill");
        assertThat(def.userInvocable()).isTrue();
        assertThat(def.disableModelInvocation()).isFalse();
        assertThat(def.allowedTools())
            .contains("Bash", "WebFetch", "AskUserQuestion", "Read", "Write")
            .contains("ManagePlugin", "ManageMcpServer");
    }

    @Test
    @DisplayName("提示词包含市场清单地址、包地址规则、skills 安装根、确认与不执行脚本约束")
    void promptContainsMarketAndSafetyRules() {
        String userSkillsRoot = Path.of("C:/tmp/nexusai-home", "skills").toString();
        String prompt = InstallPluginRegistrar.buildPrompt(userSkillsRoot, ".nexusai/skills");

        assertThat(prompt)
            .contains(InstallPluginRegistrar.DEFAULT_MARKET_MANIFEST_URL)
            .contains("plugin.zip")
            .contains("SKILL.md")
            .contains("category")
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
    @DisplayName("市场内的包必须走 InstallPlugin 工具（手搓 unzip 不会生效）")
    void marketplaceInstallMustUseTool() {
        String prompt = InstallPluginRegistrar.buildPrompt("/tmp/x/skills", ".nexusai/skills");

        assertThat(prompt)
            .contains("ManagePlugin")
            .contains("Never hand-roll a marketplace install");
    }

    @Test
    @DisplayName("MCP 三种输入形态都有解析指引（GitHub 项目 / claude 命令 / JSON 配置）")
    void mcpInputFormsAreDocumented() {
        String prompt = InstallPluginRegistrar.buildPrompt("/tmp/x/skills", ".nexusai/skills");

        assertThat(prompt)
            .contains("ManageMcpServer")
            .contains("GitHub project or a plain name")
            .contains("claude mcp add")
            .contains("--transport")
            .contains("--env")
            .contains("--header")
            .contains("mcpServers")
            .contains("separator");
    }

    @Test
    @DisplayName("破坏性操作要求先在会话里确认（confirmed:true 只在用户确认后才用）")
    void destructiveOpsRequireConfirmation() {
        String prompt = InstallPluginRegistrar.buildPrompt("/tmp/x/skills", ".nexusai/skills");

        assertThat(prompt)
            .contains("confirmed: true")
            .contains("wait for the user's reply");
    }

    @Test
    @DisplayName("提示词如实说明插件自带 MCP 会随插件一起连接（并提醒用户先知情）")
    void promptStatesPluginMcpIsConnectedWithPlugin() {
        String prompt = InstallPluginRegistrar.buildPrompt("/tmp/x/skills", ".nexusai/skills");

        assertThat(prompt)
            .as("插件 MCP 已接线 ⇒ 提示词必须说清它会自动连接，且要先让用户知情")
            .contains("connected automatically")
            .contains("no separate approval")
            .contains("must know the package brings an external tool server");
        assertThat(prompt).doesNotContain("NOT auto-enabled");
    }

    @Test
    @DisplayName("提示词不得残留 Claude/claude 品牌（唯一例外：`claude mcp add` 是真实命令语法）")
    void promptHasNoClaudeBranding() {
        String prompt = InstallPluginRegistrar.buildPrompt("C:/h/skills", ".nexusai/skills");

        assertThat(prompt).doesNotContain("Claude");
        // `claude mcp add` 是用户贴过来的**真实命令语法**（与 `/import-cc` 同性质）⇒ 必须保留，
        // 否则模型认不出用户粘贴的命令。其余位置不得出现 claude 字样。
        assertThat(prompt).contains("claude mcp add");
        assertThat(prompt.replace("claude mcp add", ""))
            .as("除真实命令语法外不得出现 claude 字样")
            .doesNotContain("claude");
    }
}
