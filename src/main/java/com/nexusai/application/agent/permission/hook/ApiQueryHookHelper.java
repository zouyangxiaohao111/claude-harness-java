package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * ApiQueryHook 泛型工厂 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/apiQueryHookHelper.ts:56-141}.
 *
 * <p>WHY (规则三): CC 把"post-sampling 后做一次侧信道 LLM 查询"抽象成通用工厂 —
 * shouldRun 门控 → buildMessages 构造消息 → queryModelWithoutStreaming 查询 →
 * parseResponse 解析 → logResult 上报成功/失败判别联合. skillImprovement 等 hook 通过
 * 复用本工厂, 避免每个 hook 重复实现"侧信道查询"样板.
 *
 * <p><b>字段来源对照</b>:
 * <ul>
 *   <li>{@link ApiQueryHookContext} — CC original: {@code ApiQueryHookContext = REPLHookContext & {queryMessageCount?}}
 *       (apiQueryHookHelper.ts:12-14)</li>
 *   <li>{@link ApiQueryHookConfig} — CC original: {@code ApiQueryHookConfig<TResult>}
 *       (apiQueryHookHelper.ts:16-38): name(QuerySource) / shouldRun / buildMessages /
 *       systemPrompt? / useTools? / parseResponse / logResult / getModel</li>
 *   <li>{@link ApiQueryResult} — CC original: {@code ApiQueryResult<TResult> = success | error}
 *       (apiQueryHookHelper.ts:40-54)</li>
 *   <li>{@link #createApiQueryHook} — CC original: {@code createApiQueryHook} (apiQueryHookHelper.ts:56-141)</li>
 * </ul>
 *
 * <p><b>Java idiom 适配</b>: CC {@code buildMessages} 返回 {@code Message[]}; Java 端
 * {@link LlmProvider#chatWithRaw} 收单条 user message → {@code buildMessages} 返回该 user
 * 内容字符串 (Java 单消息等价, queryMessageCount=1). CC {@code queryModelWithoutStreaming}
 * 全局函数 → Java 注入 {@link ModelQueryExecutor} (测试可打桩, 生产用 LlmProvider).
 *
 * @see SkillImprovementHook
 * @since Session H12
 */
public final class ApiQueryHookHelper {

    private static final Logger log = LoggerFactory.getLogger(ApiQueryHookHelper.class);

    private ApiQueryHookHelper() {}

    /**
     * 侧信道 LLM 查询执行器 · Java 等价 CC {@code queryModelWithoutStreaming}
     * (apiQueryHookHelper.ts:85-108).
     *
     * <p>WHY: CC 全局函数不可注入, Java 用函数式接口抽象 — 生产实现用
     * {@link LlmProvider#chatWithRaw}, 测试打桩返回固定文本. {@code useTools} 透传
     * config.useTools 门控 (CC L78-79).
     */
    @FunctionalInterface
    public interface ModelQueryExecutor {
        /**
         * @param systemPrompt 系统提示词 (config.systemPrompt 覆盖或 context.systemPrompt)
         * @param userMessage  用户消息内容 (buildMessages 产物)
         * @param model        模型名 (getModel 惰性加载)
         * @param useTools     useTools 门控 (false → 传空 tools, CC L78-79)
         * @param options      [CCJ-EXEC-03] helper 内建查询选项 · 对齐 CC apiQueryHookHelper.ts:85-108
         *                     queryModelWithoutStreaming 的 options 全集（thinkingConfig disabled /
         *                     temperatureOverride 0 / querySource=config.name / abortController /
         *                     mcpTools [] / isNonInteractiveSession / agents·hasAppendSystemPrompt
         *                     Java 无通道 → null 携带对齐）
         * @return LLM raw response (content + id)
         */
        LlmProvider.LlmRawResponse query(String systemPrompt, String userMessage, String model,
                                          boolean useTools, LlmProvider.ChatRequestOptions options);
    }

    /**
     * ApiQueryHook 上下文 · CC original: {@code ApiQueryHookContext = REPLHookContext & {queryMessageCount?}}
     * (apiQueryHookHelper.ts:12-14).
     *
     * <p>REPLHookContext 等价字段 (messages/systemPrompt/userContext/systemContext/
     * toolUseContext/querySource) + {@code queryMessageCount}.
     */
    public record ApiQueryHookContext(
            List<ChatMessageDto> messages,
            // [IMP-HOOKS-S7 D3 涟漪] CC ApiQueryHookContext.systemPrompt: SystemPrompt 段数组
            //   （apiQueryHookHelper.ts:12-14 = REPLHookContext & {queryMessageCount?}），
            //   与 PostSamplingContext.systemPrompt 同型。旧 String 单值已随 D3 收敛。
            List<String> systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            ToolUseContext toolUseContext,
            QuerySource querySource,
            Integer queryMessageCount
    ) {
        /** 从 REPLHookContext (PostSamplingContext) 构造, queryMessageCount 初始 null. */
        public static ApiQueryHookContext from(PostSamplingContext ctx) {
            return new ApiQueryHookContext(
                    ctx.messages(), ctx.systemPrompt(), ctx.userContext(), ctx.systemContext(),
                    ctx.toolUseContext(), ctx.querySource(), null);
        }

        /** 复制并设置 queryMessageCount (CC L70 {@code context.queryMessageCount = messages.length}). */
        public ApiQueryHookContext withQueryMessageCount(int n) {
            return new ApiQueryHookContext(messages, systemPrompt, userContext, systemContext,
                    toolUseContext, querySource, n);
        }
    }

    /**
     * ApiQueryHook 配置 · CC original: {@code ApiQueryHookConfig<TResult>}
     * (apiQueryHookHelper.ts:16-38).
     *
     * @param name           CC original: name (QuerySource, apiQueryHookHelper.ts:17)
     * @param shouldRun      CC original: shouldRun (apiQueryHookHelper.ts:18) — 门控
     * @param buildMessages  CC original: buildMessages (apiQueryHookHelper.ts:21) — 返回 user 内容
     * @param systemPrompt   CC original: systemPrompt (apiQueryHookHelper.ts:24) — 可选覆盖, null=用 context
     * @param useTools       CC original: useTools (apiQueryHookHelper.ts:27) — 可选, null/true=用 context tools, false=空
     * @param parseResponse  CC original: parseResponse (apiQueryHookHelper.ts:30)
     * @param logResult      CC original: logResult (apiQueryHookHelper.ts:31-34) — 收 success|error 判别联合
     * @param getModel       CC original: getModel (apiQueryHookHelper.ts:37) — 惰性加载模型名
     */
    public record ApiQueryHookConfig<TResult>(
            String name,
            Predicate<ApiQueryHookContext> shouldRun,
            Function<ApiQueryHookContext, String> buildMessages,
            String systemPrompt,
            Boolean useTools,
            BiFunction<String, ApiQueryHookContext, TResult> parseResponse,
            BiConsumer<ApiQueryResult<TResult>, ApiQueryHookContext> logResult,
            Function<ApiQueryHookContext, String> getModel
    ) {}

    /**
     * ApiQuery 结果判别联合 · CC original: {@code ApiQueryResult<TResult> = success | error}
     * (apiQueryHookHelper.ts:40-54).
     *
     * @param queryName CC original: queryName — config.name (QuerySource)
     */
    public sealed interface ApiQueryResult<T> permits ApiQuerySuccess, ApiQueryError {
        /** CC original: queryName (apiQueryHookHelper.ts:43/50) — 查询名. */
        String queryName();
        /** CC original: error (apiQueryHookHelper.ts:51) — 仅 error 分支有; success 为 null. */
        String error();
    }

    /**
     * 成功结果 · CC original: {@code { type:'success', queryName, result, messageId, model, uuid }}
     * (apiQueryHookHelper.ts:41-48).
     */
    public record ApiQuerySuccess<T>(
            String queryName,
            T result,
            String messageId,
            String model,
            String uuid
    ) implements ApiQueryResult<T> {
        @Override
        public String error() {
            return null;
        }
    }

    /**
     * 错误结果 · CC original: {@code { type:'error', queryName, error, uuid }}
     * (apiQueryHookHelper.ts:49-54).
     */
    public record ApiQueryError<T>(
            String queryName,
            String error,
            String uuid
    ) implements ApiQueryResult<T> {
    }

    /**
     * 泛型工厂 · CC original: {@code createApiQueryHook} (apiQueryHookHelper.ts:56-141).
     *
     * <p>流水线 (严格对齐 CC):
     * <ol>
     *   <li>shouldRun 门控 (L61-64) — false → 短路返回</li>
     *   <li>randomUUID (L66)</li>
     *   <li>buildMessages → user 内容 (L69)</li>
     *   <li>queryMessageCount 记录 (L70)</li>
     *   <li>systemPrompt 覆盖 (L73-75)</li>
     *   <li>useTools 门控 → tools 空/非空 (L78-79)</li>
     *   <li>getModel 惰性加载 (L82)</li>
     *   <li>queryModelWithoutStreaming (L85-108)</li>
     *   <li>parseResponse → logResult(success) (L113-125); 异常 → logResult(error) (L126-136)</li>
     *   <li>外层 catch → logError (L137-139)</li>
     * </ol>
     *
     * @param config   ApiQueryHook 配置
     * @param executor 侧信道 LLM 查询执行器 (queryModelWithoutStreaming 等价)
     * @return 可直接注册进 {@link PostSamplingHookRegistry} 的 hook
     */
    public static <T> PostSamplingHookRegistry.PostSamplingHook createApiQueryHook(
            ApiQueryHookConfig<T> config,
            ModelQueryExecutor executor) {
        return context -> {
            try {
                ApiQueryHookContext hookCtx = ApiQueryHookContext.from(context);
                if (!config.shouldRun().test(hookCtx)) {
                    return;
                }
                // [IMP-HOOKS-S7 D3 涟漪] CC apiQueryHookHelper.ts:73-75 ——
                //   config.systemPrompt ? asSystemPrompt([config.systemPrompt]) : context.systemPrompt：
                //   config 覆盖为单元素数组，否则用上下文段数组原样。Java executor.query 收
                //   String —— 段数组以 \n\n 连接（滤 boundary/空段），与 LlmAgentLoop:3189-3192
                //   String 契约 join 同约定。
                String uuid = UUID.randomUUID().toString();
                String userMessage = config.buildMessages().apply(hookCtx);
                hookCtx = hookCtx.withQueryMessageCount(1); // Java 单消息等价
                String systemPrompt = config.systemPrompt() != null
                        ? config.systemPrompt()
                        : hookCtx.systemPrompt().stream()
                            .filter(s -> s != null && !s.isBlank()
                                && !com.nexusai.application.agent.prompt.SystemPromptAssembler
                                    .SYSTEM_PROMPT_DYNAMIC_BOUNDARY.equals(s))
                            .collect(java.util.stream.Collectors.joining("\n\n"));
                boolean useTools = config.useTools() == null || config.useTools();
                // CC :79 useTools ? context.toolUseContext.options.tools : []
                com.fasterxml.jackson.databind.node.ArrayNode tools = useTools
                        ? toOpenAiToolsArray(hookCtx.toolUseContext())
                        : null;
                String model = config.getModel().apply(hookCtx);

                // [CCJ-EXEC-03] helper 内建查询选项 · 对齐 CC apiQueryHookHelper.ts:85-108
                //   queryModelWithoutStreaming options 全集：thinkingConfig disabled (:88)、
                //   temperatureOverride 0 (:102)、agents=activeAgents (:103，Java 无通道 → null 携带)、
                //   querySource=config.name (:104)、mcpTools [] (:105)、
                //   isNonInteractiveSession 自 ToolUseContext (:98-99)、hasAppendSystemPrompt (:100-101，
                //   Java 无 appendSystemPrompt 通道 → null 携带)、signal=createAbortController().signal (:90)。
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                    List.of(),                         // history — CC messages 已由 buildMessages 承载（单条 user 等价）
                    tools,                             // tools — CC :79
                    null,                              // outputFormat — CC 未设
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),  // :88
                    0d,                                // temperatureOverride — :102
                    config.name(),                     // querySource — :104
                    new com.nexusai.application.agent.tool.AbortController(),  // signal — :90
                    null,                              // maxTokens — CC 未设
                    null,                              // skipCacheWrite
                    null,                              // enablePromptCaching
                    null,                              // agents — CC :103 activeAgents, Java 无通道 → null 携带对齐
                    null,                              // hasAppendSystemPrompt — CC :100-101, Java 无通道 → null
                    List.of(),                         // mcpTools — CC :105 []
                    hookCtx.toolUseContext() != null
                        ? hookCtx.toolUseContext().isNonInteractiveSession() : null  // :98-99
                );

                LlmProvider.LlmRawResponse response = executor.query(systemPrompt, userMessage, model, useTools, options);
                String content = response.content().trim();

                try {
                    T result = config.parseResponse().apply(content, hookCtx);
                    config.logResult().accept(
                            new ApiQuerySuccess<>(config.name(), result, response.id(), model, uuid),
                            hookCtx);
                } catch (Exception parseError) {
                    config.logResult().accept(
                            new ApiQueryError<>(config.name(), parseError.getMessage(), uuid),
                            hookCtx);
                }
            } catch (Exception e) {
                log.warn("ApiQueryHook '{}' 侧信道查询失败: {}", config.name(), e.getMessage());
            }
        };
    }

    /**
     * ToolUseContext 工具集 → OpenAI function-calling 格式 · 对齐 CC
     * apiQueryHookHelper.ts:79 {@code useTools ? context.toolUseContext.options.tools : []}
     * （Java 等价：TUC.availableTools()，序列化同 ExecPromptHook.toOpenAiToolsArray）。
     */
    private static com.fasterxml.jackson.databind.node.ArrayNode toOpenAiToolsArray(
            ToolUseContext tuc) {
        if (tuc == null || tuc.availableTools() == null || tuc.availableTools().isEmpty()) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ArrayNode arr =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (com.nexusai.application.agent.tool.Tool t : tuc.availableTools()) {
            if (t == null) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode fn =
                arr.addObject().putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description() != null ? t.description() : "");
            com.fasterxml.jackson.databind.JsonNode schema = t.inputSchema();
            if (schema == null) {
                fn.putObject("parameters");
            } else {
                fn.set("parameters", schema);
            }
        }
        return arr.isEmpty() ? null : arr;
    }
}
