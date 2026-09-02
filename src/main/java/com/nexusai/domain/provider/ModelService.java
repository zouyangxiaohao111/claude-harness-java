package com.nexusai.domain.provider;

import com.mybatisflex.core.query.QueryWrapper;
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

    public List<ModelDto> listByProvider(String providerId) {
        if (providerMapper.selectOneById(providerId) == null) {
            throw new NotFoundException("Provider " + providerId + " not found");
        }
        List<ModelRecord> records = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", providerId));
        return records.stream().map(r -> toDto(r.toDomain())).toList();
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
        m.setTag(req.tag().name());
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
        m.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        m.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (log.isDebugEnabled()) {
            log.debug("[ModelService] create 入库模型级窗口: model={} maxContextTokens={} (来源: 请求 {} → 默认 200_000)",
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
        if (req.enabled() != null) m.setEnabled(req.enabled());
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

    private ModelDto toDto(Model m) {
        return new ModelDto(m.getId(), m.getName(), m.getAlias(),
            m.getTag() != null ? ModelTag.valueOf(m.getTag()) : null,
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
            m.getCacheWritePriceOffpeak() != null ? BigDecimal.valueOf(m.getCacheWritePriceOffpeak()) : null);
    }

    private static String generateId(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }
}
