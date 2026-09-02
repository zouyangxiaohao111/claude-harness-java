package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookMatcherEngine;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.application.agent.permission.hook.MatchedHook;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session MPL7 · feed 层（memoize feed + loadPluginHooks + clearAllCaches 级联）。
 *
 * <p>验证意图（规则九 · 测试验证意图而非行为）：
 * <ol>
 *   <li><b>单槽缓存</b>：连续两次 loadAllPluginsCacheOnly 只枚举一次 —— CC 无参 memoize 单槽
 *       （pluginLoader.ts:3137），启动消费方重复读 feed 不重扫磁盘。</li>
 *   <li><b>预热</b>：loadAllPlugins 完成后 cacheOnly 调用命中预热缓存不重枚举 —— CC :3106
 *        refresh 路径预热，下游 cacheOnly 消费方不再 plugin-cache-miss。</li>
 *   <li><b>miss 跳过</b>：sourcePath 缺失的 enabled 插件计入 plugin-cache-miss 跳过，不抛异常
 *       —— CC :2130-2138 语义。</li>
 *   <li><b>agents 缓存</b>：两插件各 1 agent → 2，二次命中缓存 —— loadAllEnabledAgents 经 feed。</li>
 *   <li><b>hooks 注册</b>：loadPluginHooks 注册插件 GenericHook 执行路径（DEL-CFG-B 后单轨,
 *       PLUGIN_HOOK bySource 存储已删除）；无 hooks.json 插件静默跳过
 *       （CC loadPluginHooks.ts:125 if(!plugin.hooksConfig) continue），不注册不抛错。</li>
 */
class PluginLoaderFeedTest {

    @TempDir
    Path tempDir;

    private InstalledPluginsManager manager;

    /** 枚举计数注册表 · 覆盖 list() 统计枚举次数（无 Mockito，POJO 直构）。 */
    static class CountingManager extends InstalledPluginsManager {
        int listCalls = 0;

        @Override
        public List<InstalledRecord> list() {
            listCalls++;
            return super.list();
        }
    }

    @BeforeEach
    void setUp() {
        manager = new CountingManager();
    }

    // ── 验收 1：无参单槽 memoize —— 连续两次仅枚举一次 ──

    @Test
    void twoCacheOnlyCallsEnumerateOnce() throws Exception {
        Path pluginRoot = Files.createDirectories(tempDir.resolve("p1"));
        manager.install("p1", "1.0.0", "marketplace", pluginRoot, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);

        PluginLoader.PluginLoadResult first = loader.loadAllPluginsCacheOnly();
        PluginLoader.PluginLoadResult second = loader.loadAllPluginsCacheOnly();

        assertEquals(1, loader.enumerationCount(),
            "连续两次 loadAllPluginsCacheOnly 必须仅枚举一次（CC 无参单槽 memoize, pluginLoader.ts:3137）");
        assertEquals(1, ((CountingManager) manager).listCalls,
            "注册表 list() 必须只被调用一次（cache 命中不再枚举）");
        assertEquals(first, second, "两次结果必须同一缓存实例");
        assertEquals(1, second.enabled().size());
    }

    // ── 验收 2：loadAllPlugins 完成后预热 cacheOnly 缓存 ──

    @Test
    void loadAllPluginsPrewarmsCacheOnlyCache() throws Exception {
        Path pluginRoot = Files.createDirectories(tempDir.resolve("p1"));
        manager.install("p1", "1.0.0", "marketplace", pluginRoot, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);

        PluginLoader.PluginLoadResult full = loader.loadAllPlugins();
        PluginLoader.PluginLoadResult cached = loader.loadAllPluginsCacheOnly();

        assertEquals(1, loader.enumerationCount(),
            "loadAllPlugins 预热后 loadAllPluginsCacheOnly 必须命中缓存不重枚举（CC pluginLoader.ts:3106）");
        assertEquals(full, cached, "预热缓存结果必须与全量加载一致");
    }

