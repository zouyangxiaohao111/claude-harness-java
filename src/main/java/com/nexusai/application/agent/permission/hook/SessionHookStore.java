package com.nexusai.application.agent.permission.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Session hook 三级存储 (sessionId → event → matcher → hooks) · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/sessionHooks.ts} 全文.
 *
 * <p>WHY (规则三): CC session hooks 是"运行时会话内临时回调" (CC 注释 "temporary, in-memory
 * only, and cleared when session ends", L66-67), 与 {@code settings.json} 持久化 hooks 并存.
 * 本 store 等价 CC {@code SessionHooksState = Map<string, SessionStore>} (L62) 的三级结构:
 * <pre>
 *   SessionHooksState  Map&lt;sessionId, SessionStore&gt;                       (L62)
 *   SessionStore      { hooks: Map&lt;event, SessionHookMatcher[]&gt; }          (L42-46)
 *   SessionHookMatcher { matcher, skillRoot?, hooks: SessionHookEntry[] }  (L33-40)
 *   SessionHookEntry  { hook: HookCommand|FunctionHook, onHookSuccess? }   (L36-39)
 * </pre>
 *
 * <p><b>Map identity 语义 (concern H5-2)</b>: CC L48-61 说明为何用 {@code Map} 而非
 * {@code Record} — {@code .set/.delete} 不改变容器 identity, 让 store.ts 的
 * {@code Object.is(next, prev)} 短路跳过 listener 通知. Java 端无响应式 store, identity
 * 无关紧要, 但保留 {@link HashMap} 语义 (computeIfAbsent/remove 就地变更), 未来若引入
 * 响应式 wrapper 可直接对齐 CC 优化.
 *
 * <p><b>线程安全</b>: {@link HookRegistry} 现有 hook 容器用 {@code synchronized} 防护
 * (register/unregister), 本 store 镜像该风格 — 变更方法 synchronized, 读取方法在锁内快照.
 * {@link SubagentExecutor} 在 finally 并发调用 {@link #clearSessionHooks} 与 add 方法竞争时
 * 不破坏 map 结构.
 *
 * @see FunctionHook
 * @see FunctionHookCallback
 * @see SessionHook
 * @see HookRegistry
 * @since Session H5
 */
public class SessionHookStore {

    private static final Logger log = LoggerFactory.getLogger(SessionHookStore.class);

    /**
     * SessionHooksState · 对齐 CC L62 {@code Map<string, SessionStore>}.
     *
     * <p>WHY: key=sessionId (子 agent 每次执行独立 session), value=该 session 的全部临时 hook.
     * {@link #clearSessionHooks} 直接 remove key = CC L442 {@code prev.sessionHooks.delete(sessionId)}.
     */
    private final Map<String, SessionStore> sessions = new HashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    // 三级存储 record 定义 (对齐 CC sessionHooks.ts:33-46)
    // ════════════════════════════════════════════════════════════════════════

    /** SessionStore · 对齐 CC L42-46 {@code { hooks: {[event]?: SessionHookMatcher[]} } }. */
    public record SessionStore(Map<HookEventType, List<SessionHookMatcher>> hooks) {
        public SessionStore {
            hooks = hooks == null ? new LinkedHashMap<>() : hooks;
        }
    }

    /** SessionHookMatcher · 对齐 CC L33-40 {@code { matcher, skillRoot?, hooks: [...] } }. */
    public record SessionHookMatcher(String matcher, String skillRoot, List<SessionHookEntry> hooks) {
        public SessionHookMatcher {
            hooks = hooks == null ? List.of() : hooks;
        }
    }

    /** SessionHookEntry · 对齐 CC L36-39 {@code { hook, onHookSuccess? } }. */
    public record SessionHookEntry(SessionHook hook, OnHookSuccess onHookSuccess) {
    }

    /** OnHookSuccess · 对齐 CC L9-12 {@code (hook, result) => void} — hook 执行成功后回调. */
    @FunctionalInterface
    public interface OnHookSuccess {
        void onSuccess(SessionHook hook, AggregatedHookResult result);
    }

    /** SessionDerivedHookMatcher · 对齐 CC L271-275 — 持久化视角 matcher (无 function hook). */
    public record SessionDerivedHookMatcher(String matcher, String skillRoot, List<HookCommand> hooks) {
        public SessionDerivedHookMatcher {
            hooks = hooks == null ? List.of() : hooks;
        }
    }

    /** FunctionHookMatcher · 对齐 CC L332-335 — 仅 function hook 的 matcher 视图. */
    public record FunctionHookMatcher(String matcher, List<FunctionHook> hooks) {
        public FunctionHookMatcher {
            hooks = hooks == null ? List.of() : hooks;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 (add)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 注册 command/prompt/agent/http hook 到 session · 对齐 CC L68-86 addSessionHook.
     *
     * @param sessionId    CC original: sessionId; 会话 ID
     * @param event         CC original: event; hook 事件类型
     * @param matcher       CC original: matcher; 工具名匹配模式
     * @param hook          CC original: hook; HookCommand (可持久化类型)
     * @param onHookSuccess CC original: onHookSuccess (L9-12); 执行成功回调, 可 null
     * @param skillRoot     CC original: skillRoot (L35); skill 作用域, 可 null
     */
    public void addSessionHook(String sessionId, HookEventType event, String matcher, HookCommand hook,
                               OnHookSuccess onHookSuccess, String skillRoot) {
        // [3-1 拆开] HookCommand 已独立 (不再 extends SessionHook) → 具体实现均 implements SessionHook,
        // 此处收窄为 union 载体 (CC: HookCommand | FunctionHook)。
        addHookToSession(sessionId, event, matcher, (SessionHook) hook, onHookSuccess, skillRoot);
    }

    /**
     * 注册 function hook (内存回调) 到 session · 对齐 CC L93-115 addFunctionHook.
     *
     * <p>id 缺省生成 {@code function-hook-<ts>-<rand>} (CC L105); timeout 缺省 5000 (CC L109).
     *
     * @param sessionId    会话 ID
     * @param event        hook 事件类型
     * @param matcher      工具名匹配模式
     * @param callback     内存回调 (CC L28)
     * @param errorMessage 拦截时错误提示 (CC L29)
     * @param timeout      超时毫秒; null → 5000 (CC L109)
     * @param id           自定义 id; null/blank → 自动生成 (CC L105)
     * @return 生成的 hook id (供 {@link #removeFunctionHook} 移除)
     */
    public String addFunctionHook(String sessionId, HookEventType event, String matcher,
                                  FunctionHookCallback callback, String errorMessage,
                                  Long timeout, String id) {
        String resolvedId = (id != null && !id.isBlank())
                ? id : "function-hook-" + System.currentTimeMillis() + "-" + Math.random();
        long resolvedTimeout = timeout != null ? timeout : FunctionHook.DEFAULT_TIMEOUT_MS;
        FunctionHook hook = new FunctionHook(resolvedId, resolvedTimeout, callback, errorMessage, null);
        addHookToSession(sessionId, event, matcher, hook, null, null);
        if (log.isDebugEnabled()) {
            log.debug("[H5] function hook 已注册: session={} event={} matcher={} id={} timeout={}ms",
                    sessionId, event, matcher, resolvedId, resolvedTimeout);
        }
        return resolvedId;
    }

    /**
     * 内部注册入口 · 对齐 CC L167-216 addHookToSession.
     *
     * <p>WHY: matcher+skillRoot 分组追加 — 同组追加 hooks 不新建 matcher (CC L181-205);
     * 不同 skillRoot 视为不同 matcher (skill-scoped hooks 独立分组).
     */
    private void addHookToSession(String sessionId, HookEventType event, String matcher, SessionHook hook,
                                  OnHookSuccess onHookSuccess, String skillRoot) {
        synchronized (this) {
            SessionStore store = sessions.computeIfAbsent(sessionId, k -> new SessionStore(new LinkedHashMap<>()));
            Map<HookEventType, List<SessionHookMatcher>> hooks = new LinkedHashMap<>(store.hooks());
            List<SessionHookMatcher> eventMatchers = new ArrayList<>(hooks.getOrDefault(event, List.of()));

            int existingMatcherIndex = -1;
            for (int i = 0; i < eventMatchers.size(); i++) {
                SessionHookMatcher m = eventMatchers.get(i);
                if (Objects.equals(m.matcher(), matcher) && Objects.equals(m.skillRoot(), skillRoot)) {
                    existingMatcherIndex = i;
                    break;
                }
            }
            if (existingMatcherIndex >= 0) {
                // 同组追加 (CC L187-194)
                SessionHookMatcher existing = eventMatchers.get(existingMatcherIndex);
                List<SessionHookEntry> appended = new ArrayList<>(existing.hooks());
                appended.add(new SessionHookEntry(hook, onHookSuccess));
                eventMatchers.set(existingMatcherIndex,
                        new SessionHookMatcher(existing.matcher(), existing.skillRoot(), appended));
            } else {
                // 新建 matcher (CC L196-204)
                eventMatchers.add(new SessionHookMatcher(matcher, skillRoot,
                        List.of(new SessionHookEntry(hook, onHookSuccess))));
            }
            hooks.put(event, eventMatchers);
            sessions.put(sessionId, new SessionStore(hooks));
        }
        if (log.isDebugEnabled()) {
            log.debug("[H5] session hook 已注册: session={} event={} matcher={} type={}",
                    sessionId, event, matcher, hook.type());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 移除 (remove)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 按 id 移除 function hook · 对齐 CC L120-162 removeFunctionHook.
     *
     * <p>WHY: 从<b>所有 matcher</b>过滤 — 同 event 下不同 matcher 的同名 id hook 都清除
     * (CC L134-146 map → filter). matcher 内全部 function hook 被清空时, 该 matcher 一并移除.
     */
    public void removeFunctionHook(String sessionId, HookEventType event, String hookId) {
        synchronized (this) {
            SessionStore store = sessions.get(sessionId);
            if (store == null) {
                return;
            }
            Map<HookEventType, List<SessionHookMatcher>> hooks = new HashMap<>(store.hooks());
            List<SessionHookMatcher> eventMatchers = hooks.get(event);
            if (eventMatchers == null) {
                return;
            }
            List<SessionHookMatcher> updatedMatchers = new ArrayList<>(eventMatchers.size());
            for (SessionHookMatcher matcher : eventMatchers) {
                List<SessionHookEntry> updatedHooks = matcher.hooks().stream()
                        .filter(entry -> {
                            SessionHook h = entry.hook();
                            if (h instanceof FunctionHook fh) {
                                return !fh.id().equals(hookId);
                            }
                            return true; // 非 function hook 保留
                        })
                        .toList();
                if (!updatedHooks.isEmpty()) {
                    updatedMatchers.add(new SessionHookMatcher(matcher.matcher(), matcher.skillRoot(), updatedHooks));
                }
            }
            if (updatedMatchers.isEmpty()) {
                hooks.remove(event); // 对齐 CC L151-153: 无剩余 matcher → 删 event key
            } else {
                hooks.put(event, updatedMatchers);
            }
            sessions.put(sessionId, new SessionStore(hooks));
        }
        if (log.isDebugEnabled()) {
            log.debug("[H5] function hook 已移除: session={} event={} hookId={}", sessionId, event, hookId);
        }
    }

    /**
     * 按 isHookEqual 移除 command/prompt/agent/http hook · 对齐 CC L225-268 removeSessionHook.
     *
     * <p>WHY: {@link #isHookEqual} 比 command+shell+if 判定身份 (hooksSettings.ts:33-64),
     * 与 settings.json 持久化 hook 的 identity 语义一致.
     */
    public void removeSessionHook(String sessionId, HookEventType event, HookCommand hook) {
        synchronized (this) {
            SessionStore store = sessions.get(sessionId);
            if (store == null) {
                return;
            }
            Map<HookEventType, List<SessionHookMatcher>> hooks = new HashMap<>(store.hooks());
            List<SessionHookMatcher> eventMatchers = hooks.get(event);
            if (eventMatchers == null) {
                return;
            }
            List<SessionHookMatcher> updatedMatchers = new ArrayList<>(eventMatchers.size());
            for (SessionHookMatcher matcher : eventMatchers) {
                List<SessionHookEntry> updatedHooks = matcher.hooks().stream()
                        .filter(entry -> !isHookEqual(entry.hook(), (SessionHook) hook))
                        .toList();
                if (!updatedHooks.isEmpty()) {
                    updatedMatchers.add(new SessionHookMatcher(matcher.matcher(), matcher.skillRoot(), updatedHooks));
                }
            }
            if (updatedMatchers.isEmpty()) {
                hooks.remove(event); // 对齐 CC L257-259: 无剩余 matcher → delete event key
            } else {
                hooks.put(event, updatedMatchers);
            }
            sessions.put(sessionId, new SessionStore(hooks));
        }
        if (log.isDebugEnabled()) {
            log.debug("[H5] session hook 已移除: session={} event={} type={}", sessionId, event, hook.type());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 查询 (query)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 查询 session hooks (不含 function hooks) · 对齐 CC L302-330 getSessionHooks.
     *
     * <p>WHY: 返回结果用于持久化/UI 展示 (getAllHooks hooksSettings.ts:146-158), function hook
     * 不可持久化 → {@link #convertToHookMatchers} 过滤 (CC L288 "Filter out function hooks").
     *
     * <p><b>遍历顺序 (D-05)</b>: 无 event 分支按 {@link HookEventType#HOOK_EVENTS_ORDER}
     * 27 项 CC 固定序遍历 (sessionHooks.ts:322-327 {@code for (const evt of HOOK_EVENTS)}),
     * 与注册顺序无关 — 展示/持久化消费方看到的 key 顺序是 CC 契约, 不是插入序.
     *
     * @param sessionId 会话 ID
     * @param event     事件过滤; null = 返回 store 内全部 event, 按 CC HOOK_EVENTS 序 (CC L322-328)
     * @return event → SessionDerivedHookMatcher[] 映射 (可能为空)
     */
    public Map<HookEventType, List<SessionDerivedHookMatcher>> getSessionHooks(String sessionId, HookEventType event) {
        SessionStore store;
        synchronized (this) {
            store = sessions.get(sessionId);
        }
        Map<HookEventType, List<SessionDerivedHookMatcher>> result = new LinkedHashMap<>();
        if (store == null) {
            return result;
        }
        if (event != null) {
            List<SessionHookMatcher> matchers = store.hooks().get(event);
            if (matchers != null) {
                result.put(event, convertToHookMatchers(matchers));
            }
            return result;
        }
        // D-05: CC 固定序遍历 (sessionHooks.ts:322-327), 跳过 null matcher (与现逻辑同)
        for (HookEventType evt : HookEventType.HOOK_EVENTS_ORDER) {
            List<SessionHookMatcher> matchers = store.hooks().get(evt);
            if (matchers != null) {
                result.put(evt, convertToHookMatchers(matchers));
            }
        }
        return result;
    }

    /**
     * 查询 session function hooks (仅 function) · 对齐 CC L345-392 getSessionFunctionHooks.
     *
     * <p>WHY: function hook 持有内存回调, 执行时需独立取出按 id 调用/超时; 与 command 查询分离
     * (CC L339 "Function hooks are kept separate because they can't be persisted").
     *
     * <p><b>遍历顺序 (D-05)</b>: 无 event 分支同 {@link #getSessionHooks} — 按
     * {@link HookEventType#HOOK_EVENTS_ORDER} 27 项 CC 固定序 (sessionHooks.ts:381-389).
     *
     * @param sessionId 会话 ID
     * @param event     事件过滤; null = 返回 store 内全部 event, 按 CC HOOK_EVENTS 序 (CC L381-389)
     * @return event → FunctionHookMatcher[] 映射; 空 matcher (无 function hook) 被过滤 (CC L367)
     */
    public Map<HookEventType, List<FunctionHookMatcher>> getSessionFunctionHooks(String sessionId, HookEventType event) {
        SessionStore store;
        synchronized (this) {
            store = sessions.get(sessionId);
        }
        Map<HookEventType, List<FunctionHookMatcher>> result = new LinkedHashMap<>();
        if (store == null) {
            return result;
        }
        if (event != null) {
            List<SessionHookMatcher> matchers = store.hooks().get(event);
            if (matchers != null) {
                List<FunctionHookMatcher> fns = extractFunctionHookMatchers(matchers);
                if (!fns.isEmpty()) {
                    result.put(event, fns);
                }
            }
            return result;
        }
        // D-05: CC 固定序遍历 (sessionHooks.ts:381-389)
        for (HookEventType evt : HookEventType.HOOK_EVENTS_ORDER) {
            List<SessionHookMatcher> matchers = store.hooks().get(evt);
            if (matchers != null) {
                List<FunctionHookMatcher> fns = extractFunctionHookMatchers(matchers);
                if (!fns.isEmpty()) {
                    result.put(evt, fns);
                }
            }
        }
        return result;
    }

    /**
     * 是否存在指定 session + event 的 session hook · 对齐 CC hooks.ts:1591
     * {@code appState?.sessionHooks.get(sessionId)?.hooks[hookEvent]} 存在性检查.
     *
     * <p>WHY: {@link HookRegistry#hasHookForEvent(String, String)} 三源检查的 session 源
     * (IMPL-02 D2) —— 只判存在性, 不构建完整 matcher 列表 (与 {@link #getSessionHooks}
     * 不同: 后者过滤 function hooks, 本方法含 function hook, 对齐 CC 原始结构).
     *
     * @param sessionId 会话 ID (null/blank → false)
     * @param event     事件类型 (null → false)
     * @return true = 该 session 该事件有任一 matcher (含 function hook)
     */
    public boolean hasHooksForEvent(String sessionId, HookEventType event) {
        if (sessionId == null || sessionId.isBlank() || event == null) {
            return false;
        }
        SessionStore store;
        synchronized (this) {
            store = sessions.get(sessionId);
        }
        if (store == null) {
            return false;
        }
        List<SessionHookMatcher> matchers = store.hooks().get(event);
        return matchers != null && !matchers.isEmpty();
    }

    /**
     * 查询完整 hook entry (含 onHookSuccess 回调) · 对齐 CC L397-430 getSessionHookCallback.
     *
     * <p>WHY: 执行 session hook 前需要完整 entry — {@code hook} 供执行, {@code onHookSuccess}
     * 供成功后通知 (CC L422-424 find 后返回整个 hookEntry). matcher 参数为空串时匹配所有 matcher
     * (CC L421 {@code matcherEntry.matcher === matcher || matcher === ''}).
     *
     * @param sessionId 会话 ID
     * @param event     事件类型
     * @param matcher   工具名匹配模式; 空/null = 匹配所有 matcher
     * @param hook      要查找的 hook (按 isHookEqual 比较)
     * @return 完整 entry (含 onHookSuccess); 未找到 = empty
     */
    public Optional<SessionHookEntry> getSessionHookCallback(String sessionId, HookEventType event,
                                                             String matcher, SessionHook hook) {
        SessionStore store;
        synchronized (this) {
            store = sessions.get(sessionId);
        }
        if (store == null) {
            return Optional.empty();
        }
        List<SessionHookMatcher> eventMatchers = store.hooks().get(event);
        if (eventMatchers == null) {
            return Optional.empty();
        }
        for (SessionHookMatcher matcherEntry : eventMatchers) {
            // CC L420 {@code matcherEntry.matcher === matcher || matcher === ''}：
            //   matcherEntry.matcher 为 null 时 === 恒等比较可命中 null；Java 需显式 null 处理，
            //   否则 null matcher 注册的 session hook 成功路径 NPE（IMP-E4-06 6g 暴露）。
            boolean matcherEquals = matcherEntry.matcher() == null
                ? matcher == null
                : matcherEntry.matcher().equals(matcher);
            if (matcherEquals || (matcher != null && matcher.isEmpty())) {
                Optional<SessionHookEntry> found = matcherEntry.hooks().stream()
                        .filter(entry -> isHookEqual(entry.hook(), hook))
                        .findFirst();
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 清空 (clear)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 清空指定 session 的全部临时 hook · 对齐 CC L437-447 clearSessionHooks.
     *
     * <p>WHY: CC runAgent.ts:822 sub-agent finally 调用 — 会话结束所有临时 hook 一并释放,
     * 防止 hook 泄漏到下一轮会话复用. 对齐 CC L442 {@code prev.sessionHooks.delete(sessionId)}.
     *
     * @param sessionId 会话 ID
     */
    public void clearSessionHooks(String sessionId) {
        synchronized (this) {
            sessions.remove(sessionId);
        }
        if (log.isDebugEnabled()) {
            log.debug("[H5] session hooks 已清空: session={}", sessionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部转换 / 相等判断
    // ════════════════════════════════════════════════════════════════════════

    /**
     * SessionHookMatcher → SessionDerivedHookMatcher · 对齐 CC L282-293 convertToHookMatchers.
     *
     * <p>WHY: 过滤 function hooks (CC L288-291) — 不可持久化到 HookMatcher 格式; skillRoot 透传.
     */
    private static List<SessionDerivedHookMatcher> convertToHookMatchers(List<SessionHookMatcher> sessionMatchers) {
        List<SessionDerivedHookMatcher> result = new ArrayList<>(sessionMatchers.size());
        for (SessionHookMatcher sm : sessionMatchers) {
            List<HookCommand> commandHooks = new ArrayList<>();
            for (SessionHookEntry entry : sm.hooks()) {
                if (entry.hook() instanceof HookCommand hc) {
                    commandHooks.add(hc);
                }
            }
            result.add(new SessionDerivedHookMatcher(sm.matcher(), sm.skillRoot(), commandHooks));
        }
        return result;
    }

    /**
     * 抽取 function hook matcher · 对齐 CC L357-368 extractFunctionHooks (内联于 getSessionFunctionHooks).
     *
     * <p>WHY: 只保留 function hook (CC L363-365), 空 matcher 过滤 (CC L367 {@code m.hooks.length > 0}).
     */
    private static List<FunctionHookMatcher> extractFunctionHookMatchers(List<SessionHookMatcher> sessionMatchers) {
        List<FunctionHookMatcher> result = new ArrayList<>();
        for (SessionHookMatcher sm : sessionMatchers) {
            List<FunctionHook> functionHooks = new ArrayList<>();
            for (SessionHookEntry entry : sm.hooks()) {
                if (entry.hook() instanceof FunctionHook fh) {
                    functionHooks.add(fh);
                }
            }
            if (!functionHooks.isEmpty()) {
                result.add(new FunctionHookMatcher(sm.matcher(), functionHooks));
            }
        }
        return result;
    }

    /**
     * 判断两个 hook 是否相等 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts:33-64}
     * isHookEqual.
     *
     * <p>WHY (CC L41-43 注释): "We only compare command/prompt content, not timeout; if is part of
     * identity" — 同 command 不同 if 条件是不同 hook (如 setup.sh if=Bash(git *) vs if=Bash(npm *));
     * shell 也是身份一部分, 缺省 'bash'. function hook 无稳定标识, 永远不等 (CC L61-63).
     *
     * @param a 左侧 hook
     * @param b 右侧 hook
     * @return 相等与否
     */
    private static boolean isHookEqual(SessionHook a, SessionHook b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.type().equals(b.type())) {
            return false; // CC L37: type 不同 → false
        }
        // function hook 无稳定标识 → 永远不等 (CC L61-63)
        if (a instanceof FunctionHook) {
            return false;
        }
        if (a instanceof CommandHook ca && b instanceof CommandHook cb) {
            // CC L46-54: command + shell(缺省 bash) + sameIf
            return Objects.equals(ca.command(), cb.command())
                    && defaultShell(ca.shell()).equals(defaultShell(cb.shell()))
                    && sameIf(ca.ifCondition(), cb.ifCondition());
        }
        if (a instanceof PromptHook pa && b instanceof PromptHook pb) {
            // CC L55-56: prompt + sameIf
            return Objects.equals(pa.prompt(), pb.prompt()) && sameIf(pa.ifCondition(), pb.ifCondition());
        }
        if (a instanceof AgentHook aa && b instanceof AgentHook ab) {
            // CC L57-58: prompt + sameIf
            return Objects.equals(aa.prompt(), ab.prompt()) && sameIf(aa.ifCondition(), ab.ifCondition());
        }
        if (a instanceof HttpHook ha && b instanceof HttpHook hb) {
            // CC L59-60: url + sameIf
            return Objects.equals(ha.url(), hb.url()) && sameIf(ha.ifCondition(), hb.ifCondition());
        }
        return false;
    }

    /** CC DEFAULT_HOOK_SHELL='bash' (shellProvider.ts:2) — shell 缺省值归一 (CC L48-52). */
    private static String defaultShell(String shell) {
        return (shell == null || shell.isBlank()) ? CommandHook.DEFAULT_SHELL : shell;
    }

    /** CC L43-44 sameIf: {@code (x.if ?? '') === (y.if ?? '')} — if 缺省 '' 参与身份比较. */
    private static boolean sameIf(String a, String b) {
        return Objects.equals(a == null ? "" : a, b == null ? "" : b);
    }
}
