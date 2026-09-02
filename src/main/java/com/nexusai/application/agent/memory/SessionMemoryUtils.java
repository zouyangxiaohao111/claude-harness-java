package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.compact.CompactSettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session Memory Utils · 重建对齐 CC services/SessionMemory/sessionMemoryUtils.ts.
 *
 * <p><b>DEL-M-07 重建</b>: 旧版按 messageCount 制（{@code stats}/{@code hasMetInitializationThreshold(int,int)}），
 * 非 CC token 语义，0 调用方。本类重建为 CC token 阈值状态机 + 模块级共享状态：
 * <ul>
 *   <li>config 三阈值 record（DEFAULT 10000/5000/3，CC :31-36）</li>
 *   <li>模块态：lastSummarizedMessageId / extractionStartedAt / tokensAtLastExtraction /
 *       sessionMemoryInitialized（CC :39-53）</li>
 *   <li>waitForSessionMemoryExtraction（15s 超时 / 60s stale / 1s sleep，CC :89-105）</li>
 *   <li>阈值谓词 hasMetInitializationThreshold / hasMetUpdateThreshold（CC :173-189）</li>
 *   <li>[V52 token-compact-settings-fix] 提取阈值 DB 实时覆盖：静态槽位注入 CompactSettingsResolver
 *       （同 BoundaryReader/MicroCompactor 先例）→ 阈值谓词读生效配置 {@code getEffectiveSessionMemoryConfig()}
 *       （DB &gt; 内存通道 &gt; DEFAULT，DB 每方法独立实时 selectOneById(1) 无缓存）</li>
 * </ul>
 *
 * <p><b>static 模块态（跨实例共享）</b>: CC 用 TS module-level let 变量（单进程全局）；
 * Java 后端按会话分散。会话态游标（lastSummarizedMessageId / extractionStartedAt /
 * tokensAtLastExtraction / sessionMemoryInitialized）<b>[sm-cursor-sessionize 2026-08-30]</b>
 * 已按 sessionId 键控为 {@code ConcurrentHashMap}（对齐项目铁律「multi-session-vs-cc-single-session」，
 * 消除多会话串扰）；全局态（sessionMemoryConfig / settingsResolver = 调参通道 + DB 只读源）保持
 * static 模块态（跨 SessionMemoryService 实例共享，REQ-12）。
 */
