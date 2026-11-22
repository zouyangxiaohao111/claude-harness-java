package com.nexusai.application.agent.subagent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * resume 并发配额 · 对齐 CC {@code roomForResume}（claude.exe 2.1.328 偏移 326611441
 * {@code function HKs(e){if(!Bne()||e===void 0)return()=>{};try{return yp().subagentSends?.roomForResume(e)}catch{return}}}
 * 与调用点 {@code $Ks(V, ()=>N(...))} = {@code try{run()}finally{V()}}）。
 *
 * <h2>语义（用户已裁定 · 照抄 CC）</h2>
 * <ul>
 *   <li><b>会话级并发槽</b>：{@link #tryAcquire(String)} 返回<b>释放句柄</b>；槽满返回 null
 *       ⇒ 调用方按 {@code session_full} 直接拒（<b>不排队</b>）。</li>
 *   <li><b>上限</b>：{@code CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS ?? 20}（{@code $xt()}，偏移 329287293）。
 *       远端配置只在 {@code >20 && <=1000} 时生效，且取 {@code min(cfg,64)}（{@code Tr()}，偏移 334052811
 *       {@code Qo=1000}/{@code Ho=64}）；否则 20。</li>
 *   <li><b>拒投文案</b>（CC {@code uSa}，偏移 326610900 原文）：
 *       {@code Agent "<name>" was not resumed, and your message was not delivered: this session has no
 *       room for another agent now, or has started as many as it may between two messages from the user.
 *       Go on without it, or say in your final response what you needed from it.}</li>
 * </ul>
 *
 * <p>本类只做「会话内计数」，不持有业务状态；spawn 侧与 resume 侧应共用同一实例（CC 的
 * {@code taskRegistry.getConcurrentSubagents()} 同时被 spawn 与 resume 计入）。
 */
@Component
public class ResumeQuota {

    /** CC {@code $xt()} 的环境变量名。 */
    public static final String ENV_MAX_CONCURRENT_SUBAGENTS = "CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS";

    /** CC {@code Uxt=20}（偏移 329287293）。 */
    public static final int DEFAULT_LIMIT = 20;

    /** CC {@code Qo=1000}：远端配置的合法上界（超过即忽略）。 */
    public static final int REMOTE_CONFIG_CEILING = 1000;

    /** CC {@code Ho=64}：远端配置生效后的硬钳位。 */
    public static final int REMOTE_CONFIG_HARD_CAP = 64;

    /** CC {@code yYa=5}（偏移 315610267）：每个 agent 自动续跑次数上限，存 {@code <id>.meta.json}。 */
    public static final int MAX_RESTART_RESUMES = 5;

    /** 释放句柄（CC {@code $Ks} 的 {@code V}）。 */
    public interface Release {
        /** 归还槽位；幂等（重复调用只生效一次）。 */
        void release();
    }

    private final int max;
    private final Map<String, AtomicInteger> inUse = new ConcurrentHashMap<>();

    /** 生产构造：读环境变量（{@code CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS}），无远端配置通道。 */
    public ResumeQuota() {
        this(resolveMax(System.getenv(ENV_MAX_CONCURRENT_SUBAGENTS), null));
    }

    /** 测试/显式上限构造。 */
    public ResumeQuota(int max) {
        this.max = Math.max(1, max);
    }

    /**
     * 解析生效上限 · 对齐 CC {@code Tr()}（偏移 334052811）。
     *
     * @param envValue     {@code CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS}（null/非数字 → 忽略）
     * @param remoteConfig 远端配置（null = 无；仅 {@code >20 && <=1000} 才生效）
     * @return 生效并发上限（恒 >=1）
     */
    public static int resolveMax(String envValue, Integer remoteConfig) {
        Integer env = parsePositiveInt(envValue);
        if (env != null) {
            return env;
        }
        if (remoteConfig != null && remoteConfig > DEFAULT_LIMIT && remoteConfig <= REMOTE_CONFIG_CEILING) {
            return Math.min(remoteConfig, REMOTE_CONFIG_HARD_CAP);
        }
        return DEFAULT_LIMIT;
    }

    private static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 预留一个会话并发槽 · 对齐 CC {@code roomForResume}。
     *
     * @param sessionId 会话键（null → 归入 {@code ""} 桶，仍受限）
     * @return 释放句柄；槽满 → <b>null</b>（调用方按 {@code session_full} 直接拒，⛔ 不等待/不排队）
     */
    public Release tryAcquire(String sessionId) {
        String key = sessionId == null ? "" : sessionId;
        AtomicInteger counter = inUse.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= max) {
                return null;
            }
            if (counter.compareAndSet(current, current + 1)) {
                AtomicBoolean released = new AtomicBoolean(false);
                return () -> {
                    if (released.compareAndSet(false, true)) {
                        counter.decrementAndGet();
                    }
                };
            }
        }
    }

    /**
     * 非预留式容量查询（spawn/resume 的前置判定）· 槽满 → false。
     *
     * <p>与 {@link #tryAcquire(String)} 的分工：查询只用于「先给模型一个确定的 session_full
     * 回执」，真正的占槽仍必须走 {@code tryAcquire}（否则检查与占用之间的竞态会超发）。
     */
    public boolean hasRoom(String sessionId) {
        return inUse(sessionId) < max;
    }

    /** 当前会话已占用槽数（测试/观测）。 */
    public int inUse(String sessionId) {
        AtomicInteger counter = inUse.get(sessionId == null ? "" : sessionId);
        return counter == null ? 0 : counter.get();
    }

    /** 生效上限。 */
    public int max() {
        return max;
    }

    /**
     * spawn 启动门的拒绝原文 · 对齐 CC {@code subagent_concurrency_cap}（claude.exe 2.1.328
     * 偏移 334098803 逐字）：
     * {@code Concurrent subagent limit reached. You can run <g> subagents at once. Do not retry.
     * If the user wants more concurrent subagents, ask them to increase
     * CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS.}
     *
     * <p>⛔ 与 resume 侧 {@link ResumeErrorCode#sessionFullText(String)} 不是同一句：
     * spawn 是启动门（CC {@code Ar}），resume 是会话满（CC {@code uSa}，degradedClass=session_full）。
     */
    public static String concurrentLimitText(int limit) {
        return "Concurrent subagent limit reached. You can run " + limit + " subagents at once. "
            + "Do not retry. If the user wants more concurrent subagents, ask them to increase "
            + ENV_MAX_CONCURRENT_SUBAGENTS + ".";
    }

    /** 清空（测试用）。 */
    public void reset() {
        inUse.clear();
    }
}