    // ── 验收 3：sourcePath 缺失的 enabled 插件被跳过计入 miss，不抛异常 ──

    @Test
    void enabledPluginWithoutSourcePathIsSkippedAsMiss() {
        Path pluginRoot = tempDir.resolve("real");
        try {
            Files.createDirectories(pluginRoot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        manager.install("real", "1.0.0", "marketplace", pluginRoot, null);
        // sourcePath 缺失（未记录安装路径）→ CC plugin-cache-miss
        manager.install("ghost", "1.0.0", "marketplace", null, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);

        PluginLoader.PluginLoadResult result = loader.loadAllPluginsCacheOnly();

        assertTrue(result.enabled().stream().anyMatch(p -> p.name().equals("real")),
            "有 sourcePath 的 enabled 插件必须进入 feed");
        assertFalse(result.enabled().stream().anyMatch(p -> p.name().equals("ghost")),
            "sourcePath 缺失的 enabled 插件必须跳过，不得进入 feed");
        assertTrue(result.errors().stream().anyMatch(e -> "plugin-cache-miss".equals(e.type())
                && "ghost".equals(e.plugin())),
            "缺失 sourcePath 必须计入 plugin-cache-miss error（CC :2130-2138）");
    }

    // ── 验收 4：loadAllEnabledAgents 两插件各 1 agent → 2，二次命中缓存 ──

    @Test
    void loadAllEnabledAgentsTwoPluginsTwoAgentsCached() throws Exception {
        Path rootA = Files.createDirectories(tempDir.resolve("pa"));
        Path agentsA = Files.createDirectories(rootA.resolve("agents"));
        Files.writeString(agentsA.resolve("helperA.md"),
            "---\nname: HelperA\ndescription: feed plugin A\n---\n\nbody");
        Path rootB = Files.createDirectories(tempDir.resolve("pb"));
        Path agentsB = Files.createDirectories(rootB.resolve("agents"));
        Files.writeString(agentsB.resolve("helperB.md"),
            "---\nname: HelperB\ndescription: feed plugin B\n---\n\nbody");
        manager.install("pa", "1.0.0", "marketplace", rootA, agentsA);
        manager.install("pb", "1.0.0", "marketplace", rootB, agentsB);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);

        List<com.nexusai.application.agent.subagent.AgentDefinition> agents =
            loader.loadAllEnabledAgents();
        List<com.nexusai.application.agent.subagent.AgentDefinition> cachedAgents =
            loader.loadAllEnabledAgents();

        assertEquals(2, agents.size(), "两插件各 1 agent → 2（CC loadPluginAgents.ts:234-331）");
        assertEquals(2, cachedAgents.size(), "二次调用命中缓存，结果一致");
        assertEquals(1, loader.enumerationCount(), "loadAllEnabledAgents 二次调用不得重枚举");
    }

    // ── 验收 5a：loadPluginHooks 注册 GenericHook 执行路径（DEL-CFG-B 后单轨）──

    @Test
    void loadPluginHooksRegistersPluginHookSource() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("hook-p"));
        Path hooksDir = Files.createDirectories(root.resolve("hooks"));
        Files.writeString(hooksDir.resolve("hooks.json"),
            "{ \"description\": \"test\", \"hooks\": { \"PreToolUse\": ["
                + " { \"matcher\": \".*\", \"hooks\": [\"echo hi\"] } ] } }");
        manager.install("hook-p", "1.0.0", "marketplace", root, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        HookRegistry registry = new HookRegistry();
        loader.setHookRegistry(registry);
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(new HooksSettings());
        loader.setHooksConfigSnapshot(snapshot);

        loader.loadPluginHooks();

        // [IMP-HOOKS-S1 DEL-CFG-B] PLUGIN_HOOK bySource 存储已删除 —— 断言改
        // registry.pluginHookNames()（GenericHook 执行路径）
        assertEquals(1, registry.pluginHookNames().size(),
            "loadPluginHooks 必须注册 1 个插件 GenericHook");
        assertTrue(registry.pluginHookNames().contains("plugin:hook-p:PreToolUse"),
            "hook 名约定 plugin:{pluginName}:{event}（CC PluginHookMatcher 上下文）");
    }

