package com.nexusai.kb.api;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.config.KbConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问答参数端点 · 入口校验。
 *
 * <p><b>WHY</b>：{@link KbConfigService#updateQaParams} 刻意<b>不</b>校验范围
 * （javadoc 写明「那是端点 DTO 的职责」）。若端点也不校验，越界值会存进库并在
 * 检索阶段表现为「一条都搜不到」—— 不报错、无告警，正是缺陷 27 那类静默形态。
 */
@DisplayName("[W12] 问答参数端点")
class KbQaConfigControllerTest {

    private static KbConfigService mockService(KbConfigService.QaParams after) {
        KbConfigService svc = Mockito.mock(KbConfigService.class);
        Mockito.when(svc.readQaParams()).thenReturn(after);
        return svc;
    }

    private static KbConfigService.QaParams params(boolean dataAnalysis) {
        return new KbConfigService.QaParams(
            30, 0.2, 0.3, 0.3, 30, 12, 5, true, true, false, 1.0, 0.9,
            5, false, 3, dataAnalysis, null, null, false);
    }

    @Test
    @DisplayName("GET 直接返回服务层已解析快照（不自行补默认值）")
    void getDelegatesToService() {
        KbConfigService svc = mockService(params(true));
        KbQaConfigController controller = new KbQaConfigController(svc);

        assertThat(controller.getQaParams().dataAnalysisEnabled()).isTrue();
        Mockito.verify(svc).readQaParams();
    }

    @Test
    @DisplayName("⭐ 阈值越界 ⇒ 400，且**不写库**（先校验后写）")
    void rejectsOutOfRangeThresholdBeforeWriting() {
        KbConfigService svc = mockService(params(false));
        KbQaConfigController controller = new KbQaConfigController(svc);

        KbConfigService.QaParamsUpdate bad = new KbConfigService.QaParamsUpdate(
            null, 1.5, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.updateQaParams(bad))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("vectorThreshold");
        Mockito.verify(svc, Mockito.never()).updateQaParams(Mockito.any());
    }

    @Test
    @DisplayName("topK 上界越界 ⇒ 400（不是 500，也不是静默夹取）")
    void rejectsTooLargeTopK() {
        KbConfigService svc = mockService(params(false));
        KbQaConfigController controller = new KbQaConfigController(svc);

        KbConfigService.QaParamsUpdate bad = new KbConfigService.QaParamsUpdate(
            100000, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.updateQaParams(bad))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("embeddingTopK");
        Mockito.verify(svc, Mockito.never()).updateQaParams(Mockito.any());
    }

    @Test
    @DisplayName("合法更新 ⇒ 写库并返回更新后的快照")
    void writesAndReturnsResolvedSnapshot() {
        KbConfigService svc = mockService(params(true));
        KbQaConfigController controller = new KbQaConfigController(svc);

        KbConfigService.QaParamsUpdate ok = new KbConfigService.QaParamsUpdate(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, true, null, null, null);

        KbConfigService.QaParams after = controller.updateQaParams(ok);

        Mockito.verify(svc).updateQaParams(ok);
        assertThat(after.dataAnalysisEnabled()).isTrue();
    }

    @Test
    @DisplayName("null 请求体 ⇒ 400（而不是 NPE 500）")
    void rejectsNullBody() {
        KbQaConfigController controller = new KbQaConfigController(mockService(params(false)));
        assertThatThrownBy(() -> controller.updateQaParams(null))
            .isInstanceOf(ValidationException.class);
    }
}
