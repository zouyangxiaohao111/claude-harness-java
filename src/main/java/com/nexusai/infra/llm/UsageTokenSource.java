package com.nexusai.infra.llm;

/**
 * 用量数值的<b>来源可信度</b> · 账本与明细行的必备标记（防估算值与上游上报值混算）。
 *
 * <p><b>WHY 必须区分</b>：
 * <ul>
 *   <li>{@link #UPSTREAM} — provider 从上游响应/流事件解析出的真实 usage（Anthropic
 *       message_start / message_delta；OpenAI final chunk / 非流式 ChatCompletion.usage）。</li>
 *   <li>{@link #ESTIMATED} — 上游未给出 usage 但调用确实发生（典型：OpenAI 流式被 abort 时
 *       {@code include_usage} 的 final chunk 永不到达；或 provider 未解析 usage 的旧路径）⇒ 用
 *       本地估算（沿用仓库既有 {@code text.length() / 4} 估算先例）<b>并显式标记</b>，统计时单列，
 *       不得与 UPSTREAM 相加后当作账单数字。</li>
 *   <li>{@link #UNKNOWN} — 既无上报也无可用估算（记录该次调用发生，token 记 0）。</li>
 * </ul>
 */
public enum UsageTokenSource {

    /** 上游上报（provider 解析所得，可与账单对账）。 */
    UPSTREAM,
    /** 本地估算（有调用发生但无上报；仅用于开销观测，不得当账单）。 */
    ESTIMATED,
    /** 无数据（仅记录调用事实）。 */
    UNKNOWN
}
