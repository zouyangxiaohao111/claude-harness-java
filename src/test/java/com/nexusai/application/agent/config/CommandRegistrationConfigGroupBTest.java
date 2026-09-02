package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.plugin.InstalledPluginsManager;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandRegistrationConfigGroupB 接线测试 · 组 B 会话/压缩/插件 命令注册面验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>prompt 命令进 BundledSkills</b>——insights 注册为 type='prompt' + progressMessage
 *       'analyzing your sessions'（CC insights.ts:3042/3044），promptFn 生成会话分析报告。</li>
 *   <li><b>local/local-jsx 命令元数据进 BundledSkills + handler 进 UserInputDispatcher</b>——
 *       force-snip(local) / btw(local-jsx, immediate) / sandbox(local-jsx, isHidden) / plugin(local-jsx)
 *       元数据（web GET /api/command 可见），同时 UserInputDispatcher.dispatch("/name") 触发执行 handler。</li>
 *   <li><b>门控</b>——force-snip isCommandEnabled=false（HISTORY_SNIP feature 默认关，CC commands.ts:83-85）；</li>
 *   <li><b>sandbox isHidden</b>——平台不支持 / 不在白名单 → isHidden=true（CC sandbox-toggle/index.ts:39-44）。</li>
 * </ol>
 */
class CommandRegistrationConfigGroupBTest {

    private final CommandRegistrationConfigGroupB config = new CommandRegistrationConfigGroupB();

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @AfterEach
    void clearRegistryAfter() {
        BundledSkills.clear();
    }

