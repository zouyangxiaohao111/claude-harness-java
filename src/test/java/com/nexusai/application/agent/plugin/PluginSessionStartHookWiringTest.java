package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookSource;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Session MPL7-WIRE · loadPluginHooks 生产触发接线（装配级）。
 *
 * <p>验证意图（规则九 · 测试验证意图而非行为）：CC 在 setup.ts:326 启动预热 loadPluginHooks +
 * sessionStart.ts:59-65 于 SessionStart hooks 执行前 await loadPluginHooks，保证插件 hooks
 * 真实注册后才会触发（memoize 幂等）。Java 端 {@link LlmAgentLoop#loadPluginHooks()} 接线前
 * 零生产引用 → 插件 hooks 永不触发，即使 PluginLoaderFeedTest 已验证 feed 层正确。
 * 本测试钉死<b>生产装配</b>：真实 {@link LlmAgentLoop} + 真实 {@link PluginLoader} + 真实
 * {@link HookRegistry}/{@link HooksSettings}，经 {@code run(RunRequest.forTest)} 驱动一次真实
 * 会话启动，断言插件 hooks 已注册到共享注册中心。若 run() 忘记调 loadPluginHooks 或注入断链，
 * 注册中心为空 → RED。
 */
@DisplayName("[MPL7-WIRE] LlmAgentLoop.run 生产会话启动触发 loadPluginHooks 装配插件 hooks")
class PluginSessionStartHookWiringTest {

    @TempDir
    Path tempDir;

    private InstalledPluginsManager manager;
    private HookRegistry registry;

    @BeforeEach
    void setUp() {
        manager = new InstalledPluginsManager();
        registry = new HookRegistry();
    }

    /**
     * 装配级：真实 LlmAgentLoop.run() → SessionStart 前调 loadPluginHooks → 插件 hooks 真实注册。
     *
     * <p>RED 条件：① run() 未在 SessionStart 路径调 loadPluginHooks（注册中心空）；
     * ② LlmAgentLoop.pluginLoader 注入断链（setPluginLoader 未生效）→ 同左。
     */
    @Test
    @DisplayName("run() 生产会话启动后插件 hooks 已注册（对齐 CC setup.ts:326 / sessionStart.ts:59-65）")
    void run_productionSessionStart_registersPluginHooks() throws Exception {
        // ── 1. 真实磁盘插件：hooks/hooks.json 声明 1 个 PreToolUse hook ──
        Path root = Files.createDirectories(tempDir.resolve("hook-p"));
        Files.createDirectories(root.resolve("hooks"));
        Files.writeString(root.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": ["
                + " { \"matcher\": \".*\", \"hooks\": [\"echo hi\"] } ] } }");
        manager.install("hook-p", "1.0.0", "marketplace", root, null);

        // ── 2. 真实 PluginLoader（feed 层注入 manager + registry + snapshot）──
        // [IMP-HOOKS-S1 DEL-CFG-B] setHooksSettings 注入已删除（PLUGIN_HOOK 源存储移除）
        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        loader.setHookRegistry(registry);
        loader.setHooksConfigSnapshot(new HooksConfigSnapshot(new HooksSettings()));

        // ── 3. 真实 LlmAgentLoop + mocked provider（首调 stop 纯文本 → loop 正常退出）──
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from test provider");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello from test provider", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setPluginLoader(loader);

        // ── 4. 驱动一次真实生产会话启动 ──
        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));
        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();

        // ── 5. 断言：插件 hooks 已真实注册（SessionStart 前 loadPluginHooks 生效）──
        // [IMP-HOOKS-S1 DEL-CFG-B] PLUGIN_HOOK bySource 存储已删除 —— 断言改
        // registry.pluginHookNames()（GenericHook 执行路径）
        assertThat(registry.pluginHookNames())
            .as("run() 后 GenericHook 执行路径必须已注册插件 hook（CC loadPluginHooks clear-then-register）")
            .contains("plugin:hook-p:PreToolUse");
    }

    /**
     * 控制组：PluginLoader 未注入（null）时 run() 不得抛错、不注册插件 hooks ——
     * 确保接线是"可选注入 + 容错"，无 PluginLoader bean 场景（单测/老路径）不破坏会话启动。
     */
    @Test
    @DisplayName("PluginLoader 未注入时 run() 容错不注册插件 hooks（向后兼容）")
    void run_withoutPluginLoader_toleratesAndSkips() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory); // 不注入 PluginLoader
        assertThatCode(() -> loop.run(RunRequest.forTest("hello", "test-model", null)))
            .as("无 PluginLoader bean 时 run() 必须正常完成（null 跳过, 向后兼容）")
            .doesNotThrowAnyException();
    }

    /**
     * bare 模式会话启动跳过 loadPluginHooks（插件目录遍历）。
     *
     * <p>WHY（规则九 · 验证意图）：CC setup.ts:315-329 {@code skipPluginPrefetch = ... || isBareMode()}
     * → bare 跳过 loadPluginHooks；sessionStart.ts:47 processSessionStartHooks 入口
     * {@code if (isBareMode()) return []}。对齐注释原话 "no point loading plugin hooks that'll
     * never run" —— bare 下 HookRegistry executeHooks 入口短路（hooks.ts:1981-1983），注册了也不执行。
     * Java 会话级判定（bareMode 随会话走，V33 列）：bare 会话不得触发插件 hooks.json 目录读取。
     * 变异点：删除 run() 的 bare 门控 → loadPluginHooks 被调 → 插件 hook 注册 → 红。
     */
    @Test
    @DisplayName("bare 模式会话启动跳过 loadPluginHooks（CC setup.ts:326 skipPluginPrefetch isBareMode）")
    void run_bareMode_skipsPluginHookLoading() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("hook-bare"));
        Files.createDirectories(root.resolve("hooks"));
        Files.writeString(root.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": ["
                + " { \"matcher\": \".*\", \"hooks\": [\"echo hi\"] } ] } }");
        manager.install("hook-bare", "1.0.0", "marketplace", root, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        loader.setHookRegistry(registry);
        loader.setHooksConfigSnapshot(new HooksConfigSnapshot(new HooksSettings()));

        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setPluginLoader(loader);

        try {
            // 全局桥 bare=true：forTest sessionId=null → isBareMode(null) 回落全局判定命中
            new MemoryBareModeConfig(true);
            AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));
            assertThat(state).as("bare 模式 run() 必须正常完成").isNotNull();
            assertThat(registry.pluginHookNames())
                .as("bare 模式必须跳过 loadPluginHooks（CC setup.ts:326 isBareMode skipPluginPrefetch）→ 插件 hook 不得注册")
                .doesNotContain("plugin:hook-bare:PreToolUse");
        } finally {
            MemoryBareModeConfig.reset();
        }
    }
}
