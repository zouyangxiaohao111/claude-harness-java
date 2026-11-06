package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作结果契约单测 · computer-use（对齐 dsh {@code actionResultSchema}）。
 *
 * <h2>WHY 这个用例重要（规则 9）</h2>
 * <p>dsh 的每个动作返回<b>九字段结构化结果</b>（{@code src/tools.ts:159-196}），
 * 其中三个字段是<b>模型自验证的唯一依据</b>：
 * <ul>
 *   <li>{@code effect.observedStateChanged} —— 动作生效没有；</li>
 *   <li>{@code resolution.{mode,confidence,candidateCount,targetChanged}} —— 点的是不是我以为的那个；</li>
 *   <li>{@code observation} —— 动作后的界面状态（省掉一整轮 observe）。</li>
 * </ul>
 * 本仓此前只返回一句文本 ⇒ 这三项全部丢失。
 * <br>⇒ 本用例钉住「这三个字段必须真的出现在给模型的输出里」，防止将来被简化掉。
 */
class ComputerUseActionResultTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static ComputerUseUiSnapshotStore.Observation observation() {
        return new ComputerUseUiSnapshotStore.Observation(
                "obs-2", "sess-1", "文本编辑", "com.apple.TextEdit", 4242L, "doc.txt",
                System.currentTimeMillis(), Long.MAX_VALUE, "hash-2", "full", "[0] Window", false,
                List.of(), 1);
    }

    @Test
    @DisplayName("结果 JSON 含 dsh actionResultSchema 的关键字段（effect / resolution / observation）")
    void jsonCarriesTheSelfVerificationFields() {
        ComputerUseTargetResolver.Resolution resolution = new ComputerUseTargetResolver.Resolution(
                "semantic-rebind", 0.9d, 1, true, null, null, null);
        ComputerUseActionResult result = new ComputerUseActionResult(
                ComputerUseActionResult.ACTION_CLICK,
                ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                ComputerUseActionResult.ACTIVATION_ALREADY_FRONTMOST,
                false,
                ComputerUseActionResult.POINTER_ROUTING_NONE,
                resolution,
                ComputerUseActionResult.Effect.changed(120L),
                observation());

        ObjectNode permissions = JSON.createObjectNode();
        permissions.put("accessibility", "granted");
        ArrayNode elements = JSON.createArrayNode();
        ObjectNode json = result.toJson(elements, permissions, null);

        // ⭐ 这三个字段是模型自验证的唯一依据 ⇒ 必须真的在输出里
        assertThat(json.has("effect")).isTrue();
        assertThat(json.path("effect").path("observedStateChanged").asBoolean()).isTrue();
        assertThat(json.path("effect").path("observedForMs").asLong()).isEqualTo(120L);
        assertThat(json.has("resolution")).isTrue();
        assertThat(json.path("resolution").path("mode").asText()).isEqualTo("semantic-rebind");
        assertThat(json.path("resolution").path("confidence").asDouble()).isEqualTo(0.9d);
        assertThat(json.path("resolution").path("candidateCount").asInt()).isEqualTo(1);
        assertThat(json.path("resolution").path("targetChanged").asBoolean()).isTrue();
        assertThat(json.has("observation")).isTrue();
        assertThat(json.path("observation").path("observationId").asText()).isEqualTo("obs-2");

        // dsh 的其余字段也要齐
        assertThat(json.path("action").asText()).isEqualTo("click");
        assertThat(json.path("channel").asText()).isEqualTo("accessibility");
        assertThat(json.path("activation").asText()).isEqualTo("already-frontmost");
        assertThat(json.path("pointerInput").asBoolean()).isFalse();
        assertThat(json.path("pointerRouting").asText()).isEqualTo("none");
    }

    @Test
    @DisplayName("「状态没变」必须带 note（dsh 语义：false 不等于动作失败）")
    void unchangedEffectCarriesTheNote() {
        ComputerUseActionResult.Effect effect = ComputerUseActionResult.Effect.unchanged(500L);
        assertThat(effect.observedStateChanged()).isFalse();
        assertThat(effect.observedForMs()).isEqualTo(500L);
        // ⭐ 没有这条 note，模型会以为「没生效」而重试（dsh 的 Skill 明确禁止自动重试）
        assertThat(effect.note()).isNotBlank();
        assertThat(effect.note()).contains("pixel-only");

        // ⭐⭐ 且 note 必须真的进了给模型的 JSON（第一版只断言了 Effect record 本身
        //   ⇒ 实现把 note 吞掉时测试照绿 ⇒ 负向对照没生效）
        ComputerUseActionResult result = new ComputerUseActionResult(
                ComputerUseActionResult.ACTION_CLICK,
                ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                ComputerUseActionResult.ACTIVATION_NOT_REQUESTED,
                false,
                ComputerUseActionResult.POINTER_ROUTING_NONE,
                null,
                effect,
                observation());
        ObjectNode permissions = JSON.createObjectNode();
        permissions.put("accessibility", "granted");
        ObjectNode json = result.toJson(JSON.createArrayNode(), permissions, null);
        assertThat(json.path("effect").path("note").asText())
                .contains("pixel-only")
                .contains("may still have occurred");
    }

    @Test
    @DisplayName("「状态变了」不带 note（避免噪音）")
    void changedEffectHasNoNote() {
        ComputerUseActionResult.Effect effect = ComputerUseActionResult.Effect.changed(80L);
        assertThat(effect.observedStateChanged()).isTrue();
        assertThat(effect.note()).isNull();
    }

    @Test
    @DisplayName("渲染文本含关键结论（模型读的是文本，不是裸 JSON）")
    void renderCarriesKeyConclusions() {
        ComputerUseActionResult result = new ComputerUseActionResult(
                ComputerUseActionResult.ACTION_CLICK,
                ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                ComputerUseActionResult.ACTIVATION_NOT_REQUESTED,
                false,
                ComputerUseActionResult.POINTER_ROUTING_NONE,
                null,
                ComputerUseActionResult.Effect.unchanged(300L),
                observation());
        ObjectNode permissions = JSON.createObjectNode();
        permissions.put("accessibility", "granted");

        String rendered = ComputerUseActionResult.render(result, permissions, null);

        assertThat(rendered).contains("observedStateChanged=false");
        assertThat(rendered).contains("obs-2");
        assertThat(rendered).contains("\"effect\"");
        assertThat(rendered).contains("\"observation\"");
    }
}
