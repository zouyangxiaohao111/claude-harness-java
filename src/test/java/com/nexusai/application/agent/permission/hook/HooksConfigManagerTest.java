package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [4-2/4-3 决策] UI 展示函数 15 项 + getAllHooks session 合并单元测试（RED 先行）。
 *
 * <p>WHY (规则九 · 测试验证意图): CC hooksConfigManager.ts（27 事件元数据表 + 分组 +
 * matcher 键推导 + 优先级排序）与 hooksSettings.ts（getAllHooks session 合并 + 来源展示串 +
 * sortMatchersByPriority）是前端 hook 配置展示/编辑面板的唯一数据源。用户拍板「后端直接实现」
 * （open-decisions.md 4-2）—— 这些函数必须存在且语义对齐 CC，否则前端面板拿到空/错序数据。
 *
 * <p>对齐 CC: hooksConfigManager.ts 全文 + hooksSettings.ts:92-271（2026-08-15 读真源）。
 */
@DisplayName("[4-2/4-3] HooksConfigManager UI 展示函数 + HooksSettings.getAllHooks(sessionId) 合并")
class HooksConfigManagerTest {

    private final HooksConfigManager manager = new HooksConfigManager();

    // ── 1. getHookEventMetadata: 27 事件全量 + memoize 缓存键 ──────────────

    @Test
    @DisplayName("1. getHookEventMetadata 返回 27 事件元数据表 (CC hooksConfigManager.ts:26-267)")
    void getHookEventMetadata_27events_allPresent() {
        Map<HookEventType, HooksConfigManager.HookEventMetadata> meta =
            manager.getHookEventMetadata(List.of("Write", "Bash", "Read"));

        // 27 事件全量 (coreTypes.ts:25-53)
        assertThat(meta).hasSize(27);
        // PreToolUse 有 matcherMetadata (tool_name, toolNames 透传)
        HooksConfigManager.HookEventMetadata pre = meta.get(HookEventType.PRE_TOOL_USE);
        assertThat(pre.summary()).isEqualTo("Before tool execution");
        assertThat(pre.matcherMetadata().fieldToMatch()).isEqualTo("tool_name");
        assertThat(pre.matcherMetadata().values()).containsExactlyInAnyOrder("Write", "Bash", "Read");
        // Stop 无 matcherMetadata (CC 真源 undefined)
        assertThat(meta.get(HookEventType.STOP).matcherMetadata()).isNull();
        // Notification 固定 values (非 toolNames)
        assertThat(meta.get(HookEventType.NOTIFICATION).matcherMetadata().values())
            .contains("permission_prompt", "idle_prompt");
        // StopFailure error 值域
        assertThat(meta.get(HookEventType.STOP_FAILURE).matcherMetadata().values())
            .contains("rate_limit", "max_output_tokens", "unknown");
    }

    @Test
    @DisplayName("2. getHookEventMetadata 相同 toolNames 命中缓存 (memoize 排序 join 键, CC :266-267)")
    void getHookEventMetadata_memoizedBySortedKey_sameInstance() {
        Map<HookEventType, HooksConfigManager.HookEventMetadata> a =
            manager.getHookEventMetadata(List.of("Bash", "Write"));
        Map<HookEventType, HooksConfigManager.HookEventMetadata> b =
            manager.getHookEventMetadata(List.of("Write", "Bash")); // 不同序 → 同键
        assertThat(b).isSameAs(a);

        Map<HookEventType, HooksConfigManager.HookEventMetadata> c =
            manager.getHookEventMetadata(List.of("Bash", "Read"));
        assertThat(c).isNotSameAs(a);
    }

    // ── 2. groupHooksByEventAndMatcher: matcherKey 推导 (元数据驱动) ─────────

