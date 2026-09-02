package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.MultiSourceHooksConfigLoader;
import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/**
 * [IMP-HOOKS-S5 D-04] SettingsFileChangeWatcher 聚焦测试 · 对齐 CC changeDetector.ts
 * （settingSourceToConfigChangeSource :252-266 + handleChange :268-302 + hasBlockingResult
 * :296-299 + executeConfigChangeHooks policy 非阻断 hooks.ts:4234-4236）。
 *
 * <p>覆盖：4 路径 → source 映射（user→project→local→policy 优先序）、阻断跳过 reload、
 * policy_settings 恒不阻断、非候选路径（skills 等）不触发。
 */
@DisplayName("[IMP-HOOKS-S5 D-04] SettingsFileChangeWatcher：source 映射 + 阻断门 + policy 豁免")
class SettingsFileChangeWatcherTest {

    @TempDir
    Path tempDir;

    /** 4 路径 → source 精确映射（CC getSourceForPath 精确匹配 + SETTING_SOURCES 优先序）. */
    @Test
    @DisplayName("4 路径 → user_settings/project_settings/local_settings/policy_settings")
    void sourceMapping_fourPaths() {
        Path userSettings = tempDir.resolve("user").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Path projectSettings = tempDir.resolve("proj").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Path localSettings = tempDir.resolve("proj").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json");
        Path policySettings = tempDir.resolve("policy").resolve("managed.json");

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath(policySettings.toString());

        assertThat(watcher.sourceFor(userSettings)).isEqualTo("user_settings");
        assertThat(watcher.sourceFor(projectSettings)).isEqualTo("project_settings");
        assertThat(watcher.sourceFor(localSettings)).isEqualTo("local_settings");
        assertThat(watcher.sourceFor(policySettings)).isEqualTo("policy_settings");
    }

    /** user.home == nexusai.home 同文件 → user 优先（CC SETTING_SOURCES find 序）. */
    @Test
    @DisplayName("user/project 同路径 → user_settings 优先（CC find 序）")
    void sourceMapping_sameFile_userWins() {
        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.toString());
        watcher.setNexusaiHome(tempDir.toString());

