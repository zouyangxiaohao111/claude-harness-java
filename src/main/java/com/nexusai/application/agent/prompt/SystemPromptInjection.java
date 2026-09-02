package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * System prompt 注入（cache breaking）· 对齐 CC {@code getSystemPromptInjection}/
 * {@code setSystemPromptInjection}
 * （CC original: {@code systemPromptInjection} 模块级 let 变量
 * (Open-ClaudeCode/src/context.ts:23-34)）。
 *
 * <p>ant-only 临时调试状态：注入值非 null 时，getSystemContext 产出
 * {@code cacheBreaker: "[CACHE_BREAKER: <injection>]"}（context.ts:143-147）。
 *
 * <p><b>setter 双清缓存（context.ts:29-34）</b>：值变更即清
 * {@code getUserContext.cache} + {@code getSystemContext.cache}。
 * Java 端 getSystemContext/getUserContext 缓存为<b>会话级实例字段</b>
 * （SystemPromptContextProvider 实例，concern #1 决议）——本类维护已注册 provider 的
 * 缓存清理回调表，set 变更时逐个通知（等价 CC 进程级 memoize.cache.clear）。
 */
public final class SystemPromptInjection {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptInjection.class);

    /** 当前注入值 · CC original: {@code systemPromptInjection: string | null}（context.ts:23） */
    private static volatile String value = null;

    /** 已注册的缓存清理回调（每个会话级 SystemPromptContextProvider 构造时注册一个，close 时注销） */
    private static final List<Runnable> CACHE_CLEAR_HOOKS = new CopyOnWriteArrayList<>();

    /**
     * 已注册的 <b>user-only</b> 缓存清理回调 · [IMP-SP2-08] SP-07 △-6 通道（CC
     * postCompactCleanup.ts:51-60 main-thread 段只 {@code getUserContext.cache.clear?.()}，
     * 不碰 getSystemContext/gitStatus 缓存）。每个会话级 SystemPromptContextProvider 构造时
     * 注册一个（仅清 userContextCache/userContextComputed），close 时注销。
     */
    private static final List<Runnable> USER_CACHE_CLEAR_HOOKS = new CopyOnWriteArrayList<>();

    private SystemPromptInjection() {
    }

    /**
     * 获取注入值 · 对齐 CC {@code getSystemPromptInjection}（context.ts:25-27）。
     *
     * @return 当前注入值；未设置时 {@code null}
     */
    public static String getSystemPromptInjection() {
        return value;
    }

    /**
     * 设置注入值 · 对齐 CC {@code setSystemPromptInjection}（context.ts:29-34）。
     *
     * <p>赋值后立即双清已注册 provider 的 getSystemContext/getUserContext 缓存
     * （CC context.ts:32-33 双 {@code cache.clear?.()}）。
     *
     * @param newValue 新注入值；传 {@code null} 清除注入
     */
    public static void setSystemPromptInjection(String newValue) {
        value = newValue;
        int hooks = CACHE_CLEAR_HOOKS.size();
        for (Runnable hook : CACHE_CLEAR_HOOKS) {
            hook.run();
        }
        log.info("[SystemPromptInjection] 注入值已变更并双清 {} 个 provider 缓存（对齐 CC context.ts:29-34）", hooks);
    }

    /**
     * 注册缓存清理回调 · 由 SystemPromptContextProvider 构造时调用（本类包级可见）。
     *
     * @param hook 清理该 provider 会话级 getSystemContext/getUserContext 缓存
     */
    static void registerCacheClearHook(Runnable hook) {
        if (hook != null) {
            CACHE_CLEAR_HOOKS.add(hook);
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptInjection] 注册缓存清理回调，当前 {} 个", CACHE_CLEAR_HOOKS.size());
            }
        }
    }

    /**
     * 注销缓存清理回调 · RES-R5-4 补 remove 通道（Java 内部卫生，非 CC 对齐项）。
     *
     * <p>与 {@link #registerCacheClearHook(Runnable)} 成对：provider 生命周期结束
     * （{@code close()}）时调用，避免 {@link #CACHE_CLEAR_HOOKS} 静态表随服务实例创建
     * 有界累积（每 provider +1 永不释放）。
     *
     * <p>幂等：回调不在表中（重复注销 / 未注册 / null）→ no-op，不抛异常
     * （{@link java.util.concurrent.CopyOnWriteArrayList#remove} 对缺席元素返回 false）。
     * 注销后 {@link #setSystemPromptInjection} / {@link #clearAllProviderCaches} 不再通知
     * 该 provider，已注册的仍被通知（不变量：setter/clearAll 通知全部存活者）。
     *
     * @param hook 构造时经 {@link #registerCacheClearHook(Runnable)} 注册的同一回调实例
     */
    static void unregisterCacheClearHook(Runnable hook) {
        if (hook != null && CACHE_CLEAR_HOOKS.remove(hook)) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptInjection] 注销缓存清理回调，当前 {} 个", CACHE_CLEAR_HOOKS.size());
            }
        }
    }

    /**
     * 注册 <b>user-only</b> 缓存清理回调 · [IMP-SP2-08] 由 SystemPromptContextProvider 构造时调用
     * （本类包级可见）。与 {@link #registerCacheClearHook(Runnable)} 的区别：仅清该 provider 的
     * getUserContext 缓存，保留 getSystemContext/gitStatus 缓存 —— 对齐 CC postCompactCleanup.ts:51-60
     * main-thread compact 只清 {@code getUserContext.cache}（SP-07 △-6：旧 Java 实现经全清通道
     * 双清 system/user 为多清偏差，R1 修订并入 IMP-SP2-08）。
     *
     * @param hook 清理该 provider 会话级 getUserContext 缓存
     */
    static void registerUserCacheClearHook(Runnable hook) {
        if (hook != null) {
            USER_CACHE_CLEAR_HOOKS.add(hook);
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptInjection] 注册 user-only 缓存清理回调，当前 {} 个", USER_CACHE_CLEAR_HOOKS.size());
            }
        }
    }

    /**
     * 注销 <b>user-only</b> 缓存清理回调 · [IMP-SP2-08] 与
     * {@link #registerUserCacheClearHook(Runnable)} 成对（provider 生命周期结束 close() 时调用，
     * 静态表不随实例创建有界累积）。幂等：回调不在表中 → no-op，不抛异常。
     *
     * @param hook 构造时经 {@link #registerUserCacheClearHook(Runnable)} 注册的同一回调实例
     */
    static void unregisterUserCacheClearHook(Runnable hook) {
        if (hook != null && USER_CACHE_CLEAR_HOOKS.remove(hook)) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptInjection] 注销 user-only 缓存清理回调，当前 {} 个", USER_CACHE_CLEAR_HOOKS.size());
            }
        }
    }

    /**
     * 清空全部已注册 provider 的 <b>getUserContext</b> 缓存 · [IMP-SP2-08] SP-07 △-6 通道 ·
     * 对齐 CC postCompactCleanup.ts:51-60 main-thread 段 {@code getUserContext.cache.clear?.()}。
     *
     * <p><b>与 {@link #clearAllProviderCaches()} 的语义边界</b>：compact 清理面只清 user 通道
     * （CC 不清 getSystemContext.cache，postCompactCleanup.ts:59 只一行 clear）；setter 双清
     * （context.ts:32-33）与 /clear 链（caches.ts:52-53）仍走全清通道 —— 两条通道各自对齐 CC
     * 对应调用点，不合并。
     *
     * @return 触发清理的 provider 缓存数（日志用）
     */
    public static int clearUserOnlyProviderCaches() {
        int hooks = USER_CACHE_CLEAR_HOOKS.size();
        for (Runnable hook : USER_CACHE_CLEAR_HOOKS) {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("[SystemPromptInjection] clearUserOnlyProviderCaches 清理回调异常（不阻断其他缓存）: {}",
                    e.getMessage());
            }
        }
        log.info("[SystemPromptInjection] clearUserOnlyProviderCaches: 已清空 {} 个 provider 的 user 上下文缓存（对齐 CC postCompactCleanup.ts:52 getUserContext.cache.clear）",
            hooks);
        return hooks;
    }

    /**
     * 清空全部已注册 provider 的 getSystemContext/getUserContext 缓存 · 对齐 CC
     * {@code getUserContext.cache.clear()}（postCompactCleanup.ts:52）。
     *
     * <p><b>FIX-CL 接线</b>：PostCompactCleanup main-thread 分支调用 —— 压缩后 user/system 上下文
     * 缓存必须失效，否则后续 fetchSystemPromptParts 命中陈旧 claudeMd（含被压缩改写的记忆文件）。
     *
     * @return 触发清理的 provider 缓存数（日志用）
     */
    public static int clearAllProviderCaches() {
        int hooks = CACHE_CLEAR_HOOKS.size();
        for (Runnable hook : CACHE_CLEAR_HOOKS) {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("[SystemPromptInjection] clearAllProviderCaches 清理回调异常（不阻断其他缓存）: {}",
                    e.getMessage());
            }
        }
        log.info("[SystemPromptInjection] clearAllProviderCaches: 已清空 {} 个 provider 的 system/user 上下文缓存（对齐 CC getUserContext.cache.clear）",
            hooks);
        return hooks;
    }
}
