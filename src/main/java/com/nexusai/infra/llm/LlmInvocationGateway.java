package com.nexusai.infra.llm;

import com.nexusai.application.agent.loop.ModelRequest;
import com.nexusai.application.agent.loop.ModelResponse;
import com.nexusai.application.agent.tool.AgentUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * <b>LLM 请求网关</b> · 全仓 LLM 出站调用的唯一边界（流式与非流式在此统一）。
 *
 * <p><b>设计约束（用户裁定）</b>：
 * <ol>
 *   <li><b>协议差异交 provider</b>：网关不解析任何上游响应，只管「解析 provider / 建 sink / 调用 /
 *       结算」；usage 由各 provider 按自身协议写入 {@link LlmUsageSink}。</li>
 *   <li><b>流式与非流式统一</b>：{@link #stream} 与 {@link #invoke} 是仅有的两个入口，
 *       两者共用同一套「sink → 结算 → {@link LlmUsageRecorder}」记账出口。</li>
 *   <li><b>失败也要统计</b>：结算在 {@code finally} 中执行 ⇒ 正常完成、被 abort、抛异常、
 *       流式回退四类结局都会把 provider 已写入的快照记入账本（abort 时 provider 上报中断前
 *       已累积的部分用量；OpenAI 中断无数据则显式记 {@link UsageTokenSource#UNKNOWN}）。</li>
 *   <li><b>无 sink 记录也要留痕</b>：快照为空时仍记一条 0 token / UNKNOWN 的记录，
 *       使「调用次数 / 耗时」可观测，不伪造 token。</li>
 * </ol>
 *
 * <p><b>与 {@code ModelCaller} 的关系</b>：{@code ModelCaller} 保留为 loop 侧适配器（只做
 * ctx → factory/resolver 的取值与委托），真正的解析/调用/记账都在本类。
 *
 * <p><b>local-only</b>：本类不推送任何外部通道；记账只落进程内账本 + DB 明细表。
 */
public final class LlmInvocationGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmInvocationGateway.class);

    private LlmInvocationGateway() {}

    /** provider 解析结果（provider 实例 + 实际发送用的模型名 + provider 类型）。 */
    private record Resolved(LlmProvider provider, String sdkModelName, String providerType) {}

    /**
     * 流式调用（唯一流式边界）· 语义与改造前的 {@code ModelCaller.call} 逐字一致，新增用量结算。
     *
     * @param factory  provider 工厂（调用方从 {@code AgentLoopContext.llmProviderFactory()} 取）
     * @param resolver 模型配置解析器（可 null → providerType 走工厂默认、模型名原样透传）
     * @param request  调用请求（含 attribution / recorder 时可记账）
     * @return {@link ModelResponse#SUBMITTED} 占位（异步结果经回调送达，与改造前一致）
     */
    public static ModelResponse stream(LlmProviderFactory factory, ModelConfigResolver resolver,
                                       ModelRequest request) {
        Resolved resolved = resolve(factory, resolver, request.config(), request.modelName());
        return streamWith(resolved.provider(), resolved.sdkModelName(), resolved.providerType(), request);
    }

    /**
     * 流式调用（<b>provider 已由调用方解析</b>）· 与 {@link #stream} 共用同一套
     * sink → 结算 → 记账出口，供「调用方自己持有 provider 实例」的路径使用
     * （当前唯一调用方 = 压缩摘要 {@code StreamCompactSummary}：它需要按 provider 类型分派
     * Anthropic 专属重载，故已自行解析 provider）。
     *
     * <p><b>WHY 必须有这个入口</b>：用户裁定「统一 LLM 调用」—— 压缩调用的用量必须与主循环、
     * 子代理、旁路走**同一条**记账流程（四类结局、T3 估算、账本分层、每 call 落表），
     * 不允许再有第二条（旧的 {@code accumulateCompactionSessionCost} 通道已随之删除）。
     *
     * @param provider     已解析的 provider 实例（非 null）
     * @param sdkModelName 实际发给上游的模型名（已剥 provider 前缀）
     * @param providerType provider 类型（记账维度用；可 null）
     * @param request      调用请求（含 attribution / recorder 时记账）
     */
    public static ModelResponse streamWith(LlmProvider provider, String sdkModelName, String providerType,
                                           ModelRequest request) {
        Resolved resolved = new Resolved(provider, sdkModelName, providerType);
        LlmUsageSink sink = LlmUsageSink.collecting();
        LlmCallScope scope = new LlmCallScope(request.agentContext(), sink);
        long startedAt = System.currentTimeMillis();
        Throwable failure = null;
        // 末条 assistant 消息捕获：provider 未上报 usage 时（mock / 协议缺字段）按既有 T3 口径
        //   估算 output（text.length()/4）并标记 ESTIMATED —— 保留改造前「估算兜底」的可观测性，
        //   同时与 UPSTREAM 值分列（不混算，可对账）。
        com.nexusai.infra.llm.AssistantMessage[] lastMessage = new com.nexusai.infra.llm.AssistantMessage[1];
        java.util.function.Consumer<com.nexusai.infra.llm.AssistantMessage> capturing = msg -> {
            lastMessage[0] = msg;
            if (request.onAssistantMessage() != null) {
                request.onAssistantMessage().accept(msg);
            }
        };
        try {
            streamTo(request, resolved, scope, capturing);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            settleStream(request, resolved, sink, startedAt, failure, lastMessage[0]);
        }
        return ModelResponse.SUBMITTED;
    }

    /**
     * 非流式调用（唯一非流式边界）· 结果携带完整 usage，并同样结算进账本。
     *
     * @param factory  provider 工厂
     * @param resolver 模型配置解析器（可 null）
     * @param request  非流式请求（含 attribution / recorder 时可记账）
     * @return 统一结果（usage 恒非 null）
     */
    public static LlmProvider.LlmInvocationResult invoke(LlmProviderFactory factory, ModelConfigResolver resolver,
                                                        LlmInvocationRequest request) {
        Resolved resolved = resolve(factory, resolver, request.config(), request.modelName(),
            request.providerType());
        LlmUsageSink sink = LlmUsageSink.collecting();
        LlmCallScope scope = new LlmCallScope(request.agentContext(), sink);
        long startedAt = System.currentTimeMillis();
        Throwable failure = null;
        LlmProvider.LlmInvocationResult result = null;
        try {
            result = resolved.provider().invoke(request.config(), resolved.sdkModelName(),
                request.systemPrompt(), request.userMessage(), request.options(), scope);
            return result;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            settleNonStream(request, resolved, sink, result, startedAt, failure);
        }
    }

    /** 按 {@code thinkingConfig} 分派到 provider 的两档 blocks 重载（与改造前 ModelCaller 一致）。 */
    private static void streamTo(ModelRequest request, Resolved resolved, LlmCallScope scope,
                                 java.util.function.Consumer<com.nexusai.infra.llm.AssistantMessage> onAssistantMessage) {
        LlmProvider provider = resolved.provider();
        String modelForSdk = resolved.sdkModelName();
        if (request.thinkingConfig() != null) {
            if (log.isDebugEnabled()) {
                log.debug("LlmInvocationGateway: thinkingConfig={} 透传 20-arg blocks stream (model={})",
                    request.thinkingConfig().type(), modelForSdk);
            }
            provider.stream(
                request.config(), modelForSdk, request.blocks(), request.messages(), request.tools(),
                request.maxOutputTokensOverride(), request.taskBudget(), request.effortValue(),
                request.querySource(), request.thinkingConfig(),
                request.onChunk(), onAssistantMessage, request.onToolCallComplete(),
                request.onReasoningChunk(), request.onStreamingFallback(), request.abortController(),
                request.onError(), request.onComplete(), request.skipCacheWrite(), scope,
                idleControlOf(request));
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("LlmInvocationGateway: blocks 重载（{} blocks, querySource={}）透传 19-arg blocks stream (model={})",
                request.blocks() != null ? request.blocks().size() : 0, request.querySource(), modelForSdk);
        }
        provider.stream(
            request.config(), modelForSdk, request.blocks(), request.messages(), request.tools(),
            request.maxOutputTokensOverride(), request.taskBudget(), request.effortValue(),
            request.querySource(),
            request.onChunk(), onAssistantMessage, request.onToolCallComplete(),
            request.onReasoningChunk(), request.onStreamingFallback(), request.abortController(),
            request.onError(), request.onComplete(), request.skipCacheWrite(), scope,
            idleControlOf(request));
    }

    /**
     * [流空闲看门狗 · 合并] 末参透传：ModelRequest 未带控制载体（旧调用方 / 单测）→
     * {@link StreamIdleControl#NONE}（无看门狗），与 provider 侧 null 语义等价。
     */
    private static StreamIdleControl idleControlOf(ModelRequest request) {
        return request.streamIdleControl() != null ? request.streamIdleControl() : StreamIdleControl.NONE;
    }

    /** 流式结算：sink 快照逐条记账（空 → 记一条 UNKNOWN 调用事实）。 */
    private static void settleStream(ModelRequest request, Resolved resolved, LlmUsageSink sink,
                                     long startedAt, Throwable failure,
                                     com.nexusai.infra.llm.AssistantMessage lastMessage) {
        LlmUsageRecorder recorder = request.usageRecorder();
        long elapsed = System.currentTimeMillis() - startedAt;
        List<LlmUsageSink.LlmUsageSnapshot> snapshots = sink.snapshots();
        if (snapshots.isEmpty()) {
            // T3 估算兜底：provider 未上报 usage 但确实产出了文本 → 按 text.length()/4 估算 output，
            //   显式标 ESTIMATED（统计口径里单列，不与上游上报值混算）；无文本 → UNKNOWN/0。
            long estimatedOutput = estimateOutputTokens(lastMessage);
            boolean estimated = estimatedOutput > 0;
            record(recorder, request, resolved,
                estimated ? new AgentUsage(0L, estimatedOutput, null, null, null, null, null) : AgentUsage.EMPTY,
                estimated ? UsageTokenSource.ESTIMATED : UsageTokenSource.UNKNOWN,
                LlmUsagePhase.SETTLED, true, startedAt, elapsed,
                statusOf(failure, null), errorKindOf(failure));
            return;
        }
        for (LlmUsageSink.LlmUsageSnapshot snap : snapshots) {
            record(recorder, request, resolved, snap.usage(), snap.source(), snap.phase(), true,
                startedAt, elapsed, statusOf(failure, snap.phase()), errorKindOf(failure));
        }
    }

    /** 非流式结算：优先 sink 快照；provider 未写 sink 时用返回结果的 usage 兜底。 */
    private static void settleNonStream(LlmInvocationRequest request, Resolved resolved, LlmUsageSink sink,
                                        LlmProvider.LlmInvocationResult result, long startedAt, Throwable failure) {
        LlmUsageRecorder recorder = request.usageRecorder();
        long elapsed = System.currentTimeMillis() - startedAt;
        List<LlmUsageSink.LlmUsageSnapshot> snapshots = sink.snapshots();
        if (snapshots.isEmpty()) {
            AgentUsage usage = result != null ? result.usage() : AgentUsage.EMPTY;
            boolean upstream = usage != null && usage.totalTokens() > 0;
            record(recorder, request, resolved, upstream ? usage : AgentUsage.EMPTY,
                upstream ? UsageTokenSource.UPSTREAM : UsageTokenSource.UNKNOWN,
                failure != null ? LlmUsagePhase.FAILED : LlmUsagePhase.SETTLED, false,
                startedAt, elapsed, statusOf(failure, null), errorKindOf(failure));
            return;
        }
        for (LlmUsageSink.LlmUsageSnapshot snap : snapshots) {
            record(recorder, request, resolved, snap.usage(), snap.source(), snap.phase(), false,
                startedAt, elapsed, statusOf(failure, snap.phase()), errorKindOf(failure));
        }
    }

    private static void record(LlmUsageRecorder recorder, ModelRequest request, Resolved resolved,
                               AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase, boolean stream,
                               long startedAt, long elapsed, String status, String errorKind) {
        if (recorder == null) {
            return; // 未接线（单测 / 老调用方）→ 不记账，行为等价改造前
        }
        recorder.record(build(request, resolved, usage, source, phase, stream, startedAt, elapsed, status, errorKind));
    }

    private static void record(LlmUsageRecorder recorder, LlmInvocationRequest request, Resolved resolved,
                               AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase, boolean stream,
                               long startedAt, long elapsed, String status, String errorKind) {
        if (recorder == null) {
            return;
        }
        recorder.record(new LlmCallRecord(LlmCallRecord.newCallId(), request.usageAttribution(),
            request.modelName(), resolved == null ? request.modelName() : resolved.sdkModelName(),
            resolved == null ? request.providerType() : resolved.providerType(),
            request.querySource(), stream, phase, source,
            usage != null ? usage : AgentUsage.EMPTY, 0.0, false, null, status, errorKind, startedAt, elapsed));
    }

    private static LlmCallRecord build(ModelRequest request, Resolved resolved, AgentUsage usage,
                                       UsageTokenSource source, LlmUsagePhase phase, boolean stream,
                                       long startedAt, long elapsed, String status, String errorKind) {
        return new LlmCallRecord(LlmCallRecord.newCallId(), request.usageAttribution(),
            request.modelName(), resolved.sdkModelName(), resolved.providerType(),
            request.querySource(), stream, phase, source,
            usage != null ? usage : AgentUsage.EMPTY, 0.0, false, null, status, errorKind, startedAt, elapsed);
    }

    /** 状态短标签：异常优先，其次按尝试结局（SETTLED=ok / ABORTED=aborted / FAILED=error）。 */
    private static String statusOf(Throwable failure, LlmUsagePhase phase) {
        if (failure != null) {
            return failure instanceof java.util.concurrent.CancellationException ? "aborted" : "error";
        }
        if (phase == null) {
            return "ok";
        }
        return switch (phase) {
            case SETTLED -> "ok";
            case ABORTED -> "aborted";
            case FAILED -> "error";
        };
    }

    /** T3 估算口径（与改造前 LlmAgentLoop 的 {@code Math.max(1, text.length() / 4)} 同源）。 */
    private static long estimateOutputTokens(com.nexusai.infra.llm.AssistantMessage msg) {
        if (msg == null || msg.content() == null || msg.content().isEmpty()) {
            return 0L;
        }
        return Math.max(1L, msg.content().length() / 4L);
    }

    private static String errorKindOf(Throwable failure) {
        return failure != null ? failure.getClass().getSimpleName() : null;
    }

    /** provider / SDK 模型名解析（与改造前 ModelCaller 逐字一致：剥 provider 前缀，未命中回落原名）。 */
    private static Resolved resolve(LlmProviderFactory factory, ModelConfigResolver resolver,
                                    ProviderConfig config, String modelName) {
        return resolve(factory, resolver, config, modelName, null);
    }

    /**
     * 带 providerType 覆盖的解析（非流式旁路调用方常已自行解析类型，覆盖可避免二次解析分歧）。
     *
     * @param providerTypeOverride 非空 → 直接采用；null → 按模型名解析（解析器缺失 → 工厂默认）
     */
    private static Resolved resolve(LlmProviderFactory factory, ModelConfigResolver resolver,
                                    ProviderConfig config, String modelName, String providerTypeOverride) {
        String providerType = (providerTypeOverride != null && !providerTypeOverride.isBlank())
            ? providerTypeOverride
            : ModelConfigResolver.resolveProviderType(resolver, modelName);
        LlmProvider provider = factory.getProvider(config, providerType);
        String sdk = resolver != null ? resolver.resolveSdkModelName(modelName) : null;
        String sdkModelName = (sdk != null && !sdk.isBlank()) ? sdk : modelName;
        return new Resolved(provider, sdkModelName, providerType);
    }
}