        Path shared = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        assertThat(watcher.sourceFor(shared)).isEqualTo("user_settings");
    }

    /** 非候选文件（同目录无关文件 / skills 路径）→ null（skills 归 SkillChangeDetector）. */
    @Test
    @DisplayName("非候选路径 → null（skills 等不触发，归 SkillChangeDetector）")
    void sourceMapping_unrelatedFile_null() {
        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath("");

        assertThat(watcher.sourceFor(tempDir.resolve("skills").resolve("a").resolve("SKILL.md"))).isNull();
        assertThat(watcher.sourceFor(tempDir.resolve("other.json"))).isNull();
    }

    /** 阻断（exit 2 语义）→ 跳过 reload（CC hasBlockingResult → return，changeDetector.ts:296-299）. */
    @Test
    @DisplayName("阻断 hook → reload 被跳过（CC changeDetector.ts:296-299）")
    void blockingHook_skipsReload() {
        Path userSettings = tempDir.resolve("user").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        AtomicInteger reloadCalls = new AtomicInteger();
        HookRegistry registry = new HookRegistry();
        registry.register("blocker",
            event -> GenericHook.HookResult.stop("blocked", "config change rejected"),
            HookEventType.CONFIG_CHANGE);

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath("");
        watcher.setHookRegistry(registry);
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        doAnswer(inv -> {
            reloadCalls.incrementAndGet();
            return null;
        }).when(loader).updateHooksConfigSnapshot();
        watcher.setHooksConfigLoader(loader);

        watcher.handleChange(userSettings);

        assertThat(reloadCalls.get())
            .as("D-04: 阻断结果必须跳过 reload（CC hasBlockingResult → return）")
            .isZero();
    }

    /** 非阻断 → reload 被调用（CC fanOut 等价）. */
    @Test
    @DisplayName("非阻断 → updateHooksConfigSnapshot 被调用（CC fanOut）")
    void nonBlocking_reloadCalled() {
        Path localSettings = tempDir.resolve("proj").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json");
        AtomicInteger reloadCalls = new AtomicInteger();
        HookRegistry registry = new HookRegistry();
        registry.register("audit",
            event -> GenericHook.HookResult.proceed(),
            HookEventType.CONFIG_CHANGE);

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath("");
        watcher.setHookRegistry(registry);
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        doAnswer(inv -> {
            reloadCalls.incrementAndGet();
            return null;
        }).when(loader).updateHooksConfigSnapshot();
        watcher.setHooksConfigLoader(loader);

        watcher.handleChange(localSettings);

        assertThat(reloadCalls.get()).isEqualTo(1);
    }

    /** policy_settings 恒不阻断（CC hooks.ts:4234-4236 blocked=false）→ reload 照常. */
    @Test
    @DisplayName("policy_settings 阻断 hook → reload 不被跳过（CC :4234-4236 强制非阻断）")
    void policySource_blockingHook_neverBlocks() {
        Path policySettings = tempDir.resolve("policy").resolve("managed.json");
        AtomicInteger reloadCalls = new AtomicInteger();
        HookRegistry registry = new HookRegistry();
        registry.register("policy-blocker",
            event -> GenericHook.HookResult.stop("blocked", "policy change rejected"),
            HookEventType.CONFIG_CHANGE);

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath(policySettings.toString());
        watcher.setHookRegistry(registry);
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        doAnswer(inv -> {
            reloadCalls.incrementAndGet();
            return null;
        }).when(loader).updateHooksConfigSnapshot();
        watcher.setHooksConfigLoader(loader);

        watcher.handleChange(policySettings);

        assertThat(reloadCalls.get())
            .as("D-04: policy_settings 恒不阻断（企业管控文件不可被 hook 阻塞，CC :4234-4236）")
            .isEqualTo(1);
    }

    /** 真实文件系统集成：目录含已存在 settings 文件 → 变更 → reload + ConfigChange hook 发射. */
    @Test
    @DisplayName("集成: 真实文件变更 → ConfigChange hook 发射 + reload（WatchService 链路）")
    void realFs_change_firesHookAndReload() throws Exception {
        Path userHomeDir = Files.createDirectories(tempDir.resolve("user").resolve(NexusaiPaths.getProjectDirName()));
        Path userSettings = userHomeDir.resolve("settings.json");
        Files.writeString(userSettings, "{\"hooks\":{}}");
        AtomicInteger reloadCalls = new AtomicInteger();
        AtomicReference<String> capturedSource = new AtomicReference<>();
        HookRegistry registry = new HookRegistry();
        registry.register("cfg-audit", event -> {
            capturedSource.set((String) event.data().get("source"));
            return GenericHook.HookResult.proceed();
        }, HookEventType.CONFIG_CHANGE);

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath("");
        watcher.setHookRegistry(registry);
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        doAnswer(inv -> {
            reloadCalls.incrementAndGet();
            return null;
        }).when(loader).updateHooksConfigSnapshot();
        watcher.setHooksConfigLoader(loader);
        watcher.initialize();

        Files.writeString(userSettings, "{\"hooks\":{\"PreToolUse\":[]}}");

        long deadline = System.currentTimeMillis() + 5000;
        while ((reloadCalls.get() < 1 || capturedSource.get() == null)
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(reloadCalls.get())
            .as("文件变更必须触发 reload（CC handleChange → fanOut）")
            .isGreaterThanOrEqualTo(1);
        assertThat(capturedSource.get())
            .as("ConfigChange hook 必须收到 user_settings source（CC settingSourceToConfigChangeSource）")
            .isEqualTo("user_settings");
    }

    // ── [H-WF1-01] 插件 hook 热重载（对齐 CC loadPluginHooks.ts:255-287 setupPluginHookHotReload）──

    /** policy 变更且插件相关快照变化 → 每次变化均重载（CC :274-284）. */
    @Test
    @DisplayName("[H-WF1-01] policy 变更 + 快照变化 → 插件 hook 重载（CC :274-284）")
    void pluginReload_policySettings_snapshotChanged_reloads() {
        Path policySettings = tempDir.resolve("policy").resolve("managed.json");
        AtomicReference<String> snapshot = new AtomicReference<>("{\"enabledPlugins\":{\"a@x\":true}}");
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        PluginLoader pluginLoader = mock(PluginLoader.class);
        when(loader.pluginAffectingSettingsSnapshot()).thenAnswer(inv -> snapshot.get());

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath(policySettings.toString());
        watcher.setHooksConfigLoader(loader);
        watcher.setPluginLoader(pluginLoader);

        watcher.handleChange(policySettings); // 初始 null != v1 → 重载, last=v1
        snapshot.set("{\"enabledPlugins\":{\"a@x\":false}}"); // enabledPlugins 变化
        watcher.handleChange(policySettings); // v2 != v1 → 重载

        // H-WF1-01: 每次插件相关快照变化必须重载插件 hook（CC :280-284）
        verify(pluginLoader, times(2)).refreshActivePlugins();
    }

    /** policy 变更但插件相关快照未变 → 跳过重载（CC :267-272）. */
    @Test
    @DisplayName("[H-WF1-01] policy 变更但快照未变 → 跳过重载（CC :267-272）")
    void pluginReload_policySettings_sameSnapshot_skips() {
        Path policySettings = tempDir.resolve("policy").resolve("managed.json");
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        PluginLoader pluginLoader = mock(PluginLoader.class);
        when(loader.pluginAffectingSettingsSnapshot()).thenReturn("same-snapshot");

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath(policySettings.toString());
        watcher.setHooksConfigLoader(loader);
        watcher.setPluginLoader(pluginLoader);

        watcher.handleChange(policySettings); // 初始 null != same → 重载, last=same
        watcher.handleChange(policySettings); // same == last → 跳过

        // H-WF1-01: 快照未变必须跳过重载（CC :267-272，防 policy 无关变更反复重载）
        verify(pluginLoader, times(1)).refreshActivePlugins();
    }

    /** 非 policy 源变更 → 即使快照不同也不触发插件 hook 重载（CC :265 仅 policySettings）. */
    @Test
    @DisplayName("[H-WF1-01] 非 policy 源变更 → 不触发插件 hook 重载（CC :265）")
    void pluginReload_nonPolicySource_skips() {
        Path userSettings = tempDir.resolve("user").resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        MultiSourceHooksConfigLoader loader = mock(MultiSourceHooksConfigLoader.class);
        PluginLoader pluginLoader = mock(PluginLoader.class);
        when(loader.pluginAffectingSettingsSnapshot()).thenReturn("snap-v1");

        SettingsFileChangeWatcher watcher = new SettingsFileChangeWatcher();
        watcher.setUserHome(tempDir.resolve("user").toString());
        watcher.setNexusaiHome(tempDir.resolve("proj").toString());
        watcher.setPolicyFilePath("");
        watcher.setHooksConfigLoader(loader);
        watcher.setPluginLoader(pluginLoader);

        watcher.handleChange(userSettings); // user_settings 源 → 直接跳过

        // H-WF1-01: user/project/local 源不得触发插件 hook 热重载（CC :265 仅 policySettings）
        verify(pluginLoader, never()).refreshActivePlugins();
    }
}

