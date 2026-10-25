package com.nexusai.application.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.provider.entity.ModelRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EmbeddingService 策略纯函数测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：向量链路最容易出的不是协议错，而是「拿错模型/用错配置」——
 * 例如把对话模型当 embedding 调用（服务端未必报错，可能返回奇怪的向量），或批大小没按
 * 百炼上限收敛（单请求 >10 条直接 400）。这些判据放在纯函数里，测试不依赖 DB/Spring。
 */
@DisplayName("EmbeddingService: 类型门 / 维度 / 批大小 / 输入校验")
class EmbeddingServicePolicyTest {

    private static ModelRecord model(String type, Integer dimensions, Integer batchSize) {
        ModelRecord m = new ModelRecord();
        m.setName("text-embedding-v4");
        m.setType(type);
        m.setEmbeddingDimensions(dimensions);
        m.setEmbeddingBatchSize(batchSize);
        return m;
    }

    @Test
    @DisplayName("类型门：只有 embedding 类型能走向量链路；chat/rerank/null 一律明确拒绝")
    void typeGate() {
        EmbeddingService.assertEmbeddingModel(model("embedding", 1024, 10));

        assertThatThrownBy(() -> EmbeddingService.assertEmbeddingModel(model("chat", null, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("不能用于向量化");
        assertThatThrownBy(() -> EmbeddingService.assertEmbeddingModel(model("rerank", null, null)))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> EmbeddingService.assertEmbeddingModel(model(null, null, null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("维度优先级：请求覆盖 > 模型配置；null/≤0 → null（不发送 dimensions）")
    void resolveDimensions() {
        assertThat(EmbeddingService.resolveDimensions(model("embedding", 1024, 10), 512)).isEqualTo(512);
        assertThat(EmbeddingService.resolveDimensions(model("embedding", 1024, 10), null)).isEqualTo(1024);
        assertThat(EmbeddingService.resolveDimensions(model("embedding", null, 10), null)).isNull();
        assertThat(EmbeddingService.resolveDimensions(model("embedding", 0, 10), null)).isNull();
    }

    @Test
    @DisplayName("批大小：未配置 → 10；显式值生效；超过硬上限 64 → 收敛到 64")
    void resolveBatchSize() {
        assertThat(EmbeddingService.resolveBatchSize(model("embedding", null, null))).isEqualTo(10);
        assertThat(EmbeddingService.resolveBatchSize(model("embedding", null, 0))).isEqualTo(10);
        assertThat(EmbeddingService.resolveBatchSize(model("embedding", null, 5))).isEqualTo(5);
        assertThat(EmbeddingService.resolveBatchSize(model("embedding", null, 100))).isEqualTo(64);
    }

    @Test
    @DisplayName("输入校验：空列表 / 空串 / 超条数 / 超长 都被拒（避免把超长文本发到服务端）")
    void validateInputs() {
        assertThatThrownBy(() -> EmbeddingService.validateInputs(null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> EmbeddingService.validateInputs(List.of()))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> EmbeddingService.validateInputs(List.of("ok", "  ")))
            .isInstanceOf(ValidationException.class).hasMessageContaining("inputs[1]");
        assertThatThrownBy(() -> EmbeddingService.validateInputs(List.of("x".repeat(32_769))))
            .isInstanceOf(ValidationException.class).hasMessageContaining("超过");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < EmbeddingService.MAX_INPUTS + 1; i++) {
            tooMany.add("t" + i);
        }
        assertThatThrownBy(() -> EmbeddingService.validateInputs(tooMany))
            .isInstanceOf(ValidationException.class).hasMessageContaining("最多");
    }

    @Test
    @DisplayName("合法输入通过（不抛异常）")
    void validateInputs_ok() {
        EmbeddingService.validateInputs(List.of("第一段", "第二段"));
    }
}
