package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM Provider 抽象接口
 *
 * <p>所有 LLM 后端（OpenAI / Anthropic / Mock）实现此接口，由 {@link LlmProviderFactory} 按
 * {@link #type()} 分发。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link #stream} 用回调而非 {@code Stream<String>} 返回值 —— 方便异步场景
 *       （LLM provider 内部用 {@link java.net.http.HttpClient} 流式读取）</li>
 *   <li>每个回调可能在虚拟线程/异步线程上触发，实现方必须保证线程安全</li>
 *   <li>{@link Runnable#run} onComplete 只在 stream 正常结束时调用；中断（cancel）走
 *       {@code onError(new CancellationException())}（当前简化：cancel 走静默退出）</li>
 *   <li>Phase 6·s02：加 {@code tools} 参数 + {@code onAssistantMessage} 回调。
 *       老 7-arg {@code stream} 变 default 方法，委托给 9-arg 传 null。</li>
 * </ul>
 *
 * @see MockLlmProvider
 * @see OpenAiSdkProvider
 */
public interface LlmProvider {

    /**
     * Provider 类型标识，用于工厂分发。
     * @return "openai_compatible" | "anthropic" | ...
     */
    String type();

    /**
     * [IMP-SP-06] blocks 流式（17 参）· <b>唯一发送契约（system 为 {@link SystemPromptBlock} 数组）</b>。
     *
     * <p>对齐 CC {@code buildSystemPromptBlocks(systemPrompt, enablePromptCaching, ...)}
     * （claude.ts:3213-3237）→ API {@code system} 为 text block 数组（每 block 可携带
     * cache_control）。调用方先经 {@code SystemPromptSplitter.splitSysPromptPrefix} 拆分
     * 再传本重载。<b>发送契约数组态唯一</b>（⊕C-1 已删除 String systemPrompt 委托链，
     * OPD-SP-28 退出）：String 兼容路径不存在，各 provider 自行将 blocks 映射为
     * 后端请求体（Anthropic=text block 数组；OpenAI 兼容=按 CC 数组 join 语义 {@code \n\n}
     * 连接为单 system 字符串）。
     *
     * <p><b>唯一抽象发送契约</b>: 3 个生产 implementor（AnthropicSdkProvider /
     * OpenAiSdkProvider / MockLlmProvider）与测试 fake 均实现本重载；{@code null} / 空
     * blocks = 不发送 system（无 system 字段）。
     *
     * @param systemPromptBlocks 拆分后的 system prompt blocks（可 null/空 = 不发送 system）·
     *                           CC original: buildSystemPromptBlocks 输入（claude.ts:1376-1382）
     * @param maxOutputTokensOverride 本次 call 的 max_tokens 覆盖（可 null = 按模型解析）
     * @param taskBudget              API task_budget 线参数（可 null = 不注入）
     * @param effortValue             会话级 effort 值（可 null = 不注入）
     * @param querySource             CC original: options.querySource（claude.ts:1378-1381）—
     *                                发送边界遥测/缓存 TTL 判定；Java getCacheControl ttl 由
     *                                PromptCachingTtlConfig 配置（默认 '1h'，RES-R7），与 querySource 无关
     * @param abortController         取消信号（可 null = 无中断能力）
     */
    void stream(ProviderConfig config,
                String modelName,
                List<SystemPromptBlock> systemPromptBlocks,
                List<ChatMessageDto> history,
                ArrayNode tools,
                Integer maxOutputTokensOverride,
                TaskBudgetParam taskBudget,
                String effortValue,
                String querySource,
                Consumer<String> onChunk,
                Consumer<AssistantMessage> onAssistantMessage,
                Consumer<ToolUseBlock> onToolCallComplete,
                Consumer<String> onReasoningChunk,
                Runnable onStreamingFallback,
                AbortController abortController,
                Consumer<Throwable> onError,
                Runnable onComplete);

    /**
     * [CCJ-EXEC-08] 18-arg 流式 · <b>带 thinkingConfig 透传</b>（含 effortValue）。
     *
     * <p>对齐 CC {@code queryModelWithStreaming({thinkingConfig})}（execAgentHook.ts:134
     * {@code thinkingConfig: {type:'disabled'}} 注入 agentToolUseContext.options →
     * query.ts:662 options.thinkingConfig）——hook agent 请求必须显式关闭思考。
     *
     * <p><b>默认实现忽略 thinkingConfig，String systemPrompt 折为单 block 路由到 blocks 抽象重载</b>
     * （⊕C-1 blocks 唯一发送契约；单 block join 恒等，语义不变。未实现 thinking 语义的 provider /
     * mock 不破坏）；{@link OpenAiSdkProvider} 覆写为把
     * {@code thinking:{type:'disabled'}} 写入请求体（对齐 chatWithOptions :490-495 先例）。
     * {@link AnthropicSdkProvider} 不覆写——其 API 省略 thinking 参数即 disabled（CC 等价）。
     *
     * @param effortValue    会话级 effort 值（可 null = 不注入；透传保持既有语义）
     * @param thinkingConfig 思考配置（可 null = 不发送；默认实现忽略）
     */
    default void stream(ProviderConfig config,
                        String modelName,
                        String systemPrompt,
                        List<ChatMessageDto> history,
                        ArrayNode tools,
                        Integer maxOutputTokensOverride,
                        TaskBudgetParam taskBudget,
                        String effortValue,
                        ChatRequestOptions.ThinkingConfig thinkingConfig,
                        Consumer<String> onChunk,
                        Consumer<AssistantMessage> onAssistantMessage,
                        Consumer<ToolUseBlock> onToolCallComplete,
                        Consumer<String> onReasoningChunk,
                        Runnable onStreamingFallback,
                        AbortController abortController,
                        Consumer<Throwable> onError,
                        Runnable onComplete) {
        // [merge-fix] ⊕C-1 blocks 唯一发送契约：String 兼容链已删（16-arg String 委托目标不在
        //   合并接口），默认实现把 systemPrompt 折为单 block（CacheScope.NULL = 不缓存，join 恒等）
        //   路由到 blocks 抽象重载；thinkingConfig 忽略（与原有默认语义一致）。
        stream(config, modelName,
            systemPrompt == null ? null : List.of(new SystemPromptBlock(systemPrompt, CacheScope.NULL)),
            history, tools, maxOutputTokensOverride, taskBudget, effortValue, null, /* querySource */
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, abortController, onError, onComplete);
    }

    /**
     * [CCJ-EXEC-08] 19-arg 流式 · blocks 重载 + <b>thinkingConfig 透传</b>。
     *
     * <p>默认实现<b>忽略 thinkingConfig 委托既有 18-arg blocks 重载</b>——保留
     * {@link AnthropicSdkProvider} 的 cache_control block 语义（其 API 省略 thinking
     * 参数即 disabled，CC 等价）；{@link OpenAiSdkProvider} 覆写本重载为 blocks 连接后
     * 走带 thinkingConfig 的 String 重载（openai-compatible 端点需显式
     * {@code thinking:{type:'disabled'}}，对齐 chatWithOptions :490-495 先例）。
     *
     * @param thinkingConfig 思考配置（可 null = 不发送；默认实现忽略）
     */
    default void stream(ProviderConfig config,
                        String modelName,
                        List<SystemPromptBlock> systemPromptBlocks,
                        List<ChatMessageDto> history,
                        ArrayNode tools,
                        Integer maxOutputTokensOverride,
                        TaskBudgetParam taskBudget,
                        String effortValue,
                        String querySource,
                        ChatRequestOptions.ThinkingConfig thinkingConfig,
                        Consumer<String> onChunk,
                        Consumer<AssistantMessage> onAssistantMessage,
                        Consumer<ToolUseBlock> onToolCallComplete,
                        Consumer<String> onReasoningChunk,
                        Runnable onStreamingFallback,
                        AbortController abortController,
                        Consumer<Throwable> onError,
                        Runnable onComplete) {
        stream(config, modelName, systemPromptBlocks, history, tools,
            maxOutputTokensOverride, taskBudget, effortValue, querySource,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, abortController, onError, onComplete);
    }

    /**
     * 静态（非流式）chat completion · 用于快速模型（标题生成等）。
     *
     * @param config       解密后的运行时配置（baseUrl + apiKey）
     * @param modelName    模型名
     * @param systemPrompt 系统提示词（可能为 null）
     * @param userMessage  单条 user 消息（轻量场景，不走 history）
     * @return 模型完整输出
     */
    String chat(ProviderConfig config,
                String modelName,
                String systemPrompt,
                String userMessage);

    /**
     * [M3.2] 静态（非流式）chat completion · 返回 raw LLM response（content + id + thinking）。
     *
     * <p>对齐 CC Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:795-924
     * 的 {@code sideQuery(stage1Opts/stage2Opts)} 返回值 — CC 端取
     * <ul>
     *   <li>{@code stage1Raw.id} 填 {@code stage1MsgId} (yoloClassifier.ts:799, 821, 838, 856)</li>
     *   <li>{@code stage2Raw.id} 填 {@code stage2MsgId} (yoloClassifier.ts:885, 911, 915, 939)</li>
     *   <li>{@code parseXmlThinking(stage2Text)} 填 {@code thinking} (yoloClassifier.ts:924)</li>
     * </ul>
     *
     * <p>Java 端扩展 {@link LlmProvider} 接口, 暴露完整 raw response 元数据. 默认实现
     * 委托给 {@link #chat}, id = null, thinking = null. 真实 provider (Anthropic / OpenAI)
     * 应覆写此方法, 从 response metadata 提取 message id + thinking blocks.
     *
     * <p>WHY 独立接口而非扩展 {@link #chat}: 旧 {@code chat} 仍被
     * {@link com.nexusai.application.chat.ChatService} titleGeneration 复用, 不能破坏.
     * 新接口只对 {@code YoloClassifierImpl} 调用方可见, 行为对齐 CC sideQuery.
     *
     * @param config       解密后的运行时配置（baseUrl + apiKey）
     * @param modelName    模型名
     * @param systemPrompt 系统提示词（可能为 null）
     * @param userMessage  单条 user 消息
     * @return LlmRawResponse(content, id, thinking) — 一律非 null，缺失字段填 null
     */
    default LlmRawResponse chatWithRaw(ProviderConfig config,
                                       String modelName,
                                       String systemPrompt,
                                       String userMessage) {
        // 默认委托给 chat — 保持向后兼容 (MockLlmProvider / 老 Provider)
        String content = chat(config, modelName, systemPrompt, userMessage);
        // [D P1-7] 默认实现无 request id 通道 → null (对齐 CC ?? undefined 兜底)
        return new LlmRawResponse(content, null, null, null);
    }

    /**
     * [IMP-6 OPD-WF6-02-RV] LLM API token usage · 对齐 CC {@code extractUsage}
     * （yoloClassifier.ts:609-618）从 API 响应 usage 提取的 4 个 token 字段：
     * inputTokens / outputTokens / cacheReadInputTokens / cacheCreationInputTokens。
     *
     * <p>CC 真源（snake_case → Java camelCase）:
     * <ul>
     *   <li>{@code inputTokens} ← result.usage.input_tokens（:613）</li>
     *   <li>{@code outputTokens} ← result.usage.output_tokens（:614）</li>
     *   <li>{@code cacheReadInputTokens} ← result.usage.cache_read_input_tokens ?? 0（:615）</li>
     *   <li>{@code cacheCreationInputTokens} ← result.usage.cache_creation_input_tokens ?? 0（:616）</li>
     * </ul>
     *
     * @param inputTokens             uncached input tokens（CC input_tokens）
     * @param outputTokens            output tokens（CC output_tokens）
     * @param cacheReadInputTokens    缓存读取 tokens（CC cache_read_input_tokens ?? 0）
     * @param cacheCreationInputTokens 缓存创建 tokens（CC cache_creation_input_tokens ?? 0）
     */
    record LlmUsage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {}

    /**
     * [M3.2 + D P1-7 + IMP-6] LLM raw response · 对齐 CC sideQuery 返回值.
     *
     * <p>CC 端 sideQuery 返回对象含 {@code id} + {@code content} (Array of ContentBlock) +
     * 非枚举 {@code _request_id} 属性 + {@code usage}. Java 端扁化为
     * (content: String, id: String, thinking: String, requestId: String, usage: LlmUsage)
     * 五字段 record.
     *
     * <ul>
     *   <li>{@link #content}  ← CC sideQuery.content 拼接字符串</li>
     *   <li>{@link #id}      ← CC sideQuery.id (Anthropic message_start.id / OpenAI root.id)</li>
     *   <li>{@link #thinking}← CC parseXmlThinking(stage2Text) 抽取的推理块</li>
     *   <li>{@link #requestId}← CC extractRequestId(stage1Raw/stage2Raw) 的 SDK
     *       {@code _request_id} (yoloClassifier.ts:624-628) — API request_id (req_xxx),
     *       Java 端从 HTTP 响应头提取: Anthropic {@code request-id} header /
     *       OpenAI 兼容网关 {@code x-request-id} (兜底 {@code request_id})</li>
     *   <li>{@link #usage}    ← CC result.usage（:609-618 extractUsage）— 分类器 token
     *       用量遥测通道；provider 未暴露 usage 时 null（CC 恒有值，Java 如实暴露缺口）</li>
     * </ul>
     *
     * <p>所有字段均可为 null (provider 未实现 / 推理未启用 / 网关无 request id header).
     */
    record LlmRawResponse(String content, String id, String thinking, String requestId, LlmUsage usage) {
        public LlmRawResponse {
            // null-safe compact constructor — 仅归一化 content, 允许 id/thinking/requestId/usage null
            content = content == null ? "" : content;
        }

        /**
         * 4-arg 便捷构造器（usage=null）· provider 未暴露 usage 通道时使用
         * （OpenAI 非流式 / Mock / {@link #chatWithRaw} 默认委托）。
         *
         * @param content    响应文本
         * @param id         Anthropic message_start.id / OpenAI root.id
         * @param thinking   推理内容（可为 null）
         * @param requestId  API request_id（可为 null）
         */
        public LlmRawResponse(String content, String id, String thinking, String requestId) {
            this(content, id, thinking, requestId, null);
        }
    }

    /**
     * [对抗核验 H13-GAP-3 v3] 非流式 chat 请求选项 · 对齐 CC {@code queryModelWithoutStreaming}
     * (Open-ClaudeCode/src/services/api/claude.ts) 入参的 tools / outputFormat / thinkingConfig /
     * temperatureOverride / querySource / signal。
     *
     * <p>WHY (J.md H13-GAP-3 登记 + P2-16): ExecPromptHook 的 CC 真源 execPromptHook.ts:71-99 传
     * {@code tools(toolUseContext.options.tools)} + {@code outputFormat:{type:'json_schema',
     * schema:{ok,reason}}} + {@code thinkingConfig:{type:'disabled'}} + messages 历史；SkillImprovement
     * 侧信道 (skillImprovement.ts:236-249) 另传 {@code thinkingConfig:{type:'disabled'}} +
     * {@code temperatureOverride: 0} + {@code querySource} + {@code signal}。Java 旧
     * {@link #chat} 仅单条 userMessage, 无法表达这些选项。本 record + {@link #chatWithOptions}
     * 扩展接口, 让 prompt hook 对齐 CC 行为。
     *
     * @param history         prepend 消息历史（CC messages 参数, 可空/空 = 仅 userMessage）
     * @param tools           OpenAI function-calling 格式 tool 定义（可空/空）
     * @param outputFormat    结构化输出约束（可空；CC execPromptHook.ts:87-98 json_schema）
     * @param thinkingConfig  思考配置（可空；CC execPromptHook.ts:71 disabled / skillImprovement.ts:236）
     * @param temperature     CC original: options.temperatureOverride（skillImprovement.ts:245
     *                        {@code temperatureOverride: 0}; claude.ts:1693-1694 thinking disabled 时
     *                        写入请求体 temperature=temperatureOverride ?? 1）— 可空 = 不发送
     * @param querySource     CC original: options.querySource（skillImprovement.ts:247
     *                        {@code 'skill_improvement_apply'} / apiQueryHookHelper.ts:104
     *                        {@code config.name}）— 侧信道来源标记, 仅日志/遥测区分
     * @param abortController CC original: signal（skillImprovement.ts:238
     *                        {@code createAbortController().signal}; claude.ts:744-745 abort 消费）
     *                        — provider 发送前预检 isCancelled(), 可空 = 无中断能力
     */
    record ChatRequestOptions(
            List<ChatMessageDto> history,
            ArrayNode tools,
            OutputFormat outputFormat,
            ThinkingConfig thinkingConfig,
            Double temperature,
            String querySource,
            AbortController abortController,
            // IMP-M-P1-2: max_tokens 侧信道选项 · CC original: max_tokens（sideQuery.ts:49, max_tokens = 1024 缺省；
            // findRelevantMemories.ts:108 max_tokens: 256）。可空 = 不显式发送（provider 回落模型缺省）。
            Integer maxTokens,
            // IMP-M-P2-3: skipCacheWrite · CC original: options.skipCacheWrite（awaySummary.ts:56
            //   queryModelWithoutStreaming options 内 skipCacheWrite: true）— 主线程记忆 side-query 除外
            //   的侧信道查询禁止写 API cache（cc sessionMemory.ts:318-325 未设 = false；awaySummary.ts:56 true）。
            //   [ODF-B3] 已物理接入 AnthropicSdkProvider.buildMessageParams：true → messages 通道 marker
            //   移位到倒数第二条（共享前缀最后点，mycro no-op merge），false/未设 → 最后一条
            //   （CC addCacheBreakpoints claude.ts:3089）；OpenAI 侧恒 0 cache_control。
            Boolean skipCacheWrite,
            // [W9-01 OPD-TS-29] queryHaiku options 承载 · CC original: options.enablePromptCaching
            //   （toolUseSummaryGenerator.ts:75 enablePromptCaching: true；claude.ts:1374-1375
            //   `enablePromptCaching = options.enablePromptCaching ?? getPromptCachingEnabled(model)`）
            //   — 非空 = 覆盖模型级 caching gate（对齐 CC），null = 模型级默认。
            Boolean enablePromptCaching,
            // [W9-01 OPD-TS-29] CC original: options.agents（toolUseSummaryGenerator.ts:76 agents: []）
            //   — 供 query 管线 system prompt 装配；Java chatWithOptions 直呼路径不消费（携带以对齐）。
            List<String> agents,
            // [W9-01 OPD-TS-29] CC original: options.hasAppendSystemPrompt（toolUseSummaryGenerator.ts:78
            //   hasAppendSystemPrompt: false）— Java 直呼路径不消费（携带以对齐）。
            Boolean hasAppendSystemPrompt,
            // [W9-01 OPD-TS-29] CC original: options.mcpTools（toolUseSummaryGenerator.ts:79 mcpTools: []）
            //   — Java 直呼路径不消费（携带以对齐）。
            List<String> mcpTools,
            // [W9-01 OPD-TS-29] CC original: options.isNonInteractiveSession（toolUseSummaryGenerator.ts:77
            //   透传）— Java 直呼路径不消费（携带以对齐）。
            Boolean isNonInteractiveSession,
            // [WF3-04 explainer] CC original: options.tool_choice（permissionExplainer.ts:183
            //   {type:'tool', name:'explain_command'}）— 强制结构化输出（强制 LLM 调用指定工具）。
            //   可空 = 不发送 tool_choice（模型自由选择）。
            ToolChoice toolChoice
    ) {
        public ChatRequestOptions {
            history = history == null ? List.of() : List.copyOf(history);
        }

        /**
         * [W9-01] 9-arg 便捷构造器 · 既有 9 参调用方（AwaySummaryService）零改动；末 5 项
         * （enablePromptCaching/agents/hasAppendSystemPrompt/mcpTools/isNonInteractiveSession）
         * 默认 null（未显式设置，等价 CC undefined）。
         */
        public ChatRequestOptions(
                List<ChatMessageDto> history,
                ArrayNode tools,
                OutputFormat outputFormat,
                ThinkingConfig thinkingConfig,
                Double temperature,
                String querySource,
                AbortController abortController,
                Integer maxTokens,
                Boolean skipCacheWrite) {
            this(history, tools, outputFormat, thinkingConfig, temperature, querySource, abortController,
                maxTokens, skipCacheWrite, null, null, null, null, null, null);
        }

        /**
         * 8-arg 便捷构造器 · 现有调用方（FindRelevantMemories/ExecPromptHook/SkillImprovementHook）
         * 零改动（skipCacheWrite 默认 null = 未显式设置，等价 CC undefined）。
         */
        public ChatRequestOptions(
                List<ChatMessageDto> history,
                ArrayNode tools,
                OutputFormat outputFormat,
                ThinkingConfig thinkingConfig,
                Double temperature,
                String querySource,
                AbortController abortController,
                Integer maxTokens) {
            this(history, tools, outputFormat, thinkingConfig, temperature, querySource, abortController, maxTokens, null);
        }

        /**
         * [WF3-04 explainer] 14-arg 便捷构造器 · 保留新增 {@code toolChoice} 前的 canonical
         * 14 参形状（HaikuToolUseSummaryGenerator 等 canonical 调用方零改动），
         * toolChoice 默认 null（未显式设置 = 不发送，等价 CC undefined）。
         */
        public ChatRequestOptions(
                List<ChatMessageDto> history,
                ArrayNode tools,
                OutputFormat outputFormat,
                ThinkingConfig thinkingConfig,
                Double temperature,
                String querySource,
                AbortController abortController,
                Integer maxTokens,
                Boolean skipCacheWrite,
                Boolean enablePromptCaching,
                List<String> agents,
                Boolean hasAppendSystemPrompt,
                List<String> mcpTools,
                Boolean isNonInteractiveSession) {
            this(history, tools, outputFormat, thinkingConfig, temperature, querySource, abortController,
                maxTokens, skipCacheWrite, enablePromptCaching, agents, hasAppendSystemPrompt, mcpTools,
                isNonInteractiveSession, null);
        }

        /** 结构化输出约束 · 对齐 CC API outputFormat. */
        public record OutputFormat(String type, com.fasterxml.jackson.databind.JsonNode schema) {
            public static OutputFormat jsonSchema(com.fasterxml.jackson.databind.JsonNode schema) {
                return new OutputFormat("json_schema", schema);
            }
        }

        /**
         * 思考配置 · 对齐 CC thinkingConfig (Open-ClaudeCode/src/utils/thinking.ts:10-13)。
         *
         * <p>CC 为 union 类型：{@code {type:'adaptive'} | {type:'enabled'; budgetTokens} |
         * {type:'disabled'}}。Java 以单 record 表达，{@code budgetTokens} 仅
         * {@code type=='enabled'} 时有值（其余 null），与 CC enabled 分支消费
         * (withRetry.ts:407-410) 一致。
         */
        public record ThinkingConfig(String type, Integer budgetTokens) {
            /** CC original: {@code {type:'disabled'}} (utils/thinking.ts:13) — 关闭思考，minRequired=0+1. */
            public static ThinkingConfig disabled() {
                return new ThinkingConfig("disabled", null);
            }

            /** CC original: {@code {type:'adaptive'}} (utils/thinking.ts:11) — 自适应思考，无 budgetTokens，minRequired=0+1. */
            public static ThinkingConfig adaptive() {
                return new ThinkingConfig("adaptive", null);
            }

            /** CC original: {@code {type:'enabled'; budgetTokens}} (utils/thinking.ts:12) — 启用思考 + 思考预算，minRequired=budgetTokens+1. */
            public static ThinkingConfig enabled(int budgetTokens) {
                return new ThinkingConfig("enabled", budgetTokens);
            }
        }

        /**
         * 强制工具选择 · 对齐 CC {@code options.tool_choice}（permissionExplainer.ts:183
         * {@code {type:'tool', name:'explain_command'}}）— 强制 LLM 调用指定工具以获取结构化输出。
         *
         * <p>CC 的 tool_choice 是 Anthropic 原生格式 {@code {type:'tool', name}}；
         * Java 端以单 record 承载，各 provider 按自身协议投影（Anthropic → ToolChoiceTool；
         * OpenAI → named function choice）。
         *
         * @param type CC original: tool_choice.type（'tool'）
         * @param name CC original: tool_choice.name（工具名，如 'explain_command'）
         */
        public record ToolChoice(String type, String name) {
            /** 指定工具名强制调用 · CC original: {@code {type:'tool', name}}。 */
            public static ToolChoice tool(String name) {
                return new ToolChoice("tool", name);
            }
        }
    }

    /**
     * [对抗核验 H13-GAP-3 v3] 带选项的非流式 chat · 对齐 CC {@code queryModelWithoutStreaming}
     * 的 options (tools / outputFormat / thinkingConfig / messages 历史)。
     *
     * <p>默认实现忽略 options 委托给 {@link #chat} —— 未实现选项的 provider (老实现) 不破坏；
     * OpenAiSdkProvider / AnthropicSdkProvider / MockLlmProvider 覆写以表达选项。
     *
     * @param config       解密后的运行时配置（baseUrl + apiKey）
     * @param modelName    模型名
     * @param systemPrompt 系统提示词（可能为 null）
     * @param userMessage  本次 user 消息（history 非空时作为最后一条追加）
     * @param options      chat 选项（可空 = 退化为 {@link #chat}）
     * @return 模型完整输出
     */
    default String chatWithOptions(ProviderConfig config,
                                   String modelName,
                                   String systemPrompt,
                                   String userMessage,
                                   ChatRequestOptions options) {
        return chat(config, modelName, systemPrompt, userMessage);
    }

    /**
     * [WF3-04 explainer] 带选项非流式 chat · 返回完整 {@link AssistantMessage}（含 tool_use 块）。
     *
     * <p>对齐 CC {@code sideQuery} 返回 content blocks（permissionExplainer.ts:178-186：
     * sideQuery → response.content.find(c => c.type === 'tool_use')）。强制 tool_choice 场景下
     * LLM 以 tool_use 块作答，文本 content 为空——旧 {@link #chatWithOptions} 仅返回文本，
     * 无法取到 tool_use 输入，故扩展本方法返回完整消息（含 toolCalls）。
     *
     * <p>默认实现委托 {@link #chatWithOptions}（content 为纯文本、toolCalls 空）——
     * 未实现该语义的 provider 不破坏；真实 provider（OpenAi / Anthropic / Mock）覆写
     * 以返回 tool_use 块。
     *
     * @param config       解密后的运行时配置（baseUrl + apiKey）
     * @param modelName    模型名
     * @param systemPrompt 系统提示词（可能为 null）
     * @param userMessage  本次 user 消息
     * @param options      chat 选项（含 tools + tool_choice）
     * @return 完整 assistant message（含 toolCalls）
     */
    default AssistantMessage chatWithOptionsMessage(ProviderConfig config,
                                                    String modelName,
                                                    String systemPrompt,
                                                    String userMessage,
                                                    ChatRequestOptions options) {
        String content = chatWithOptions(config, modelName, systemPrompt, userMessage, options);
        return new AssistantMessage(content, "stop", List.of(), "");
    }
}
