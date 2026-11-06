package com.nexusai.domain.provider;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.model.provider.Model;
import com.nexusai.model.provider.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model 业务逻辑（应用层）：list/get/create/update/delete。
 * 跨 context 调用 {@link ProviderMapper} 校验 provider 存在。
 *
 * <p>DDD 分层：只持有 domain POJO（{@link Model}），mapper 返回的
 * {@link ModelRecord} 通过 {@code toDomain()} / {@code fromDomain()} 互转。
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    @Autowired private ModelMapper modelMapper;
    @Autowired private ProviderMapper providerMapper;

    /**
     * 知识库引用检查（B10 · 用户 2026-11-04 裁定「删/停用被引用的模型要拦」）。
     *
     * <p>⛔ 不能省：停用 embedding / rerank 模型后检索会<b>静默降级</b>（只剩关键词 / 不重排），
     * 日志最多一条 WARN，用户只会觉得「搜索突然变差了」。
     *
     * <p>{@code required = false}：精简装配（单测）下缺失时不做检查，⛔ 不因缺 bean 阻断正常操作。
     */
    @Autowired(required = false)
    private com.nexusai.kb.repo.KbModelUsageRepository kbModelUsage;

    public List<ModelDto> listByProvider(String providerId) {
        if (providerMapper.selectOneById(providerId) == null) {
            throw new NotFoundException("Provider " + providerId + " not found");
        }
        List<ModelRecord> records = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", providerId));
        return records.stream().map(r -> toDto(r.toDomain())).toList();
    }

    /**
     * 全部模型（跨 provider 扁平列表 · 批次 W8 的 {@code GET /models}）。
     *
     * <p><b>为什么单独一个方法而不是前端自己拼</b>：源侧 {@code GET /models} 就是扁平的；
     * 前端「模型总览」页不需要先拉 provider 再逐个拉 models（N+1 次请求）。
     */
    public List<ModelDto> listAll() {
        return modelMapper.selectAll().stream().map(r -> toDto(r.toDomain())).toList();
    }

    public ModelDto getById(String id) {
        ModelRecord r = modelMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Model " + id + " not found");
        return toDto(r.toDomain());
    }

    public ModelDto create(String providerId, ModelCreateRequest req) {
        if (providerMapper.selectOneById(providerId) == null) {
            throw new NotFoundException("Provider " + providerId + " not found");
        }
        Model m = new Model();
        m.setId(generateId("model"));
        m.setProviderId(providerId);
        m.setName(req.name());
        m.setAlias(req.alias());
        m.setTag(ModelTags.normalize(req.tag()));   // [D5] 写路径校验（1-8 位大写字母/数字，非法 400）
        m.setDescription(req.desc() != null ? req.desc() : "");
        m.setType(req.type().name());
        m.setMaxTokens(req.maxTokens() != null ? req.maxTokens() : 384000); // 默认 384K 输出上限（用户拍板）
        m.setTemperature(req.temperature() != null ? req.temperature().doubleValue() : 1.0); // 默认 1.0
        m.setTopP(req.topP() != null ? req.topP().doubleValue() : null);
        // W2-1: 模型级上下文窗口入库（models.max_context_tokens · 此前死列零接线）。
        // 默认 1_048_576 = 1M 上下文（deepseek-v4 系支持 1M · 用户拍板）。
        m.setMaxContextTokens(req.maxContextTokens() != null ? req.maxContextTokens() : 1_048_576);
        // [V-TOK 实施] 价格 8 列入库（models V47）· 请求显式给值透传，null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档）
        m.setInputPricePeak(req.inputPricePeak() != null ? req.inputPricePeak().doubleValue() : null);
        m.setInputPriceOffpeak(req.inputPriceOffpeak() != null ? req.inputPriceOffpeak().doubleValue() : null);
        m.setOutputPricePeak(req.outputPricePeak() != null ? req.outputPricePeak().doubleValue() : null);
        m.setOutputPriceOffpeak(req.outputPriceOffpeak() != null ? req.outputPriceOffpeak().doubleValue() : null);
        m.setCacheReadPricePeak(req.cacheReadPricePeak() != null ? req.cacheReadPricePeak().doubleValue() : null);
        m.setCacheReadPriceOffpeak(req.cacheReadPriceOffpeak() != null ? req.cacheReadPriceOffpeak().doubleValue() : null);
        m.setCacheWritePricePeak(req.cacheWritePricePeak() != null ? req.cacheWritePricePeak().doubleValue() : null);
        m.setCacheWritePriceOffpeak(req.cacheWritePriceOffpeak() != null ? req.cacheWritePriceOffpeak().doubleValue() : null);
        m.setThink(req.think() != null ? req.think() : "");
        // [2026-10-22 extraBody] 模型级额外请求体（JSON 字符串）· 空/未传 → 空串（运行期按空处理）
        m.setExtraBody(req.extraBody() != null ? req.extraBody() : "");
        // [V200] 向量嵌入配置（仅 type=embedding 有意义；null = 用服务端默认）
        m.setEmbeddingDimensions(req.embeddingDimensions());
        m.setEmbeddingBatchSize(req.embeddingBatchSize());
        m.setRerankTopN(req.rerankTopN());
        m.setRerankInstruct(req.rerankInstruct());
        m.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        m.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (log.isDebugEnabled()) {
            log.debug("[ModelService] create 入库模型级窗口: model={} maxContextTokens={} (来源: 请求 {} → 默认 1_048_576 = 1M)",
                m.getName(), m.getMaxContextTokens(), req.maxContextTokens() != null ? "显式" : "缺省");
        }
        modelMapper.insert(ModelRecord.fromDomain(m));
        return toDto(m);
    }

    public ModelDto update(String id, ModelUpdateRequest req) {
        ModelRecord r = modelMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Model " + id + " not found");
        Model m = r.toDomain();
        if (req.name() != null) m.setName(req.name());
        if (req.alias() != null) m.setAlias(req.alias());
        if (req.desc() != null) m.setDescription(req.desc());
        if (req.type() != null) m.setType(req.type().name());
        if (req.maxTokens() != null) m.setMaxTokens(req.maxTokens());
        if (req.temperature() != null) m.setTemperature(req.temperature().doubleValue());
        if (req.topP() != null) m.setTopP(req.topP().doubleValue());
        // W2-1: PATCH 语义——maxContextTokens 非 null 才更新（null = 不改，与既有 null-skip 约定一致）
        if (req.maxContextTokens() != null) m.setMaxContextTokens(req.maxContextTokens());
        // [V-TOK 实施] 价格 8 列 PATCH 语义：null 不改（对齐 maxContextTokens 先例）
        if (req.inputPricePeak() != null) m.setInputPricePeak(req.inputPricePeak().doubleValue());
        if (req.inputPriceOffpeak() != null) m.setInputPriceOffpeak(req.inputPriceOffpeak().doubleValue());
        if (req.outputPricePeak() != null) m.setOutputPricePeak(req.outputPricePeak().doubleValue());
        if (req.outputPriceOffpeak() != null) m.setOutputPriceOffpeak(req.outputPriceOffpeak().doubleValue());
        if (req.cacheReadPricePeak() != null) m.setCacheReadPricePeak(req.cacheReadPricePeak().doubleValue());
        if (req.cacheReadPriceOffpeak() != null) m.setCacheReadPriceOffpeak(req.cacheReadPriceOffpeak().doubleValue());
        if (req.cacheWritePricePeak() != null) m.setCacheWritePricePeak(req.cacheWritePricePeak().doubleValue());
        if (req.cacheWritePriceOffpeak() != null) m.setCacheWritePriceOffpeak(req.cacheWritePriceOffpeak().doubleValue());
        if (req.think() != null) m.setThink(req.think());
        // [2026-10-22 extraBody] PATCH 语义：null = 不改；非 null（含空串清空）才写
        if (req.extraBody() != null) m.setExtraBody(req.extraBody());
        // [V200] PATCH 语义：null 不改
        if (req.embeddingDimensions() != null) m.setEmbeddingDimensions(req.embeddingDimensions());
        if (req.embeddingBatchSize() != null) m.setEmbeddingBatchSize(req.embeddingBatchSize());
        if (req.rerankTopN() != null) m.setRerankTopN(req.rerankTopN());
        if (req.rerankInstruct() != null) m.setRerankInstruct(req.rerankInstruct());
        if (req.enabled() != null) {
            // ⭐ 停用 = 等价于「这个模型不再可用」⇒ 被知识库引用时必须拦
            //    （否则向量路/重排静默降级，用户只觉得「搜不准了」）
            boolean turningOff = Boolean.TRUE.equals(m.getEnabled()) && !req.enabled();
            if (turningOff) {
                assertNotReferencedByKnowledge(id, "停用");
            }
            m.setEnabled(req.enabled());
        }
        if (log.isDebugEnabled()) {
            log.debug("[ModelService] update 模型级窗口: model={} maxContextTokens={} (PATCH 语义: 请求 null 不改)",
                m.getName(), m.getMaxContextTokens());
        }
        modelMapper.update(ModelRecord.fromDomain(m));
        return toDto(m);
    }

    public void delete(String id) {
        ModelRecord r = modelMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Model " + id + " not found");
        modelMapper.deleteById(id);
    }

    /**
     * 复制模型（批次 W8 · 源侧 {@code POST /models/{id}/copy}）。
     *
     * <p><b>源侧注释</b>：「复制模型配置（含已存凭证）」。本仓的凭证在 provider 上，
     * 不在模型上 ⇒ 复制出来的模型与源模型<b>共用同一个 provider 凭证</b>，
     * 这正是用户想要的（同一个厂商下多开一个同名不同参数的模型）。
     *
     * <p><b>⚠️ 名称冲突规则</b>：新名若已存在则 409。
     * 源侧在同一 provider 下允许同名（靠 id 区分），但本仓前端的模型选择器
     * 在部分位置按 <b>name</b> 定位（见 settings 存的是 model name）⇒
     * 同名会导致「选哪个都指向同一个」。宁可在这里拦下。
     *
     * @param id  源模型 id
     * @param newName 新模型名（{@code null} = {@code 原名-copy}）
     * @return 新模型
     */
    public ModelDto copy(String id, String newName) {
        ModelRecord src = modelMapper.selectOneById(id);
        if (src == null) throw new NotFoundException("Model " + id + " not found");

        String name = (newName == null || newName.isBlank())
            ? src.getName() + "-copy"
            : newName.trim();
        ensureNameFree(src.getProviderId(), name, null);

        // 逐字段复制（一字不改），只换 id / name / 时间
        Model m = src.toDomain();
        m.setId(generateId("model"));
        m.setName(name);
        m.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        modelMapper.insert(ModelRecord.fromDomain(m));
        if (log.isInfoEnabled()) {
            log.info("[ModelService] 已复制模型 {} → {} (name={})", id, m.getId(), name);
        }
        return toDto(m);
    }

    /**
     * 创建模型（扁平路径 · 批次 W8 的 {@code POST /models}）。
     *
     * <p>与 {@link #create(String, ModelCreateRequest)} 的差别只有一个：
     * provider 从请求体里来（源侧扁平端点没有路径上的 provider）。
     * ⛔ 不再单独实现一遍建模型逻辑 —— 委托给既有方法，避免两份默认值分叉。
     *
     * @param providerId provider id（必填）
     * @param req        模型字段
     */
    public ModelDto createFlat(String providerId, ModelCreateRequest req) {
        if (providerId == null || providerId.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("provider_id is required");
        }
        ensureNameFree(providerId, req.name(), null);
        return create(providerId, req);
    }

    /**
     * 同一 provider 下模型名不得重复。
     *
     * @param providerId provider id
     * @param name       模型名
     * @param excludeId  排除的模型 id（更新场景传自己）
     * @throws ConflictException 重名
     */
    public void ensureNameFree(String providerId, String name, String excludeId) {
        if (name == null || name.isBlank()) {
            return;  // 名字校验交给 create 的既有逻辑
        }
        List<ModelRecord> same = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", providerId).eq("name", name));
        for (ModelRecord r : same) {
            if (excludeId == null || !excludeId.equals(r.getId())) {
                throw new ConflictException(
                    "该提供商下已存在同名模型：" + name + "（模型名被配置引用，不能重复）");
            }
        }
    }

    /**
     * 被知识库引用时拒绝操作（用户 2026-11-04 裁定「推荐拦」）。
     *
     * @param modelId 模型 ID
     * @param action  动作名（「删除」/「停用」），用于错误文案
     * @throws ConflictException 有引用
     */
    private void assertNotReferencedByKnowledge(String modelId, String action) {
        if (kbModelUsage == null) {
            return;  // 精简装配（单测场景）⇒ 不做检查，⛔ 不因缺 bean 而阻断正常操作
        }
        com.nexusai.kb.repo.KbModelUsageRepository.Usage usage =
            kbModelUsage.countUsage(modelId);
        if (!usage.isUnused()) {
            throw new ConflictException(
                "该模型正被 " + usage.describe() + " 使用，不能" + action
                    + "。请先在知识库中改用其他模型。");
        }
    }

    private ModelDto toDto(Model m) {
        return new ModelDto(m.getId(), m.getName(), m.getAlias(),
            ModelTags.parse(m.getTag()),   // [D5] 读路径安全解析：脏 tag → null 不抛
            m.getDescription(), m.getType() != null ? ModelType.valueOf(m.getType()) : null,
            m.getMaxTokens(),
            m.getTemperature() != null ? BigDecimal.valueOf(m.getTemperature()) : null,
            m.getTopP() != null ? BigDecimal.valueOf(m.getTopP()) : null,
            m.getThink(),
            Boolean.TRUE.equals(m.getEnabled()),
            m.getMaxContextTokens(),
            m.getInputPricePeak() != null ? BigDecimal.valueOf(m.getInputPricePeak()) : null,
            m.getInputPriceOffpeak() != null ? BigDecimal.valueOf(m.getInputPriceOffpeak()) : null,
            m.getOutputPricePeak() != null ? BigDecimal.valueOf(m.getOutputPricePeak()) : null,
            m.getOutputPriceOffpeak() != null ? BigDecimal.valueOf(m.getOutputPriceOffpeak()) : null,
            m.getCacheReadPricePeak() != null ? BigDecimal.valueOf(m.getCacheReadPricePeak()) : null,
            m.getCacheReadPriceOffpeak() != null ? BigDecimal.valueOf(m.getCacheReadPriceOffpeak()) : null,
            m.getCacheWritePricePeak() != null ? BigDecimal.valueOf(m.getCacheWritePricePeak()) : null,
            m.getCacheWritePriceOffpeak() != null ? BigDecimal.valueOf(m.getCacheWritePriceOffpeak()) : null,
            // [2026-10-22 extraBody] 模型级额外请求体（JSON 字符串）原样回显
            m.getExtraBody(),
            // [V200] 向量嵌入 + 重排配置
            m.getEmbeddingDimensions(),
            m.getEmbeddingBatchSize(),
            m.getRerankTopN(),
            m.getRerankInstruct());
    }

    private static String generateId(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }
}
