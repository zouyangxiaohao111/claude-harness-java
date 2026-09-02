package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookMatcherEngine;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [IMP-GAP03] 插件 hook matcher 过滤 · 对齐 CC PluginHookMatcher（loadPluginHooks.ts:74-81）
 * + getMatchingHooks matcher 过滤（hooks.ts:1683-1686）。
 *
 * <p>验证意图（规则九 · 测试验证意图而非行为）：matcher 决定「该组插件 hooks 是否适用于
 * 当前事件」。Java 修复前丢弃 matcher → 插件 hook 对同事件全部执行（过度执行）；修复后
 * 仅 matcher 命中的组执行。分支覆盖：
 * <ol>
 *   <li><b>匹配</b>：matcher=Read，Read 事件执行、Bash 事件跳过。</li>
 *   <li><b>pipe 列表</b>：matcher=Bash|Write（CC matchesPattern :1351-1359 管道列表）。</li>
 *   <li><b>空 matcher</b>：不过滤（CC matcher 为 null/空 也保留，:1684）。</li>
 *   <li><b>'*' matcher</b>：不过滤（matchesPattern 一级短路，:1350）。</li>
 *   <li><b>engine null</b>：PluginLoader 未注入引擎 → 回退不过滤（保持现状）。</li>
 *   <li><b>无 matchQuery 事件</b>：Stop 无匹配字段（extractMatchQuery → null，CC :1684 全部
 *       保留）→ 带 matcher 的 hook 仍执行（防过度过滤）。</li>
 *   <li><b>Notification</b>：按 notification_type 匹配（HookMatcherEngine NOTIFICATION case；
 *       先例 ElicitationHandler:158-161 fireNotification）。</li>
 * </ol>
 *
 * <p>执行观测：注册 RecordingCommandHookExecutor（覆写 execute 计数，无真实进程）→
 * {@code registry.executeEvent(...)} 走插件 GenericHook 全链路，断言 execute 调用次数。
 */
class PluginHookMatcherFilterTest {

    @TempDir
    Path tempDir;

    private InstalledPluginsManager manager;
    private HookRegistry registry;
    private RecordingCommandHookExecutor executor;
    private HookMatcherEngine engine;
    private PluginLoader loader;

    /** 计数执行器 · 覆写 execute 记录调用（无真实进程，super 无参构造 + 不调 super.execute）。 */
    static class RecordingCommandHookExecutor extends CommandHookExecutor {
        final AtomicInteger executeCalls = new AtomicInteger();

        RecordingCommandHookExecutor() {
            super();
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex, boolean forceSyncExecution) {
            executeCalls.incrementAndGet();
            return new CommandHookResult("", "", "", 0, false, false);
        }
    }

    @BeforeEach
    void setUp() {
        manager = new InstalledPluginsManager();
        registry = new HookRegistry();
        executor = new RecordingCommandHookExecutor();
        registry.setCommandHookExecutor(executor);
        engine = new HookMatcherEngine(null, new PermissionRuleValueParser());
        loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        loader.setHookRegistry(registry);
        loader.setCommandHookExecutor(executor);
    }

