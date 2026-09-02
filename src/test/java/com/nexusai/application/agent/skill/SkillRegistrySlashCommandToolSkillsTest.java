package com.nexusai.application.agent.skill;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-3] SkillRegistry.getSlashCommandToolSkills 过滤测试 · 对齐 CC getSlashCommandToolSkills
 * （commands.ts:586-608 第二套过滤）。数据源 = getSkillInfo（prompt.ts:221-241）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>与 getModelInvocableCommands 是两套不同过滤</b>——getSlashCommandToolSkills 统计「斜杠
 *       命令技能」（用户/模型都可用，含 disableModelInvocation 的仅用户可用技能），而
 *       getModelInvocableCommands 只统计模型可调用。前者必须包含 disableModelInvocation=true 的技能
 *       （CC :598），后者排除（commands.ts:569）。若两套过滤误合并，getSkillInfo 计数错误。</li>
 *   <li><b>BUILTIN 源永远排除</b>——CC :593 {@code source !== 'builtin'}。内置 CLI 命令（/help /clear）
 *       不是技能，即使带 whenToUse/disableModelInvocation 也不得计入。</li>
 *   <li><b>无显式描述且无 whenToUse 的 USER 技能不入选</b>——CC :594 {@code (hasUserSpecifiedDescription
 *       || whenToUse)}。技能清单只列有引导信息的技能，否则 model 无从判断何时用。</li>
 *   <li><b>独立 memoize + refresh() 清空</b>——CC :586 memoize-by-cwd；P1-16 skill 热更新必须经
 *       refresh() 清空本缓存，否则 getSkillInfo 返回陈旧计数（MEDIUM 风险点）。</li>
 * </ol>
 */
class SkillRegistrySlashCommandToolSkillsTest {

    /** 写一个最小 SKILL.md（frontmatter name + 可选额外字段）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name, String extraFrontmatter) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        StringBuilder fm = new StringBuilder("---\nname: ").append(name).append('\n');
        if (extraFrontmatter != null) {
            fm.append(extraFrontmatter).append('\n');
        }
        fm.append("---\n# ").append(name).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), fm.toString());
    }

    /**
     * 经 MCP 通道注入 MCP source 命令（P2-9 分离后仅 MCP 命令可用此通道 —— MCP 不再并入 getAllCommands）。
     * McpServerService 为具体类，子类覆写即可。
     */
    private static void injectMcpCommand(SkillRegistry registry, Command cmd) {
        registry.setMcpServerService(new StaticMcpServerService(List.of(cmd)));
    }

    /**
     * P2-9 后夹具注册表：覆写 getAllCommands 直接喂入任意 source 命令（如 PLUGIN/BUILTIN）。
     * MCP 通道分离后不再能经 setMcpServerService 注入非 MCP source 命令进 getAllCommands；
     * 需把命令放入命令集合的用例改用本夹具（对齐 McpShellGuardTest.SingleCommandRegistry 模式）。
     */
    private static final class FixtureRegistry extends SkillRegistry {
        private final List<Command> cmds;

        FixtureRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    /**
     * 加载失败夹具：覆写 getAllCommands 抛 RuntimeException（模拟技能加载期异常 —— 如
     * isCommandEnabled 门控 BooleanSupplier 抛错，Command.isCommandEnabled() 惰性求值传播，
     * Command.java:362-364）。用于验证 CC commands.ts:600-605 恒不抛契约。
     */
    private static final class ThrowingFixtureRegistry extends SkillRegistry {
        ThrowingFixtureRegistry() {
            super(".claude/skills");
        }

        @Override
        public List<Command> getAllCommands() {
            throw new RuntimeException("模拟技能加载失败 (getAllCommands isCommandEnabled 门控抛错)");
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void clearBundledSkills() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("USER 技能带显式 description → 入选 (CC :594 hasUserSpecifiedDescription)")
    void userSkill_withExplicitDescription_included(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "user-desc", "user-desc", "description: 显式描述");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("user-desc");
    }

