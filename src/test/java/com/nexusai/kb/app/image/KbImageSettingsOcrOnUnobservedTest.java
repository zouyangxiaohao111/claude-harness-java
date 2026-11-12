package com.nexusai.kb.app.image;

import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ocr_on_unobserved} 可配置解析 · 对齐源站
 * {@code ImageProcessingConfig.ImagePipelineParams["smartocr.ocr_on_unobserved"]}
 * （{@code internal/types/knowledgebase.go:571-577}）。
 *
 * <p><b>WHY 这些断言</b>：这个参数决定「属性没观察到时要不要继续 OCR」。
 * 读错的代价是单向的 —— 读成 false 会让整批图片<b>静默跳过 OCR</b>（字丢了没人知道），
 * 读成 true 最多多花一次模型调用。所以除了「显式 false」以外，一切异常输入都必须落 true。
 */
@DisplayName("图片管线 · ocr_on_unobserved 可配置读取")
class KbImageSettingsOcrOnUnobservedTest {

    private static KbKnowledgeBaseRecord kbWith(String json) {
        KbKnowledgeBaseRecord kb = new KbKnowledgeBaseRecord();
        kb.setImageProcessingConfig(json);
        return kb;
    }

    @Test
    @DisplayName("源站槽位键 = false ⇒ 关闭兜底 OCR")
    void slotKeyFalseDisables() {
        KbKnowledgeBaseRecord kb = kbWith(
            "{\"image_pipeline_params\":{\"smartocr.ocr_on_unobserved\":false}}");
        assertThat(KbImageSettings.ocrOnUnobservedOf(kb)).isFalse();
        assertThat(KbImageSettings.from(kb).ocrOnUnobserved()).isFalse();
    }

    @Test
    @DisplayName("槽位键缺失时读扁平 ocr_on_unobserved（兼容简写）")
    void flatKeyFallback() {
        KbKnowledgeBaseRecord kb = kbWith("{\"ocr_on_unobserved\":false}");
        assertThat(KbImageSettings.ocrOnUnobservedOf(kb)).isFalse();
    }

    @Test
    @DisplayName("槽位键优先于扁平键")
    void slotKeyWinsOverFlat() {
        KbKnowledgeBaseRecord kb = kbWith(
            "{\"ocr_on_unobserved\":false,\"image_pipeline_params\":{\"smartocr.ocr_on_unobserved\":true}}");
        assertThat(KbImageSettings.ocrOnUnobservedOf(kb)).isTrue();
    }

    @Test
    @DisplayName("空配置 / 无字段 / null 行 ⇒ 默认 true")
    void defaultsToTrue() {
        assertThat(KbImageSettings.ocrOnUnobservedOf(null)).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(kbWith(null))).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(kbWith(""))).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(kbWith("{}"))).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(
            kbWith("{\"enable_multimodal\":true}"))).isTrue();
    }

    @Test
    @DisplayName("坏 JSON / 非布尔值 ⇒ 仍默认 true（宁可多跑一次，不静默丢字）")
    void malformedFallsBackToTrue() {
        assertThat(KbImageSettings.ocrOnUnobservedOf(kbWith("{not json"))).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(
            kbWith("{\"ocr_on_unobserved\":\"false\"}"))).isTrue();
        assertThat(KbImageSettings.ocrOnUnobservedOf(
            kbWith("{\"image_pipeline_params\":{\"smartocr.ocr_on_unobserved\":0}}"))).isTrue();
    }
}
