package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P3-5] SkillListingFilter 纯静态过滤语义测试 · 对齐 CC utils/attachments.ts:2651-2659
 * {@code filterToBundledAndMcp} + :2641 {@code FILTERED_LISTING_MAX = 30}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>bundled+mcp 两源保留、其余 loadedFrom 丢弃</b>——CC 过滤键 {@code loadedFrom === 'bundled' || 'mcp'}
 *       （:2652-2653）。user/project/plugin（长尾）走 discovery 而非 listing，绝不可混入 turn-0 注入。
 *       若过滤条件被改（如多留 skills 源），本测试 fail。</li>
 *   <li><b>超限回退 bundled-only（严格大于 30）</b>——CC :2654-2658 {@code filtered.length > FILTERED_LISTING_MAX}。
 *       =30 全保留、=31 回退 bundled-only。边界条件错一（如 >=）则本测试 fail（CC 是严格 >）。</li>
 *   <li><b>保持原序</b>——CC filter 不重排；顺序丢失会改变 listing 注入次序。</li>
 *   <li><b>null 输入防御</b>——LlmAgentLoop A8 调用点 getModelInvocableCommandsForListing 可能返回 null，
 *       过滤器不得 NPE。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert SkillListingFilter（返回原输入 / 过滤键改错 / 边界用 >= / 重排顺序）→
 * 本测试必须 fail。LlmAgentLoop 2800 行 loop 内门控分支不可直接单测，本测试 + flag 分支断言构成等价失败证据。
 */
class SkillListingFilterTest {

    private static Command command(String name, CommandLoadedFrom loadedFrom) {
        Command c = new Command();
        c.setId("id-" + name);
        c.setName(name);
        c.setLoadedFrom(loadedFrom);
        return c;
    }

    @Test
    @DisplayName("bundled+mcp 保留、skills/plugin 丢弃（CC attachments.ts:2652-2653）")
    void keepsBundledAndMcp_dropsOthers() {
        List<Command> input = List.of(
            command("bundled-1", CommandLoadedFrom.BUNDLED),
            command("mcp-1", CommandLoadedFrom.MCP),
            command("skills-1", CommandLoadedFrom.SKILLS),
            command("plugin-1", CommandLoadedFrom.PLUGIN),
            command("managed-1", CommandLoadedFrom.MANAGED),
            command("legacy-1", CommandLoadedFrom.COMMANDS_DEPRECATED),
            command("null-loaded", null));

        List<Command> result = SkillListingFilter.filterToBundledAndMcp(input);

        assertThat(result).extracting(Command::getName)
            .containsExactly("bundled-1", "mcp-1");
    }

    @Test
    @DisplayName("边界: filtered.length==30 全保留、==31 回退 bundled-only（CC :2654-2658 严格 >）")
    void boundary_30Kept_31FallsBackToBundledOnly() {
        List<Command> atMax = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            atMax.add(command("b-" + i, CommandLoadedFrom.BUNDLED));
        }
        // =30 → 全保留（含 mcp，不触发回退）
        List<Command> atMaxResult = SkillListingFilter.filterToBundledAndMcp(atMax);
        assertThat(atMaxResult).hasSize(30);
        assertThat(atMaxResult).extracting(Command::getName)
            .allMatch(n -> n.startsWith("b-"));

