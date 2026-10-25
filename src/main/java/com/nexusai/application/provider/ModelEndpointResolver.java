package com.nexusai.application.provider;

import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模型 → provider 运行时配置解析 · 向量 / 重排等<b>非对话</b>能力的共用入口。
 *
 * <p><b>WHY 单独成类</b>：embedding 与 rerank 的解析链完全一致（模型行 → 类型门 →
 * provider 行 → enabled → 解密 key → {@link ProviderConfig}）。如果两个 Service 各写一遍，
 * 「类型门文案 / 禁用判定 / key 缺失判定」迟早分叉（本仓有过同类前科）。这里做单点。
 *
 * <p><b>类型门</b>：每种能力只接受自己的 {@code models.type}；⛔ 绝不拿 chat 模型去调
 * embeddings/rerank（服务端未必报错，可能返回语义上无意义的结果）。
 */
@Component
public class ModelEndpointResolver {

    private final ModelMapper modelMapper;
    private final ProviderMapper providerMapper;
    private final ProviderService providerService;

    @Autowired
    public ModelEndpointResolver(ModelMapper modelMapper, ProviderMapper providerMapper,
                                 ProviderService providerService) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.providerService = providerService;
    }

    /**
     * 解析结果。
     *
     * @param model    模型行
     * @param provider 提供商行
     * @param config   运行时配置（baseUrl + 明文 key + 自定义头）
     */
    public record Resolved(ModelRecord model, ProviderRecord provider, ProviderConfig config) {
    }

    /**
     * 解析模型与 provider。失败一律 fail loud（NotFoundException / ValidationException）。
     *
     * @param modelId      模型行 id
     * @param requiredType 要求的 {@code models.type}（embedding / rerank）
     * @return 解析结果
     */
    public Resolved resolve(String modelId, String requiredType) {
        ModelRecord model = modelMapper.selectOneById(modelId);
        if (model == null) {
            throw new NotFoundException("Model " + modelId + " not found");
        }
        assertType(model, requiredType);

        ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
        if (provider == null) {
            throw new NotFoundException("Provider " + model.getProviderId() + " not found");
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ValidationException("Provider " + provider.getId() + " 已禁用，无法调用 " + requiredType);
        }
        String apiKey = providerService.getDecryptedApiKey(provider.getId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new ValidationException("Provider " + provider.getId() + " 未配置 API key，无法调用 " + requiredType);
        }
        ProviderConfig config = new ProviderConfig(provider.getBaseUrl(), apiKey,
            ProviderService.deserializeHeaders(provider.getExtraHeaders()));
        return new Resolved(model, provider, config);
    }

    /**
     * 类型门（纯函数，便于单测）。
     *
     * @param model        模型行
     * @param requiredType 要求的类型
     */
    public static void assertType(ModelRecord model, String requiredType) {
        String type = model.getType();
        if (!requiredType.equals(type)) {
            throw new ValidationException("模型 " + model.getName() + " 的类型为 "
                + (type == null || type.isBlank() ? "未设置" : type)
                + "，不能用于" + capabilityLabel(requiredType)
                + "（需要 " + requiredType + " 类型）");
        }
    }

    private static String capabilityLabel(String type) {
        return switch (type) {
            case "embedding" -> "向量化";
            case "rerank" -> "重排序";
            default -> type;
        };
    }
}
