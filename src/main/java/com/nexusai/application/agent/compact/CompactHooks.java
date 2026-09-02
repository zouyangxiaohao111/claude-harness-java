package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 压缩 hooks 执行 · 对齐 CC {@code executePreCompactHooks} / {@code executePostCompactHooks}
 * （utils/hooks.ts:3961-4090）+ {@code processSessionStartHooks}（sessionStart.ts，REQ-06）。
 *
 * <p><b>WHY 存在（IMP-04 REQ-06）</b>: CC 压缩单流程执行三组 hooks——
 * PreCompact（压缩前，可返回 newCustomInstructions/userDisplayMessage）、
 * SessionStart（压缩成功后建立新会话上下文，返回 hookMessages）、
 * PostCompact（压缩后，返回 userDisplayMessage）。Java 端经
 * {@link HookRegistry#executeEventAll(HookEvent)} 分发
 * {@link HookEvent#preCompact} / {@link HookEvent#postCompact} /
 * {@link HookEvent#sessionStart} 事件并聚合结果。
 *
 * <p>hookRegistry 未注入（null）→ 全部返回空（不抛错，不阻断压缩）。
 */
public final class CompactHooks {

    private static final Logger log = LoggerFactory.getLogger(CompactHooks.class);

    private CompactHooks() { /* 静态工具类 */ }

    /** PreCompact hook 结果 · CC original: executePreCompactHooks 返回值（hooks.ts:3961-3970）。 */
    public record PreCompactHookResult(String newCustomInstructions, String userDisplayMessage) {}

    /** PostCompact hook 结果 · CC original: executePostCompactHooks 返回值（hooks.ts:4034-4042）。 */
    public record PostCompactHookResult(String userDisplayMessage) {}

    // ════════════════════════════════════════════════════════════════════
    // PreCompact hooks（compact.ts:413-424 / hooks.ts:3961-4030）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 执行 PreCompact hooks · 对齐 CC {@code executePreCompactHooks}
     * （hooks.ts:3961-4030）。
     *
     * <p><b>聚合语义</b>：
     * <ul>
     *   <li>{@code newCustomInstructions} = 成功 hook 的非空输出 join("\n\n")；无则 undefined</li>
     *   <li>{@code userDisplayMessage} = 全部 hook 的展示消息 join("\n")（成功/失败均记）
     *       —— 格式 {@code PreCompact [command] completed successfully[: output]} / failed</li>
     * </ul>
     *
     * @param ctx                 压缩上下文
     * @param trigger             'manual' | 'auto'（compact.ts:415-417）
     * @param customInstructions  用户自定义指令（hookInput custom_instructions）
     * @return 聚合结果（无 hook 或未注入 registry → 空）
     */
    public static PreCompactHookResult executePreCompactHooks(
            CompactConversationContext ctx, String trigger, String customInstructions) {
        HookRegistry registry = ctx.getHookRegistry();
        if (registry == null) {
            return new PreCompactHookResult(null, null);
        }
        // [IMP-A2-2 · OPD-CM5-A-07] 对齐 CC 传 context.abortController.signal（compact.ts:418）：
        //   batchAbort=ctx.getAbortController() → HookRegistry 入口 signal 早退
        //   （executeHooksOutsideREPL hooks.ts:3051-3053 if (signal?.aborted) return []）。
        if (ctx.getAbortController().isCancelled() && log.isDebugEnabled()) {
            log.debug("[CompactHooks] PreCompact hooks: abortController 已取消, 整批跳过 (对齐 CC signal.aborted 早退)");
        }
        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.preCompact(ctx.getSessionId(), trigger, customInstructions),
            ctx.getAbortController());
        if (results == null || results.isEmpty()) {
            return new PreCompactHookResult(null, null);
        }

        List<String> successfulOutputs = new ArrayList<>();
        List<String> displayMessages = new ArrayList<>();
        for (GenericHook.HookResult result : results) {
            boolean succeeded = result.outcome() == GenericHook.HookOutcome.SUCCESS;
            String output = outputOf(result);
            String command = commandOf(result);
            if (succeeded && !output.isEmpty()) {
                successfulOutputs.add(output);
            }
            if (succeeded) {
                displayMessages.add(output.isEmpty()
                    ? "PreCompact [" + command + "] completed successfully"
                    : "PreCompact [" + command + "] completed successfully: " + output);
            } else {
                displayMessages.add(output.isEmpty()
                    ? "PreCompact [" + command + "] failed"
                    : "PreCompact [" + command + "] failed: " + output);
            }
        }
        String newCustomInstructions = successfulOutputs.isEmpty() ? null : String.join("\n\n", successfulOutputs);
        String userDisplayMessage = displayMessages.isEmpty() ? null : String.join("\n", displayMessages);
        if (log.isDebugEnabled() && !results.isEmpty()) {
            log.debug("[CompactHooks] PreCompact hooks: results={} newInstructions={} display={}",
                results.size(), successfulOutputs.size(), userDisplayMessage != null);
        }
        return new PreCompactHookResult(newCustomInstructions, userDisplayMessage);
    }

    // ════════════════════════════════════════════════════════════════════
    // SessionStart hooks（compact.ts:587-594 / sessionStart.ts processSessionStartHooks）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 执行 SessionStart hooks（source='compact'）· 对齐 CC
     * {@code processSessionStartHooks('compact', {model})}（compact.ts:592-594）。
     *
     * <p>成功 hook 的非空输出转换为 {@code ChatMessageDto}（author='hook'）加入
     * {@code hookResults}（CompactionResult.hookResults，compact.ts:742）。
     *
     * @param ctx 压缩上下文
     * @return hook 结果消息列表（无 hook / 无输出 → 空）
     */
    public static List<ChatMessageDto> processSessionStartHooks(CompactConversationContext ctx) {
        HookRegistry registry = ctx.getHookRegistry();
        if (registry == null) {
            return List.of();
        }
        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.sessionStart(ctx.getSessionId(), ctx.getAgentId(), "compact", ctx.getAgentType(), ctx.getModel()));
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<ChatMessageDto> hookMessages = new ArrayList<>();
        // △-5 附加通道（对齐 CC sessionStart.ts:141-156）：additionalContext（单值）+
        // watchPaths 收集；CC 聚合顺序 = 逐 result push message，最后 push
        // hook_additional_context 附件消息（sessionStart.ts:163-172）。
        List<String> additionalContexts = new ArrayList<>();
        List<String> allWatchPaths = new ArrayList<>();
        for (GenericHook.HookResult result : results) {
            if (result.outcome() != GenericHook.HookOutcome.SUCCESS) {
                continue;
            }
            String output = outputOf(result);
            if (!output.isEmpty()) {
                hookMessages.add(new ChatMessageDto(
                    UUID.randomUUID().toString(), ctx.getSessionId(), Role.user, "hook",
                    output, null, List.of(), FinishReason.stop,
                    null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                    List.of(), List.of(), null, false, false));
            }
            // △-5 · CC original: hookResult.additionalContexts（sessionStart.ts:145-149）·
            //   Java HookResult.additionalContexts 为 List<String>（H-WF5a-02 折叠链项2, 全保留）
            if (result.additionalContexts() != null) {
                for (String ac : result.additionalContexts()) {
                    if (ac != null && !ac.isBlank()) {
                        additionalContexts.add(ac);
                    }
                }
            }
            // △-5 · CC original: hookResult.watchPaths（sessionStart.ts:153-155）
            if (result.watchPaths() != null && !result.watchPaths().isEmpty()) {
                for (String p : result.watchPaths()) {
                    if (p != null && !p.isBlank() && !allWatchPaths.contains(p)) {
                        allWatchPaths.add(p);
                    }
                }
            }
        }
        // △-5 · CC original: updateWatchPaths(allWatchPaths)（sessionStart.ts:158-160）→
        //   Java 经 ctx 出口交接线方（生产接 FileChangedWatcher.updateWatchPaths）
        if (!allWatchPaths.isEmpty()) {
            ctx.getSessionStartWatchPathsConsumer().accept(allWatchPaths);
            if (log.isDebugEnabled()) {
                log.debug("[CompactHooks] SessionStart hooks (compact) watchPaths: {} 条动态监听路径",
                    allWatchPaths.size());
            }
        }
        // △-5 · CC original: createAttachmentMessage({type:'hook_additional_context',
        //   content: additionalContexts, hookName:'SessionStart', toolUseID:'SessionStart',
        //   hookEvent:'SessionStart'})（sessionStart.ts:163-172）——追加到 hookMessages 尾部
        if (!additionalContexts.isEmpty()) {
            hookMessages.add(new ChatMessageDto(
                UUID.randomUUID().toString(), ctx.getSessionId(), Role.user, "hook",
                String.join("\n", additionalContexts), null, List.of(), FinishReason.stop,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, false, false,
                null, "hook_additional_context"));
            if (log.isDebugEnabled()) {
                log.debug("[CompactHooks] SessionStart hooks (compact) additionalContext: {} 段（hook_additional_context 追加到 hookMessages 尾部）",
                    additionalContexts.size());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactHooks] SessionStart hooks (compact): results={} hookMessages={}",
                results.size(), hookMessages.size());
        }
        return hookMessages;
    }

    // ════════════════════════════════════════════════════════════════════
    // PostCompact hooks（compact.ts:719-729 / hooks.ts:4034-4090）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 执行 PostCompact hooks · 对齐 CC {@code executePostCompactHooks}
     * （hooks.ts:4034-4090）。
     *
     * <p>聚合语义：{@code userDisplayMessage} = 全部 hook 展示消息 join("\n")，
     * 格式 {@code PostCompact [command] completed successfully[: output]} / failed。
     *
     * @param ctx            压缩上下文
     * @param trigger        'manual' | 'auto'
     * @param compactSummary 压缩摘要（hookInput compact_summary）
     * @return 聚合结果
     */
    public static PostCompactHookResult executePostCompactHooks(
            CompactConversationContext ctx, String trigger, String compactSummary) {
        HookRegistry registry = ctx.getHookRegistry();
        if (registry == null) {
            return new PostCompactHookResult(null);
        }
        // [IMP-A2-2 · OPD-CM5-A-07] 对齐 CC 传 context.abortController.signal（compact.ts:728）：
        //   batchAbort=ctx.getAbortController() → HookRegistry 入口 signal 早退
        //   （executeHooksOutsideREPL hooks.ts:3051-3053 if (signal?.aborted) return []）。
        if (ctx.getAbortController().isCancelled() && log.isDebugEnabled()) {
            log.debug("[CompactHooks] PostCompact hooks: abortController 已取消, 整批跳过 (对齐 CC signal.aborted 早退)");
        }
        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.postCompact(ctx.getSessionId(), trigger, compactSummary),
            ctx.getAbortController());
        if (results == null || results.isEmpty()) {
            return new PostCompactHookResult(null);
        }
        List<String> displayMessages = new ArrayList<>();
        for (GenericHook.HookResult result : results) {
            boolean succeeded = result.outcome() == GenericHook.HookOutcome.SUCCESS;
            String output = outputOf(result);
            String command = commandOf(result);
            if (succeeded) {
                displayMessages.add(output.isEmpty()
                    ? "PostCompact [" + command + "] completed successfully"
                    : "PostCompact [" + command + "] completed successfully: " + output);
            } else {
                displayMessages.add(output.isEmpty()
                    ? "PostCompact [" + command + "] failed"
                    : "PostCompact [" + command + "] failed: " + output);
            }
        }
        String userDisplayMessage = displayMessages.isEmpty() ? null : String.join("\n", displayMessages);
        if (log.isDebugEnabled() && !results.isEmpty()) {
            log.debug("[CompactHooks] PostCompact hooks: results={} display={}", results.size(), userDisplayMessage != null);
        }
        return new PostCompactHookResult(userDisplayMessage);
    }

    // ── 提取小工具 ──

    /** hook 输出文本（HookResult.message，trim；null → 空串）。 */
    private static String outputOf(GenericHook.HookResult result) {
        if (result == null || result.message() == null) {
            return "";
        }
        return result.message().toString().trim();
    }

    /** hook 命令串（CommandHook.command；非 CommandHook / null → '?'）。 */
    private static String commandOf(GenericHook.HookResult result) {
        if (result == null || result.hook() == null) {
            return "?";
        }
        if (result.hook() instanceof CommandHook cmd && cmd.command() != null) {
            return cmd.command();
        }
        return "?";
    }
}
