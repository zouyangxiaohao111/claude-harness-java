package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [RV-11 · REV-FIX-2] 生产输入源接线测试 —— 「CLI/settings 输入 → 初始 mode 非恒 DEFAULT」。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：RV-11 复验发现 LlmAgentLoop 生产路径
 * （buildBaseToolUseContext）用 1 参 {@code Input.empty()} → 初始 mode 恒 DEFAULT +
 * isBypassPermissionsModeAvailable=false，CLI/settings 输入源在生产无效。本测试钉死修复后
 * 的<b>整条接线链</b>：
 * <ol>
 *   <li><b>6 参重载生效</b>：{@link PermissionContextBuilder#buildPermissionContext(
 *       AgentState, boolean, PermissionMode, boolean, InitialPermissionModeResolver.Input,
 *       InitialPermissionModeResolver.Config)} 传入真实 Input → mode 非恒 DEFAULT；</li>
 *   <li><b>settings 源合并</b>：{@link InitialPermissionModeSource} 读磁盘 3 层 settings meta
 *       （local &gt; project &gt; user，对齐 CC getInitialSettings）；</li>
 *   <li><b>LlmAgentLoop 生产接缝</b>：RunRequest.permissionModeCli → doRun 组装 Input →
 *       base TUC → per-turn TUC 的 {@code permissionMode()} 非 DEFAULT。</li>
 * </ol>
 * 任一环节回退 Input.empty() 恒 DEFAULT，测试即 RED。
 */
class RevFix2ProductionInputWiringTest {

    private static final InitialPermissionModeResolver.Config DEFAULT_CFG =
        InitialPermissionModeResolver.Config.defaults();