    @Test
    @DisplayName("3. groupHooksByEventAndMatcher: 27 事件键恒存在 + matcherKey 元数据驱动 (CC :307-320)")
    void groupHooksByEventAndMatcher_27Keys_andMatcherKeyByMetadata() {
        // 有 matcherMetadata 的 PreToolUse → matcher 作 key
        List<IndividualHookConfig> hooks = List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS),
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.LOCAL_SETTINGS),
            hook(HookEventType.STOP, null, HookSource.USER_SETTINGS)); // 无 matcherMetadata → 空串 key

        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped =
            manager.groupHooksByEventAndMatcher(hooks, List.of("Write"));

        // 27 事件键恒存在 (CC :274-302 初始化)
        assertThat(grouped).hasSize(27);
        // 无 hooks 的事件键仍存在且空 (CC :274-302 初始化)
        assertThat(grouped.get(HookEventType.CWD_CHANGED)).isNotNull().isEmpty();

        // PreToolUse: matcher="Write" → key="Write", 2 条 (user+local concat)
        assertThat(grouped.get(HookEventType.PRE_TOOL_USE).get("Write")).hasSize(2);
        // Stop: 无 matcherMetadata → 空串 key
        assertThat(grouped.get(HookEventType.STOP).get("")).hasSize(1);
        assertThat(grouped.get(HookEventType.STOP)).doesNotContainKey("Write");
    }

    // ── 3. getSortedMatchersForEvent + sortMatchersByPriority ──────────────

    @Test
    @DisplayName("4. sortMatchersByPriority: local 最高 > project > user > plugin 最低 (CC :230-271)")
    void sortMatchersByPriority_editablePriorityOrder_pluginLowest() {
        // PreToolUse 下 4 个 matcher, 各来自不同 source
        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped = Map.of(
            HookEventType.PRE_TOOL_USE, Map.of(
                "userMatcher", List.of(hook(HookEventType.PRE_TOOL_USE, "userMatcher", HookSource.USER_SETTINGS)),
                "pluginMatcher", List.of(hook(HookEventType.PRE_TOOL_USE, "pluginMatcher", HookSource.PLUGIN_HOOK)),
                "localMatcher", List.of(hook(HookEventType.PRE_TOOL_USE, "localMatcher", HookSource.LOCAL_SETTINGS)),
                "projectMatcher", List.of(hook(HookEventType.PRE_TOOL_USE, "projectMatcher", HookSource.PROJECT_SETTINGS))));

        List<String> sorted = HooksSettings.sortMatchersByPriority(
            List.of("userMatcher", "pluginMatcher", "localMatcher", "projectMatcher"), grouped, HookEventType.PRE_TOOL_USE);

        // local(0) < project(1) < user(2) < plugin(999)
        assertThat(sorted).containsExactly(
            "localMatcher", "projectMatcher", "userMatcher", "pluginMatcher");
    }

    @Test
    @DisplayName("5. getSortedMatchersForEvent: 委托 sortMatchersByPriority (CC hooksConfigManager.ts:368-377)")
    void getSortedMatchersForEvent_delegatesToPrioritySort() {
        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped = Map.of(
            HookEventType.POST_TOOL_USE, Map.of(
                "a", List.of(hook(HookEventType.POST_TOOL_USE, "a", HookSource.USER_SETTINGS)),
                "b", List.of(hook(HookEventType.POST_TOOL_USE, "b", HookSource.LOCAL_SETTINGS))));

        List<String> sorted = manager.getSortedMatchersForEvent(grouped, HookEventType.POST_TOOL_USE);
        assertThat(sorted).containsExactly("b", "a"); // local 优先
        // 无该事件 → 空
        assertThat(manager.getSortedMatchersForEvent(grouped, HookEventType.STOP)).isEmpty();
    }

    // ── 4. getHooksForMatcher + getMatcherMetadata ──────────────────────────

    @Test
    @DisplayName("6. getHooksForMatcher: matcher ?? '' 键查找, 缺失 → 空 (CC :380-392)")
    void getHooksForMatcher_matcherNull_emptyStringKey() {
        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped = Map.of(
            HookEventType.STOP, Map.of("", List.of(hook(HookEventType.STOP, null, HookSource.USER_SETTINGS))));

        // null matcher → 空串 key
        assertThat(manager.getHooksForMatcher(grouped, HookEventType.STOP, null)).hasSize(1);
        // 有 matcher 的事件查空串 → 空
        assertThat(manager.getHooksForMatcher(grouped, HookEventType.PRE_TOOL_USE, "Write")).isEmpty();
    }

    @Test
    @DisplayName("7. getMatcherMetadata: 返回事件 matcher 元数据; 无 matcherMetadata → null (CC :395-400)")
    void getMatcherMetadata_eventHasMetadata_orNull() {
        HooksConfigManager.MatcherMetadata m =
            manager.getMatcherMetadata(HookEventType.PRE_TOOL_USE, List.of("Bash"));
        assertThat(m.fieldToMatch()).isEqualTo("tool_name");
        assertThat(m.values()).containsExactly("Bash");
        assertThat(manager.getMatcherMetadata(HookEventType.STOP, List.of("Bash"))).isNull();
    }

    // ── 5. HooksSettings.getAllHooks(sessionId): session 合并 (4-3) ────────

    @Test
    @DisplayName("8. getAllHooks(sessionId): session hook 合并 (source=sessionHook, CC hooksSettings.ts:144-158)")
    void getAllHooks_sessionId_mergesSessionHooks() {
        HooksSettings settings = new HooksSettings();
        // settings 源: user settings 一个 PreToolUse hook
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        // SessionHookStore 注入一个 session hook
        SessionHookStore store = new SessionHookStore();
        store.addSessionHook("sess-1", HookEventType.STOP, null,
            new CommandHook("echo session-stop", null, null, null, null, null, null, null), null, null);
        settings.setSessionHooksProvider(sid -> store.getSessionHooks(sid, null));

        List<IndividualHookConfig> all = settings.getAllHooks("sess-1");

        // settings hook + session hook 合并
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(h -> {
            assertThat(h.event()).isEqualTo(HookEventType.STOP);
            assertThat(h.source()).isEqualTo(HookSource.SESSION_HOOK);
        });
        // 其他 sessionId → 无 session hook, 仅 settings
        assertThat(settings.getAllHooks("other")).hasSize(1);
        // null sessionId → 仅 settings
        assertThat(settings.getAllHooks(null)).hasSize(1);
    }

    @Test
    @DisplayName("9. getAllHooks(sessionId): 无 provider 保持 settings-only (非 UI 调用不受影响)")
    void getAllHooks_sessionId_noProvider_settingsOnly() {
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        // 未注入 provider → session 段跳过
        assertThat(settings.getAllHooks("any")).hasSize(1);
    }

    @Test
    @DisplayName("10. getAllHooks(sessionId): managedOnly=true 时 session 仍合并 (CC session 段无条件)")
    void getAllHooks_sessionId_managedOnly_sessionStillMerged() {
        // policy allowManagedHooksOnly=true → settings 段隐藏, 但 session 段仍追加 (CC :96-101/:144-158)
        HooksSettings settings = new HooksSettings(key -> "allowManagedHooksOnly".equals(key) ? true : null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        SessionHookStore store = new SessionHookStore();
        store.addSessionHook("sess-1", HookEventType.STOP, null,
            new CommandHook("echo s", null, null, null, null, null, null, null), null, null);
        settings.setSessionHooksProvider(sid -> store.getSessionHooks(sid, null));

        // settings-only → 空 (managedOnly); with session → 仍含 session hook
        assertThat(settings.getAllHooks()).isEmpty();
        assertThat(settings.getAllHooks("sess-1"))
            .extracting(h -> h.source())
            .containsExactly(HookSource.SESSION_HOOK);
    }

    // ── 6. getHooksForEvent (hooksSettings.ts:163-168) ─────────────────────

    @Test
    @DisplayName("11. getHooksForEvent: getAllHooks 按事件过滤 (CC hooksSettings.ts:163-168)")
    void getHooksForEvent_filtersByEvent() {
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS),
            hook(HookEventType.STOP, null, HookSource.USER_SETTINGS)));

        assertThat(settings.getHooksForEvent(null, HookEventType.PRE_TOOL_USE)).hasSize(1);
        assertThat(settings.getHooksForEvent(null, HookEventType.STOP)).hasSize(1);
        assertThat(settings.getHooksForEvent(null, HookEventType.POST_TOOL_USE)).isEmpty();
    }

    // ── 7. hookSource*DisplayString (hooksSettings.ts:170-228) ─────────────

    @Test
    @DisplayName("12. hookSource*DisplayString: 7 分支 + default 返回枚举名 (CC :170-228)")
    void hookSourceDisplayStrings_allSources() {
        assertThat(HooksSettings.hookSourceDescriptionDisplayString(HookSource.USER_SETTINGS))
            .isEqualTo("User settings (~/.nexusai/settings.json)");
        assertThat(HooksSettings.hookSourceDescriptionDisplayString(HookSource.PLUGIN_HOOK))
            .isEqualTo("Plugin hooks (~/.nexusai/plugins/*/hooks/hooks.json)");
        assertThat(HooksSettings.hookSourceDescriptionDisplayString(HookSource.SESSION_HOOK))
            .isEqualTo("Session hooks (in-memory, temporary)");
        assertThat(HooksSettings.hookSourceHeaderDisplayString(HookSource.PROJECT_SETTINGS))
            .isEqualTo("Project Settings");
        assertThat(HooksSettings.hookSourceInlineDisplayString(HookSource.LOCAL_SETTINGS))
            .isEqualTo("Local");
        // default (POLICY_SETTINGS 无专属串) → 枚举名 (CC default: source as string)
        assertThat(HooksSettings.hookSourceDescriptionDisplayString(HookSource.POLICY_SETTINGS))
            .isEqualTo("POLICY_SETTINGS");
    }

    // ── 8. OPD-WF1-CFG-01 生产接线: getAllHooks(sessionId) session 合并 ─────

    @Test
    @DisplayName("13. groupHooksByEventAndMatcher(sessionId): 生产接线后 session hook 并入分组 (CC 内部 getAllHooks(appState) 语义)")
    void groupHooksByEventAndMatcher_sessionId_mergesSessionHooks() {
        // 组装生产接线: HooksConfigManager.setHooksSettings + HooksSettings.setSessionHooksProvider
        // (SessionHookStore 读取器, 等价 HookRegistry 生产注入) + settings 源一个 PreToolUse hook
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        SessionHookStore store = new SessionHookStore();
        store.addSessionHook("sess-1", HookEventType.STOP, null,
            new CommandHook("echo session-stop", null, null, null, null, null, null, null), null, null);
        settings.setSessionHooksProvider(sid -> store.getSessionHooks(sid, null));
        manager.setHooksSettings(settings);

        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped =
            manager.groupHooksByEventAndMatcher("sess-1", List.of("Write"));

        // settings hook 分组 (PreToolUse matcher=Write)
        assertThat(grouped.get(HookEventType.PRE_TOOL_USE).get("Write")).hasSize(1);
        // session hook 分组 (STOP 无 matcherMetadata → 空串 key, source=sessionHook)
        List<IndividualHookConfig> stopHooks = grouped.get(HookEventType.STOP).get("");
        assertThat(stopHooks).hasSize(1);
        assertThat(stopHooks.get(0).source()).isEqualTo(HookSource.SESSION_HOOK);
        // 其他 sessionId → 无 session hook, STOP 组为空串 key 缺失
        assertThat(manager.groupHooksByEventAndMatcher("other", List.of("Write"))
            .get(HookEventType.STOP).get("")).isNull();
    }

    @Test
    @DisplayName("14. HookRegistry 注入 HooksSettings 时接线 sessionHooksProvider (生产 getAllHooks(sessionId) 含 session, EV-WF1-CFG-061)")
    void hookRegistry_injectsSessionHooksProvider_mergesSessionHooks() {
        // 生产接线点: HookRegistry.setHooksSettings(settings) → settings.setSessionHooksProvider
        // (sessionId -> sessionHookStore.getSessionHooks(sessionId, null)), 对齐 HooksSettings javadoc 4-3 决策
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        HookRegistry registry = new HookRegistry();
        registry.setHooksSettings(settings);
        registry.addSessionHook("sess-1", HookEventType.STOP, null,
            new CommandHook("echo session-stop", null, null, null, null, null, null, null), null, null);

        // 接线后: getAllHooks(sessionId) = settings + session 合并 (CC hooksSettings.ts:92-161)
        List<IndividualHookConfig> all = settings.getAllHooks("sess-1");
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(h -> {
            assertThat(h.event()).isEqualTo(HookEventType.STOP);
            assertThat(h.source()).isEqualTo(HookSource.SESSION_HOOK);
        });
        // 未注入 provider 的 settings → settings-only (非 UI 调用不受影响)
        HooksSettings plain = new HooksSettings();
        plain.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            hook(HookEventType.PRE_TOOL_USE, "Write", HookSource.USER_SETTINGS)));
        assertThat(plain.getAllHooks("sess-1")).hasSize(1);
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────

    private static IndividualHookConfig hook(HookEventType event, String matcher, HookSource source) {
        return new IndividualHookConfig(event,
            new CommandHook("echo " + (matcher == null ? "x" : matcher),
                null, null, null, null, null, null, null),
            matcher, source, null);
    }
}
