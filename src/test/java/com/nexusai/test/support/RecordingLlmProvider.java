package com.nexusai.test.support;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * <b>步骤 8 · 出站请求记录器</b>：provider 边界的一次真实实现，把每次 {@code stream(...)} 收到的
 * 头部三构件记成 {@link OutboundRequest}，并按脚本回放回复。
 *
 * <h2>⭐ 为什么是「真实现类」而不是 {@code Mockito.mock(LlmProvider.class)} + {@code doAnswer}</h2>
 * <p>本仓的 provider 桩历史上以<b>纯位置索引</b>消费回调
 * （{@code inv.getArgument(9/10/16)} 或 {@code args.length == 19 ? 大 : 小}），而
 * {@code LlmProvider.stream} 的<b>重载 arity 已被两次末尾追加推到 19/19/20</b>
 * —— 于是出现过「19 参时取到 20 参档的索引 ⇒ {@code ClassCastException} ⇒
 * {@code onComplete} 永不执行 ⇒ 300s 流超时」的真实前科
 * （详见 {@code LlmProviderStreamArityInvariantTest} 与
 * {@code LlmAgentLoopPerRunPromptAssemblyTest} 的修复史）。
 * <p>本类<b>直接实现 19 参抽象重载</b>（{@code ModelCaller} 走的 20 参 thinkingConfig 重载的
 * default 实现会委托到它）⇒ <b>零位置索引、零 arity 常量</b>，签名变更时在<b>编译期</b>暴露，
 * 不会退化成一次静默的 300s 超时。
 *
 * <h2>回放语义</h2>
 * <p>脚本按调用序号取第 N 个 {@link AssistantMessage}；脚本耗尽后<b>重复最后一条</b>
 * （便于「首轮 tool_calls + 之后恒 stop」这类用例不必按调用次数精确配脚本，
 * 但需要精确时也可给足条数 —— 越界读取会记入 {@link #scriptOverruns()} 供断言）。
 *
 * <p>纯测试基建：不做任何 I/O、不连网、不读盘。
 */
public final class RecordingLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(RecordingLlmProvider.class);

    private final List<OutboundRequest> requests = new CopyOnWriteArrayList<>();
    private final List<AssistantMessage> script;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger scriptOverruns = new AtomicInteger();

    /**
     * 回放前副作用钩子（入参 = 本次调用序号，0-based）· 默认 no-op。
     *
     * <p><b>WHY 需要它</b>：做「同 run 内文件被改动 ⇒ 下一轮尾部投递」这类用例时，需要在
     * <b>两轮请求之间</b>改盘。此时距 run 启动（readFileState 基线登记）已经过去，改盘会命中
     * {@code mtime > 记录时间戳} 判据 —— 于是无需注册真工具也能精确复现「工具改了文件」。
     */
    private volatile Consumer<Integer> beforeReply = n -> { };

    /** 设置回放前副作用钩子（链式；见字段 javadoc）。 */
    public RecordingLlmProvider beforeReply(Consumer<Integer> hook) {
        this.beforeReply = hook == null ? n -> { } : hook;
        return this;
    }

    private RecordingLlmProvider(List<AssistantMessage> script) {
        if (script == null || script.isEmpty()) {
            throw new IllegalArgumentException("脚本不能为空（至少一条回复，否则 loop 会空转直到流超时）");
        }
        this.script = List.copyOf(script);
    }

    /** 以脚本构造记录器（第 N 次调用取第 N 条；耗尽后重复最后一条）。 */
    public static RecordingLlmProvider of(AssistantMessage... scripted) {
        List<AssistantMessage> s = new ArrayList<>();
        for (AssistantMessage m : scripted) {
            if (m != null) {
                s.add(m);
            }
        }
        return new RecordingLlmProvider(s);
    }

    /** 全部出站请求（按调用时序；下标 0 = 本 provider 实例收到的第一次请求）。 */
    public List<OutboundRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    /** 第 index 次出站请求（越界抛 {@link IndexOutOfBoundsException}，⛔ 不返回 null）。 */
    public OutboundRequest request(int index) {
        return requests.get(index);
    }

    /** 累计收到的请求数。 */
    public int callCount() {
        return requests.size();
    }

    /** 脚本被耗尽、走了「重复最后一条」的调用次数（诊断用；非 0 不算失败，只说明脚本给少了）。 */
    public int scriptOverruns() {
        return scriptOverruns.get();
    }

    @Override
    public String type() {
        return "openai_compatible";
    }

    /**
     * 19 参抽象重载（唯一发送契约）· 记录 + 回放。
     *
     * <p>{@code ModelCaller} 的 thinkingConfig 分支走 20 参重载，其 default 实现委托到本方法
     * ⇒ 两条路径都被记录（thinkingConfig 不进 {@link OutboundRequest}：它不参与前缀稳定性判据）。
     */
    @Override
    public void stream(ProviderConfig config,
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
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       com.nexusai.application.agent.subagent.AgentContext agentContext) {
        requests.add(new OutboundRequest(systemPromptBlocks, history, tools));
        int n = calls.getAndIncrement();
        // [步骤 8 · 数据流日志] 每次出站请求记一行（只记形状与长度，⛔ 不打正文）：判前缀稳定性
        //   失败时，这行能直接指出「是第几次请求、system 多长、消息几条」，与断言里的线级投影互证。
        if (log.isDebugEnabled()) {
            OutboundRequest recorded = requests.get(requests.size() - 1);
            log.debug("[步骤8·出站请求记录] 第 {} 次: systemBlocks={} systemChars={} messages={} tools={}",
                n + 1, recorded.system().size(), recorded.systemText().length(),
                recorded.messageCount(), tools == null ? 0 : tools.size());
        }
        beforeReply.accept(n);
        AssistantMessage reply;
        if (n < script.size()) {
            reply = script.get(n);
        } else {
            scriptOverruns.incrementAndGet();
            reply = script.get(script.size() - 1);
        }
        // 与 MockLlmProvider 同一回放契约：onChunk（正文流）→ onAssistantMessage（完整消息）→ onComplete
        if (onChunk != null && reply != null && reply.content() != null && !reply.content().isEmpty()) {
            onChunk.accept(reply.content());
        }
        if (onAssistantMessage != null && reply != null) {
            onAssistantMessage.accept(reply);
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    @Override
    public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
        return "recording-provider-chat";
    }

    /** 20 参重载（blocks + thinkingConfig）· 显式覆写以记录 thinkingConfig 场景的同一份请求。 */
    @Override
    public void stream(ProviderConfig config,
                       String modelName,
                       List<SystemPromptBlock> systemPromptBlocks,
                       List<ChatMessageDto> history,
                       ArrayNode tools,
                       Integer maxOutputTokensOverride,
                       TaskBudgetParam taskBudget,
                       String effortValue,
                       String querySource,
                       LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       com.nexusai.application.agent.subagent.AgentContext agentContext) {
        stream(config, modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, querySource, onChunk, onAssistantMessage, onToolCallComplete,
            onReasoningChunk, onStreamingFallback, abortController, onError, onComplete,
            skipCacheWrite, agentContext);
    }
}
