package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventData;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * PermissionDenied hook 执行器 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:3529-3562 executePermissionDeniedHooks}。
 *
 * <p>WHY: CC 以 {@code AsyncGenerator<AggregatedHookResult>} 惰性产出结果；Java 端用
 * {@link Stream} 保留"消费时执行"语义，并在没有对应事件 hook 时直接返回空流。
 *
 * <p>[P-AL-04 REQ-C-C1] 第 7 参 signal 桥接：CC 第 7 参 = {@code toolUseContext.abortController.signal}
 * (toolExecution.ts:1088)；Java 等价 = {@link AbortController}（agent.tool 包，{@code isCancelled()}
 * 即 {@code signal.aborted}）。调用方 (StreamingToolExecutor.maybeFirePermissionDeniedRetry) 透传
 * {@code ctx.abortController()}，已取消 → 早返空流（对齐 CC executeHooks 入口
 * {@code if (signal?.aborted) return}，hooks.ts:2015-2017）。
 */
public final class PermissionDeniedHookExecutor {

    private static final Logger log = LoggerFactory.getLogger(PermissionDeniedHookExecutor.class);

    private final HookRegistry hookRegistry;

    public PermissionDeniedHookExecutor(HookRegistry hookRegistry) {
        if (hookRegistry == null) {
            throw new IllegalArgumentException("hookRegistry is null");
        }
        this.hookRegistry = hookRegistry;
    }

