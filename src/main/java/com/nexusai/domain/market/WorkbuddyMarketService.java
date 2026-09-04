package com.nexusai.domain.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.market.dto.MarketConnectorDto;
import com.nexusai.model.market.dto.MarketExpertDto;
import com.nexusai.model.market.dto.MarketSkillDto;
import com.nexusai.model.market.dto.MarketUseResponse;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯 workbuddy「技能市场」后端代理服务（本地 market_sources.tencent_workbuddy 凭证代调）。
 *
 * <p><b>职责</b>：
 * <ul>
 *   <li>从本地配置读取腾讯凭证（configHome 优先，见 {@link NexusaiPaths#getAppConfigHomePath()}，
 *       兜底绝对路径），构造带 cookie/x-user-id/user-agent/referer/origin/accept 的 HTTP 请求代调腾讯</li>
 *   <li>代理三接口：专家 expert/list、技能 skill/list（POST）、连接器 registry2c/list（GET）</li>
 *   <li>本地缓存 + 控频（腾讯风控极严，短时连续请求 401 实测）：三列表结果缓存 TTL 5 分钟 +
 *       相邻真实调用最小间隔（单飞合并并发重复请求）</li>
 *   <li>{@link #useExpert} 真闭环：把远端专家构造成本地 CustomAgentDefinition 并入会话 agent
 *       registry（SubagentTool.registryForSession.merge），再设 sessions.main_thread_agent</li>
 * </ul>
 *
 * <p><b>fail-loud</b>：凭证缺失 / HTTP 非 200 / 401 / 非 JSON → {@link BadGatewayException}
 * （502，中文错误），绝不静默降级。
 *
 * <p>三接口实测（200）返回结构：
 * <pre>
 *   专家 POST /portal/operation-platform/market/expert/list
 *        body {"page":1,"page_size":N,"sort_by":"reco_rank","sort_order":"desc"} → data.experts[]
 *   技能 POST /portal/operation-platform/market/skill/list
 *        body {"page":1,"page_size":N} → data.skills[]
 *   连接器 GET /console/as/connector/registry2c/list?scope=all → data.list[]
 * </pre>
 */
@Service
public class WorkbuddyMarketService {
    private static final Logger log = LoggerFactory.getLogger(WorkbuddyMarketService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 列表本地缓存 TTL：5 分钟（腾讯风控，缓存三列表结果防限流）。 */
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    /** 相邻两次真实腾讯 HTTP 调用的最小间隔：控频兜底（风控极严，短时连续请求 401）。 */
    private static final long TENCENT_MIN_GAP_MS = 500L;
    /** 「使用」时回查专家的单页拉取量：尽量一次拉全减少腾讯调用（风控友好）。 */
    private static final int EXPERT_LOOKUP_PAGE_SIZE = 500;
    /** 分页页大小上限：避免参数失控放大腾讯请求量。 */
    private static final int PAGE_SIZE_MAX = 500;
    /** cookie 配置文件兜底绝对路径（configHome 未找到时）。 */
    private static final String FALLBACK_COOKIE_FILE = "C:/Users/WIN/.nexusai/workbuddy-cookie.json";

    private static final String UA_CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String PATH_EXPERT_LIST = "/portal/operation-platform/market/expert/list";
    private static final String PATH_SKILL_LIST = "/portal/operation-platform/market/skill/list";
    private static final String PATH_CONNECTOR_LIST = "/console/as/connector/registry2c/list?scope=all";

    @Autowired
    @Lazy
    private SubagentTool subagentTool;
    @Autowired private SessionService sessionService;

    /** 本地缓存：cacheKey → (响应根 JsonNode, 抓取时间戳)。 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** 真实腾讯调用串行化锁：单飞合并并发重复请求 + 控频互斥（防风控 401）。 */
    private final Object fetchLock = new Object();
    /** 最近一次真实腾讯调用时间（控频判定）。 */
    private volatile long lastTencentCallMs = 0L;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    /** 本地缓存条目。 */
    private record CacheEntry(JsonNode root, long fetchedAt) {}

    /** 腾讯 workbuddy 登录凭证（workbuddy-cookie.json market_sources.tencent_workbuddy）。 */
    private record TencentAuth(String base, String cookie, String userId, String referer) {}

    // ────────────────────────────────────────────────────────────────────────
    // 对外：三列表代理 + expert use 闭环
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 代调腾讯专家列表（GET /api/market/expert）。
     *
     * @param page     页码（从 1 起；越界自动钳到 [1, +∞)）
     * @param pageSize 每页条数（自动钳到 [1, 500]）
     * @return 统一 DTO 列表（远端专家，remote=true）
     */
    public List<MarketExpertDto> listExperts(int page, int pageSize) {
        page = Math.max(1, page);
        pageSize = Math.min(PAGE_SIZE_MAX, Math.max(1, pageSize));
        JsonNode root = fetchExpertsRoot(page, pageSize);
        List<MarketExpertDto> out = new ArrayList<>();
        for (JsonNode n : nodeList(root, "experts")) {
            MarketExpertDto dto = mapExpert(n);
            if (dto.marketId() != null && !dto.marketId().isBlank()) {
                out.add(dto);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 专家列表 page={} page_size={} → {} 条（腾讯代调，缓存 5 分钟）",
                page, pageSize, out.size());
        }
        return out;
    }

    /**
     * 代调腾讯技能列表（GET /api/market/skill）。
     *
     * @param page     页码（从 1 起；越界自动钳到 [1, +∞)）
     * @param pageSize 每页条数（自动钳到 [1, 500]）
     * @return 统一 DTO 列表（远端技能，remote=true）
     */
    public List<MarketSkillDto> listSkills(int page, int pageSize) {
        page = Math.max(1, page);
        pageSize = Math.min(PAGE_SIZE_MAX, Math.max(1, pageSize));
        JsonNode root = fetchSkillsRoot(page, pageSize);
        List<MarketSkillDto> out = new ArrayList<>();
        for (JsonNode n : nodeList(root, "skills")) {
            MarketSkillDto dto = mapSkill(n);
            if (dto.marketId() != null && !dto.marketId().isBlank()) {
                out.add(dto);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 技能列表 page={} page_size={} → {} 条（腾讯代调，缓存 5 分钟）",
                page, pageSize, out.size());
        }
        return out;
    }

    /**
     * 代调腾讯连接器列表（GET /api/market/connector）。
     *
     * @return 统一 DTO 列表（远端连接器，remote=true）
     */
    public List<MarketConnectorDto> listConnectors() {
        JsonNode root = cachedOrFetch("connector:all", () -> {
            TencentAuth auth = readAuth();
            return execute(newRequest(auth, PATH_CONNECTOR_LIST, null), "连接器列表");
        });
        List<MarketConnectorDto> out = new ArrayList<>();
        for (JsonNode n : nodeList(root, "list")) {
            MarketConnectorDto dto = mapConnector(n);
            if (dto.marketId() != null && !dto.marketId().isBlank()) {
                out.add(dto);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 连接器列表 → {} 条（腾讯代调，缓存 5 分钟）", out.size());
        }
        return out;
    }

    /**
     * 使用某腾讯专家 = 真闭环：
     * <ol>
     *   <li>从专家列表取该 expert（含 agent_name/description_zh/quick_prompts 等构造素材）</li>
     *   <li>构造本地 {@link AgentDefinition.CustomAgentDefinition}（agentType=wb-前缀防冲突；
     *       whenToUse=description_zh 首行；systemPromptContent=简介+开场建议换行拼接）</li>
     *   <li>并入会话 agent registry：{@code subagentTool.registryForSession(sessionId).merge([agent])}</li>
     *   <li>设会话主线程 agent：{@code SessionService.update(sessionId, mainThreadAgent=agentType)}
     *       —— 顺序必须先 merge 再 update，使 update 内 registry.findAgent 校验命中</li>
     * </ol>
     *
     * @param marketId  腾讯 expert_id
     * @param sessionId 目标会话 ID（sess-xxx）
     * @return 已注册并设为主线程的 agentType + 展示名
     */
    public MarketUseResponse useExpert(String marketId, String sessionId) {
        if (marketId == null || marketId.isBlank()) {
            throw new ValidationException("marketId 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("sessionId 不能为空");
        }
        // 先校验会话存在（SessionService.getById 缺失 → 404），避免对不存在的会话做无谓腾讯调用（控频）
        sessionService.getById(sessionId);
        JsonNode expert = findExpertNode(marketId);
        String displayName = text(expert.get("display_name_zh"));

        AgentDefinition agent = buildMarketAgent(expert);
        if (subagentTool == null) {
            log.error("[WorkbuddyMarket] useExpert: SubagentTool 未注入，无法注册会话 agent marketId={} sessionId={}",
                marketId, sessionId);
            throw new BadGatewayException("专家注册服务暂不可用（agent 注册组件未就绪）");
        }
        AgentDefinitionRegistry registry = subagentTool.registryForSession(sessionId);
        registry.merge(List.of(agent));
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] useExpert: 专家已并入会话 agent registry sessionId={} agentType={} displayName={}（merge 后 registry 命中 {}）",
                sessionId, agent.agentType(), displayName, registry.findAgent(agent.agentType()) != null);
        }

        // 顺序关键：先 merge 注册再 update（SessionService.update 内部经 registry.findAgent 校验，
        // 未 merge 则 400「agent 类型不存在」）
        SessionUpdateRequest updateReq = new SessionUpdateRequest(
            null, null, null, null, null, null, agent.agentType());
        sessionService.update(sessionId, updateReq);
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] useExpert 完成: sessionId={} 已设主线程 agent={}（wb- 前缀防本地冲突）",
                sessionId, agent.agentType());
        }
        return new MarketUseResponse(agent.agentType(), displayName);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 腾讯代调 + 缓存 + 控频
    // ────────────────────────────────────────────────────────────────────────

    /** 拉取专家列表原始响应（带 5 分钟缓存 + 单飞控频）。 */
    private JsonNode fetchExpertsRoot(int page, int pageSize) {
        String key = "expert:" + page + ":" + pageSize;
        return cachedOrFetch(key, () -> {
            String body = "{\"page\":" + page + ",\"page_size\":" + pageSize
                + ",\"sort_by\":\"reco_rank\",\"sort_order\":\"desc\"}";
            TencentAuth auth = readAuth();
            return execute(newRequest(auth, PATH_EXPERT_LIST, body), "专家列表");
        });
    }

    /** 拉取技能列表原始响应（带 5 分钟缓存 + 单飞控频）。 */
    private JsonNode fetchSkillsRoot(int page, int pageSize) {
        String key = "skill:" + page + ":" + pageSize;
        return cachedOrFetch(key, () -> {
            String body = "{\"page\":" + page + ",\"page_size\":" + pageSize + "}";
            TencentAuth auth = readAuth();
            return execute(newRequest(auth, PATH_SKILL_LIST, body), "技能列表");
        });
    }

    /**
     * 缓存读/写（单飞合并并发重复请求 + 控频）。
     *
     * <p>命中且未过期 → 直接返回缓存；未命中/过期 → 在 {@link #fetchLock} 内双检后执行真实
     * 腾讯调用（先 {@link #rateGateWait()} 控频）并回填缓存。同一锁保证并发的相同/不同 key
     * 请求在真实调用层被串行化——不同 key 至少间隔 {@link #TENCENT_MIN_GAP_MS}，同 key 只发一次。
     *
     * @param cacheKey 缓存键（含列表类型 + 分页，标识唯一远端响应）
     * @param fetcher  真实腾讯调用（抛 {@link BadGatewayException} 表示上游不可用）
     * @return 腾讯响应根 JsonNode
     */
    private JsonNode cachedOrFetch(String cacheKey, java.util.function.Supplier<JsonNode> fetcher) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && now - entry.fetchedAt() < CACHE_TTL_MS) {
            if (log.isDebugEnabled()) {
                log.debug("[WorkbuddyMarket] 缓存命中 {}（TTL 5 分钟，未触腾讯）", cacheKey);
            }
            return entry.root();
        }
        synchronized (fetchLock) {
            entry = cache.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.fetchedAt() < CACHE_TTL_MS) {
                return entry.root();
            }
            rateGateWait();
            JsonNode root = fetcher.get();
            putCache(cacheKey, root);
            if (log.isDebugEnabled()) {
                log.debug("[WorkbuddyMarket] 已回填缓存 {}（腾讯代调成功）", cacheKey);
            }
            return root;
        }
    }

    /**
     * 回填缓存 + 防无界增长守卫（仅在 {@link #fetchLock} 内调用）：
     * 条目数超阈值先清已过期条目（TTL），仍满则任意驱逐至阈值以下（分页键理论有限，守卫仅兜底异常分页）。
     */
    private void putCache(String cacheKey, JsonNode root) {
        if (cache.size() >= 256) {
            int before = cache.size();
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> now - e.getValue().fetchedAt() >= CACHE_TTL_MS);
            int excess = cache.size() - 128;
            if (excess > 0) {
                int toRemove = excess;
                for (Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
                        it.hasNext() && toRemove > 0; ) {
                    it.next();
                    it.remove();
                    toRemove--;
                }
            }
            if (log.isWarnEnabled()) {
                log.warn("[WorkbuddyMarket] 市场列表缓存超阈值，已驱逐 {} 条（过期/最旧，防无界增长）",
                    before - cache.size());
            }
        }
        cache.put(cacheKey, new CacheEntry(root, System.currentTimeMillis()));
    }

    /** 控频：距上次真实腾讯调用不足最小间隔则等待（在 {@link #fetchLock} 内调用，串行化）。 */
    private void rateGateWait() {
        long now = System.currentTimeMillis();
        long wait = TENCENT_MIN_GAP_MS - (now - lastTencentCallMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastTencentCallMs = System.currentTimeMillis();
    }

    /** 构造腾讯 HTTP 请求（headers 对齐腾讯校验：cookie/x-user-id/user-agent/referer/origin/accept）。 */
    private HttpRequest newRequest(TencentAuth auth, String path, String body) {
        String base = auth.base();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        URI uri = URI.create(base + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20));
        String referer = (auth.referer() == null || auth.referer().isBlank())
            ? stripTrailingSlash(base) + "/app/connectors" : auth.referer();
        Map<String, String> headerMap = headers(auth, referer);
        headerMap.forEach(b::header);
        if (body == null) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json;charset=UTF-8");
            b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return b.build();
    }

    private static Map<String, String> headers(TencentAuth auth, String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cookie", auth.cookie());
        h.put("x-user-id", auth.userId());
        h.put("User-Agent", UA_CHROME);
        h.put("Referer", referer);
        h.put("Origin", stripTrailingSlash(auth.base()));
        h.put("Accept", "application/json, text/plain, */*");
        return h;
    }

    /**
     * 发送腾讯 HTTP 请求并校验响应。
     *
     * @param req   已构造请求
     * @param label 中文调用标签（日志）
     * @return 200 响应体解析后的根 JsonNode
     * @throws BadGatewayException 网络异常 / 401 / 非 200 / 非 JSON（fail-loud，502）
     */
    private JsonNode execute(HttpRequest req, String label) {
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 真实代调腾讯 {} url={}（含控频串行）", label, req.uri());
        }
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("[WorkbuddyMarket] 代调腾讯 {} 网络异常: {}", label, e.toString());
            throw new BadGatewayException("腾讯市场接口暂不可用（网络异常：" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[WorkbuddyMarket] 代调腾讯 {} 被中断: {}", label, e.toString());
            throw new BadGatewayException("腾讯市场接口暂不可用（请求被中断）", e);
        }
        int sc = resp.statusCode();
        if (sc == 401) {
            log.error("[WorkbuddyMarket] 代调腾讯 {} 401（凭证过期或限流，需重新登录更新 workbuddy-cookie.json）",
                label);
            throw new BadGatewayException("腾讯市场接口暂不可用（凭证过期或限流）");
        }
        if (sc != 200) {
            log.error("[WorkbuddyMarket] 代调腾讯 {} 失败 status={} body 前 200 字={}",
                label, sc, truncate(resp.body(), 200));
            throw new BadGatewayException("腾讯市场接口暂不可用（HTTP " + sc + "）");
        }
        try {
            return JSON.readTree(resp.body());
        } catch (Exception e) {
            log.error("[WorkbuddyMarket] 代调腾讯 {} 返回非 JSON: {}", label, e.toString());
            throw new BadGatewayException("腾讯市场接口返回异常（非 JSON 结构）", e);
        }
    }

    /**
     * 读腾讯登录凭证（本地 workbuddy-cookie.json）。
     *
     * <p>configHome（{@link NexusaiPaths#getAppConfigHomePath()}）优先，未找到回落
     * {@link #FALLBACK_COOKIE_FILE}；均不存在/解析失败/关键字段缺失 → {@link BadGatewayException}（fail-loud）。
     */
    private TencentAuth readAuth() {
        Path path = cookieFile();
        if (path == null) {
            log.error("[WorkbuddyMarket] 未找到腾讯凭证文件 workbuddy-cookie.json（configHome={} 或 {}）",
                NexusaiPaths.getAppConfigHomePath().resolve("workbuddy-cookie.json"), FALLBACK_COOKIE_FILE);
            throw new BadGatewayException("腾讯市场凭证缺失，请先登录并写入 workbuddy-cookie.json");
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[WorkbuddyMarket] 读取腾讯凭证失败 path={}: {}", path, e.toString());
            throw new BadGatewayException("腾讯市场凭证读取失败：" + path, e);
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (Exception e) {
            log.error("[WorkbuddyMarket] 腾讯凭证 JSON 解析失败 path={}: {}", path, e.toString());
            throw new BadGatewayException("腾讯市场凭证格式错误（workbuddy-cookie.json 非法 JSON）", e);
        }
        JsonNode src = root.path("market_sources").path("tencent_workbuddy");
        if (!src.isObject()) {
            log.error("[WorkbuddyMarket] 腾讯凭证结构异常（缺 market_sources.tencent_workbuddy）path={}", path);
            throw new BadGatewayException("腾讯市场凭证结构异常（缺 market_sources.tencent_workbuddy）");
        }
        String base = text(src.get("base"));
        String cookie = text(src.get("cookie"));
        String userId = text(src.get("userId"));
        String referer = text(src.get("referer"));
        if (base == null || base.isBlank() || cookie == null || cookie.isBlank()
                || userId == null || userId.isBlank()) {
            log.error("[WorkbuddyMarket] 腾讯凭证缺关键字段 path={}（需 base/cookie/userId）", path);
            throw new BadGatewayException("腾讯市场凭证缺关键字段（需 base/cookie/userId），请重新登录");
        }
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 腾讯凭证读取成功 path={} base={} userId={}", path, base, userId);
        }
        return new TencentAuth(stripTrailingSlash(base), cookie, userId, referer);
    }

    /** 定位 cookie 配置文件：configHome 优先，回落绝对路径。 */
    private Path cookieFile() {
        Path home = NexusaiPaths.getAppConfigHomePath().resolve("workbuddy-cookie.json");
        if (Files.isRegularFile(home)) {
            return home;
        }
        Path fallback = Path.of(FALLBACK_COOKIE_FILE);
        if (Files.isRegularFile(fallback)) {
            return fallback;
        }
        return null;
    }

    /**
     * 按 expert_id 定位专家节点。
     *
     * <p>先扫缓存内已有专家页（浏览阶段已缓存则零腾讯调用）；未命中再拉一次大页
     * （{@link #EXPERT_LOOKUP_PAGE_SIZE}，风控友好）并缓存，扫该页。两轮均无 → 404。
     */
    private JsonNode findExpertNode(String marketId) {
        for (CacheEntry e : cache.values()) {
            JsonNode found = scanExperts(e.root(), marketId);
            if (found != null) {
                return found;
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[WorkbuddyMarket] 专家 {} 不在已缓存页，拉取大页 page_size={} 回查（腾讯调用 1 次）",
                marketId, EXPERT_LOOKUP_PAGE_SIZE);
        }
        JsonNode root = fetchExpertsRoot(1, EXPERT_LOOKUP_PAGE_SIZE);
        JsonNode found = scanExperts(root, marketId);
        if (found == null) {
            log.warn("[WorkbuddyMarket] 腾讯市场未找到专家 marketId={}", marketId);
            throw new NotFoundException("腾讯市场中不存在该专家：" + marketId);
        }
        return found;
    }

    private static JsonNode scanExperts(JsonNode root, String marketId) {
        for (JsonNode n : nodeList(root, "experts")) {
            if (marketId.equals(text(n.get("expert_id")))) {
                return n;
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 专家 → 本地 agent 构造（真闭环核心）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 腾讯专家 JSON → 本地 {@link AgentDefinition.CustomAgentDefinition}。
     *
     * <p>source 用 "userSettings"（对齐 CC source 值域内、merge 折叠档位 user 组；wb- 前缀已防
     * 与磁盘 user/project/flag 同名冲突，档位优先级实际不影响命中）。
     */
    private AgentDefinition buildMarketAgent(JsonNode expert) {
        String agentName = text(expert.get("agent_name"));
        if (agentName == null || agentName.isBlank()) {
            throw new BadGatewayException("腾讯专家数据缺 agent_name，无法注册");
        }
        String agentType = "wb-" + agentName.trim().replaceAll("\\s+", " ");
        String description = text(expert.get("description_zh"));
        String whenToUse = firstNonBlankLine(description);
        if (whenToUse == null) {
            List<String> prompts = strList(expert.get("quick_prompts"));
            whenToUse = prompts.isEmpty() ? agentName : prompts.get(0);
        }
        String systemPromptContent = buildSystemPrompt(description,
            strList(expert.get("quick_prompts")));
        if (log.isDebugEnabled()) {
            log.debug("[WorkbuddyMarket] 构造市场专家 agent agentType={} whenToUse={}（{} 字系统提示）",
                agentType, truncate(whenToUse, 60), systemPromptContent.length());
        }
        return AgentDefinition.CustomAgentDefinition.builder(
                agentType, whenToUse, "userSettings", systemPromptContent)
            .build();
    }

    /** 简介 + 开场建议换行拼接为系统提示内容（skeleton：其余字段默认）。 */
    private static String buildSystemPrompt(String description, List<String> quickPrompts) {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isBlank()) {
            sb.append(description);
        }
        if (!quickPrompts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("开场建议/可尝试的问题：\n");
            for (String p : quickPrompts) {
                if (p != null && !p.isBlank()) {
                    sb.append("- ").append(p.trim()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // DTO 映射
    // ────────────────────────────────────────────────────────────────────────

    private static MarketExpertDto mapExpert(JsonNode n) {
        int useCount = intValue(n.get("use_count"));
        return new MarketExpertDto(
            text(n.get("expert_id")),
            text(n.get("agent_name")),
            text(n.get("display_name_zh")),
            text(n.get("icon")),
            text(n.get("profession_zh")),
            text(n.get("description_zh")),
            strList(n.get("tags_zh")),
            strList(n.get("categories")),
            useCount,
            formatUseCount(useCount),
            boolValue(n.get("preinstalled")),
            boolValue(n.get("featured")),
            true);
    }

    private static MarketSkillDto mapSkill(JsonNode n) {
        return new MarketSkillDto(
            text(n.get("skill_id")),
            text(n.get("name")),
            text(n.get("display_name_zh")),
            text(n.get("icon")),
            text(n.get("description_zh")),
            strList(n.get("categories")),
            strList(n.get("examples_zh")),
            boolValue(n.get("preinstalled")),
            true);
    }

    private static MarketConnectorDto mapConnector(JsonNode n) {
        String name = text(n.get("name"));
        return new MarketConnectorDto(
            name,                       // 腾讯连接器无独立 id，以 name 作 marketId
            name,
            text(n.get("scope")),
            text(n.get("status")),
            text(n.get("auth_type")),
            boolValue(n.get("is_connected")),
            true);
    }

    // ────────────────────────────────────────────────────────────────────────
    // JsonNode 小工具
    // ────────────────────────────────────────────────────────────────────────

    /** 提取响应列表数组：兼容 {data:{field:[...]}} 与 {field:[...]} 两层；缺失/非数组 → 空列表 + warn。 */
    private static List<JsonNode> nodeList(JsonNode root, String field) {
        JsonNode data = root == null ? null : root.path("data");
        JsonNode arr = null;
        if (data != null && data.isObject() && data.has(field)) {
            arr = data.get(field);
        } else if (root != null && root.has(field)) {
            arr = root.get(field);
        }
        if (arr == null || !arr.isArray()) {
            log.warn("[WorkbuddyMarket] 响应结构异常（未找到 data.{} 数组），按空列表处理 root={}",
                field, root == null ? "null" : root.toString().length() > 200 ? truncate(root.toString(), 200) : root.toString());
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n != null && !n.isNull() && n.isObject()) {
                out.add(n);
            }
        }
        return out;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()) {
            return null;
        }
        String s = node.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> strList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (n != null && !n.isNull() && n.isValueNode()) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static int intValue(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() || !node.isNumber()
            ? 0 : node.asInt(0);
    }

    private static boolean boolValue(JsonNode node) {
        return node != null && node.isBoolean() && node.asBoolean();
    }

    /** 使用次数展示：≥1万 → "N.N万"/"N万"，否则原整数（如 118234 → "11.8万"）。 */
    private static String formatUseCount(int useCount) {
        if (useCount >= 10000) {
            double w = useCount / 10000.0;
            if (w == Math.floor(w)) {
                return (long) w + "万";
            }
            return String.format(Locale.ROOT, "%.1f万", w);
        }
        return Integer.toString(useCount);
    }

    /** 文本首个非空行（\n 分隔）。 */
    private static String firstNonBlankLine(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
