package com.nexusai.application.agent.loop;

import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [H7-arch Phase 5-2 P3-④] callModel 静态辅助 · 把 {@link ModelRequest} 委托给
 * {@link LlmProvider#stream}（loop 内 provider.stream 调用点的唯一落点）。
 *
 * <p><b>WHY 存在</b>: Main/Subagent/Hook 三路 deps 的 callModel 实现完全同构
 * （{@code factory.getProvider(config)} + stream 逐字段透传），抽此单点避免三处复制。
 * {@link LoopDeps#callModel} 接口默认实现即委托本类——匿名 deps（测试 / 单测）无需任何
 * override 即获得真实 LLM 调用能力。
 *
 * <p><b>行为契约</b>（对齐 P3-④ 提示词）: streaming fallback 的 onStreamingFallback 回调触发、
 * 错误分类（onError 原始异常透传）、工具提取（onAssistantMessage / onToolCallComplete）全部保留——
 * 本方法仅做参数透传，不新增/删除任何回调语义。
 *
 * <p>[对抗核验 H13-GAP-4 v3] <b>abortController 透传</b>: blocks stream（17 参）把 request.abortController()
 * 传给 provider, abort 时 provider 以 CancellationException 终止底层请求（对齐 CC
 * createCombinedAbortSignal 硬中断）。本方法保持同步；异步解耦 loop 线程由 LlmAgentLoop 调用点
 * wrap（STREAM_EXECUTOR 后台执行 callModel）, 让 loop 的 {@code done.await(500ms)} abort 感知轮询
 * 对同步 provider（OpenAi/Anthropic）也能执行。
 *
 * @see LoopDeps#callModel
 */
public final class ModelCaller {

    private static final Logger log = LoggerFactory.getLogger(ModelCaller.class);

    private ModelCaller() {}

    /**
     * 从 ctx 取 factory（{@link AgentLoopContext#llmProviderFactory()}）→ 按 {@code request.config()}
     * 解析 provider → stream 逐字段透传（同步；异步解耦由 LlmAgentLoop 调用点 wrap）。
     * 返回最小 {@link ModelResponse} 占位（异步结果经回调送达）。
     *
     * <p>[IMP-15 REWORK] <b>maxOutputTokensOverride 透传</b>: maxOutputTokensOverride 非 null
     * （ESCALATED 升级重试 = 64000，CC query.ts:1213）时写入请求体 max_tokens；null（常规请求 /
     * CONTINUATION 续写）时 provider 按模型解析——两态均经 blocks 唯一路径透传
     * （DRIFT-10 升级值到达 API 的保证不变）。
     *
     * <p>[IMP-16 REWORK] <b>taskBudget 透传</b>: taskBudget 非 null 时透传（AnthropicSdkProvider
     * 写入请求体 output_config.task_budget + task-budgets-2026-03-13 beta header，对齐 CC
     * claude.ts:479-500）；null（未注入任务预算）= 不注入。结转后的 remaining 真正到达 provider
     * （MISS-4 根因闭合，非写后不读）。
     *
     * <p>[C-31] <b>effortValue 透传</b>: effortValue 非 null 时透传（AnthropicSdkProvider
     * 写入请求体 output_config.effort + effort-2025-11-24 beta header，对齐 CC claude.ts:1458 +
     * query.ts:694）；null（未设置会话级 effort）= 不注入。
     *
     * <p>[⊕C-1] 上述三参 + blocks/querySource 均经 {@link LlmProvider#stream} blocks 重载（17 参）
     * 单一路径透传——String systemPrompt 兼容分派（15/16/17/18-arg）已删除，无 arg 数回落逻辑。
     *
     * @param ctx     loop 基础设施容器（factory 从 ctx 读，解耦 deps 记录字段）
     * @param request LLM call 请求（17 字段镜像 provider.stream blocks 签名 + abortController）
     * @return {@link ModelResponse#SUBMITTED} 占位
     */
    public static ModelResponse call(AgentLoopContext ctx, ModelRequest request) {
        ProviderConfig config = request.config();
        // [MAINCHAIN-01] deps 主链路同 loop 直路：2 参 getProvider + resolver 取 providerType
        // （1 参恒落 openai_sdk → anthropic 型 provider 路由错）。providerType null → 工厂默认
        // openai_sdk（等价既有 1 参行为，不抛异常不落 mock）。
        // [FIX-STRIP-PREFIX] 发送名剥 provider 前缀：前端传全名 providerName/modelName，SDK model
        //   参数必须裸名（deepseek/deepseek-v4-flash → deepseek-v4-flash，否则 API 400）。
        //   resolveSdkModelName 未命中（裸名/别名）→ null → 回落 request.modelName() 原样透传。
        ModelConfigResolver resolver = ctx.modelConfigResolver();
        LlmProvider provider = ctx.llmProviderFactory().getProvider(config,
            ModelConfigResolver.resolveProviderType(resolver, request.modelName()));
        String sdkModelName = resolver != null
            ? resolver.resolveSdkModelName(request.modelName())
            : null;
        String modelForSdk = (sdkModelName != null && !sdkModelName.isBlank())
            ? sdkModelName
            : request.modelName();
        // [对抗核验 H13-GAP-4 v3] stream 带 abortController（request.abortController()）。
        //   本方法保持同步（async 由 LlmAgentLoop 调用点 wrap，见 loop() STREAM_EXECUTOR），
        //   让既有 mock 测试（同步 stub stream）不受异步时序影响。
        // [CCJ-EXEC-08] thinkingConfig 非 null（仅 hook agent 注入）→ 优先走带 thinkingConfig
        //   的 blocks stream 重载（19 参）· CC execAgentHook.ts:134 注入 {type:'disabled'} →
        //   query.ts:662 options.thinkingConfig → queryModelWithStreaming。
        //   默认实现忽略 thinkingConfig 委托 17-arg blocks 重载（Anthropic API 省略 thinking
        //   参数即 disabled，CC 等价；OpenAiSdkProvider 覆写发射 thinking:{type:'disabled'}）。
        //   主循环/子代理 thinkingConfig=null → 既有分支零变化。
        if (request.thinkingConfig() != null) {
            if (log.isDebugEnabled()) {
                log.debug("ModelCaller 数据流: thinkingConfig={} 透传 19-arg blocks stream (model={}) · CC execAgentHook.ts:134 → query.ts:662",
                    request.thinkingConfig().type(), modelForSdk);
            }
            provider.stream(
                config,
                modelForSdk,
                request.blocks(),
                request.messages(),
                request.tools(),
                request.maxOutputTokensOverride(),
                request.taskBudget(),
                request.effortValue(),
                request.querySource(),
                request.thinkingConfig(),
                request.onChunk(),
                request.onAssistantMessage(),
                request.onToolCallComplete(),
                request.onReasoningChunk(),
                request.onStreamingFallback(),
                request.abortController(),
                request.onError(),
                request.onComplete());
            return ModelResponse.SUBMITTED;
        }
        // [IMP-SP-08] blocks 发送边界：splitSysPromptPrefix 产物直达 blocks 重载（system 为
        //   text block 数组，每 block 可携带 cache_control；CC claude.ts:1376-1382）。
        // [⊕C-1] String 兼容契约已删除 —— blocks 重载为唯一发送契约
        //   （ModelRequest 无 String 字段）；blocks null/空 → 透传 null = 不发送 system。
        //   effort/taskBudget/override 经 blocks 路径透传，AnthropicSdkProvider 覆写后全量生效。
        if (log.isDebugEnabled()) {
            log.debug("ModelCaller 数据流: blocks 重载（{} blocks, querySource={}）透传 blocks stream (model={}) · CC claude.ts:1376-1382",
                request.blocks() != null ? request.blocks().size() : 0, request.querySource(), modelForSdk);
        }
        provider.stream(
            config,
            modelForSdk,
            request.blocks(),
            request.messages(),
            request.tools(),
            request.maxOutputTokensOverride(),
            request.taskBudget(),
            request.effortValue(),
            request.querySource(),
            request.onChunk(),
            request.onAssistantMessage(),
            request.onToolCallComplete(),
            request.onReasoningChunk(),
            request.onStreamingFallback(),
            request.abortController(),
            request.onError(),
            request.onComplete());
        return ModelResponse.SUBMITTED;
    }
}
