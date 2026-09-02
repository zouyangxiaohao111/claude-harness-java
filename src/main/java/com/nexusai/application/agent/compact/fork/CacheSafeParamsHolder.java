package com.nexusai.application.agent.compact.fork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * fork 缓存共享参数会话槽位 · 对齐 CC forkedAgent.ts:70-81
 * （CC original: {@code let lastCacheSafeParams: CacheSafeParams | null = null}
 * + {@code saveCacheSafeParams}/{@code getLastCacheSafeParams}）。
 *
 * <p><b>WHY 存在（RES-②）</b>: CC 主线程在压缩前经 {@code getCacheSharingParams}
 * （compact.ts:250-287）构建 CacheSafeParams 存入模块级槽位，压缩 fork 查询经
 * {@code getLastCacheSafeParams} 读取，使 fork 与主线程共享 prompt cache 前缀。
 * Java 端由 {@link CacheSharingParamsBuilder} 构建、本类槽位承接、StreamCompactSummary
 * 经 cacheSafeParamsSupplier 读取。
 *
 * <p><b>Java 化差异（防并发串台）</b>: CC 为单进程单 loop，模块级变量即安全；
 * Java Spring 多会话服务并发跑多个 LlmAgentLoop，用 {@link ThreadLocal} 实现线程级隔离
 * （每 loop 各自线程，save→autoCompactIfNeeded→get 同线程同步调用，见 LlmAgentLoop
 * autoCompact 触发点 finally clear）。
 *
 * <p><b>生命周期契约</b>:
 * <ol>
 *   <li>{@link #save(CacheSafeParams)} —— LlmAgentLoop autoCompact 触发点压缩前调用</li>
 *   <li>{@link #get()} —— StreamCompactSummary cacheSafeParamsSupplier 读取（null → 跳过 fork 路径）</li>
 *   <li>{@link #clear()} —— autoCompactIfNeeded 完成后 finally 调用，防止槽位串台/泄漏到下一 turn</li>
 * </ol>
 */
public final class CacheSafeParamsHolder {

    private static final Logger log = LoggerFactory.getLogger(CacheSafeParamsHolder.class);

    /** 线程级会话槽位（Java 化：CC 模块级变量 → ThreadLocal 隔离多会话并发）。 */
    private static final ThreadLocal<CacheSafeParams> SLOT = new ThreadLocal<>();

    private CacheSafeParamsHolder() {
    }

    /**
     * 保存 fork 缓存共享参数 · 对齐 CC {@code saveCacheSafeParams}（forkedAgent.ts:72-74）。
     *
     * @param params 构建好的 CacheSafeParams（null → 存 null，get 侧按 null 跳过 fork）
     */
    public static void save(CacheSafeParams params) {
        SLOT.set(params);
        if (log.isDebugEnabled()) {
            log.debug("[CacheSafeParamsHolder] 已保存 fork 缓存共享参数: systemPromptBlocks={}, "
                    + "forkMsgs={}",
                params == null ? 0 : params.systemPrompt().size(),
                params == null ? 0 : params.forkContextMessages().size());
        }
    }

    /**
     * 读取当前线程槽位 · 对齐 CC {@code getLastCacheSafeParams}（forkedAgent.ts:76-78）。
     *
     * @return 当前线程保存的 CacheSafeParams；无则 null（调用方跳过 fork 路径）
     */
    public static CacheSafeParams get() {
        return SLOT.get();
    }

    /**
     * 清空当前线程槽位（autoCompactIfNeeded 后 finally 调用 · 防串台）。
     */
    public static void clear() {
        SLOT.remove();
        if (log.isDebugEnabled()) {
            log.debug("[CacheSafeParamsHolder] fork 缓存共享参数槽位已清空");
        }
    }
}
