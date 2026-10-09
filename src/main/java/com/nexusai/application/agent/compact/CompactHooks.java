package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
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
     * <p><b>[压缩回执外显修复] hook attachment 按 CC attachment→message 分派产出</b>
     * （CC 在 sessionStart.ts:141-143 推入 attachment 消息对象，由 attachment→message 转换渲染）：
     * <ul>
     *   <li><b>hook_success · JSON 型</b>（content=''，hooks.ts:729 硬编码；stdout 仅记录载荷 :730）→
     *       转换时 content==='' 返回 []（messages.ts:4106-4108）⇒ 不进模型；UI 亦空渲染
     *       （nullRenderingAttachments.ts:14-16）——两端不可见（真库 39 条
     *       {@code {"hookSpecificOutput":…}} 脏消息即旧实现此路径外显为裸文本，已消除）。</li>
     *   <li><b>hook_success · 非 JSON 型</b>（content=stdout.trim()，hooks.ts:2617-2644）→ 产 isMeta
     *       system-reminder 消息 {@code "{hookName} hook success: {content}"}
     *       （messages.ts:4099-4116：事件门 {SessionStart, UserPromptSubmit} + content 非空）——模型可见、
     *       UI 不可见；渲染与本仓 {@code LlmAgentLoop.appendPlainHookMessage} 同构（就地实现，不复用）。</li>
     *   <li><b>hook_blocking_error</b>（JSON {@code {"decision":"block"}} 路径，HookOutputParser.java:302-311
     *       → :463-465，outcome 恒 SUCCESS :480-482）→ 产 isMeta system-reminder 消息
     *       {@code "{hookName} hook blocking error from command: "{command}": {blockingError}"}
     *       （messages.ts:4090-4097，<b>无事件门</b>；渲染与 AgentLoopContext.renderHookAttachmentForLlm
     *       :3539-3554 同构）——模型可见、UI 不可见。</li>
     * </ul>
     * {@code additionalContexts} 聚合为单条 {@code hook_additional_context} 消息（isMeta=true，
     * sessionStart.ts:163-172），顺序在逐 hook 消息之后。
     *
     * <p><b>isMeta=true 读侧后果</b>（同 §14 先例说理，LlmAgentLoop.java:11583-11590）：
     * {@code lastUserMessageId}（!isMeta 过滤）/ pivot 候选过滤不再被 hook 行劫持（user_message_id
     * 不指向 hook 随机 UUID）、{@code countNonMetaMessages} 计数不虚高、前端按 isMeta 不渲染为气泡；
     * 模型面不受影响（isMeta 绝不用于模型侧过滤）。
     *
     * @param ctx 压缩上下文
     * @return hook 附加消息列表（逐 hook 的 system-reminder 回执 + hook_additional_context 聚合行）
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
        // watchPaths 收集；CC 顺序 = 逐 result 推入 message（hook 回执见下方与方法 JavaDoc），
        // 最后 push hook_additional_context 附件消息（sessionStart.ts:163-172）。
        List<String> additionalContexts = new ArrayList<>();
        List<String> allWatchPaths = new ArrayList<>();
        for (GenericHook.HookResult result : results) {
            if (result.outcome() != GenericHook.HookOutcome.SUCCESS) {
                continue;
            }
            // [压缩回执外显修复] 按 CC attachment→message 分派（messages.ts:4090-4116）：
            //   ① hook_success：content 非空（!isEmpty，严格对齐 CC content === ''；⛔ 不用 isBlank 等更严判据）
            //      且 hookEvent ∈ {SessionStart, UserPromptSubmit}（事件门 messages.ts:4100-4105）→ 产
            //      `{hookName} hook success: {content}`；JSON 型 content=''（hooks.ts:729）→ 门内判空拦截。（事件门依据 att.hookEvent——producer 经 HookRegistry:5059 ccName 填充；plugin 双注册 lambda 副本 hookEvent=null 被门抑制属重复副本，正式副本照产、无消息丢失。）
            //   ② hook_blocking_error：无事件门（messages.ts:4090-4097）→ 产
            //      `{hookName} hook blocking error from command: "{command}": {blockingError}`。
            //      承重：JSON {"decision":"block"}（HookOutputParser.java:302-311 → :463-465）产 blocking
            //      attachment 且 outcome 恒 SUCCESS（:480-482）——⛔ 不带 type 门时会被错套 "hook success" 前缀。
            //   ③ 其它 type / String 等其它形态 message：无 CC attachment 对应物 → 不产（debug 日志仅覆盖非 attachment 形态；attachment 其它 type 静默，可达性≈0，outcome 门先拦）。
            //   等价性承重前提：producer 侧 content 已 trim / 恒空（CommandHookExecutor.java:1699
            //   stdout.trim()；HookOutputParser.java:467-468 content 恒 ""）⇒ !isEmpty 与 CC content === '' 等价；
            //   与 LlmAgentLoop.java:11608 的 isBlank 差异为刻意（对齐 CC 判空）。
            if (result.message() instanceof AttachmentMessageDto att) {
                // hookName 缺省链同 LlmAgentLoop.resolveHookName（attachment.hookName → 事件名
                //   → "Hook"）：绝不产出 "null hook success: " 这类伪造前缀（不伪造、不留空）。
                String hookName = att.hookName() != null && !att.hookName().isBlank()
                    ? att.hookName()
                    : (att.hookEvent() != null && !att.hookEvent().isBlank() ? att.hookEvent() : "Hook");
                if ("hook_success".equals(att.type())
                        && att.content() != null && !att.content().isEmpty()
                        && ("SessionStart".equals(att.hookEvent()) || "UserPromptSubmit".equals(att.hookEvent()))) {
                    hookMessages.add(new ChatMessageDto(
                        UUID.randomUUID().toString(), ctx.getSessionId(), Role.user, "hook",
                        "<system-reminder>\n" + hookName + " hook success: " + att.content()
                            + "\n</system-reminder>",
                        null, List.of(), FinishReason.stop,
                        null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                        List.of(), List.of(), null, true, false));
                } else if ("hook_blocking_error".equals(att.type())) {
                    // 取数：CC blockingError.{command,blockingError} 在 Java 落 att.command() 与
                    //   att.blockingError()（AttachmentMessageDto.hookBlockingError 工厂把 blockingError
                    //   文本同写 content 与 blockingError() 两字段，:583-587；此处兜底取 content）。
                    //   command 缺省 ""（同 AgentLoopContext.renderHookAttachmentForLlm :3554 先例）。
                    String command = att.command() != null ? att.command() : "";
                    String blockingText = att.blockingError() != null ? att.blockingError()
                        : (att.content() != null ? att.content() : "");
                    hookMessages.add(new ChatMessageDto(
                        UUID.randomUUID().toString(), ctx.getSessionId(), Role.user, "hook",
                        "<system-reminder>\n" + hookName + " hook blocking error from command: \""
                            + command + "\": " + blockingText + "\n</system-reminder>",
                        null, List.of(), FinishReason.stop,
                        null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                        List.of(), List.of(), null, true, false));
                }
            } else if (result.message() != null) {
                // [fail-loud 可查] String / 其它形态 message 无 CC hook_success attachment 对应物
                //   （CC 该路径 result.message 恒为 attachment 消息对象，hooks.ts:710-736 / :2628-2643）→ 不产消息。
                if (log.isDebugEnabled()) {
                    log.debug("[CompactHooks] SessionStart hook 结果 message 无 CC attachment 对应物（{}）→ 不产消息",
                        result.message().getClass().getName());
                }
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
        // [SM/compact 对齐 CC] 行形状与 LlmAgentLoop §14（SessionStart cold 注入）完全一致：content 包
        //   <system-reminder>\nSessionStart hook additional context: ...\n</system-reminder> + isMeta=true
        //   （对齐 CC messages.ts:4117-4127 wrapInSystemReminder + createUserMessage({isMeta:true})）。
        //   旧形状（裸拼接 + isMeta=false）会让模型把技能说明当用户贴的内容；且两处形状不一致 →
        //   同一 subtype 两类消息，前端/恢复侧判别分裂。
        if (!additionalContexts.isEmpty()) {
            hookMessages.add(new ChatMessageDto(
                UUID.randomUUID().toString(), ctx.getSessionId(), Role.user, "hook",
                "<system-reminder>\nSessionStart hook additional context: "
                    + String.join("\n", additionalContexts) + "\n</system-reminder>",
                null, List.of(), FinishReason.stop,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, true, false,
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

    /**
     * hook 输出文本 · 对齐 CC {@code HookOutsideReplResult.output}
     * （utils/hooks.ts:3476-3480 {@code result.status === 0 ? result.stdout : result.stderr}）。
     *
     * <p><b>[P1-6 修复] WHY 取 attachment 的 stdout/stderr 而非 {@code message().toString()}</b>：
     * CC 的 {@code output} 是 <b>hook 进程的文本输出</b> —— command hook 成功取 stdout、失败取 stderr
     * （utils/hooks.ts:3476-3480），HTTP hook 取 body（:3390-3400）。CompactHooks 消费点
     * （PreCompact newCustomInstructions / PostCompact userDisplayMessage）在 CC 均派生自该文本；
     * SessionStart 侧回执不经本函数（直接取 attachment.content，见 {@link #processSessionStartHooks}）。
     * Java 的等价文本落在 {@link AttachmentMessageDto} 的
     * {@code stdout}/{@code stderr}（CommandHookExecutor 逐字段对齐 CC 3800 行）；
     * 旧实现取 {@code message().toString()} → 结构化 DTO 的 toString 被当成 hook 输出
     * （DB 实证：{@code content=AttachmentMessageDto[id=8f85a058-…, messageType=attachment, type=hook_success,
     * stdout={"hookSpecificOutput":{…}} 整串进模型上下文，每次 compact 3 条）。
     *
     * <p><b>回落 {@code content}</b>：无 stdout/stderr 的 attachment 两类 ——
     * ① JSON 输出的 hook_success（CC suppressOutput 分支 content 承载，stdout 仅原始 JSON）；
     * ② hook_blocking_error（Java 该工厂不填 stdout/stderr，仅 content=blockingError 文本）。
     *
     * @param result hook 结果（null → 空串）
     * @return hook 输出文本（trim 后；无输出 → 空串）
     */
    private static String outputOf(GenericHook.HookResult result) {
        if (result == null) {
            return "";
        }
        Object message = result.message();
        if (message instanceof AttachmentMessageDto att) {
            String text = result.outcome() == GenericHook.HookOutcome.SUCCESS
                ? att.stdout() : att.stderr();
            if (text == null || text.isEmpty()) {
                text = att.content();
            }
            return text == null ? "" : text.trim();
        }
        if (message instanceof String s) {
            return s.trim();
        }
        if (message != null) {
            // 非 attachment 非 String：无 CC 对应物（CC result.message 恒为消息对象，utils/hooks.ts:338-357）
            //   → fail-loud，绝不把结构化对象 toString 后当 hook 输出（本项修复的根因）。
            if (log.isWarnEnabled()) {
                log.warn("[CompactHooks] hook 结果 message 类型无 CC 对应物（{}）→ 输出按空处理"
                    + "（不再 toString 结构化对象）", message.getClass().getName());
            }
        }
        return "";
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
