package com.nexusai.application.agent.loop;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.function.Consumer;

/**
 * [H7-arch Phase 5-2 P3-④] LLM call 请求载体 · 对齐 CC {@code deps.callModel({...})}
 * (Open-ClaudeCode/src/query/deps.ts:21-31, queryModelWithStreaming 入参)。
 *
 * <p>字段镜像 {@link com.nexusai.infra.llm.LlmProvider#stream} 的签名（config / modelName / blocks /
 * messages / tools / maxOutputTokensOverride / taskBudget / effortValue / querySource + 8 个回调），
 * 使 loop 的 LLM 调用点收敛为 {@code params.deps().callModel(request)}——loop 不再直接持有 provider / 直调 stream。
 *
 * <p><b>Java 同步封装（wrapper 方案，提示词明示）</b>: provider.stream 为 void + 回调异步，
 * 本 record 仅承载入参；完整结果经回调（onChunk / onAssistantMessage / onError / onComplete）
 * 逐段送达，{@link ModelResponse} 为最小占位。回调留在 loop（lambda 捕获 loop 局部数组，
 * 均已 effectively final，Java 捕获无问题）。
 *
 * <p>[对抗核验 H13-GAP-4 v3] 新增 {@code abortController} 字段（13 参）· 对齐 CC
 * queryModelWithStreaming 的 {@code signal} 参数（AbortSignal 透传 provider 硬中断）。
 * Java 等价: {@link AbortController} 经 ModelCaller 透传 provider stream, abort 时 provider
 * 以 CancellationException 终止底层请求（ExecAgentHook 超时硬中断, H13-GAP-4）。
 *
 * <p>[IMP-15 REWORK] 新增 {@code maxOutputTokensOverride} 字段（14 参）· 对齐 CC
 * {@code queryModelWithStreaming({maxOutputTokensOverride})}（query.ts:687 options.maxOutputTokensOverride）。
 * <b>WHY</b>: ESCALATED 升级（max_tokens 8K→64K）重试必须携带该 override（CC query.ts:1213
 * {@code maxOutputTokensOverride: ESCALATED_MAX_TOKENS}），否则升级值 64000 从未到达 API
 * （DRIFT-10 在升级路径上依旧存在）。本字段经 {@link ModelCaller} 透传 provider blocks stream（17 参）→
 * {@code AnthropicSdkProvider.doStream} → {@code buildMessageParams} max_tokens。
 * @param blocks                  [IMP-SP-08] splitSysPromptPrefix 产物（可 null/空 = 无 system 字段）·
 *                                CC original: buildSystemPromptBlocks 输入（claude.ts:1376-1382），
 *                                发送契约数组态唯一（⊕C-1 已删除 String 兼容契约）
 * @param querySource             [IMP-SP-08] 调用源 · CC original: options.querySource（claude.ts:1378-1381），
 *                                blocks 发送边界遥测 + promptCacheBreakDetection（recordPromptState/checkResponseForCacheBreak）
 *                                用 · Java getCacheControl ttl 由 PromptCachingTtlConfig 配置（默认 '1h'，RES-R7），
 *                                与 querySource 无关（不做 CC 用户资格/allowlist 判定）
 * @param maxOutputTokensOverride 本次 call 的 max_tokens 覆盖（可 null = provider 按模型解析
 *                                {@code getMaxOutputTokensForModel}，CC claude.ts:1593-1594）
 * @param taskBudget              [IMP-16 REWORK] API task_budget 线参数（可 null = 不注入）· CC original:
 *                                options.taskBudget (Open-ClaudeCode/src/query.ts:699-706) {total, remaining?}，
 *                                经 {@link com.nexusai.application.agent.loop.ModelCaller} 透传 provider → 请求体
 *                                {@code output_config.task_budget}（claude.ts:479-500 configureTaskBudgetParams）。
 *                                remaining 由 loop 内部维护（query.ts:291），本字段镜像 callModel options 而非
 *                                输入契约（输入契约见 {@link com.nexusai.application.agent.TaskBudget}）。
 * @param effortValue             [C-31] 会话级 effort 值（可 null = 不注入）· CC original:
 *                                options.effortValue (Open-ClaudeCode/src/query.ts:694
 *                                {@code effortValue: appState.effortValue})，写入侧 = skill
 *                                contextModifier（SkillTool.ts:823-836）。经 {@link ModelCaller} 透传
 *                                provider → 请求体 {@code output_config.effort} + {@code effort-2025-11-24}
 *                                beta header（claude.ts:437-463 configureEffortParams + :1458
 *                                resolveAppliedEffort）。
 * @param onStreamingFallback     provider 内部流式失败降级非流式时回调 · 对齐 CC query.ts:678-680
 *                                （loop 在下一条 message 到达时 tombstone 已积累的部分 assistant 消息）
 * @param onComplete              正常完成回调（与 onError 互斥）
 */
public record ModelRequest(
    ProviderConfig config,                                  // provider 运行时配置（baseUrl + apiKey）
    String modelName,                                       // 本次 call 的 model（fallback 切换后的 effectiveModel）
    List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,  // [IMP-SP-08] splitSysPromptPrefix 产物（非 null/空 → ModelCaller 走 blocks 重载）
    String querySource,                                     // [IMP-SP-08] CC options.querySource（claude.ts:1378-1381；blocks 发送边界 + promptCacheBreakDetection 用）
    List<ChatMessageDto> messages,                          // 注入记忆 / todo reminder 后的 messagesForLlm
    ArrayNode tools,                                        // OpenAI function-calling 格式 tool 定义（llmToolsArray 产物）
    Integer maxOutputTokensOverride,                        // [IMP-15] max_tokens 覆盖（null = 按模型解析）· CC original: options.maxOutputTokensOverride (claude.ts:1593-1594)
    TaskBudgetParam taskBudget,                             // [IMP-16 REWORK] API task_budget 线参数（null = 不注入）· CC original: options.taskBudget (query.ts:699-706)
    String effortValue,                                     // [C-31] 会话级 effort 值（null = 不注入）· CC original: options.effortValue (query.ts:694)
    // [CCJ-EXEC-08] thinkingConfig · CC original: toolUseContext.options.thinkingConfig
    //   （execAgentHook.ts:134 注入 {type:'disabled'} → query.ts:662 options.thinkingConfig）。
    //   仅 hook agent（querySource=HOOK_AGENT）注入，主循环/子代理 null（零行为变化）；
    //   经 ModelCaller 透传 provider stream 新重载 → OpenAiSdkProvider 发射 thinking:{type:'disabled'}。
    com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig,
    Consumer<String> onChunk,                               // 每个文本增量回调
    Consumer<AssistantMessage> onAssistantMessage,          // 每个完整 assistant message 回调
    Consumer<ToolUseBlock> onToolCallComplete,              // 每个 tool_call 块完整时实时回调（真流式并行）
    Consumer<String> onReasoningChunk,                      // 每个 reasoning chunk 实时回调（真流式 reasoning）
    Runnable onStreamingFallback,                           // streaming→non-streaming 降级通知（CC query.ts:678-680）
    Consumer<Throwable> onError,                            // 错误回调；只触发一次
    Runnable onComplete,                                    // 正常完成回调；只触发一次，与 onError 互斥
    AbortController abortController                         // [H13-GAP-4 v3] 取消信号（可 null）
) {
}