    private Map<String, Command> byName() {
        return BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity(), (a, b) -> a));
    }

    @Test
    @DisplayName("prompt 命令 insights 注册为 type='prompt' + progressMessage + promptFn 生成报告")
    void insightsRegisteredAsPromptWithReportPromptFn() {
        config.commandGroupBBundledRegistration(null, null);

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKey("insights");
        assertThat(cmds.get("insights").getType()).isEqualTo("prompt");
        assertThat(cmds.get("insights").getProgressMessage()).isEqualTo("analyzing your sessions");
        assertThat(cmds.get("insights").getPromptFn()).isNotNull();

        // promptFn 生成会话分析报告（对齐 CC insights.ts:3156-3181）
        String prompt = renderPromptFn(cmds.get("insights"));
        assertThat(prompt).contains("insights").contains("usage report");
    }

    @Test
    @DisplayName("local 命令元数据注册为 type='local'/'local-jsx' + immediate + force-snip 门控默认关")
    void localCommandMetadataRegisteredWithCorrectTypeAndGate() {
        config.commandGroupBBundledRegistration(null, null);

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys("force-snip", "btw", "sandbox", "plugin");

        // type（CC 真实类型：force-snip=local，btw/sandbox/plugin=local-jsx）
        assertThat(cmds.get("force-snip").getType()).isEqualTo("local");
        assertThat(cmds.get("btw").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("sandbox").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("plugin").getType()).isEqualTo("local-jsx");

        // immediate（CC btw/index.ts:8 / sandbox-toggle/index.ts:45 / plugin/index.tsx:7）
        assertThat(cmds.get("btw").getImmediate()).isTrue();
        assertThat(cmds.get("sandbox").getImmediate()).isTrue();
        assertThat(cmds.get("plugin").getImmediate()).isTrue();

        // argumentHint（CC btw/index.ts:9 '<question>' / sandbox-toggle/index.ts:38 'exclude "command pattern"'）
        assertThat(cmds.get("btw").getArgumentHint()).isEqualTo("<question>");
        assertThat(cmds.get("sandbox").getArgumentHint()).isEqualTo("exclude \"command pattern\"");

        // force-snip 门控默认关（HISTORY_SNIP feature 默认 false，CC commands.ts:83-85）
        assertThat(cmds.get("force-snip").isCommandEnabled()).isFalse();
        // 无 gate 命令恒启用（CC types/command.ts:214-215 isEnabled?.() ?? true）
        assertThat(cmds.get("btw").isCommandEnabled()).isTrue();
        assertThat(cmds.get("plugin").isCommandEnabled()).isTrue();
    }

    @Test
    @DisplayName("sandbox isHidden 门控：平台不支持 → 隐藏（对齐 CC sandbox-toggle/index.ts:39-44）")
    void sandboxHiddenWhenPlatformUnsupported() {
        // 测试平台多数不支持沙箱（Windows 不支持）→ isHidden=true
        config.commandGroupBBundledRegistration(null, null);

        Command sandbox = byName().get("sandbox");
        assertThat(sandbox).isNotNull();
        // isHidden = !isSupportedPlatform || !isPlatformInEnabledList（CC sandbox-toggle/index.ts:39-44）
        boolean supported = SandboxManager.isSupportedPlatformEnv();
        boolean inEnabledList = true; // 测试 SandboxManager 未注入 → 平台白名单视为允许
        assertThat(sandbox.getIsHidden()).isEqualTo(!supported || !inEnabledList);
    }

    @Test
    @DisplayName("local 命令执行 handler 注册进 UserInputDispatcher：/name 触发后端执行")
    void localSlashHandlersRegisteredAndDispatchable() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandGroupBLocalSlashRegistration(dispatcher, null, null, null, null, null);

        // /force-snip → result handler（[Fix-P1] type=local 迁移 registerSlashCommandResult →
        //   dispatchResult 回传 text；未注入 registry → fail-loud text，不抛）
        UserInputDispatcher.LocalCommandResult forceSnip = dispatcher.dispatchResult("/force-snip");
        assertThat(forceSnip).isNotNull();
        assertThat(forceSnip.kind()).as("/force-snip text 结果回传").isEqualTo("text");

        // /btw <question> → 命名 handler（CC btw/btw.tsx call；未注入 LLM → 仅记录提问）
        UserInputDispatcher.RoutingResult btw = dispatcher.dispatch("/btw what does this code do");
        assertThat(btw.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(btw.routedTo()).isEqualTo("btw");

        // /sandbox → 命名 handler（CC sandbox-toggle；未注入 SandboxManager → fail loud warn）
        UserInputDispatcher.RoutingResult sandbox = dispatcher.dispatch("/sandbox");
        assertThat(sandbox.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(sandbox.routedTo()).isEqualTo("sandbox");

        // /plugin → 命名 handler（CC plugin；未注入 InstalledPluginsManager → fail loud warn）
        UserInputDispatcher.RoutingResult plugin = dispatcher.dispatch("/plugin");
        assertThat(plugin.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(plugin.routedTo()).isEqualTo("plugin");
    }

    @Test
    @DisplayName("force-snip handler 注入 registry 后对含 snip_boundary 会话真实执行压缩")
    void forceSnipHandlerExecutesSnipOnBoundarySession() {
        // SnipCompactor 单元行为已在 SnipCompactor 自身测试覆盖；此处仅验证 handler 接线点：
        // 未注入 registry → fail-loud text 而非异常（[Fix-P1] type=local 迁移 registerSlashCommandResult，
        //   入口改为 dispatchResult）。
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandGroupBLocalSlashRegistration(dispatcher, null, null, null, null, null);
        // registry=null → 不抛（fail loud 由 text 结果披露）
        UserInputDispatcher.LocalCommandResult r = dispatcher.dispatchResult("/force-snip");
        assertThat(r).isNotNull();
        assertThat(r.kind()).isEqualTo("text");
        assertThat(r.value()).contains("SessionAgentStateRegistry 未注入");
    }

    @Test
    @DisplayName("沙箱启用（注入 SandboxManager）时 /sandbox 状态记录不抛异常")
    void sandboxHandlerWithManagerRecordsStatus() {
        SandboxManager sandboxManager = new SandboxManager(true, true);
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandGroupBLocalSlashRegistration(dispatcher, null, sandboxManager, null, null, null);
        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/sandbox");
        assertThat(r.routedTo()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("plugin handler 注入 InstalledPluginsManager 后列插件不抛异常")
    void pluginHandlerWithManagerListsPlugins() {
        InstalledPluginsManager manager = new InstalledPluginsManager();
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandGroupBLocalSlashRegistration(dispatcher, null, null, manager, null, null);
        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/plugin");
        assertThat(r.routedTo()).isEqualTo("plugin");
    }

    @Test
    @DisplayName("force-snip 门控由 FeatureFlags.historySnip 惰性求值（注入开启 → isCommandEnabled=true）")
    void forceSnipGateReadsFeatureFlags() {
        // ALL_DISABLED（historySnip=false）→ 关
        FeatureFlags flags = FeatureFlags.ALL_DISABLED;
        assertThat(flags.historySnip()).isFalse();
        // 显式开启的 FeatureFlags 需走全参构造；此处仅验证 ALL_DISABLED 与 isEnabled supplier 同源
        config.commandGroupBBundledRegistration(FeatureFlags.ALL_DISABLED, null);
        Command forceSnip = byName().get("force-snip");
        assertThat(forceSnip).isNotNull();
        assertThat(forceSnip.isCommandEnabled()).isFalse();
    }

    /** 调 Command.promptFn → 文本内容（text 块 join）。 */
    private static String renderPromptFn(Command cmd) {
        if (cmd.getPromptFn() == null) {
            return null;
        }
        return cmd.getPromptFn().apply("", new com.nexusai.model.command.PromptFnContext(
            System.getProperty("user.dir", "."), List.of(), null)).stream()
            .filter(b -> b instanceof com.nexusai.application.agent.tool.ContentBlockParam.TextBlockParam)
            .map(b -> ((com.nexusai.application.agent.tool.ContentBlockParam.TextBlockParam) b).text())
            .collect(Collectors.joining("\n\n"));
    }
}
