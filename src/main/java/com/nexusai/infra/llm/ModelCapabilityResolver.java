package com.nexusai.infra.llm;

import com.nexusai.model.provider.dto.ModelType;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 模型能力判定（A2）· modelName → 是否支持图片（multimodal/vision）。
 *
 * <p>方案定稿：图片附件 → 当前模型 {@code type ∈ {multimodal, vision}} 支持 → 直接发
 * image content block；不支持 → 多模态工具路由（读缓存注入）。本类提供该判定的单一入口。
 *
 * <p>判定链路：{@code modelName → ModelNameResolver.resolve → ModelRecord.getType()}，
 * type ∈ {multimodal, vision} → true。<b>查询失败 / 未知 / 未命中 → false（保守）</b>
 * —— 未知模型类型不冒险直接发 image block，回落多模态工具路由。
 *
 * <p>纯静态工具类（同 {@link ModelNameResolver} 风格）：mapper 由调用方持有，Spring 与
 * 静态上下文（{@code LlmAgentLoop.computeBudgetFromGates} 等）均适用。
 *
 * <p>[A2 铁律] 只信 DB type 实值（models.type 列），不做模型名关键字猜测——对齐 CC 行为：
 * 多模态支持由模型元数据决定，非名字匹配。
 */
public final class ModelCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilityResolver.class);

    private ModelCapabilityResolver() {}

    /**
     * 当前模型是否支持图片（A2）· 对齐方案定稿：type=multimodal/vision → true，
     * 查询失败/未知/未命中 → false（保守，走多模态工具路由 A3）。
     *
     * @param modelMapper    模型 mapper（null → false）
     * @param providerMapper 提供商 mapper（null → 走 {@link ModelNameResolver} 按 name 兼容路径）
     * @param modelName      模型全名（providerName/modelName）或裸模型名（null/blank → false）
     * @return type ∈ {multimodal, vision} → true；查询失败/未知/未命中 → false
     */
    public static boolean supportsImage(ModelMapper modelMapper, ProviderMapper providerMapper, String modelName) {
        if (modelMapper == null || modelName == null || modelName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelCapabilityResolver] 图片能力判定: 入参不可用(modelMapper={} modelName={}) → false（保守，走多模态工具路由）",
                    modelMapper != null, modelName);
            }
            return false;
        }
        try {
            ModelRecord model = ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
            if (model == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelCapabilityResolver] 图片能力判定: modelName={} 未命中 enabled model → false（保守）", modelName);
                }
                return false;
            }
            String rawType = model.getType();
            if (rawType == null || rawType.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelCapabilityResolver] 图片能力判定: modelName={} type 为空 → false（保守）", modelName);
                }
                return false;
            }
            String type = rawType.trim().toLowerCase(Locale.ROOT);
            // 只信 DB type 实值，不做名字关键字猜测；ModelType 枚举值为小写常量（ModelType.java:7-8）
            boolean supported = ModelType.vision.name().equals(type) || ModelType.multimodal.name().equals(type);
            if (log.isDebugEnabled()) {
                log.debug("[ModelCapabilityResolver] 图片能力判定: modelName={} type={} supportsImage={}（A2，ModelType.java:7-8）",
                    modelName, rawType, supported);
            }
            return supported;
        } catch (Exception e) {
            log.warn("[ModelCapabilityResolver] 图片能力判定异常, 保守回落 false: modelName={} err={}", modelName, e.toString());
            return false;
        }
    }
}