    private static final AgentState STATE = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);

    // ═══════════════ ① 6 参重载：CLI/settings 输入 → 初始 mode 非恒 DEFAULT ═══════════════

    @Test
    @DisplayName("[6 参] Input(plan) → mode=PLAN（修复前 Input.empty() 恒 DEFAULT）")
    void sixParam_cliPermissionModePlan_nonDefault() {
        ToolPermissionContext ctx = build(new InitialPermissionModeResolver.Input(
            "plan", false, null, false));
        assertThat(ctx.mode())
            .as("6 参重载必须使 CLI --permission-mode 在生产生效（非 Input.empty() → 非恒 DEFAULT）")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("[6 参] Input(null,true) → mode=BYPASS_PERMISSIONS + bypass 可用")
    void sixParam_dangerouslySkip_bypassAvailable() {
        ToolPermissionContext ctx = build(new InitialPermissionModeResolver.Input(
            null, true, null, false));
        assertThat(ctx.mode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("dangerouslySkipPermissions 且未被禁用门关闭 → bypass 可用（CC permissionSetup.ts:939-944）")
            .isTrue();
    }

    @Test
    @DisplayName("[6 参] settings.defaultMode=acceptEdits → mode=ACCEPT_EDITS（settings 源生效）")
    void sixParam_settingsDefaultMode_acceptEdits() {
        ToolPermissionContext ctx = build(new InitialPermissionModeResolver.Input(
            null, false, "acceptEdits", false));
        assertThat(ctx.mode())
            .as("settings.permissions.defaultMode 必须作为初始 mode 源生效（CC permissionSetup.ts:743）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("[6 参] dangerouslySkip + settings.disableBypass='disable' → 降级 DEFAULT + bypass 禁用")
    void sixParam_bypassDisabledBySettings_downgraded() {
        ToolPermissionContext ctx = build(new InitialPermissionModeResolver.Input(
            null, true, null, true));
        assertThat(ctx.mode())
            .as("bypass 被 settings.disableBypassPermissionsMode 禁用门关闭 → 降级 default（CC :778-787）")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("禁用门关闭 → isBypassPermissionsModeAvailable=false")
            .isFalse();
    }

    // ═══════════════ ② InitialPermissionModeSource：settings 磁盘 meta 合并 ═══════════════

    @Test
    @DisplayName("[source] 3 层 settings 合并：local > project（默认 mode）+ disableBypass OR")
    void source_mergesLocalOverProject(@TempDir Path nexusaiHome) throws IOException {
        // project 层：defaultMode=acceptEdits
        Files.createDirectories(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()));
        Files.writeString(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json"),
            "{\"permissions\":{\"defaultMode\":\"acceptEdits\"}}");
        // local 层：defaultMode=plan + disableBypassPermissionsMode='disable'
        Files.writeString(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json"),
            "{\"permissions\":{\"defaultMode\":\"plan\",\"disableBypassPermissionsMode\":\"disable\"}}");

        InitialPermissionModeSource source = newSource(nexusaiHome);
        InitialPermissionModeResolver.Input input = source.resolveInput(null, false);

        // local 覆盖 project（CC 覆盖序 local > project > user，constants.ts:4-16）
        assertThat(input.settingsDefaultMode())
            .as("local 层 defaultMode=plan 必须覆盖 project 层 acceptEdits")
            .isEqualTo("plan");
        // disableBypass 任一层设置 'disable' 即禁用（types.ts:67-70 唯一合法值）
        assertThat(input.settingsDisableBypassPermissionsMode())
            .as("local 层 disableBypassPermissionsMode='disable' → settings 侧禁用门打开")
            .isTrue();

        InitialPermissionModeResolver.Result r = InitialPermissionModeResolver.resolve(input, DEFAULT_CFG);
        assertThat(r.mode())
            .as("settings.defaultMode=plan → 初始 mode PLAN（settings 源非恒 DEFAULT）")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("[source] project 层兜底：local 缺失时取 project.defaultMode")
    void source_projectFallsBackWhenLocalAbsent(@TempDir Path nexusaiHome) throws IOException {
        Files.createDirectories(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()));
        Files.writeString(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json"),
            "{\"permissions\":{\"defaultMode\":\"acceptEdits\"}}");

        InitialPermissionModeSource source = newSource(nexusaiHome);
        InitialPermissionModeResolver.Input input = source.resolveInput(null, false);

        assertThat(input.settingsDefaultMode()).isEqualTo("acceptEdits");
        assertThat(InitialPermissionModeResolver.resolve(input, DEFAULT_CFG).mode())
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    // ═══════════════ ③ LlmAgentLoop 生产接缝：RunRequest 输入 → base/per-turn TUC 非 DEFAULT ═══════════════

    @Test
    @DisplayName("[loop 接缝] RunRequest.permissionModeCli=plan → per-turn TUC permissionMode=PLAN")
    void loop_runWithPermissionModeCli_perTurnTucNonDefault() throws Exception {
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(tucRef);
        // 注入真实 PermissionContextBuilder（生产 @Autowired(required=false) 路径等价物）
        injectPermissionContextBuilder(loop, new PermissionContextBuilder());

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session(
            "hello", sessionUuid, null, ProviderConfig.empty(), "test-model",
            null, null, null, "plan", false, null));

        assertThat(state).isNotNull();
        assertThat(tucRef.get())
            .as("per-turn TUC 必须非 null（base TUC 完整构造）")
            .isNotNull();
        assertThat(tucRef.get().permissionMode())
            .as("RunRequest.permissionModeCli=plan → 初始 mode 解析为 PLAN（非恒 DEFAULT，RV-11 接线生效）")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("[loop 接缝] 无 CLI/settings 输入 → per-turn TUC permissionMode=DEFAULT（空输入回落）")
    void loop_runWithoutInput_defaultMode() throws Exception {
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(tucRef);
        injectPermissionContextBuilder(loop, new PermissionContextBuilder());

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session(
            "hello", sessionUuid, null, ProviderConfig.empty(), "test-model",
            null, null, null, null, false, null));

        assertThat(state).isNotNull();
        assertThat(tucRef.get()).isNotNull();
        assertThat(tucRef.get().permissionMode())
            .as("无输入 → 回落 DEFAULT（与旧行为一致，非回归）")
            .isEqualTo(PermissionMode.DEFAULT);
    }

    // ── helpers ──────────────────────────────────────────────

    /** 6 参重载直接调用（真实 PermissionContextBuilder，无 loader）。 */
    private static ToolPermissionContext build(InitialPermissionModeResolver.Input input) {
        return new PermissionContextBuilder().buildPermissionContext(
            STATE, false, null, false, input, DEFAULT_CFG);
    }

    /** 真实 source：注入临时 nexusaiHome（user 层读真实 user.home，本机无 settings.json → EMPTY）。 */
    private static InitialPermissionModeSource newSource(Path nexusaiHome) {
        return new InitialPermissionModeSource(
            new SettingsJsonParser(new ObjectMapper(), new PermissionRuleValueParser()),
            () -> nexusaiHome.toString());
    }

    /** 装配真实 loop + mocked provider，捕获 per-turn TUC（镜像 G1 主线程可达性测试模式）。 */
    private static LlmAgentLoop newLoop(AtomicReference<ToolUseContext> tucRef) {
        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        doAnswer(inv -> {
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from RV-11 wiring");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return loop;
    }

    /** 反射注入私有字段 permissionContextBuilder（无 setter，对齐既有测试惯例）。 */
    private static void injectPermissionContextBuilder(LlmAgentLoop loop, PermissionContextBuilder builder)
            throws Exception {
        java.lang.reflect.Field f = LlmAgentLoop.class.getDeclaredField("permissionContextBuilder");
        f.setAccessible(true);
        f.set(loop, builder);
    }
}
