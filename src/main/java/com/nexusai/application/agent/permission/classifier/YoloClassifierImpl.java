package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * YoloClassifier 实现 · 对齐 CC 2-stage XML + classify_result 协议
 * （yoloClassifier.ts:711-996 {@code classifyYoloActionXml}，OPD-WF6-01）。
 *
 * <h2>[S06 重构] 核心协议变更（大工程）</h2>
 * <ul>
 *   <li><b>2-stage XML</b>：stage1（fast，{@code <block>} 立即判定）→ 若 block 触发 stage2
 *       （thinking，chain-of-thought 减少误报）——对齐 CC classifyYoloActionXml :711-996。</li>
 *   <li><b>classify_result 协议</b>：结果契约为 {@link YoloClassifierResult}（布尔
 *       {@code shouldBlock}），XML 输出格式由 {@link YoloPromptBuilder#replaceOutputFormatWithXml}
 *       承载 —— 删除旧 JSON {@code {decision,reason,confidence}} 契约（⊕-01/⊕-02）。</li>
 *   <li><b>stage2 触发</b>：CC 语义 = stage1 block（⊕-03），删除旧 confidence&gt;0.8 阈值触发。</li>
 *   <li><b>溢出检测</b>：删除 wouldOverflow 90% 预检（⊕-04，OPD-WF6-02），统一到
 *       {@link YoloTokenEstimator#detectPromptTooLong}（API 'prompt is too long' 承载）。</li>
 *   <li><b>thinking 提取</b>：仅 XML parseXmlThinking（⊕-06），删除 JSON reasoning_content/thinking 路径。</li>
 *   <li><b>单模型</b>：删除双 config + getModelConfig + ClassifierModelConfig（⊕-08），
 *       {@link #resolveClassifierModel} 对齐 CC getClassifierModel（env→GB→mainLoop，⊕-09）。</li>
 *   <li><b>重试</b>：CC sideQuery maxRetries=getDefaultMaxRetries()（429/5xx，OPD-WF6-07）——
 *       {@link #callWithRetry} maxRetries=10 指数退避。</li>
 * </ul>
 *
 * <p><b>失败语义（fail-closed，对齐 CC yoloClassifier.ts:941-995）</b>：
 * <ul>
 *   <li>abort（ctx.abortController 取消）→ shouldBlock=true + unavailable=true，
 *       reason='Classifier request aborted'</li>
 *   <li>API 'prompt is too long' → shouldBlock=true + transcriptTooLong=true，
 *       reason='Classifier transcript exceeded context window'（确定性，重试无效）</li>
 *   <li>stage1 失败 → shouldBlock=true + unavailable=true，
 *       reason='Classifier unavailable - blocking for safety'</li>
 *   <li>stage2 失败（stage1 已成功）→ shouldBlock=true + unavailable=false，
 *       reason='Stage 2 classifier error - blocking based on stage 1 assessment'</li>
 *   <li>解析失败 → shouldBlock=true（unavailable=false），reason='Classifier stage N unparseable - blocking for safety'</li>
 * </ul>
 *
 * @see YoloClassifier
 * @see YoloClassifierResult
 * @see YoloPromptBuilder
 * @see YoloTokenEstimator
 */
@Component
public class YoloClassifierImpl implements YoloClassifier {

    private final Logger log = LoggerFactory.getLogger(YoloClassifierImpl.class);

    private final YoloPromptBuilder promptBuilder;
    private final YoloTokenEstimator tokenEstimator;
    private final LlmProviderFactory providerFactory;
    /** [V45] 显式 yml 覆写模型名（nexusai.classifier.model）· 优先于 DB settings.classifierModel。
     *  空/未配置 → 不参与（有效名由 {@link #classifierModelName()} 动态解析）。
     *  对齐 CC getClassifierModel() 的 yml 槽（yoloClassifier.ts:1345-1361）。 */
    private final String ymlModelName;
    /** chatWithRaw 单次调用超时（秒）· 替换旧 ClassifierModelConfig.timeoutSeconds（⊕-08）。 */
    private final int timeoutSeconds;
    private final ModelConfigResolver modelConfigResolver;
    /** [IMP-6 OPD-WF6-01-RV] 2-stage XML 分类器开关 · CC isTwoStageClassifierEnabled()
     *  （yoloClassifier.ts:1374-1377）——false（默认，无配置）→ 1-stage classify_result 工具协议；
     *  true → 2-stage XML（both）。CC 'fast'/'thinking' 单级模式为 ant 内部变体，Java N/A。 */
    private final boolean twoStageClassifier;

    /** CC feature('TRANSCRIPT_CLASSIFIER') 门（betas.ts:161）· Java {@code nexusai.classifier.transcript.enabled}
     *  （toolExecution.ts:1075-1101，默认 true）。关闭时 isAvailable() → false（对齐 CC modelSupportsAutoMode 首层门）。 */
    @Value("${nexusai.classifier.transcript.enabled:true}")
    private volatile boolean transcriptClassifierEnabled = true;

    /** CC tengu_auto_mode_config.allowModels 覆写（betas.ts:173-183）· Java 无 GrowthBook，
     *  以逗号分隔静态配置 {@code nexusai.auto-mode.allow-models} 建模（force-enable 列表）。 */
    @Value("${nexusai.auto-mode.allow-models:}")
    private volatile String allowModelsConfig = "";

    /** [P5 AM-CONF-4] isAvailable() 结果缓存（懒计算；@Value 字段 Spring 注入后才正确）。 */
    private volatile Boolean modelSupportCache;
    /** [V45] isAvailable() 缓存计算时用的有效模型名 · settings.classifierModel 变化 → 重算
     *  （否则前端改配置不生效）。 */
    private volatile String modelSupportCacheModelName;

    /** [V45] DB settings.classifierModel 读源 · 字段注入（required=false）：Spring 装配后注入；
     *  POJO 单测 / 无 Spring → null → 读 DB 跳过（未配置 → 主循环兜底，零行为变化）。
     *  对齐 InitialPermissionModeSource（:74-75 字段注入）先例。 */
    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    /** [prompt-align TOOLS-01] 可选 CLAUDE.md 内容读源 · 镜像 CC
     *  {@code getCachedClaudeMdContent}（bootstrap/state.ts 缓存，context.ts 填充；
     *  yoloClassifier.ts:453-459 注释——缓存未填充（测试/未调 getUserContext 入口）
     *  → null → 无前缀，同 pre-PR 行为）。ToolRegistrationConfig 接线读
     *  {@code UserContextProvider.claudeMd()}；未注入/为 null → 无前缀。
     *  POJO 单测不注入 → 零行为变化。 */
    @Autowired(required = false)
    private java.util.function.Supplier<String> claudeMdContentSupplier;

    /** CC 1-stage 分类器 max_tokens（yoloClassifier.ts:1136）= 4096（thinkingPadding=0 非 ant）。 */
    private static final int CLASSIFIER_SINGLE_STAGE_MAX_TOKENS = 4096;

    /** CC DEFAULT_MAX_RETRIES（withRetry.ts:52）= 10 —— 分类器 429/5xx 最大重试次数。 */
    static final int DEFAULT_MAX_RETRIES = 10;
    /** CC BASE_DELAY_MS（withRetry.ts:55）= 500 —— 指数退避基础延迟（ms）。 */
    static final long RETRY_BASE_DELAY_MS = 500L;
    /** 指数退避上限（ms）· 对齐 FindRelevantMemories 既有简化实现。 */
    private static final long RETRY_MAX_DELAY_MS = 8000L;

    /** 工具注册表 · classify 时用于转录 per-tool 投影（CC buildToolLookup :364-374）。 */
    private ToolRegistry toolRegistry;

    @Autowired(required = false)
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        if (toolRegistry != null && log.isInfoEnabled()) {
            log.info("YoloClassifierImpl: 注入 ToolRegistry（可用工具数={}），转录将使用 per-tool 投影", toolRegistry.size());
        }
    }

    public YoloClassifierImpl(
            YoloPromptBuilder promptBuilder,
            YoloTokenEstimator tokenEstimator,
            LlmProviderFactory providerFactory,
            @Value("${nexusai.classifier.model:}") String modelName,
            @Value("${nexusai.classifier.timeout-seconds:30}") int timeoutSeconds,
            ModelConfigResolver modelConfigResolver,
            @Value("${nexusai.classifier.two-stage-classifier:false}") boolean twoStageClassifier
    ) {
        this.promptBuilder = promptBuilder;
        this.tokenEstimator = tokenEstimator;
        this.providerFactory = providerFactory;
        // [V45] 不再硬编码 claude-sonnet-4-20250514 缺省字面量（偏离 CC getClassifierModel 兜底
        //   getMainLoopModel，yoloClassifier.ts:1361）——只保留 yml 覆写原文（可空白）；有效模型名
        //   由 classifierModelName() 动态解析（yml → DB settings.classifierModel → 主循环兜底）。
        this.ymlModelName = modelName;
        this.timeoutSeconds = timeoutSeconds;
        this.modelConfigResolver = modelConfigResolver;
        this.twoStageClassifier = twoStageClassifier;
    }

    /**
     * 2-stage XML 分类器是否启用 · 对齐 CC {@code isTwoStageClassifierEnabled}
     * （yoloClassifier.ts:1374-1377，{@code resolveTwoStageClassifier()} 返回 true/'fast'/'thinking'）。
     *
     * <p>Java 端以 Spring 配置 {@code nexusai.classifier.two-stage-classifier} 建模
     * （GrowthBook / ant env N/A）；默认 false → 1-stage classify_result 工具协议（CC 默认）。
     *
     * @return true = 2-stage XML；false = 1-stage
     */
    private boolean isTwoStageClassifierEnabled() {
        return twoStageClassifier;
    }

    @Override
    public boolean isAvailable() {
        // [V45] 简单失效：记录缓存计算时用的有效模型名，settings.classifierModel 变化 → 重算
        //   （否则前端改配置不生效）。classifierModelName() 每次调用实时读 DB（settings 单行，廉价）。
        String effective = classifierModelName();
        Boolean cached = modelSupportCache;
        String cachedModel = modelSupportCacheModelName;
        if (cached != null && java.util.Objects.equals(cachedModel, effective)) {
            return cached;
        }
        boolean computed = computeModelSupportsAutoMode();
        modelSupportCache = computed;
        modelSupportCacheModelName = effective;
        return computed;
    }

    /**
     * [P5 · AM-CONF-4 拍板：对齐 CC modelSupportsAutoMode 五层链] 计算当前分类器
     * 模型/提供商是否支持 auto 模式（betas.ts:160-195）。
     *
     * <p>fail-closed：任何异常 → false（不因解析失败误放 auto 模式）。
     *
     * @return true = 模型+provider 支持 auto 模式
     */
    private boolean computeModelSupportsAutoMode() {
        // [AM-CC-20260824] 彻底对齐 CC 当前版（betas.ts:160-162）：
        //   modelSupportsAutoMode(_model) { return feature('TRANSCRIPT_CLASSIFIER') ? true : false }
        //   —— 只查 feature 门，不看 provider/model（旧五层链是旧版 CC 对齐遗留，用户 2026-08-24
        //   拍板删除）。无 DB 解析 → 无异常源，fail-closed 语义由本方法直接返回值承担。
        return transcriptClassifierEnabled;
    }

    /**
     * [AM-CC-20260824] CC modelSupportsAutoMode 当前版（betas.ts:160-162）· 纯静态（可单测）。
     *
     * <p><b>彻底对齐 CC</b>：CC 当前版
     * {@code modelSupportsAutoMode(_model) { return feature('TRANSCRIPT_CLASSIFIER') ? true : false }}
     * —— 只查 feature 门，{@code _model} 参数忽略。旧五层链（provider 仅 firstParty /
     * allowModels / ant denylist / external allowlist）是<b>旧版 CC</b> 对齐遗留，用户
     * 2026-08-24 拍板删除（前身实现曾以「放行 openai_compatible」偏离旧版首方限制，现
     * 直接对齐 CC 当前版，deepseek 等 openai_compatible 提供方随 feature 门自然放行）。
     *
     * <p>6 参签名保留以兼容调用点（computeModelSupportsAutoMode/isAvailable 传参），除
     * {@code transcriptClassifierEnabled} 外全部忽略。
     *
     * @param modelName               分类器模型名（CC model，raw；忽略）
     * @param providerAvailable       Java providerFactory 是否接线（忽略）
     * @param providerType            Java providerType（忽略）
     * @param allowModels             allowModels 覆写列表（忽略）
     * @param transcriptClassifierEnabled CC feature('TRANSCRIPT_CLASSIFIER')（Java nexusai.classifier.transcript.enabled）
     * @param userType                System.getenv("USER_TYPE")（忽略）
     * @return true = 支持 auto 模式
     */
    static boolean modelSupportsAutoMode(
            String modelName, boolean providerAvailable, String providerType,
            Set<String> allowModels, boolean transcriptClassifierEnabled, String userType) {
        // [AM-CC-20260824] 对齐 CC 当前版（betas.ts:160-162）：只查 feature 门，其余参数忽略。
        return transcriptClassifierEnabled;
    }

    @Override
    public CompletableFuture<YoloClassifierResult> classify(
            String toolName, JsonNode input,
            List<ChatMessageDto> transcript, ToolUseContext ctx
    ) {
        // [RV14B-WIRE-01] 调用方线程先 resolve 真实配置（DB/settings 访问不在 ForkJoinPool 公共池）
        //   · 对齐 CC getClassifierModel() 兜底 getMainLoopModel()（yoloClassifier.ts:1334-1350）。
        //   [V45] 有效模型名动态解析（yml → DB settings.classifierModel → 主循环兜底），解析失败 →
        //   resolved=null → fail-closed block；modelName 透传（含兜底 fast 名）保证 LLM model 参数一致。
        ClassifierResolution resolution = resolveClassifierModel(classifierModelName());
        ModelConfigResolver.ResolvedModel resolved = resolution != null ? resolution.resolved() : null;
        String modelName = resolution != null ? resolution.modelName() : null;
        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            try {
                return classifySync(toolName, input, transcript, ctx, resolved, modelName);
            } catch (Exception e) {
                // 兜底 fail-closed：shouldBlock=true + unavailable=true（CC yoloClassifier.ts:941-995）
                boolean aborted = isAborted(ctx);
                String reason = aborted
                    ? "Classifier request aborted"
                    : "Classifier unavailable - blocking for safety";
                if (log.isWarnEnabled()) {
                    log.warn("YoloClassifier failed (fail-closed block): aborted={} err={}", aborted, e.getMessage());
                }
                return YoloClassifierResult.unavailable(reason, modelName, 1);
            }
        });
    }

    /**
     * [IMP-SUB-25 R3] 用户文本 action 分类 · 对齐 CC {@code classifyYoloAction}
     * （yoloClassifier.ts:1012）的 user-text action 变体（toCompactBlock :418-421）。
     */
    @Override
    public CompletableFuture<YoloClassifierResult> classifyTextAction(
            String userText,
            List<ChatMessageDto> transcript,
            ToolUseContext ctx
    ) {
        // [V45] 同 classify()：有效模型名动态解析 + modelName 透传（含兜底 fast 名），LLM model 参数一致。
        ClassifierResolution resolution = resolveClassifierModel(classifierModelName());
        ModelConfigResolver.ResolvedModel resolved = resolution != null ? resolution.resolved() : null;
        String modelName = resolution != null ? resolution.modelName() : null;
        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            try {
                if (isAborted(ctx)) {
                    return YoloClassifierResult.unavailable("Classifier request aborted", modelName, 1);
                }
                Map<String, Tool> projectionLookup = buildProjectionLookup();
                String text = userText != null ? userText : "";
                String actionCompact = "User: " + text + "\n";
                if (log.isDebugEnabled()) {
                    log.debug("YoloClassifierImpl: user-text action 序列化 len={} (CC yoloClassifier.ts:418-421)",
                        actionCompact.length());
                }
                return classifyActionCore(actionCompact, projectionLookup, transcript, ctx, resolved, modelName);
            } catch (Exception e) {
                boolean aborted = isAborted(ctx);
                String reason = aborted
                    ? "Classifier request aborted"
                    : "Classifier unavailable - blocking for safety";
                if (log.isWarnEnabled()) {
                    log.warn("YoloClassifier user-text action 失败 (fail-closed block): aborted={} err={}",
                        aborted, e.getMessage());
                }
                return YoloClassifierResult.unavailable(reason, modelName, 1);
            }
        });
    }

    /**
     * 同步分类（tool_use action）· abort 预检 → action 投影 → 2-stage XML 核心。
     */
    private YoloClassifierResult classifySync(
            String toolName, JsonNode input,
            List<ChatMessageDto> transcript, ToolUseContext ctx,
            ModelConfigResolver.ResolvedModel resolved, String modelName
    ) {
        if (isAborted(ctx)) {
            if (log.isDebugEnabled()) {
                log.debug("YoloClassifier: 调用前已 abort → 返回 unavailable 结果 (tool={})", toolName);
            }
            return YoloClassifierResult.unavailable("Classifier request aborted", modelName, 1);
        }
        Map<String, Tool> projectionLookup = buildProjectionLookup();
        String actionCompact = promptBuilder.toCompactBlock(toolName, input, projectionLookup);
        return classifyActionCore(actionCompact, projectionLookup, transcript, ctx, resolved, modelName);
    }

    /**
     * 共享分类核心 · 对齐 CC {@code classifyYoloAction}（yoloClassifier.ts:1012-1306）。
     *
     * <p>流程：'' 短路 ALLOW（:1021-1028）→ system prompt（buildYoloSystemPrompt :484-540）→
     * 转录分桶（:1038-1059）→ provider 解析 → 按 {@link #isTwoStageClassifierEnabled()}
     * 分发（:1111）：true → 2-stage XML（classifyYoloActionXml :711-996）；false → 1-stage
     * classify_result 工具协议（:1131-1305）。
     *
     * @param actionCompact    已序列化的 action 块（tool_use {@code "toolName {projection}\n"} /
     *                         user-text {@code "User: {text}\n"}）
     * @param projectionLookup 工具名/别名 → Tool 投影查表（transcript 历史 tool_use 投影用）
     * @param transcript       对话历史
     * @param ctx              工具调用上下文（abort 判定）
     * @param resolved         单模型真实配置（null → fail-closed block）
     * @param modelName        分类器有效模型名（含主循环兜底名；透传 LLM model 参数 + 结果字段）
     * @return 分类结果（CC shouldBlock 契约）
     */
    private YoloClassifierResult classifyActionCore(
            String actionCompact,
            Map<String, Tool> projectionLookup,
            List<ChatMessageDto> transcript, ToolUseContext ctx,
            ModelConfigResolver.ResolvedModel resolved, String modelName
    ) {
        // '' 短路 ALLOW · CC :1021-1028（tool_use 投影 '' 才触发；user-text 恒非空不短路）
        if (actionCompact == null || actionCompact.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.info("YoloClassifier: action 投影为空 → 短路 ALLOW（action 无安全相关性分类输入，"
                    + "CC yoloClassifier.ts:1021-1028）");
            }
            return YoloClassifierResult.allowed("Tool declares no classifier-relevant input", modelName);
        }
        // abort 预检 · CC :1261-1269（signal.aborted → block）
        if (isAborted(ctx)) {
            return YoloClassifierResult.unavailable("Classifier request aborted", modelName, 1);
        }

        // system prompt · CC :484-540（buildYoloSystemPrompt；2-stage 另经 replaceOutputFormatWithXml :741-748）
        String systemPrompt = promptBuilder.buildYoloSystemPrompt();

        // 转录（CC :302-360）+ 分桶（CC :1038-1059）· 全量 + 排除 assistant 文本防注入
        List<YoloPromptBuilder.CompactMessage> entries =
            transcript != null ? promptBuilder.buildTranscriptEntries(transcript, projectionLookup) : List.of();
        StringBuilder contents = new StringBuilder();
        long userPromptsLength = 0L;
        long toolCallsLength = actionCompact.length();
        for (YoloPromptBuilder.CompactMessage entry : entries) {
            String content = entry.content();
            contents.append(content);
            if ("user".equals(entry.role())) {
                userPromptsLength += content.length();
            } else if ("assistant".equals(entry.role())) {
                toolCallsLength += content.length();
            }
        }
        // action 块（CC :1102-1106 append actionCompact，stage2 共享同一前缀）· 恒追加
        // 2-stage：<transcript> 包裹（CC :760-766）；1-stage：原始内容 + action，无包裹（CC :1040-1061）
        String wrappedContent = "<transcript>\n" + contents + actionCompact + "</transcript>\n";
        String singleStageUser = contents + actionCompact;
        // [prompt-align TOOLS-01] CLAUDE.md 前缀块注入 · CC buildClaudeMdMessage
        //  （yoloClassifier.ts:460-477）→ prefixMessages（:1033-1035）置于转录 user 消息
        //  之前（2-stage :787/:874、1-stage :1148 各自 spread）。Java chatWithRaw 单字符串
        //  通道 → 前缀块 prepend 到 user 消息文本，语义等价（同为 user role 内容前置）。
        //  供应商未填充 → null → 零变化。promptLengths 分桶不变（CC :1045-1047 只累加
        //  转录 user 条目，不含 claudeMd 前缀消息）。
        String claudeMdPrefix = buildClaudeMdPrefix();
        if (claudeMdPrefix != null) {
            wrappedContent = claudeMdPrefix + "\n\n" + wrappedContent;
            singleStageUser = claudeMdPrefix + "\n\n" + singleStageUser;
            if (log.isDebugEnabled()) {
                log.debug("YoloClassifier: CLAUDE.md 前缀块已注入 user 消息（TOOLS-01，CC yoloClassifier.ts:460-477）: 前缀{}字符",
                    claudeMdPrefix.length());
            }
        }
        PromptLengths promptLengths = new PromptLengths(
            systemPrompt.length(), toolCallsLength, userPromptsLength);
        if (log.isInfoEnabled()) {
            log.info("[YoloClassifier] promptLengths 分桶: transcript.size={} userPrompts.totalLen={} toolCalls.totalLen={}",
                entries.size(), userPromptsLength, toolCallsLength);
        }

        // 单模型 provider 解析 · null → fail-closed（对齐 RV14B-GATE-01：不落 mock）
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            if (log.isWarnEnabled()) {
                log.warn("YoloClassifierImpl: 模型 {} 配置解析失败/不可用，fail-closed block（warn+skip 不落 mock）", modelName);
            }
            return YoloClassifierResult.unavailable("Classifier unavailable - blocking for safety", modelName, 1);
        }
        LlmProvider provider = providerFactory.getProvider(resolved.config(), resolved.providerType());
        if (provider == null) {
            return YoloClassifierResult.unavailable("Classifier unavailable - blocking for safety", modelName, 1);
        }

        // 2-stage / 1-stage 分发 · CC :1111 isTwoStageClassifierEnabled()
        if (isTwoStageClassifierEnabled()) {
            String xmlSystemPrompt = promptBuilder.replaceOutputFormatWithXml(systemPrompt);
            return classifyTwoStage(provider, resolved, modelName, xmlSystemPrompt, wrappedContent, promptLengths, ctx);
        }
        return classifySingleStage(provider, resolved, modelName, systemPrompt, singleStageUser, promptLengths, ctx);
    }

    /**
     * 解析 CLAUDE.md 前缀块 · [prompt-align TOOLS-01] 镜像 CC {@code buildClaudeMdMessage}
     * 读源侧（yoloClassifier.ts:460-461 {@code getCachedClaudeMdContent()}）。
     *
     * <p>读 {@link #claudeMdContentSupplier}（ToolRegistrationConfig 接线
     * {@code UserContextProvider.claudeMd()}）；供应商未注入/为 null/抛异常 → null →
     * 无前缀（对齐 CC pre-PR 缓存未填充行为）。文本拼装委托
     * {@link YoloPromptBuilder#buildClaudeMdPrefix}。
     *
     * @return 前缀块文本；无 CLAUDE.md → null
     */
    private String buildClaudeMdPrefix() {
        if (claudeMdContentSupplier == null) {
            return null;
        }
        String claudeMd;
        try {
            claudeMd = claudeMdContentSupplier.get();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("YoloClassifier: claudeMd 读源异常，按无前缀处理（对齐 CC 缓存未填充语义）: {}",
                    e.getMessage());
            }
            return null;
        }
        return promptBuilder.buildClaudeMdPrefix(claudeMd);
    }

    /**
     * 2-stage XML 分类核心 · 对齐 CC {@code classifyYoloActionXml}（yoloClassifier.ts:711-996）。
     *
     * <p>流程：stage1 fast（:768-858）→ 若 block 触发 stage2 thinking（:860-940）。
     * 结果填充 usage（extractUsage :609-618 / combineUsage :633-641，OPD-WF6-02-RV）。
     *
     * @param provider        LLM provider
     * @param resolved        单模型真实配置
     * @param modelName       分类器模型名
     * @param xmlSystemPrompt replaceOutputFormatWithXml 后的 XML 版 system prompt
     * @param wrappedContent  {@code <transcript>...</transcript>} 包裹的转录 + action
     * @param promptLengths   三段 prompt 长度
     * @param ctx             工具调用上下文（abort 判定）
     * @return 分类结果（CC shouldBlock 契约）
     */
    private YoloClassifierResult classifyTwoStage(
            LlmProvider provider, ModelConfigResolver.ResolvedModel resolved, String modelName,
            String xmlSystemPrompt, String wrappedContent, PromptLengths promptLengths, ToolUseContext ctx) {
        long overallStart = System.currentTimeMillis();
        StageCallOutcome stage1 = null;
        try {
            // ===== Stage 1 (fast) · CC :768-858 =====
            StageCallOutcome s1 = runStage(provider, resolved, modelName, xmlSystemPrompt,
                wrappedContent + YoloPromptBuilder.XML_S1_SUFFIX, ctx, 1);
            stage1 = s1;
            Boolean s1Block = YoloPromptBuilder.parseXmlBlock(s1.text());
            // CC :807-823 — stage1 说 no → fast allow（'Allowed by fast classifier'），usage=stage1Usage
            if (Boolean.FALSE.equals(s1Block)) {
                if (log.isInfoEnabled()) {
                    log.info("[YoloClassifier] stage1 block=no → fast allow（CC yoloClassifier.ts:807-823）");
                }
                return new YoloClassifierResult(
                    null, false, "Allowed by fast classifier", false, false, modelName,
                    s1.usage(), s1.durationMs(), promptLengths, null, 1,
                    null, null, s1.requestId(), s1.msgId(), null, null, null, null);
            }
            // stage1 后 abort 复检 · CC :1261-1269
            if (isAborted(ctx)) {
                return YoloClassifierResult.unavailable("Classifier request aborted", modelName, 2);
            }

            // ===== Stage 2 (thinking) · CC :860-940 · stage1 block/null → stage2 =====
            StageCallOutcome s2 = runStage(provider, resolved, modelName, xmlSystemPrompt,
                wrappedContent + YoloPromptBuilder.XML_S2_SUFFIX, ctx, 2);
            Boolean s2Block = YoloPromptBuilder.parseXmlBlock(s2.text());
            // CC :889-891 — totalUsage = combineUsage(stage1Usage, stage2Usage)（:889-891）
            ClassifierUsage totalUsage = combineUsage(s1.usage(), s2.usage());
            if (s2Block == null) {
                // CC :898-917 — stage2 不可解析 → block for safety，usage=totalUsage + stage1/2Usage
                if (log.isWarnEnabled()) {
                    log.warn("YoloClassifier: stage2 不可解析 → block for safety（CC yoloClassifier.ts:898-917）");
                }
                return new YoloClassifierResult(
                    null, true, "Classifier stage 2 unparseable - blocking for safety", false, false, modelName,
                    totalUsage, s1.durationMs() + s2.durationMs(), promptLengths, null, 2,
                    s1.usage(), s1.durationMs(), s1.requestId(), s1.msgId(), s2.usage(), s2.durationMs(), s2.requestId(), s2.msgId());
            }
            // CC :919-940 — shouldBlock=stage2Block；thinking=parseXmlThinking；reason=parseXmlReason ?? 'No reason provided'
            String thinking = YoloPromptBuilder.parseXmlThinking(s2.text());
            String reason = YoloPromptBuilder.parseXmlReason(s2.text());
            if (reason == null) {
                reason = "No reason provided";
            }
            if (log.isInfoEnabled()) {
                log.info("[YoloClassifier] stage2 决策: shouldBlock={} reason={} thinkingSet={}（CC yoloClassifier.ts:919-940）",
                    s2Block, reason, thinking != null);
            }
            return new YoloClassifierResult(
                thinking, s2Block, reason, false, false, modelName,
                totalUsage, s1.durationMs() + s2.durationMs(), promptLengths, null, 2,
                s1.usage(), s1.durationMs(), s1.requestId(), s1.msgId(), s2.usage(), s2.durationMs(), s2.requestId(), s2.msgId());
        } catch (Exception e) {
            // [P3 返工 OPD-WF3-01-09] 对齐 CC yoloClassifier.ts:941-995 2-stage catch 顺序：
            //   catch 开头先判 signal.aborted（:941-953）→ 返回 unavailable 不带 errorDumpPath；
            //   非 abort 才 dumpErrorPrompts（:961-985）产出 errorDumpPath 填入结果。
            //   旧实现把 dump 作为参数预执行 —— abort 异常也会触发 dump 副作用（文件落盘），
            //   CC abort 分支（:945-952）不 dump；dump 顺序已后移进 handleStageError。
            return handleStageError(e, stage1, overallStart, promptLengths,
                xmlSystemPrompt, wrappedContent, ctx, modelName);
        }
    }

    /**
     * 1-stage classify_result 工具协议 · 对齐 CC classifyYoloAction 1-stage 分支
     * （yoloClassifier.ts:1131-1305）。
     *
     * <p>流程：system prompt（原样，含 TOOL_USE_LINE）→ 转录 + action（无 &lt;transcript&gt;
     * 包裹，:1040-1061）→ tools=[classify_result] + tool_choice（:1151-1155）→ 解析 tool_use
     * 块（:1193-1237）→ usage（:1168-1190 extractUsage）+ context-delta 遥测日志。
     *
     * @param provider      LLM provider
     * @param resolved      单模型真实配置
     * @param modelName     分类器模型名
     * @param systemPrompt  原样 system prompt（tool_use 输出格式）
     * @param userMessage   转录内容 + actionCompact（无 <transcript> 包裹）
     * @param promptLengths 三段 prompt 长度
     * @param ctx           工具调用上下文（abort 判定）
     * @return 分类结果（CC shouldBlock 契约）
     */
    private YoloClassifierResult classifySingleStage(
            LlmProvider provider, ModelConfigResolver.ResolvedModel resolved, String modelName,
            String systemPrompt, String userMessage, PromptLengths promptLengths, ToolUseContext ctx) {
        long overallStart = System.currentTimeMillis();
        try {
            // 工具协议：tools=[classify_result]（CC :1151-1155）
            ArrayNode tools = promptBuilder.buildClassifyResultToolsArray();
            // [AM-CC-20260825] provider 协议区分（用户拍板）：Anthropic 支持强制 named tool_choice
            //   （CC {type:'tool', name} 语义，传之）；openai_compatible（deepseek）推理模式不支持
            //   强制 named tool_choice（"Thinking mode does not support this tool_choice" 400，
            //   2026-08-25 联调实测）→ 不传（null → auto），tools 唯一 = classify_result 模型只能选它
            //   （对齐主循环 thinking 模式工具协议）。
            boolean isAnthropicProtocol = "anthropic".equals(resolved.providerType());
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(), tools, null, null, 0.0, "auto_mode",
                ctx != null ? ctx.abortController() : null,
                CLASSIFIER_SINGLE_STAGE_MAX_TOKENS, null,
                null, null, null, null, null,
                isAnthropicProtocol
                    ? LlmProvider.ChatRequestOptions.ToolChoice.tool(YoloPromptBuilder.CLASSIFY_RESULT_TOOL_NAME)
                    : null);
            AssistantMessage msg = callWithOptionsRetry(provider, resolved.config(), modelName,
                systemPrompt, userMessage, options, ctx);
            long durationMs = System.currentTimeMillis() - overallStart;
            // CC :1168-1173 usage 提取（chatWithOptionsMessage 返回 AgentUsage → ClassifierUsage）
            ClassifierUsage usage = extractUsage(msg.usage());
            String requestId = msg.requestId();
            // CC :1193-1213 — extractToolUseBlock 找 classify_result 块；无 → block for safety
            ToolUseBlock toolUse = findToolUseBlock(msg.toolCalls(), YoloPromptBuilder.CLASSIFY_RESULT_TOOL_NAME);
            if (toolUse == null) {
                if (log.isWarnEnabled()) {
                    log.warn("YoloClassifier: 1-stage 无 classify_result tool_use 块 → block for safety（CC yoloClassifier.ts:1198-1212）");
                }
                return YoloClassifierResult.singleStage(null, true,
                    "Classifier returned no tool use block - blocking for safety", modelName,
                    usage, durationMs, promptLengths, requestId, null);
            }
            // CC :1216-1237 — parseClassifierResponse（schema {thinking,shouldBlock,reason}）；非法 → block for safety
            ParsedClassifyResult parsed = parseClassifyResultInput(toolUse.input());
            if (parsed == null) {
                if (log.isWarnEnabled()) {
                    log.warn("YoloClassifier: 1-stage classify_result 输入非法（缺 thinking/shouldBlock/reason）→ block for safety（CC yoloClassifier.ts:1220-1236）");
                }
                return YoloClassifierResult.singleStage(null, true,
                    "Invalid classifier response - blocking for safety", modelName,
                    usage, durationMs, promptLengths, requestId, null);
            }
            if (log.isInfoEnabled()) {
                log.info("[YoloClassifier] 1-stage 决策: shouldBlock={} reason={} usage={}/{}/{}（CC yoloClassifier.ts:1239-1259）",
                    parsed.shouldBlock, parsed.reason,
                    usage != null ? usage.inputTokens() : -1,
                    usage != null ? usage.outputTokens() : -1,
                    usage != null ? usage.cacheReadInputTokens() : -1);
            }
            // CC :1239-1249 — classifierResult（thinking/shouldBlock/reason/usage/durationMs/promptLengths/stage1RequestId/stage1MsgId）
            return YoloClassifierResult.singleStage(parsed.thinking, parsed.shouldBlock,
                parsed.reason, modelName, usage, durationMs, promptLengths, requestId, null);
        } catch (Exception e) {
            // [P3 返工 OPD-WF3-01-09] 对齐 CC yoloClassifier.ts:1260-1305 1-stage catch 顺序：
            //   catch 开头先判 signal.aborted（:1261-1269）→ 返回 unavailable 不带 errorDumpPath；
            //   非 abort 才 dumpErrorPrompts（:1275-1303）产出 errorDumpPath。
            //   旧实现把 dump 作为参数预执行 —— abort 异常也会触发 dump 副作用；dump 顺序后移进
            //   handleSingleStageError。
            return handleSingleStageError(e, overallStart, promptLengths,
                systemPrompt, userMessage, ctx, modelName);
        }
    }

    /**
     * 1-stage 失败 → fail-closed 结果 · 对齐 CC classifyYoloAction 1-stage catch
     * （yoloClassifier.ts:1260-1305）。
     *
     * <p>[P3 返工] CC 语义（顺序重要，与 dump 副作用耦合）：
     * <ol>
     *   <li><b>先判 abort</b>（:1261-1269 {@code if (signal.aborted)}）→ 返回 unavailable=true，
     *       不带 errorDumpPath —— abort 不触发 dump 副作用；</li>
     *   <li>非 abort 才 {@code dumpErrorPrompts}（:1275-1303）产出 errorDumpPath 填入结果
     *       （dump 失败 → null）。</li>
     * </ol>
     * transcriptTooLong → transcriptTooLong=true（:1271/:1302）；其它 → unavailable=true（:1301）。
     *
     * @param e             调用失败异常
     * @param overallStart  调用起始时间戳（durationMs 计算）
     * @param promptLengths 三段 prompt 长度
     * @param systemPrompt  1-stage system prompt（CC dumpErrorPrompts systemPrompt，非 abort 才 dump）
     * @param userMessage   1-stage 用户消息 transcript+action（CC userPrompt，非 abort 才 dump）
     * @param ctx           工具调用上下文（abort 判定 + sessionId 取 dump 文件名）
     * @param modelName     分类器有效模型名（透传结果字段）
     * @return shouldBlock=true 的 fail-closed 结果
     */
    private YoloClassifierResult handleSingleStageError(
            Exception e, long overallStart, PromptLengths promptLengths,
            String systemPrompt, String userMessage, ToolUseContext ctx, String modelName) {
        // CC :1261 signal.aborted —— ctx 取消态 + 异常链 CancellationException 双检（
        //   callWithRetryGeneric abort 抛 CancellationException，Mid-call 取消经 CompletableFuture
        //   ExecutionException 包装仍走异常链）
        boolean aborted = isAborted(ctx) || isAbortedFrom(e);
        boolean tooLong = tokenEstimator.detectPromptTooLong(errorMessage(e)) != null;
        long durationMs = System.currentTimeMillis() - overallStart;
        if (aborted) {
            if (log.isWarnEnabled()) {
                log.warn("YoloClassifier: 1-stage 调用已中止（fail-closed block，不 dump）: {}", e.getMessage());
            }
            return YoloClassifierResult.unavailable("Classifier request aborted", modelName, 1);
        }
        // CC :1275-1303 非 abort 才 dump —— abort 分支已提前返回，此处不再有 dump 副作用
        String errorDumpPath = dumpClassifierErrorPrompts(systemPrompt, userMessage, e, ctx);
        String reason = tooLong
            ? "Classifier transcript exceeded context window"
            : "Classifier unavailable - blocking for safety";
        if (log.isWarnEnabled()) {
            log.warn("YoloClassifier: 1-stage 调用失败 (fail-closed block) tooLong={}: {}", tooLong, e.getMessage());
        }
        return new YoloClassifierResult(
            null, true, reason, true, tooLong, modelName,
            null, durationMs, promptLengths, errorDumpPath, 1,
            null, null, null, null, null, null, null, null);
    }

    /**
     * 2-stage API 失败 → fail-closed 结果 · 对齐 CC classifyYoloActionXml catch 分支
     * （yoloClassifier.ts:941-995）。
     *
     * <p>[P3 返工] CC 语义（顺序重要，与 dump 副作用耦合）：
     * <ol>
     *   <li><b>先判 abort</b>（:941-953 {@code if (signal.aborted)}）→ 返回 unavailable=true，
     *       不带 usage/stage1/errorDumpPath —— abort 不触发 dump 副作用；</li>
     *   <li>非 abort 才 {@code dumpErrorPrompts}（:961-985）产出 errorDumpPath 填入结果。</li>
     * </ol>
     * transcriptTooLong → transcriptTooLong=true；其它 → {@code unavailable: stage1Usage === undefined}
     * （:981，stage1 未跑成功才 unavailable=true），reason 按 stage1Usage 有无区分
     * 'Stage 2 classifier error...' / 'Classifier unavailable...'（:973-980）；
     * stage1 已成功 → usage/stage1Usage/stage1DurationMs/stage1RequestId/stage1MsgId 透传（:986-992）。
     *
     * @param e             调用失败异常
     * @param stage1        stage1 调用结果（null = stage1 未成功，CC stage1Usage === undefined）
     * @param overallStart  调用起始时间戳（durationMs 计算）
     * @param promptLengths 三段 prompt 长度
     * @param systemPrompt  2-stage XML system prompt（CC dumpErrorPrompts systemPrompt，非 abort 才 dump）
     * @param userMessage   2-stage <transcript> 包裹内容（CC userPrompt，非 abort 才 dump）
     * @param ctx           工具调用上下文（abort 判定 + sessionId 取 dump 文件名）
     * @param modelName     分类器有效模型名（透传结果字段）
     * @return shouldBlock=true 的 fail-closed 结果
     */
    private YoloClassifierResult handleStageError(
            Exception e, StageCallOutcome stage1, long overallStart, PromptLengths promptLengths,
            String systemPrompt, String userMessage, ToolUseContext ctx, String modelName) {
        // CC :941 signal.aborted —— ctx 取消态 + 异常链 CancellationException 双检
        boolean aborted = isAborted(ctx) || isAbortedFrom(e);
        boolean tooLong = tokenEstimator.detectPromptTooLong(errorMessage(e)) != null;
        long durationMs = System.currentTimeMillis() - overallStart;
        boolean stage1Done = stage1 != null;
        String reason;
        if (aborted) {
            reason = "Classifier request aborted";
        } else if (tooLong) {
            reason = "Classifier transcript exceeded context window";
        } else if (stage1Done) {
            reason = "Stage 2 classifier error - blocking based on stage 1 assessment";
        } else {
            reason = "Classifier unavailable - blocking for safety";
        }
        // CC :981 unavailable = stage1Usage === undefined；abort → unavailable=true（:948）
        boolean unavailable = aborted || (!stage1Done && !tooLong);
        int stage = stage1Done ? 2 : 1;
        if (log.isWarnEnabled()) {
            log.warn("YoloClassifier: classifier 调用失败 (fail-closed block) aborted={} tooLong={} stage1Done={}: {}",
                aborted, tooLong, stage1Done, e.getMessage());
        }
        if (aborted) {
            // CC :945-952 abort 返回不带 usage/stage1/errorDumpPath 字段（且不 dump）
            return new YoloClassifierResult(
                null, true, reason, true, false, modelName,
                null, durationMs, promptLengths, null, stage,
                null, null, null, null, null, null, null, null);
        }
        // CC :961-985 非 abort 才 dump —— abort 分支已提前返回，此处不再有 dump 副作用
        String errorDumpPath = dumpClassifierErrorPrompts(systemPrompt, userMessage, e, ctx);
        if (stage1Done) {
            // CC :986-992 stage1 已成功 → usage=stage1Usage + stage1 全字段透传
            ClassifierUsage u = stage1.usage();
            return new YoloClassifierResult(
                null, true, reason, unavailable, tooLong, modelName,
                u, durationMs, promptLengths, errorDumpPath, stage,
                u, stage1.durationMs(), stage1.requestId(), stage1.msgId(), null, null, null, null);
        }
        return new YoloClassifierResult(
            null, true, reason, unavailable, tooLong, modelName,
            null, durationMs, promptLengths, errorDumpPath, stage,
            null, null, null, null, null, null, null, null);
    }

    /**
     * [P3 · OPD-WF3-01-09] 分类器错误 prompts dump · 对齐 CC {@code dumpErrorPrompts}
     * （yoloClassifier.ts:213-250）→ {@code getAutoModeClassifierErrorDumpPath}（:186-192
     * {@code join(getClaudeTempDir(), 'auto-mode-classifier-errors', sessionId.txt)}）。
     *
     * <p>CC 在非 abort 分类错误时无条件 dump（供 /share 收集，无 USER_TYPE 门——门只在
     * 通知侧 permissions.ts:705-716）；dump 失败返回 null（CC :247-249 catch → null）。
     *
     * @param systemPrompt system prompt（2-stage 为 replaceOutputFormatWithXml 后的 XML 版）
     * @param userMessage  用户消息（transcript + action）
     * @param e            分类失败异常
     * @param ctx          工具调用上下文（sessionId 取 dump 文件名）
     * @return dump 文件绝对路径；dump 失败 → null
     */
    private String dumpClassifierErrorPrompts(String systemPrompt, String userMessage, Throwable e, ToolUseContext ctx) {
        try {
            String tempRoot = NexusaiPaths.getAppTempDir();
            Path dumpDir = Path.of(tempRoot, "auto-mode-classifier-errors");
            Files.createDirectories(dumpDir);
            // [session-id-short] ctx.sessionId() 已 String，恒等直传
            String sessionId = ctx != null ? ctx.sessionId() : null;
            String fileName = (sessionId != null ? sessionId : "unknown") + ".txt";
            Path dumpPath = dumpDir.resolve(fileName);
            String content = "=== ERROR ===\n" + errorMessage(e) + "\n\n"
                + "=== SYSTEM PROMPT ===\n" + systemPrompt + "\n\n"
                + "=== USER PROMPT (transcript) ===\n" + userMessage + "\n";
            Files.writeString(dumpPath, content, StandardCharsets.UTF_8);
            if (log.isInfoEnabled()) {
                log.info("YoloClassifier: auto-mode 分类器错误 prompts 已 dump（CC dumpErrorPrompts）: {}", dumpPath);
            }
            return dumpPath.toString();
        } catch (Throwable dumpFail) {
            if (log.isDebugEnabled()) {
                log.debug("YoloClassifier: 错误 prompts dump 失败 → errorDumpPath null: {}", dumpFail.getMessage());
            }
            return null;
        }
    }

    /**
     * [IMP-6 OPD-WF6-02-RV] usage 提取（chatWithRaw 通道）· 对齐 CC {@code extractUsage}
     * （yoloClassifier.ts:609-618）：从 API 响应 usage 提取 4 token 字段。
     *
     * @param raw chatWithRaw 原始响应（usage 可为 null = provider 未暴露）
     * @return ClassifierUsage；raw/usage 为 null → null（Java 如实暴露缺口，CC 恒有值）
     */
    private static ClassifierUsage extractUsage(LlmProvider.LlmRawResponse raw) {
        if (raw == null || raw.usage() == null) {
            return null;
        }
        LlmProvider.LlmUsage u = raw.usage();
        return new ClassifierUsage(u.inputTokens(), u.outputTokens(),
            u.cacheReadInputTokens(), u.cacheCreationInputTokens());
    }

    /**
     * [IMP-6 OPD-WF6-02-RV] usage 提取（chatWithOptionsMessage 通道）· 对齐 CC {@code extractUsage}
     * （yoloClassifier.ts:609-618）。
     *
     * <p>Java 1-stage 经 chatWithOptionsMessage 返回 {@link AssistantMessage#usage()}
     * （AgentUsage，零初始化 EMPTY 哨兵）；4 token 字段（long）投影到 CC ClassifierUsage（int）。
     *
     * @param u AgentUsage（null/EMPTY → 全零 ClassifierUsage，对齐 CC 缺省 0 语义）
     * @return ClassifierUsage（恒非 null）
     */
    private static ClassifierUsage extractUsage(AgentUsage u) {
        if (u == null) {
            return ClassifierUsage.empty();
        }
        return new ClassifierUsage(
            toIntUsage(u.inputTokens()), toIntUsage(u.outputTokens()),
            toIntUsage(u.cacheReadInputTokens()), toIntUsage(u.cacheCreationInputTokens()));
    }

    /** long token 计数 → int（超上限截断，实际 token 计数远小于 2^31）。 */
    /** AgentUsage 4 token 字段为 Long（nullable，CC agentToolUtils.ts:241-242 nullable）·
     *  DeepSeek 不返回 cache_creation_input_tokens → null → 0（对齐 CC extractUsage `?? 0`，
     *  2026-08-25 联调实测 NPE）。 */
    private static int toIntUsage(Long v) {
        if (v == null) {
            return 0;
        }
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v.intValue();
    }

    /**
     * [IMP-6 OPD-WF6-02-RV] 合并两段 usage · 对齐 CC {@code combineUsage}
     * （yoloClassifier.ts:633-641）：四字段逐项相加（null 安全）。
     *
     * @param a stage1 usage（可为 null）
     * @param b stage2 usage（可为 null）
     * @return 合并 usage；两端均 null → null
     */
    private static ClassifierUsage combineUsage(ClassifierUsage a, ClassifierUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new ClassifierUsage(
            a.inputTokens() + b.inputTokens(),
            a.outputTokens() + b.outputTokens(),
            a.cacheReadInputTokens() + b.cacheReadInputTokens(),
            a.cacheCreationInputTokens() + b.cacheCreationInputTokens());
    }

    /**
     * [IMP-6] 在 assistant toolCalls 中找指定名 tool_use 块 · 对齐 CC
     * {@code extractToolUseBlock(result.content, YOLO_CLASSIFIER_TOOL_NAME)}
     * （classifierShared.ts:15-39，yoloClassifier.ts:1193-1196）。
     *
     * @param toolCalls LLM 返回的工具调用列表（可为空）
     * @param toolName  目标工具名（classify_result）
     * @return 匹配的工具调用块；无 → null
     */
    private static ToolUseBlock findToolUseBlock(List<ToolUseBlock> toolCalls, String toolName) {
        if (toolCalls == null) {
            return null;
        }
        for (ToolUseBlock tc : toolCalls) {
            if (tc != null && toolName.equals(tc.name())) {
                return tc;
            }
        }
        return null;
    }

    /**
     * [IMP-6] 解析 classify_result 工具输入 · 对齐 CC {@code parseClassifierResponse}
     * （classifierShared.ts）+ yoloClassifierResponseSchema（yoloClassifier.ts:252-258）
     * {@code {thinking: string, shouldBlock: boolean, reason: string}}。
     *
     * <p>三字段均要求存在且类型正确（zod safeParse 语义）；任一缺失/类型不符 →
     * null（等价 schema 解析失败 → 'Invalid classifier response - blocking for safety'）。
     *
     * @param input classify_result 工具输入 JSON
     * @return (thinking, shouldBlock, reason)；非法 → null
     */
    private static ParsedClassifyResult parseClassifyResultInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode sb = input.get("shouldBlock");
        JsonNode thinking = input.get("thinking");
        JsonNode reason = input.get("reason");
        if (sb == null || !sb.isBoolean()) {
            return null;
        }
        if (thinking == null || !thinking.isTextual()) {
            return null;
        }
        if (reason == null || !reason.isTextual()) {
            return null;
        }
        return new ParsedClassifyResult(thinking.asText(), sb.asBoolean(), reason.asText());
    }

    /** 1-stage classify_result 解析结果 · (thinking, shouldBlock, reason)。 */
    private record ParsedClassifyResult(String thinking, boolean shouldBlock, String reason) {}

    /**
     * abort 判定 · 对齐 CC {@code signal.aborted}（yoloClassifier.ts:1261）。
     */
    private static boolean isAborted(ToolUseContext ctx) {
        return ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled();
    }

    /**
     * 从异常链识别 abort（CancellationException 包装面）。
     */
    private static boolean isAbortedFrom(Throwable e) {
        Throwable cur = e;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 提取异常消息（含 cause 链第一层，供 detectPromptTooLong 判定）。
     */
    private static String errorMessage(Throwable e) {
        if (e == null) {
            return "";
        }
        String msg = e.getMessage();
        if (msg != null && e.getCause() != null && e.getCause().getMessage() != null) {
            return msg + " " + e.getCause().getMessage();
        }
        return msg != null ? msg : e.toString();
    }

    /**
     * [S06 ⊕-09] 单模型解析 · 对齐 CC getClassifierModel()（yoloClassifier.ts:1334-1361
     * env→GB→mainLoop）。Java 端：配置 modelName → resolve；失败 → resolveFastModelName
     * （settings fast/main，Java getMainLoopModel 兜底）→ 再 resolve。失败 → null
     * （callWithRetry 不发起，fail-closed block）。
     *
     * <p>[V45] 返回 {@link ClassifierResolution}（resolved + 有效模型名）：null modelId
     * （yml/DB 均未配置）也能经 resolveFastModelName 兜底解析出 fast 模型配置，且 modelName
     * 回填兜底名——保证 LLM model 参数与实际 resolved 配置一致（避免 model 字符串 null /
     * 与 config 错配导致 API 400）。
     *
     * @param modelId 分类器模型配置名（yml 覆写 / DB settings.classifierModel；可 null = 未配置）
     * @return (resolved, 有效模型名)；解析失败 → null
     */
    private ClassifierResolution resolveClassifierModel(String modelId) {
        if (modelConfigResolver == null) {
            log.warn("YoloClassifierImpl: ModelConfigResolver 未注入，跳过配置解析（warn+skip 不落 mock）");
            return null;
        }
        ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelId);
        if (r != null) {
            return new ClassifierResolution(r, sdkModelName(modelId));
        }
        // CC getMainLoopModel() 兜底：settings fast/main → DB 名 → resolve（yoloClassifier.ts:1361）
        String fallbackName = modelConfigResolver.resolveFastModelName(modelId);
        if (fallbackName != null && !fallbackName.equals(modelId)) {
            r = modelConfigResolver.resolve(fallbackName);
            if (r != null) {
                if (log.isInfoEnabled()) {
                    log.info("YoloClassifierImpl: 分类模型 {} 配置未命中，回退主循环 fast 模型名 {} 解析成功",
                        modelId, fallbackName);
                }
                return new ClassifierResolution(r, sdkModelName(fallbackName));
            }
        }
        log.warn("YoloClassifierImpl: 模型 {} 配置解析失败（无 enabled model/provider/apiKey），分类将 fail-closed block",
            modelId);
        return null;
    }

    /** [AM-CC-20260825] 剥 provider 前缀 → SDK model 裸名（对齐主循环 ModelCaller 用
     *  {@code resolveSdkModelName} 发 API 的做法，FIX-STRIP-PREFIX）：classifier chatWithRaw
     *  直接传带前缀 {@code deepseek/deepseek-v4-flash} → DeepSeek API 400（2026-08-25 联调实测）。
     *  未命中 → 回落原始值透传。 */
    private String sdkModelName(String modelId) {
        if (modelConfigResolver == null) {
            return modelId;
        }
        String sdk = modelConfigResolver.resolveSdkModelName(modelId);
        return sdk != null ? sdk : modelId;
    }

    /** (真实配置, 有效模型名) 解析载体 · [V45] 新增 modelName 字段：resolveClassifierModel 兜底后
     *  回填实际生效模型名（可能是主循环 fast 名），供 LLM model 参数 + 结果字段透传。 */
    private record ClassifierResolution(ModelConfigResolver.ResolvedModel resolved, String modelName) {}

    /**
     * [V45] 有效分类器模型名 · 对齐 CC getClassifierModel()（yoloClassifier.ts:1345-1361）。
     *
     * <p>优先级（严格对齐 CC）：
     * <ol>
     *   <li><b>yml 覆写</b>{@code nexusai.classifier.model}（Java 显式覆写槽，等价 CC ant env
     *       CLAUDE_CODE_AUTO_MODE_MODEL 的 Java 侧；但 Java 无 USER_TYPE 区分，恒优先）</li>
     *   <li><b>DB settings.classifierModel</b>（V45 列 · CC original: tengu_auto_mode_config.model，
     *       yoloClassifier.ts:1350-1356，前端可配置）</li>
     *   <li><b>未配置/空白 → null</b>（resolveClassifierModel(null) 走 resolveFastModelName
     *       主循环兜底，等价 CC getMainLoopModel() :1361；全未配置 → fail-closed block）</li>
     * </ol>
     * CC poor mode → getDefaultSonnetModel（:1358-1360）为 ant 内部变体，Java N/A（同既有惯例）。
     *
     * @return 有效模型名（yml → DB → null）；每次调用实时读 DB（settings 单行，廉价）
     */
    private String classifierModelName() {
        if (ymlModelName != null && !ymlModelName.isBlank()) {
            return ymlModelName;
        }
        String dbModel = readDbClassifierModel();
        return (dbModel != null && !dbModel.isBlank()) ? dbModel : null;
    }

    /**
     * [V45] 读 DB settings.classifierModel（V45 列，settings 单例行 id=1）。
     *
     * <p><b>fail-soft</b>：settingsMapper 未注入（POJO 单测/无 Spring）/ 行 null / 异常 → null
     * （未配置 → 主循环兜底，零行为变化）。对齐 InitialPermissionModeSource.readDbGlobalPermissionMode
     * （:151-172）容错先例。
     *
     * @return settings.classifier_model 列值（trim 后）；未配置 / 异常 → null
     */
    private String readDbClassifierModel() {
        if (settingsMapper == null) {
            return null;
        }
        try {
            SettingsRecord s = settingsMapper.selectOneById(1);
            if (s == null) {
                return null;
            }
            String model = s.getClassifierModel();
            return (model == null || model.isBlank()) ? null : model.trim();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("YoloClassifierImpl: 读 settings.classifier_model 失败，回落主循环兜底: {}", e.toString());
            }
            return null;
        }
    }

    /**
     * 单 stage LLM 调用 · 对齐 CC sideQuery stage1/stage2（yoloClassifier.ts:795-796/881-882）。
     *
     * <p>[S06 OPD-WF6-07] 重试：CC sideQuery maxRetries=getDefaultMaxRetries()（429/5xx），
     * Java chatWithRaw 无内置重试 → {@link #callWithRetry} maxRetries=10 指数退避。
     *
     * <p>durationMs = chatWithRaw 返回后立即测量（纯 LLM 调用，对齐 CC stage1DurationMs
     * = Date.now() - stage1Start，:796/:882）。
     *
     * <p>[IMP-6 OPD-WF6-02-RV] usage：chatWithRaw 原始响应携带 usage（LlmRawResponse.usage，
     * AnthropicSdkProvider 从 message.usage 提取）→ extractUsage 填充 StageCallOutcome.usage。
     *
     * @return (text, msgId, requestId, durationMs, usage) 五元组
     */
    private StageCallOutcome runStage(
            LlmProvider provider, ModelConfigResolver.ResolvedModel resolved, String modelName,
            String systemPrompt, String userMessage, ToolUseContext ctx, int stageNum) throws Exception {
        long startMs = System.currentTimeMillis();
        LlmProvider.LlmRawResponse raw = callWithRetry(provider, resolved.config(), modelName,
            systemPrompt, userMessage, ctx);
        long durationMs = System.currentTimeMillis() - startMs;
        if (log.isInfoEnabled()) {
            log.info("[YoloClassifier] stage {} chatWithRaw 提取: msgId={} requestId={} contentLen={} usage={}",
                stageNum, raw.id(), raw.requestId(), raw.content().length(), raw.usage() != null);
        }
        return new StageCallOutcome(raw.content(), raw.id(), raw.requestId(), durationMs,
            extractUsage(raw));
    }

    /**
     * [S06 OPD-WF6-07] 分类器调用重试 · 对齐 CC sideQuery
     * {@code maxRetries: getDefaultMaxRetries()}（withRetry.ts:789-797，429/5xx）。
     *
     * <p>Java chatWithRaw 无内置重试（实证 OPD-WF6-07）→ 本方法实现：
     * 429/5xx（含 529）→ 指数退避（0.5s × 2^attempt，cap 8s）重试至多 10 次；
     * abort → 取消；其它错误（400/超时）→ 原样上抛（fail-closed，重试无意义）。
     */
    private LlmProvider.LlmRawResponse callWithRetry(
            LlmProvider provider, ProviderConfig config, String modelName,
            String systemPrompt, String userMessage, ToolUseContext ctx) throws Exception {
        return callWithRetryGeneric(() -> callWithMdc(provider, config, modelName, systemPrompt, userMessage), ctx);
    }

    /**
     * [IMP-6] 1-stage 分类器调用（chatWithOptionsMessage 通道）+ 重试 · 对齐 CC sideQuery
     * maxRetries=getDefaultMaxRetries()（withRetry.ts:789-797，429/5xx）。
     *
     * <p>工具协议（tools + tool_choice）只能经 {@code chatWithOptionsMessage} 发送
     * （chatWithRaw 无 tools 通道）；usage 从返回的 {@link AssistantMessage#usage()}
     * （AgentUsage）提取。
     */
    private AssistantMessage callWithOptionsRetry(
            LlmProvider provider, ProviderConfig config, String modelName,
            String systemPrompt, String userMessage,
            LlmProvider.ChatRequestOptions options, ToolUseContext ctx) throws Exception {
        return callWithRetryGeneric(() ->
            callWithOptionsMdc(provider, config, modelName, systemPrompt, userMessage, options), ctx);
    }

    /**
     * 分类器调用重试（泛型）· 对齐 CC sideQuery maxRetries=getDefaultMaxRetries()（429/5xx）。
     *
     * <p>429/5xx（含 529）→ 指数退避（0.5s × 2^attempt，cap 8s）重试至多 10 次；
     * abort → 取消；其它错误（400/超时）→ 原样上抛（fail-closed，重试无意义）。
     */
    private <T> T callWithRetryGeneric(ClassifierCallable<T> call, ToolUseContext ctx) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (isAborted(ctx)) {
                throw new CancellationException("classifier aborted before attempt " + (attempt + 1));
            }
            try {
                return call.call();
            } catch (Exception e) {
                if (attempt >= DEFAULT_MAX_RETRIES || !isRetryableClassifierError(e)) {
                    throw e;
                }
                long delayMs = Math.min(RETRY_BASE_DELAY_MS << attempt, RETRY_MAX_DELAY_MS);
                if (log.isWarnEnabled()) {
                    log.warn("[YoloClassifier] stage API 瞬时错误（429/5xx）重试 {}/{}（CC sideQuery maxRetries=getDefaultMaxRetries()=10）: {}",
                        attempt + 1, DEFAULT_MAX_RETRIES, e.getMessage());
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("classifier retry interrupted");
                }
            }
        }
    }

    /** 分类器 LLM 调用闭包 · 供 {@link #callWithRetryGeneric} 复用。 */
    private interface ClassifierCallable<T> {
        T call() throws Exception;
    }

    /**
     * chatWithRaw 调用 · supplyAsync 虚拟线程不继承创建线程 ThreadLocal → 捕获 MDC context
     * map 回放（对齐 LlmAgentLoop 既有 STREAM_EXECUTOR 回放模式）；finally clear 防污染。
     */
    private LlmProvider.LlmRawResponse callWithMdc(
            LlmProvider provider, ProviderConfig config, String modelName,
            String systemPrompt, String userMessage) throws Exception {
        final java.util.Map<String, String> mdcCtx = org.slf4j.MDC.getCopyOfContextMap();
        if (log.isDebugEnabled()) {
            log.debug("YoloClassifier: stage 调用 {}", modelName);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (mdcCtx != null) {
                org.slf4j.MDC.setContextMap(mdcCtx);
            }
            try {
                return provider.chatWithRaw(config, modelName, systemPrompt, userMessage);
            } finally {
                org.slf4j.MDC.clear();
            }
        }).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * [IMP-6] chatWithOptionsMessage 调用 · 与 {@link #callWithMdc} 同构（MDC 回放 + 超时）。
     *
     * <p>1-stage classify_result 工具协议的唯一发送通道（tools + tool_choice 仅本方法支持）。
     */
    private AssistantMessage callWithOptionsMdc(
            LlmProvider provider, ProviderConfig config, String modelName,
            String systemPrompt, String userMessage,
            LlmProvider.ChatRequestOptions options) throws Exception {
        final java.util.Map<String, String> mdcCtx = org.slf4j.MDC.getCopyOfContextMap();
        if (log.isDebugEnabled()) {
            log.debug("YoloClassifier: 1-stage classify_result 调用 {}", modelName);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (mdcCtx != null) {
                org.slf4j.MDC.setContextMap(mdcCtx);
            }
            try {
                return provider.chatWithOptionsMessage(config, modelName, systemPrompt, userMessage, options);
            } finally {
                org.slf4j.MDC.clear();
            }
        }).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * 分类器重试判定 · 对齐 CC withRetry.ts 429/5xx（含 529）。
     * 解包 cause 链（≤5 层，AnthropicSdkProvider:865 全异常包 RuntimeException 面）找
     * {@link LlmApiException}。
     *
     * @param e 调用失败异常（LlmApiException / 任意包装）
     * @return true=可重试（429/5xx）；false=不可重试（400/超时/逻辑错误）
     */
    static boolean isRetryableClassifierError(Throwable e) {
        Throwable cur = e;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur instanceof LlmApiException lae) {
                int status = lae.status();
                return status == 429 || status >= 500;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * [OPD-24 G1] 投影 lookup · 对齐 CC {@code buildToolLookup}（yoloClassifier.ts:364-374）：
     * 遍历 {@code toolRegistry.all()}，name + 每个 alias → Tool 实例。查不到 → 跳过该块
     * （CC :390-391）。toolRegistry 未注入 / 异常 → 空表。
     *
     * @return name/alias → Tool 的投影查表（不可变视图，可为空）
     */
    private Map<String, Tool> buildProjectionLookup() {
        if (toolRegistry == null) {
            return Map.of();
        }
        try {
            Map<String, Tool> lookup = new HashMap<>();
            for (Tool tool : toolRegistry.all()) {
                lookup.put(tool.name(), tool);
                for (String alias : tool.aliases()) {
                    if (alias != null && !alias.isBlank()) {
                        lookup.put(alias, tool);
                    }
                }
            }
            return lookup;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("YoloClassifierImpl: 构建投影 lookup 失败 → 空表（全部跳过分类）: {}", e.getMessage());
            }
            return Map.of();
        }
    }

    /**
     * 单 stage LLM 调用结果 · (text, msgId, requestId, durationMs, usage) 五元组。
     *
     * <p>[S06] 对齐 CC stage1Raw/stage2Raw（yoloClassifier.ts:795-799/881-885）：
     * text=content 拼接、msgId=id（:799/:885）、requestId=extractRequestId（:798/:884）、
     * durationMs=stage 时长（:796/:882）。
     *
     * <p>[IMP-6 OPD-WF6-02-RV] usage = extractUsage(raw)（:797/:883）——stage1/stage2 各自
     * token 用量，供 combineUsage 汇总（:889-891）。
     *
     * @param usage 该 stage 的 API token 用量（provider 未暴露 → null）
     */
    private record StageCallOutcome(String text, String msgId, String requestId, long durationMs, ClassifierUsage usage) {}
}
