package com.nexusai.application.agent.permission.classifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 连续拒绝追踪器 · 对齐 CC denialTracking.ts + permissions.ts:486-499/:878-916/:984-1058
 *
 * <p><b>CC 真源（denialTracking.ts 纯函数状态）</b>:
 * <pre>{@code
 * type DenialTrackingState = { consecutiveDenials, totalDenials }
 * recordDenial: 双计数 +1
 * recordSuccess: 只清零 consecutive（total 不清）
 * shouldFallbackToPrompting: consecutive >= 3 || total >= 20
 * }</pre>
 *
 * <p><b>CC 超限处置（permissions.ts:984-1058 handleDenialLimitExceeded）</b>:
 * 超限 → 回退 prompting（ask 用户确认，不 deny）；hitTotalLimit 时双计数清零
 * （permissions.ts:1034-1040），因此 total 熔断不持久；consecutive 熔断在下次
 * 任意 allow 经 {@link #recordSuccess(String)} 恢复（permissions.ts:486-499）。
 *
 * <p><b>熔断门控（S13 处置，O50）</b>: Java 曾以持久状态 enum 承载熔断状态；
 * CC 无此持久状态机，熔断是 {@link #shouldFallbackToPrompting(String)} 的派生查询
 * （denialTracking.ts:40-45）。S13 删除该 enum 后，门控方
 * （PermissionPipeline）直接调派生查询，与 CC 一致。
 *
 * <h2>双态解析（对齐 CC permissions.ts:556-558）</h2>
 * <pre>{@code
 * const denialState = context.localDenialTracking ?? appState.denialTracking ?? createDenialTrackingState()
 * }</pre>
 * 本类以两种形态承载，两者都用<b>键控计数器</b>（{@link #counters}），不同的只是键的来源：
 * <ul>
 *   <li><b>per-agent 实例（localDenialTracking 等价，子代理路径）</b>：{@link #forLocalState(Map)}
 *       绑定 {@code ctx.localDenialTracking()} Map，计数从 Map 恢复并在 record 后就地写回
 *       （对齐 CC persistDenialState {@code Object.assign}，permissions.ts:967-968）；
 *       键固定为 {@link #LOCAL_KEY}（每实例一条）。</li>
 *   <li><b>单例 bean（appState.denialTracking 等价，主 agent 路径）</b>：计数<b>按显式 sessionId 键控</b>
 *       —— 见下节。PermissionPipeline 在 {@code ctx.localDenialTracking()==null} 时回落本 bean。</li>
 * </ul>
 *
 * <h2>⛔ 为什么单例路径必须按 sessionId 键控（本批修复的跨会话缺陷）</h2>
 * <p>CC 侧 {@code appState.denialTracking} 挂在<b>进程级 AppState</b>
 * （{@code state/AppStateStore.ts:426}）—— 在 CC 里安全（单进程单会话）。
 * 本仓是<b>一 JVM 多会话</b>，而本类是 {@code @Component} 单例，且<b>主 agent 的每次工具调用
 * 都走本 bean</b>（{@code LlmAgentLoop#buildBaseToolUseContext} 对 localDenialTracking 传 null，
 * 见 LlmAgentLoop:9879 ⇒ PermissionPipeline.resolveDenialTracker 的回落分支）⇒ 原实现的单份实例字段
 * 会让<b>A 会话的连续拒绝把 B 会话直接推入 fallback（回退 prompting）</b>——实打实的跨会话串扰，
 * 且 B 的 fallback 是「用户被无谓打断」的可观测后果。
 *
 * <p>修法（裁定-10 (A)）：计数载体收敛为 {@code final Map<String,int[]> counters}，键 = 显式
 * sessionId（无会话 → 无会话桶 {@code ""}，写入侧 ≥WARN）。选 (A) 而非「主链 TUC 携带 per-session Map」
 * 是因为后者要改 {@code ToolUseContext}/{@code LlmAgentLoop}（另一轨文件面）。
 *
 * <p><b>容量/清理</b>：每条 = 一个 {@code int[2]}；条目在<b>会话删除</b>时经
 * {@link #removeSession(String)} 回收（由 {@code SessionService.delete} 的会话级注册表回收块调用，
 * 与 {@code MicroCompactor.removeSessionState} 同一口径）；未删除的会话在跑过 auto-mode 分类器后
 * 留一条（总量 ≈ 会话数，与既有各 session 注册表同阶）。
 * ⛔ forLocalState 实例不参与回收：每实例至多 1 条，随实例 GC。
 *
 * <h2>配置属性</h2>
 * <ul>
 *   <li>{@code nexusai.auto-mode.denial-threshold} — 连续拒绝触发熔断的阈值（默认 3，
 *       CC DENIAL_LIMITS.maxConsecutive）</li>
 *   <li>{@code nexusai.auto-mode.denial-max-total} — 累计拒绝上限（默认 20，
 *       CC DENIAL_LIMITS.maxTotal）</li>
 * </ul>
 */
@Component
public class DenialTracker {

    private static final Logger log = LoggerFactory.getLogger(DenialTracker.class);

    private static final int DEFAULT_THRESHOLD = 3;
    private static final int DEFAULT_MAX_TOTAL = 20;

    /** CC DenialTrackingState 字段名（denialTracking.ts:7-10）→ Map 键。 */
    private static final String KEY_CONSECUTIVE_DENIALS = "consecutiveDenials";
    private static final String KEY_TOTAL_DENIALS = "totalDenials";

    /** forLocalState 实例的内部计数键 · 以 NUL 开头 ⇒ 不可能与真实 sessionId 冲突。 */
    private static final String LOCAL_KEY = "\0local";

    private final int threshold;
    private final int maxTotal;
    /** per-agent 本地状态载体 · 对齐 CC {@code context.localDenialTracking}（Tool.ts:283）。
     *  {@code null} = 未绑定（全局 bean / appState.denialTracking 等价，主 agent 路径）。 */
    private final Map<String, Object> localState;

    /**
     * 键控计数载体 · 键 = 显式 sessionId（单例路径）或 {@link #LOCAL_KEY}（forLocalState 实例）。
     *
     * <p>{@code final} 容器 ⇒ 单例 bean 上不存在「非 static 非 final 的可变会话态<b>字段</b>」
     * （T10 闸门判据）；可变性收敛进容器内部。
     */
    private final Map<String, int[]> counters = new ConcurrentHashMap<>();

    @Autowired
    public DenialTracker(
            @Value("${nexusai.auto-mode.denial-threshold:3}") int threshold,
            @Value("${nexusai.auto-mode.denial-max-total:20}") int maxTotal
    ) {
        this(threshold, maxTotal, null);
    }

    /**
     * per-agent 工厂 · 对齐 CC {@code context.localDenialTracking ?? appState.denialTracking}
     * 中 localDenialTracking 分支（permissions.ts:556-558）。
     *
     * <p>子代理 ctx 携带非 null localDenialTracking（forkedAgent.ts:420-422 非 share 子代理
     * 新建独立状态）→ 返回绑定该 Map 的独立 tracker，拒绝计数隔离在 per-agent 内，
     * 不污染全局 bean。
     *
     * @param localState 子代理 ctx 的 localDenialTracking Map（null → 空 HashMap）
     * @return per-agent DenialTracker 实例
     */
    public static DenialTracker forLocalState(Map<String, Object> localState) {
        return new DenialTracker(DEFAULT_THRESHOLD, DEFAULT_MAX_TOTAL,
            localState != null ? localState : new java.util.HashMap<>());
    }

    /**
     * 全参构造 · localState 非 null 时绑定 per-agent 本地计数并从 Map 恢复（三态解析）。
     */
    private DenialTracker(int threshold, int maxTotal, Map<String, Object> localState) {
        this.threshold = threshold > 0 ? threshold : DEFAULT_THRESHOLD;
        this.maxTotal = maxTotal > 0 ? maxTotal : DEFAULT_MAX_TOTAL;
        this.localState = localState;
        if (localState != null) {
            // 从绑定 Map 恢复计数（对齐 CC 三态解析 permissions.ts:556-558 的「已有 state」分支）
            counters.put(LOCAL_KEY, new int[]{asInt(localState.get(KEY_CONSECUTIVE_DENIALS)),
                                              asInt(localState.get(KEY_TOTAL_DENIALS))});
        }
    }

    private static int asInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 会话键归一 · null/blank → 无会话桶（{@code ""}，不与任何真实会话共享）。 */
    static String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "" : sessionId;
    }

    /** 取（或惰性建）该次调用的计数槽 · forLocalState 实例恒用 {@link #LOCAL_KEY}。 */
    private int[] counter(String sessionId) {
        return counters.computeIfAbsent(localState != null ? LOCAL_KEY : sessionKey(sessionId),
            k -> new int[]{0, 0});
    }

    /**
     * 写回本地状态载体 · 对齐 CC persistDenialState（permissions.ts:967-968
     * {@code Object.assign(context.localDenialTracking, newState)} 就地修改）。
     *
     * <p>Map 不可变（ToolUseContext compact ctor {@code Map.copyOf} 产物）时
     * {@code UnsupportedOperationException} 静默跳过，计数保留在本实例；
     * 隔离语义不受影响（不触碰全局 bean）。
     */
    private void writeBackLocalState(int[] c) {
        if (localState == null) {
            return;
        }
        try {
            localState.put(KEY_CONSECUTIVE_DENIALS, c[0]);
            localState.put(KEY_TOTAL_DENIALS, c[1]);
        } catch (UnsupportedOperationException ex) {
            // 不可变 Map → 就地写回不可行；计数保留在本实例（隔离不依赖写回）。
        }
    }

    /**
     * 记录一次分类器拒绝 · 对齐 CC {@code recordDenial}（denialTracking.ts:24-30）
     * + {@code handleDenialLimitExceeded}（permissions.ts:984-1058）。
     *
     * <p>双计数 +1；达阈值（consecutive≥threshold 或 total≥maxTotal）→ 回退标志；
     * total 达上限 → 双计数清零（CC permissions.ts:1034-1040
     * persistDenialState({totalDenials:0, consecutiveDenials:0})，total 熔断不持久）；
     * 熔断判定由 {@link #shouldFallbackToPrompting(String)} 派生（denialTracking.ts:40-45）。
     *
     * @param sessionId 显式会话标识（**必传**：计数按会话键控，见类 javadoc 的「为什么」；
     *                  null/blank → 无会话桶并 ≥WARN 可观测）
     * @return 回退快照：fallback=true 表示本次拒绝触发超限回退（调用方应转 ask 用户确认）；
     *         计数为清零前的值，供 CC warning 文案使用（permissions.ts:1003-1007）
     */
    public FallbackSnapshot recordDenial(String sessionId) {
        if (localState == null && (sessionId == null || sessionId.isBlank())) {
            // (b) 类：无会话标识 ⇒ 计入无会话桶（只与同为无会话的调用共享），但必须 ≥WARN 可观测。
            log.warn("[DenialTracker] recordDenial 无会话标识（sessionId=null/blank）→ 计入无会话桶"
                + "（不与任何真实会话共享；调用方应传显式 sessionId）");
        }
        int[] c = counter(sessionId);
        synchronized (c) {
            c[0]++;
            c[1]++;
            boolean fallback = c[0] >= threshold || c[1] >= maxTotal;
            // CC permissions.ts:1003-1007 — 清零前捕获计数（warning 文案用）
            int snapshotConsecutive = c[0];
            int snapshotTotal = c[1];
            if (c[1] >= maxTotal) {
                // CC permissions.ts:1034-1040 — hitTotalLimit → 双计数清零，total 熔断不持久
                c[0] = 0;
                c[1] = 0;
            }
            writeBackLocalState(c);
            return new FallbackSnapshot(fallback, snapshotConsecutive, snapshotTotal);
        }
    }

    /**
     * 记录一次放行 · 对齐 CC {@code recordSuccess}（denialTracking.ts:32-38）。
     *
     * <p>只清零 consecutive（total 保留），断连拒链（CC permissions.ts:486-499：
     * auto 模式下任意 allow 事件恢复分类器）。熔断随之解除（派生查询不再 fallback，
     * 验收 R4）。
     *
     * @param sessionId 显式会话标识（**必传**：只断<b>本会话</b>的连拒链）
     */
    public void recordSuccess(String sessionId) {
        int[] c = counter(sessionId);
        synchronized (c) {
            c[0] = 0;
            writeBackLocalState(c);
        }
    }

    /**
     * 是否应回退到用户确认 · 对齐 CC {@code shouldFallbackToPrompting}
     * （denialTracking.ts:40-45）派生查询。
     *
     * @param sessionId 显式会话标识（null/blank → 无会话桶）
     */
    public boolean shouldFallbackToPrompting(String sessionId) {
        int[] c = counter(sessionId);
        synchronized (c) {
            return c[0] >= threshold || c[1] >= maxTotal;
        }
    }

    /**
     * 获取该会话当前连续拒绝次数。
     *
     * @param sessionId 显式会话标识（null/blank → 无会话桶）
     */
    public int getConsecutiveDenials(String sessionId) {
        int[] c = counter(sessionId);
        synchronized (c) {
            return c[0];
        }
    }

    /**
     * 获取该会话当前累计拒绝次数。
     *
     * @param sessionId 显式会话标识（null/blank → 无会话桶）
     */
    public int getTotalDenials(String sessionId) {
        int[] c = counter(sessionId);
        synchronized (c) {
            return c[1];
        }
    }

    /**
     * 回收某会话的计数槽（会话删除时调用）· 防「每会话一条」无界累积。
     *
     * <p>调用点：{@code SessionService.delete} 的会话级注册表回收块（与
     * {@code MicroCompactor.removeSessionState} / {@code SessionGitStatusRegistry.evict} 同一口径）。
     * ⛔ forLocalState 实例不参与（其键是 {@link #LOCAL_KEY}，随实例 GC；本方法只清单例的会话桶）。
     * 未知/已删会话 → no-op，不抛。
     *
     * @param sessionId 会话标识（null/blank → 清无会话桶）
     */
    public void removeSession(String sessionId) {
        if (localState != null) {
            return;
        }
        int[] removed = counters.remove(sessionKey(sessionId));
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[DenialTracker] removeSession: sessionId={} 计数槽已回收", sessionId);
        }
    }

    /**
     * 获取累计拒绝上限（CC DENIAL_LIMITS.maxTotal）· 与键无关的配置值。
     *
     * <p>保留：S12 接线后 PermissionPipeline 超限回退分支（permissions.ts:984-1058
     * handleDenialLimitExceeded）以本值判定 hitTotalLimit（O51 部分剔除）。
     *
     * @return maxTotal
     */
    public int getMaxTotal() {
        return maxTotal;
    }
    public record FallbackSnapshot(boolean fallback, int consecutiveDenials, int totalDenials) {}
}
