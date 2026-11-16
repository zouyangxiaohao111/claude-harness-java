package com.nexusai.apis.settings;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.kb.app.ingest.IngestConcurrencyConfig;
import com.nexusai.kb.app.ingest.KbIngestPoolProperties;
import com.nexusai.kb.app.ingest.KbIngestWorker;
import com.nexusai.model.settings.dto.ModelTierRole;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Settings REST 端点（singleton） */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    @Autowired private SettingsService settingsService;

    /** 知识库域配置（R0 · V273）：检索驱动/默认存储后端/记忆开关都在这张独立表里。 */
    @Autowired private com.nexusai.kb.config.KbConfigService kbConfigService;

    /** 检索驱动注册表（W1）：驱动清单来自 Spring 容器，⛔ 前端不硬编码。 */
    @Autowired private com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry;

    /** 入库六池并发的 yml 侧配置（DB 未配置时的回落值 · V293）。 */
    @Autowired private KbIngestPoolProperties ingestPoolProperties;

    /** 入库 worker · 热调必须落在它持有的那一个池实例上（⛔ 不要 new Worker / new Pools）。 */
    @Autowired private KbIngestWorker ingestWorker;

    /** GET：响应 = {@link SettingsResponse}（内层 SettingsDto 摊平 + appName/configHome 两个只读键）。
     *  ⛔ 这两个键只在这里出站：PUT 的入参/出参仍是 {@link SettingsDto} ⇒ 结构上写不动（见 SettingsResponse javadoc）。 */
    @GetMapping
    public SettingsResponse get() {
        return new SettingsResponse(
            settingsService.get(),
            NexusaiPaths.getAppName(),
            NexusaiPaths.getAppConfigHomeDir());
    }

    @PutMapping
    public SettingsDto update(@RequestBody SettingsDto req) {
        return settingsService.update(req);
    }

    /** [V295] MCP 启动预取总闸的 yml 兜底值（DB 从未配置时生效）。 */
    @org.springframework.beans.factory.annotation.Value("${nexusai.mcp.prefetch-on-startup:true}")
    private boolean mcpPrefetchYmlDefault;

    /** [V295] 总闸响应：{@code source=db|yml} 让前端区分「用户设置」与「系统默认」。 */
    public record McpPrefetchResponse(boolean enabled, String source) {}

    /**
     * GET MCP 启动预取总闸（V295）。
     *
     * @return 生效值 + 来源（db = 用户设置过；yml = 从未配置，回落默认）
     */
    @GetMapping("/mcp-prefetch")
    public McpPrefetchResponse getMcpPrefetch() {
        Boolean db = settingsService.readMcpPrefetchOnStartup();
        return new McpPrefetchResponse(
            db != null ? db : mcpPrefetchYmlDefault,
            db != null ? "db" : "yml");
    }

    /**
     * PUT MCP 启动预取总闸（V295）· 落 settings 表，下一次应用启动生效。
     *
     * <p>⛔ 不热连：运行中的连接不重建；关闭后已连接的 server 保持连接，直到手动 stop。
     *
     * @param body {@code {"enabled": true|false}}
     * @return 写入后的值（source=db）
     */
    @PutMapping("/mcp-prefetch")
    public McpPrefetchResponse updateMcpPrefetch(@RequestBody java.util.Map<String, Object> body) {
        Object v = body == null ? null : body.get("enabled");
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("enabled (boolean) required");
        }
        settingsService.updateMcpPrefetchOnStartup(b);
        return new McpPrefetchResponse(b, "db");
    }

    /**
     * [V298] GET LLM 出站 HTTP 代理（ChatGPT 登录 + 对话专用）。
     *
     * <p>⛔ 与 WebSearch / WebFetch 的代理是两条通道：那边读 {@code settings.proxy}，
     * 本端点只读写 {@code settings.http_proxy}，互不影响。
     *
     * @return {@code {"proxy":"http://127.0.0.1:7897"}}；未配置返回 {@code proxy: null}
     */
    @GetMapping("/http-proxy")
    public HttpProxyResponse getHttpProxy() {
        return new HttpProxyResponse(settingsService.readDbHttpProxy());
    }

    /**
     * [V298] PUT LLM 出站 HTTP 代理 · 落 settings 表，<b>下一次请求即生效</b>（不重启）。
     *
     * <p><b>三态</b>：{@code null} = 未配置（出站回落环境变量/直连）；空串 = 用户显式选择直连
     * （压过环境变量，`OutboundProxy` 不再回落）；非空 = 该代理。
     * 格式校验在前端（「试一下」）做，这里只做归一化（trim）；非法格式交给
     * {@code OutboundProxy.parse} 记 warn 并当直连，不阻断保存。
     *
     * @param body {@code {"proxy":"http://127.0.0.1:7897"}}
     * @return 写入后的值（未配置时 {@code proxy: null}）
     */
    @PutMapping("/http-proxy")
    public HttpProxyResponse updateHttpProxy(@RequestBody(required = false) HttpProxyRequest body) {
        settingsService.writeDbHttpProxy(body == null ? null : body.proxy());
        return new HttpProxyResponse(settingsService.readDbHttpProxy());
    }

    /**
     * LLM 出站代理请求体。
     *
     * @param proxy 形如 {@code http://host:port}；null = 未配置；空串 = 显式直连
     */
    public record HttpProxyRequest(String proxy) {
    }

    /**
     * [代理自动检测 2026-11-16] 探测本机常见代理端口 · {@code GET /api/v1/settings/http-proxy/probe}。
     *
     * <p><b>判据不是「端口开着」</b>：并发用各端口<b>当 HTTP 代理</b>去取一次
     * {@code https://www.gstatic.com/generate_204}，拿到 2xx/3xx 才算命中 ⇒ SOCKS 端口、
     * 被别的服务占用的端口都会被正确排除。详见 {@link com.nexusai.infra.llm.ProxyProbe}。
     *
     * <p>范围仅本机回环（不扫局域网）。前端在此结果上**只做提示**，是否填入由用户决定。
     *
     * @return 命中列表（按延迟升序；可能为空）
     */
    @GetMapping("/http-proxy/probe")
    public ProxyProbeResponse probeHttpProxy() {
        return new ProxyProbeResponse(com.nexusai.infra.llm.ProxyProbe.probeLocal());
    }

    /**
     * [代理自动检测 2026-11-16] 实测一条代理是否可用 · {@code POST /api/v1/settings/http-proxy/test}。
     *
     * <p>「记住」之前调用：{@code ok=false} 时前端仍会保存（用户明确要求），只把 {@code error}
     * 显示成可读提示，不拦截。
     *
     * @param body {@code {"proxy":"http://127.0.0.1:7897"}}
     * @return {@code {ok, latencyMs, error}}
     */
    @PostMapping("/http-proxy/test")
    public ProxyTestResponse testHttpProxy(@RequestBody(required = false) HttpProxyRequest body) {
        String proxy = body == null ? null : body.proxy();
        com.nexusai.infra.llm.ProxyProbe.TestResult r =
            com.nexusai.infra.llm.ProxyProbe.test(proxy);
        return new ProxyTestResponse(r.ok(), r.latencyMs(), r.error());
    }

    /**
     * 代理探测响应。
     *
     * @param candidates 命中的本机代理（按延迟升序）
     */
    public record ProxyProbeResponse(List<com.nexusai.infra.llm.ProxyProbe.Candidate> candidates) {
    }

    /**
     * 代理实测响应。
     *
     * @param ok        是否真的能经该代理取到 HTTPS 204
     * @param latencyMs 往返耗时
     * @param error     失败原因（可读短句）；成功为 null
     */
    public record ProxyTestResponse(boolean ok, long latencyMs, String error) {
    }

    /**
     * LLM 出站代理响应。
     *
     * @param proxy 当前配置；{@code null} = 未配置（出站回落环境变量/直连）
     */
    public record HttpProxyResponse(String proxy) {
    }

    /**
     * GET 知识库模型全局默认。
     *
     * <p><b>为什么独立端点而不塞进 {@code /settings}</b>：{@code SettingsDto} 被 GET/PUT 共用且有
     * 12 处构造点；且「知识库模型」是<b>能力配置</b>，不是用户全局偏好，放独立路径语义更准。
     *
     * <p>⚠️ <b>2026-11-05 起真源是 {@code kb_config}</b>：settings 上的
     * {@code kb_embedding_model_id} / {@code kb_rerank_model_id} 已删除，本端点保留 URL
     * 仅为兼容，内部走 {@link KbConfigService#readModelConfig()}。
     *
     * <p>返回的是<b>原始值</b>（{@code null} = 用户没显式配过），不含「自动选用」的结果 ——
     * 否则用户会以为自己配过，模型被禁用后行为变化却找不到原因。
     */
    @GetMapping("/kb-models")
    public com.nexusai.kb.config.KbConfigService.ModelConfig getKbModels() {
        return kbConfigService.readModelConfig();
    }

    /**
     * PUT 知识库模型全局默认。
     *
     * <p><b>字段语义（与本仓 settings 的 merge 契约一致）</b>：
     * <ul>
     *   <li>字段缺省 / {@code null} ⇒ <b>不改</b>这一项</li>
     *   <li>传空串 ⇒ <b>清空</b>（回到「自动选用同类型第一个启用模型」）</li>
     *   <li>传模型 ID ⇒ 设为该模型</li>
     * </ul>
     *
     * @param req 请求体（两个字段都可选）
     * @return 写入后的配置（原始值）
     */
    @PutMapping("/kb-models")
    public com.nexusai.kb.config.KbConfigService.ModelConfig updateKbModels(@RequestBody KbModelConfigRequest req) {
        String embedding = req == null ? null : req.embeddingModelId();
        String rerank = req == null ? null : req.rerankModelId();
        kbConfigService.updateModelConfig(embedding, rerank);
        return kbConfigService.readModelConfig();
    }

    /**
     * 知识库模型配置请求体。
     *
     * @param embeddingModelId 向量模型 ID（null=不改；""=清空）
     * @param rerankModelId    重排模型 ID（null=不改；""=清空）
     */
    public record KbModelConfigRequest(String embeddingModelId, String rerankModelId) {
    }

    /**
     * GET 检索驱动（W1 · V268）。
     *
     * <p><b>为什么单独一个端点</b>：界面上要显示「有哪些驱动可选」+「哪些本机真能用」，
     * 这两份信息都来自 Spring 容器里的实现集合 —— 只有后端知道。
     * 前端⛔ 不应该硬编码驱动清单（加驱动时会漏改）。
     *
     * @return 当前配置 / 已注册 / 可用 三份清单
     */
    @GetMapping("/retrieval-engines")
    public RetrievalEnginesResponse getRetrievalEngines() {
        return new RetrievalEnginesResponse(
            engineRegistry.resolveEngineType(null),
            engineRegistry.registeredTypes(),
            engineRegistry.availableTypes());
    }

    /**
     * PUT 全局默认检索驱动（W1 · V268）。
     *
     * <p>⚠️ 未注册或本机不可用的驱动在这里就 <b>400</b> —— 不要等到用户去检索时
     * 才在后台抛异常（那时已经上传并索引过文档了，排查成本高得多）。
     *
     * @param req 请求体（{@code engine} 为驱动类型；空串 = 清空回落默认）
     * @return 写入后的三份清单
     */
    @PutMapping("/retrieval-engines")
    public RetrievalEnginesResponse updateRetrievalEngine(@RequestBody RetrievalEngineRequest req) {
        String engine = req == null ? null : req.engine();
        if (engine != null && !engine.isBlank()) {
            // 提前校验：可用性检查放在写入之前。
            // ⚠️ 注册表的 require() 抛 IllegalStateException（那是给「内部调用方」的信号）；
            //    这里是用户输入 ⇒ 必须转成 400。否则用户配错驱动只能看到一句
            //    「Internal Server Error」，不知道该填什么。
            try {
                engineRegistry.require(engine);
            } catch (IllegalStateException e) {
                throw new ValidationException(e.getMessage());
            }
        }
        kbConfigService.updateRetrieverEngine(engine);
        return getRetrievalEngines();
    }

    /**
     * 检索驱动请求体。
     *
     * @param engine 驱动类型（null = 不改；"" = 清空回落 sqlite）
     */
    public record RetrievalEngineRequest(String engine) {
    }

    /**
     * 检索驱动响应。
     *
     * @param current   当前生效的驱动（知识库没覆盖时的全局值）
     * @param registered 本构建已声明的驱动（含未接入的）
     * @param available 本机真正可用的驱动
     */
    public record RetrievalEnginesResponse(
        String current, java.util.List<String> registered, java.util.List<String> available
    ) {
    }

    /**
     * GET 入库流水线六池并发配置（V293）。
     *
     * <p><b>契约固定</b>（前端按此写）：
     * <pre>
     * 200 {"parse":8,"postprocess":2,"enrichment":12,"wiki":8,
     *      "maintenance":4,"shared":6,"maxTasksPerTick":5,"source":"db"|"yml"}
     * </pre>
     *
     * <p>{@code source} 语义：{@code db} = 用户显式保存过
     * （{@code settings.ingest_concurrency_config} 有值）；{@code yml} = 没保存过，
     * 返回的是 {@code nexusai.knowledge.ingest.*} 的默认值。前端据此区分
     * 「用户设置」与「系统默认」，⛔ 不要自己猜。
     *
     * @return 生效中的完整配置 + 来源
     */
    @GetMapping("/ingest-concurrency")
    public IngestConcurrencyResponse getIngestConcurrency() {
        IngestConcurrencyConfig stored = IngestConcurrencyConfig.parse(
            settingsService.readIngestConcurrencyConfig());
        IngestConcurrencyConfig effective = stored == null
            ? IngestConcurrencyConfig.ymlDefaults(ingestPoolProperties)
            : stored.resolve(ingestPoolProperties);
        return toIngestConcurrencyResponse(effective, stored == null ? "yml" : "db");
    }

    /**
     * PUT 入库流水线六池并发配置（V293）· 写库 + <b>热生效</b>（不重启）。
     *
     * <p><b>请求语义</b>：字段<b>全部可选</b>，缺失（{@code null}）= 不改这一项
     * —— 与 settings 其它 PUT 的 merge 契约一致。合并基线 = 「DB 已存值，缺的回落 yml」，
     * 因此一次只改一个池也能得到完整对象。
     *
     * <p><b>校验（fail loud → 400）</b>：六个池 1..64，{@code maxTasksPerTick} 1..50；
     * 越界错误信息带字段名（用户要能知道是哪一项填错了）。
     *
     * <p><b>⭐ 热生效顺序</b>：写库 → {@link KbIngestWorker#applyConcurrency}
     * （内部先覆盖 properties，再 resize 池；旧池在途归零后才 shutdown，⛔ 不打断在途任务）。
     * 前端拿到的返回对象就是<b>此刻真正生效</b>的值。
     *
     * @param req 请求体（可空对象；各字段可空）
     * @return 生效后的完整对象（同 GET 结构，{@code source=db}）
     */
    @PutMapping("/ingest-concurrency")
    public IngestConcurrencyResponse updateIngestConcurrency(@RequestBody(required = false) IngestConcurrencyRequest req) {
        IngestConcurrencyRequest patch = req == null
            ? new IngestConcurrencyRequest(null, null, null, null, null, null, null) : req;
        IngestConcurrencyConfig stored = IngestConcurrencyConfig.parse(
            settingsService.readIngestConcurrencyConfig());
        IngestConcurrencyConfig base = stored == null
            ? IngestConcurrencyConfig.ymlDefaults(ingestPoolProperties)
            : stored.resolve(ingestPoolProperties);
        // 先校验（越界直接 400，⛔ 不落库、不动池）
        IngestConcurrencyConfig overlay = new IngestConcurrencyConfig(
            validatePoolConcurrency("parse", patch.parse()),
            validatePoolConcurrency("postprocess", patch.postprocess()),
            validatePoolConcurrency("enrichment", patch.enrichment()),
            validatePoolConcurrency("wiki", patch.wiki()),
            validatePoolConcurrency("maintenance", patch.maintenance()),
            validatePoolConcurrency("shared", patch.shared()),
            validateMaxTasksPerTick(patch.maxTasksPerTick()));
        IngestConcurrencyConfig merged = base.overlay(overlay);
        settingsService.updateIngestConcurrencyConfig(merged.toJson());
        ingestWorker.applyConcurrency(
            merged.parse(), merged.postprocess(), merged.enrichment(), merged.wiki(),
            merged.maintenance(), merged.shared(), merged.maxTasksPerTick());
        return toIngestConcurrencyResponse(merged, "db");
    }

    /** 单池并发校验（null = 不改；越界 → 400，错误信息带字段名）。 */
    private static Integer validatePoolConcurrency(String field, Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > 64) {
            throw new ValidationException(field + " 非法（有效域 1..64）: " + value);
        }
        return value;
    }

    /** 每轮认领上限校验（null = 不改；越界 → 400）。 */
    private static Integer validateMaxTasksPerTick(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > 50) {
            throw new ValidationException("maxTasksPerTick 非法（有效域 1..50）: " + value);
        }
        return value;
    }

    /** 组装固定契约的响应（字段顺序 = 契约顺序）。 */
    private static IngestConcurrencyResponse toIngestConcurrencyResponse(
        IngestConcurrencyConfig config, String source) {
        return new IngestConcurrencyResponse(
            config.parse(), config.postprocess(), config.enrichment(), config.wiki(),
            config.maintenance(), config.shared(), config.maxTasksPerTick(), source);
    }

    /**
     * 入库六池并发 · 请求体（全部可选，缺失 = 不改）。
     *
     * <p>⚠️ 键名是前后端契约：{@code parse / postprocess / enrichment / wiki /
     * maintenance / shared / maxTasksPerTick} —— ⛔ 不要改名。
     */
    public record IngestConcurrencyRequest(
        Integer parse,
        Integer postprocess,
        Integer enrichment,
        Integer wiki,
        Integer maintenance,
        Integer shared,
        Integer maxTasksPerTick
    ) {
    }

    /**
     * 入库六池并发 · 响应体（GET/PUT 同结构）。
     *
     * @param source {@code db} = 值来自 settings 列；{@code yml} = 尚未保存过，返回 yml 默认值
     */
    public record IngestConcurrencyResponse(
        int parse,
        int postprocess,
        int enrichment,
        int wiki,
        int maintenance,
        int shared,
        int maxTasksPerTick,
        String source
    ) {
    }

    /**
     * DELETE 模型档位（全部）—— 「一键清除所有已配置的模型」。
     *
     * <p><b>为什么需要独立的 DELETE</b>：{@link #update} 的 merge 契约是「{@code null} = 不覆盖」
     * ⇒ 用 PUT <b>无法</b>表达「把档位清空」（清空要传 {@code null}，而 {@code null} 恰被当成「别动它」）。
     * 清空与覆盖语义相反，故走独立通道。
     *
     * <p>返回清空后的 {@link SettingsDto}（前端经 mergeSettings 合并回 state）。
     */
    @DeleteMapping("/model-tiers")
    public SettingsDto clearAllModelTiers() {
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除全部模型档位绑定");
        }
        return settingsService.clearAllModelTiers();
    }

    /**
     * DELETE 模型档位（单个）—— 解除某一个档位与模型的绑定。
     *
     * @param role 档位 wire 名（main / fast / strong / medium / weak / subagent /
     *             fallback / multimodal / tts / asr / classifier）
     * @return 清空后的 {@link SettingsDto}
     * @throws ValidationException 档位名非法（400）
     * @throws NotFoundException   settings 单例行缺失（404）
     */
    @DeleteMapping("/model-tiers/{role}")
    public SettingsDto clearModelTier(@PathVariable String role) {
        ModelTierRole r = ModelTierRole.fromWire(role);
        if (r == null) {
            // fail loud：未知档位名 → 400，不静默忽略（静默会让前端以为清掉了，实际没动）
            throw new ValidationException(
                "档位非法（允许 " + String.join("/", ModelTierRole.wireNames()) + "）：" + role);
        }
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除模型档位 {}", r.wire());
        }
        return settingsService.clearModelTier(r);
    }
}