    // ── 验收 5b：loadPluginHooks 无 hooks.json 插件静默跳过（对齐 CC loadPluginHooks.ts:125）──

    @Test
    void loadPluginHooksSkipsPluginWithoutHooksJson() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("no-hooks"));
        manager.install("no-hooks", "1.0.0", "marketplace", root, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        HookRegistry registry = new HookRegistry();
        loader.setHookRegistry(registry);

        loader.loadPluginHooks(); // 不得抛错

        assertTrue(registry.pluginHookNames().isEmpty(),
            "无 hooks.json 插件不得注册 GenericHook 执行路径");
    }
    // ── 验收 6：clearAllCaches 后 feed 重枚举，禁用插件 hooks 被 prune ──

    @Test
    void clearAllCachesReenumeratesFeedAndPrunesDisabledPluginHooks() throws Exception {
        Path rootA = Files.createDirectories(tempDir.resolve("ha"));
        Files.createDirectories(rootA.resolve("hooks"));
        Files.writeString(rootA.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo a\"] } ] } }");
        Path rootB = Files.createDirectories(tempDir.resolve("hb"));
        Files.createDirectories(rootB.resolve("hooks"));
        Files.writeString(rootB.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo b\"] } ] } }");
        manager.install("ha", "1.0.0", "marketplace", rootA, null);
        manager.install("hb", "1.0.0", "marketplace", rootB, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        HookRegistry registry = new HookRegistry();
        loader.setHookRegistry(registry);
        loader.setHooksConfigSnapshot(new HooksConfigSnapshot(new HooksSettings()));

        loader.loadPluginHooks();
        assertEquals(2, registry.pluginHookNames().size(),
            "两插件各 1 GenericHook 注册");

        // 禁用 hb → feed 失效 → clearAllCaches → prune 移除 hb 的 hooks
        loader.disable("hb");
        PluginCacheUtils cacheUtils = new PluginCacheUtils();
        cacheUtils.setPluginLoader(loader);
        int beforeClear = loader.enumerationCount();
        cacheUtils.clearAllCaches();

        assertTrue(loader.enumerationCount() > beforeClear,
            "clearAllCaches 后 feed 必须重枚举（CC cacheUtils.ts:44-50 级联失效）");
        assertEquals(1, registry.pluginHookNames().size(),
            "GenericHook 执行路径同步 prune（CC loadPluginHooks.ts:179-204）");
        assertTrue(registry.pluginHookNames().contains("plugin:ha:PreToolUse"),
            "hb 的 GenericHook 必须移除");
        assertTrue(registry.pluginHookNames().stream().noneMatch(n -> n.contains("hb")),
            "禁用插件的 hook 名必须全部移除");
    }

    // ── 验收 7（IMP-HR-02 R-3）：registeredHookMatchers store 生命周期 —— reload 去重 + prune 剪除 ──

    /** 组装带引擎的 registry（getMatchingHooks 需 HookMatcherEngine 才能观察到 registered 源）。 */
    private static HookRegistry registryWithEngine() {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, new PermissionRuleValueParser()));
        return registry;
    }

    @Test
    void loadPluginHooksTwice_rebuildsRegisteredMatchers_notDuplicated() throws Exception {
        // WHY (IMP-HR-02 R-3): CC loadPluginHooks clear-then-register 原子换（loadPluginHooks.ts:147-148
        //   clearRegisteredPluginHooks + registerHookCallbacks）——每次加载清空 registered matcher store
        //   再全量重建。旧 Java clearPluginHooks 不触碰 registeredHookMatchers → 二次加载逐组追加 →
        //   同插件 matcher 重复 2 份。本测试用「两次加载间插件命令变更」使 dedupKey 不同（否则引擎去重
        //   会掩盖重复），断言二次加载后 getMatchingHooks 恰 1 份（无重复）。
        Path root = Files.createDirectories(tempDir.resolve("ha"));
        Path hooksDir = Files.createDirectories(root.resolve("hooks"));
        Path hooksJson = hooksDir.resolve("hooks.json");
        Files.writeString(hooksJson,
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo a\"] } ] } }");
        manager.install("ha", "1.0.0", "marketplace", root, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        HookRegistry registry = registryWithEngine();
        loader.setHookRegistry(registry);
        loader.setHooksConfigSnapshot(new HooksConfigSnapshot(new HooksSettings()));

        loader.loadPluginHooks();
        assertEquals(1, registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null)).size(),
            "首次加载后插件 matcher 恰 1 份");

        // 插件热更新：hooks.json 命令变更 → 再次 loadPluginHooks（模拟 LlmAgentLoop 每会话加载/热重载）
        Files.writeString(hooksJson,
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo a2\"] } ] } }");
        loader.loadPluginHooks();

        List<MatchedHook> matched = registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null));
        assertEquals(1, matched.size(),
            "二次 loadPluginHooks 后 registered matcher 必须仍 1 份（clear-then-register 原子换，CC loadPluginHooks.ts:147-148）");
        assertEquals("echo a2", ((com.nexusai.application.agent.permission.hook.CommandHook)
            matched.get(0).hook()).command(),
            "二次加载后应只剩新命令 matcher（旧 matcher 已清空）");
    }

    @Test
    void pruneRemovedPluginHooks_removesDisabledPluginRegisteredMatcher() throws Exception {
        // WHY (IMP-HR-02 R-3): CC pruneRemovedPluginHooks（loadPluginHooks.ts:179-204 survivors 重建）
        //   同步剪除 registered matcher store —— 禁用插件 matcher 残留会导致其 env hooks（CwdChanged/
        //   FileChanged）经 env 收集链继续发射。旧 Java prunePluginHooks 只剪 genericHooks，registered
        //   matcher 残留。
        Path rootA = Files.createDirectories(tempDir.resolve("ha"));
        Files.createDirectories(rootA.resolve("hooks"));
        Files.writeString(rootA.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo a\"] } ] } }");
        Path rootB = Files.createDirectories(tempDir.resolve("hb"));
        Files.createDirectories(rootB.resolve("hooks"));
        Files.writeString(rootB.resolve("hooks/hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \".*\", \"hooks\": [\"echo b\"] } ] } }");
        manager.install("ha", "1.0.0", "marketplace", rootA, null);
        manager.install("hb", "1.0.0", "marketplace", rootB, null);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        HookRegistry registry = registryWithEngine();
        loader.setHookRegistry(registry);
        loader.setHooksConfigSnapshot(new HooksConfigSnapshot(new HooksSettings()));

        loader.loadPluginHooks();
        assertEquals(2, registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null)).size(),
            "两插件各 1 matcher 注册 → matched 集 2 份");

        // 禁用 hb → feed 失效 → clearAllCaches → pruneRemovedPluginHooks → registered matcher 同步剪除
        loader.disable("hb");
        PluginCacheUtils cacheUtils = new PluginCacheUtils();
        cacheUtils.setPluginLoader(loader);
        cacheUtils.clearAllCaches();

        List<MatchedHook> matched = registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null));
        assertEquals(1, matched.size(),
            "禁用插件 hb 的 registered matcher 必须被剪除（CC loadPluginHooks.ts:179-204）");
        assertTrue(matched.stream().noneMatch(m -> m.hookSource().contains("hb")),
            "matched 集不得再包含 hb 的 matcher");
    }
}