    /**
     * 执行 PermissionDenied hooks。
     *
     * @param toolName 工具名（CC toolName, hooks.ts:3530）
     * @param toolUseId 工具调用 ID（CC toolUseID, hooks.ts:3531）
     * @param toolInput 工具输入（CC toolInput, hooks.ts:3532）
     * @param reason 拒绝原因（CC reason, hooks.ts:3533）
     * @param toolUseContext 工具上下文（CC toolUseContext, hooks.ts:3534）
     * @param permissionMode 权限模式（CC permissionMode, hooks.ts:3535，可为 null）
     * @param signal 取消信号 · CC original: {@code signal: AbortSignal}（hooks.ts:3536）；
     *               Java 等价 = {@link AbortController}（即 {@code toolUseContext.abortController()}，
     *               CC toolExecution.ts:1088 第 7 参）。已取消 → 早返空流（对齐
     *               {@code executeHooks} 入口 {@code signal?.aborted} 检查, hooks.ts:2015-2017）；
     *               null = 无取消信号（等价 CC signal undefined，不检查）
     * @return 惰性聚合结果流；无 hook 监听或 signal 已取消时返回空流（不消费时零成本）
     */
    public Stream<AggregatedHookResult> executePermissionDeniedHooks(
            String toolName,
            String toolUseId,
            JsonNode toolInput,
            String reason,
            ToolUseContext toolUseContext,
            String permissionMode,
            AbortController signal) {
        // [IMPL-02] 三源门控: 无 hook 监听 PermissionDenied → 立即返回空流
        // (CC hooks.ts:3541 hasHookForEvent('PermissionDenied', appState, sessionId)).
        // sessionId = toolUseContext.sessionId (CC: toolUseContext.agentId ?? getSessionId()
        // hooks.ts:3540; Java ToolUseContext.sessionId 为必填会话 ID, 等价 agentId 归因).
        String sessionId = toolUseContext != null && toolUseContext.sessionId() != null
            ? toolUseContext.sessionId() : null;
        if (!hookRegistry.hasHookForEvent("PermissionDenied", sessionId)) {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION_DENIED hook 早返: 三源均无 hook 监听 eventName=PermissionDenied, toolName={}, sessionId={}",
                    toolName, sessionId);
            }
            return Stream.empty();
        }
        // [P-AL-04 REQ-C-C1] 早返: signal 已取消 → 跳过 hook 执行（对齐 CC executeHooks 入口
        //   {@code if (signal?.aborted) return}, hooks.ts:2015-2017）。WHY: 用户已请求取消时
        //   不应再执行 retry hook / 注入 isMeta（旧实现调用方传 null，取消语义丢失——
        //   open-decisions REQ-C-C1 登记偏差："CC 请求取消时 retry hook 跳过，Java 仍完整执行注入 isMeta"）。
        if (signal != null && signal.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION_DENIED hook 早返: signal 已取消 (abortController.abort 已触发), toolName={}",
                    toolName);
            }
            return Stream.empty();
        }
        String agentId = toolUseContext != null && toolUseContext.agentId() != null
            ? toolUseContext.agentId().toString() : null;

        // [R32-c-1] CC PermissionDeniedHookInput 字段透传到 HookEvent.data map:
        //   tool_name / tool_input / tool_use_id / reason / permission_mode
        // [IMP-CF-01] 类型化 PermissionDenied record 承载载荷, 保证 permission_mode 字段也写入
        // (对齐 CC PermissionDeniedHookInput.permission_mode schema 字段).
        HookEventData data = new HookEventData.PermissionDenied(reason, toolUseId, permissionMode, null);
        HookEvent event = new HookEvent(
            HookEventType.PERMISSION_DENIED,
            sessionId, null, null, permissionMode, agentId,
            toolName, toolInput, null, null, null, null, data, 0L);
        Stream<GenericHook.HookResult> source = StreamSupport.stream(
            java.util.Collections.singletonList(event).spliterator(), false)
            .map(e -> hookRegistry.executeEvent(e, null, toolUseContext));
        return source
            .filter(result -> result != null)
            .map(this::toAggregated)
            .onClose(() -> {
                if (log.isDebugEnabled()) {
                    log.debug("PERMISSION_DENIED hook stream 关闭: toolName={} toolUseId={}",
                        toolName, toolUseId);
                }
            });
    }

    /**
     * 把 {@link GenericHook.HookResult} 转 16 字段 {@link AggregatedHookResult}。
     * 缺失字段保持 null/false（对齐 CC 各 yield case union 的 fallback 分支）。
     */
    private AggregatedHookResult toAggregated(GenericHook.HookResult r) {
        if (r == null) {
            return AggregatedHookResult.proceed();
        }
        // [H4] hookUpdatedInput 合并到 updatedInput (CC 无 hookUpdatedInput 顶层字段)
        Map<String, Object> effectiveUpdatedInput = r.updatedInput() instanceof Map
            ? (Map<String, Object>) r.updatedInput() : null;
        // [IMPL-07 OD-14] message 转换边界: AttachmentMessageDto 通道 (旧 instanceof String 截断
        //   附件载荷); String 消息包装为 hook_user_message (PermissionDenied 无工具 hook 名上下文
        //   → hookName/toolUseID null, hookEvent 用事件名, 语义与旧消费端包装一致)
        java.util.List<com.nexusai.application.agent.attachment.AttachmentMessageDto> messageChannel =
            AggregatedHookResult.messageChannel(r.message(), null, null, "PermissionDenied");
        if (r.preventContinuation()) {
            return new AggregatedHookResult(
                messageChannel,
                r.blockingError(),
                true,
                r.stopReason(),
                r.hookPermissionDecisionReason(),
                null,  // hookSource: CC AHR 从 matchingHooks 取, 非 HookResult 顶层
                null,
                // [H-WF5a-02] HookResult.additionalContexts 已是 List<String> → 直接透传 (全保留)
                r.additionalContexts(),
                null,
                effectiveUpdatedInput,
                r.updatedMCPToolOutput(),
                null,
                null,
                null,
                null,
                r.retry()
            );
        }
        // 非 stop: retry 通道优先
        return new AggregatedHookResult(
            messageChannel,
            r.blockingError(),
            false,
            r.stopReason(),
            r.hookPermissionDecisionReason(),
            null,
            null,
            // [H-WF5a-02] HookResult.additionalContexts 已是 List<String> → 直接透传 (全保留)
            r.additionalContexts(),
            null,
            effectiveUpdatedInput,
            r.updatedMCPToolOutput(),
            null,
            null,
            null,
            null,
            r.retry()
        );
    }
}
