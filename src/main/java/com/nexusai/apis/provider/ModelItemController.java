package com.nexusai.apis.provider;

import com.nexusai.domain.provider.ModelProviderCatalog;
import com.nexusai.domain.provider.ModelService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.provider.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Model 端点（{@code /api/v1/models}）· 批次 W8 补齐源侧扁平路径。
 *
 * <p><b>⭐ 两套路径并存（用户 2026-11-05 裁决 A「加扁平路径，保留嵌套」）</b>：
 * <ul>
 *   <li><b>嵌套</b> {@code /providers/{pid}/models} —— 前端 8 处仍在用，⛔ 不动。</li>
 *   <li><b>扁平</b> {@code /models} —— 源侧契约（{@code routes_infra.go:19-48}），本类补齐。</li>
 * </ul>
 * 两者<b>操作同一份数据</b>（{@code models} 表）⇒ 状态天然同步，不存在「两个入口两份状态」。
 *
 * <p><b>⚠️ 路由顺序</b>：{@code /providers} 与 {@code /catalog/resolve} 是字面量段，
 * {@code /{id}} 是变量段。Spring 会优先匹配更具体的，这里仍把字面量声明在前。
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelItemController {

    @Autowired private ModelService modelService;

    // ── 字面量路径（必须先于 /{id}）────────────────────────

    /**
     * 模型厂商目录（平台支持的种类 · <b>不是</b> {@code providers} 表）。
     *
     * @param modelType 只保留支持该模型类型的厂商（可空）
     */
    @GetMapping("/providers")
    public Map<String, Object> listProviders(
        @RequestParam(value = "model_type", required = false) String modelType
    ) {
        if (!ModelProviderCatalog.isKnownModelType(modelType)) {
            throw new ValidationException("unknown model_type: " + modelType);
        }
        return Map.of("success", true, "data", ModelProviderCatalog.byModelType(modelType));
    }

    /**
     * 单个模型厂商。
     *
     * @param provider  厂商标识
     * @param keyword   按厂商名过滤（本仓目录很小，关键字只做 id/name 包含匹配）
     */
    @GetMapping("/providers/{provider}")
    public Map<String, Object> getProvider(
        @PathVariable String provider,
        @RequestParam(value = "model_type", required = false) String modelType,
        @RequestParam(value = "keyword", required = false) String keyword
    ) {
        ModelProviderCatalogDto found = ModelProviderCatalog.get(provider);
        if (found == null) {
            throw new NotFoundException("model provider " + provider + " not found");
        }
        if (modelType != null && !modelType.isBlank()
            && found.modelTypes().stream().noneMatch(t -> t.equalsIgnoreCase(modelType.trim()))) {
            // 源侧同款：厂商存在但不提供该类型 ⇒ 400（不是 404，语义是「参数不适用」）
            throw new ValidationException("provider does not offer this model_type");
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim().toLowerCase();
            if (!found.id().toLowerCase().contains(k) && !found.name().toLowerCase().contains(k)) {
                throw new NotFoundException("model provider " + provider + " not found");
            }
        }
        return Map.of("success", true, "data", found);
    }

    /**
     * 解析模型的有效接入配置（源侧 {@code ResolveModelCatalog}）。
     *
     * <p><b>⚠️ 本仓是「如实的不支持」而不是假实现</b>：
     * 源侧这个端点靠一套厂商目录 + 模型名匹配规则，推断出「协议 / 思考等级 / 上下文窗口」，
     * 供模型编辑器实时预览。本仓没有那套推断机制（模型的这些属性是用户手填的）
     * ⇒ 返回 {@code resolved=false} + 原因。
     *
     * <p>⛔ 不要返回一堆编出来的默认值 —— 那会让用户以为「系统已经帮我推断好了」。
     */
    @GetMapping("/catalog/resolve")
    public Map<String, Object> resolveCatalog(
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "model", required = false) String model,
        @RequestParam(value = "base_url", required = false) String baseUrl
    ) {
        ModelProviderCatalogDto found = ModelProviderCatalog.get(provider);
        if (provider != null && !provider.isBlank() && found == null) {
            throw new NotFoundException("model provider " + provider + " not found");
        }
        return Map.of(
            "success", true,
            "data", Map.of(
                "resolved", false,
                "provider", provider == null ? "" : provider,
                "model", model == null ? "" : model,
                "base_url", baseUrl == null ? "" : baseUrl,
                "message",
                "本仓不提供模型目录推断：模型协议/思考等级/上下文窗口由你在模型配置里直接指定，"
                    + "系统不会按模型名自动推断。"));
    }

    // ── 集合 ───────────────────────────────────────────────

    /** 全部模型（跨 provider 扁平列表）。 */
    @GetMapping
    public List<ModelDto> list() {
        return modelService.listAll();
    }

    /**
     * 创建模型（扁平）。provider 从请求体取。
     *
     * <p>⚠️ 请求体是 {@link ModelCreateRequest} 的扩展形状：多了 {@code provider_id}。
     * 用一个小的 record 包一层，⛔ 不改 {@link ModelCreateRequest}（它有既有调用点）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto create(@RequestBody FlatModelCreateRequest req) {
        return modelService.createFlat(req.providerId(), req.toStandard());
    }

    // ── 变量路径 ───────────────────────────────────────────

    @GetMapping("/{id}")
    public ModelDto get(@PathVariable String id) { return modelService.getById(id); }

    @PatchMapping("/{id}")
    public ModelDto update(@PathVariable String id, @RequestBody ModelUpdateRequest req) {
        return modelService.update(id, req);
    }

    /** 复制模型（源侧 {@code POST /models/{id}/copy}）。 */
    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto copy(@PathVariable String id, @RequestBody(required = false) CopyModelRequest req) {
        return modelService.copy(id, req == null ? null : req.name());
    }

    /** 全量更新（源侧 {@code PUT /models/{id}}）。 */
    @PutMapping("/{id}")
    public ModelDto replace(@PathVariable String id, @RequestBody ModelUpdateRequest req) {
        // ⚠️ 与 PATCH 的区别：源侧 PATCH 只改显示信息（alias/description），PUT 是全量。
        //    本仓的 ModelUpdateRequest 本身就是「null = 不改」的补丁语义 ⇒
        //    PUT 走同一条路径，但先明确拒绝「只传显示字段」以外的误用没有意义
        //    （两者字段集相同，强行区分只会制造两份行为）。
        return modelService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { modelService.delete(id); }

    /**
     * 扁平创建请求：{@link ModelCreateRequest} + {@code provider_id}。
     *
     * <p>⚠️ 用组合而不是给 {@link ModelCreateRequest} 加字段：
     * 那个 record 有 23 个字段，加一个会让所有既有构造点（测试）全部改一次，
     * 而它们本来就不需要 provider_id（嵌套路径已有）。
     */
    public record FlatModelCreateRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("provider_id") String providerId,
        String name,
        String alias,
        String tag,
        String desc,
        ModelType type,
        Integer maxTokens,
        java.math.BigDecimal temperature,
        java.math.BigDecimal topP,
        String think,
        Boolean enabled,
        Integer maxContextTokens,
        java.math.BigDecimal inputPricePeak,
        java.math.BigDecimal inputPriceOffpeak,
        java.math.BigDecimal outputPricePeak,
        java.math.BigDecimal outputPriceOffpeak,
        java.math.BigDecimal cacheReadPricePeak,
        java.math.BigDecimal cacheReadPriceOffpeak,
        java.math.BigDecimal cacheWritePricePeak,
        java.math.BigDecimal cacheWritePriceOffpeak,
        String extraBody,
        Integer embeddingDimensions,
        Integer embeddingBatchSize,
        Integer rerankTopN,
        String rerankInstruct
    ) {
        ModelCreateRequest toStandard() {
            return new ModelCreateRequest(
                name, alias, tag, desc, type, maxTokens, temperature, topP, think, enabled,
                maxContextTokens,
                inputPricePeak, inputPriceOffpeak, outputPricePeak, outputPriceOffpeak,
                cacheReadPricePeak, cacheReadPriceOffpeak, cacheWritePricePeak, cacheWritePriceOffpeak,
                extraBody, embeddingDimensions, embeddingBatchSize, rerankTopN, rerankInstruct);
        }
    }

    /** 复制请求体（源侧可带新名字）。 */
    public record CopyModelRequest(String name) {
    }
}
