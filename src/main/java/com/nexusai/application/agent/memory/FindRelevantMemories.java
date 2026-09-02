package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.settings.entity.SettingsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.nexusai.application.agent.prompt.PromptCaching;
import com.nexusai.application.agent.recovery.ErrorClassifier;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * 记忆召回器 · LLM side-query 选相关记忆 · 对齐 CC findRelevantMemories.ts。
 *
 * <p>对齐 CC 真源（2026-08-05 grep -n 自验）：
 * <ol>
 *   <li>{@link #findRelevantMemories} — CC findRelevantMemories.ts:39-75：scan → alreadySurfaced 过滤
 *       （:46-48）→ memories 空返 []（:49-51）→ selectRelevantMemories → 按 filename 匹配回
 *       path+mtime（:59-74）</li>
 *   <li>{@link #selectRelevantMemories} — CC :77-141：sonnet + json_schema{selected_memories} +
 *       max_tokens=256 + recentTools 段（:92-95）+ querySource='memdir_relevance'（:121）；
 *       失败/中止 catch 返回 []（:131-140），<b>无关键词降级</b>（DEL-M-33）</li>
 *   <li>{@link #formatManifest} — CC memoryScan.ts:84-94 formatMemoryManifest：每行
 *       {@code - [type] filename (ISO-ts): description} 或 {@code - [type] filename (ISO-ts)}</li>
 * </ol>
 *
 * <p>返回绝对路径 + mtime（CC RelevantMemory，:13-16），供调用方注入时携带新鲜度而不必二次 stat。
 */

public class FindRelevantMemories {

    private static final Logger log = LoggerFactory.getLogger(FindRelevantMemories.class);

    /** side-query 重试退避基值（ms）· SDK 默认 0.5s 指数退避的 Java 简化；测试可调 0 加速。 */
    static volatile long retryBackoffBaseMs = 500L;


    /** CC findRelevantMemories.ts:18-24 SELECT_MEMORIES_SYSTEM_PROMPT（逐字对齐 CC 原文） */
    static final String SELECT_MEMORIES_SYSTEM_PROMPT = """
        You are selecting memories that will be useful to NexusAI as it processes a user's query. You will be given the user's query and a list of available memory files with their filenames and descriptions.

        Return a list of filenames for the memories that will clearly be useful to NexusAI as it processes the user's query (up to 5). Only include memories that you are certain will be helpful based on their name and description.
        - If you are unsure if a memory will be useful in processing the user's query, then do not include it in your list. Be selective and discerning.
        - If there are no memories in the list that would clearly be useful, feel free to return an empty list.
        - If a list of recently-used tools is provided, do not select memories that are usage reference or API documentation for those tools (NexusAI is already exercising them). DO still select memories containing warnings, gotchas, or known issues about those tools — active use is exactly when those matter.
        """;

    /**
     * 单条相关记忆 · CC original: {@code RelevantMemory}（findRelevantMemories.ts:13-16
     * {@code {path: string, mtimeMs: number}}）。
     *
     * @param path    记忆文件绝对路径（CC original: path）
     * @param mtimeMs 文件修改时间戳毫秒（CC original: mtimeMs）
     */
    public record RelevantMemory(String path, long mtimeMs) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmProviderFactory providerFactory;
    private final String sideModel;
    private final MemoryScanner scanner;
    /**
     * [RV14B-WIRE-02] 共享配置解析器 · side 模型名 → 真实 (ProviderConfig, providerType)。
     * <p>解决生产恒 mock 根因（原 :171 getProvider(ProviderConfig.empty()) 恒 mock）：sideModel
     * "sonnet" 字面量非 DB models.name，经 {@link #resolveDefaultSonnetModelName}（settings.mediumModelId
     * 直用 / provider 分流 sonnet46-45，G-1 起不经 fast 链）→ DB 名，再 resolve 真实 config。
     * 解析失败 → warn + 返回空列表（对齐 CC findRelevantMemories.ts:131-140 失败返回空、下轮重试），
     * 不落 mock（RV14B-GATE-01）。
     */
    private final com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver;

    /**
     * @param providerFactory     LLM provider 工厂
     * @param sideModel           side-query 用的模型名（CC getDefaultSonnetModel → 生产 "sonnet"；
     *                            经 {@link com.nexusai.infra.llm.ModelConfigResolver} 解析 DB 模型名；
     *                            G-1 起 medium 档不经 fast 链，未配置时按 provider 分流 sonnet46/45）
     * @param scanner             记忆目录扫描器（CC scanMemoryFiles 等价）
     * @param modelConfigResolver [RV14B-WIRE-02] 共享配置解析器（可 null：测试/无 Spring 场景 → 解析失败返回空）
     */
    public FindRelevantMemories(LlmProviderFactory providerFactory, String sideModel, MemoryScanner scanner,
                                com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver) {
        this.providerFactory = providerFactory;
        this.sideModel = sideModel;
        this.scanner = scanner;
        this.modelConfigResolver = modelConfigResolver;
        // [W6-1] 生产 @Bean 构造（ToolRegistrationConfig.findRelevantMemories）已注入 resolver → 直接安装
        //   静态 DB 来源（@Autowired setter 未触发时的兜底；setter 与构造同源幂等，无冲突）
        if (modelConfigResolver != null) {
            sonnetModelSource = () -> modelConfigResolver.settingsTierModelName(SettingsRecord::getMediumModelName);
        }
    }
    /**
     * 查找与 query 相关的记忆文件（最多 5，path+mtime）· 对齐 CC findRelevantMemories.ts:39-75。
     *
     * <p>{@code alreadySurfaced} 在 Sonnet 调用前过滤历史 turn 已展示的路径，让 5-slot 预算花在
     * 新候选而非调用方会丢弃的旧文件（CC :35-38 注释）。扫描失败（目录不存在）→ 空目录 → []。
     *
     * @param query           用户 query（CC original: query）
     * @param memoryDir       记忆目录（CC original: memoryDir）
     * @param recentTools     最近成功工具名列表（CC original: recentTools，:43）
     * @param alreadySurfaced 历史已展示路径集合（CC original: alreadySurfaced，:44）
     * @param signal          取消信号（CC original: signal，:47 —— scan + side-query 全链透传，
     *                        MEM-03/G-14；可 null = 无取消）
     * @return 相关记忆（最多 5）；无候选 / 无匹配 → 空列表
     */
    public List<RelevantMemory> findRelevantMemories(
        String query,
        Path memoryDir,
        List<String> recentTools,
        Set<String> alreadySurfaced,
        AbortController signal
    ) {
        // CC :46-48 scanMemoryFiles(memoryDir, signal) → filter(!alreadySurfaced.has(filePath))
        List<MemoryEntry> memories = scanner.scan(memoryDir, signal);
        List<MemoryEntry> fresh = memories.stream()
            .filter(m -> !alreadySurfaced.contains(m.filePath().toString()))
            .toList();
        if (fresh.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[FindRelevantMemories] 候选记忆为空（扫描 {} 条，alreadySurfaced 过滤后 0 条）返回空 · CC findRelevantMemories.ts:49-51",
                    memories.size());
            }
            return List.of();
        }

        List<String> selectedFilenames = selectRelevantMemories(query, fresh, recentTools, signal);
        // CC :59-62 byFilename map + filter(undefined)
        Map<String, MemoryEntry> byFilename = fresh.stream()
            .collect(Collectors.toMap(MemoryEntry::filename, e -> e, (a, b) -> a));
        List<RelevantMemory> selected = selectedFilenames.stream()
            .map(byFilename::get)
            .filter(Objects::nonNull)
            .map(m -> new RelevantMemory(m.filePath().toString(), m.mtime().toEpochMilli()))
            .toList();

        if (log.isDebugEnabled()) {
            log.debug("[FindRelevantMemories] 选完成: 候选 {} 条 → 选中 {} 条 (目录={})",
                fresh.size(), selected.size(), memoryDir);
        }
        return selected;
    }

    /**
     * side-query 选相关记忆 · 对齐 CC selectRelevantMemories.ts:77-141。
     *
     * <p>参数严格对齐 CC（2026-08-05 grep -n 自验）：
     * <ul>
     *   <li>model: {@code getDefaultSonnetModel()}（:99）→ Java 生产 sideModel="sonnet"</li>
     *   <li>system: SELECT_MEMORIES_SYSTEM_PROMPT + skipSystemPromptPrefix=true（:100-101）</li>
     *   <li>messages: {@code `Query: ${query}\n\nAvailable memories:\n${manifest}${toolsSection}`}（:105）</li>
     *   <li>max_tokens: 256（:108）</li>
     *   <li>output_format: json_schema {@code {selected_memories: string[]}}（:109-119）</li>
     *   <li>querySource: 'memdir_relevance'（:121）</li>
     *   <li>signal 透传（:117 → ChatRequestOptions.abortController，MEM-03）</li>
     * </ul>
     *
     * <p>失败/中止 → []（CC :131-140 catch），无关键词降级（DEL-M-33）。
     *
     * @param signal 取消信号（可 null = 无取消；abort 后不发起/不重试调用）
     * @return 选中文件名列表（已按 validFilenames 过滤）
     */
    List<String> selectRelevantMemories(String query, List<MemoryEntry> memories, List<String> recentTools,
                                        AbortController signal) {
        // CC :83 validFilenames = Set(memories.map(m => m.filename))
        Set<String> validFilenames = memories.stream()
            .map(MemoryEntry::filename)
            .collect(Collectors.toSet());

        String manifest = formatManifest(memories);

        // CC :92-95 toolsSection —— 工具正在使用时展示其参考文档是噪音，selector 会按关键词重合误选
        String toolsSection = (recentTools == null || recentTools.isEmpty())
            ? ""
            : "\n\nRecently used tools: " + String.join(", ", recentTools);

        String userMessage = "Query: " + query + "\n\nAvailable memories:\n" + manifest + toolsSection;

        // CC :109-119 json_schema {selected_memories: string[]}
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode selectedArr = props.putObject("selected_memories");
        selectedArr.put("type", "array");
        selectedArr.putObject("items").put("type", "string");
        schema.putArray("required").add("selected_memories");
        schema.put("additionalProperties", false);

        try {
            // [RV14B-WIRE-02] side 模型名 → 真实 (config, providerType)；解析失败 → 返回空列表
            //   （对齐 CC findRelevantMemories.ts:131-140 失败返回空、下轮重试；不落 mock）
            com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolved = resolveSideModelConfig();
            if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
                log.warn("[FindRelevantMemories] side 模型配置解析失败（模型名={}），返回空列表（warn+skip 不落 mock）",
                    resolveSideModelName());
                return List.of();
            }
            String modelName = resolveSideModelName();
            LlmProvider provider = providerFactory.getProvider(resolved.config(), resolved.providerType());
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(), null,
                LlmProvider.ChatRequestOptions.OutputFormat.jsonSchema(schema),
                null,
                null,
                "memdir_relevance",   // CC querySource: 'memdir_relevance' (:121)
                signal,               // MEM-03：CC signal 透传（:117）→ provider 请求前 abort 预检
                256                   // CC max_tokens: 256 (:108)
            );
            // MEM-02/G-22：sideQuery.ts:115-128 maxRetries=2 —— SDK 客户端级重试
            // （408/409/429/5xx + 连接错误；claude.ts:1781 主链 maxRetries:0 的 Java provider 无
            // 客户端重试 → 本处调用点手工重试等价）。abort 优先：取消后不发起/不重试。
            String rawJson = chatWithOptionsWithRetry(provider, resolved.config(), modelName,
                SELECT_MEMORIES_SYSTEM_PROMPT, userMessage, options);
            return parseSelectedMemories(rawJson).stream()
                .filter(validFilenames::contains)
                .toList();
        } catch (CancellationException e) {
            // CC :131-140 catch（含 abort）→ 返回 []，无关键词降级；下轮重试
            if (log.isDebugEnabled()) {
                log.debug("[FindRelevantMemories] selectRelevantMemories 被取消（abort），返回空列表: {}",
                    e.getMessage());
            }
            return List.of();
        } catch (Exception e) {
            // CC :131-140 catch → 返回 []，无关键词降级；下轮重试
            log.warn("[FindRelevantMemories] selectRelevantMemories 失败，返回空列表（CC 失败返回空、下轮重试）: {}",
                e.getMessage());
            return List.of();
        }
    }

    /** CC sideQuery maxRetries=2 等价 · 瞬时 5xx/429（SDK 重试状态集 408/409/429/≥500）重试至多 2 次。 */
    private static String chatWithOptionsWithRetry(
            LlmProvider provider,
            ProviderConfig config,
            String modelName,
            String systemPrompt,
            String userMessage,
            LlmProvider.ChatRequestOptions options) {
        int maxRetries = 2;   // CC sideQuery.ts:116 maxRetries = 2
        for (int attempt = 0; ; attempt++) {
            // abort 预检（CC SDK signal.throwIfAborted 请求前等价）：取消后不再发起调用
            if (options != null && options.abortController() != null
                    && options.abortController().isCancelled()) {
                throw new CancellationException("side-query aborted before attempt " + (attempt + 1));
            }
            try {
                return provider.chatWithOptions(config, modelName, systemPrompt, userMessage, options);
            } catch (Exception e) {
                // F2-MEM-02（返工）：catch 面从 LlmApiException 扩展为全 Exception —— 生产 provider
                //   异常包装面（AnthropicSdkProvider.chatWithOptions:976 全异常包 RuntimeException；
                //   OpenAiSdkProvider 连接错误 OpenAIConnectionException 原样上抛）经
                //   {@link #isRetryableError} 分类后进入 CC SDK maxRetries=2 重试面；不可重试原样上抛。
                if (attempt >= maxRetries || !isRetryableError(e)) {
                    throw e;
                }
                // SDK 默认指数退避（0.5s × 2^attempt，cap 8s）—— Java 简化实现（基值可测可调）
                long delayMs = Math.min(retryBackoffBaseMs << attempt, 8000L);
                log.warn("[FindRelevantMemories] side-query 瞬时错误（status/连接）重试 {}/{}（CC sideQuery maxRetries=2）: {}",
                    attempt + 1, maxRetries, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("side-query retry interrupted");
                }
            }
        }
    }
    /**
     * SDK maxRetries 重试判定（CC sideQuery.ts:115-128 → getAnthropicClient({maxRetries}) 客户端级：
     * 408/409/429/5xx + 连接错误 APIConnectionError，指数退避）。
     *
     * <p>Java provider 实际异常包装面分类（F2-MEM-02 返工，E2 实证）：
     * <ol>
     *   <li>{@link LlmApiException}（OpenAiSdkProvider.translateSdkError 面）→ status 判定；</li>
     *   <li>任意包装（AnthropicSdkProvider:976 RuntimeException 面）→ 解包 cause 链（≤5 层，同
     *       {@link ErrorClassifier#isConnectionError} 深度）依次找 {@link LlmApiException}
     *       （其他 provider 翻译后包装面）、{@link AnthropicServiceException}（SDK 原生 HTTP 面，
     *       含 429/5xx 子类 RateLimitException/InternalServerException，statusCode()）、
     *       {@link AnthropicRetryableException}（SDK 标记可重试面）；</li>
     *   <li>连接类错误（{@link ErrorClassifier#isConnectionError}：cause 链 ≤5 层
     *       instanceof IOException 类型闸，无消息子串 —— ER-IMP-2026-02 收紧，
     *       CC APIConnectionError 等价）→ 重试（OpenAiSdkProvider 连接错误原样上抛面 +
     *       AnthropicIoException 面）。</li>
     * </ol>
     *
     * @param e 调用失败异常（LlmApiException / 任意包装 / 连接错误）
     * @return true=可重试（CC SDK maxRetries 语义）；false=不可重试（400 等状态、纯逻辑错误）
     */
    static boolean isRetryableError(Throwable e) {
        if (e instanceof LlmApiException lae) {
            return isRetryableStatus(lae.status());
        }
        Throwable cur = e;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur instanceof LlmApiException lae) {
                return isRetryableStatus(lae.status());
            }
            if (cur instanceof AnthropicServiceException ase) {
                return isRetryableStatus(ase.statusCode());
            }
            if (cur instanceof AnthropicRetryableException) {
                return true;   // SDK 内部已标记可重试（连接/瞬时类）
            }
            cur = cur.getCause();
        }
        // CC APIConnectionError 等价：cause 链 ≤5 层 instanceof IOException 类型闸
        // （ER-IMP-2026-02 收紧，无消息子串；ErrorClassifier.java isConnectionError）
        return ErrorClassifier.isConnectionError(e);
    }

    /** Anthropic SDK 重试状态集（408/409/429/≥500）· sideQuery maxRetries 客户端重试判定。 */
    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }
    /**
     * [RV14B-WIRE-02] 解析 side 模型真实配置。
     *
     * <p>sideModel 字面量（"sonnet"/"haiku"）非 DB models.name → 经
     * {@link #resolveDefaultSonnetModelName} 解析为 DB 模型名（settings.mediumModelId 直用 /
     * provider 分流 sonnet46-45；G-1 起不经 fast 链），再 resolve 真实 (config, providerType)。
     * 与 CC getDefaultSonnetModel 语义一致（findRelevantMemories.ts:99）。解析失败 → null。
     *
     * @return 真实 (config, providerType)；解析失败 / resolver 未注入 → null
     */
    private com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolveSideModelConfig() {
        if (modelConfigResolver == null) {
            log.warn("[FindRelevantMemories] ModelConfigResolver 未注入，跳过配置解析（warn+skip）");
            return null;
        }
        String modelName = resolveSideModelName();
        if (modelName == null || modelName.isBlank()) return null;
        return modelConfigResolver.resolve(modelName);
    }

    // [W6-1] 中档(sonnet)模型来源 · static volatile Supplier（同 TeamMemoryHttpClient.baseUrlSource
    //   W4-1 模式）：默认 null（未注入 ModelConfigResolver）→ resolveSideModelName() 回落 sideModel。
    //   Spring 侧 {@link #setModelConfigResolver}（@Bean 实例经 @Autowired 触发）安装 DB settings
    //   mediumModelId 读取。

    /** [W6-1] 中档(sonnet)模型来源 · settings.mediumModelId（V25 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> sonnetModelSource = () -> null;

    /**
     * [RV14B-WIRE-02] 解析 side 模型 DB 名 · 字面量经 settings.mediumModelId 反查 / provider 分流。
     *
     * <p><b>MEM-01/G-21 + G-1（P0 修复）</b>：对齐 CC getDefaultSonnetModel（model.ts:119-130）——
     * <ol>
     *   <li>[W6-1] settings.mediumModelId 覆盖（:120-122，原 env {@code ANTHROPIC_DEFAULT_SONNET_MODEL}
     *       已删）→ 直用；</li>
     *   <li>provider 分支（:124-127）：firstParty → sonnet46 / 3P → sonnet45 —— Java 无进程级
     *       getAPIProvider 全局态（SchemaNotSentHint.java:40-42 同源先例），provider 判定 =
     *       探针 DEFAULT_SONNET（sonnet46，必要时再探 DEFAULT_SONNET_45）的已解析 config.baseUrl
     *       （GlobalCacheScope 单实现语义，betas.ts:227-233 对齐）。<b>绝不经 fast 链</b>：
     *       old :419 走 resolveFastModelName 落入 fast→weak→haiku，operator 配 fast/weak 未配
     *       medium 时记忆查询静默降级 haiku —— G-1 P0 BUG，已修复。</li>
     * </ol>
     *
     * @return DB 可用模型名；resolver 未注入 → 原字面量（测试兜底）
     */
    private String resolveSideModelName() {
        return resolveDefaultSonnetModelName(
            sonnetModelSource.get(), sideModel, modelConfigResolver);
    }

    /**
     * [W6-1] 安装中档(sonnet)模型 DB 来源 · 注入 {@link com.nexusai.infra.llm.ModelConfigResolver}
     * （内含 SettingsMapper，读 settings 单例行 id=1）后将 {@link #sonnetModelSource} 切换为
     * DB settings.mediumModelId 反查。{@code @Autowired(required=false)}：测试/孤立运行不注入 →
     * 保持默认 null（回落 sideModel，等价 CC env 未设）。同 TeamMemoryHttpClient#setProviderMapper
     * 的 W4-1 注入风格。
     */
    @Autowired(required = false)
    public void setModelConfigResolver(com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver) {
        if (modelConfigResolver != null) {
            sonnetModelSource = () -> modelConfigResolver.settingsTierModelName(SettingsRecord::getMediumModelName);
            log.info("FindRelevantMemories: ANTHROPIC_DEFAULT_SONNET_MODEL env 路删除，"
                + "侧查询模型改读 DB settings.medium_model_name（[W6-1][FN2] 字段改名）");
        }
    }

    /**
     * CC getDefaultSonnetModel（model.ts:119-130）纯核心 · 包内静态便于 settings 覆盖/provider 分支测试。
     * [W6-1] 语义改为「settings 值经参数注入」：第一参数是 DB settings.mediumModelId 反查后的
     * models.name（非 env）。
     *
     * <p><b>G-1（P0 BUG 修复）</b>：medium（sonnet）档<b>绝不经</b> {@code resolveFastModelName}
     * （fast→weak→haiku 三级链）。CC getDefaultSonnetModel 永不 consult 小快/弱档 settings
     * （model.ts:119-130），medium 未配置时恒按 provider 分流 sonnet46/45 —— 旧实现 :419 走 fast 链，
     * operator 配了 fast/weak 档未配 medium 时记忆 side-query 静默降级为 haiku
     * （findRelevantMemories.ts:99 恒 getDefaultSonnetModel）。修复：以 DEFAULT_SONNET 为探针，
     * 与 PromptCaching.defaultSonnetModel（PromptCaching.java:121-135）同构。
     *
     * @param settingsSonnetValue settings.mediumModelId 反查后的 models.name（CC original:
     *                            process.env.ANTHROPIC_DEFAULT_SONNET_MODEL，model.ts:120-122）
     * @param sideModel   Java side 模型字面量（生产 "sonnet"；resolver 未注入时回落）
     * @param resolver    共享配置解析器（可 null = 测试/无 Spring 场景）
     * @return 最终模型名（settings 直用 / provider 分支 sonnet46-45 字面量 / 原字面量）
     */
    static String resolveDefaultSonnetModelName(String settingsSonnetValue, String sideModel,
                                                com.nexusai.infra.llm.ModelConfigResolver resolver) {
        // CC :120-122 settings 覆盖直用（[W6-1] DB settings.mediumModelId）
        if (settingsSonnetValue != null && !settingsSonnetValue.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[FindRelevantMemories] side 模型 DB settings.mediumModelId 命中: {}（[W6-1]）",
                    settingsSonnetValue);
            }
            return settingsSonnetValue;
        }
        if (resolver == null) {
            return sideModel;
        }
        // CC :124-127 provider 分支：settings 未提供 → 按已解析 provider 的 firstParty 判定选默认。
        // [G-1] 探针 base = DEFAULT_SONNET（sonnet46）：medium 档与 fast/weak 档无关，绝不经
        //   resolveFastModelName 链（旧实现 :419 落入 fast→weak→haiku，G-1 P0 BUG）。
        com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel rm = resolver.resolve(PromptCaching.DEFAULT_SONNET);
        if (rm == null || rm.config() == null) {
            // 3P 可能仅有 sonnet45（CC model.ts:124-126 "3P may not have 4.6 yet"）→ 次探针
            rm = resolver.resolve(PromptCaching.DEFAULT_SONNET_45);
        }
        if (rm == null || rm.config() == null) {
            // 探针全失败（DB 无 sonnet 系）→ 回落 CC firstParty 默认 sonnet46；调用链 resolve
            //   再失败将 warn+skip 返回空（fail-loud，不落 haiku —— G-1 关键）
            if (log.isDebugEnabled()) {
                log.debug("[FindRelevantMemories] medium 档未配置且探针 {} / {} 均解析失败，回落默认 {}（CC getDefaultSonnetModel，G-1 不经 fast 链）",
                    PromptCaching.DEFAULT_SONNET, PromptCaching.DEFAULT_SONNET_45, PromptCaching.DEFAULT_SONNET);
            }
            return PromptCaching.DEFAULT_SONNET;
        }
        boolean firstParty = rm.config().baseUrl() != null
            && rm.config().baseUrl().contains("api.anthropic.com");
        if (log.isDebugEnabled()) {
            log.debug("[FindRelevantMemories] medium 档未配置，按 provider 分流: {} → {}（CC getDefaultSonnetModel model.ts:124-127，G-1 不经 fast 链）",
                firstParty ? "firstParty" : "3P",
                firstParty ? PromptCaching.DEFAULT_SONNET : PromptCaching.DEFAULT_SONNET_45);
        }
        return firstParty
            ? PromptCaching.DEFAULT_SONNET       // sonnet46
            : PromptCaching.DEFAULT_SONNET_45;   // sonnet45
    }


    /**
     * 解析 LLM 返回 JSON {@code {selected_memories:[...]}} · 对齐 CC findRelevantMemories.ts:129
     * {@code jsonParse(textBlock.text)}。
     *
     * <p>FIX-FR：删除 markdown 围栏剥离分支（CC 直接 jsonParse，json_schema output_format 已保证
     * 裸 JSON —— sideQuery.ts:190 真发 output_config.format.json_schema 后无围栏）。
     *
     * @return selected_memories 字符串数组；解析失败 → 空列表
     */
    List<String> parseSelectedMemories(String rawJson) {
        try {
            String json = rawJson.trim();
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.get("selected_memories");
            if (arr != null && arr.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode n : arr) {
                    if (n.isTextual()) {
                        result.add(n.asText());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[FindRelevantMemories] JSON 解析失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 格式化 catalog 为 manifest 文本 · 对齐 CC memoryScan.ts:84-94 formatMemoryManifest。
     *
     * <p><b>rev2 单一入口（D-06/OPD-R2-MEM-06）</b>：CC extractMemories.ts:398-400 与
     * findRelevantMemories 复用同一 formatMemoryManifest —— 本方法为唯一实现，ExtractMemoriesAgent
     * 预注入 manifest 亦调用此处（旧私有重复实现已删）。
     *
     * <p>每行: {@code - [type] filename (ISO-ts): description}（有 description）或
     * {@code - [type] filename (ISO-ts)}（无 description）· CC :90-91。
     *
     * <p><b>rev2 X-1（NEW-3）</b>：时间戳恒 3 位毫秒 —— CC :88 {@code new Date(m.mtimeMs).toISOString()}
     * 输出固定 {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}（整秒补 .000）；旧 Instant.toString() 整秒
     * 省略毫秒/纳秒原样 → 注入 LLM 的 manifest 字节差异（跨模块 X-1 两处消费方同病，本处收敛）。
     *
     * <p>CC join('\n') 无尾换行 —— 与 opener 注入段（prompts.ts:32 段后 2 换行）字节对齐（EX-03③）。
     *
     * @param entries 记忆条目（按 mtime 降序的 catalog）
     * @return manifest 文本（空输入 → 空串）
     */
    public static String formatManifest(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        return entries.stream()
            .map(e -> {
                // CC :87-89 tag = m.type ? `[${m.type}] ` : ''; ts = new Date(m.mtimeMs).toISOString()
                String tag = e.type() != null ? "[" + e.type().toTypeValue() + "] " : "";
                String ts = MANIFEST_TIMESTAMP_FORMATTER.format(e.mtime());
                String desc = e.description();
                return (desc != null && !desc.isBlank())
                    ? "- " + tag + e.filename() + " (" + ts + "): " + desc
                    : "- " + tag + e.filename() + " (" + ts + ")";
            })
            .collect(Collectors.joining("\n"));
    }

    /** CC toISOString() 恒 3 位毫秒形态 · memoryScan.ts:88（UTC）。 */
    private static final java.time.format.DateTimeFormatter MANIFEST_TIMESTAMP_FORMATTER =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC);
}
