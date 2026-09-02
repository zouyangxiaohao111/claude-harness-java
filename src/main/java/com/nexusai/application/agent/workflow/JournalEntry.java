package com.nexusai.application.agent.workflow;

/**
 * Journal 条目 · CC original: {@code JournalEntry} (Open-ClaudeCode/packages/workflow-engine/src/types.ts:77-82)。
 *
 * <ul>
 *   <li>{@code key} — {@code agentCallKey(prompt, params)}：{@code sha256(prompt + '\n' + canonicalParams)}，
 *       同一 prompt+params 恒同 key（journal resume 命中依据）</li>
 *   <li>{@code seq} — agent() 调用顺序号（源自 agentIdSeq；跨子 workflow 单调递增）。
 *       {@code push} 序 = 完成顺序（并行完成序 ≠ 调用序）；{@code read} 按 seq 重排以稳定 resume</li>
 *   <li>{@code result} — {@link AgentRunResult} 三态判别联合</li>
 * </ul>
 *
 * @param key    CC original: {@code key} (types.ts:78)
 * @param seq    CC original: {@code seq} (types.ts:79) — 旧条目缺 seq 按 0 处理（Jackson 对缺失原始 int 补 0）
 * @param result CC original: {@code result} (types.ts:80)
 */
public record JournalEntry(String key, int seq, AgentRunResult result) {
}
