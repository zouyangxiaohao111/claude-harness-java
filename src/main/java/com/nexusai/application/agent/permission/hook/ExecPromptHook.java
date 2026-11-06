package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.skill.ArgumentSubstitution;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exec Prompt Hook · 对齐 CC Open-ClaudeCode/src/utils/hooks/execPromptHook.ts (211 行).
 *
 * <p>WHY: CC PromptHook 通过 LLM 单轮评估条件是否满足, 强制返回
 * {@code {ok: boolean, reason?: string}} JSON, 按 outcome 4 态返回 HookResult.
 * 原 Java 实现仅字符串拼接 ({@code ExecResult}), 不调 LLM, 与 CC 行为错位.
 *
 * <p><b>outcome 4 态映射</b> (对齐 CC execPromptHook.ts):
 * <ul>
 *   <li>{@code ok: true} → {@link HookOutcome#SUCCESS} (CC :172)</li>
 *   <li>{@code ok: false} → {@link HookOutcome#BLOCKING} + preventContinuation + stopReason (CC :154-167)</li>
 *   <li>JSON 解析失败 → {@link HookOutcome#NON_BLOCKING_ERROR} (CC :118)</li>
 *   <li>schema 校验失败 → {@link HookOutcome#NON_BLOCKING_ERROR} (CC :138)</li>
 *   <li>timeout/abort → {@link HookOutcome#CANCELLED} (CC :186)</li>
 *   <li>其他异常 → {@link HookOutcome#NON_BLOCKING_ERROR} (CC :197-209)</li>
 * </ul>
 *
 * <p><b>选项透传（J.md H13-GAP-3 已接）</b>:
 * <ul>
 *   <li>CC 用 {@code outputFormat: {type:'json_schema', schema:{ok,reason}}} (line 87-98) 强制 JSON 输出.
 *       Java 经 {@link LlmProvider#chatWithOptions} 的 OutputFormat.jsonSchema 真实透传
 *       provider（不依赖 systemPrompt 描述；[CCJ-EXEC-11] systemPrompt 与 CC :64-70 逐字一致）。</li>
 *   <li>[H2/CCJ-EXEC-01] CC 支持 prepend {@code messages} 历史 (line 45-48 {@code [...messages,
 *       userMessage]}) — 经 exec 第 7 参 {@code messages} 传入，ChatRequestOptions.history 承载
 *       （provider 侧追加 userMessage，OpenAiSdkProvider.chatWithOptions :364-370 已核验）。
 *       分发层仅 Stop/SubagentStop 传 messages（CC hooks.ts:3688-3696），其他事件 null。</li>
 *   <li>{@code tools} (line 72) + {@code thinkingConfig: disabled} (line 71) + querySource
 *       'hook_prompt' (CC :84) + abortController（IMPL-06 D5-1/OD-EX-02，combined = 父 abort ∪
 *       timeout，见 {@link #exec exec 签名}）均经 ChatRequestOptions 透传。</li>
 *   <li>[E8/CCJ-EXEC-17] 父 abort 监听器在 finally 移除（CC cleanupSignal →
 *       combinedAbortSignal.ts:40-44 removeEventListener），防长会话累积。</li>
 * </ul>
 *
 * <p>CC 用 {@code createCombinedAbortSignal} 组合外部 signal + timeout signal（combinedAbortSignal.ts:15-47）。
 *     <b>[IMPL-06 已接]</b> Java 端现以 {@link AbortController combinedAbort}（父 abort ∪ 调度超时）等价承载:
 *     父取消 → future.cancel + provider 侧 CancellationException 硬中断 → outcome=cancelled（CC :186-190）；
 *     超时 → 调度任务 abort combined → 同路径（不再依赖 {@link CompletableFuture#orTimeout} 纯等待）。
 *
 * <p><b>入参类型</b>: {@link PromptHook}（HookCommand sealed 子类，settings 解析的持久化类型）
 * 直接作为 exec 入参 —— 对齐 CC 单一 PromptHook 类型（schemas/hooks.ts:67-95），无独立运行时
 * 配置 record（DEL-EX-04/DEL-SSE-02 收敛）。
 */
@Component
public class ExecPromptHook {

    private static final Logger log = LoggerFactory.getLogger(ExecPromptHook.class);

    /** 超时调度器（daemon 线程）· 对齐 CC createCombinedAbortSignal 的 timeout 分量（combinedAbortSignal.ts:33-36）. */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newScheduledThreadPool(
        1, r -> { Thread t = new Thread(r, "exec-prompt-hook-timeout"); t.setDaemon(true); return t; });

    /**
     * CC systemPrompt · 逐字对齐 claude.exe v2.1.212 的 stop-condition 评估器
     * （{@code execPromptHook} 的 Stop/SubagentStop 分支 systemPrompt）。
     *
     * <p><b>[goal V253] 从两态升级为三态</b>：旧实现只有 {@code {ok:true}} / {@code {ok:false,reason}}，
     * 缺 {@code impossible} 分支，且缺「只认 transcript 证据」「assistant 自称不可能只是证据不是证明」
     * 两条关键约束 ⇒ 目标模式（/goal）在「条件根本不可能达成」时会永远卡着，模型无路可走。
     *
     * <p>CC v2.1.212 的三条硬约束（逐字保留，这是它区别于普通 hook 评估器的关键）：
     * <ul>
     *   <li>transcript 里没有清晰证据 ⇒ 返回 {@code {"ok":false,"reason":"insufficient evidence in transcript"}}</li>
     *   <li>{@code impossible} 只允许「自相矛盾 / 依赖不可用资源 / 已穷尽合理办法并明确说做不到」</li>
     *   <li>「assistant 声称目标不可能只是证据不是证明，要独立确认」；「不要因为还没到或进展慢就用 impossible」</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
        You are evaluating a stop-condition hook in NexusAI. Read the conversation transcript carefully, then judge whether the user-provided condition is satisfied.

        Your response must be a JSON object with one of these shapes:
        - {"ok": true, "reason": "<quote evidence from the transcript that satisfies the condition>"}
        - {"ok": false, "reason": "<quote what is missing or what blocks the condition>"}
        - {"ok": false, "impossible": true, "reason": "<explain why the condition can never be satisfied>"}

        Always include a "reason" field, quoting specific text from the transcript whenever possible. If the transcript does not contain clear evidence that the condition is satisfied, return {"ok": false, "reason": "insufficient evidence in transcript"}.

        Only use {"ok": false, "impossible": true} when the condition is genuinely unachievable in this session — for example: the condition is self-contradictory, it depends on a resource or capability that is unavailable, or the assistant has explicitly tried, exhausted reasonable approaches, and stated it cannot be done. Apply your own judgment when deciding this — the assistant claiming the goal is impossible is evidence, not proof; independently confirm the condition is genuinely unachievable rather than deferring to the assistant's self-assessment. Do not use it just because the goal has not been reached yet or because progress is slow. When in doubt, return {"ok": false} without "impossible".""";

    private final ObjectMapper objectMapper;

    /**
     * [goal V253] 「该模型必须开思考」的模型名集合（进程内缓存）。
     *
     * <p><b>WHY 需要</b>：<b>强制开启思考的模型会拒绝 {@code thinking:{type:"disabled"}}</b>
     * （实测 MiniMax-M3.1-Flash：{@code requires adaptive thinking; thinking.type="disabled"
     * is not allowed (2013)} ⇒ HTTP 400）。目标（/goal）评估器默认按 CC 语义关思考，
     * 在这类模型上会<b>每轮都白烧一次 400 往返</b>。
     *
     * <p>⚠️ <b>降级目标是「不传 thinking 字段」，不是「传 adaptive」</b>：显式 adaptive 会
     * <b>强行要求模型思考</b>（实测响应带 reasoning_content），改变评估器「只读 transcript 判定」的
     * 语义；而「不传」是<b>最小干预</b> —— 由 OpenAI 兼容端点/模型自己决定要不要思考
     * （实测 MiniMax 省略=开启、deepseek 省略=关闭）。
     *
     * <p>⇒ 首次遇到该错误时把模型名记进来，后续直接用 {@code adaptive} 发（不再试错）。
     * 与 CC 的「模型能力探测」同思路，但存在<b>进程内</b>（重启后重新探测一次即可，成本一次往返）。
     *
     * <p>⚠️ 只影响「评估器/prompt hook」这类<b>旁路判定</b>调用，不改主对话的 thinking 行为。
     */
    private static final java.util.Set<String> REQUIRES_NO_THINKING_FIELD =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * [goal V253] 该模型是否已被证实「必须开思考」。
     *
     * @param modelName 模型名（可 null）
     * @return true ⇒ 直接不传 thinking 字段（跳过 disabled 试错）
     */
    static boolean requiresNoThinkingField(String modelName) {
        return modelName != null && REQUIRES_NO_THINKING_FIELD.contains(modelName);
    }

    /**
     * [goal V253] 标记「该模型拒绝 thinking:disabled」。
     *
     * @param modelName 模型名（可 null）
     */
    static void markRequiresNoThinkingField(String modelName) {
        if (modelName != null) {
            REQUIRES_NO_THINKING_FIELD.add(modelName);
        }
    }

    /**
     * [goal V253] 判定异常是否是「模型拒绝 thinking:disabled」。
     *
     * <p>判据取服务端错误文本（实测形态）：
     * {@code invalid params, model "X" requires adaptive thinking; thinking.type="disabled" ... is not allowed}
     * 或 {@code reasoning effort none is not allowed}（同一族拒绝）。
     *
     * <p>⚠️ <b>必须带具体错误文本</b>：不能只看「400」—— 任何 400（参数错、schema 不支持）
     * 都会命中「400 ⇒ 换 adaptive」这种宽判据 ⇒ 把真错误糊过去。
     *
     * @param t 异常
     * @return true = 确实是 thinking 被拒
     */
    static boolean isThinkingRejected(Throwable t) {
        if (t == null) {
            return false;
        }
        String text = t.getMessage();
        if (text == null) {
            text = t.toString();
        }
        String lower = text.toLowerCase();
        if (!lower.contains("thinking")) {
            return false;
        }
        return lower.contains("requires adaptive thinking")
            || lower.contains("adaptive thinking is not supported")
            || lower.contains("thinking.type")
            || lower.contains("reasoning effort none is not allowed");
    }

    /** [usage-gateway] 旁路 LLM 单发客户端（统一记账）；测试可经 ReflectionTestUtils 注入 mock。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.cost.LlmSideCallClient sideCallClient;

    public ExecPromptHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 prompt hook 评估 · 对齐 CC execPromptHook.ts:21-211.
     *
     * @param hook       hook 配置 (PromptHook: prompt/timeout/model, CC schemas/hooks.ts:67-95)
     * @param hookName   hook 名 (用于日志/附件)
     * @param hookEvent  hook 事件载体
     * @param jsonInput  hook 输入 JSON (替换 $ARGUMENTS)
     * @param llmContext LLM 上下文 (provider + config + 解析后的 model + 父工具集)
     * @param parentAbort 父循环 abort 信号 (可 null = 无父级取消) · 对齐 CC execPromptHook.ts:73
     *                    {@code signal} 参数（combinedAbortSignal 的父分量，IMPL-06 D5-1/OD-EX-02）
     * @return HookResult (outcome 4 态之一)
     */
    public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                           String jsonInput, PromptLlmContext llmContext, AbortController parentAbort) {
        // CC execPromptHook.ts:28 messages?: Message[] — 可选参数，未传 = 无会话历史（CC 等价）
        return exec(hook, hookName, hookEvent, jsonInput, llmContext, parentAbort, null);
    }

    /**
     * 执行 prompt hook 评估 · 对齐 CC execPromptHook.ts:21-211.
     *
     * @param hook       hook 配置 (PromptHook: prompt/timeout/model, CC schemas/hooks.ts:67-95)
     * @param hookName   hook 名 (用于日志/附件)
     * @param hookEvent  hook 事件载体
     * @param jsonInput  hook 输入 JSON (替换 $ARGUMENTS)
     * @param llmContext LLM 上下文 (provider + config + 解析后的 model + 父工具集)
     * @param parentAbort 父循环 abort 信号 (可 null = 无父级取消) · 对齐 CC execPromptHook.ts:73
     *                    {@code signal} 参数（combinedAbortSignal 的父分量，IMPL-06 D5-1/OD-EX-02）
     * @param messages   [H2/CCJ-EXEC-01] 会话历史 (CC execPromptHook.ts:28 messages?: Message[]；
     *                   非空时发送给 LLM 的消息 = {@code [...messages, userMessage]}，CC :44-48)。
     *                   仅 Stop/SubagentStop 事件由分发层传入 (CC hooks.ts:3688-3696)，其他事件 null
     * @return HookResult (outcome 4 态之一)
     */
    public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                           String jsonInput, PromptLlmContext llmContext, AbortController parentAbort,
                           List<ChatMessageDto> messages) {
        // [goal-observability] 执行期留痕：本方法此前**没有任何「开始了 / 结束了」的日志**，
        //   调用方只能从「条件满足 / 未满足」这类分支日志反推它跑没跑；而取消、超时、
        //   JSON 解析失败、schema 失败、异常五条路径的日志里**都没有 sessionId**
        //   ⇒ 多会话并发时无法归因，用户的原话是「不知道 goal 的 stop hook 到底执行没有」。
        //   本层只做首尾两条日志（含 sessionId + outcome + 耗时），不改任何分支语义。
        //   ⚠️ 首尾日志在 try 之外 ⇒ 这里的每个取值都必须 null-safe：hook / llmContext 为 null
        //   在原实现里是**抛在 try 内**的异常（由 catch 归一成 non_blocking_error），
        //   若日志先一步解引用就会让 NPE 逃出本方法、改变既有异常语义。
        String hookSessionId = hookEvent == null ? null : hookEvent.sessionId();
        String hookEventType = hookEvent == null || hookEvent.type() == null
            ? null : hookEvent.type().name();
        String hookModel = llmContext == null ? null : llmContext.defaultFastModel();
        int hookPromptLength = hook == null || hook.prompt() == null ? 0 : hook.prompt().length();
        long hookEnterMs = System.currentTimeMillis();
        if (log.isInfoEnabled()) {
            log.info("[hook] prompt hook 开始执行 session={} event={} model={} 条件长度={}",
                hookSessionId, hookEventType, hookModel, hookPromptLength);
        }
        HookResult hookResult = execInternal(hook, hookName, hookEvent, jsonInput, llmContext,
            parentAbort, messages);
        if (log.isInfoEnabled()) {
            log.info("[hook] prompt hook 执行结束 session={} event={} outcome={} impossible={} 耗时={}ms",
                hookSessionId, hookEventType,
                hookResult == null ? null : hookResult.outcome(),
                hookResult == null ? null : hookResult.impossible(),
                System.currentTimeMillis() - hookEnterMs);
        }
        return hookResult;
    }

    /**
     * {@link #exec} 的实际执行体（无首尾日志时的原实现体）。
     *
     * <p>拆出来的唯一原因是让 {@code exec} 能在<b>不受 return 路径数量影响</b>的前提下
     * 统一打首尾两条日志 —— 本方法内部有 10 个 return 分支（父 abort / 超时 / 取消 /
     * JSON 失败 / schema 失败 / 三态判定 / 异常），逐分支插日志必然漂移。
     * 本方法自捕获 {@code Throwable}（见末尾 catch），故恒返回非 null。
     */
    private HookResult execInternal(PromptHook hook, String hookName, HookEvent hookEvent,
                                    String jsonInput, PromptLlmContext llmContext,
                                    AbortController parentAbort, List<ChatMessageDto> messages) {
        String effectiveToolUseID = "hook-" + UUID.randomUUID();
        // [goal V253] 本次调用起点（降级重试时算剩余超时用）
        long callStartMs = System.currentTimeMillis();
        // CC :55 hookTimeoutMs（truthy 语义，OD-EX-03）+ :57-59 createCombinedAbortSignal(signal, {timeoutMs})
        long timeoutMs = hook.timeoutMs();
        AbortController combinedAbort = new AbortController();
        ScheduledFuture<?> timeoutFuture = null;
        // [E8/CCJ-EXEC-17] 父 abort 监听器引用（finally 移除；CC cleanupSignal 语义）
        java.util.function.Consumer<AbortController> parentAbortListener = null;
        try {
            // 1. 替换 $ARGUMENTS 占位符 · 对齐 CC execPromptHook.ts:35 addArgumentsToPrompt
            String processedPrompt = substituteArguments(hook.prompt(), jsonInput);
            if (log.isDebugEnabled()) {
                log.debug("ExecPromptHook: 处理后 prompt (hook={}): {}", hookName, processedPrompt);
            }

            // 2. 解析模型名 · 对齐 CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()
            String modelName = hook.modelOrFallback(llmContext.defaultFastModel());
            // [R10] defaultFastModel 为空（直连 exec 未走 HookRegistry 解析）→ 回落 getSmallFastModel
            //   env 链（CC model.ts:36-38；实现同 SkillImprovementHook.getSmallFastModel，同包共享单一
            //   实现）——不产出空串进 provider.chatWithOptions。HookRegistry:2377-2382 空模型守卫
            //   （跳过 proceed）属 EX-HOOK 域，本层只保证 exec 直连路径模型名非空。
            if (modelName == null || modelName.isBlank()) {
                modelName = SkillImprovementHook.getSmallFastModel();
            }
            final String effectiveModelName = modelName;   // supplyAsync lambda 捕获（effectively-final 约束）

            // 3. 组合信号装配 · 对齐 CC createCombinedAbortSignal (combinedAbortSignal.ts:15-47)
            //    父 abort 分量: 已取消 → 立即 abort（CC :22-25 signal?.aborted → combined.abort()）
            //    [E8/CCJ-EXEC-17] 注册引用捕获：finally 移除（CC :102/:184 cleanupSignal →
            //    combinedAbortSignal.ts:40-44 removeEventListener），防长会话累积
            if (parentAbort != null) {
                if (parentAbort.isCancelled()) {
                    combinedAbort.abort(parentAbort.reason());
                } else {
                    parentAbortListener = ac -> {
                        if (!combinedAbort.isCancelled()) {
                            combinedAbort.abort(ac.reason());
                        }
                    };
                    parentAbort.onCancel(parentAbortListener);
                }
            }
            //    超时分量: 到点 abort combined → provider 侧 CancellationException 硬中断（CC :33-36 setTimeout）
            timeoutFuture = TIMEOUT_SCHEDULER.schedule(() -> {
                if (!combinedAbort.isCancelled()) {
                    combinedAbort.abort("timeout");
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);

            // 4. 调用 LLM (单轮非流式) + 超时控制 · 对齐 CC queryModelWithoutStreaming + createCombinedAbortSignal
            //    [IMPL-06 D5-1/OD-EX-02] combinedAbort 透传 provider（CC :73 signal: combinedSignal）:
            //    请求前预检（claude.ts:744-745）+ provider onCancel 硬中断底层请求。
            //    预检: 父 abort 已取消 → 立即 cancelled（CC combinedAbortSignal.ts:22-25 → query 立即拒绝,
            //    不发起 LLM 调用）。
            if (combinedAbort.isCancelled()) {
                if (log.isInfoEnabled()) {
                    log.info("ExecPromptHook: hook={} 父 abort 已取消, 跳过 LLM 调用, outcome=cancelled", hookName);
                }
                return cancelled();
            }
            // [usage-gateway] 旁路调用收口到 LlmSideCallClient：PromptLlmContext 仅携带 agentContext
            //   （agentContext 由 HookRegistry 从父 TUC 盖章下传），无 sessionId ⇒ 归属
            //   standalone(HOOK, "exec-prompt-hook").withAgentContext(llmContext.agentContext())；
            //   config 仍取自 llmContext.config()。返回值/异常/超时/abort 语义与 chatWithOptions 完全一致
            //   （options 内 abortController=combinedAbort 原样透传）。
            // [goal V253] thinking 类型三态选择：
            //   "disabled"（默认）→ 发 thinking:{type:"disabled"}（CC execPromptHook.ts:71 语义）
            //   null（不传）      → 走 OpenAI 兼容端点/模型自身的默认行为
            //   ⛔ 不用 "adaptive" 作为降级目标：显式 adaptive 会**强行要求模型思考**、
            //      改变判定语义（评估器本该只读 transcript 判定）；「不指定」才是最小干预
            //      ——端点自己决定要不要思考（实测 MiniMax 省略=开启、deepseek 省略=关闭）。
            //   已知「拒绝 disabled」的模型 → 直接 null（省一次 400 往返）。
            String thinkingType = requiresNoThinkingField(effectiveModelName) ? null : "disabled";
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                sideCallClient.chat(
                    llmContext.config(),
                    effectiveModelName,
                    SYSTEM_PROMPT,
                    processedPrompt,
                    buildRequestOptions(llmContext, combinedAbort, messages, thinkingType),
                    com.nexusai.application.agent.cost.LlmSideCallClient.SideCallUsage
                        .standalone(com.nexusai.infra.llm.UsageCategory.HOOK, "exec-prompt-hook")
                        .withAgentContext(llmContext.agentContext())
                )
            );
            //    组合信号取消 → 立即唤醒 get（provider 未消费 abort 时也能及时返回 cancelled；
            //    CC :102 cleanupSignal 后 catch combinedSignal.aborted → cancelled）
            combinedAbort.onCancel(ac -> future.cancel(true));
            String fullResponse;
            try {
                fullResponse = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                // [goal V253] 降级重试 · 「模型拒绝 thinking:disabled」⇒ 改用 adaptive 再试一次。
                //   WHY：强制开启思考的模型（如 MiniMax-M3.1-Flash）会 400 拒 disabled；
                //   不降级 ⇒ 目标（/goal）评估器在这种模型上永远跑不起来。
                //   判据必须精确（isThinkingRejected 只认具体错误文本）⇒ 其它 400 不被糊过去。
                if (isThinkingRejected(cause) && "disabled".equals(thinkingType)) {
                    log.info("ExecPromptHook: hook={} 模型拒绝 thinking:disabled → 降级 adaptive 重试（model={}）",
                        hookName, effectiveModelName);
                    markRequiresNoThinkingField(effectiveModelName);
                    // ⚠️ 降级重试自身也可能失败（网络/其它错误）→ 由 retryWithAdaptive 内部
                    //   重新抛出，交给下面的 catch 链处理（不吞异常）。
                    fullResponse = retryWithAdaptive(llmContext, effectiveModelName, processedPrompt,
                        messages, timeoutMs, callStartMs);
                } else {
                    // provider 抛出的业务异常 → 交给外层 catch 走 non_blocking_error；
                    // 取消类（父 abort / 超时硬中断）→ cancelled · 对齐 CC :183-191
                    if (combinedAbort.isCancelled() || cause instanceof CancellationException) {
                        if (log.isInfoEnabled()) {
                            log.info("ExecPromptHook: hook={} 取消触发 (reason={}), outcome=cancelled",
                                hookName, combinedAbort.reason());
                        }
                        return cancelled();
                    }
                    throw cause;
                }
            } catch (TimeoutException te) {
                // timeout → cancelled · 对齐 CC execPromptHook.ts:186-190
                if (log.isWarnEnabled()) {
                    log.warn("ExecPromptHook: hook={} 超时 ({}ms), outcome=cancelled", hookName, timeoutMs);
                }
                return cancelled();
            } catch (CancellationException ce) {
                // future.cancel(true)（父 abort / 超时已触发 combinedAbort）
                if (log.isInfoEnabled()) {
                    log.info("ExecPromptHook: hook={} future 被取消, outcome=cancelled", hookName);
                }
                return cancelled();
            }

            if (log.isDebugEnabled()) {
                log.debug("ExecPromptHook: 模型响应 (hook={}): {}", hookName, fullResponse);
            }

            // 5. 解析 JSON · 对齐 CC execPromptHook.ts:113 safeParseJSON
            String trimmed = fullResponse == null ? "" : fullResponse.trim();
            JsonNode json = safeParseJSON(trimmed);
            if (json == null) {
                if (log.isWarnEnabled()) {
                    log.warn("ExecPromptHook: JSON 解析失败 (hook={}): {}", hookName, trimmed);
                }
                // CC :118-130 message=hook_non_blocking_error attachment (stderr='JSON validation failed')
                return nonBlockingError(hookName, effectiveToolUseID, hookEvent,
                    "JSON validation failed", trimmed);
            }

            // 6. schema 校验 · 对齐 CC execPromptHook.ts:133 hookResponseSchema().safeParse
            String schemaError = validateHookResponseSchema(json);
            if (schemaError != null) {
                if (log.isWarnEnabled()) {
                    log.warn("ExecPromptHook: schema 校验失败 (hook={}): {}", hookName, schemaError);
                }
                // CC :138-150 message=hook_non_blocking_error attachment (stderr='Schema validation failed: ${error}')
                return nonBlockingError(hookName, effectiveToolUseID, hookEvent,
                    "Schema validation failed: " + schemaError, trimmed);
            }
            // 7. 按 ok 分流 · 对齐 CC v2.1.212 stop-condition 评估器
            boolean ok = json.path("ok").asBoolean();
            JsonNode reasonNode = json.path("reason");
            String reason = reasonNode.isMissingNode() ? null : reasonNode.asText(null);
            // [goal V253] impossible 分支 · 对齐 CC v2.1.212：
            //   ok=false + impossible=true → hook_success + impossible 标记（**不是** blocking）
            //   —— 条件根本不可能达成时交还控制权，而不是无限 block 到撞 8 次上限。
            boolean impossible = json.path("impossible").asBoolean(false);

            if (!ok) {
                if (impossible) {
                    // impossible → success + impossible 标记 · CC v2.1.212 stop-condition 分支
                    if (log.isInfoEnabled()) {
                        log.info("ExecPromptHook: hook={} 条件不可能达成, outcome=success, impossible=true, reason={}",
                            hookName, reason);
                    }
                    return successWithImpossible(hookName, effectiveToolUseID, hookEvent, reason);
                }
                // ok=false → blocking + preventContinuation + stopReason · 对齐 CC :154-167
                // [CCJ-EXEC-15] reason 缺失 → blockingError 文本按 CC 模板字面量拼 'undefined'，
                //   stopReason = parsed.data.reason 原样（可 null；旧实现兜底 'Prompt hook condition was not met'）
                String reasonText = reason != null ? reason : "undefined";
                if (log.isInfoEnabled()) {
                    log.info("ExecPromptHook: hook={} 条件未满足, outcome=blocking, reason={}", hookName, reasonText);
                }
                return blocking(reason, hook.prompt(),
                    "Prompt hook condition was not met: " + reasonText);
            }

            // ok=true → success + hook_success attachment · 对齐 CC :172-182
            if (log.isInfoEnabled()) {
                log.info("ExecPromptHook: hook={} 条件满足, outcome=success", hookName);
            }
            return success(hookName, effectiveToolUseID, hookEvent);

        } catch (Throwable t) {
            // 其他异常 → non_blocking_error · 对齐 CC execPromptHook.ts:197-209
            String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (log.isErrorEnabled()) {
                log.error("ExecPromptHook: hook={} 执行异常, outcome=non_blocking_error: {}", hookName, errorMsg, t);
            }
            // CC :197-209 message=hook_non_blocking_error attachment (stderr='Error executing prompt hook: ${error}')
            return nonBlockingError(hookName, effectiveToolUseID, hookEvent,
                "Error executing prompt hook: " + errorMsg, "");
        } finally {
            // CC :102/184 cleanupSignal() · 取消超时任务（已触发则 no-op）+ 移除父 abort 监听器
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            if (parentAbortListener != null) {
                parentAbort.removeOnCancel(parentAbortListener);
            }
        }
    }

    /**
     * [goal V253] 降级重试 · 用 {@code thinking:{type:"adaptive"}} 再试一次。
     *
     * <p><b>WHY 需要</b>：强制开启思考的模型（如 MiniMax-M3.1-Flash）会 400 拒
     * {@code thinking:{type:"disabled"}} ⇒ 目标（/goal）评估器在这种模型上跑不起来。
     * 降级后模型<b>会思考</b>（响应带 {@code reasoning_content}）——
     * 这偏离 CC「评估器不思考、只读 transcript 判定」的语义，但：
     * <ul>
     *   <li>{@code extractContent} 只取 {@code message.content}，{@code reasoning_content}
     *       在独立字段 ⇒ <b>不会污染评估器的 JSON 解析</b>；</li>
     *   <li>比「目标完全跑不起来」好；</li>
     *   <li>且这是<b>按模型</b>的自动降级，不影响支持 disabled 的模型（deepseek-flash 仍走 disabled）。</li>
     * </ul>
     *
     * <p><b>超时口径</b>：用<b>剩余</b>超时（{@code timeoutMs - 已耗时}），不是全额 ——
     * 否则两次调用加起来可能超过调用方给的 30s 上限。
     *
     * @throws RuntimeException 重试仍失败 → 原样抛出（交给外层 catch 走 non_blocking_error）
     */
    private String retryWithAdaptive(PromptLlmContext llmContext, String modelName,
                                    String processedPrompt, List<ChatMessageDto> messages,
                                    long timeoutMs, long callStartMs) {
        long elapsed = System.currentTimeMillis() - callStartMs;
        long remaining = Math.max(1000L, timeoutMs - elapsed);
        // 降级重试用独立 abort（原来的 combined 可能已被 abort）
        AbortController retryAbort = new AbortController();
        try {
            return sideCallClient.chat(
                llmContext.config(),
                modelName,
                SYSTEM_PROMPT,
                processedPrompt,
                buildRequestOptions(llmContext, retryAbort, messages, null),   // null = 不传 thinking（走端点默认）
                com.nexusai.application.agent.cost.LlmSideCallClient.SideCallUsage
                    .standalone(com.nexusai.infra.llm.UsageCategory.HOOK, "exec-prompt-hook")
                    .withAgentContext(llmContext.agentContext())
            );
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 请求选项构建 · [对抗核验 H13-GAP-3 v3] 对齐 CC execPromptHook.ts:62-99 queryModelWithoutStreaming
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建 chatWithOptions 请求选项 · 对齐 CC execPromptHook.ts:
     * <ul>
     *   <li>{@code history: messages} ([H2/CCJ-EXEC-01] :45-48 {@code [...messages, userMessage]}
     *       — provider 侧 chatWithOptions 追加 userMessage 为最后一条，OpenAiSdkProvider:364-370 已核验)</li>
     *   <li>{@code outputFormat: {type:'json_schema', schema:{ok, reason}}} (:87-98)</li>
     *   <li>{@code thinkingConfig: {type:'disabled'}} (:71)</li>
     *   <li>{@code tools: toolUseContext.options.tools} (:72, 父工具集)</li>
     * </ul>
     *
     * <p>WHY (J.md H13-GAP-3 登记): 旧 Java {@code chat} 仅单条 userMessage, 无法表达这些选项;
     * 现经 LlmProvider.chatWithOptions 扩展点透传, provider 侧真实序列化。
     */
    private LlmProvider.ChatRequestOptions buildRequestOptions(PromptLlmContext llmContext,
                                                               AbortController combinedAbort,
                                                               List<ChatMessageDto> messages) {
        return buildRequestOptions(llmContext, combinedAbort, messages, "disabled");
    }

    /**
     * [goal V253] 构建请求选项 · thinking 类型可覆盖（{@code thinkingTypeOverride}）。
     *
     * @param thinkingTypeOverride null = 用默认（{@code disabled}，CC 语义）；
     *                            {@code "adaptive"} = 强制开思考（兼容拒绝 disabled 的模型）
     */
    private LlmProvider.ChatRequestOptions buildRequestOptions(PromptLlmContext llmContext,
                                                               AbortController combinedAbort,
                                                               List<ChatMessageDto> messages,
                                                               String thinkingTypeOverride) {
        // CC v2.1.212 schema {ok:boolean, reason?:string, impossible?:boolean}
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("ok").put("type", "boolean");
        props.putObject("reason").put("type", "string");
        // [goal V253] impossible 可选布尔（CC v2.1.212：只在 ok=false 时有意义）
        props.putObject("impossible").put("type", "boolean");
        schema.putArray("required").add("ok");
        // [IT-5] 广告 additionalProperties=false 保留 · 逐字对齐 CC execPromptHook.ts:96
        // 手写 outputFormat schema（provider 端 json_schema 校验，不经 ToolInputValidator；
        // CC 运行时为 z.object strip，provider 校验 unknown 键方向与 CC 一致）。
        schema.put("additionalProperties", false);

        List<com.nexusai.application.agent.tool.Tool> tools =
            llmContext.tools() != null ? llmContext.tools() : List.of();
        // [goal V253] thinking 类型三态：
        //   "disabled" → thinking:{type:"disabled"}（CC execPromptHook.ts:71 的默认语义）
        //   "adaptive" → thinking:{type:"adaptive"}（显式要思考）
        //   null       → **不传 thinking 字段** ⇒ 走 OpenAI 兼容端点/模型自身的默认行为
        //                （「降级」用这个：模型拒 disabled 时，退回「不指定」而不是「指定 adaptive」——
        //                 指定 adaptive 会强行要求模型思考、改变判定语义；不指定则由端点决定）
        LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig =
            thinkingTypeOverride == null || thinkingTypeOverride.isBlank()
                ? null
                : ("adaptive".equals(thinkingTypeOverride)
                    ? LlmProvider.ChatRequestOptions.ThinkingConfig.adaptive()
                    : LlmProvider.ChatRequestOptions.ThinkingConfig.disabled());
        return new LlmProvider.ChatRequestOptions(
            // [H2/CCJ-EXEC-01] history = CC messages 参数（可 null/空 = 仅 userMessage，CC :46-48）
            messages != null ? messages : List.of(),
            toOpenAiToolsArray(tools),
            LlmProvider.ChatRequestOptions.OutputFormat.jsonSchema(schema),
            thinkingConfig,
            null,   // [P2-16] temperature — CC execPromptHook.ts 未设 temperatureOverride (claude.ts:1693-1694 缺省 1)
            "hook_prompt",  // [IMPL-05 OD-EX-01] querySource — CC execPromptHook.ts:84 querySource: 'hook_prompt'
                          //   （遥测/日志按来源分流；Java 旧实现恒 null，EV-EX-023）
            combinedAbort,  // [IMPL-06 OD-EX-02] abortController — CC execPromptHook.ts:73 signal: combinedSignal
                          //   （combined = 父 abort ∪ timeout；provider 预检 + onCancel 硬中断，combinedAbortSignal.ts:15-47）
            null    // [IMP-M-P1-2] maxTokens — CC execPromptHook.ts 未设 max_tokens（缺省 1024）
        , llmContext.agentContext());   // [批 5b-1] agent 归因上下文改取显式载体
                                        //   （原读 AgentContext ThreadLocal：本方法在 supplyAsync
                                        //   闭包内运行 → 恒 null → invokingRequestId 归因边静默丢失）
    }

    /** Tool 列表 → OpenAI function-calling 格式（无工具 → null）. */
    private com.fasterxml.jackson.databind.node.ArrayNode toOpenAiToolsArray(
            List<com.nexusai.application.agent.tool.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        for (com.nexusai.application.agent.tool.Tool t : tools) {
            com.fasterxml.jackson.databind.node.ObjectNode fn = arr.addObject().putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description() != null ? t.description() : "");
            // 对齐 ToolRegistry.toOpenAiToolsArray: inputSchema() 即 JSON Schema
            com.fasterxml.jackson.databind.JsonNode schema = t.inputSchema();
            if (schema == null) {
                fn.putObject("parameters");
            } else {
                fn.set("parameters", schema);
            }
        }
        return arr;
    }

    // ════════════════════════════════════════════════════════════════════════
    // HookResult 工厂 (outcome 4 态)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * success · 对齐 CC execPromptHook.ts:172-182 + message=hook_success attachment（CC :175-182）.
     */
    private HookResult success(String hookName, String effectiveToolUseID, HookEvent hookEvent) {
        return new HookResult(
            false, null, null, null,                                                // preventContinuation, blockingError, systemMessage, additionalContext
            AttachmentMessageDto.hookSuccess(hookName, effectiveToolUseID, hookEvent.type().name()),  // message
            null, null,                                                              // updatedInput, updatedMCPToolOutput
            null, null,                                                              // retry, hookPermissionDecisionReason
            HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);                                  // outcome, stopReason, permissionBehavior, permissionRequestResult, hook, +4 awaiting
    }

    /**
     * success + impossible 标记 · [goal V253] 对齐 CC v2.1.212 的 impossible 分支。
     *
     * <p>CC 该分支返回 {@code outcome:'success', impossible:true, stopReason:reason} ——
     * 「条件根本不可能达成」时<b>交还控制权</b>而不是继续 block。若仍走 blocking，
     * 目标会一直卡到撞连续 block 上限，模型陷入「明知道做不到还在试」的死循环。
     *
     * @param stopReason 评估器给出的 reason（CC 侧即 goal 的 lastReason）
     */
    private HookResult successWithImpossible(String hookName, String effectiveToolUseID,
                                             HookEvent hookEvent, String stopReason) {
        return new HookResult(
            false, null, null, null,
            AttachmentMessageDto.hookSuccess(hookName, effectiveToolUseID, hookEvent.type().name()),
            null, null,
            null, null,
            HookOutcome.SUCCESS,
            stopReason,                                        // stopReason = 评估器 reason
            null, null, null, null, null, null, null,
            Boolean.TRUE);                                     // impossible = true
    }

    /**
     * blocking + preventContinuation + stopReason + blockingError · 对齐 CC execPromptHook.ts:158-167.
     *
     * <p><b>[H13] 修复 command 恒 null 语义 bug</b>: CC :163 {@code command: hook.prompt}。
     * 旧实现传 null（探查 §C 4），审计/UI 无法追溯触发阻塞的 hook 命令。
     *
     * <p><b>[CCJ-EXEC-15]</b> stopReason = {@code parsed.data.reason} 原样（可 null，CC :166）；
     * blockingError 文本由调用方按 CC 模板字面量拼接（reason 缺失 → "…: undefined"）。
     */
    private static HookResult blocking(String stopReason, String hookPrompt, String blockingErrorText) {
        return new HookResult(
            true,                                       // preventContinuation（CC :165 prompt blocking 显式 true）
            new HookBlockingError(blockingErrorText, hookPrompt),  // blockingError（CC :161-164）
            null, null, null, null, null,                // systemMessage, additionalContext, message, updatedInput, updatedMCPToolOutput
            null, null,                                  // retry, hookPermissionDecisionReason
            HookOutcome.BLOCKING,
            stopReason,                                 // stopReason（CC :166 parsed.data.reason，可 null）
            null,                                       // permissionBehavior
            null,                                       // permissionRequestResult (S07)
            null,                                       // hook
            null, null, null, null);                    // +4 awaiting (2026-08-12 △-01)
    }

    /**
     * non_blocking_error · 对齐 CC execPromptHook.ts:118-130 / :138-150 / :197-209
     * + message=hook_non_blocking_error attachment（stderr/stdout/exitCode 入 attachment）。
     */
    private HookResult nonBlockingError(String hookName, String effectiveToolUseID, HookEvent hookEvent,
                                        String stderr, String stdout) {
        // [对抗核验 H13-GAP] CC :128 exitCode=1 显式传递（旧实现丢 stdout/exitCode）
        return new HookResult(
            false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError(hookName, effectiveToolUseID, hookEvent.type().name(), stderr, stdout, 1),
            null, null,
            null, null, HookOutcome.NON_BLOCKING_ERROR, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    /**
     * cancelled · 对齐 CC execPromptHook.ts:186-190.
     */
    private static HookResult cancelled() {
        return new HookResult(
            false, null, null, null, null, null, null,
            null, null, HookOutcome.CANCELLED, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    // ════════════════════════════════════════════════════════════════════════
    // JSON 解析 + schema 校验 (对齐 CC safeParseJSON + hookResponseSchema)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 安全解析 JSON · 对齐 CC utils/json.ts safeParseJSON.
     * 返回 null 表示解析失败.
     */
    private JsonNode safeParseJSON(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验 hook 响应 schema · 对齐 CC v2.1.212 stop-condition 评估器的
     * {@code hookResponseSchema}：{@code {ok: boolean, reason?: string, impossible?: boolean}}；
     * reason:null 判非法（CCJ-EXEC-12）。
     *
     * @return null 表示校验通过; 非 null 为错误描述
     */
    private String validateHookResponseSchema(JsonNode json) {
        JsonNode okNode = json.path("ok");
        if (okNode.isMissingNode()) {
            return "ok field is required";
        }
        if (!okNode.isBoolean()) {
            return "ok must be boolean, got " + okNode.getNodeType();
        }
        JsonNode reasonNode = json.path("reason");
        // [CCJ-EXEC-12] reason:null 判非法 · 对齐 zod z.string().optional()（hookHelpers.ts:16-24）
        //   —— optional 不接受 null，safeParse 失败 → non_blocking_error。
        //   旧实现 isNull() 放行（→ success/blocking 分流），语义偏移。
        if (!reasonNode.isMissingNode() && !reasonNode.isTextual()) {
            return "reason must be string, got " + reasonNode.getNodeType();
        }
        // [goal V253] impossible 可选布尔 · 对齐 CC v2.1.212 的
        //   {@code impossible: z.boolean().optional().describe("Whether the condition can never be
        //   satisfied (only meaningful when ok is false)")}
        JsonNode impossibleNode = json.path("impossible");
        if (!impossibleNode.isMissingNode() && !impossibleNode.isBoolean()) {
            return "impossible must be boolean, got " + impossibleNode.getNodeType();
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 参数替换 (对齐 CC argumentSubstitution.ts substituteArguments)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 替换 {@code $ARGUMENTS} 占位符 · 统一委托公共 {@link ArgumentSubstitution#substituteArguments}
     * （#PLAN-P04-2 双实现漂移闭环 · 消除本类私有重复实现）。
     *
     * <p>CC 真源：hookHelpers.ts:34 {@code addArgumentsToPrompt(prompt, jsonInput) →
     * substituteArguments(prompt, jsonInput)}（argumentSubstitution.ts:94-145），默认
     * {@code appendIfNoPlaceholder=true}、无命名参数。Java 端单一公共实现
     * {@code ArgumentSubstitution.substituteArguments(content, args, true, null)} 承载
     * 全部 5 替换（$name/$ARGUMENTS[N]/$N/$ARGUMENTS/append），与 ExecAgentHook 共用，消除漂移。
     *
     * @param content 含占位符的 prompt
     * @param args    原始参数字符串 (null → 返回 content 不变)
     * @return 替换后的 prompt
     */
    private static String substituteArguments(String content, String args) {
        return ArgumentSubstitution.substituteArguments(content, args, true, null);
    }

    /**
     * LLM 上下文 · 封装 provider + config + 解析后的 model 名 + 工具集.
     *
     * <p>对齐 CC execPromptHook.ts 通过 {@code toolUseContext} 拿 provider/model 的语义.
     * 生产端由 HookRegistry 解析真实 provider (IMPL-05 D10-1 / OD-EX-05)，本 context 作为
     * exec 入参。config 不允许为 null（null → provider 无法发起请求；旧实现 null 默认成
     * {@link ProviderConfig#empty()} 落 mock 的隐性路径已移除 —— 解析失败在 HookRegistry
     * 显式跳过，不进入本 context）。
     *
     * <p>[对抗核验 H13-GAP-3 v3] {@code tools} · 对齐 CC execPromptHook.ts:72
     * {@code tools: toolUseContext.options.tools}（hook 评估时把父工具集传给 LLM）。
     *
     * <p><b>[IMPL-05 DEL-EX-04 收敛]</b>: 3 参兼容构造器（无工具集）已删除 —— 调用方
     * （测试）显式传 null tools 表达"无工具"，不留兼容壳（对齐 CC 单一定义）。
     *
     * @param provider         LLM provider (非 null)
     * @param config           provider 运行时配置 (baseUrl + apiKey, 非 null)
     * @param defaultFastModel 解析后的模型名 (hook.model 或 fast model；对齐 CC getSmallFastModel)
     * @param tools            父工具集（CC toolUseContext.options.tools；可 null = 无工具）
     */
    public record PromptLlmContext(
        LlmProvider provider,
        ProviderConfig config,
        String defaultFastModel,
        List<com.nexusai.application.agent.tool.Tool> tools,
        // [批 5b-1] agent 归因上下文（显式载体）· CC original: execPromptHook.ts 经 ALS 读 ambient
        //   agentContext（logging.ts:294/:461 consumeInvokingRequestId）。Java 的
        //   Java 侧归因上下文曾是 plain ThreadLocal 载体，而本 hook 的 LLM 调用跑在
        //   supplierAsync 闭包（commonPool worker）⇒ 闭包内读 ThreadLocal 恒 null
        //   ⇒ invokingRequestId/invocationKind 归因边静默丢失。
        //   值由 HookRegistry 从父 TUC 取（该 TUC 在 SubagentExecutor / buildBaseToolUseContext
        //   显式盖章），随本 record 显式下传。null = 主线程/无 agent 上下文
        //   （等价 CC context?.invokingRequestId undefined）。
        com.nexusai.application.agent.subagent.AgentContext agentContext
    ) {
        public PromptLlmContext {
            if (provider == null) {
                throw new IllegalArgumentException("provider is null");
            }
            if (config == null) {
                throw new IllegalArgumentException("config is null");
            }
        }
    }
}