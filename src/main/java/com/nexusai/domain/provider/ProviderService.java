package com.nexusai.domain.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.DynamicHeaderExpander;
import com.nexusai.infra.llm.ProviderHeaderInjector;
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
        // 写侧校验必须在序列化之前：敏感头 / 非法名 / 换行值 / 占位符拼写错误 → 400（规范 §6.4）
        validateExtraHeaders(req.extraHeaders());
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
        // 写侧校验必须在序列化之前（同 create）：命中敏感头 / 非法名 / 换行值 / 占位符拼写错误 → 400
        validateExtraHeaders(req.extraHeaders());
        // [provider-custom-headers 清空契约] extra_headers 的三态（前端 KvEditor 全空产出 {}）：
        //   缺字段 / JSON null → 不触碰（PATCH null-skip，现状语义不变）
        //   显式 {}            → 清空（写 SQL NULL）
        //   非空 map           → 设为该值
        // WHY 必须显式写 NULL：{@link #serializeHeaders} 对空 map 返回 null（其 javadoc 明言「这与存 {}
        //   不可互换」），而 MyBatis-Flex 的 update(entity) 默认 $$ignoreNulls=true 会跳过 null 字段 ——
        //   不显式写 NULL 就永远清不掉该列，「用户删光所有 header」会变成静默 no-op。
        // 安全性：p 由 r.toDomain() 而来（DB 全字段回读），且 ProviderRecord 的 11 个字段在
        //   toDomain/fromDomain 上逐一对称，故 ignoreNulls=false 只会把「DB 里本就是 NULL」的列再写一次
        //   NULL，不会误伤其他列。
        // 范式照抄本仓既有（不新造）：ProjectSessionBindingService.unbind / EffortCommand、
        //   TodoWriteTool 均用 update(entity, false) 显式写 NULL。
        boolean clearHeaders = req.extraHeaders() != null && req.extraHeaders().isEmpty();
        if (req.extraHeaders() != null) {
            p.setExtraHeaders(clearHeaders ? null : serializeHeaders(req.extraHeaders()));
        }
        if (req.enabled() != null) p.setEnabled(req.enabled());
        p.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        ProviderRecord rec = ProviderRecord.fromDomain(p);
        if (clearHeaders) {
            if (log.isDebugEnabled()) {
                log.debug("[ProviderService] provider {} 的 extra_headers 显式清空（请求体传空对象）→ 写 SQL NULL", id);
            }
            providerMapper.update(rec, false);   // 显式写 NULL（update(entity) 默认忽略 null 字段）
        } else {
            providerMapper.update(rec);
        }
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
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");

            // [provider-custom-headers 任务 8 · 修 B3] 该 provider 的自定义 header 也要上到「测试连接」。
            //
            // 此处**复用注入侧单点** ProviderHeaderInjector.apply，不另写一套展开/过滤逻辑（R7 前科：
            // 同一能力两套判据）。apply 内部做的两件事恰好都是这里需要的：
            //   ① DynamicHeaderExpander.expandAll(..., gateEnabled()) —— 占位符展开 + 读 settings 开关；
            //   ② 禁止清单防御性过滤（存量脏数据可能含凭据头/受限头）。
            // 也正是靠 ① 的封装，本类**无需触碰** gateEnabled()——它是 infra 包的 package-private
            // （ProviderHeaderInjector.java:73），而本类在 domain 包，跨包取不到；由 apply 内部读即可。
            //
            // ⚠️ 第三参 sessionId **恒传 null，不要改成读 RequestContext.sessionId()**（规范 §6.7 / T3 实测）：
            //   · ProviderController.test(:38) 的签名是 test(@PathVariable String id)，**无 sessionId 入参**；
            //   · 而 RequestContext.sessionId() 读的是裸 MDC，本仓没有 Filter 写 MDC，该线程上它可能是
            //     **上一个请求残留的、别的会话的 sessionId**（MemoryController:143 / TaskController:145 /
            //     TeamController:95 三处 setSession 均无 clear，Tomcat 线程复用下真实存在）。
            //   · 读它会把 **A 会话的亲和 id 发给 B 会话的测试连接请求**；而本方法打的是 {baseUrl}/models 的
            //     **一次性 GET**，亲和性对它本就毫无意义。
            //   → 落 STATIC_FALLBACK（nexusai-static）零损失、零风险。
            ProviderHeaderInjector.apply(rb::header, deserializeHeaders(p.getExtraHeaders()), null);

            // 内置 Authorization 放在 apply **之后**，且用 setHeader。
            //
            // ⚠️ 为什么必须是 setHeader 而不是 header（2026-09-13 JDK 25.0.3 桩实测）：
            //   · `header(name,value)` 是**追加**语义：同名调用两次 → 线上实际发出 [FIRST, SECOND]，
            //     两个值都在，**不构成覆盖**；
            //   · 只有 `setHeader(name,value)` 才是**替换**语义（实测 header();setHeader() → [SECOND]）。
            //   所以「先加用户 header、再设内置」这个**顺序本身**并不能让内置的赢 —— 换个语义才有用。
            //
            // 今天本行**不改变任何可观测行为**：正常路径下用户配不进 authorization（写侧
            // validateExtraHeaders 按 D5 拒绝 → 400），存量脏值也在上一步 apply 内部被禁止清单
            // 跳过（并 warn），故冲突根本到不了这一行。
            //
            // 它是**对未来的防御**：一旦禁止清单放宽到不再含 authorization（例如有人把它移出
            // FORBIDDEN_HEADER_NAMES），只有 setHeader 能让内置凭据真正覆盖用户配置的；用 header()
            // 则会让脏值与真凭据并列上线（服务器取哪个不确定 → 静默的凭据劫持）。
            //
            // 守护方式：本行的效果**无法用行为测试证伪**（唯一能触发的场景被禁止清单拦住了），
            // 故改用**源码级断言**钉住，见 ProviderTestConnectionHeadersTest#builtinAuthorizationMustUseSetHeader。
            rb.setHeader("Authorization", "Bearer " + apiKey);

            HttpRequest req = rb.GET().build();
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

    /**
     * 校验用户配置的自定义请求 header（写侧，create / update 共用）。
     *
     * <p>拒绝四类（规范 §6.4 / 用户决策 D5）：
     * <ol>
     *   <li>禁止清单内的头（凭据类 + 报文完整性类）—— 判据在 {@link DynamicHeaderExpander}；
     *       T2 桩实测证明 {@code putHeader} 能顶掉 SDK 依 {@code .apiKey()} 注入的凭据头，
     *       故<b>这份清单是唯一防线</b>，写侧必须先拦。</li>
     *   <li>null 名 / null 值（规则十二 fail loud：不能落到「序列化静默存 null 值」那一层）</li>
     *   <li>非法 header 名（非 RFC 7230 token）</li>
     *   <li>值含 CR/LF（防 header 注入）；以及疑似占位符拼写错误（{@code ${SESSION_ID}} 之类
     *       会被原样当字面量发出去 → 静默失效，宁可 fail loud）</li>
     * </ol>
     * 校验判据与注入侧<b>共用同一份</b> {@link DynamicHeaderExpander}，不另起一套。
     *
     * <p><b>校验顺序是有意的</b>：{@code isForbiddenHeaderName} 是 {@code isValidHeaderName}
     * 的<b>子集</b>，必须先判前者，否则敏感头会被报成笼统的「非法名」，用户拿不到「禁止自定义」
     * 这个精确原因。
     *
     * <p><b>异常类型</b>：抛 {@link ValidationException} 而非 {@code IllegalArgumentException}——
     * 本仓 {@code IllegalArgumentException} 只被 {@code GlobalExceptionHandler} 的兜底
     * {@code @ExceptionHandler(Exception.class)} 接住 → <b>HTTP 500</b>；而本类既有的写侧校验
     * （{@link #validateProviderName}）走的是 {@code ValidationException} → 400（见其 javadoc
     * 「GlobalExceptionHandler → 400」）。规范 §7 要求「写侧 400」，故照既有做法办，不新增异常处理范式。
     *
     * @param headers 请求体里的 extraHeaders（可 null / 空 → 放行）
     * @throws ValidationException 命中任一条；消息指明具体是哪个 header、为什么
     */
    static void validateExtraHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (DynamicHeaderExpander.isForbiddenHeaderName(name)) {
                throw new ValidationException(
                    "header [" + name + "] 不允许自定义：该头由系统管理或属于报文完整性头，覆盖会破坏请求");
            }
            if (!DynamicHeaderExpander.isValidHeaderName(name)) {
                throw new ValidationException(
                    "header 名 [" + name + "] 非法：必须符合 HTTP token 规则（字母/数字/!#$%&'*+-.^_`|~），且不得为 null");
            }
            if (!DynamicHeaderExpander.isValidHeaderValue(value)) {
                throw new ValidationException(
                    "header [" + name + "] 的值非法：不得包含换行符，且只能是可见 ASCII 字符，且不得为 null");
            }
            if (DynamicHeaderExpander.hasMalformedPlaceholder(value)) {
                throw new ValidationException(
                    "header [" + name + "] 的值里有疑似占位符拼写错误：[ " + value + " ]。"
                    + "占位符区分大小写，正确写法是 " + DynamicHeaderExpander.SESSION_ID_TOKEN);
            }
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
    private static final ObjectMapper HEADERS_JSON = new ObjectMapper();

    // 可见性说明：这两个方法从 private static 提为 package-private static（任务 3，**仅为测试可见性**：
    // ProviderHeaderSerializeTest 需直接调用做 JSON 往返）；任务 6 再提为 public static —— 供
    // **运行时链路**解析 Provider.extraHeaders（JSON 字符串 → Map）用：Anthropic 侧 2 个构造点
    // （ModelConfigResolver / ChatService）持 Provider 实体，须把 JSON 列转成
    // ProviderConfig.extraHeaders 的 Map 形态。不改变任何调用方与运行时语义。
    /** 序列化为 JSON。失败 → warn + null（fail-loud 不 fail-crash）。 */
    public static String serializeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        // key / value 为 null 的条目：跳过该条 + warn（与注入侧 DynamicHeaderExpander.expandAll 同一语义）。
        // WHY：Jackson 会把 null value 静默写成 {"X-A":null} 并入库，而本层是「任务 3 → 任务 4 写侧校验」
        // 之间的窗口，脏值一旦静默落库用户只会看到「header 莫名消失」；旧手写实现在此处是 NPE（响亮失败），
        // 改造后不得退化为静默。
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null) {
                log.warn("[ProviderService] extra_headers 含有一个 header 名为 null 的条目，已跳过");
                continue;
            }
            if (e.getValue() == null) {
                log.warn("[ProviderService] extra_headers 中 header [{}] 的值为 null，已跳过", e.getKey());
                continue;
            }
            clean.put(e.getKey(), e.getValue());
        }
        if (clean.isEmpty()) {
            // 全部条目都被跳过 → 与「空 Map 存 null」的既有契约一致（DB 里要么 NULL、要么至少含一个条目的 JSON）。
            // 注意这与「存 {}」不可互换：deserializeHeaders(null) 返回 null，而 deserializeHeaders("{}") 返回空 Map。
            return null;
        }
        try {
            return HEADERS_JSON.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("[ProviderService] extra_headers 序列化失败，按 null 存储: {}", e.toString());
            return null;
        }
    }
    /**
     * 反序列化。失败 → warn + 空 Map（不静默：打印原始片段便于排查）。
     *
     * <p><b>已知行为位移（登记，不修）</b>：入参字面量 {@code "null"} 旧实现返回空 Map，本实现 Jackson 会返回
     * {@code null}。判定**不可达**——本类 {@link #serializeHeaders} 永不写出字符串 {@code "null"}
     * （null / 空 Map 一律返回 Java {@code null} → 落库为 SQL NULL），故不存在能产生该入参的写路径。
     *
     * <p><b>任务 6 提为 public static</b>：运行时链路（ModelConfigResolver / ChatService 两个
     * Provider 实体构造点）需把 {@code Provider.extraHeaders}（JSON 字符串）转成
     * {@link com.nexusai.infra.llm.ProviderConfig#extraHeaders()} 的 Map 形态。
     * <b>注意返回 null ≠ 返回空 Map</b>（null = 未配置；空 Map = 配了但解析失败）——
     * 注入侧 {@link com.nexusai.infra.llm.ProviderHeaderInjector} 对两者都做了空短路，故无行为差异。
     */
    public static Map<String, String> deserializeHeaders(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return HEADERS_JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            String snippet = json.length() > 200 ? json.substring(0, 200) + "…" : json;
            log.warn("[ProviderService] extra_headers 反序列化失败，按空处理 · 原始值片段=[{}] · {}",
                snippet, e.toString());
            return Map.of();
        }
    }
    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s); } catch (Exception e) { return null; }
    }
}
