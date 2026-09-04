package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptSplitter;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 生产 ForkedQuery · 对齐 CC {@code query()} fork 语义
 * (Open-ClaudeCode/src/query.ts:181-199 QueryParams + forkedAgent.ts:545-556)。
 *
 * <p><b>WHY 存在（IMP-M-P0-3 生产接线 · 先行者风险 R9/IMP-18）</b>: extract-memories /
 * auto-dream 后台 fork（querySource='extract_memories'/'auto_dream'）在生产必须真实执行
 * 多轮 LLM loop —— 本类把 {@link RunForkedAgent.ForkedQuery} seam 接真实 provider loop，
 * 使 extract（skipTranscript + maxTurns=5 + 受限 canUseTool）/ auto-dream 生产真正执行
 * fork（对齐 CC extractMemories.ts:415-427 / autoDream.ts:224-233）。
 *
 * <p><b>为什么 fork 不复用 LlmAgentLoop.queryLoop（INV-6 破坏风险）</b>: 主循环的权限消费点
 * 在 StreamingToolExecutor 内层（继承主线程 permissionGate），{@code QueryParams.canUseTool}
 * 无消费点（H9-GAP-4，QueryParams.java:45 已删）——直接复用会让 fork 继承主线程权限，
 * 破坏 INV-6 的受限 canUseTool（Read/Grep/Glob + 只读 Bash + auto-memory 目录内 Edit/Write）。
 * 本类专用 loop 直接用 {@link HookPermissionResolver#resolve} 消费调用方传入的
 * {@code canUseTool}，保证 extract/auto-dream 的工具权限受限语义真实生效。
 *
 * <p><b>loop 语义（对齐 CC query() fork）</b>:
 * <ol>
 *   <li>逐轮 provider 调用（阻塞桥接，复用 {@code StreamCompactSummary.streamOnce} 模式，
 *       compact 包私有不可跨包调用，故本地复刻）</li>
 *   <li>有 tool_calls → 逐条经 canUseTool 门控（HookPermissionResolver.resolve）→
 *       ToolRegistry.dispatch 执行 → tool_result 追加消息，继续下一轮</li>
 *   <li>无 tool_calls → 最终回答，终止</li>
 *   <li>maxTurns 达上限 → 终止（对齐 CC query.ts:1705-1710 max_turns_reached）</li>
 * </ol>
 *
 * <p><b>usage 累计（IMP2-19 S-11 + IMP-MV2-10 全量保真）</b>: CC 从 message_delta stream 事件
 * 累计真实 usage（forkedAgent.ts:557-566 updateUsage/accumulateUsage 四字段全量）；Java 每轮
 * provider 调用 = 一次 API call，{@link com.nexusai.infra.llm.AssistantMessage#usage()}
 * （AgentUsage · AnthropicSdkProvider 从 message_start/message_delta 解析 input/output/
 * cache_creation/cache_read，AssistantMessage.java:29-37）<b>逐轮全量累加</b>，totalUsage
 * 对齐 CC NonNullableUsage（null → 0）。全程无 usage 上报（全零）时 fail-loud 记日志。
 */
public class ProductionForkedQuery implements RunForkedAgent.ForkedQuery {

    private static final Logger log = LoggerFactory.getLogger(ProductionForkedQuery.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // [IMP-GAP04 △-15] §7-10 默认裁决对齐 CC：CC 无 300s 级硬超时（forkedAgent.ts query()
    //   靠 abortController + SDK 状态，流一直持续则等待）。旧 STREAM_AWAIT_TIMEOUT_MS=300s 为
    //   Java 独有（△-15，大会话慢网络提前失败风险 13）——已删除；StreamCompactSummary 已于
    //   IMP2-15 同裁决移除（compact 路径），本文件为 fork 路径同等待遇（当时登记遗漏，GAP04
    //   补齐）。等待改为无界（future.get() 无超时），取消路径保留（abortController → 下方
    //   CancellationException 分支）。

    /** provider 解析（生产：llmProviderFactory 按 config 分发；测试：fake） */
    private final Supplier<LlmProvider> providerSupplier;

    /** 当前模型（CC context.options.mainLoopModel；null → provider 默认） */
    private final Supplier<String> modelSupplier;

    /** provider 运行时配置（baseUrl + apiKey） */
    private final Supplier<ProviderConfig> configSupplier;

    /** 工具注册表（fork 工具执行） */
    private final ToolRegistry toolRegistry;

    /** canUseTool 门控解析器（resolve(hookResult=null, updatedInput=null, ..., canUseTool)） */
    private final HookPermissionResolver permissionResolver;

    /**
     * 构造 · 由 ToolRegistrationConfig 生产注入。
     *
     * @param providerSupplier  provider 解析
     * @param modelSupplier     当前模型（可为 null Supplier）
     * @param configSupplier    provider 配置解析
     * @param toolRegistry      工具注册表（执行 fork 的 Read/Write/Edit/Bash 等）
     */
    public ProductionForkedQuery(Supplier<LlmProvider> providerSupplier,
                                 Supplier<String> modelSupplier,
                                 Supplier<ProviderConfig> configSupplier,
                                 ToolRegistry toolRegistry) {
        this.providerSupplier = providerSupplier;
        this.modelSupplier = modelSupplier;
        this.configSupplier = configSupplier;
        this.toolRegistry = toolRegistry;
        this.permissionResolver = new HookPermissionResolver();
    }

    /**
     * 构造（测试友好）· 注入 HookPermissionResolver（sandbox 语义可选）。
     *
     * @param providerSupplier    provider 解析
     * @param modelSupplier       当前模型
     * @param configSupplier      provider 配置解析
     * @param toolRegistry        工具注册表
     * @param permissionResolver  权限解析器（null → 内部 new）
     */
    public ProductionForkedQuery(Supplier<LlmProvider> providerSupplier,
                                 Supplier<String> modelSupplier,
                                 Supplier<ProviderConfig> configSupplier,
                                 ToolRegistry toolRegistry,
                                 HookPermissionResolver permissionResolver) {
        this.providerSupplier = providerSupplier;
        this.modelSupplier = modelSupplier;
        this.configSupplier = configSupplier;
        this.toolRegistry = toolRegistry;
        this.permissionResolver = permissionResolver != null ? permissionResolver : new HookPermissionResolver();
    }

    /**
     * [SM-02] 当前模型供应器访问器 · CC original: {@code context.options.mainLoopModel}
     * （CC 主循环每轮解析的运行时模型）。供 SessionMemoryService 的 SessionStart hooks
     * model 载荷复用同一源（getMainLoopModel 等价）。
     *
     * @return model supplier（可为 null）
     */
    public Supplier<String> modelSupplier() {
        return modelSupplier;
    }

    /**
     * [G-81] provider 运行时配置供应器访问器 · 供 ToolRegistrationConfig 的 RES-C5
     * firstParty gate 接线（GlobalCacheScope 求值）与 compact 同源取数
     * （buildForkSuppliers 单一来源，无第二份解析逻辑）。
     *
     * @return config supplier（可为 null）
     */
    public Supplier<ProviderConfig> configSupplier() {
        return configSupplier;
    }

    // ════════════════════════════════════════════════════════════════════
    // ForkedQuery 实现 · 多轮 fork loop（对齐 CC query() fork 语义）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 运行多轮 fork loop · 对齐 CC {@code for await (const message of query({...}))}
     * （forkedAgent.ts:545-556 query() 透传 maxTurns/skipCacheWrite/canUseTool）。
     *
     * <p>逐轮 provider 调用：消息列表 = initialMessages（forkContextMessages + promptMessages，
     * 由 {@link RunForkedAgent#run} 构造）+ 每轮追加的 assistant 消息 + tool_result 消息。
     * 工具调用经 {@link #executeGatedTool} 门控执行（INV-6 受限 canUseTool）。
     *
     * @param params query() 透传参数（maxTurns / maxOutputTokensOverride / canUseTool /
     *               toolUseContext / systemPrompt）
     * @return fork 结果（全部产出消息 + 累计 usage）
     */
    @Override
    public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
        long startTime = System.currentTimeMillis();
        String model = modelSupplier != null ? modelSupplier.get() : null;
        ProviderConfig config = configSupplier != null ? configSupplier.get() : null;
        LlmProvider provider = providerSupplier != null ? providerSupplier.get() : null;
        if (provider == null) {
            throw new IllegalStateException("[ProductionForkedQuery] provider 未注入（providerSupplier 返回 null），无法执行 fork loop");
        }

        // [IMP-MV2-09 T9] userContext 前置 meta user 消息 · 对齐 CC query.ts:660
        //   {@code prependUserContext(messagesForQuery, userContext)} —— fork 重建主线程
        //   消息前缀（forkedAgent.ts:545-556 透传 userContext 后 fork 自身 query 再前置）；
        //   CloudeMd/currentDate 等上下文是 Anthropic prompt cache 前缀一部分，不前置则
        //   fork 消息前缀与主线程不一致（缓存永不命中）。空 userContext → 原列表（no-op）。
        //   StreamCompactSummary.withUserContextPrepended 同款语义（compact fork 路径先例）。
        List<ChatMessageDto> runningMessages = new ArrayList<>(
            com.nexusai.application.agent.loop.AgentLoopContext.prependUserContext(
                new ArrayList<>(params.messages()), params.userContext()));
        List<ChatMessageDto> outputMessages = new ArrayList<>();
        ForkedAgentResult.ForkUsage totalUsage = ForkedAgentResult.ForkUsage.empty();

        ToolUseContext forkCtx = params.toolUseContext();
        AbortController abort = forkCtx != null && forkCtx.abortController() != null
            ? forkCtx.abortController() : AbortController.NOOP;

        // tools ArrayNode（cache-safe：fork 用主线程工具集，availableTools 由 cacheSafeParams
        //   toolUseContext 继承 —— CC tools 是 cache key 一部分，fork 不能换工具集）
        ArrayNode tools = buildToolsArray(forkCtx);

        // [RES-C6] 发送边界 boundary 剥离 → blocks 数组（对齐主线程 LlmAgentLoop:2897-2904 +
        //   ModelCaller blocks 重载）：fork 与主线程同一 gate（params.useGlobalCacheScope，由
        //   CacheSafeParams 第 6 字段透传）→ splitSysPromptPrefix 剥离 boundary → 剥离产物<b>以
        //   blocks 数组原样发送</b>（LlmProvider blocks 重载 · system text block 数组，每 block 携带
        //   cacheScope）——不再 join 单 String（旧路径 :174-179/:206-207 已删）。boundary 永不达
        //   LLM（CC systemPrompt 以数组贯穿发送边界，api.ts:321-435 + claude.ts:1376-1382
        //   buildSystemPromptBlocks 消费）。
        // [IMP-SP2-07 G1] 第三参 = needsToolBasedCacheMarker 等价物（gate && forkCtx 发送工具集存在
        //   MCP 工具 · CC claude.ts:1212-1214 + claude.ts:1377；Java 无 tool-search → willDefer 恒
        //   false，等价论证见 hasMcpTool）。与 buildToolsArray 同源（:168 forkCtx.availableTools()）。
        List<SystemPromptBlock> systemPromptBlocks = SystemPromptSplitter.splitSysPromptPrefix(
            params.systemPrompt(), params.useGlobalCacheScope(),
            params.useGlobalCacheScope() && hasMcpTool(forkCtx));
        if (log.isDebugEnabled()) {
            log.debug("[ProductionForkedQuery] 发送边界 systemPrompt blocks 组装完成: sourceBlocks={} "
                    + "gate={} sendBlocks={}（boundary 已剥离 · blocks 数组发送）· 对齐 LlmAgentLoop:2897-2904",
                params.systemPrompt().size(), params.useGlobalCacheScope(), systemPromptBlocks.size());
        }

        int turns = 0;
        boolean maxTurnsReached = false;
        while (true) {
            turns++;
            if (params.maxTurns() != null && turns > params.maxTurns()) {
                // CC query.ts:1705-1710 max_turns_reached
                maxTurnsReached = true;
                log.info("[ProductionForkedQuery] fork 达到 maxTurns={}（querySource={}）终止 · CC query.ts:1705-1710",
                    params.maxTurns(), params.querySource());
                break;
            }
            if (abort.isCancelled()) {
                log.info("[ProductionForkedQuery] abortController 已取消，终止 fork loop（querySource={}）",
                    params.querySource());
                break;
            }

            // ── 逐轮 provider 调用（阻塞桥接 · blocks 数组发送）──
            AssistantMessage msg;
            try {
                msg = streamOnce(provider, config, model, systemPromptBlocks,
                    runningMessages, tools, params.maxOutputTokensOverride(), abort,
                    params.querySource() != null ? params.querySource().canonical() : null);
            } catch (Exception e) {
                log.warn("[ProductionForkedQuery] 第 {} 轮 provider 调用异常（best-effort 终止 fork loop）: {}",
                    turns, e.getMessage());
                break;
            }
            if (msg == null) {
                log.warn("[ProductionForkedQuery] 第 {} 轮无流式响应，终止 fork loop（querySource={}）",
                    turns, params.querySource());
                break;
            }

            // ── usage 逐轮累计（S-11 · 对齐 CC forkedAgent.ts:557-566）──
            //   CC 从 message_delta stream 事件累加真实 usage（updateUsage({...EMPTY_USAGE},
            //   event.usage) + accumulateUsage，null 缓存字段 → 0）；Java 每轮 provider 调用
            //   = 一次 API call，AssistantMessage.usage（AgentUsage）即该轮最终 usage
            //   （AnthropicSdkProvider 从 message_start/message_delta 解析 input/output/
            //   cache_creation/cache_read，AssistantMessage.java:29-37）→ 四字段全量累计，
            //   totalUsage 对齐 CC NonNullableUsage（[IMP-MV2-10] 修复 input/cache 恒 0）。
            if (msg.usage() != null) {
                AgentUsage turnUsage = msg.usage();
                totalUsage = totalUsage.accumulate(new ForkedAgentResult.ForkUsage(
                    turnUsage.inputTokens(),
                    turnUsage.outputTokens(),
                    turnUsage.cacheReadInputTokens() != null ? turnUsage.cacheReadInputTokens() : 0L,
                    turnUsage.cacheCreationInputTokens() != null ? turnUsage.cacheCreationInputTokens() : 0L));
                if (log.isDebugEnabled()) {
                    log.debug("[ProductionForkedQuery] 第 {} 轮 usage 累计: input={} output={} "
                            + "cacheRead={} cacheCreate={} totalInput={} totalOutput={} totalCacheRead={} "
                            + "· 对齐 CC forkedAgent.ts:557-566（message_delta usage 累加）",
                        turns, turnUsage.inputTokens(), turnUsage.outputTokens(),
                        turnUsage.cacheReadInputTokens(), turnUsage.cacheCreationInputTokens(),
                        totalUsage.inputTokens(), totalUsage.outputTokens(),
                        totalUsage.cacheReadInputTokens());
                }
            }
            ChatMessageDto assistantMsg = toAssistantMessage(msg);
            outputMessages.add(assistantMsg);
            runningMessages.add(assistantMsg);
            // G-79 流式回调（forkedAgent.ts:578 onMessage?.(message)）：消息产出即回调，
            //   非完成后回放——dream/extract 进度 UI 在 fork 进行中即更新
            params.onMessage().accept(assistantMsg);

            if (!msg.hasToolCalls()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ProductionForkedQuery] 第 {} 轮无工具调用，fork 完成（querySource={}）",
                        turns, params.querySource());
                }
                break;
            }

            // ── 工具调用门控执行（INV-6 受限 canUseTool）──
            for (ToolUseBlock call : msg.toolCalls()) {
                ToolResult<?> result = executeGatedTool(call, params, forkCtx);
                // [G2] 复用 LlmAgentLoop.toolResultMessage（DEL-G2-02：删除私有影子实现）
                ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(result);
                outputMessages.add(toolMsg);
                runningMessages.add(toolMsg);
                // G-79 流式回调：tool_result 消息同样产出即回调（CC query() 产出 user 消息亦回调）
                params.onMessage().accept(toolMsg);
                if (log.isDebugEnabled()) {
                    log.debug("[ProductionForkedQuery] 工具执行完成: name={} id={} isError={}",
                        call.name(), call.id(),
                        com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result.data()));
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("[ProductionForkedQuery] fork loop 完成: querySource={} turns={} maxTurns={} "
                + "maxTurnsReached={} messages={} usageInput={} usageOutput={} usageCacheRead={} "
                + "usageCacheCreate={} durationMs={}",
            params.querySource(), turns, params.maxTurns(), maxTurnsReached,
            outputMessages.size(), totalUsage.inputTokens(), totalUsage.outputTokens(),
            totalUsage.cacheReadInputTokens(), totalUsage.cacheCreationInputTokens(), durationMs);
        // [IMP2-19 S-11 + IMP-MV2-10] 四字段已逐轮全量累计（上方循环内，对齐 CC
        //   forkedAgent.ts:557-566 message_delta usage 累加；input/cache 由
        //   AssistantMessage.usage（AgentUsage）承载，DEC-04 R2-USAGE 数据源闭环）。
        //   全部轮次均无 usage 上报（totalUsage 全零）时 fail-loud 记日志，避免
        //   tengu_extract_memories_extraction 的 token 恒 0 被误读为正常。
        if (totalUsage.outputTokens() == 0) {
            log.warn("[ProductionForkedQuery] usage 缺口: 全程无 usage 上报，totalUsage 仍空 "
                    + "（CC forkedAgent.ts:557-566 从 message_delta 累计）——provider 未提取 "
                    + "usage 或真实响应无 usage；input/cache 随 usage 缺失同为 0（如实不伪造）");
        }
        // [A 命中率口径] 第 3 组件 providerType = provider.type()：下游 cacheHitRate 按协议分派
        //   （anthropic → read/(input+read+create)；openai_sdk/deepseek → read/input，input 已含 cache hit）。
        //   provider 已在 :182-184 判空（null 抛 IllegalStateException）→ 此处恒非 null。
        return new ForkedAgentResult(outputMessages, totalUsage, provider.type());
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具门控执行（INV-6 · 受限 canUseTool 消费点）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 门控 + 执行单个工具调用 · 对齐 CC query loop 内 canUseTool 检查
     * （toolHooks.ts:337 + forkedAgent.ts:545-556）。
     *
     * <p><b>为什么经 {@link HookPermissionResolver#resolve}</b>: fork 的受限工具集
     * （createAutoMemCanUseTool）必须真实生效 —— 直接 ToolRegistry.dispatch 会继承主线程
     * 权限（INV-6 破坏）。resolve(hookPermissionResult=null, hookUpdatedInput=null, ...) 走
     * "无决策"分支直接调 canUseTool（gate），Deny → ToolResult.error，Allow → dispatch。
     *
     * @param call   LLM 发起的工具调用
     * @param params fork 参数（carries canUseTool）
     * @param ctx    fork 隔离上下文（权限继承 + abortController）
     * @return 工具执行结果（Deny → error result；Allow → registry.dispatch）
     */
    private ToolResult<?> executeGatedTool(ToolUseBlock call,
                                           RunForkedAgent.ForkQueryParams params,
                                           ToolUseContext ctx) {
        Tool tool = resolveTool(call.name());
        if (tool == null) {
            log.warn("[ProductionForkedQuery] fork 调用未知工具 '{}'（id={}）", call.name(), call.id());
            return ToolResult.error(call.id(), "No such tool available: " + call.name());
        }
        if (params.canUseTool() == null) {
            log.warn("[ProductionForkedQuery] canUseTool 未注入（querySource={}），fork 工具不受限 —— INV-6 破坏，fail loud",
                params.querySource());
            throw new IllegalStateException("[ProductionForkedQuery] canUseTool 未注入，fork 工具权限不受限（INV-6）");
        }
        HookPermissionResolver.ResolvedPermission resolved = permissionResolver.resolve(
            null,            // hookPermissionResult · fork 无 PreToolUse hook → null（走"无决策"分支）
            null,            // hookUpdatedInput · 无 hook updatedInput
            tool,
            call.input(),
            ctx,
            call.id(),
            params.canUseTool());
        PermissionResult decision = resolved.decision();
        if (decision instanceof PermissionResult.Deny deny) {
            if (log.isDebugEnabled()) {
                log.debug("[ProductionForkedQuery] fork 工具被 canUseTool 拒绝: name={} id={} reason={}",
                    call.name(), call.id(), deny.message());
            }
            return ToolResult.error(call.id(),
                deny.message() != null ? deny.message() : "Permission denied for tool use.",
                "permission");
        }
        // Allow → ToolRegistry 执行（对齐 ToolRegistry.dispatch(call, ctx)）
        return toolRegistry.dispatch(call, ctx);
    }

    /** 从 ToolRegistry 解析工具实例（未注册 → null）。 */
    private Tool resolveTool(String name) {
        if (name == null || toolRegistry == null) {
            return null;
        }
        return toolRegistry.get(name).orElse(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // provider 阻塞桥接（复用 StreamCompactSummary.streamOnce 模式）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把 provider.stream（void + 回调异步）桥接为阻塞返回 AssistantMessage ·
     * 模式复刻 {@code StreamCompactSummary.streamOnce}（compact 包私有不可跨包调用）。
     *
     * <p><b>[RES-C6] blocks 数组发送</b>：本方法统一走 LlmProvider <b>blocks 重载</b>
     * （OPD-SP-28 契约 · 18-arg，AnthropicSdkProvider 覆写为 system text block 数组 + cache_control），
     * 不再走 String 重载。非 Anthropic provider 由 blocks 默认实现以 {@code \n\n} join 委托 String
     * 路径（CC 数组 join 语义，split 亦如此）——fork 与主线程同一序列化层级（缓存 key 对齐）。
     *
     * @param systemPromptBlocks 发送边界 blocks（splitSysPromptPrefix 剥离产物 · boundary 已剥离）·
     *                           CC original: buildSystemPromptBlocks 输入（utils/api.ts:321-435）
     * @param maxOutputTokensOverride fork 路径传 null（不设，防 cache key 破坏 · INV-7）
     * @param querySource         来源标识（blocks 重载发送边界遥测 · 对齐 ModelCaller request.querySource）
     * @return 完整 assistant message（流结束但无 assistant → null）
     */
    private AssistantMessage streamOnce(LlmProvider provider,
                                        ProviderConfig config,
                                        String model,
                                        List<SystemPromptBlock> systemPromptBlocks,
                                        List<ChatMessageDto> history,
                                        ArrayNode tools,
                                        Integer maxOutputTokensOverride,
                                        AbortController abortController,
                                        String querySource) {
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();
        final AtomicInteger chunkCount = new AtomicInteger(0);

        java.util.function.Consumer<AssistantMessage> onAssistant = future::complete;
        java.util.function.Consumer<Throwable> onError = future::completeExceptionally;
        Runnable onComplete = () -> {
            if (!future.isDone()) {
                future.complete(null); // 流结束但无 assistant message → 无响应
            }
        };

        try {
            // [RES-C6] blocks 重载统一发送：AnthropicSdkProvider 覆写（system 数组）或默认 join 委托。
            //   taskBudget/effortValue 恒 null（fork 无 task_budget/effort 语义 · 对齐 ModelCaller
            //   blocks 分支非 Anthropic 场景）；maxOutputTokensOverride 透传（INV-7 恒 null）。
            provider.stream(
                config, model, systemPromptBlocks, history, tools, maxOutputTokensOverride,
                null, /* taskBudget */
                null, /* effortValue */
                querySource,
                chunk -> { chunkCount.incrementAndGet(); },
                onAssistant,
                toolCall -> { /* 每轮统一处理 toolCalls（onAssistant 携带完整列表） */ },
                reasoning -> { /* fork 关闭 thinking 注入 */ },
                () -> { /* onStreamingFallback */ },
                abortController,
                onError,
                onComplete);
            // [IMP-GAP04 △-15] §7-10 默认裁决对齐 CC（CC 无 300s 硬超时，forkedAgent.ts query()
            //   靠 abortController + SDK 状态，流一直持续则等待）→ future.get() 无超时等待；
            //   取消路径保留：abortController → provider abort → CancellationException →
            //   下方 CompletionException 分支（对齐 StreamCompactSummary.java:707-710 IMP2-15 模式）。
            return future.get();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.CancellationException) {
                log.info("[ProductionForkedQuery] 流式调用被取消 (abort)");
                throw new IllegalStateException("fork 流式调用被取消");
            }
            log.warn("[ProductionForkedQuery] 流式调用异常: {}", cause.toString());
            throw new IllegalStateException("fork 流式调用异常: " + cause.getMessage());
        } catch (Exception e) {
            log.warn("[ProductionForkedQuery] 流式调用异常: {}", e.toString());
            throw new IllegalStateException("fork 流式调用异常: " + e.getMessage());
        }
    }

    /**
     * [IMP-SP2-07 G1] fork 发送工具集 MCP 判定 · CC claude.ts:1212-1214
     * {@code filteredTools.some(t => t.isMcp === true && !willDefer(t))} 的 Java 等价物：
     * Java 无 tool-search（useToolSearch/deferredToolNames/shouldDeferLspTool 0 命中）→
     * {@code willDefer} 恒 false → 等价于 availableTools 存在 MCP 工具（McpServerScope.isMcpTool
     * 等价 {@code t.isMcp===true}，name {@code mcp__} 前缀兜底）。与 {@link #buildToolsArray}
     * 同源（forkCtx.availableTools，:162 作用域）——ToolSearchTool 非 MCP，不影响判定。
     *
     * @param ctx fork 上下文（null / 无 availableTools → false）
     * @return true 时 splitSysPromptPrefix 走模式 1（skipGlobalCache）
     */
    private static boolean hasMcpTool(ToolUseContext ctx) {
        if (ctx == null || ctx.availableTools() == null) {
            return false;
        }
        return ctx.availableTools().stream()
            .anyMatch(t -> com.nexusai.application.agent.mcp.McpServerScope.isMcpTool(t.name(), t));
    }

    // ════════════════════════════════════════════════════════════════════
    // 消息构造 / 工具数组
    // ════════════════════════════════════════════════════════════════════

    /** 从 fork 上下文 availableTools 构建 OpenAI tools 数组（cache-safe 工具集）。 */
    private ArrayNode buildToolsArray(ToolUseContext ctx) {
        if (ctx == null || ctx.availableTools() == null || ctx.availableTools().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ProductionForkedQuery] fork 上下文无 availableTools，向 provider 传空工具集");
            }
            return JSON.createArrayNode();
        }
        return ToolRegistry.from(ctx.availableTools()).toOpenAiToolsArray();
    }

    /** infra AssistantMessage → ChatMessageDto（assistant + toolCalls）。 */
    private static ChatMessageDto toAssistantMessage(AssistantMessage msg) {
        List<ToolCallDto> toolCalls = new ArrayList<>();
        if (msg.toolCalls() != null) {
            for (ToolUseBlock c : msg.toolCalls()) {
                toolCalls.add(new ToolCallDto(
                    c.id(), c.name(), jsonNodeToString(c.input()), null, null));
            }
        }
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            msg.content() == null ? "" : msg.content(),
            msg.reasoning(), toolCalls, FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false);
    }

    /** ToolUseBlock.input → JSON 字符串（对齐 LlmAgentLoop.toolCallDto）。 */
    private static String jsonNodeToString(JsonNode node) {
        if (node == null) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
