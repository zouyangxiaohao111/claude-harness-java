package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-D · YoloClassifierResult stage requestId / msgId 字段 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:798, 821, 838, 856, 884, 911, 915, 934, 938, 939}
 * + {@code Open-ClaudeCode/src/types/permissions.ts:377-396}。
 *
 * <p><b>WHY (意图验证)</b>: CC SDK 在响应对象上挂非枚举 {@code _request_id} 属性
 * (API request_id, req_xxx 格式, yoloClassifier.ts:624-628 extractRequestId),
 * yoloClassifier 在 stage1 / stage2 完成时写入 {@code stage1RequestId} /
 * {@code stage2RequestId} 字段, 供 server-side api_usage 日志 join (types/permissions.ts:382)。
 * 与 {@code message.id} (msg_xxx) 是<b>并存的两组字段</b> (yoloClassifier.ts:798/799 相邻两行独立赋值)。
 *
 * <p>[S06 重构] YoloClassifierResult 对齐 CC 布尔 shouldBlock 契约（⊕-01/⊕-02）——
 * 本测试断言新 record 契约：{@code shouldBlock} 布尔 + {@code model} 唯一模型字段 +
 * stage1/2 requestId/msgId 并存。
 *
 * @see YoloClassifierResult
 * @see com.nexusai.infra.llm.LlmProvider.LlmRawResponse
 */
class R32D_YoloClassifierRequestIdTest {

    @Test
    @DisplayName("YoloClassifierResult · 携带 stage1MsgId 字段（stage 1 直接放行）")
    void yoloClassifierResult_carriesStage1RequestId() {
        YoloClassifierResult result = new YoloClassifierResult(
            null,                                    // thinking（stage 1 放行无 thinking）
            false,                                   // shouldBlock（stage 1 快速放行，CC :812）
            "Allowed by fast classifier",            // reason
            false,                                   // unavailable
            false,                                   // transcriptTooLong
            "claude-sonnet",                         // model
            null,                                    // usage（Java provider 不暴露，可为 null）
            0L,                                      // durationMs
            null,                                    // promptLengths
            null,                                    // errorDumpPath
            YoloClassifierResult.STAGE_FAST,         // stage=1（CC 'fast'）
            null,                                    // stage1Usage
            null,                                    // stage1DurationMs
            "req_stage1_xyz987",                     // stage1RequestId (CC yoloClassifier.ts:798 extractRequestId)
            "msg_stage1_abc123",                     // stage1MsgId (CC yoloClassifier.ts:821,838,856)
            null,                                    // stage2Usage
            null,                                    // stage2DurationMs
            null,                                    // stage2RequestId (stage 1 直接返回)
            null                                     // stage2MsgId (stage 1 直接返回)
        );

        assertThat(result.stage1MsgId())
            .as("stage1MsgId 必须携带")
            .isEqualTo("msg_stage1_abc123");
        assertThat(result.stage2MsgId())
            .as("stage2MsgId 在 stage 1 终止时应为 null")
            .isNull();
        assertThat(result.stage1RequestId())
            .as("stage1RequestId 必须携带 (CC yoloClassifier.ts:798)")
            .isEqualTo("req_stage1_xyz987");
        assertThat(result.stage2RequestId())
            .as("stage2RequestId 在 stage 1 终止时应为 null")
            .isNull();
        assertThat(result.shouldBlock())
            .as("stage 1 放行 → shouldBlock=false（CC 布尔契约）")
            .isFalse();
        assertThat(result.stage()).isEqualTo(1);
    }

    @Test
    @DisplayName("YoloClassifierResult · 携带 stage2MsgId 字段 (stage 1 block → stage 2)")
    void yoloClassifierResult_carriesStage2RequestId() {
        YoloClassifierResult result = new YoloClassifierResult(
            "thinking blocks parsed",                // thinking（stage 2 chain-of-thought）
            true,                                    // shouldBlock（stage 2 block，CC :925）
            "Stage 2 deep thinking deny",            // reason
            false,                                   // unavailable
            false,                                   // transcriptTooLong
            "claude-sonnet-thinking",                // model
            null,                                    // usage
            0L,                                      // durationMs
            null,                                    // promptLengths
            null,                                    // errorDumpPath
            YoloClassifierResult.STAGE_THINKING,     // stage=2（CC 'thinking'）
            null,                                    // stage1Usage
            null,                                    // stage1DurationMs
            "req_stage1_abc654",                     // stage1RequestId (CC yoloClassifier.ts:934)
            "msg_stage1_xyz789",                     // stage1MsgId
            null,                                    // stage2Usage
            null,                                    // stage2DurationMs
            "req_stage2_ghi321",                     // stage2RequestId (CC yoloClassifier.ts:938)
            "msg_stage2_def456"                      // stage2MsgId (CC yoloClassifier.ts:915,939)
        );

        assertThat(result.stage1MsgId())
            .as("stage1MsgId 必须携带 (即使最终走 stage 2)")
            .isEqualTo("msg_stage1_xyz789");
        assertThat(result.stage2MsgId())
            .as("stage2MsgId 必须携带")
            .isEqualTo("msg_stage2_def456");
        assertThat(result.stage1RequestId())
            .as("stage1RequestId 必须携带 (CC yoloClassifier.ts:934)")
            .isEqualTo("req_stage1_abc654");
        assertThat(result.stage2RequestId())
            .as("stage2RequestId 必须携带 (CC yoloClassifier.ts:938)")
            .isEqualTo("req_stage2_ghi321");
        assertThat(result.shouldBlock())
            .as("stage 2 block → shouldBlock=true（CC 布尔契约）")
            .isTrue();
        assertThat(result.stage()).isEqualTo(2);
        assertThat(result.thinking())
            .as("thinking 仅 stage 2 成功路径携带（CC yoloClassifier.ts:924）")
            .isEqualTo("thinking blocks parsed");
    }
}
