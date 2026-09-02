package com.nexusai.model.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/sessions/{sessionId}/partial-compact 请求 · 对齐 CC REPL.tsx:4918
 * {@code onSummarize(message, feedback?, direction: PartialCompactDirection = 'from')}
 * 与前端对接 §7.2 入参 {@code { messageId, direction: from/up_to, feedback? }}。
 *
 * <p><b>语义（CC MessageSelector.tsx + REPL.tsx:4921-4943）</b>:
 * <ul>
 *   <li>{@code messageId} —— 前端消息选择器选中的消息（pivot 定位用，REPL.tsx:4922
 *       {@code compactMessages.indexOf(message)}；Java 侧按 messageId 等价）</li>
 *   <li>{@code direction} —— from（摘要切点之后，保留侧不清旧 boundary）| up_to
 *       （摘要切点之前，保留侧 strip 旧 boundary）；缺省 from（CC 默认值）</li>
 *   <li>{@code feedback} —— 用户补充上下文（CC userFeedback，可选）</li>
 * </ul>
 */
public record PartialCompactRequest(
    /** 选中的消息 id（CC REPL.tsx:4922 pivot 定位）· 不在 active 列表 → 404 */
    @NotBlank String messageId,
    /** 压缩方向 · CC PartialCompactDirection（MessageSelector.tsx，wire: from/up_to，缺省 from） */
    Direction direction,
    /** 用户补充上下文 · CC userFeedback（compact.ts:827-834，可选） */
    String feedback
) {

    /**
     * 压缩方向 · CC PartialCompactDirection = 'from' | 'up_to'（MessageSelector.tsx:31）。
     * Jackson {@code @JsonProperty} 保证 wire 格式为小写 from/up_to（非枚举名 FROM/UP_TO）。
     */
    public enum Direction {
        /** 摘要切点之后（较晚）消息，保留头段 · CC 'from'（compact.ts:784） */
        @JsonProperty("from") FROM,
        /** 摘要切点之前（较早）消息，保留尾段 · CC 'up_to'（compact.ts:783） */
        @JsonProperty("up_to") UP_TO
    }

    /** compact 构造器：direction 缺省 → FROM（对齐 CC onSummarize 默认 'from'，REPL.tsx:4918）。 */
    public PartialCompactRequest {
        if (direction == null) {
            direction = Direction.FROM;
        }
    }

    /** 映射到 {@link com.nexusai.application.agent.compact.CompactPrompt.Direction}。 */
    public com.nexusai.application.agent.compact.CompactPrompt.Direction toCompactDirection() {
        return direction == Direction.UP_TO
            ? com.nexusai.application.agent.compact.CompactPrompt.Direction.UP_TO
            : com.nexusai.application.agent.compact.CompactPrompt.Direction.FROM;
    }
}
