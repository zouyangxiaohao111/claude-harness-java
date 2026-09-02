package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H5] SessionHookStore 三级存储 (event→matcher→hooks) · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/sessionHooks.ts} 全文 (addSessionHook L68-86,
 * addFunctionHook L93-115, removeFunctionHook L120-162, addHookToSession L167-216,
 * removeSessionHook L225-268, getSessionHooks L302-330, getSessionFunctionHooks L345-392,
 * getSessionHookCallback L397-430, clearSessionHooks L437-447) + isHookEqual
 * {@code hooksSettings.ts:33-64}.
 *
 * <p>WHY (规则九 · 测试验证意图): 本测试验证 session hook 的<b>生命周期契约</b>——
 * 若注册/移除/查询任一步骤断裂, sub-agent 的临时 hook 会泄漏 (注册了永不清理) 或丢失
 * (查询不到无法执行). 三级存储的对齐点:
 * <ul>
 *   <li>command/function 两类 hook 必须<b>分离</b> (function 不可持久化, CC L288 过滤)</li>
 *   <li>isHookEqual 按 command+shell+if 判定身份 (CC hooksSettings.ts:41-54, if/shell 是身份一部分)</li>
 *   <li>matcher+skillRoot 分组追加 (同组不新建 matcher, CC L181-205)</li>
 *   <li>function hook 按 id 移除 (CC L134-140), command hook 按 isHookEqual 移除 (CC L242-244)</li>
 * </ul>
 *
 * @since Session H5
 */
@DisplayName("[H5] SessionHookStore 三级存储对齐 CC sessionHooks.ts")
class SessionHookStoreTest {

    private final SessionHookStore store = new SessionHookStore();

    /** WHY: function hook callback 返回 true=放行 (CC FunctionHookCallback 语义, sessionHooks.ts:15-18). */
    private static FunctionHookCallback passingCallback() {
        // D-06: CC 签名 (messages, signal?) => boolean | Promise<boolean> (sessionHooks.ts:15-18)
        return (messages, signal) -> CompletableFuture.completedFuture(true);
    }

