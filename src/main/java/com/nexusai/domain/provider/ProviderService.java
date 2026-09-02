package com.nexusai.domain.provider;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.provider.dto.ModelType;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.model.provider.Model;
import com.nexusai.model.provider.Provider;
import com.nexusai.model.provider.dto.*;
import com.nexusai.infra.util.ApiKeyHasher;
import com.nexusai.infra.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

/**
 * Provider 业务逻辑（应用层）：CRUD + test connection + decrypt key (供 LlmProvider)。
 *
 * <p>DDD 分层：只持有 domain POJO（{@link Provider} / {@link Model}），
 * 内部通过 {@link ProviderRecord#toDomain()} / {@link ProviderRecord#fromDomain(Provider)}
 * 与持久化层互转。
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    @Autowired private ProviderMapper providerMapper;
    @Autowired private ModelMapper modelMapper;
    @Autowired private CryptoUtil cryptoUtil;
    @Autowired private SettingsMapper settingsMapper;
    @Autowired private SessionMapper sessionMapper;

    private static final int SETTINGS_SINGLETON_ID = 1;

    public List<ProviderDto> listAll() {
        List<ProviderRecord> records = providerMapper.selectAll();
        if (records.isEmpty()) return List.of();
        List<ModelRecord> allModelRecords = modelMapper.selectAll();
        Map<String, List<ModelDto>> modelsByProvider = new HashMap<>();
        for (ModelRecord r : allModelRecords) {
            modelsByProvider.computeIfAbsent(r.getProviderId(), k -> new ArrayList<>())
                .add(toModelDto(r.toDomain()));
        }
        List<ProviderDto> result = new ArrayList<>(records.size());
        for (ProviderRecord r : records) {
            Provider p = r.toDomain();
            result.add(toProviderDto(p, modelsByProvider.getOrDefault(p.getId(), List.of())));
        }
        return result;
    }

    public ProviderDto getById(String id) {
        ProviderRecord r = providerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Provider " + id + " not found");
        Provider p = r.toDomain();
        List<ModelRecord> modelRecords = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", id));
        List<ModelDto> modelDtos = modelRecords.stream()
            .map(rec -> toModelDto(rec.toDomain()))
            .toList();
        return toProviderDto(p, modelDtos);
    }

    public ProviderDto create(ProviderCreateRequest req) {
        validateProviderName(req.name());
        ProviderRecord existing = providerMapper.selectOneByQuery(
            QueryWrapper.create().eq("name", req.name()));
        if (existing != null) {
            throw new ConflictException("Provider name '" + req.name() + "' already exists");
        }
        Provider p = new Provider();
        p.setId(generateId("prov"));
        p.setName(req.name());
        p.setType(req.type() != null ? req.type().name() : "openai_compatible");
        p.setBaseUrl(req.baseUrl());
        p.setApiKeyHash(ApiKeyHasher.hash(req.apiKey()));
        p.setApiKeyMasked(ApiKeyHasher.mask(req.apiKey()));
        p.setApiKeyEncrypted(cryptoUtil.encrypt(req.apiKey()));
        p.setExtraHeaders(serializeHeaders(req.extraHeaders()));
        p.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));
        return toProviderDto(p, List.of());
    }

    public ProviderDto update(String id, ProviderUpdateRequest req) {
        ProviderRecord r = providerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Provider " + id + " not found");
        String oldName = r.getName();
        if (req.name() != null) validateProviderName(req.name());
        Provider p = r.toDomain();
        if (req.name() != null) p.setName(req.name());
        if (req.type() != null) p.setType(req.type().name());
        if (req.baseUrl() != null) p.setBaseUrl(req.baseUrl());
        if (req.apiKey() != null) {
            p.setApiKeyHash(ApiKeyHasher.hash(req.apiKey()));
            p.setApiKeyMasked(ApiKeyHasher.mask(req.apiKey()));
            p.setApiKeyEncrypted(cryptoUtil.encrypt(req.apiKey()));
        }
        if (req.extraHeaders() != null) p.setExtraHeaders(serializeHeaders(req.extraHeaders()));
        if (req.enabled() != null) p.setEnabled(req.enabled());
        p.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        providerMapper.update(ProviderRecord.fromDomain(p));
        // 改名联动（领域一致性）：settings.*_model_name / sessions.model_name 中以 {oldName}/
        // 开头的全名引用同步迁移为 {newName}/，避免改名后 ModelNameResolver 查无此 provider
        if (req.name() != null && !req.name().isBlank() && !req.name().equals(oldName)) {
            migrateModelNamePrefix(oldName, req.name());
        }
        return getById(id);
    }

    /**
     * Provider 改名联动 · 迁移所有以 {oldName}/ 开头的模型全名引用为 {newName}/。
     *
     * <p>模型全名 = {providerName}/{modelName}（{@link com.nexusai.infra.llm.ModelNameResolver#parseFullName}），
     * provider.name 是可变前缀。改名前引用的全名若不联动，改名后 ModelNameResolver 将查无此 provider
     * → 模型解析失败（领域修改不一致）。仅替换 {oldName}/ 前缀（避免误伤模型名自身含 oldName 的情况）；
     * null/blank 原样保留。settings 恒单例（id=1）。
     */
    private void migrateModelNamePrefix(String oldName, String newName) {
        String oldPrefix = oldName + "/";
        String newPrefix = newName + "/";
        int settingsChanged = 0;
        int sessionChanged = 0;

        // 1) settings 单例：10 个 *_model_name 字段前缀迁移（各字段独立判断，简单直接）
        SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
        if (s != null) {
            String v;
            v = s.getMainModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setMainModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getFastModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setFastModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getWeakModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setWeakModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getMediumModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setMediumModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getStrongModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setStrongModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getSubagentModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setSubagentModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getFallbackModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setFallbackModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getMultimodalModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setMultimodalModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getTtsModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setTtsModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            v = s.getAsrModelName(); if (v != null && v.startsWith(oldPrefix)) { s.setAsrModelName(newPrefix + v.substring(oldPrefix.length())); settingsChanged++; }
            if (settingsChanged > 0) settingsMapper.update(s);
        }

        // 2) sessions：model_name 前缀迁移（likeLeft 生成 model_name LIKE 'oldPrefix%'）
        List<SessionRecord> sessions = sessionMapper.selectListByQuery(
            QueryWrapper.create().likeLeft("model_name", oldPrefix));
        for (SessionRecord sess : sessions) {
            String mn = sess.getModelName();
            if (mn != null && mn.startsWith(oldPrefix)) {
                sess.setModelName(newPrefix + mn.substring(oldPrefix.length()));
                sessionMapper.update(sess);
                sessionChanged++;
            }
        }

        log.info("Provider 改名联动完成: {} → {} · settings 迁移 {} 字段, sessions 迁移 {} 条",
            oldName, newName, settingsChanged, sessionChanged);
    }

    public void delete(String id) {
        ProviderRecord r = providerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Provider " + id + " not found");
        modelMapper.deleteByQuery(QueryWrapper.create().eq("provider_id", id));
        providerMapper.deleteById(id);
    }

    public TestConnectionResponse test(String id) {
        ProviderRecord r = providerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Provider " + id + " not found");
        Provider p = r.toDomain();
        String apiKey;
        try {
            apiKey = cryptoUtil.decrypt(p.getApiKeyEncrypted());
        } catch (Exception e) {
            log.warn("Provider {} key decryption failed: {}", id, e.getMessage());
            return new TestConnectionResponse(false, null, "无法解密 API key: " + e.getMessage(), null);
        }
        String url = (p.getBaseUrl() == null ? "" : p.getBaseUrl()).trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String testUrl = url + "/models";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
            int sc = resp.statusCode();
            if (sc == 200 || (sc >= 200 && sc < 300)) {
                return new TestConnectionResponse(true, latencyMs, "已连接 (latency " + latencyMs + "ms)",
                    Map.of("status", sc));
            }
            if (sc == 401 || sc == 403) return new TestConnectionResponse(false, latencyMs, "鉴权失败：key 无效", Map.of("status", sc));
            if (sc == 404) return new TestConnectionResponse(false, latencyMs, "baseUrl 不正确（404）", Map.of("status", sc));
            return new TestConnectionResponse(false, latencyMs, "HTTP " + sc, Map.of("status", sc));
        } catch (java.net.ConnectException ce) {
            return new TestConnectionResponse(false, null, "无法连接 " + testUrl + ": " + ce.getMessage(), null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
            log.warn("Provider {} test failed: {}", id, e.toString());
            return new TestConnectionResponse(false, latencyMs, "Unknown error: " + e.getMessage(), null);
        }
    }

    public String getDecryptedApiKey(String providerId) {
        if (providerId == null) return null;
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) return null;
        String enc = r.getApiKeyEncrypted();
        if (enc == null || enc.isBlank()) return null;
        try { return cryptoUtil.decrypt(enc); }
        catch (Exception e) { log.warn("getDecryptedApiKey failed for provider {}: {}", providerId, e.getMessage()); return null; }
    }

    /**
     * W1-2: provider name 校验 — 不允许包含 '/'。
     *
     * <p>模型全名 = {providerName}/{modelName}（取第一个 / 拆分，见
     * {@link com.nexusai.infra.llm.ModelNameResolver#parseFullName}）。若 provider name 本身
     * 含 /，全名拆分将歧义，故创建/更新时拒绝。null/blank 放行（非本校验职责，创建时由
     * 上游 @Valid / 调用方处理）。
     *
     * @param name provider name（可 null）
     * @throws ValidationException name 含 '/' 时抛出（GlobalExceptionHandler → 400）
     */
    private static void validateProviderName(String name) {
        if (name != null && name.contains("/")) {
            throw new ValidationException(
                "Provider name 不允许包含 '/'（模型全名 {providerName}/{modelName} 以 '/' 分隔，provider name 含 '/' 会导致全名拆分歧义）");
        }
    }

    private ProviderDto toProviderDto(Provider p, List<ModelDto> models) {
        return new ProviderDto(p.getId(), p.getName(),
            p.getType() != null ? ProviderType.valueOf(p.getType()) : null,
            p.getBaseUrl(), p.getApiKeyMasked(),
            deserializeHeaders(p.getExtraHeaders()),
            Boolean.TRUE.equals(p.getEnabled()), models,
            parseDateTime(p.getCreatedAt()), parseDateTime(p.getUpdatedAt()));
    }

    private ModelDto toModelDto(Model m) {
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
    private static String serializeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey().replace("\"", "\\\"")).append("\":\"")
              .append(e.getValue().replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }
    private static Map<String, String> deserializeHeaders(String json) {
        if (json == null || json.isBlank()) return null;
        Map<String, String> result = new HashMap<>();
        String s = json.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1);
        if (s.isBlank()) return result;
        for (String pair : s.split(",")) {
            int idx = pair.indexOf(':');
            if (idx < 0) continue;
            String key = pair.substring(0, idx).trim().replaceAll("^\"|\"$", "");
            String val = pair.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
            if (!key.isEmpty()) result.put(key, val);
        }
        return result;
    }
    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s); } catch (Exception e) { return null; }
    }
}
