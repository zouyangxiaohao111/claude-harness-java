package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [Session H4] 全量对齐 CC {@code AggregatedHookResult} 真源
 * {@code Open-ClaudeCode/src/utils/hooks.ts:359-376} (grep 自验 2026-07-30).
 *
 * <p><b>CC 真源 (utils/hooks.ts:359-376) 16 字段</b>:
 * <pre>
 * export type AggregatedHookResult = {
 *   message?: HookResultMessage
 *   blockingError?: HookBlockingError
 *   preventContinuation?: boolean
 *   stopReason?: string
 *   hookPermissionDecisionReason?: string
 *   hookSource?: string
 *   permissionBehavior?: PermissionResult['behavior']
 *   additionalContexts?: string[]
 *   initialUserMessage?: string
 *   updatedInput?: Record<string, unknown>
 *   updatedMCPToolOutput?: unknown
 *   permissionRequestResult?: PermissionRequestResult
 *   watchPaths?: string[]
 *   elicitationResponse?: ElicitationResponse
 *   elicitationResultResponse?: ElicitationResponse
 *   retry?: boolean
 * }
 * </pre>
 *
 * <p><b>[Session H4] 字段重构 (破约, 不留兼容壳)</b>:
 * <ul>
 *   <li>{@code blockingError} 类型 String → {@link HookBlockingError} (对齐 CC utils/hooks.ts:361)</li>
 *   <li>{@code permissionRequestResult} 类型 Object → {@link PermissionRequestResult} sealed
 *       (对齐 CC utils/hooks.ts:373, types/hooks.ts:248-258)</li>
 *   <li>移除非 CC 字段 {@code additionalContext} (单值, CC AHR 无此字段, 仅有 additionalContexts 列表)</li>
 *   <li>移除非 CC 字段 {@code aggregatedAt} (Java 端扩展时间戳, CC 无此字段)</li>
 *   <li>[DEL-WF1-TY-02 v4 实施] 移除非 CC 字段 {@code systemMessages} — CC AHR (utils/hooks.ts:359-376)
 *       无此字段; CC 逐结果 yield systemMessage → hook_system_message attachment (hooks.ts:2769-2780).
 *       Java 按 CC 语义就地折叠为 {@code hook_system_message} AttachmentMessageDto 并入
 *       {@code message} 通道 (见 {@link #foldSystemMessages}), 消费端
 *       StreamingToolExecutor.injectPreToolUseHookAttachments 逐条 appendAttachment, 不再承载
 *       独立聚合字段.</li>
 *   <li>保留 {@code hookSource} (CC utils/hooks.ts:361 有此字段, 注意 types/hooks.ts:277-290 无,
 *       按 Pattern #7 择 utils/hooks.ts 实际实现版)</li>
 *   <li>保留 {@code watchPaths} / {@code elicitationResponse} / {@code elicitationResultResponse}
 *       (CC utils/hooks.ts:374-375 有); [I-2 拍板] 后两者类型 Object → {@link ElicitationResponse}
 *       (对齐 CC utils/hooks.ts:335-336 ElicitResult re-export)</li>
 * </ul>
 *
 * <p><b>[IMPL-07 D3-3 + OD-14] message 类型通道: String → List&lt;AttachmentMessageDto&gt;</b>:
 * <ul>
 *   <li><b>OD-14 (EV-011/Q-04)</b>: CC {@code message?: HookResultMessage} 是消息对象
 *       (AttachmentMessage 语义, 含 stdout/stderr/exitCode/command/durationMs 载荷);
 *       Java 旧 {@code String message} 在 AHR 转换边界 {@code instanceof String} 截断 —
 *       AttachmentMessageDto 附件载荷被丢弃. 现统一 AttachmentMessageDto 通道, 载荷不截断.</li>
 *   <li><b>D3-3 (EV-003/019)</b>: CC executeHooks 逐结果 yield message (hooks.ts:2765-2767),
 *       消费端 resultingMessages.push 保留全部 (toolExecution.ts:815-829) — Java 聚合 N 结果
 *       → List 全保留, 第 2..N 个 hook 的 message 不丢失 (旧 first-non-null 只留第 1 个).</li>
 *   <li>String 消息 (旧通道/测试) 经 {@link #messageChannel(Object, String, String, String)}
 *       包装为 hook_user_message AttachmentMessageDto, 语义与旧消费端包装一致.</li>
 * </ul>
 *
 * <p>字段集从 18 字段缩减为 16 字段 (移除 additionalContext + aggregatedAt);
 * message 字段类型 String → List&lt;AttachmentMessageDto&gt; (字段名与 CC 一致, 类型为 Java
 * 聚合表达 — CC 无聚合 record, 聚合发生在消费端 resultingMessages 数组).
 *
 * @since P0-3, Session H4 重构; IMPL-07 通道改造
 */
public record AggregatedHookResult(
        List<AttachmentMessageDto> message,
        HookBlockingError blockingError,
        boolean preventContinuation,
        String stopReason,
        String hookPermissionDecisionReason,
        String hookSource,
        PermissionResult permissionBehavior,
        List<String> additionalContexts,
        String initialUserMessage,
        Map<String, Object> updatedInput,
        Object updatedMCPToolOutput,
        PermissionRequestResult permissionRequestResult,
        List<String> watchPaths,
        ElicitationResponse elicitationResponse,
        ElicitationResponse elicitationResultResponse,
        Boolean retry
) {

    /**
     * Compact canonical constructor · 保证 List/Map 不可变 + null-safe.
     */
    public AggregatedHookResult {
        additionalContexts = additionalContexts == null ? null : List.copyOf(additionalContexts);
        watchPaths = watchPaths == null ? null : List.copyOf(watchPaths);
        if (message != null) {
            message = List.copyOf(message);
        }
        if (updatedInput != null) {
            updatedInput = Map.copyOf(updatedInput);
        }
    }

    /**
     * [IMPL-07 OD-14] 消息对象 → AttachmentMessageDto 通道 · 对齐 CC
     * {@code message?: HookResultMessage} (hooks.ts:360) 消息对象语义.
     *
     * <p>WHY: AHR.message 统一 AttachmentMessageDto 通道 (OD-14 ADJUDICATED) — 旧边界
     * {@code instanceof String} 截断附件载荷 (stdout/stderr/exitCode/command/durationMs 丢弃).
     * 本 helper 供 HookResult → AHR 转换边界使用:
     * <ul>
     *   <li>{@link AttachmentMessageDto} → 单元素 List (原样透传, 载荷不截断)</li>
     *   <li>{@link String} → 包装为 hook_user_message AttachmentMessageDto (旧 String 通道语义,
     *       与旧消费端 StreamingToolExecutor 包装行为一致; hookName/toolUseID/hookEvent 由
     *       调用方上下文提供, 无上下文传 null)</li>
     *   <li>其他/null → null (无消息)</li>
     * </ul>
     *
     * @param message   HookResult.message (Object: AttachmentMessageDto | String | null)
     * @param hookName  attachment.hookName (e.g. "PreToolUse:Bash"; String 包装时用; 可 null)
     * @param toolUseID attachment.toolUseID (String 包装时用; 可 null)
     * @param hookEvent attachment.hookEvent (String 包装时用; 可 null)
     * @return List&lt;AttachmentMessageDto&gt;; 无消息 → null
     */
    public static List<AttachmentMessageDto> messageChannel(Object message,
                                                            String hookName, String toolUseID,
                                                            String hookEvent) {
        if (message instanceof AttachmentMessageDto att) {
            return List.of(att);
        }
        if (message instanceof String s) {
            return List.of(AttachmentMessageDto.hookUserMessage(hookName, toolUseID, hookEvent, s));
        }
        return null;
    }

    /**
     * [DEL-WF1-TY-02 v4 实施] 逐条折叠 HookResult.systemMessages → hook_system_message attachment ·
     * 对齐 CC {@code executeHooks} 逐结果 yield systemMessage → hook_system_message attachment
     * (Open-ClaudeCode/src/utils/hooks.ts:2769-2780; N 结果 → N 附件, 旧 first-non-null 只留第 1 条).
     *
     * <p>WHY: CC AggregatedHookResult (utils/hooks.ts:359-376) 无 systemMessages 聚合字段 —
     * systemMessage 按 CC 语义就地转为 {@code hook_system_message} AttachmentMessageDto 并入
     * {@code message} 通道 (消息通道单一出口), 由消费端
     * StreamingToolExecutor.injectPreToolUseHookAttachments 逐条 appendAttachment (N 条 → N 附件).
     * 顺序对齐 CC 逐结果 yield: message 先行, 随后该结果的 N 条 systemMessage (hooks.ts:2765-2780).
     *
     * @param message         现有 message 通道 (HookResult.message 转换后; 可 null)
     * @param systemMessages  HookResult.systemMessages (逐条折叠; 可 null/空 → 原样返回 message)
     * @param hookName        attachment.hookName (e.g. "PreToolUse:Bash"; 可 null)
     * @param toolUseID       attachment.toolUseID (可 null)
     * @param hookEvent       attachment.hookEvent (e.g. "PreToolUse"; 可 null)
     * @return 折叠后 message 列表 (message + N 条 hook_system_message); 全空 → null
     */
    public static List<AttachmentMessageDto> foldSystemMessages(List<AttachmentMessageDto> message,
                                                                List<String> systemMessages,
                                                                String hookName, String toolUseID,
                                                                String hookEvent) {
        if (systemMessages == null || systemMessages.isEmpty()) {
            return message;
        }
        List<AttachmentMessageDto> out = new ArrayList<>();
        if (message != null) {
            out.addAll(message);
        }
        for (String sm : systemMessages) {
            if (sm != null && !sm.isBlank()) {
                out.add(AttachmentMessageDto.hookSystemMessage(hookName, toolUseID, hookEvent, sm));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 返回"继续执行 / 无干预"结果.
     */
    public static AggregatedHookResult proceed() {
        return new AggregatedHookResult(
            null, null, false, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null
        );
    }

    /**
     * 判断 outcome 是否完全无干预 (等价于 {@link #proceed()}).
     */
    public boolean isProceed() {
        return message == null
            && blockingError == null
            && !preventContinuation
            && stopReason == null
            && hookPermissionDecisionReason == null
            && hookSource == null
            && permissionBehavior == null
            && additionalContexts == null
            && initialUserMessage == null
            && updatedInput == null
            && updatedMCPToolOutput == null
            && permissionRequestResult == null
            && watchPaths == null
            && elicitationResponse == null
            && elicitationResultResponse == null
            && retry == null;
    }

    /**
     * 跨阶段收敛字段 · updatedInput (CC 全替换语义).
     *
     * <p>WHY 暴露单字段 accessor: 16 字段 AHR 中, input 替换语义只有 updatedInput 一条通道
     * (CC toolHooks.ts:556-563), 不存在双通道需要收敛.
     */
    public Map<String, Object> effectiveInput() {
        return updatedInput;
    }
}
