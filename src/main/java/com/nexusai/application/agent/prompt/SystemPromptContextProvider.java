package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * System / User 上下文提供者 · 对齐 CC {@code getSystemContext} + {@code getUserContext} +
 * {@code fetchSystemPromptParts} + {@code appendSystemContext}
 * （CC original: {@code getSystemContext}/{@code getUserContext} 进程级 memoize async
 * (Open-ClaudeCode/src/context.ts:116-189)、{@code fetchSystemPromptParts}
 * (utils/queryContext.ts:44-74)、{@code appendSystemContext} (utils/api.ts:437-447)）。
 *
 * <p><b>会话级实例（concern #1 决议）</b>：CC lodash memoize 为进程级全局；Spring 多会话服务下
 * 会跨会话串 gitStatus/claudeMd。本类按会话构建（随 AgentState 生命周期），getSystemContext /
 * getUserContext 缓存为<b>实例字段</b>（会话内只算一次，对齐 CAP-SP07-B1 与 IMP-SP-02 会话级模式）。
 *
 * <p><b>cacheBreaker feature 门（concern #2 决议）</b>：CC {@code feature('BREAK_CACHE_COMMAND')}
 * 是 ant 内部 flag（context.ts:131/143）。Java 用 {@code System.getenv("BREAK_CACHE_COMMAND")}
 * truthy 作门（默认关，对齐 CC 默认关），cacheBreaker 机制照常实现（FeatureFlags 越界改留主 agent 定夺）。
 *
 * <p><b>currentDate 属 user 通道（I-10）</b>：用会话冻结 {@code sessionStartDate}
 * （AgentState 构造时取本地日），跨午夜不陈旧；随压缩 replaceMessages 不清除。
 */
