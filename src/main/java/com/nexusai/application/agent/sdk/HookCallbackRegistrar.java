package com.nexusai.application.agent.sdk;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 公开回调 hook 注册面 · 对齐 CC {@code registerHookCallbacks}
 * (Open-ClaudeCode/src/bootstrap/state.ts:1419-1444) + {@code HookCallbackMatcher}
 * (Open-ClaudeCode/src/types/hooks.ts:228-232).
 *
 * <p>WHY (hooks_v3 WF1-X1 SDK/headless 轴): CC 的 SDK 回调 hook（type:'callback' 函数式
 * hook）经公开 API {@code registerHookCallbacks} 注册（print.ts:4435-4448 的 SDK init
 * request.hooks → createHookCallback → registerHookCallbacks），多次调用按事件 merge
 * （push 非覆盖，state.ts:1426-1432），注册后经 getHooksConfig 并入统一匹配链执行。
 * Java 端此前只有内部程序化注册（HookRegistry.register / registerPluginHook），缺公开
 * SDK 注册面（X-WF46-03 S2 缺、S3 createHookCallback 桥接缺）——本类提供该公开面。
 *
 * <p><b>字段来源对照</b>:
 * <ul>
 *   <li>{@link RegisteredHookCallback} — CC original: {@code HookCallback}
 *       (types/hooks.ts:210-226): callback / timeout? / internal?（type:'callback' 恒定，
 *       Java 用 {@link GenericHook} 表达函数式回调）+ 所在 matcher 的 {@code matcher}
 *       匹配串（types/hooks.ts:229，SDK init matcher 展开后随回调携带）</li>
 *   <li>{@link #registerHookCallbacks} — CC original: {@code registerHookCallbacks}
 *       (state.ts:1419-1434): 多次调用 merge（push 非覆盖）</li>
 *   <li>{@link #getRegisteredHooks} — CC original: {@code getRegisteredHooks}
 *       (state.ts:1436-1440)</li>
 *   <li>{@link #clearRegisteredHooks} — CC original: {@code clearRegisteredHooks}
 *       (state.ts:1442-1444)</li>
 *   <li>{@link HookRegistrar} — 执行链桥接 · 对齐 CC getHooksConfig 把 registeredHooks
 *       并入统一匹配链 (hooks.ts:1518-1529) 的 Java 等价：生产接 {@code HookRegistry.register}
 *       （含 internal/pluginId 透传，随 H-WF7 patch-note 落地），测试接记录桩（与
 *       SessionFileAccessHooks.PostToolUseRegistrar 注入式模式一致）</li>
 * </ul>
 *
 * <p><b>createHookCallback 桥接（S3）</b>: CC structuredIO.createHookCallback
 * (structuredIO.ts:661-689) 创建向外部 SDK consumer 发 {@code hook_callback} 控制请求并
 * 等待 JSON 响应的回调 hook —— 依赖 SDK 控制通道，随 5-W7-1「本期规划 SDK/headless 入口」
 * 后续阶段实现，本类先落注册面。
 *
 * <p><b>并发</b>: 方法 synchronized（CC 单线程模块态，Java 显式同步以保合并语义）。
 *
 * @see com.nexusai.application.agent.permission.hook.HookRegistry
 * @since hooks_v3 H-WF7-01
 */
public final class HookCallbackRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HookCallbackRegistrar.class);

    /**
     * SDK 注册回调 hook · CC original: {@code HookCallback} (types/hooks.ts:210-226)
     * + 所在 matcher 的 {@code matcher} 匹配串 (types/hooks.ts:229).
     *
     * <p><b>matcher 携带（硬约束 #5 禁止简单化）</b>: CC SDK init（print.ts:4435-4448）
     * 把每个 matcher 展开为 {@code { matcher: matcher.matcher, hooks: callbacks }} ——
     * matcher 串是匹配过滤依据（getMatchingHooks 按 matcher 匹配，hooks.ts:1681-1686）。
     * Java 端执行链（HookRegistry）当前尚无 matcher 串匹配面，本字段在注册面携带保数据
     * 不丢，SDK/headless 入口（5-W7-1 本期规划后续阶段）落地匹配面时直接可用。
     *
     * @param name       注册名（执行链唯一键；建议携带来源前缀防碰撞）
     * @param matcher    CC original: HookCallbackMatcher.matcher（types/hooks.ts:229）；
     *                   同一 matcher 组的回调共享该串；null = 不限定（匹配全部）
     * @param hook       Java 函数式回调（CC {@code callback: (...) => Promise<HookJSONOutput>}
     *                   等价，{@link GenericHook#onEvent} 返回 {@code HookResult}）
     * @param timeoutMs  CC original: timeout（types/hooks.ts:213）；null = 无
     * @param internal   CC original: internal（types/hooks.ts:214）；internal=true →
     *                   不入 tengu_run_hook 的 userHooks 计数（isInternalHook，hooks.ts:1440-1442）
     * @param pluginId   CC original: PluginHookMatcher.pluginId（types/hooks.ts:230 域；
     *                   用于 pluginHookCounts 官方/third-party 分类，hooks.ts:1461-1478）
     */
    public record RegisteredHookCallback(
            String name,
            String matcher,
            GenericHook hook,
            Long timeoutMs,
            boolean internal,
            String pluginId) {

        public RegisteredHookCallback {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Hook name is blank");
            }
            if (hook == null) {
                throw new IllegalArgumentException("Hook is null");
            }
        }
    }

    /**
     * 执行链桥接 · CC getHooksConfig 把 registeredHooks 并入统一匹配链 (hooks.ts:1518-1529)
     * 的 Java 等价.
     *
     * <p>生产实现把 {@code internal}/{@code pluginId} 透传给 {@code HookRegistry}（随
     * H-WF7 patch-note 新增 internal 跟踪 + pluginHookCounts 装配点）；测试接记录桩验证
     * 合并语义.
     *
     * @param internal 透传 RegisteredHookCallback.internal（HookRegistry 记录为 internal hook，
     *                 tengu_run_hook userHooks 排除）
     * @param pluginId 透传 RegisteredHookCallback.pluginId（pluginHookCounts 分类依据）
     * @param events   监听事件（CC HookEvent 名映射后的 {@link HookEventType}）
     */
    @FunctionalInterface
    public interface HookRegistrar {
        void register(String name, GenericHook hook, boolean internal, String pluginId,
                      HookEventType... events);

        /** 注销（clearRegisteredHooks 半环；CC clearRegisteredHooks 只清 STATE 引用） */
        default void unregister(String name) {
            // 默认 no-op —— 生产桥接按需覆写
        }
    }

    /** CC STATE.registeredHooks（state.ts:1422-1423）· event → matcher list. */
    private final Map<String, List<RegisteredHookCallback>> registeredHooks = new LinkedHashMap<>();

    private final HookRegistrar bridge;

    /**
     * @param bridge 执行链桥接（生产传 HookRegistry 适配器；测试传记录桩）
     */
    public HookCallbackRegistrar(HookRegistrar bridge) {
        this.bridge = bridge;
    }

    /**
     * SDK 公开回调 hook 注册 · CC original: {@code registerHookCallbacks}
     * (state.ts:1419-1434).
     *
     * <p>语义（严格对齐 CC）:
     * <ol>
     *   <li>可多次调用，按事件 <b>merge</b>（push 非覆盖，:1426-1432）——重复注册同一
     *       event 追加而非覆盖</li>
     *   <li>注册的同时经 {@link HookRegistrar} 桥接进执行链（CC getHooksConfig 并入
     *       匹配链的 Java 表达）</li>
     *   <li>未知事件名（CC HOOK_EVENTS 27 项之外）→ 记录 warn 并跳过注册</li>
     * </ol>
     *
     * @param hooks CC original: {@code Partial<Record<HookEvent, RegisteredHookMatcher[]>>}
     *              — event(CC PascalCase 名) → 回调 matcher 列表
     */
    public synchronized void registerHookCallbacks(
            Map<String, List<RegisteredHookCallback>> hooks) {
        if (hooks == null) {
            return;
        }
        for (Map.Entry<String, List<RegisteredHookCallback>> e : hooks.entrySet()) {
            String event = e.getKey();
            List<RegisteredHookCallback> matchers = e.getValue();
            if (event == null || event.isBlank() || matchers == null || matchers.isEmpty()) {
                continue;
            }
            // 1. merge（push 非覆盖, CC state.ts:1426-1432）
            registeredHooks.computeIfAbsent(event, k -> new ArrayList<>()).addAll(matchers);
            // 2. 桥接进执行链（CC getHooksConfig hooks.ts:1518-1529）
            if (bridge == null) {
                continue;
            }
            HookEventType eventType = HookEventType.fromCcName(event);
            if (eventType == null) {
                if (log.isWarnEnabled()) {
                    log.warn("HOOK registerHookCallbacks: 未知事件名 '{}' (CC HOOK_EVENTS 27 项之外), 跳过注册",
                        event);
                }
                continue;
            }
            for (RegisteredHookCallback cb : matchers) {
                bridge.register(cb.name(), cb.hook(), cb.internal(), cb.pluginId(), eventType);
                if (log.isDebugEnabled()) {
                    log.debug("HOOK registerHookCallbacks: 桥接注册 event={} name={} internal={} pluginId={}",
                        event, cb.name(), cb.internal(), cb.pluginId());
                }
            }
        }
    }

    /**
     * 已注册 SDK 回调 hook · CC original: {@code getRegisteredHooks} (state.ts:1436-1440).
     *
     * @return event → matcher 列表的不可变浅拷贝（merge 语义下可观测）
     */
    public synchronized Map<String, List<RegisteredHookCallback>> getRegisteredHooks() {
        Map<String, List<RegisteredHookCallback>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<RegisteredHookCallback>> e : registeredHooks.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 清空已注册 SDK 回调 hook · CC original: {@code clearRegisteredHooks} (state.ts:1442-1444).
     *
     * <p>CC 语义：置 {@code STATE.registeredHooks = null}，后续 getHooksConfig 不再并入
     * 匹配链 → 已注册 hook 停止执行。Java 端 bridge 已把 hook 注册进执行链，故同步经
     * {@link HookRegistrar#unregister} 逐名注销（生产桥接按需覆写）。
     */
    public synchronized void clearRegisteredHooks() {
        if (bridge != null) {
            for (List<RegisteredHookCallback> list : registeredHooks.values()) {
                for (RegisteredHookCallback cb : list) {
                    bridge.unregister(cb.name());
                }
            }
        }
        registeredHooks.clear();
    }
}