    /** 安装一个带单 matcher 组 hook 的插件（eventType 可为 PreToolUse/Stop/Notification 等 CC 名）。 */
    private void installPlugin(String pluginName, String eventCcName, String matcher) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(pluginName));
        Path hooksDir = Files.createDirectories(root.resolve("hooks"));
        Files.writeString(hooksDir.resolve("hooks.json"),
            "{ \"hooks\": { \"" + eventCcName + "\": ["
                + " { \"matcher\": \"" + matcher + "\", \"hooks\": [\"echo hi\"] } ] } }");
        manager.install(pluginName, "1.0.0", "marketplace", root, null);
    }

    private void loadHooks() {
        loader.loadPluginHooks();
    }

    // ── 1. matcher 匹配：仅匹配事件执行 ──

    @Test
    void matcherMatchExecutesOnlyOnMatchingToolEvent() throws Exception {
        installPlugin("m-p", "PreToolUse", "Read");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Read", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertEquals(1, executor.executeCalls.get(),
            "matcher=Read: Read 事件执行 1 次（匹配）");
    }

    // ── 2. pipe 列表 matcher（CC matchesPattern :1351-1359）──

    @Test
    void pipeListMatcherExecutesOnAnyListedTool() throws Exception {
        installPlugin("pipe-p", "PreToolUse", "Bash|Write");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Read", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Write", null, "s1", null));

        assertEquals(2, executor.executeCalls.get(),
            "matcher=Bash|Write: Bash/Write 各执行 1 次, Read 跳过（管道列表命中任一即执行）");
    }

    // ── 3. 空 matcher：不过滤（CC :1684 matcher null/空 保留）──

    @Test
    void emptyMatcherExecutesOnAllEvents() throws Exception {
        installPlugin("empty-p", "PreToolUse", "");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Read", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertEquals(2, executor.executeCalls.get(),
            "空 matcher 不过滤（CC matchesPattern 一级短路）→ 两事件均执行");
    }

    // ── 4. '*' matcher：不过滤 ──

    @Test
    void wildcardMatcherExecutesOnAllEvents() throws Exception {
        installPlugin("star-p", "PreToolUse", "*");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Read", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertEquals(2, executor.executeCalls.get(),
            "'*' matcher 不过滤（matchesPattern :1350）→ 两事件均执行");
    }

    // ── 5. engine null：回退不过滤（保持现状）──

    @Test
    void engineNullFallsBackToNoMatcherFiltering() throws Exception {
        installPlugin("noeng-p", "PreToolUse", "Read");
        // 不注入 setHookMatcherEngine → loader.hookMatcherEngine == null
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertEquals(1, executor.executeCalls.get(),
            "engine 未注入 → 回退不过滤（matcher=Read 对 Bash 事件仍执行, 保持现状）");
    }

    // ── 6. 无 matchQuery 事件（Stop）：matcher 过滤不生效（CC :1684 全部保留）──

    @Test
    void noMatchQueryEventSkipsMatcherFilter() throws Exception {
        installPlugin("stop-p", "Stop", "Read");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.stop("s1", "a1", false, null));

        assertEquals(1, executor.executeCalls.get(),
            "Stop 事件无 matchQuery（extractMatchQuery → null）→ matcher=Read 仍执行（防过度过滤）");
    }

    // ── 7. Notification：按 notification_type 匹配（ElicitationHandler:158-161 先例）──

    @Test
    void notificationMatcherMatchesByNotificationType() throws Exception {
        installPlugin("notif-p", "Notification", "elicitation_complete");
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.notification("s1", "a1", "msg", "t", "elicitation_complete"));
        registry.executeEvent(HookEvent.notification("s1", "a1", "msg", "t", "permission_request"));

        assertEquals(1, executor.executeCalls.get(),
            "Notification 按 notification_type 匹配：elicitation_complete 执行, permission_request 跳过");
    }

    // ── 8. 多 matcher 组同事件：注册名含 matcher → 各组独立存活（CC PluginHookMatcher[] 逐组 push）──

    @Test
    void multiMatcherGroupsSameEventAllRegistered() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("multi-p"));
        Path hooksDir = Files.createDirectories(root.resolve("hooks"));
        // 同一 PreToolUse 事件挂两个 matcher 组（Read / Bash）
        Files.writeString(hooksDir.resolve("hooks.json"),
            "{ \"hooks\": { \"PreToolUse\": ["
                + " { \"matcher\": \"Read\", \"hooks\": [\"echo read\"] },"
                + " { \"matcher\": \"Bash\", \"hooks\": [\"echo bash\"] } ] } }");
        manager.install("multi-p", "1.0.0", "marketplace", root, null);
        loader.setHookMatcherEngine(engine);
        loadHooks();

        registry.executeEvent(HookEvent.toolPre("Read", null, "s1", null));
        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertEquals(2, executor.executeCalls.get(),
            "同一事件两个 matcher 组必须独立注册（注册名含 matcher）——Read/Bash 各执行 1 次；"
                + "若注册名不含 matcher 则后组覆盖前组，Read 组丢失（仅 Bash 执行 1 次）");
    }
}
