package com.nexusai.application.provider;

import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ModelEndpointResolver.class);

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
     * <p>⭐ 2026-11-11 用户裁定：知识库配置里的模型字段要存 {@code provider/modelName} 全名
     * （与 {@code settings} 表一致，且能区分同名模型 —— 实测真库有 3 份
     * {@code deepseek-v4.1-flash} 分属 oc / oc_new / oc2）。
     * 本方法因此接受**三种形态**，按序尝试：
     * <ol>
     *   <li>模型 ID（{@code model-xxxxxxxx}）—— 存量数据都是这个形态，必须继续支持；</li>
     *   <li>全名 {@code provider/modelName} —— 新写入的形态，走
     *       {@link com.nexusai.infra.llm.ModelNameResolver#resolve} 精确匹配 provider，
     *       ⛔ 不做「裸名取第一条」的模糊兜底（同名时那会静默串到别的提供商）；</li>
     *   <li>裸模型名 —— 历史兼容（{@code ModelConfigResolver} 同款语义）。</li>
     * </ol>
     *
     * @param modelIdOrName 模型 ID、{@code provider/name} 全名，或裸模型名
     * @param requiredType  要求的 {@code models.type}（embedding / rerank）
     * @return 解析结果
     */
    public Resolved resolve(String modelIdOrName, String requiredType) {
        ModelRecord model = findModel(modelIdOrName);
        if (model == null) {
            throw new NotFoundException("Model " + modelIdOrName + " not found"
                + "（支持 models.id / provider/modelName / 模型名 三种形态）");
        }
        return resolveWithModel(model, requiredType);
    }

    /**
     * 按 ID → 全名 → 裸名 的顺序找模型行。
     *
     * <p>⚠️ 顺序不可颠倒：ID 形态必须**先**判，否则 {@code model-0823a98d} 会被当成
     * 裸模型名去按 name 查（查不到，浪费一次查询；更糟的是若真有同名模型会错绑）。
     */
    private ModelRecord findModel(String modelIdOrName) {
        if (modelIdOrName == null || modelIdOrName.isBlank()) {
            return null;
        }
        String key = modelIdOrName.trim();
        // ① 存量形态：模型 ID
        ModelRecord byId = modelMapper.selectOneById(key);
        if (byId != null) {
            return byId;
        }
        // ② 新形态：provider/modelName 全名；③ 历史兼容：裸模型名
        //    两者都由 ModelNameResolver 统一处理（它内部先拆全名、再走裸名兼容路径）。
        try {
            return ModelNameResolver.resolve(modelMapper, providerMapper, key);
        } catch (RuntimeException e) {
            log.warn("[ModelEndpointResolver] 按名解析失败 model={} 原因={}", key, e.getMessage());
            return null;
        }
    }

    /** 拿到模型行之后的解析（类型门 → provider → key → config）。 */
    private Resolved resolveWithModel(ModelRecord model, String requiredType) {
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