        // =31（30 bundled + 1 mcp）→ 回退 bundled-only（mcp 被丢）
        List<Command> overMax = new ArrayList<>(atMax);
        overMax.add(command("mcp-extra", CommandLoadedFrom.MCP));
        List<Command> overMaxResult = SkillListingFilter.filterToBundledAndMcp(overMax);
        assertThat(overMaxResult).hasSize(30);
        assertThat(overMaxResult).extracting(Command::getName)
            .allMatch(n -> n.startsWith("b-"))
            .doesNotContain("mcp-extra");
    }

    @Test
    @DisplayName("结果保持输入原序（CC filter 不重排）")
    void preservesInputOrder() {
        List<Command> input = List.of(
            command("z-mcp", CommandLoadedFrom.MCP),
            command("a-bundled", CommandLoadedFrom.BUNDLED),
            command("m-mcp", CommandLoadedFrom.MCP));

        List<Command> result = SkillListingFilter.filterToBundledAndMcp(input);

        assertThat(result).extracting(Command::getName)
            .containsExactly("z-mcp", "a-bundled", "m-mcp");
    }

    @Test
    @DisplayName("null / 空输入 → 空 list（防御性，不 NPE）")
    void nullAndEmptyInput_returnsEmpty() {
        assertThat(SkillListingFilter.filterToBundledAndMcp(null)).isEmpty();
        assertThat(SkillListingFilter.filterToBundledAndMcp(List.of())).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    // formatDescriptionWithSource（CC commands.ts:728-754，FIX-B5 拍板#8）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 构造带 source + description 的命令（formatDescriptionWithSource 判别源）。
     */
    private static Command commandWithSource(String name, CommandSource source) {
        Command c = new Command();
        c.setId("id-" + name);
        c.setName(name);
        c.setSource(source);
        c.setDescription("desc-" + name);
        return c;
    }

    private static Command withPluginInfo(Command c, String pluginName, String repository) {
        c.setPluginInfo(new Command.PluginInfo(new Command.PluginManifest(pluginName), repository));
        return c;
    }

    @Test
    @DisplayName("非 prompt 类型 → 原样返回 description（CC commands.ts:729-731）")
    void nonPromptType_returnsRawDescription() {
        // WHY: CC formatDescriptionWithSource 对非 prompt 命令不追加来源标注（type==='prompt' 是
        //   后续所有 source 分支的前置守卫）——判别错则 local/local-jsx 命令被误加来源后缀。
        Command c = commandWithSource("local", CommandSource.USER);
        c.setType("local");
        assertThat(SkillListingFilter.formatDescriptionWithSource(c)).isEqualTo("desc-local");
    }

    @Test
    @DisplayName("workflow kind → 'desc (workflow)'（CC commands.ts:733-735）")
    void workflowKind_appendsWorkflow() {
        Command c = commandWithSource("wf", CommandSource.BUILTIN);
        c.setKind("workflow");
        assertThat(SkillListingFilter.formatDescriptionWithSource(c)).isEqualTo("desc-wf (workflow)");
    }

    @Test
    @DisplayName("plugin 源 + pluginInfo.pluginManifest.name → '(pluginName) desc'（CC commands.ts:738-741）")
    void pluginSource_withPluginName_prefixesPluginName() {
        // WHY: plugin 源命令的展示名前缀来自 pluginInfo.pluginManifest.name（commands.ts:738）——
        //   pluginInfo 读侧消费（NEW-GAP-V-CI-1-1/2 回填）；读错字段则 plugin 命令展示名缺插件标识。
        Command c = withPluginInfo(commandWithSource("plugin-cmd", CommandSource.PLUGIN), "my-plugin", "my-plugin@market");
        assertThat(SkillListingFilter.formatDescriptionWithSource(c)).isEqualTo("(my-plugin) desc-plugin-cmd");
    }

    @Test
    @DisplayName("plugin 源 + 无 pluginInfo / 空 name → 'desc (plugin)'（CC commands.ts:742）")
    void pluginSource_withoutPluginName_appendsPlugin() {
        Command noInfo = commandWithSource("p1", CommandSource.PLUGIN);
        assertThat(SkillListingFilter.formatDescriptionWithSource(noInfo)).isEqualTo("desc-p1 (plugin)");
        Command emptyName = withPluginInfo(commandWithSource("p2", CommandSource.PLUGIN), "", "repo");
        assertThat(SkillListingFilter.formatDescriptionWithSource(emptyName)).isEqualTo("desc-p2 (plugin)");
    }

    @Test
    @DisplayName("builtin / mcp 源 → 原样（CC commands.ts:745-747）")
    void builtinAndMcp_returnRaw() {
        assertThat(SkillListingFilter.formatDescriptionWithSource(commandWithSource("b", CommandSource.BUILTIN)))
            .isEqualTo("desc-b");
        assertThat(SkillListingFilter.formatDescriptionWithSource(commandWithSource("m", CommandSource.MCP)))
            .isEqualTo("desc-m");
    }

    @Test
    @DisplayName("bundled 源 → 'desc (bundled)'（CC commands.ts:749-751）")
    void bundled_appendsBundled() {
        assertThat(SkillListingFilter.formatDescriptionWithSource(commandWithSource("bd", CommandSource.BUNDLED)))
            .isEqualTo("desc-bd (bundled)");
    }

    @Test
    @DisplayName("SettingSource 回退 → 'desc (user)/(managed)'（CC commands.ts:753 + getSettingSourceName）")
    void settingSourceFallback_appendsShortName() {
        // WHY: CC getSettingSourceName（constants.ts:26-33）userSettings→'user'/policySettings→'managed'；
        //   Java CommandSource 折叠 userSettings 等 4 值为 USER（M7 已知折叠）→ 仅能区分 user/managed。
        assertThat(SkillListingFilter.formatDescriptionWithSource(commandWithSource("u", CommandSource.USER)))
            .isEqualTo("desc-u (user)");
        assertThat(SkillListingFilter.formatDescriptionWithSource(commandWithSource("ps", CommandSource.POLICY_SETTINGS)))
            .isEqualTo("desc-ps (managed)");
    }

    @Test
    @DisplayName("null → 空串（防御性，不 NPE）")
    void nullCommand_returnsEmpty() {
        assertThat(SkillListingFilter.formatDescriptionWithSource(null)).isEmpty();
    }
}
