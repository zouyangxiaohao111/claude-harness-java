package com.nexusai.domain.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.DynamicHeaderExpander;
import com.nexusai.infra.llm.ProviderHeaderInjector;
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
    /** 拉取远端模型清单时解析各家 /models 响应（键名不一，见 parseRemoteModels） */
    @Autowired private ObjectMapper objectMapper;

    private static final int SETTINGS_SINGLETON_ID = 1;

    // ───────────────────── [V296] 订阅 OAuth 凭据层 ─────────────────────

    /** 凭据形态：静态 API key（默认）。 */
    public static final String AUTH_MODE_API_KEY = "api_key";
    /** 凭据形态：OpenAI 订阅（Codex）OAuth 会话。 */
    public static final String AUTH_MODE_CODEX_OAUTH = "codex_oauth";
    /** 凭据形态：Anthropic Claude 订阅（Claude Code OAuth）会话。 */
    public static final String AUTH_MODE_CLAUDE_OAUTH = "claude_oauth";

    /**
     * access token 提前刷新窗口（5 分钟）。
     *
     * <p>WHY：请求可能排队 / 重试，卡到「刚好没过期」再发就会 401；提前 5 分钟换新，
     * 让一次刷新覆盖整轮对话。口径 = epoch 毫秒（与 oauth_expires_at 同口径）。
     */
    private static final long OAUTH_REFRESH_SKEW_MILLIS = 5 * 60 * 1000L;

    /**
     * OAuth 刷新能力（实现由上层注入；未接线时 null）。
     *
     * <p>WHY 定义在 domain 层：{@link ProviderService} 属于 domain 包，不能反向依赖
     * application/infra 的 token 客户端；由上层（登录/装配侧）实现后注入，
     * 未接线时刷新路径降级为「warn + 返回 null」，⛔ 不静默返回过期凭据。
     */
    public interface OAuthRefresher {
        /** 用 refresh token 换新凭据；失败返回 null。 */
        Refreshed refresh(String refreshToken);

        /**
         * 本刷新器服务的凭据形态（与 {@code providers.auth_mode} 同值）。
         *
         * <p>为什么要它：token 端点各家不同（Codex = form-urlencoded，Claude = JSON），
         * 刷新器按 provider 的 {@code auth_mode} 选择。默认 {@code codex_oauth} 是既有唯一实现的
         * 语义，⛔ 不改变 Codex 行为。
         */
        default String authMode() {
            return AUTH_MODE_CODEX_OAUTH;
        }

        /** 刷新结果（expiresAt 为 epoch 毫秒；refresh/accountId 可为 null = 沿用旧值）。 */
        record Refreshed(String access, String refresh, long expiresAt, String accountId) {}
    }

    /** OAuth 刷新器（可空：实现由上层接线；按 {@link OAuthRefresher#authMode()} 选）。 */
    @Autowired(required = false) private List<OAuthRefresher> oauthRefreshers;

    public List<ProviderDto> listAll() {
        List<ProviderRecord> records = providerMapper.selectAll();
        if (records.isEmpty()) return List.of();
        List<ModelRecord> allModelRecords = modelMapper.selectAll();
        // providerId → name（模型 DTO 要显示 `provider/modelName`，这里已有 records 不必再查）
        Map<String, String> nameById = new HashMap<>();
        for (ProviderRecord r : records) {
            if (r.getId() != null) {
                nameById.put(r.getId(), r.getName());
            }
        }
        Map<String, List<ModelDto>> modelsByProvider = new HashMap<>();
        for (ModelRecord r : allModelRecords) {
            modelsByProvider.computeIfAbsent(r.getProviderId(), k -> new ArrayList<>())
                .add(toModelDto(r.toDomain(), nameById.get(r.getProviderId())));
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
            .map(rec -> toModelDto(rec.toDomain(), p.getName()))
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
        p.setApiKeyMasked(ApiKeyHasher.maskSecret(req.apiKey()));
        p.setApiKeyEncrypted(cryptoUtil.encrypt(req.apiKey()));
        // 写侧校验必须在序列化之前：敏感头 / 非法名 / 换行值 / 占位符拼写错误 → 400（规范 §6.4）
        validateExtraHeaders(req.extraHeaders());
        // [保密 2026-11-22] 新建时库里没有旧值可还原 ⇒ 掩码值直接丢弃（⛔ 不把 **** 当配置存进库）
        p.setExtraHeaders(serializeHeaders(
            ApiKeyHasher.mergeMaskedMap(req.extraHeaders(), null)));
        p.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        // [V296] auth_mode 是 NOT NULL 列：insert(entity) 不忽略 null ⇒ 必须显式给默认值，
        //   否则 NOT NULL 约束会挡住「新建 provider」（DB 的 DEFAULT 只对「不写该列」生效）。
        p.setAuthMode(AUTH_MODE_API_KEY);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));
        return toProviderDto(p, List.of());
    }

    // ───────────── [方案 A] 内置「OpenAI 官方」订阅 provider ─────────────

    /** 官方 Codex provider 的固定名（识别约定之一：name + type）。 */
    private static final String OFFICIAL_PROVIDER_NAME = "OpenAI";

    /**
     * 「OpenAI」已被别的类型占用时的备用名。
     *
     * <p>⚠️ 也计入 name+type 识别约定：登录前 auth_mode 仍是 api_key，若只认 {@link #OFFICIAL_PROVIDER_NAME}
     * 这条记录就不可见 ⇒ 再点一次登录会重复创建（前端 isOfficial 同样认这个名字）。
     */
    private static final String OFFICIAL_PROVIDER_FALLBACK_NAME = "OpenAI-订阅";

    /** 官方 Codex provider 的协议类型（固定）。 */
    private static final String OFFICIAL_PROVIDER_TYPE = ProviderType.openai_response.name();

    /**
     * 查官方 Codex provider（不存在 → null）。
     *
     * <p>识别优先级：{@code auth_mode='codex_oauth'} 优先（登录后唯一可靠判据），其次
     * name+type 约定（{@code OpenAI} / {@code OpenAI-订阅} + {@code openai_response}，
     * 覆盖「已 ensure 但还没登录」的窗口）。
     *
     * <p>⚠️ 多条 codex_oauth（并发 ensure / 历史脏数据）取第一条并 warn —— 不做修复、不抛。
     */
    public ProviderDto getOfficialCodexProvider() {
        return findOfficialCodexProvider(selectAllProviderRecords());
    }

    /**
     * 幂等创建 / 返回官方 Codex provider。
     *
     * <p>已存在直接返回；否则插入一条：name={@code OpenAI}（被别的类型占用则 {@code OpenAI-订阅}）、
     * type={@code openai_response}、baseUrl=Codex 强制端点、空 API key（hash/mask/encrypt 对空串均正常）、
     * auth_mode 保持 {@code api_key}（登录成功后由 {@link #saveOAuthCredential} 翻成 codex_oauth）、
     * enabled=1。
     *
     * <p><b>并发容忍</b>：先查再建、不加锁 —— 重复请求最坏各插一条，随后
     * {@link #getOfficialCodexProvider()} 的「取第一条 + warn」会稳定收敛；不为此引入分布式锁。
     * ⛔ 不改 {@link #create} 的既有语义（它强制 api_key 模式是对的）。
     */
    public ProviderDto ensureOfficialCodexProvider() {
        List<ProviderRecord> records = selectAllProviderRecords();
        ProviderDto existing = findOfficialCodexProvider(records);
        if (existing != null) {
            // 已有 provider 但一条模型都没有（历史库 / 中途删光）→ 补内置默认模型；有模型则 no-op
            ensureDefaultOfficialModel(existing.id());
            return existing;
        }

        // `OpenAI` 已被别的类型占用（同类型的话 findOfficial 已经命中）⇒ 用备用名
        String name = OFFICIAL_PROVIDER_NAME;
        for (ProviderRecord r : records) {
            if (OFFICIAL_PROVIDER_NAME.equals(r.getName())) {
                name = OFFICIAL_PROVIDER_FALLBACK_NAME;
                break;
            }
        }

        Provider p = new Provider();
        p.setId(generateId("prov"));
        p.setName(name);
        p.setType(OFFICIAL_PROVIDER_TYPE);
        p.setBaseUrl(CODEX_OAUTH_BASE_URL);
        // 空串凭据：登录前不用它发请求；hash/mask/encrypt 对空串都是正常值（mask → "****"）
        p.setApiKeyHash(ApiKeyHasher.hash(""));
        p.setApiKeyMasked(ApiKeyHasher.maskSecret(""));
        p.setApiKeyEncrypted(cryptoUtil.encrypt(""));
        p.setEnabled(Boolean.TRUE);
        p.setAuthMode(AUTH_MODE_API_KEY);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));
        log.info("已创建内置 OpenAI 官方 provider: id={} name={} type={}", p.getId(), name, p.getType());
        // 新建的官方 provider 是 0 模型 ⇒ 立刻补一条内置默认模型，用户开箱即可对话
        ensureDefaultOfficialModel(p.getId());
        return toProviderDto(p, List.of());
    }

    /** 官方 provider 的内置默认模型名（模型数为 0 时才插入，⛔ 不覆盖 / 不去重删除用户已有模型）。 */
    private static final String DEFAULT_OFFICIAL_MODEL_NAME = "gpt-6.1-sol";

    /**
     * 官方 provider 模型数为 0 → 插入一条内置默认模型（幂等）。
     *
     * <p><b>触发条件</b>：该 providerId 下 {@code models} 一条都没有。已有任意模型（哪怕未启用）
     * 一律 no-op —— 用户自己配的模型永远优先，不覆盖、不删重。
     *
     * <p>数值字段按 {@link ModelService#create} 的同类默认给：上下文 272000 / 输出 32000；
     * {@code temperature} / {@code topP} 留 null（= 用服务端默认，不注入请求参数）。
     */
    private void ensureDefaultOfficialModel(String providerId) {
        if (providerId == null || providerId.isBlank()) return;
        List<ModelRecord> models = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", providerId));
        if (models != null && !models.isEmpty()) return;

        Model m = new Model();
        m.setId(generateId("model"));
        m.setProviderId(providerId);
        m.setName(DEFAULT_OFFICIAL_MODEL_NAME);
        m.setAlias(null);
        m.setTag("OAI");
        m.setDescription("内置默认模型");
        m.setType(ModelType.chat.name());
        m.setMaxTokens(32000);
        m.setTemperature(null);
        m.setTopP(null);
        m.setMaxContextTokens(272000);
        // models 表 think / extra_body 有 DEFAULT ''，这里显式给空串与 ModelService.create 一致
        m.setThink("");
        m.setExtraBody("");
        m.setEnabled(Boolean.TRUE);
        m.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        modelMapper.insert(ModelRecord.fromDomain(m));
        log.info("已为内置 OpenAI 官方 provider 补默认模型: providerId={} name={}",
            providerId, DEFAULT_OFFICIAL_MODEL_NAME);
    }

    /** 全部 provider 记录（mapper 返回 null 视为空）。provider 个位数规模 ⇒ 全表读即可。 */
    private List<ProviderRecord> selectAllProviderRecords() {
        List<ProviderRecord> all = providerMapper.selectAll();
        return all == null ? List.of() : all;
    }

    /** 官方识别（get / ensure 共用同一份判据，避免两套实现漂移）。 */
    private ProviderDto findOfficialCodexProvider(List<ProviderRecord> records) {
        ProviderRecord firstOauth = null;
        for (ProviderRecord r : records) {
            if (!AUTH_MODE_CODEX_OAUTH.equals(r.getAuthMode())) continue;
            if (firstOauth == null) {
                firstOauth = r;
            } else {
                log.warn("发现多条 codex_oauth provider（{} / {}），官方识别取第一条 id={}",
                    firstOauth.getId(), r.getId(), firstOauth.getId());
                break;
            }
        }
        if (firstOauth != null) return toProviderDto(firstOauth.toDomain(), List.of());
        for (ProviderRecord r : records) {
            boolean nameMatches = OFFICIAL_PROVIDER_NAME.equals(r.getName())
                || OFFICIAL_PROVIDER_FALLBACK_NAME.equals(r.getName());
            if (nameMatches && OFFICIAL_PROVIDER_TYPE.equals(r.getType())) {
                return toProviderDto(r.toDomain(), List.of());
            }
        }
        return null;
    }

    // ───────────── [方案 A+1] 内置「Claude」订阅 provider ─────────────

    /** 内置 Claude provider 的固定名（识别约定之一：name + type）。 */
    private static final String CLAUDE_PROVIDER_NAME = "Claude";

    /**
     * 「Claude」已被别的类型占用时的备用名。
     *
     * <p>⚠️ 也计入 name+type 识别约定：登录前 auth_mode 仍是 api_key，若只认
     * {@link #CLAUDE_PROVIDER_NAME} 这条记录就不可见 ⇒ 再点一次登录会重复创建。
     */
    private static final String CLAUDE_PROVIDER_FALLBACK_NAME = "Claude-订阅";

    /** 内置 Claude provider 的协议类型（固定）。 */
    private static final String CLAUDE_PROVIDER_TYPE = ProviderType.anthropic.name();

    /** Claude 订阅的固定 baseUrl（订阅凭据只对 Anthropic 官方端点有效）。 */
    private static final String CLAUDE_API_BASE_URL = "https://api.anthropic.com";

    /** 内置品牌目录里 Claude 的稳定键（{@link BuiltinProviderCatalog#byKey(String)} 用）。 */
    private static final String CLAUDE_BRAND_KEY = "claude";

    /** 官方 Claude provider 的内置默认模型名（模型数为 0 时才插入）。 */
    private static final String DEFAULT_CLAUDE_MODEL_NAME = "claude-sonnet-4-5";

    /**
     * 幂等创建 / 返回内置 Claude provider（点「登录 Claude 订阅」的第一步）。
     *
     * <p>识别优先级：{@code auth_mode='claude_oauth'} 优先（登录后唯一可靠判据），其次
     * {@code name+type} 约定（{@code Claude} / {@code Claude-订阅} + {@code anthropic}），最后按
     * {@link BuiltinProviderCatalog} 的 {@code api.anthropic.com} host 命中（用户可能已自建过一条
     * 官方地址的 Anthropic provider ⇒ 复用比再建一条更符合预期）。
     *
     * <p>新建时：name={@code Claude}（被别的类型占用则 {@code Claude-订阅}）、
     * type={@code anthropic}、baseUrl={@code https://api.anthropic.com}、空 API key、
     * auth_mode 保持 {@code api_key}（登录成功后由
     * {@link #saveOAuthCredential(String, String, String, String, Long, String)} 翻成
     * {@code claude_oauth}）、enabled=1；模型为 0 时补一条内置默认模型。
     *
     * <p><b>并发容忍</b>：先查再建、不加锁 —— 重复请求最坏各插一条，随后识别取第一条并 warn 收敛。
     */
    public ProviderDto ensureOfficialClaudeProvider() {
        List<ProviderRecord> records = selectAllProviderRecords();
        ProviderDto existing = findOfficialClaudeProvider(records);
        if (existing != null) {
            ensureDefaultClaudeModel(existing.id());
            return existing;
        }

        // `Claude` 已被别的类型占用（同类型的话 find 已经命中）⇒ 用备用名
        String name = CLAUDE_PROVIDER_NAME;
        for (ProviderRecord r : records) {
            if (CLAUDE_PROVIDER_NAME.equals(r.getName())) {
                name = CLAUDE_PROVIDER_FALLBACK_NAME;
                break;
            }
        }

        Provider p = new Provider();
        p.setId(generateId("prov"));
        p.setName(name);
        p.setType(CLAUDE_PROVIDER_TYPE);
        p.setBaseUrl(CLAUDE_API_BASE_URL);
        // 空串凭据：登录前不用它发请求；hash/mask/encrypt 对空串都是正常值（mask → "****"）
        p.setApiKeyHash(ApiKeyHasher.hash(""));
        p.setApiKeyMasked(ApiKeyHasher.maskSecret(""));
        p.setApiKeyEncrypted(cryptoUtil.encrypt(""));
        p.setEnabled(Boolean.TRUE);
        p.setAuthMode(AUTH_MODE_API_KEY);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));
        log.info("已创建内置 Claude provider: id={} name={} type={}", p.getId(), name, p.getType());
        ensureDefaultClaudeModel(p.getId());
        return toProviderDto(p, List.of());
    }

    /**
     * 官方 Claude 识别（get / ensure 共用同一份判据）。
     *
     * <p>第 3 条按 {@link BuiltinProviderCatalog} 的 host 集合匹配，与
     * {@link #listBuiltinStatuses()} 的「已配置」口径保持一致 —— 否则前端认为「已配置」而
     * ensure 又新建一条，卡片与列表会各指一个 provider。
     */
    private ProviderDto findOfficialClaudeProvider(List<ProviderRecord> records) {
        ProviderRecord firstOauth = null;
        for (ProviderRecord r : records) {
            if (!AUTH_MODE_CLAUDE_OAUTH.equals(r.getAuthMode())) continue;
            if (firstOauth == null) {
                firstOauth = r;
            } else {
                log.warn("发现多条 claude_oauth provider（{} / {}），官方识别取第一条 id={}",
                    firstOauth.getId(), r.getId(), firstOauth.getId());
                break;
            }
        }
        if (firstOauth != null) return toProviderDto(firstOauth.toDomain(), List.of());
        for (ProviderRecord r : records) {
            boolean nameMatches = CLAUDE_PROVIDER_NAME.equals(r.getName())
                || CLAUDE_PROVIDER_FALLBACK_NAME.equals(r.getName());
            if (nameMatches && CLAUDE_PROVIDER_TYPE.equals(r.getType())) {
                return toProviderDto(r.toDomain(), List.of());
            }
        }
        BuiltinProviderCatalog.Brand brand = BuiltinProviderCatalog.byKey(CLAUDE_BRAND_KEY);
        if (brand != null) {
            for (ProviderRecord r : records) {
                if (baseUrlHostMatches(r.getBaseUrl(), brand.matchHosts())) {
                    return toProviderDto(r.toDomain(), List.of());
                }
            }
        }
        return null;
    }

    /**
     * Claude provider 模型数为 0 → 插入一条内置默认模型（幂等）。
     *
     * <p>触发条件与 {@link #ensureDefaultOfficialModel(String)} 相同：该 providerId 下
     * {@code models} 一条都没有。已有任意模型一律 no-op（用户自己配的永远优先）。
     */
    private void ensureDefaultClaudeModel(String providerId) {
        if (providerId == null || providerId.isBlank()) return;
        List<ModelRecord> models = modelMapper.selectListByQuery(
            QueryWrapper.create().eq("provider_id", providerId));
        if (models != null && !models.isEmpty()) return;

        Model m = new Model();
        m.setId(generateId("model"));
        m.setProviderId(providerId);
        m.setName(DEFAULT_CLAUDE_MODEL_NAME);
        m.setAlias(null);
        // 与前端 brandTagFromName('Claude') 的品牌色一致（'CL'）
        m.setTag("CL");
        m.setDescription("内置默认模型");
        m.setType(ModelType.chat.name());
        m.setMaxTokens(64000);
        m.setTemperature(null);
        m.setTopP(null);
        m.setMaxContextTokens(200000);
        m.setThink("");
        m.setExtraBody("");
        m.setEnabled(Boolean.TRUE);
        m.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        modelMapper.insert(ModelRecord.fromDomain(m));
        log.info("已为内置 Claude provider 补默认模型: providerId={} name={}",
            providerId, DEFAULT_CLAUDE_MODEL_NAME);
    }

    // ───────────── [内置品牌目录] 5 张常驻内置卡（只读） ─────────────

    /** 一个品牌下的一条连接（只读快照 · 不含任何凭据）。 */
    public record BuiltinConnection(String id, String name, int modelCount, boolean enabled, String authMode) {
    }

    /**
     * 单个内置品牌的配置状态（只读快照 · 不含任何凭据）。
     *
     * <p>[V299] {@code connections} = 同一品牌下的全部连接；{@code configured / providerId / ...}
     * 保留为「第一条连接」的兼容字段（旧前端仍读它们），⛔ 不要再用它判断「一个品牌只能配一条」。
     */
    public record BuiltinStatus(String key, String group, String name, String type, String baseUrl, String subtitle,
                                boolean configured, String providerId, String providerName, int modelCount,
                                List<BuiltinConnection> connections,
                                /** 模板模型建议（[2026-11-23 Octop provider 导入] 可空 · 前端「导入模型」里作建议，用户可改）。 */
                                List<BuiltinProviderCatalog.BrandModel> models) {
    }

    /**
     * 内置品牌目录 + 每个品牌下的连接（只读 · 不写库、不创建记录）。
     *
     * <p><b>匹配优先级</b>：{@code providers.brand_key} 精确命中 → 该品牌的全部连接；
     * 没有 brand_key 的老记录才回落到 host（含子域）→ name 的旧判据。
     *
     * <p>⛔ 不再「多个命中取第一条并 warn」：一个品牌本来就可以有多条连接（不同 Key / 不同账号）。
     */
    public List<BuiltinStatus> listBuiltinStatuses() {
        List<ProviderRecord> records = selectAllProviderRecords();
        // providerId → 模型数（一次全表读，providers/models 都是个位数~几十规模）
        Map<String, Integer> modelCounts = new HashMap<>();
        List<ModelRecord> allModels = modelMapper.selectAll();
        if (allModels != null) {
            for (ModelRecord m : allModels) {
                String pid = m.getProviderId();
                if (pid != null) modelCounts.merge(pid, 1, Integer::sum);
            }
        }

        List<BuiltinStatus> out = new ArrayList<>(BuiltinProviderCatalog.BRANDS.size());

        // providerId → 最终品牌 key。
        // ① 显式 brand_key 优先；② 未归属的记录按 host/name 推断**一次**（只归第一个命中的品牌）。
        // ⚠️ 不能「品牌没命中才回落」：同一品牌既有 brand_key 命中、又有未归属的老记录时，
        //    老记录会被漏掉（用户 2026-11-17 报：大写 Kimi / api.moonshot.cn 留在自定义组）。
        Map<String, String> brandOf = new HashMap<>();
        for (ProviderRecord r : records) {
            if (r.getId() != null && r.getBrandKey() != null && !r.getBrandKey().isBlank()) {
                brandOf.put(r.getId(), r.getBrandKey());
            }
        }
        for (ProviderRecord r : records) {
            if (r.getId() == null || brandOf.containsKey(r.getId())) continue;
            for (BuiltinProviderCatalog.Brand b : BuiltinProviderCatalog.BRANDS) {
                if (baseUrlHostMatches(r.getBaseUrl(), b.matchHosts())
                    || providerNameMatches(r.getName(), b.matchNames())) {
                    brandOf.put(r.getId(), b.key());
                    break;
                }
            }
        }

        for (BuiltinProviderCatalog.Brand brand : BuiltinProviderCatalog.BRANDS) {
            List<ProviderRecord> hits = new ArrayList<>();
            for (ProviderRecord r : records) {
                if (r.getId() != null && brand.key().equals(brandOf.get(r.getId()))) hits.add(r);
            }
            List<BuiltinConnection> connections = hits.stream()
                .filter(r -> r.getId() != null)
                .map(r -> new BuiltinConnection(
                    r.getId(), r.getName(),
                    modelCounts.getOrDefault(r.getId(), 0),
                    Boolean.TRUE.equals(r.getEnabled()),
                    r.getAuthMode() == null ? AUTH_MODE_API_KEY : r.getAuthMode()))
                .toList();
            ProviderRecord first = hits.isEmpty() ? null : hits.get(0);
            out.add(new BuiltinStatus(brand.key(), brand.group(), brand.name(), brand.type(), brand.baseUrl(),
                brand.subtitle(), first != null,
                first == null ? null : first.getId(),
                first == null ? null : first.getName(),
                first == null ? 0 : modelCounts.getOrDefault(first.getId(), 0),
                connections,
                brand.models()));
        }
        return out;
    }

    /**
     * 新建连接时的品牌归属：显式值优先；否则按 host/name 猜一次（猜不中 = 自定义连接）。
     */
    private static String resolveBrandKey(String explicit, String name, String baseUrl) {
        if (explicit != null) return explicit.isBlank() ? null : explicit.trim();
        for (BuiltinProviderCatalog.Brand b : BuiltinProviderCatalog.BRANDS) {
            if (baseUrlHostMatches(baseUrl, b.matchHosts())) return b.key();
            if (providerNameMatches(name, b.matchNames())) return b.key();
        }
        return null;
    }

    /** baseUrl 解析出的 host 等于品牌 host，或为其子域（如 foo.api.deepseek.com 命中 api.deepseek.com）。 */
    private static boolean baseUrlHostMatches(String baseUrl, Set<String> hosts) {
        if (baseUrl == null || baseUrl.isBlank() || hosts == null || hosts.isEmpty()) return false;
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String candidate : hosts) {
            if (candidate == null || candidate.isBlank()) continue;
            String c = candidate.toLowerCase(Locale.ROOT);
            if (h.equals(c) || h.endsWith("." + c)) return true;
        }
        return false;
    }

    /** provider 名 trim + 小写后等于任一别名（大小写不敏感）。 */
    private static boolean providerNameMatches(String name, Set<String> names) {
        if (name == null || names == null || names.isEmpty()) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (String candidate : names) {
            if (candidate != null && n.equals(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
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
        // [保密 2026-11-22 B4] 掩码哨兵：GET 只回 apiKeyMasked ⇒ 客户端把掩码当 apiKey 回传时
        //   必须视为「未修改」。⛔ 不能只判 !=null：否则库里真 key 的密文会被 "****1234" 顶掉。
        //   基准 = 当前密文的解密明文（拿不到时回落 storedMasked）；空/空白同样视为不改
        //   （旧实现会把 "" 加密成一个「空凭据」，同样属于静默破坏）。
        if (req.apiKey() != null && !req.apiKey().isBlank()
                && !isMaskedApiKeyEcho(req.apiKey(), p)) {
            p.setApiKeyHash(ApiKeyHasher.hash(req.apiKey()));
            p.setApiKeyMasked(ApiKeyHasher.maskSecret(req.apiKey()));
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
        // [保密 2026-11-22] 掩码哨兵：敏感头回传的 ****末四位 ⇒ 保留库里原值（否则真 token
        //   被掩码覆盖，下一次请求就 401）。其余头按入参写；非敏感键从不受影响。
        Map<String, String> mergedHeaders = null;
        if (req.extraHeaders() != null) {
            mergedHeaders = ApiKeyHasher.mergeMaskedMap(
                req.extraHeaders(), deserializeHeaders(p.getExtraHeaders()));
        }
        boolean clearHeaders = mergedHeaders != null && mergedHeaders.isEmpty();
        if (mergedHeaders != null) {
            p.setExtraHeaders(clearHeaders ? null : serializeHeaders(mergedHeaders));
        }
        if (req.enabled() != null) p.setEnabled(req.enabled());
        // [V299] 品牌归属三态：缺字段 = 不改；空串 = 移出品牌（改回自定义）；非空 = 写入
        boolean clearBrand = req.brandKey() != null && req.brandKey().isBlank();
        if (req.brandKey() != null) {
            p.setBrandKey(clearBrand ? null : req.brandKey().trim());
        }
        p.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        ProviderRecord rec = ProviderRecord.fromDomain(p);
        if (clearHeaders || clearBrand) {
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
        // [C2c] 凭据与 baseUrl 统一走 resolveCredential / resolveRemoteBaseUrl：
        //   codex_oauth 模式下 apiKeyEncrypted 恒为空，直接解密必然失败；baseUrl 也必须强制到
        //   /backend-api/codex（用户填的值不参与）。
        String apiKey = resolveCredential(id);
        if (apiKey == null || apiKey.isBlank()) {
            return new TestConnectionResponse(false, null,
                AUTH_MODE_CODEX_OAUTH.equals(r.getAuthMode())
                    ? "无法获取 OAuth access token（未登录或刷新失败）"
                    : "无法解密 API key", null);
        }
        String testUrl = resolveRemoteBaseUrl(r, p) + "/models";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        long t0 = System.nanoTime();
        try {
            // 请求组装复用 buildModelsRequest（与 fetchRemoteModels 单一真源）——
            //   原本这段逻辑内联在此，自定义 header 注入 / Authorization 覆盖 / sessionId 恒 null
            //   三处细节的 WHY 全部搬进了那个方法的 javadoc（⚠️ 别再拆回两套判据）。
            HttpRequest req = buildModelsRequest(r, p, apiKey, testUrl).GET().build();
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

    // ───────────────────────── 拉取远端模型清单 ─────────────────────────

    /**
     * 拉取提供商的远端模型清单（{@code GET {baseUrl}/models}）。
     *
     * <p><b>WHY 复用 test() 的请求构造</b>：{@code test()} 已经把「自定义请求头 + Authorization +
     * 超时」这套组装跑通了（见其上方注释里的 B3 修复）。另写一套 ⇒ 自定义请求头会漏、鉴权头会漏、
     * 超时行为不一致 —— 本仓前科：同一能力两套判据必然漂移。故抽 {@link #buildModelsRequest} 单一真源。
     *
     * <p><b>失败不抛异常</b>：拉模型是辅助动作，失败要让用户能退回手动添加。
     *
     * @param id provider id
     * @return 归一化后的清单；失败时 {@code ok=false} + 中文原因
     */
    public RemoteModelsResponse fetchRemoteModels(String id) {
        ProviderRecord r = providerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Provider " + id + " not found");
        Provider p = r.toDomain();
        // [C2c] 同 test()：凭据走 resolveCredential，baseUrl 走 resolveRemoteBaseUrl。
        String apiKey = resolveCredential(id);
        if (apiKey == null || apiKey.isBlank()) {
            return new RemoteModelsResponse(false, List.of(),
                AUTH_MODE_CODEX_OAUTH.equals(r.getAuthMode())
                    ? "无法获取 OAuth access token（未登录或刷新失败）"
                    : "无法解密 API key");
        }
        String modelsUrl = resolveRemoteBaseUrl(r, p) + "/models";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = buildModelsRequest(r, p, apiKey, modelsUrl).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 401 || sc == 403) {
                return new RemoteModelsResponse(false, List.of(), "鉴权失败：API Key 无效");
            }
            if (sc == 404) {
                return new RemoteModelsResponse(false, List.of(), "该提供商未提供模型列表接口（404）");
            }
            if (sc < 200 || sc >= 300) {
                return new RemoteModelsResponse(false, List.of(), "HTTP " + sc);
            }
            List<RemoteModelDto> models = parseRemoteModels(resp.body());
            return new RemoteModelsResponse(true, models,
                "读取到 " + models.size() + " 个模型");
        } catch (java.net.ConnectException ce) {
            return new RemoteModelsResponse(false, List.of(), "无法连接 " + modelsUrl + "：" + ce.getMessage());
        } catch (Exception e) {
            log.warn("Provider {} fetchRemoteModels failed: {}", id, e.toString());
            return new RemoteModelsResponse(false, List.of(), "读取失败：" + e.getMessage());
        }
    }

    /** codex_oauth 强制端点（与 {@code ChatService.CODEX_OAUTH_BASE_URL} 同值，两侧判据各自独立成型）。 */
    private static final String CODEX_OAUTH_BASE_URL = "https://chatgpt.com/backend-api/codex";

    /**
     * 远端探测（{@code /models}）用的 baseUrl —— {@code test()} / {@code fetchRemoteModels()} 共用。
     *
     * <p>{@code codex_oauth} → <b>强制</b> {@link #CODEX_OAUTH_BASE_URL}（用户填的 baseUrl 不参与：
     * 订阅凭据只对 ChatGPT 后端有效）；其余模式 → provider.baseUrl，去空白 + 去尾斜杠。
     */
    private String resolveRemoteBaseUrl(ProviderRecord r, Provider p) {
        if (AUTH_MODE_CODEX_OAUTH.equals(r.getAuthMode())) {
            return CODEX_OAUTH_BASE_URL;
        }
        String url = (p.getBaseUrl() == null ? "" : p.getBaseUrl()).trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 组装 {@code GET {baseUrl}/models} 请求 —— <b>test() 与 fetchRemoteModels() 共用的单一真源</b>。
     *
     * <p>⚠️ 保持与 test() 完全一致的两处细节（勿动）：
     * <ul>
     *   <li>自定义 header 走 {@code ProviderHeaderInjector.apply}（含占位符展开 + 禁止清单过滤）；
     *       内置 Authorization 必须在 apply <b>之后</b>用 {@code setHeader} 覆盖
     *       （{@code header()} 是追加语义 ⇒ 用户配的 authorization 会与内置凭据并列上线）。</li>
     *   <li>第三参 sessionId <b>恒传 null</b>：本请求是一次性 GET，亲和性无意义；
     *       读线程局部 MDC 会把 A 会话的 id 发给 B 会话。</li>
     * </ul>
     */
    private HttpRequest.Builder buildModelsRequest(ProviderRecord r, Provider p, String apiKey, String url) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json");
        ProviderHeaderInjector.apply(rb::header, deserializeHeaders(p.getExtraHeaders()), null);
        rb.setHeader("Authorization", "Bearer " + apiKey);
        return rb;
    }

    /**
     * 解析各家 {@code /models} 响应 → 归一化的 {@link RemoteModelDto} 列表。
     *
     * <p><b>各家形状（实测 2026-11-03）</b>：
     * <ul>
     *   <li>标准 OpenAI：{@code data:[{id, object, created, owned_by}]} —— 无窗口/输出上限信息</li>
     *   <li>DeepSeek：{@code data:[{id, name, context_window, max_output_tokens, input_modalities}]}</li>
     *   <li>OpenRouter：{@code data:[{id, name, context_length, top_provider:{context_length, max_completion_tokens}}]}</li>
     *   <li>Anthropic：{@code data:[{id, display_name}]}</li>
     * </ul>
     *
     * <p>⚠️ <b>键名多路尝试</b>：只认一个键名 = 只支持一家（实测三家三种叫法）。
     *
     * <p>⚠️ 解析失败**不抛异常**，返回空列表 + 日志：拉模型是尽力而为的动作。
     */
    private List<RemoteModelDto> parseRemoteModels(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (!data.isArray()) return List.of();
            List<RemoteModelDto> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : data) {
                if (!item.isObject()) continue;
                String id = text(item, "id");
                if (id == null || id.isBlank()) continue;
                if (!seen.add(id)) continue;   // 同一批里 id 重复 → 只留一条
                String name = firstText(item, "name", "display_name");
                Integer ctx = firstInt(item, "context_window", "context_length", "max_context_length");
                if (ctx == null) {
                    JsonNode tp = item.get("top_provider");
                    if (tp != null && tp.isObject()) ctx = firstInt(tp, "context_length");
                }
                Integer maxOut = firstInt(item, "max_output_tokens", "max_tokens");
                if (maxOut == null) {
                    JsonNode tp = item.get("top_provider");
                    if (tp != null && tp.isObject()) maxOut = firstInt(tp, "max_completion_tokens");
                }
                out.add(new RemoteModelDto(id, name, ctx, maxOut));
            }
            return out;
        } catch (Exception e) {
            log.warn("parseRemoteModels failed: {}", e.toString());
            return List.of();
        }
    }

    /** 取字符串字段（null/空白视为无）。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 取第一个非空字符串字段。 */
    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * 取第一个非空整数字段。
     *
     * <p>⚠️ 用 {@code canConvertToInt()} 判型而非 {@code asInt()}：后者对非数字文本静默返回 0
     * ⇒ 一个「无此字段」会变成「窗口=0」这种致命错值（0 会让压缩阈值失效）。
     */
    private static Integer firstInt(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v == null || v.isNull()) continue;
            if (v.isNumber() && v.canConvertToInt()) return v.asInt();
            if (v.isTextual()) {
                try {
                    return Integer.valueOf(v.asText().trim());
                } catch (NumberFormatException ignore) {
                    // 非数字文本 → 视为无此字段，继续试下一个键名
                }
            }
        }
        return null;
    }

    /**
     * 兼容入口：取「当前可用凭据」的明文（历史签名，所有调用方零改动）。
     *
     * <p>[V296] 语义已泛化为 {@link #resolveCredential(String)}：api_key 模式下与旧实现
     * 完全等价（解密 api_key_encrypted，失败 → warn + null）；codex_oauth 模式下返回
     * OAuth access token（必要时先刷新）。
     */
    public String getDecryptedApiKey(String providerId) {
        return resolveCredential(providerId);
    }

    /**
     * [保密 2026-11-22] 服务端内部读链：provider 自定义头的<b>明文</b>（供 hook 子代理发真实请求）。
     *
     * <p>⛔ 不要用 {@code ProviderDto.extraHeaders()} 去发请求：那是只回末四位的掩码。
     * 本方法直读 DB 列（与 {@code ProviderHeaderInjector.apply} 在测试连接里读的是同一份）。
     */
    public Map<String, String> getPlainExtraHeaders(String providerId) {
        if (providerId == null) return null;
        ProviderRecord r = providerMapper.selectOneById(providerId);
        return r == null ? null : deserializeHeaders(r.getExtraHeaders());
    }

    /**
     * 解析当前可用凭据：api_key 模式 → 解密 apiKey；订阅 OAuth 模式
     * （{@code codex_oauth} / {@code claude_oauth}）→ access token（必要时刷新）。
     *
     * <p>刷新策略：{@code oauth_expires_at} 为空视为「无过期信息」直接返回；距过期不足
     * {@link #OAUTH_REFRESH_SKEW_MILLIS} 时用 refresh token 换新并回写（写入统一走
     * {@link #saveOAuthCredential}，加密口径单一）。
     *
     * <p>失败一律返回 null 而不抛：调用方（请求装配）已有「无凭据」分支，抛出会把
     * 「未登录」升级成 500。
     *
     * @param providerId provider id（可 null → null）
     * @return 明文凭据；不存在 / 解密失败 / 刷新失败 → null
     */
    public String resolveCredential(String providerId) {
        if (providerId == null) return null;
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) return null;
        if (isOAuthAuthMode(r.getAuthMode())) {
            return resolveOAuthCredential(r);
        }
        return decryptOrNull(providerId, "api key", r.getApiKeyEncrypted());
    }

    /** 该 auth_mode 是否为订阅 OAuth 形态（codex_oauth / claude_oauth）。 */
    private static boolean isOAuthAuthMode(String authMode) {
        return AUTH_MODE_CODEX_OAUTH.equals(authMode) || AUTH_MODE_CLAUDE_OAUTH.equals(authMode);
    }

    /**
     * 按 auth_mode 选刷新器（Codex = form-urlencoded，Claude = JSON）。
     *
     * @param authMode provider 的 auth_mode（null 视为 codex_oauth = 既有唯一形态）
     * @return 匹配的刷新器；未接线 / 无匹配 → null（调用方 warn + 不使用过期凭据）
     */
    private OAuthRefresher refresherFor(String authMode) {
        List<OAuthRefresher> refreshers = this.oauthRefreshers;
        if (refreshers == null || refreshers.isEmpty()) {
            return null;
        }
        String mode = authMode == null ? AUTH_MODE_CODEX_OAUTH : authMode;
        for (OAuthRefresher refresher : refreshers) {
            if (refresher != null && mode.equals(refresher.authMode())) {
                return refresher;
            }
        }
        return null;
    }

    /** OAuth 分支：必要时刷新并回写；任何失败 → warn + null。 */
    private String resolveOAuthCredential(ProviderRecord r) {
        String providerId = r.getId();
        String access = decryptOrNull(providerId, "oauth access token", r.getOauthAccessEncrypted());
        if (access == null) {
            log.warn("Provider {} 为 {} 模式但没有可用的 access token", providerId, r.getAuthMode());
            return null;
        }
        Long expiresAt = r.getOauthExpiresAt();
        if (expiresAt == null || expiresAt > System.currentTimeMillis() + OAUTH_REFRESH_SKEW_MILLIS) {
            return access;
        }
        OAuthRefresher refresher = refresherFor(r.getAuthMode());
        if (refresher == null) {
            log.warn("Provider {} 的 OAuth access 已过期或即将过期，但 OAuthRefresher 未接线 ⇒ 无法刷新", providerId);
            return null;
        }
        String refreshToken = decryptOrNull(providerId, "oauth refresh token", r.getOauthRefreshEncrypted());
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Provider {} 的 OAuth access 已过期且无 refresh token", providerId);
            return null;
        }
        OAuthRefresher.Refreshed refreshed;
        try {
            refreshed = refresher.refresh(refreshToken);
        } catch (Exception e) {
            log.warn("Provider {} 的 OAuth 刷新抛异常: {}", providerId, e.getMessage());
            return null;
        }
        if (refreshed == null || refreshed.access() == null || refreshed.access().isBlank()) {
            log.warn("Provider {} 的 OAuth 刷新未返回可用 access token", providerId);
            return null;
        }
        // refresh token 会轮换：上游给了新的就用新的，没给就沿用旧的；accountId 同理
        String newRefresh = (refreshed.refresh() != null && !refreshed.refresh().isBlank())
            ? refreshed.refresh() : refreshToken;
        String newAccountId = refreshed.accountId() != null
            ? refreshed.accountId() : r.getOauthAccountId();
        try {
            // ⭐ 保留本 provider 原有的 auth_mode（刷新不能把 claude_oauth 改回 codex_oauth）
            saveOAuthCredential(providerId, r.getAuthMode(), refreshed.access(), newRefresh,
                refreshed.expiresAt(), newAccountId);
        } catch (Exception e) {
            // 回写失败不该让本轮请求失败：新 access 已在手里
            log.warn("Provider {} 的 OAuth 刷新结果回写失败（本轮仍用新 access）: {}", providerId, e.getMessage());
        }
        return refreshed.access();
    }

    /**
     * 强制刷新某 provider 的 OAuth 凭据（401 自愈路径）。
     *
     * <p>与 {@link #resolveOAuthCredential} 的分工：那里按本地 {@code oauth_expires_at} 决定要不要
     * 刷新；这里<b>无条件</b>刷新一次 —— 上游 401 已判定手里的 access 失效（时钟漂移 / 多实例
     * 并发刷新），再按本地过期时间判断只会把旧 token 原样返回。
     *
     * @param providerId provider id（可 null）
     * @return 新的 access token；不存在 / 非 codex_oauth / 无 refresh token / 刷新失败 → null
     */
    public String forceRefresh(String providerId) {
        if (providerId == null) return null;
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) return null;
        if (!isOAuthAuthMode(r.getAuthMode())) return null;
        return refreshOAuthCredential(r);
    }

    /**
     * 按当前 access token 反查 provider 并强制刷新（401 自愈路径）。
     *
     * <p>provider 数量很少（个位数），遍历 + 解密比对即可，⛔ 不为此建索引 / 落哈希。
     * 比较是普通 {@link String#equals}（本地单用户场景，无需常数时间）。
     *
     * @param accessToken 请求里正在用的明文 access token（可 null / blank）
     * @return 新的 access token；找不到匹配 provider / 非 codex_oauth / 刷新失败 → null
     */
    public String forceRefreshByAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return null;
        List<ProviderRecord> records;
        try {
            records = providerMapper.selectAll();
        } catch (Exception e) {
            log.warn("OAuth 反查刷新时读取 provider 列表失败: {}", e.getMessage());
            return null;
        }
        if (records == null) return null;
        for (ProviderRecord r : records) {
            if (r == null || !isOAuthAuthMode(r.getAuthMode())) continue;
            String current = decryptOrNull(r.getId(), "oauth access token", r.getOauthAccessEncrypted());
            if (current == null || !current.equals(accessToken)) continue;
            return refreshOAuthCredential(r);
        }
        return null;
    }

    /** 无条件用 refresh token 换新凭据并回写；任何失败 → warn + null（⛔ 不抛）。 */
    private String refreshOAuthCredential(ProviderRecord r) {
        String providerId = r.getId();
        OAuthRefresher refresher = refresherFor(r.getAuthMode());
        if (refresher == null) {
            log.warn("Provider {} 需要强制刷新 OAuth 凭据，但 OAuthRefresher 未接线 ⇒ 无法刷新", providerId);
            return null;
        }
        String refreshToken = decryptOrNull(providerId, "oauth refresh token", r.getOauthRefreshEncrypted());
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Provider {} 的 OAuth access 已失效且无 refresh token", providerId);
            return null;
        }
        OAuthRefresher.Refreshed refreshed;
        try {
            refreshed = refresher.refresh(refreshToken);
        } catch (Exception e) {
            log.warn("Provider {} 的 OAuth 强制刷新抛异常: {}", providerId, e.getMessage());
            return null;
        }
        if (refreshed == null || refreshed.access() == null || refreshed.access().isBlank()) {
            log.warn("Provider {} 的 OAuth 强制刷新未返回可用 access token", providerId);
            return null;
        }
        // refresh token 会轮换：上游给了新的就用新的，没给就沿用旧的；accountId 同理
        String newRefresh = (refreshed.refresh() != null && !refreshed.refresh().isBlank())
            ? refreshed.refresh() : refreshToken;
        String newAccountId = refreshed.accountId() != null
            ? refreshed.accountId() : r.getOauthAccountId();
        try {
            // ⭐ 保留本 provider 原有的 auth_mode（刷新不能把 claude_oauth 改回 codex_oauth）
            saveOAuthCredential(providerId, r.getAuthMode(), refreshed.access(), newRefresh,
                refreshed.expiresAt(), newAccountId);
        } catch (Exception e) {
            // 回写失败不该让本轮重试失败：新 access 已在手里
            log.warn("Provider {} 的 OAuth 强制刷新结果回写失败（本轮仍用新 access）: {}", providerId, e.getMessage());
        }
        return refreshed.access();
    }

    /** 解密列值；空 / 解密失败 → null（失败只 warn，不回显任何明文或密文）。 */
    private String decryptOrNull(String providerId, String fieldLabel, String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            return cryptoUtil.decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("Provider {} 凭据解密失败（{}）: {}", providerId, fieldLabel, e.getMessage());
            return null;
        }
    }

    /**
     * 写入 OAuth 凭据（登录成功 / 刷新成功时调用，供登录端点使用）。
     *
     * <p>四个凭据列一律经 {@link CryptoUtil} 加密后再落库；同时把 auth_mode 切到
     * {@code codex_oauth}。允许 {@code refresh} / {@code expiresAt} / {@code accountId} 为 null。
     *
     * <p>⚠️ 需要指定 auth_mode（如 {@code claude_oauth}）时用
     * {@link #saveOAuthCredential(String, String, String, String, Long, String)} 重载。
     *
     * @param providerId provider id（不存在 → {@link NotFoundException}）
     */
    public void saveOAuthCredential(String providerId, String access, String refresh,
                                    Long expiresAt, String accountId) {
        saveOAuthCredential(providerId, AUTH_MODE_CODEX_OAUTH, access, refresh, expiresAt, accountId);
    }

    /**
     * 写入 OAuth 凭据并把 auth_mode 切到指定形态（Codex = {@code codex_oauth} / Claude =
     * {@code claude_oauth}）。
     *
     * @param authMode 凭据形态；null / 空 → 回落 {@code codex_oauth}（既有调用方语义不变）
     */
    public void saveOAuthCredential(String providerId, String authMode, String access, String refresh,
                                    Long expiresAt, String accountId) {
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) throw new NotFoundException("Provider " + providerId + " not found");
        r.setAuthMode(authMode == null || authMode.isBlank() ? AUTH_MODE_CODEX_OAUTH : authMode);
        r.setOauthAccessEncrypted(cryptoUtil.encrypt(access));
        r.setOauthRefreshEncrypted(cryptoUtil.encrypt(refresh));
        r.setOauthExpiresAt(expiresAt);
        r.setOauthAccountId(accountId);
        r.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        // update(entity, false) 显式写 NULL：refresh 为空时必须真落 NULL，而不是被
        //   MyBatis-Flex 的 $$ignoreNulls 跳过（同 update() 里 extra_headers 清空的先例）。
        //   安全性：r 由 selectOneById 全字段回读，其余列按原值写回，不会误伤。
        providerMapper.update(r, false);
        log.info("Provider {} 的 OAuth 凭据已更新（authMode={}，accountId={}，expiresAt={}）",
            providerId, r.getAuthMode(), accountId, expiresAt);
    }

    /**
     * 清除 OAuth 凭据并回落到 api_key 模式（退出登录）。
     *
     * <p>清 4 列（access / refresh / expiresAt / accountId）+ auth_mode='api_key'；
     * oauth_state 不在这里动 —— 它属于登录在途状态，由登录流程自行清。
     */
    public void clearOAuth(String providerId) {
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) throw new NotFoundException("Provider " + providerId + " not found");
        r.setAuthMode(AUTH_MODE_API_KEY);
        r.setOauthAccessEncrypted(null);
        r.setOauthRefreshEncrypted(null);
        r.setOauthExpiresAt(null);
        r.setOauthAccountId(null);
        r.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        // 必须 ignoreNulls=false 才能把上面 4 列真写成 NULL
        providerMapper.update(r, false);
        log.info("Provider {} 的 OAuth 凭据已清除，回落到 api_key 模式", providerId);
    }

    /**
     * 读登录流程的 state（CSRF 一次性随机串）。
     *
     * @return state；provider 不存在或无 state → null
     */
    public String readOAuthState(String providerId) {
        if (providerId == null) return null;
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) return null;
        return r.getOauthState();
    }

    /**
     * 写登录流程的 state（传 null 即清除）。
     *
     * @param providerId provider id（不存在 → {@link NotFoundException}）
     */
    public void writeOAuthState(String providerId, String state) {
        ProviderRecord r = providerMapper.selectOneById(providerId);
        if (r == null) throw new NotFoundException("Provider " + providerId + " not found");
        r.setOauthState(state);
        // ignoreNulls=false：传 null 时要真清掉，否则 state 残留（一次性串被复用）
        providerMapper.update(r, false);
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
    /**
     * [保密 2026-11-22 B4] provider.apiKey 写侧哨兵（包级可见，便于单测）。
     *
     * <p>两个显式判据：
     * <ol>
     *   <li>入参含 {@link ApiKeyHasher#MASK_TOKEN}（掩码必然含，真 key 不含），或逐字等于
     *       库里 {@code api_key_masked}；</li>
     *   <li>{@link ApiKeyHasher#isMaskedSentinel} 对「当前密文的解密明文」再判一次
     *       （精确形态，防掩码格式变化后漏判）。</li>
     * </ol>
     *
     * <p>⛔ 解密失败 / OAuth 模式 / 无密文时只看第 1 条 —— 不让一次解密失败阻断保存。
     */
    boolean isMaskedApiKeyEcho(String incoming, Provider p) {
        if (incoming == null || p == null) return false;
        String storedMasked = p.getApiKeyMasked();
        if (storedMasked != null && !storedMasked.isBlank() && incoming.equals(storedMasked)) {
            return true;
        }
        return ApiKeyHasher.isMaskedSentinel(incoming, decryptApiKeyPlain(p));
    }

    /** 解密「当前」apiKey 明文（仅用于哨兵比对；失败/OAuth/无密文 → null，不抛不写日志内容）。 */
    private String decryptApiKeyPlain(Provider p) {
        if (cryptoUtil == null || p.getApiKeyEncrypted() == null || p.getApiKeyEncrypted().isBlank()) {
            return null;
        }
        String mode = p.getAuthMode();
        if (AUTH_MODE_CODEX_OAUTH.equals(mode) || AUTH_MODE_CLAUDE_OAUTH.equals(mode)) {
            return null;
        }
        try {
            return cryptoUtil.decrypt(p.getApiKeyEncrypted());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[ProviderService] 当前 apiKey 解密失败，掩码哨兵回落 storedMasked: {}", e.toString());
            }
            return null;
        }
    }

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
            // [保密 2026-11-22] 敏感头（*KEY/*TOKEN/*SECRET/*PASSWORD/Authorization）只回末四位掩码；
            //   明文仍留在 DB 供 ProviderHeaderInjector 注入真实请求（它读 Provider.extraHeaders，不经 DTO）。
            ApiKeyHasher.maskSensitiveMap(deserializeHeaders(p.getExtraHeaders())),
            Boolean.TRUE.equals(p.getEnabled()), models,
            parseDateTime(p.getCreatedAt()), parseDateTime(p.getUpdatedAt()),
            // [V296] 只暴露「凭据形态」这类非敏感元数据；access/refresh/state 绝不出 DTO
            p.getAuthMode() == null ? AUTH_MODE_API_KEY : p.getAuthMode(),
            p.getOauthAccountId(), p.getOauthExpiresAt(), p.getBrandKey());
    }

    /**
     * 单个模型 → DTO。
     *
     * @param providerName 所属提供商显示名（调用方已知 ⇒ 透传，避免多查一次库）
     */
    private ModelDto toModelDto(Model m, String providerName) {
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
            m.getMaxConcurrency(),
            m.getRerankTopN(),
            m.getRerankInstruct(),
            // ⭐ 2026-11-11：带上提供商，前端模型下拉显示成 `provider/modelName`。
            //    本方法在 toProviderDto 内被调用，调用方手里**就有** provider（p），
            //    直接透传即可，⛔ 不要为此再查一次库。
            m.getProviderId(), providerName);
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
