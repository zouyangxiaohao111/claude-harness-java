package com.nexusai.domain.provider;

import com.nexusai.model.provider.dto.ModelProviderCatalogDto;
import com.nexusai.model.provider.dto.ProviderType;

import java.util.List;

/**
 * 模型厂商目录（批次 W8）· 静态数据，不是数据库表。
 *
 * <p><b>⭐ 与 {@code providers} 表的区别（很容易搞混）</b>：
 * <ul>
 *   <li>{@code providers} 表 = 用户<b>配好的</b>厂商实例：有 api_key、有 base_url，可被模型引用。</li>
 *   <li>本类 = 平台<b>支持的种类</b>：新建厂商时下拉框里的那几项。</li>
 * </ul>
 * ⇒ {@code GET /providers} 读表；{@code GET /models/providers} 读本类。
 *
 * <p><b>为什么不照搬源侧 30+ 家厂商目录</b>：源侧的目录是用来「按模型名自动推断
 * 协议/思考等级/上下文」的（{@code ResolveModelCatalog}），本仓的模型配置是
 * 用户手填的，没有那套推断机制。照搬一份用不上的 JSON 只会误导用户。
 * ⇒ 只列<b>本仓真有 LlmProvider 实现</b>的四种，这也是用户在下拉框里能选的全部。
 */
public final class ModelProviderCatalog {

    private ModelProviderCatalog() {
    }

    private static final List<ModelProviderCatalogDto> ENTRIES = List.of(
        new ModelProviderCatalogDto(
            ProviderType.openai_compatible.name(),
            "OpenAI 兼容",
            // 兼容层几乎什么类型都能跑：对话/文本/视觉/多模态/图像/embedding/rerank
            List.of("chat", "text", "vision", "multimodal", "image",
                "image_generation", "embedding", "audio", "rerank"),
            List.of("https://api.openai.com/v1")),
        new ModelProviderCatalogDto(
            ProviderType.openai_sdk.name(),
            "OpenAI 官方 SDK",
            List.of("chat", "text", "vision", "multimodal", "embedding", "rerank"),
            List.of("https://api.openai.com/v1")),
        new ModelProviderCatalogDto(
            ProviderType.anthropic.name(),
            "Anthropic",
            List.of("chat", "text", "vision"),
            List.of("https://api.anthropic.com")),
        new ModelProviderCatalogDto(
            ProviderType.mock.name(),
            "Mock（本地联调用）",
            List.of("chat", "embedding", "rerank"),
            List.of())
    );

    /** 全部厂商。 */
    public static List<ModelProviderCatalogDto> all() {
        return ENTRIES;
    }

    /**
     * 按 id 取厂商。
     *
     * @return 厂商；未知返回 {@code null}（调用方决定抛 404）
     */
    public static ModelProviderCatalogDto get(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase();
        return ENTRIES.stream()
            .filter(e -> e.id().equalsIgnoreCase(key))
            .findFirst()
            .orElse(null);
    }

    /**
     * 按模型类型过滤。
     *
     * @param modelType 模型类型（可空 = 全部）
     * @return 支持该类型的厂商；类型未知返回空列表（由调用方报 400）
     */
    public static List<ModelProviderCatalogDto> byModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return ENTRIES;
        }
        String t = modelType.trim().toLowerCase();
        return ENTRIES.stream()
            .filter(e -> e.modelTypes().stream().anyMatch(m -> m.equalsIgnoreCase(t)))
            .toList();
    }

    /** 模型类型是否被本仓认识（用于 400 校验）。 */
    public static boolean isKnownModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return true;
        }
        String t = modelType.trim().toLowerCase();
        for (com.nexusai.model.provider.dto.ModelType v :
            com.nexusai.model.provider.dto.ModelType.values()) {
            if (v.name().equals(t)) {
                return true;
            }
        }
        return false;
    }
}