    /** WHY: 测试便捷构造, 其他字段无关紧要 (isHookEqual 只比 command/shell/if). */
    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1-3. 正向
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: addSessionHook (CC L68-86) 委托 addHookToSession 按 event+matcher 注册 command hook.
     * 若注册后 getSessionHooks (CC L302-330) 查不到, 则 hook 配置了但永不执行 = 三级存储的
     * 写入/读取链路断裂.
     */
    @Test
    @DisplayName("addSessionHook 注册 command hook → getSessionHooks 按 event+matcher 命中")
    void addCommandHook_registersByEventAndMatcher() {
        CommandHook hook = commandHook("echo hi");
        store.addSessionHook("sess-1", HookEventType.USER_PROMPT_SUBMIT, "Write", hook, null, null);

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                store.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT);

        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        List<SessionHookStore.SessionDerivedHookMatcher> matchers =
                hooks.get(HookEventType.USER_PROMPT_SUBMIT);
        assertThat(matchers).hasSize(1);
        assertThat(matchers.get(0).matcher()).isEqualTo("Write");
        assertThat(matchers.get(0).hooks()).containsExactly(hook);
    }

    /**
     * WHY: addFunctionHook (CC L93-115) 必须生成 {@code function-hook-<ts>-<rand>} 前缀 id
     * (供 removeFunctionHook 定位) + timeout 缺省 5000. 若 id 不唯一或 timeout 缺省不对,
     * 移除会误删其他 function hook / 超时语义偏离 CC.
     */
    @Test
    @DisplayName("addFunctionHook 生成 function-hook- 前缀 id + 默认 timeout 5000")
    void addFunctionHook_generatesIdWithDefaultTimeout() {
        String id = store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash",
                passingCallback(), "blocked", null, null);

        assertThat(id).startsWith("function-hook-");

        Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> fns =
                store.getSessionFunctionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        FunctionHook fn = fns.get(HookEventType.PRE_TOOL_USE).get(0).hooks().get(0);
        assertThat(fn.id()).isEqualTo(id);
        assertThat(fn.timeout()).isEqualTo(FunctionHook.DEFAULT_TIMEOUT_MS);
    }

    /**
     * WHY: getSessionHookCallback (CC L397-430) 返回完整 entry (hook + onHookSuccess),
     * 调用方在执行 hook 后触发 onHookSuccess 回调. 若回调丢失, session hook 的成功通知
     * 链路断裂 (对齐 CC SessionHookMatcher.hooks[i].onHookSuccess, L36-39).
     */
    @Test
    @DisplayName("getSessionHookCallback 取完整 entry (含 onHookSuccess)")
    void getSessionHookCallback_returnsFullEntryWithOnHookSuccess() {
        AtomicReference<SessionHook> captured = new AtomicReference<>();
        CommandHook hook = commandHook("notify");
        store.addSessionHook("sess-1", HookEventType.POST_TOOL_USE, "Bash", hook,
                (h, result) -> captured.set(h), null);

        Optional<SessionHookStore.SessionHookEntry> entry =
                store.getSessionHookCallback("sess-1", HookEventType.POST_TOOL_USE, "Bash", hook);

        assertThat(entry).isPresent();
        assertThat(entry.get().hook()).isEqualTo(hook);
        assertThat(entry.get().onHookSuccess()).isNotNull();
        entry.get().onHookSuccess().onSuccess(hook, AggregatedHookResult.proceed());
        assertThat(captured.get()).isSameAs(hook);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4-5. 反向
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: removeFunctionHook (CC L120-162) 按 id 从<b>所有 matcher</b>过滤移除 — 同 event 下
     * 不同 matcher 里的同名 id hook 都要清干净. 若只移除首个 matcher, 残留 function hook
     * 会继续拦截后续工具调用 = 清理不彻底的回归.
     */
    @Test
    @DisplayName("removeFunctionHook 按 id 从所有 matcher 过滤移除")
    void removeFunctionHook_removesByHookIdAcrossAllMatchers() {
        String id1 = store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash",
                passingCallback(), "e1", null, null);
        String id2 = store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash",
                passingCallback(), "e2", null, null);
        store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Write",
                passingCallback(), "e3", null, null);

        store.removeFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, id1);

        Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> fns =
                store.getSessionFunctionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        // Bash matcher 只剩 id2; Write matcher (不同 matcher) 不受影响
        List<FunctionHook> bashHooks = fns.get(HookEventType.PRE_TOOL_USE).stream()
                .filter(m -> "Bash".equals(m.matcher()))
                .findFirst().orElseThrow().hooks();
        assertThat(bashHooks).extracting(FunctionHook::id).containsExactly(id2);
        assertThat(fns.get(HookEventType.PRE_TOOL_USE)).hasSize(2);
    }

    /**
     * WHY: removeSessionHook (CC L225-268) 按 isHookEqual (hooksSettings.ts:33-64) 移除.
     * command hook 身份 = command + shell(缺省 bash) + if(缺省 ''), shell/if 不同即不同 hook
     * (CC L43-54). 若只比 command, 会误删 if 条件不同的安全 gate hook.
     */
    @Test
    @DisplayName("removeSessionHook 按 isHookEqual 移除 (command 比 command+shell+if)")
    void removeSessionHook_removesByIsHookEqual() {
        // a 与 b 完全相等 (shell/if 均同); diff 仅 if 不同 → 身份不同
        CommandHook a = new CommandHook("echo hi", "Bash(git *)", "bash", null, null, null, null, null);
        CommandHook b = new CommandHook("echo hi", "Bash(git *)", "bash", null, null, null, null, null);
        CommandHook diff = new CommandHook("echo hi", "Bash(npm *)", "bash", null, null, null, null, null);
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", a, null, null);
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", diff, null, null);

        store.removeSessionHook("sess-1", HookEventType.PRE_TOOL_USE, b);

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                store.getSessionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        List<CommandHook> remaining = hooks.get(HookEventType.PRE_TOOL_USE).get(0).hooks().stream()
                .filter(CommandHook.class::isInstance)
                .map(CommandHook.class::cast)
                .toList();
        // a 被移除 (与 b isHookEqual), diff 保留
        assertThat(remaining).containsExactly(diff);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6-8. 边界
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: addHookToSession (CC L181-205) 按 matcher+skillRoot 分组 — 同组追加 hooks 不新建
     * matcher, skillRoot 不同则新建. 若分组错误, getSessionHooks 返回的 matcher 数量会膨胀,
     * 每次执行遍历成本上升且 hook 归组错乱.
     */
    @Test
    @DisplayName("matcher+skillRoot 分组: 同组追加 hooks 不新建 matcher")
    void sameMatcherAndSkillRoot_appendsHooksWithoutNewMatcher() {
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Write", commandHook("h1"), null, "skill-a");
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Write", commandHook("h2"), null, "skill-a");
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Write", commandHook("h3"), null, "skill-b");

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                store.getSessionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        List<SessionHookStore.SessionDerivedHookMatcher> matchers =
                hooks.get(HookEventType.PRE_TOOL_USE);
        // skill-a 组 + skill-b 组 = 2 matchers (h3 因 skillRoot 不同必须新建, 不并入 skill-a)
        assertThat(matchers).hasSize(2);
        SessionHookStore.SessionDerivedHookMatcher skillA = matchers.stream()
                .filter(m -> "skill-a".equals(m.skillRoot()))
                .findFirst().orElseThrow();
        assertThat(skillA.hooks()).hasSize(2);
    }

    /**
     * WHY: function/command hooks 必须分离 (CC getSessionHooks L288 "Filter out function hooks",
     * getSessionFunctionHooks L345-392). function hook 持内存回调不可持久化, 若混入 command
     * 查询结果会被当作可持久化 HookCommand 序列化 → 崩溃; 反之 command 混入 function 查询
     * 会因无 callback 无法执行.
     */
    @Test
    @DisplayName("function hooks 与 command hooks 分离 (getSessionHooks 不含 function)")
    void functionHooks_areSeparatedFromCommandHooks() {
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", commandHook("ls"), null, null);
        store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", passingCallback(), "err", null, null);

        // getSessionHooks → 只含 command hook
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                store.getSessionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        List<HookCommand> commandHooks = hooks.get(HookEventType.PRE_TOOL_USE).get(0).hooks();
        assertThat(commandHooks).hasSize(1);
        assertThat(commandHooks.get(0)).isInstanceOf(CommandHook.class);

        // getSessionFunctionHooks → 只含 function hook
        Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> fns =
                store.getSessionFunctionHooks("sess-1", HookEventType.PRE_TOOL_USE);
        List<FunctionHook> fnHooks = fns.get(HookEventType.PRE_TOOL_USE).get(0).hooks();
        assertThat(fnHooks).hasSize(1);
        assertThat(fnHooks.get(0)).isInstanceOf(FunctionHook.class);
    }

    /**
     * WHY: clearSessionHooks (CC L437-447) delete sessionId 清空<b>整个 store</b> —
     * 会话结束所有临时 hook 一并释放. 若只清 event, 同 session 其他 event 的 hook 泄漏到
     * 下一轮会话复用 (对齐 CC runAgent.ts:822 subagent finally 调用).
     */
    @Test
    @DisplayName("clearSessionHooks 按 sessionId 清空整个 store")
    void clearSessionHooks_removesEntireSessionFromStore() {
        store.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", commandHook("ls"), null, null);
        store.addFunctionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash", passingCallback(), "err", null, null);
        store.addSessionHook("sess-2", HookEventType.PRE_TOOL_USE, "Bash", commandHook("ls2"), null, null);

        store.clearSessionHooks("sess-1");

        // sess-1 全清空: command + function + callback 全查不到
        assertThat(store.getSessionHooks("sess-1", HookEventType.PRE_TOOL_USE)).isEmpty();
        assertThat(store.getSessionFunctionHooks("sess-1", HookEventType.PRE_TOOL_USE)).isEmpty();
        assertThat(store.getSessionHookCallback("sess-1", HookEventType.PRE_TOOL_USE, "Bash",
                commandHook("ls"))).isEmpty();
        // sess-2 不受影响
        assertThat(store.getSessionHooks("sess-2", HookEventType.PRE_TOOL_USE))
                .containsKey(HookEventType.PRE_TOOL_USE);
    }

    /**
     * WHY (D-05): CC getSessionHooks 无 event 分支按 HOOK_EVENTS 27 项固定顺序遍历
     * (sessionHooks.ts:322-327 {@code for (const evt of HOOK_EVENTS)}) — 全量查询的 key 顺序
     * 是展示/持久化契约 (getAllHooks hooksSettings.ts:146-158), 与注册顺序无关. Java 旧实现
     * 按 LinkedHashMap 插入序遍历 → 逆序注册时返回逆序, 偏离 CC.
     */
    @Test
    @DisplayName("getSessionHooks(sessionId, null) key 顺序 = CC HOOK_EVENTS 27 项固定序 (D-05)")
    void getSessionHooks_noEvent_returnsCcOrder() {
        // 逆序注册 (CC 序尾 → 序头), 断言返回 keySet 仍按 CC 固定序
        for (int i = HookEventType.HOOK_EVENTS_ORDER.size() - 1; i >= 0; i--) {
            HookEventType evt = HookEventType.HOOK_EVENTS_ORDER.get(i);
            store.addSessionHook("sess-1", evt, "Write", commandHook("echo " + evt), null, null);
        }

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                store.getSessionHooks("sess-1", null);

        assertThat(hooks.keySet()).containsExactlyElementsOf(HookEventType.HOOK_EVENTS_ORDER);
    }

    /**
     * WHY (D-05): getSessionFunctionHooks 无 event 分支同序 (sessionHooks.ts:381-389) —
     * 两个全量查询共享同一 CC 顺序契约, 防 function/command 查询序不一致.
     */
    @Test
    @DisplayName("getSessionFunctionHooks(sessionId, null) key 顺序 = CC HOOK_EVENTS 固定序 (D-05)")
    void getSessionFunctionHooks_noEvent_returnsCcOrder() {
        for (int i = HookEventType.HOOK_EVENTS_ORDER.size() - 1; i >= 0; i--) {
            HookEventType evt = HookEventType.HOOK_EVENTS_ORDER.get(i);
            store.addFunctionHook("sess-1", evt, "Write", passingCallback(), "e" + evt, null, null);
        }

        Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> fns =
                store.getSessionFunctionHooks("sess-1", null);

        assertThat(fns.keySet()).containsExactlyElementsOf(HookEventType.HOOK_EVENTS_ORDER);
    }

    /**
     * WHY (D-05 契约防漂移): HOOK_EVENTS_ORDER 硬编码 CC coreTypes.ts:25-53 27 项序 —
     * 测试内再硬编码一遍 CC 名列表, 防止将来枚举重排/增删时 Java 顺序与 CC 静默脱钩.
     */
    @Test
    @DisplayName("HOOK_EVENTS_ORDER 与 CC coreTypes.ts:25-53 硬编码 27 项顺序一致 (防漂移)")
    void hookEventsOrder_matchesCcHardcodedOrder() {
        List<String> ccOrder = List.of(
                "PreToolUse", "PostToolUse", "PostToolUseFailure", "Notification", "UserPromptSubmit",
                "SessionStart", "SessionEnd", "Stop", "StopFailure", "SubagentStart", "SubagentStop",
                "PreCompact", "PostCompact", "PermissionRequest", "PermissionDenied", "Setup",
                "TeammateIdle", "TaskCreated", "TaskCompleted", "Elicitation", "ElicitationResult",
                "ConfigChange", "WorktreeCreate", "WorktreeRemove", "InstructionsLoaded", "CwdChanged",
                "FileChanged");

        assertThat(HookEventType.HOOK_EVENTS_ORDER.stream().map(HookEventType::ccName).toList())
                .containsExactlyElementsOf(ccOrder);
    }

    /**
     * WHY (D-05 同源约束): HOOK_EVENTS_ORDER 与 ccEventNames() 推导集合必须同源 —
     * 枚举增删时两者同步 (数量 27 + 名称集合一致), 防止白名单与遍历序漂移成两套事实.
     */
    @Test
    @DisplayName("HOOK_EVENTS_ORDER 与 ccEventNames() 同源: 27 项名称集合一致")
    void hookEventsOrder_sameSourceAsCcEventNames() {
        assertThat(HookEventType.HOOK_EVENTS_ORDER).hasSize(27);
        assertThat(HookEventType.HOOK_EVENTS_ORDER.stream().map(HookEventType::ccName)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(HookEventType.ccEventNames());
    }
}
