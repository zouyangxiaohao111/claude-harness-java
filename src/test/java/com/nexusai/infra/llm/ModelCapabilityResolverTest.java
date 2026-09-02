package com.nexusai.infra.llm;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ModelCapabilityResolver 图片能力判定单测（A2 · 模型类型判定）。
 *
 * <p>WHY（CLAUDE.md 规则九 · 测试验证意图）：用户拍板「图片进模型根据当前 model 类型判断支持与否」
 * ——type=multimodal/vision 支持直接发 image content block，未知/失败必须保守回落多模态工具路由，
 * 否则不支持图片的模型收到 image block 直接 API 报错（模型拒绝）。本测试锁定三层意图：
 * <ol>
 *   <li><b>multimodal/vision → true</b>：ModelType.java:7-8 两型是支持图片的唯二判据</li>
 *   <li><b>其他 type / DB 未命中 / 入参不可用 → false</b>：保守，宁走工具路由也不冒险直发</li>
 *   <li><b>解析异常 → false</b>：查询失败不能打崩主消息注入链路</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ModelCapabilityResolverTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    /** 构造 type 指定的 enabled 模型（裸名 → 兼容路径 selectListByQuery 命中）。 */
    private static ModelRecord modelOfType(String type) {
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("some-model");
        m.setType(type);
        m.setEnabled(true);
        return m;
    }

    @Test
    @DisplayName("type=multimodal → true（支持图片，直接注入 image block）· ModelType.java:8")
    void multimodalType_supportsImage() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType("multimodal")));
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "some-model"))
            .as("multimodal 型应支持图片")
            .isTrue();
    }

    @Test
    @DisplayName("type=vision → true（支持图片）· ModelType.java:7")
    void visionType_supportsImage() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType("vision")));
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "some-model"))
            .as("vision 型应支持图片")
            .isTrue();
    }

    @Test
    @DisplayName("type=chat/text/image → false（保守回落多模态工具路由）")
    void nonVisionMultimodalTypes_doNotSupportImage() {
        for (String type : new String[] {"chat", "text", "image", "image_generation", "embedding", "audio"}) {
            when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType(type)));
            assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "some-model"))
                .as("type=%s 不应支持图片", type)
                .isFalse();
        }
    }

    @Test
    @DisplayName("DB 未命中（无 enabled 模型）→ false（保守）")
    void modelNotFound_false() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of());
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "unknown-model"))
            .as("未命中模型不能断定支持图片")
            .isFalse();
    }

    @Test
    @DisplayName("type 为空 → false（未知类型保守回落）")
    void nullType_false() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType(null)));
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "some-model"))
            .as("type 为空的模型不能断定支持图片")
            .isFalse();
    }

    @Test
    @DisplayName("解析异常 → false（查询失败不阻断主消息注入）")
    void resolverThrows_false() {
        when(modelMapper.selectListByQuery(any())).thenThrow(new RuntimeException("db down"));
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "some-model"))
            .as("DB 异常应保守回落 false")
            .isFalse();
    }

    @Test
    @DisplayName("入参不可用（modelMapper=null / modelName=blank）→ false（保守）")
    void nullInputs_false() {
        assertThat(ModelCapabilityResolver.supportsImage(null, providerMapper, "some-model")).isFalse();
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, null)).isFalse();
        assertThat(ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, "  ")).isFalse();
    }
}