    @Test
    @DisplayName("USER 技能带 whenToUse → 入选 (CC :594 whenToUse)")
    void userSkill_withWhenToUse_included(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "user-when", "user-when", "when_to_use: 当用户请求该能力时使用");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("user-when");
    }

    @Test
    @DisplayName("USER 技能无显式描述且无 whenToUse → 排除 (CC :594)")
    void userSkill_withoutDescOrWhenToUse_excluded(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "user-nodesc", "user-nodesc", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .doesNotContain("user-nodesc");
    }

    @Test
    @DisplayName("PLUGIN 技能带 whenToUse → 入选 (CC :596 loadedFrom==='plugin')")
    void pluginSkill_withWhenToUse_included(@TempDir Path tempDir) throws Exception {
        Command plugin = new Command();
        plugin.setName("plugin-when");
        plugin.setWhenToUse("当用户请求插件能力时使用");
        plugin.setSource(CommandSource.PLUGIN);
        plugin.setLoadedFrom(CommandLoadedFrom.PLUGIN);   // P2-21: 独立 loadedFrom 字段判别（CC :595-597）
        // P2-9 后 MCP 通道不再并入 getAllCommands；PLUGIN 命令经 FixtureRegistry 直接进命令集合
        SkillRegistry registry = new FixtureRegistry(List.of(plugin));

        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("plugin-when");
    }

    @Test
    @DisplayName("BUNDLED 源技能带显式描述 → 入选 (CC :597 loadedFrom==='bundled')")
    void bundledSkill_withDescription_included(@TempDir Path tempDir) throws Exception {
        Command bundled = new Command();
        bundled.setName("bundled-skill");
        bundled.setHasUserSpecifiedDescription(Boolean.TRUE);
        bundled.setSource(CommandSource.BUNDLED);
        bundled.setLoadedFrom(CommandLoadedFrom.BUNDLED); // P2-21: 独立 loadedFrom 字段判别（CC :595-597）
        SkillRegistry registry = new FixtureRegistry(List.of(bundled));

        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("bundled-skill");
    }

    @Test
    @DisplayName("BUILTIN 源永不入选（即使带 whenToUse + disableModelInvocation）· CC :593 source!=='builtin'")
    void builtinSource_excluded(@TempDir Path tempDir) throws Exception {
        Command builtin = new Command();
        builtin.setName("builtin-cmd");
        builtin.setWhenToUse("内置命令指引");
        builtin.setDisableModelInvocation(Boolean.TRUE);
        builtin.setSource(CommandSource.BUILTIN);
        SkillRegistry registry = new FixtureRegistry(List.of(builtin));

        // 先证 builtin-cmd 确实在命令集合（过滤输入真实存在，避免 doesNotContain '看起来绿'失去意图）
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .contains("builtin-cmd");
        // 若仅按 whenToUse/disableModelInvocation 过滤会入选；source==BUILTIN 强制排除
        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .doesNotContain("builtin-cmd");
    }

