package com.nexusai.model.session.dto;

/** 模型生成结束原因 */
public enum FinishReason {
    stop,
    length,
    max_tokens,       // Anthropic 原始 stop_reason 'max_tokens'（落库原样保留；读回 valueOf 曾抛异常）
    tool_calls,
    content_filter,
    error;

    /**
     * 容错解析：已知枚举名 → 对应常量；未知/空 → null（不抛 IllegalArgumentException）。
     * <p>WHY：DB {@code finish_reason} 列是「枚举名 + 各 provider 原始 stop_reason」混存
     * （Anthropic 落 'max_tokens'/'end_turn'/'tool_use'… 原样；其他路径落枚举名）。读回
     * 一律 {@code valueOf} 会让整条消息读取出错（trace/分页 500）。未知值以 null 表示，
     * 语义安全（transcript 读回侧无 finishReason 判定需求，P-28 已注）。
     */
    public static FinishReason parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