public final class SessionMemoryUtils {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryUtils.class);

    private SessionMemoryUtils() {
        // 工具类不可实例化
    }

    /** CC :12 EXTRACTION_WAIT_TIMEOUT_MS */
    public static final long EXTRACTION_WAIT_TIMEOUT_MS = 15000L;
    /** CC :13 EXTRACTION_STALE_THRESHOLD_MS（1 分钟） */
    public static final long EXTRACTION_STALE_THRESHOLD_MS = 60000L;

    /**
     * 配置三阈值 · CC original: {@code SessionMemoryConfig}（sessionMemoryUtils.ts:18-29）。
     *
     * @param minimumMessageTokensToInit   初始化阈值 · CC original: minimumMessageTokensToInit（:19-22）
     * @param minimumTokensBetweenUpdate   更新增长阈值 · CC original: minimumTokensBetweenUpdate（:23-26）
     * @param toolCallsBetweenUpdates      工具调用阈值 · CC original: toolCallsBetweenUpdates（:27-28）
     */
    public record SessionMemoryConfig(
            int minimumMessageTokensToInit,
            int minimumTokensBetweenUpdate,
            int toolCallsBetweenUpdates) {

        /** CC :32-36 DEFAULT_SESSION_MEMORY_CONFIG = (10000, 5000, 3) */
        public static final SessionMemoryConfig DEFAULT = new SessionMemoryConfig(10000, 5000, 3);
    }

    // ── 模块级共享状态（CC sessionMemoryUtils.ts:39-53）──

    /** 当前配置 · CC :39-41 模块态 sessionMemoryConfig */
    private static volatile SessionMemoryConfig sessionMemoryConfig = SessionMemoryConfig.DEFAULT;

    /**
     * 提取阈值 DB 实时读源（静态槽位）· [V52 token-compact-settings-fix] 同
     * {@code BoundaryReader}/{@code MicroCompactor} 静态槽位先例。DB 非 null 且 &gt; 0 优先于
     * 内存通道（Web 调参 PUT），null = 未接线 / 未配置 → 回落内存通道（零行为变化）。
     * 由 {@code SessionMemoryService.setSettingsResolver} 注入（生产 ToolRegistrationConfig:1411）。
     */
    private static volatile CompactSettingsResolver settingsResolver;

    /**
     * 最后摘要消息 ID · CC :44 lastSummarizedMessageId（shared state）。
     * <p><b>[sm-cursor-sessionize 2026-08-30] 按 sessionId 键控</b>: CC 单会话进程级 let 变量
     * （sessionMemoryUtils.ts:44）在 Web 多会话后端会成为跨会话串扰源（A 的游标被 B 读到）。
     * 对齐项目铁律「multi-session-vs-cc-single-session」→ 会话态游标全部迁入按 sessionId 键控的
     * ConcurrentHashMap（非 ThreadLocal：提取 hook 跑共享单线程执行器，ThreadLocal 会串会话值）。
     * sessionMemoryConfig / settingsResolver 为全局调参/只读源，保持全局（设计使然）。
     */
    private static final Map<String, String> lastSummarizedMessageIdBySession = new ConcurrentHashMap<>();

    /** 抽取进行时间戳 · CC :47 extractionStartedAt（由 sessionMemory.ts 设置，按 sessionId 键控） */
    private static final Map<String, Long> extractionStartedAtBySession = new ConcurrentHashMap<>();

    /** 上次抽取时的上下文大小 · CC :50 tokensAtLastExtraction（供 minimumTokensBetweenUpdate，按 sessionId 键控） */
    private static final Map<String, Integer> tokensAtLastExtractionBySession = new ConcurrentHashMap<>();

    /** session memory 是否已初始化 · CC :53 sessionMemoryInitialized（按 sessionId 键控） */
    private static final Map<String, Boolean> sessionMemoryInitializedBySession = new ConcurrentHashMap<>();

    /** 会话态游标 null-safe key（null sessionId → "unknown"，与 SessionMemoryService.sessionIdFrom 同兜底）。 */
    static String keyOf(String sessionId) {
        return sessionId != null ? sessionId : "unknown";
    }

    // ════════════════════════════════════════════════════════════════════
    // lastSummarizedMessageId · CC :58-69
    // ════════════════════════════════════════════════════════════════════

    /**
     * 获取消息 ID（到该消息为止 session memory 是最新的）· CC original:
     * {@code getLastSummarizedMessageId()}（sessionMemoryUtils.ts:58）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>（CC 单会话 let → Web 多会话 Map）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @return 摘要到该消息；null = 尚未摘要（undefined）
     */
    public static String getLastSummarizedMessageId(String sessionId) {
        return lastSummarizedMessageIdBySession.get(keyOf(sessionId));
    }

    /**
     * 设置 lastSummarizedMessageId · CC original: {@code setLastSummarizedMessageId(messageId)}
     * （sessionMemoryUtils.ts:65-69）。<b>[sm-cursor-sessionize] 按 sessionId 键控</b>——
     * 压缩成功路径 {@code setLastSummarizedMessageId(sessionId, null)} 只清本会话游标，
     * 不再跨会话清空（旧 static volatile 语义缺陷）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @param messageId 摘要到该 messageId；null = 复位（undefined）
     */
    public static void setLastSummarizedMessageId(String sessionId, String messageId) {
        String key = keyOf(sessionId);
        // ConcurrentHashMap 不允许 null value → null（undefined）语义用 remove 表达（absent = undefined）
        if (messageId == null) {
            lastSummarizedMessageIdBySession.remove(key);
        } else {
            lastSummarizedMessageIdBySession.put(key, messageId);
        }
        if (log.isInfoEnabled()) {
            log.info("[SessionMemoryUtils] setLastSummarizedMessageId: session={} value={}",
                key, messageId == null ? "undefined" : messageId);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 抽取状态 · CC :74-105
    // ════════════════════════════════════════════════════════════════════

    /**
     * 标记抽取开始（由 sessionMemory.ts 调用）· CC original: {@code markExtractionStarted()}
     * （sessionMemoryUtils.ts:74-76）。<b>[sm-cursor-sessionize] 按 sessionId 键控</b>。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     */
    public static void markExtractionStarted(String sessionId) {
        extractionStartedAtBySession.put(keyOf(sessionId), System.currentTimeMillis());
    }

    /**
     * 标记抽取完成 · CC original: {@code markExtractionCompleted()}（sessionMemoryUtils.ts:81-83）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>（仅清本会话时间戳）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     */
    public static void markExtractionCompleted(String sessionId) {
        extractionStartedAtBySession.remove(keyOf(sessionId));
    }

    /**
     * 等待进行中的 session memory 抽取完成（15s 超时）· CC original:
     * {@code waitForSessionMemoryExtraction()}（sessionMemoryUtils.ts:89-105）。
     *
     * <p><b>CC 语义</b>：无抽取进行 → 立即返回；抽取 stale（>60s）→ 立即返回（不再等）；
     * 等待超时（>15s）→ 返回（继续）。Java 端为同步等待（sleep 1000 循环），
     * 与 CC async while(sleep) 行为等价。
     *
     * <p><b>[sm-cursor-sessionize] P1-1 会话化</b>：只等<b>本会话</b>的 extractionStartedAt
     * （{@code keyOf(sessionId)}），消除跨会话 15s 阻塞——旧 static volatile 语义下 A 抽取进行中，
     * B 的 SM 压缩 wait 会等满 A 的 15s。
     *
     * <p><b>[SM-12] age 基准每轮重读（DRIFT-19）</b>：CC sessionMemoryUtils.ts:91-92 循环体内
     * 每次读取 {@code extractionStartedAt}（age 以最新时间戳起算）——等待期间新抽取开始时
     * 不按旧时间戳提前 stale 早退。旧实现循环前一次性捕获 startedAt（NOT_ALIGNED）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     */
    public static void waitForSessionMemoryExtraction(String sessionId) {
        String key = keyOf(sessionId);
        if (extractionStartedAtBySession.get(key) == null) {
            return;
        }
        long startTime = System.currentTimeMillis();
        while (true) {
            // [SM-12] 每轮重读 extractionStartedAt（CC :91-92）——新抽取开始时 age 重新起算
            Long currentStartedAt = extractionStartedAtBySession.get(key);
            if (currentStartedAt == null) {
                // 本会话抽取已完成，结束等待
                return;
            }
            long extractionAge = System.currentTimeMillis() - currentStartedAt;
            if (extractionAge > EXTRACTION_STALE_THRESHOLD_MS) {
                // 抽取 stale，不再等待
                if (log.isDebugEnabled()) {
                    log.debug("[SessionMemoryUtils] waitForSessionMemoryExtraction: 抽取 stale({}ms)，不再等待",
                        extractionAge);
                }
                return;
            }
            if (System.currentTimeMillis() - startTime > EXTRACTION_WAIT_TIMEOUT_MS) {
                // 超时，继续
                if (log.isDebugEnabled()) {
                    log.debug("[SessionMemoryUtils] waitForSessionMemoryExtraction: 等待超时({}ms)，继续",
                        EXTRACTION_WAIT_TIMEOUT_MS);
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 测试辅助 · 读取某会话的 extractionStartedAt（无 → null）· CC :47 extractionStartedAt。
     * <b>[sm-cursor-sessionize]</b> 替代旧 {@code ReflectionTestUtils.getField(...,"extractionStartedAt")}
     * 私有字段断言（static volatile 字段已迁入 ConcurrentHashMap）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @return 抽取开始时间戳；无 → null
     */
    static Long getExtractionStartedAt(String sessionId) {
        return extractionStartedAtBySession.get(keyOf(sessionId));
    }


    // ════════════════════════════════════════════════════════════════════
    // config · CC :131-196
    // ════════════════════════════════════════════════════════════════════

    /**
     * 设置配置 · CC original: {@code setSessionMemoryConfig(config)}（sessionMemoryUtils.ts:131-138）。
     * 纯 merge：入参字段全部覆盖（含 0/负值）——CC 的「仅正值覆盖」过滤在
     * {@code initSessionMemoryConfigIfNeeded}（sessionMemory.ts:246-262）层，本 setter 无过滤。
     * Java 侧该过滤层由 {@link SessionMemoryConfigChannel#updateSessionMemoryConfig} 读入点承担
     * （[IMP-MV2-33] 过滤层位置修复：过滤自本方法内嵌位移入通道，对齐 CC 纯 merge + init 层过滤）。
     * <p>[F-10 登记 · IMP-MV2-40] △-10 一次性 init（拍板 OPD-CM3-14）：CC initSessionMemoryConfigIfNeeded
     *   memoize 一次（sessionMemory.ts:240-264）；Java 本 setter 可重复 PUT（纯 merge，正值过滤在
     *   ConfigChannel 读入点，IMP-MV2-33）—— 一次性面 △ 接受，登记声明。
     *
     * @param config 覆盖配置（null → 忽略）
     */
    public static void setSessionMemoryConfig(SessionMemoryConfig config) {
        if (config == null) {
            return;
        }
        sessionMemoryConfig = new SessionMemoryConfig(
            config.minimumMessageTokensToInit(),
            config.minimumTokensBetweenUpdate(),
            config.toolCallsBetweenUpdates());
    }

    /**
     * 获取 Web 调参通道配置（副本）· CC original: {@code getSessionMemoryConfig()}
     * （sessionMemoryUtils.ts:143-145）。
     *
     * <p><b>[IMP-CM-03] SM 阈值消费点</b>：本静态态是 Web 调参通道（tengu_sm_config 通道等价，
     * IMP-CM-35 SessionMemoryConfigChannel 写 setSessionMemoryConfig）的运行期存储；GET 端点与
     * PUT 合并基值读本方法（内存通道语义）。<b>阈值谓词不直接读本方法</b>，改读
     * {@link #getEffectiveSessionMemoryConfig()}（DB &gt; 内存，[V52 token-compact-settings-fix]）。
     */
    public static SessionMemoryConfig getSessionMemoryConfig() {
        return sessionMemoryConfig;
    }

    /**
     * 注入提取阈值 DB 实时读源（静态槽位）· [V52 token-compact-settings-fix] 同
     * {@code BoundaryReader#setSettingsResolver} 静态槽位先例（可 null = 回落内存通道）。
     * 由 {@code SessionMemoryService.setSettingsResolver} 生产注入；null 清除槽位。
     *
     * @param resolver DB 实时读源（可 null）
     */
    public static void setSettingsResolver(CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[SessionMemoryUtils] setSettingsResolver: 注入={}（SM 提取阈值 DB 实时覆盖，"
                + "null 回落内存通道）", resolver);
        }
    }

    /**
     * 获取生效配置（DB &gt; 内存 &gt; 默认）· [V52 token-compact-settings-fix] 阈值谓词读端。
     *
     * <p>逐字段合并：DB {@code settings.sm_minimum_message_tokens_to_init /
     * sm_minimum_tokens_between_update / sm_tool_calls_between_updates} 非 null（且 &gt; 0，
     * CompactSettingsResolver 内已过滤）优先；未配置字段回落内存通道值（Web 调参 PUT）；未接线 →
     * 恒返回内存通道值（零行为变化）。
     *
     * @return 当前生效 SM 提取阈值（副本）
     */
    public static SessionMemoryConfig getEffectiveSessionMemoryConfig() {
        CompactSettingsResolver resolver = settingsResolver;
        if (resolver == null) {
            return sessionMemoryConfig;
        }
        Integer dbInit = resolver.smMinimumMessageTokensToInit();
        Integer dbUpdate = resolver.smMinimumTokensBetweenUpdate();
        Integer dbTools = resolver.smToolCallsBetweenUpdates();
        if (dbInit == null && dbUpdate == null && dbTools == null) {
            return sessionMemoryConfig;
        }
        return new SessionMemoryConfig(
            dbInit != null ? dbInit : sessionMemoryConfig.minimumMessageTokensToInit(),
            dbUpdate != null ? dbUpdate : sessionMemoryConfig.minimumTokensBetweenUpdate(),
            dbTools != null ? dbTools : sessionMemoryConfig.toolCallsBetweenUpdates());
    }

    /**
     * 记录抽取时上下文大小 · CC original: {@code recordExtractionTokenCount(currentTokenCount)}
     * （sessionMemoryUtils.ts:151-153）。用于度量 minimumTokensBetweenUpdate 的增长。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>（B 的 tokensAtLastExtraction 独立于 A）。
     *
     * @param sessionId         会话 ID（null → "unknown" 兜底键）
     * @param currentTokenCount 抽取时上下文 token 数
     */
    public static void recordExtractionTokenCount(String sessionId, int currentTokenCount) {
        tokensAtLastExtractionBySession.put(keyOf(sessionId), currentTokenCount);
    }

    /**
     * session memory 是否已初始化（已过 init 阈值）· CC original:
     * {@code isSessionMemoryInitialized()}（sessionMemoryUtils.ts:158-160）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     */
    public static boolean isSessionMemoryInitialized(String sessionId) {
        return Boolean.TRUE.equals(sessionMemoryInitializedBySession.get(keyOf(sessionId)));
    }

    /**
     * 标记 session memory 已初始化 · CC original: {@code markSessionMemoryInitialized()}
     * （sessionMemoryUtils.ts:165-167）。<b>[sm-cursor-sessionize] 按 sessionId 键控</b>。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     */
    public static void markSessionMemoryInitialized(String sessionId) {
        sessionMemoryInitializedBySession.put(keyOf(sessionId), true);
    }

    /**
     * 是否已达初始化阈值 · CC original: {@code hasMetInitializationThreshold(currentTokenCount)}
     * （sessionMemoryUtils.ts:173-177）：当前 token ≥ minimumMessageTokensToInit。
     * <b>阈值取生效配置</b>（DB &gt; 内存，[V52 token-compact-settings-fix]，DB 实时读无缓存）。
     * <b>[sm-cursor-sessionize]</b>：仅读全局生效配置，无会话态游标 → 保持无 sessionId 形参。
     *
     * @param currentTokenCount 当前上下文窗口 token 数（与 autocompact 同口径）
     * @return true=已达 init 阈值
     */
    public static boolean hasMetInitializationThreshold(int currentTokenCount) {
        return currentTokenCount >= getEffectiveSessionMemoryConfig().minimumMessageTokensToInit();
    }

    /**
     * 是否已达更新阈值 · CC original: {@code hasMetUpdateThreshold(currentTokenCount)}
     * （sessionMemoryUtils.ts:184-189）：自上次抽取增长的 token ≥ minimumTokensBetweenUpdate。
     * <b>阈值取生效配置</b>（DB &gt; 内存，[V52 token-compact-settings-fix]，DB 实时读无缓存）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>（读本会话 tokensAtLastExtraction）。
     *
     * @param sessionId         会话 ID（null → "unknown" 兜底键）
     * @param currentTokenCount 当前上下文窗口 token 数
     * @return true=已达更新阈值
     */
    public static boolean hasMetUpdateThreshold(String sessionId, int currentTokenCount) {
        int tokensAtLast = tokensAtLastExtractionBySession.getOrDefault(keyOf(sessionId), 0);
        int tokensSinceLastExtraction = currentTokenCount - tokensAtLast;
        return tokensSinceLastExtraction >= getEffectiveSessionMemoryConfig().minimumTokensBetweenUpdate();
    }

    /**
     * 获取两次更新之间的工具调用数 · CC original: {@code getToolCallsBetweenUpdates()}
     * （sessionMemoryUtils.ts:194-196）。<b>阈值取生效配置</b>（DB &gt; 内存，
     * [V52 token-compact-settings-fix]，DB 实时读无缓存）。
     */
    public static int getToolCallsBetweenUpdates() {
        return getEffectiveSessionMemoryConfig().toolCallsBetweenUpdates();
    }

    /**
     * 重置 session memory 状态（测试用）· CC original: {@code resetSessionMemoryState()}
     * （sessionMemoryUtils.ts:201-207）。
     * <b>[V52 token-compact-settings-fix] 追加清除 DB 实时读源静态槽位</b>（防测试间串扰）。
     */
    public static void resetSessionMemoryState() {
        sessionMemoryConfig = SessionMemoryConfig.DEFAULT;
        settingsResolver = null;
        tokensAtLastExtractionBySession.clear();
        sessionMemoryInitializedBySession.clear();
        lastSummarizedMessageIdBySession.clear();
        extractionStartedAtBySession.clear();
    }
}