public class SystemPromptContextProvider {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptContextProvider.class);

    /** CC original: CLAUDE_CODE_REMOTE（context.ts:125，CCR 时跳过 git status） */
    private static final String CLAUDE_CODE_REMOTE_ENV = "CLAUDE_CODE_REMOTE";

    /** CC original: CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS（gitSettings.ts，禁 git 指令时跳过） */
    private static final String DISABLE_GIT_INSTRUCTIONS_ENV = "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS";

    /** CC original: BREAK_CACHE_COMMAND feature（context.ts:131/143，Java 用 env truthy 作门） */
    private static final String BREAK_CACHE_COMMAND_ENV = "BREAK_CACHE_COMMAND";

    /**
     * 可注入环境变量查询 · 测试注入假实现避免改真实进程环境（GitRunner 假 runner 同款先例）。
     *
     * <p>CC original: {@code process.env.X}（context.ts:125/131，envUtils.ts:32-37）。
     */
    @FunctionalInterface
    public interface Environment {
        String get(String key);
    }

    private final String sessionStartDate;
    private final UserContextProvider userContextProvider;
    private final GitStatusProvider gitStatusProvider;
    private final Environment environment;

    /**
     * 本 provider 注册到 {@link SystemPromptInjection#CACHE_CLEAR_HOOKS} 的缓存清理回调 ·
     * RES-R5-4：保存<b>同一实例</b>供 {@link #close()} 注销（register/unregister 成对，
     * 静态表不再有界累积）。
     */
    private final Runnable cacheClearHook = this::clearCaches;

    /**
     * [IMP-SP2-08] 本 provider 注册到 {@link SystemPromptInjection#USER_CACHE_CLEAR_HOOKS} 的
     * <b>user-only</b> 清理回调 · 仅清 getUserContext 缓存（保留 systemContext/gitStatus）——
     * 对齐 CC postCompactCleanup.ts:51-60 main-thread compact 只清 {@code getUserContext.cache}
     * （SP-07 △-6）。保存<b>同一实例</b>供 {@link #close()} 注销（register/unregister 成对）。
     */
    private final Runnable userCacheClearHook = this::clearUserContextCache;

    /** 会话级 getSystemContext 缓存 · CC original: memoize (context.ts:116) */
    private volatile Map<String, String> systemContextCache = null;
    private volatile boolean systemContextComputed = false;

    /** 会话级 getUserContext 缓存 · CC original: memoize (context.ts:155) */
    private volatile Map<String, String> userContextCache = null;
    private volatile boolean userContextComputed = false;

    /**
     * @param sessionStartDate    会话冻结日期（{@code "YYYY-MM-DD"}，取自 AgentState.sessionStartDate，
     *                            对齐 CC getSessionStartDate 会话冻结 I-10）
     * @param userContextProvider user 通道提供者（claudeMd + currentDate + prependUserContext）
     * @param gitStatusProvider   git 状态提供者（getGitStatus 完整链）
     */
    public SystemPromptContextProvider(String sessionStartDate,
                                       UserContextProvider userContextProvider,
                                       GitStatusProvider gitStatusProvider) {
        this(sessionStartDate, userContextProvider, gitStatusProvider, System::getenv);
    }

    /**
     * 测试注入构造：可替换环境查询。
     *
     * @param environment 环境变量查询（默认 {@code System::getenv}；测试注入假实现）
     */
    public SystemPromptContextProvider(String sessionStartDate,
                                       UserContextProvider userContextProvider,
                                       GitStatusProvider gitStatusProvider,
                                       Environment environment) {
        this.sessionStartDate = sessionStartDate;
        this.userContextProvider = userContextProvider;
        this.gitStatusProvider = gitStatusProvider;
        this.environment = environment != null ? environment : System::getenv;
        // setter 变更即双清本 provider 缓存（CC context.ts:29-34）· 同一实例注册（供 close 注销）
        SystemPromptInjection.registerCacheClearHook(cacheClearHook);
        // [IMP-SP2-08] user-only 通道注册：compact 清理面只清 getUserContext（CC postCompactCleanup.ts:51-60）
        SystemPromptInjection.registerUserCacheClearHook(userCacheClearHook);
    }

    /**
     * 生命周期终结 · RES-R5-4 注销通道：从 {@link SystemPromptInjection#CACHE_CLEAR_HOOKS}
     * 移除本 provider 的缓存清理回调，使静态表不再随实例创建有界累积（register/unregister 成对）。
     *
     * <p>幂等：重复调用 / 回调已注销 → no-op（{@code SystemPromptInjection.unregisterCacheClearHook}
     * 对缺席元素不抛异常）。调用方在 provider 生命周期结束（服务实例销毁 / 会话结束）时调用；
     * 注销后 {@code setSystemPromptInjection} / {@code clearAllProviderCaches} 不再通知本 provider，
     * 存活的 provider 仍被通知（不变量：setter/clearAll 通知全部存活者）。
     *
     * <p><b>约定</b>：无 CC 等价（CC memoize 进程级、getSystemContext 不销毁）；本方法为 Java
     * 内部卫生（09 §十一 R5-4 用户拍板），不改变任何缓存清理语义。
     */
    public void close() {
        SystemPromptInjection.unregisterCacheClearHook(cacheClearHook);
        // [IMP-SP2-08] user-only 通道成对注销（构造注册的同一实例，静态表不再累积）
        SystemPromptInjection.unregisterUserCacheClearHook(userCacheClearHook);
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptContextProvider] close: 已注销缓存清理回调（RES-R5-4，静态表不再累积）");
        }
    }

    /**
     * 获取 system 通道上下文 · 会话级 memoize（对齐 CC getSystemContext context.ts:116-150）。
     *
     * <p>门控（I-14）：{@code CLAUDE_CODE_REMOTE} truthy 或禁 git 指令 →
     * gitStatus 恒 {@code null}（context.ts:124-128）。cacheBreaker 仅在
     * BREAK_CACHE_COMMAND 门开启且注入值非 null 时产出
     * {@code [CACHE_BREAKER: <injection>]}（context.ts:131-147）。
     *
     * <p><b>SP-07 △-5 并发单飞</b>：volatile 双检非原子（A 计算期间 B 亦进入计算体 →
     * 重复计算）；改 {@code synchronized (this)} 单飞——首检命中直接返回，未命中持锁二次检
     * 后计算，并发调用方阻塞于监视器共享同一次计算（对齐 CC lodash memoize promise 共享）。
     * 无重入风险：计算体仅调 gitStatusProvider / SystemPromptInjection / userContextProvider，
     * 均不回入本锁（git 子进程秒级持锁期间并发方串行等待，正是 CC promise 共享语义）。
     *
     * @return map：含 {@code gitStatus}? / {@code cacheBreaker}?（均条件包含）
     */
    public Map<String, String> getSystemContext() {
        if (systemContextComputed) {
            return systemContextCache;
        }
        synchronized (this) {
            if (systemContextComputed) {
                return systemContextCache;
            }
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptContextProvider] getSystemContext 开始");
            }

            String gitStatus = shouldSkipGitStatus()
                ? null
                : gitStatusProvider.getGitStatus();

            String injection = breakCacheCommandEnabled()
                ? SystemPromptInjection.getSystemPromptInjection()
                : null;

            Map<String, String> result = new LinkedHashMap<>();
            if (gitStatus != null) {
                result.put("gitStatus", gitStatus);
            }
            if (breakCacheCommandEnabled() && injection != null) {
                result.put("cacheBreaker", "[CACHE_BREAKER: " + injection + "]");
            }

            systemContextCache = result;
            systemContextComputed = true;
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptContextProvider] getSystemContext 完成: 耗时 {} ms, hasGitStatus={}, hasCacheBreaker={}",
                    System.currentTimeMillis() - start, gitStatus != null, injection != null);
            }
            return result;
        }
    }

    /**
     * 获取 user 通道上下文 · 会话级 memoize（对齐 CC getUserContext context.ts:155-189）。
     *
     * <p>claudeMd 受 {@code CLAUDE_CODE_DISABLE_CLAUDE_MDS} / bare 模式门控
     * （UserContextProvider 内，SP-07 △-2）；currentDate 恒包含（会话冻结，I-10）。
     *
     * <p><b>SP-07 △-5 并发单飞</b>：同 {@link #getSystemContext()}（synchronized 单飞，
     * 对齐 CC lodash memoize promise 共享）。claudeMd 无独立 memoize：CC getClaudeMds
     * 本身无 memoize（context.ts:155-189 memoize 在 getUserContext 边界），其唯一生产
     * 消费点即本方法（会话级 memoize）——计划字面「UserContextProvider 并发 memoize 单飞」
     * 按此归因落实。
     *
     * @return map：含 {@code claudeMd}?（可读时）/ {@code currentDate}（恒）
     */
    public Map<String, String> getUserContext() {
        if (userContextComputed) {
            return userContextCache;
        }
        synchronized (this) {
            if (userContextComputed) {
                return userContextCache;
            }
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptContextProvider] getUserContext 开始");
            }

            String claudeMd = userContextProvider.claudeMd();

            Map<String, String> result = new LinkedHashMap<>();
            if (claudeMd != null) {
                result.put("claudeMd", claudeMd);
            }
            result.put("currentDate", userContextProvider.currentDate(sessionStartDate));

            userContextCache = result;
            userContextComputed = true;
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptContextProvider] getUserContext 完成: 耗时 {} ms, hasClaudeMd={}",
                    System.currentTimeMillis() - start, claudeMd != null);
            }
            return result;
        }
    }


    /**
     * 三路并行获取 context 部件 · 对齐 CC {@code fetchSystemPromptParts}
     * （CC original: queryContext.ts:44-74）。
     *
     * <p><b>I-13 短路</b>：{@code customSystemPrompt !== undefined} 时
     * defaultSystemPrompt=[]（:62-63）且 systemContext={}（:71）——custom 提示完整替换
     * default，systemContext 会拼到未使用的 default 上故一并跳过。
     *
     * @param customSystemPrompt custom 系统提示（{@code null}=未定义，走 default 组装）
     * @param defaultAssemble    default 系统提示惰性组装入口（仅 custom 未定义时调用；
     *                           对齐 CC {@code getSystemPrompt(tools, model, dirs, mcpClients)}
     *                           queryContext.ts:64-69，Java 端经 Supplier 注入避免硬依赖）
     * @return 三元组（defaultSystemPrompt / userContext / systemContext）
     */
    public SystemPromptParts fetchSystemPromptParts(
            String customSystemPrompt, Supplier<SystemPrompt> defaultAssemble) {
        // CC queryContext.ts:62/71 用 !== undefined 判定（Java null 等价），空串也算 defined → 短路
        boolean custom = customSystemPrompt != null;
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptContextProvider] fetchSystemPromptParts 开始: custom={}", custom);
        }

        CompletableFuture<List<String>> defaultFuture = CompletableFuture.supplyAsync(() -> {
            if (custom) {
                return List.of();
            }
            if (defaultAssemble == null) {
                return List.of();
            }
            return defaultAssemble.get().elements();
        });
        CompletableFuture<Map<String, String>> userFuture =
            CompletableFuture.supplyAsync(this::getUserContext);
        CompletableFuture<Map<String, String>> systemFuture =
            CompletableFuture.supplyAsync(() -> custom ? Map.of() : getSystemContext());

        try {
            CompletableFuture.allOf(defaultFuture, userFuture, systemFuture).join();
            SystemPromptParts parts = new SystemPromptParts(
                defaultFuture.get(), userFuture.get(), systemFuture.get());
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptContextProvider] fetchSystemPromptParts 完成: 耗时 {} ms, "
                        + "defaultSize={}, userKeys={}, systemKeys={}",
                    System.currentTimeMillis() - start, parts.defaultSystemPrompt().size(),
                    parts.userContext().keySet(), parts.systemContext().keySet());
            }
            return parts;
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[SystemPromptContextProvider] fetchSystemPromptParts 失败（fail loud）: {}", e.getMessage());
            throw new IllegalStateException("fetchSystemPromptParts 失败", e);
        }
    }

    /**
     * 把 system 通道上下文并入 system prompt · 对齐 CC {@code appendSystemContext}
     * （CC original: api.ts:437-447）。
     *
     * <p>{@code [...systemPrompt, Object.entries(context).map(([k,v]) => `${k}: ${v}`).join('\n')]}
     * 后 filter(Boolean)——空 context 时 join 为空串被过滤；元素 null/空也过滤。
     *
     * @param systemPrompt 品牌化 system prompt 数组
     * @param context      system 通道上下文 map（{@code key: value} 行，换行拼接为单元素）
     * @return 并入后的系统提示数组（原元素 + 条件 context 块）
     */
    public List<String> appendSystemContext(SystemPrompt systemPrompt, Map<String, String> context) {
        List<String> result = new ArrayList<>();
        if (systemPrompt != null) {
            result.addAll(systemPrompt.elements());
        }
        StringBuilder joined = new StringBuilder();
        if (context != null) {
            int idx = 0;
            for (Map.Entry<String, String> e : context.entrySet()) {
                if (idx++ > 0) {
                    joined.append('\n');
                }
                joined.append(e.getKey()).append(": ").append(e.getValue());
            }
        }
        result.add(joined.toString());
        // 对齐 CC filter(Boolean)：移除 null/空串
        return result.stream().filter(s -> s != null && !s.isEmpty()).toList();
    }

    /**
     * 双清 getSystemContext/getUserContext 缓存 · 由 SystemPromptInjection setter 触发
     * （CC context.ts:32-33）。
     */
    public void clearCaches() {
        systemContextCache = null;
        systemContextComputed = false;
        userContextCache = null;
        userContextComputed = false;
        log.info("[SystemPromptContextProvider] 会话级 systemContext/userContext 缓存已双清（对齐 CC context.ts:32-33）");
    }

    /**
     * [IMP-SP2-08] 仅清 getUserContext 缓存 · 由 SystemPromptInjection
     * {@code USER_CACHE_CLEAR_HOOKS} 触发（PostCompactCleanup main-thread 分支，CC
     * postCompactCleanup.ts:51-60）。systemContext/gitStatus 缓存<b>保留</b> —— CC compact
     * 清理面只 {@code getUserContext.cache.clear?.()}，不清 getSystemContext（SP-07 △-6：
     * 旧 Java 实现双清为多清偏差，compact 后 systemContext 命中缓存不重算）。
     */
    private void clearUserContextCache() {
        userContextCache = null;
        userContextComputed = false;
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptContextProvider] user-only 缓存已清（保留 systemContext，CC postCompactCleanup.ts:51-60）");
        }
    }

    /** 会话冻结日期（测试/审计可见）。 */
    public String sessionStartDate() {
        return sessionStartDate;
    }

    /**
     * gitStatus 是否跳过 · 对齐 CC context.ts:124-128（I-14）。
     *
     * <p>{@code isEnvTruthy(CLAUDE_CODE_REMOTE) || !shouldIncludeGitInstructions()}。
     */
    private boolean shouldSkipGitStatus() {
        boolean remote = isEnvTruthy(environment.get(CLAUDE_CODE_REMOTE_ENV));
        boolean gitDisabled = isEnvTruthy(environment.get(DISABLE_GIT_INSTRUCTIONS_ENV));
        return remote || gitDisabled;
    }

    /** BREAK_CACHE_COMMAND 门是否开启（默认关 · 对齐 CC feature 默认 off）。 */
    private boolean breakCacheCommandEnabled() {
        return isEnvTruthy(environment.get(BREAK_CACHE_COMMAND_ENV));
    }

    /** CC original: isEnvTruthy（envUtils.ts:32-37）——truthy 集合 1/true/yes/on。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }
}
