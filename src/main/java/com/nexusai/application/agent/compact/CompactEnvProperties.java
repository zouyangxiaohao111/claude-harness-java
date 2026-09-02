package com.nexusai.application.agent.compact;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 压缩阈值 env 覆盖映射 · 对齐 CC {@code autoCompact.ts} 的 override env（OD-16 裁决）。
 *
 * <p>prefix = {@code claude}，Spring 宽松绑定下 3 个字段与 CC env 一一对应：
 * <table>
 *   <tr><th>本字段</th><th>CC env</th><th>CC 源码</th><th>语义</th></tr>
 *   <tr><td>{@link #codeAutoCompactWindow()}</td><td>CLAUDE_CODE_AUTO_COMPACT_WINDOW</td><td>autoCompact.ts:40</td><td>[W3-1] 已删读取路（DB settings.auto_compact_window 权威），保留字段仅兼容绑定</td></tr>
 *   <tr><td>{@link #autocompactPctOverride()}</td><td>CLAUDE_AUTOCOMPACT_PCT_OVERRIDE</td><td>autoCompact.ts:79</td><td>按百分比取自动压缩阈值（min 语义，0-100）</td></tr>
 *   <tr><td>{@link #codeBlockingLimitOverride()}</td><td>CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE</td><td>autoCompact.ts:127</td><td>直接覆盖 blocking 上限（>0 生效）</td></tr>
 * </table>
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>: CC 的 override env 是生产可调阈值/blocking 的唯一通道
 * （OD-16 ADJUDICATED 移植全部 override env）。DISABLE_COMPACT / DISABLE_AUTO_COMPACT
 * 两个 disable env 归 IMP-07（isAutoCompactEnabled），本类只承载阈值体系的 3 个 override。
 *
 * <p>未设置时各字段为 {@code null} —— 与 CC {@code process.env.X 为 undefined} 等价
 * （不参与 min/覆盖），由 {@link CompactThresholdSystem} 判定。
 */
@ConfigurationProperties(prefix = "claude")
public class CompactEnvProperties {

    /**
     * CLAUDE_CODE_AUTO_COMPACT_WINDOW（CC autoCompact.ts:40）· 收窄上下文窗口。
     *
     * <p>[W3-1] 已不再参与计算（env 路删除，用户拍板：DB settings.auto_compact_window 权威，
     * V25 建列）——{@link CompactThresholdSystem} 不再读取本字段，仅保留字段兼容旧绑定
     * （防止残留 env 静默影响窗口，收窄语义由 settings 承担）。待清理项：后续可整体移除。
     */
    private Integer codeAutoCompactWindow;

    /**
     * CLAUDE_AUTOCOMPACT_PCT_OVERRIDE（CC autoCompact.ts:79）· 百分比阈值覆盖。
     *
     * <p>等价 CC {@code parseFloat(process.env.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE)}；
     * 仅 &gt; 0 且 ≤ 100 时生效，阈值取 {@code Math.min(floor(effectiveWindow*pct/100), autoThreshold)}。
     */
    private Double autocompactPctOverride;

    /**
     * CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE（CC autoCompact.ts:127）· blocking 上限直接覆盖。
     *
     * <p>等价 CC {@code parseInt(process.env.CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE, 10)}；
     * 仅 &gt; 0 时替换默认 {@code effectiveWindow - 3000}。
     */
    private Integer codeBlockingLimitOverride;

    /**
     * CLAUDE_CODE_DISABLE_1M_CONTEXT（CC context.ts:32 is1mContextDisabled）· 1M 上下文禁用门。
     *
     * <p>等价 CC {@code isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_1M_CONTEXT)}（envUtils.ts:32-37，
     * 真值集 {'1','true','yes','on'}）；Spring 宽松绑定 CLAUDE_CODE_DISABLE_1M_CONTEXT →
     * {@code claude.code-disable-1m-context}，StringToBooleanConverter 接受该全集（大小写不敏感）。
     * true = 禁用 1M 窗口（HIPAA 合规场景，context.ts:27-33）：{@code has1mContext} 恒 false、
     * 窗口解析超 200k 钳制回落 200k（context.ts:75-81）。
     */
    private Boolean disable1MContext;

    public Integer getCodeAutoCompactWindow() {
        return codeAutoCompactWindow;
    }

    public void setCodeAutoCompactWindow(Integer codeAutoCompactWindow) {
        this.codeAutoCompactWindow = codeAutoCompactWindow;
    }

    public Double getAutocompactPctOverride() {
        return autocompactPctOverride;
    }

    public void setAutocompactPctOverride(Double autocompactPctOverride) {
        this.autocompactPctOverride = autocompactPctOverride;
    }

    public Integer getCodeBlockingLimitOverride() {
        return codeBlockingLimitOverride;
    }

    public void setCodeBlockingLimitOverride(Integer codeBlockingLimitOverride) {
        this.codeBlockingLimitOverride = codeBlockingLimitOverride;
    }

    public Boolean getDisable1MContext() {
        return disable1MContext;
    }

    public void setDisable1MContext(Boolean disable1MContext) {
        this.disable1MContext = disable1MContext;
    }
}
