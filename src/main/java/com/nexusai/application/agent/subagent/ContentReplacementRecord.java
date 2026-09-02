package com.nexusai.application.agent.subagent;

/**
 * Content replacement 记录 · 对齐 CC {@code ContentReplacementRecord}
 * (Open-ClaudeCode/src/utils/toolResultStorage.ts:475-479).
 *
 * <p>作为 {@link AgentTranscript#getAgentTranscript} 返回值 contentReplacements 的元素:
 * transcript 内被 budget 替换为 preview 的 tool result (toolUseId → replacement 文本).
 * resume 时由 {@link com.nexusai.application.agent.tool.ContentReplacementState#reconstructForSubagentResume}
 * 合并回 parentState (CC toolResultStorage.ts:1001).
 *
 * <p>字段名严格对齐 CC (S5 纪律): {@code replacement} 是 LLM 实际看到的精确文本
 * (CC 注释 toolResultStorage.ts:471-473 "stored rather than derived on resume"),
 * 非 {@code preview}; {@code kind} 是判别字段, 当前仅 {@code 'tool-result'},
 * 未来其他替换机制 (用户文本/图片离屏) 共用同一条目类型.
 *
 * @param kind        CC original: ContentReplacementRecord.kind (toolResultStorage.ts:476)
 * @param toolUseId   CC original: ContentReplacementRecord.toolUseId (toolResultStorage.ts:477)
 * @param replacement CC original: ContentReplacementRecord.replacement (toolResultStorage.ts:478)
 */
public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {}
