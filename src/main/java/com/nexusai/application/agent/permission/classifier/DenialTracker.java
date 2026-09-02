package com.nexusai.application.agent.permission.classifier;

import java.util.Map;

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
 * 任意 allow 经 {@link #recordSuccess()} 恢复（permissions.ts:486-499）。
 *
 * <p><b>熔断门控（S13 处置，O50）</b>: Java 曾以持久状态 enum 承载熔断状态；
 * CC 无此持久状态机，熔断是 {@link #shouldFallbackToPrompting()} 的派生查询
 * （denialTracking.ts:40-45）。S13 删除该 enum 后，门控方
 * （PermissionPipeline）直接调派生查询，与 CC 一致。
 *
 * <h2>per-agent 本地态（[IMP-9] OPD-WF3-01-14 拍板：子代理独立计数）</h2>
 * <p>CC 双态解析（permissions.ts:556-558）：
 * <pre>{@code
 * const denialState = context.localDenialTracking ?? appState.denialTracking ?? createDenialTrackingState()
 * }</pre>
 * 本类以两种形态承载：
 * <ul>
 *   <li><b>全局 bean（appState.denialTracking 等价，主 agent 路径）</b>：实例字段计数，
 *       经 {@code @Value} 构造；PermissionPipeline 在 ctx.localDenialTracking()==null 时回落。</li>
 *   <li><b>per-agent 实例（localDenialTracking 等价，子代理路径）</b>：{@link #forLocalState(Map)}
 *       绑定 {@code ctx.localDenialTracking()} Map，计数从 Map 恢复并在 record 后就地写回
 *       （对齐 CC persistDenialState {@code Object.assign}，permissions.ts:967-968）；
 *       Map 不可变（ToolUseContext compact ctor {@code Map.copyOf} 产物）时静默跳过写回，
 *       计数保留在本实例 —— 隔离语义不受影响（不触碰全局 bean）。</li>
 * </ul>
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

    private static final int DEFAULT_THRESHOLD = 3;
    private static final int DEFAULT_MAX_TOTAL = 20;

    /** CC DenialTrackingState 字段名（denialTracking.ts:7-10）→ Map 键。 */
    private static final String KEY_CONSECUTIVE_DENIALS = "consecutiveDenials";
    private static final String KEY_TOTAL_DENIALS = "totalDenials";

    private final int threshold;
    private final int maxTotal;
    /** per-agent 本地状态载体 · 对齐 CC {@code context.localDenialTracking}（Tool.ts:283）。
     *  {@code null} = 未绑定（全局 bean / appState.denialTracking 等价，主 agent 路径）。 */
    private final Map<String, Object> localState;
    private int consecutiveDenials = 0;
    private int totalDenials = 0;

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
        seedFromLocalState();
    }

    /** 从本地状态载体恢复计数 · 对齐 CC 三态解析（permissions.ts:556-558）。 */
    private void seedFromLocalState() {
        if (localState == null) {
            return;
        }
        Object c = localState.get(KEY_CONSECUTIVE_DENIALS);
        Object t = localState.get(KEY_TOTAL_DENIALS);
        consecutiveDenials = c instanceof Number n ? n.intValue() : 0;
        totalDenials = t instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 写回本地状态载体 · 对齐 CC persistDenialState（permissions.ts:967-968
     * {@code Object.assign(context.localDenialTracking, newState)} 就地修改）。
     *
     * <p>Map 不可变（ToolUseContext compact ctor {@code Map.copyOf} 产物）时
     * {@code UnsupportedOperationException} 静默跳过，计数保留在本实例；
     * 隔离语义不受影响（不触碰全局 bean）。
     */
    private void writeBackLocalState() {
        if (localState == null) {
            return;
        }
        try {
            localState.put(KEY_CONSECUTIVE_DENIALS, consecutiveDenials);
            localState.put(KEY_TOTAL_DENIALS, totalDenials);
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
     * 熔断判定由 {@link #shouldFallbackToPrompting()} 派生（denialTracking.ts:40-45）。
     *
     * @return 回退快照：fallback=true 表示本次拒绝触发超限回退（调用方应转 ask 用户确认）；
     *         计数为清零前的值，供 CC warning 文案使用（permissions.ts:1003-1007）
     */
    public FallbackSnapshot recordDenial() {
        consecutiveDenials++;
        totalDenials++;
        boolean fallback = consecutiveDenials >= threshold || totalDenials >= maxTotal;
        // CC permissions.ts:1003-1007 — 清零前捕获计数（warning 文案用）
        int snapshotConsecutive = consecutiveDenials;
        int snapshotTotal = totalDenials;
        if (totalDenials >= maxTotal) {
            // CC permissions.ts:1034-1040 — hitTotalLimit → 双计数清零，total 熔断不持久
            consecutiveDenials = 0;
            totalDenials = 0;
        }
        // [IMP-9] per-agent 本地态就地写回（CC persistDenialState Object.assign）
        writeBackLocalState();
        return new FallbackSnapshot(fallback, snapshotConsecutive, snapshotTotal);
    }

    /**
     * 记录一次放行 · 对齐 CC {@code recordSuccess}（denialTracking.ts:32-38）。
     *
     * <p>只清零 consecutive（total 保留），断连拒链（CC permissions.ts:486-499：
     * auto 模式下任意 allow 事件恢复分类器）。熔断随之解除（派生查询不再 fallback，
     * 验收 R4）。
     */
    public void recordSuccess() {
        consecutiveDenials = 0;
        // [IMP-9] per-agent 本地态就地写回（CC persistDenialState Object.assign）
        writeBackLocalState();
    }

    /**
     * 是否应回退到用户确认 · 对齐 CC {@code shouldFallbackToPrompting}
     * （denialTracking.ts:40-45）派生查询。
     *
     * <p>连续拒绝 ≥ 阈值（maxConsecutive=3）或累计拒绝 ≥ 上限（maxTotal=20）→ true。
     * Java 不再维护持久状态机（O50 已删），门控方直接调本查询。
     *
     * @return true 表示应回退 prompting（分类器不再被咨询）
     */
    public boolean shouldFallbackToPrompting() {
        return consecutiveDenials >= threshold || totalDenials >= maxTotal;
    }

    /**
     * 获取当前连续拒绝次数。
     *
     * @return 连续拒绝计数
     */
    public int getConsecutiveDenials() {
        return consecutiveDenials;
    }

    /**
     * 获取累计拒绝次数。
     *
     * @return 累计拒绝计数
     */
    public int getTotalDenials() {
        return totalDenials;
    }

    /**
     * 拒绝快照 · 对齐 CC handleDenialLimitExceeded 清零前捕获的计数
     * （permissions.ts:1003-1007）。
     *
     * @param fallback          本次拒绝是否触发超限回退（shouldFallbackToPrompting）
     * @param consecutiveDenials 清零前的连续拒绝计数
     * @param totalDenials       清零前的累计拒绝计数
     */

    /**
     * 获取累计拒绝上限（CC DENIAL_LIMITS.maxTotal）。
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
