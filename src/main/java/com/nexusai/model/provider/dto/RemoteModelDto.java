package com.nexusai.model.provider.dto;

/**
 * 远端提供商 {@code GET {baseUrl}/models} 返回的**一个模型**（原始事实）。
 *
 * <p><b>WHY 只回事实、不做推断</b>：模型类型（chat/embedding/rerank）属展示决策，
 * 前端做（并要标出推断来源）；后端只负责把各家不同的字段名**归一化**后原样回传，
 * 拿不到的字段就是 {@code null}，<b>不在后端填默认值</b> ——
 * 默认值一旦写进库里就分不清「接口说的」和「我们猜的」。
 *
 * <p><b>字段归一化</b>（各家键名不一，实测 2026-11-03）：
 * <ul>
 *   <li>上下文窗口：{@code context_window} → {@code context_length} →
 *       {@code top_provider.context_length} → {@code max_context_length}</li>
 *   <li>输出上限：{@code max_output_tokens} → {@code top_provider.max_completion_tokens} →
 *       {@code max_tokens}</li>
 * </ul>
 * 标准 OpenAI 形态（只有 id/object/created/owned_by）两者皆 null ⇒ 前端落默认。
 *
 * @param id                模型 id（必填 · 缺失的行会被丢弃）
 * @param name              显示名（DeepSeek 的 {@code name} / OpenRouter 的 {@code name} / Anthropic 的 {@code display_name}）
 * @param contextWindow     上下文窗口（token）；null = 提供商未给出
 * @param maxOutputTokens   单次最大输出（token）；null = 提供商未给出
 */
public record RemoteModelDto(
    String id,
    String name,
    Integer contextWindow,
    Integer maxOutputTokens
) {}
