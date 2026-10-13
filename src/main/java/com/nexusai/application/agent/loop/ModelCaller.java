package com.nexusai.application.agent.loop;

import com.nexusai.infra.llm.LlmInvocationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [H7-arch Phase 5-2 P3-④] callModel 静态辅助 · loop 侧的<b>网关适配器</b>
 * （把 {@link ModelRequest} 交给 {@link LlmInvocationGateway}，后者是流式调用的唯一落点）。
 *
 * <p><b>WHY 存在</b>: Main/Subagent/Hook 三路 deps 的 callModel 实现完全同构
 * （取 provider + 逐字段透传 stream），抽此单点避免三处复制。
 * {@link LoopDeps#callModel} 接口默认实现即委托本类——匿名 deps（测试 / 单测）无需任何
 * override 即获得真实 LLM 调用能力。
 *
 * <p><b>[usage-gateway] 职责收窄</b>：本类不再自己解析 provider / 不再自己调 {@code provider.stream}
 * —— 请求解析、调用、<b>用量记账</b>全部移入 {@link LlmInvocationGateway}（用户裁定：用量统计前置到
 * 请求网关，流式与非流式统一，协议解析交各 provider）。本类只保留「从 ctx 取基础设施 + 委托」。
 *
 * <pre>
 * [usage-gateway] 原 待办 的落地说明（保留原始诉求以备查，勿删）
 * ─────────────────────────────────────────────────────────────────────
 * 原 待办（用户 2026-10-10 手写，位置=本方法内）：
 *   「在 ModelCaller 中，尝试在流中断、取消、结束时实现对 token 用量统计，用于观测每个请求的
 *     实际开销。以此实现对该项目所有经过 ModelCaller 的 LLM API 调用（subagent compact-fork
 *     cron background 等）的无遗漏统计和计费。相应的 AgentState 需要提供对应的关联上下文层级和
 *     会话信息以精确到具体哪个 agent 中。对于非流式请求 com.nexusai.infra.llm.LlmProvider.chatWithRaw
 *     由处理，一样需要处理 token 用量统计以观测开销。」
 *
 * 落地方式（对应关系逐条）：
 *   1. 「流中断/取消/结束都要统计」⇒ provider 在每个尝试结局把快照写入 {@code LlmCallScope.sink()}
 *      （SETTLED / ABORTED / FAILED；Anthropic 中断时 {@code StreamState} 已有部分 usage），
 *      网关在 {@code finally} 结算 ⇒ 四类结局都进账本。
 *   2. 「所有经过本处的调用无遗漏统计」⇒ 网关记账与调用方解耦：归属由 {@code ModelRequest}
 *      的 {@link com.nexusai.infra.llm.UsageAttribution} 显式携带，主/子代理/fork/cron/后台
 *      各自盖章；旁路调用（21 处 chat 家族）经 {@code LlmInvocationGateway.invoke} 走同一记账出口。
 *   3. 「AgentState 提供关联层级与会话信息」⇒ {@code AgentState} 持有
 *      {@code usageAttribution()}（会话/agent/归类/父子/深度）与可读构成的账本视图
 *      （{@code SessionUsageLedger}）。
 *   4. 「非流式 chatWithRaw 同样要统计」⇒ 非流式统一入口 {@code LlmInvocationGateway.invoke}
 *      返回完整 {@code AgentUsage} 并同样结算；{@code chatWithRaw} 的 usage 缺口已在 provider 侧补齐。
 * ─────────────────────────────────────────────────────────────────────
 * </pre>
 *
 * <p><b>行为契约</b>（对齐 P3-④ 提示词）: streaming fallback 的 onStreamingFallback 回调触发、
 * 错误分类（onError 原始异常透传）、工具提取（onAssistantMessage / onToolCallComplete）全部保留——
 * 网关只做参数透传 + 记账，不新增/删除任何回调语义。
 *
 * <p>[对抗核验 H13-GAP-4 v3] <b>abortController 透传</b>: blocks stream 把 request.abortController()
 * 传给 provider, abort 时 provider 以 CancellationException 终止底层请求（对齐 CC
 * createCombinedAbortSignal 硬中断）。本方法保持同步；异步解耦 loop 线程由 LlmAgentLoop 调用点
 * wrap（STREAM_EXECUTOR 后台执行 callModel）, 让 loop 的 {@code done.await(500ms)} abort 感知轮询
 * 对同步 provider（OpenAi/Anthropic）也能执行。
 *
 * @see LoopDeps#callModel
 * @see LlmInvocationGateway
 */
public final class ModelCaller {

    private static final Logger log = LoggerFactory.getLogger(ModelCaller.class);

    private ModelCaller() {}

    /**
     * 从 ctx 取 factory / resolver → 委托 {@link LlmInvocationGateway#stream}（解析 provider、
     * 调用、记账的唯一落点）。
     *
     * @param ctx     loop 基础设施容器（factory / resolver 从此取值，解耦 deps 记录字段）
     * @param request LLM call 请求（含记账归属与出口；未接线时为 null → 不记账，行为等价改造前）
     * @return {@link ModelResponse#SUBMITTED} 占位
     */
    public static ModelResponse call(AgentLoopContext ctx, ModelRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("ModelCaller → LlmInvocationGateway: model={} querySource={} stream=true accounting={}",
                request.modelName(), request.querySource(), request.usageRecorder() != null);
        }
        return LlmInvocationGateway.stream(ctx.llmProviderFactory(), ctx.modelConfigResolver(), request);
    }
}