    @Test
    @DisplayName("disableModelInvocation=true 的 USER 技能仍入选 (CC :598)；MCP 源分离后永不入第二套过滤")
    void disableModelInvocationSkill_includedAndMcpSeparated(@TempDir Path tempDir) throws Exception {
        // 部分 A（CC :598 意图保留）：USER 技能 disableModelInvocation=true + whenToUse 仍入选斜杠命令技能
        // （第二套过滤含仅用户可用的技能），但被 getModelInvocableCommands 排除（commands.ts:569）。
        // 条件3（CC :594 hasUserSpecifiedDescription || whenToUse）需独立成立 → 补 when_to_use frontmatter。
        writeSkill(tempDir, "user-only", "user-only",
            "disable-model-invocation: true\nwhen_to_use: 用户手动触发的技能");
        // 部分 B（P2-9 分离语义）：MCP 源命令 live outside getCommands（commands.ts:541-546），
        // 分离后 MCP 不进 getAllCommands → 第二套过滤（getSlashCommandToolSkills）从数据源上就不含 MCP，
        // 即便带 disableModelInvocation=true 也永不入选。△ 折叠漂移（旧'Java MCP 已并入'）随 P2-9 消除。
        Command mcpDisabled = new Command();
        mcpDisabled.setName("mcp-user-only");
        mcpDisabled.setWhenToUse("用户手动触发的 MCP 技能");
        mcpDisabled.setDisableModelInvocation(Boolean.TRUE);
        mcpDisabled.setSource(CommandSource.MCP);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        injectMcpCommand(registry, mcpDisabled);

        // 部分 A：USER disableModelInvocation 技能入选斜杠命令技能（CC :598）
        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("user-only");
        // 部分 A：被 getModelInvocableCommands 排除（CC :569）
        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("user-only");
        // 部分 B：MCP 源分离后排除（getAllCommands 不含 MCP，第二套过滤无 MCP 可滤）
        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .doesNotContain("mcp-user-only");
        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("mcp-user-only");
        // 部分 B：getAllCommands 分离实证 —— MCP 命令不入本地聚合
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("mcp-user-only");
    }

    @Test
    @DisplayName("独立 memoize + refresh() 清空（P1-16 热更新失效入口）")
    void memoized_and_refresh_clears(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a", "when_to_use: 场景A");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        List<Command> first = registry.getSlashCommandToolSkills();
        assertThat(first).extracting(Command::getName).contains("skill-a");
        // 独立 memoize：同一次过滤结果实例
        assertThat(registry.getSlashCommandToolSkills()).isSameAs(first);

        // 追加带 whenToUse 的 skill-b：refresh() 前不可见、后可见（对齐 CC :586 memoize + 显式 clear）
        writeSkill(tempDir, "skill-b", "skill-b", "when_to_use: 场景B");
        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .doesNotContain("skill-b");

        registry.refresh();
        assertThat(registry.getSlashCommandToolSkills())
            .extracting(Command::getName)
            .contains("skill-b");
    }

    @Test
    @DisplayName("加载失败恒不抛 → 返回空列表 (CC commands.ts:600-605 never-throw 契约)")
    void loadFailure_returnsEmpty_notThrows() {
        // WHY（规则九 · 测试验证意图）：CC getSlashCommandToolSkills 用 try/catch 包裹 getCommands + filter ——
        // 技能加载失败（如 isCommandEnabled 门控 BooleanSupplier 抛错）不得把异常传播给调用方
        // （commands.ts:602 注释「skills are non-critical，防止技能加载失败拖垮整个系统」+ :605 return []）。
        // getSkillInfo（prompt.ts:221-241）依赖该恒不抛契约；若异常向上传播，即使调用方防御 catch 兜底
        // 返回 {0,0}，契约层面 getSlashCommandToolSkills 自身必须返回 []（对齐 CC :605），且失败解析值 []
        // 被 memoize 缓存（对齐 lodash memoize 缓存 Promise 解析结果，直至 refresh() 显式失效）。
        SkillRegistry registry = new ThrowingFixtureRegistry();

        // 改造前（RED 基线）：异常经 getAllCommands 传播 → 本行抛 RuntimeException → 用例 FAIL；
        // 改造后：try-catch 捕获 → log.warn + 缓存 [] + 返回空列表 → 用例 GREEN。
        assertThat(registry.getSlashCommandToolSkills()).isEmpty();
        // memoize 语义：失败解析值 [] 已缓存（对齐 CC memoize），二次调用仍空不抛
        assertThat(registry.getSlashCommandToolSkills()).isEmpty();
    }

    /** 静态 MCP 命令注入（隔离 MCP 源验证）· McpServerService 为具体类，子类覆写即可。 */
    private static final class StaticMcpServerService extends McpServerService {
        private final List<Command> commands;

        StaticMcpServerService(List<Command> commands) {
            this.commands = commands;
        }

        @Override
        public List<Command> getMcpSkillCommands() {
            return commands;
        }
    }
}
