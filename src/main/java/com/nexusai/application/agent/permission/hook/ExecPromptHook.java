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
     * CC systemPrompt · 逐字对齐 execPromptHook.ts:64-70（无附加句 —
     * CCJ-EXEC-11 删除旧 'Return ONLY the JSON object, no other text.' 尾部附加句，
     * JSON 输出约束由 outputFormat json_schema 承担）。
     */
    private static final String SYSTEM_PROMPT = """
        You are evaluating a hook in Claude Code.

        Your response must be a JSON object matching one of the following schemas:
        1. If the condition is met, return: {"ok": true}
        2. If the condition is not met, return: {"ok": false, "reason": "Reason for why it is not met"}""";

    private final ObjectMapper objectMapper;

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
        String effectiveToolUseID = "hook-" + UUID.randomUUID();
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
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                llmContext.provider().chatWithOptions(
                    llmContext.config(),
                    effectiveModelName,
                    SYSTEM_PROMPT,
                    processedPrompt,
                    buildRequestOptions(llmContext, combinedAbort, messages)
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
            // 7. 按 ok 分流 · 对齐 CC execPromptHook.ts:154-172
            boolean ok = json.path("ok").asBoolean();
            JsonNode reasonNode = json.path("reason");
            String reason = reasonNode.isMissingNode() ? null : reasonNode.asText(null);

            if (!ok) {
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
        // CC :89-97 schema {ok:boolean, reason?:string} (hookResponseSchema 一致)
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("ok").put("type", "boolean");
        props.putObject("reason").put("type", "string");
        schema.putArray("required").add("ok");
        // [IT-5] 广告 additionalProperties=false 保留 · 逐字对齐 CC execPromptHook.ts:96
        // 手写 outputFormat schema（provider 端 json_schema 校验，不经 ToolInputValidator；
        // CC 运行时为 z.object strip，provider 校验 unknown 键方向与 CC 一致）。
        schema.put("additionalProperties", false);

        List<com.nexusai.application.agent.tool.Tool> tools =
            llmContext.tools() != null ? llmContext.tools() : List.of();
        return new LlmProvider.ChatRequestOptions(
            // [H2/CCJ-EXEC-01] history = CC messages 参数（可 null/空 = 仅 userMessage，CC :46-48）
            messages != null ? messages : List.of(),
            toOpenAiToolsArray(tools),
            LlmProvider.ChatRequestOptions.OutputFormat.jsonSchema(schema),
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            null,   // [P2-16] temperature — CC execPromptHook.ts 未设 temperatureOverride (claude.ts:1693-1694 缺省 1)
            "hook_prompt",  // [IMPL-05 OD-EX-01] querySource — CC execPromptHook.ts:84 querySource: 'hook_prompt'
                          //   （遥测/日志按来源分流；Java 旧实现恒 null，EV-EX-023）
            combinedAbort,  // [IMPL-06 OD-EX-02] abortController — CC execPromptHook.ts:73 signal: combinedSignal
                          //   （combined = 父 abort ∪ timeout；provider 预检 + onCancel 硬中断，combinedAbortSignal.ts:15-47）
            null    // [IMP-M-P1-2] maxTokens — CC execPromptHook.ts 未设 max_tokens（缺省 1024）
        );
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
     * 校验 hook 响应 schema · 对齐 CC hookHelpers.ts:16-24 hookResponseSchema (Zod).
     * {@code {ok: boolean, reason?: string}}；reason:null 判非法（CCJ-EXEC-12）。
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
        List<com.nexusai.application.agent.tool.Tool> tools
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