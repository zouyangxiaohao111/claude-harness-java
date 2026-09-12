package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [2026-09-12 用户拍板 · appendPlainHookMessage 对齐 CC] 非工具事件的"普通文本 hook message"
 * → {@code <system-reminder>} 包裹 + {@code {hookName} hook success: } 前缀 + {@code isMeta=true}
 * + hookEvent 白名单门.
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：{@code LlmAgentLoop.appendPlainHookMessage}
 * 是 CC {@code sessionStart.ts:141-142}（{@code hookMessages.push(hookResult.message)} →
 * initialMessages；{@code processSetupHooks} sessionStart.ts:203-205 同）/ {@code toolHooks.ts:478-480}
 * 那条「hook 的 message 进 transcript」在本仓的落点。该 message 在 CC 里最终由
 * {@code normalizeMessagesForAPI} 的 {@code case 'attachment'}（messages.ts:2591-2609）交给
 * {@code normalizeAttachmentForAPI} 渲染，{@code hook_success} 分支（messages.ts:4539-4556）为：
 * <pre>
 * case 'hook_success':
 *   if (attachment.hookEvent !== 'SessionStart' &amp;&amp; attachment.hookEvent !== 'UserPromptSubmit') return []  // ① 事件门
 *   if (attachment.content === '') return []                                                                  // ② 空内容门
 *   return [createUserMessage({
 *     content: wrapInSystemReminder(`${attachment.hookName} hook success: ${attachment.content}`),           // ③ 包裹 + 前缀
 *     isMeta: true,                                                                                           // ④ 元消息
 *   })]
 * </pre>
 * {@code wrapInSystemReminder}（messages.ts:3488-3490）= {@code `<system-reminder>\n${content}\n</system-reminder>`}。
 *
 * <p><b>修前（RED）</b>：{@code toMessage(Role.user, content, null)} 第 3 参无 isMeta
 * → {@code isMeta=false} + 裸文本无包裹无前缀 —— 四项全错。
 *
 * <p><b>为什么 isMeta=true 是「对齐 CC」而非回归</b>：CC 侧该消息是元消息，{@code Messages.tsx:176}
 * 对 {@code type==='user'} 的 meta 消息不渲染为用户气泡（用户看不见、模型看得见）。本仓前端
 * （{@code MessageList.tsx} / {@code TraceView.tsx} 的 {@code !isMeta} 过滤）同语义 → 用户气泡消失即
 * 对齐。同时修正两处<b>用户可见</b>的读侧后果（与 2026-09-12 {@code TaskCompleted}/{@code TeammateIdle}
 * 回注 isMeta 修复同批同类）：
 * <ul>
 *   <li>{@link AgentState#lastUserMessageId()}（{@code !m.isMeta()} 过滤）不再把本 hook 文本当
 *       "最后一条 user 消息" → 事件/落库 {@code user_message_id} 归属不再指向 hook 的随机 UUID；</li>
 *   <li>{@code MessageService.countNonMetaMessages}（{@code WHERE is_meta IS NULL OR is_meta != 1}）
 *       不再计入 → 轨迹条数徽标不虚高。</li>
 * </ul>
 * <b>模型面不受影响</b>：isMeta 绝不用于模型侧过滤（CC 同），本消息仍在 {@code state.rawMessages()} 内
 * 参与 {@code messagesForLlm}。
 *
 * @since 2026-09-12（appendPlainHookMessage 对齐 CC）
 */
@DisplayName("[appendPlainHookMessage 对齐 CC] 普通文本 hook message → system-reminder 包裹 + 前缀 + isMeta=true + 事件门")
class LlmAgentLoopPlainHookMessageCcAlignTest {

    /** 反射注入 hookRegistry（@Autowired(required=false), 单测手动接线）. */
    private static void setHookRegistry(LlmAgentLoop loop, HookRegistry registry) throws Exception {
        Field f = LlmAgentLoop.class.getDeclaredField("hookRegistry");
        f.setAccessible(true);
        f.set(loop, registry);
    }

    /**
     * 桩 HookRegistry：仅目标 CC 事件名返回 {@code message}，其余事件 proceed。
     *
     * <p>同时 override 1 参（SessionStart / Setup —— {@code executeSetupHooks} 走 1 参）与
     * 4 参（UserPromptSubmit —— doRun 显式传 parentTuc + promptRequester）两个入口；
     * 2/3 参重载生产未用。
     *
     * @param targetCcEvent 目标事件 CC 名（{@code HookEventType.ccName()}，如 "SessionStart"）
     * @param message       HookResult.message 载荷（String → 走"普通文本"分支；
     *                      AttachmentMessageDto → 走 AttachmentMessageDto 分支）
     */
    private static HookRegistry registryReturningMessage(String targetCcEvent, Object message) {
        return registryReturningMessage(targetCcEvent, message, null);
    }

    private static HookRegistry registryReturningMessage(String targetCcEvent, Object message,
                                                         java.util.concurrent.atomic.AtomicInteger hits) {
        return new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                return respond(event);
            }

            @Override
            public GenericHook.HookResult executeEvent(HookEvent event, List<ChatMessageDto> messages,
                                                       ToolUseContext parentTuc,
                                                       PromptRequester promptRequester) {
                return respond(event);
            }

            private GenericHook.HookResult respond(HookEvent event) {
                if (event == null || event.type() == null
                        || !targetCcEvent.equals(event.type().ccName())) {
                    return GenericHook.HookResult.proceed();
                }
                if (hits != null) {
                    hits.incrementAndGet();
                }
                return new GenericHook.HookResult(
                    false, null, null, null,
                    message,
                    null, null, null, null,
                    GenericHook.HookOutcome.SUCCESS,
                    null, null, null, null, null, null, null, null);
            }
        };
    }

    /** mocked provider: 捕获首轮 history（arg 3）到 holder + 首调返回纯文本 stop → loop 正常退出. */
    private static LlmProviderFactory captureFactory(AtomicReference<List<ChatMessageDto>> holder) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            holder.set(inv.getArgument(3));
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("answer from model");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("answer from model", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private static AgentState runWithHookMessage(String targetCcEvent, Object message) throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(new AtomicReference<>()));
        setHookRegistry(loop, registryReturningMessage(targetCcEvent, message));
        return loop.run(RunRequest.forTest("hello", "test-model", null));
    }

    private static AgentState runWithHookMessage(String targetCcEvent, Object message,
                                                 java.util.concurrent.atomic.AtomicInteger hits) throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(new AtomicReference<>()));
        setHookRegistry(loop, registryReturningMessage(targetCcEvent, message, hits));
        return loop.run(RunRequest.forTest("hello", "test-model", null));
    }

    /**
     * 会话绑定 run（UserPromptSubmit 块专用）。
     *
     * <p><b>WHY</b>：doRun 的 UserPromptSubmit 段用
     * {@code ToolUseContext.of(agentId, sessionId, ...)} 构造父 TUC，而该工厂
     * <b>要求 sessionId 非空</b>（否则抛 {@code ToolUseContext.sessionId is required}，
     * 被 {@code catch (Exception) → log.warn("HOOK UserPromptSubmit failed")} 吞掉）——
     * 故 {@code RunRequest.forTest}(sessionId=null) 走不到 UserPromptSubmit hook 发射。
     * 本 helper 用 {@code RunRequest.session} 携带真实 sessionId。
     */
    private static AgentState runSessionBound(String targetCcEvent, Object message,
                                              java.util.concurrent.atomic.AtomicInteger hits) throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(new AtomicReference<>()));
        loop.setStreamContext(null, SESSION_KEY, "user-1");
        setHookRegistry(loop, registryReturningMessage(targetCcEvent, message, hits));
        return loop.run(RunRequest.session("hello", SESSION_KEY, null,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));
    }

    /** 会话 id（RunRequest.session 必填）. */
    private static final String SESSION_KEY = "sess-plain-hook-msg";

    /** 仅取 role=user 且 content 含 "hook success:" 的消息（被测注入消息唯一标识）. */
    private static ChatMessageDto hookInjected(AgentState state) {
        return state.rawMessages().stream()
            .filter(m -> m.role() == Role.user && m.content() != null && m.content().contains("hook success:"))
            .findFirst()
            .orElse(null);
    }

    /** 非 meta 的 user 消息（镜像 MessageService.countNonMetaMessages 的 is_meta 口径）. */
    private static List<ChatMessageDto> nonMetaUsers(AgentState state) {
        return state.rawMessages().stream()
            .filter(m -> m.role() == Role.user && !m.isMeta())
            .toList();
    }

    @Test
    @DisplayName("① ② ③ ④ SessionStart 普通文本 hook message → <system-reminder>\\nSessionStart hook success: X\\n</system-reminder> + isMeta=true")
    void sessionStartPlainMessage_renderedAsMetaSystemReminder() throws Exception {
        // WHY: CC hook_success 渲染四项（事件门 ① / 空内容门 ② / wrapInSystemReminder + 前缀 ③ / isMeta ④）
        //      逐字节锁定；修前是裸文本 + isMeta=false，本断言必红。
        AgentState state = runWithHookMessage("SessionStart", "hook message 1");

        ChatMessageDto injected = hookInjected(state);
        assertThat(injected)
            .as("SessionStart 普通 hook message 必须注入（① 白名单内）")
            .isNotNull();
        assertThat(injected.content())
            .as("③ CC wrapInSystemReminder = `<system-reminder>\\n${content}\\n</system-reminder>`（前后各有换行）"
                + " + `${attachment.hookName} hook success: ${attachment.content}` 前缀")
            .isEqualTo("<system-reminder>\nSessionStart hook success: hook message 1\n</system-reminder>");
        assertThat(injected.isMeta())
            .as("④ CC messages.ts:4554 isMeta:true —— 元消息（用户不可见气泡、模型可见）")
            .isTrue();
    }

    @Test
    @DisplayName("③⑧ UserPromptSubmit 普通文本 hook message 同形渲染（第二条白名单事件）")
    void userPromptSubmitPlainMessage_renderedSameShape() throws Exception {
        // WHY: 白名单是 {SessionStart, UserPromptSubmit} 两个（CC messages.ts:4541-4542），
        //      只测 SessionStart 会漏掉 UserPromptSubmit 分支（它走 4 参 executeEvent 重载，独立接线）。
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        AgentState state = runSessionBound("UserPromptSubmit", "prompt hook msg", hits);

        ChatMessageDto injected = hookInjected(state);
        // 前置断言（防假绿）：UserPromptSubmit 段用 ToolUseContext.of(...) 构造父 TUC，
        // sessionId=null 时抛异常被 catch 吞掉 → 事件根本到不了桩。故先证明链路真跑通。
        assertThat(hits.get())
            .as("前置：UserPromptSubmit 事件必须真的到达 executeEvent 桩（否则下面的断言是假绿）")
            .isGreaterThan(0);
        assertThat(injected).isNotNull();
        assertThat(injected.content())
            .isEqualTo("<system-reminder>\nUserPromptSubmit hook success: prompt hook msg\n</system-reminder>");
        assertThat(injected.isMeta()).isTrue();
        // hookName 缺省 = 事件名（CC hooks.ts:2123 无 matchQuery 时 `hookName = hookEvent`），不得为 "null"
        assertThat(injected.content()).doesNotContain("null hook success");
    }

    @Test
    @DisplayName("① hookEvent = Setup → 不注入（CC 白名单只有 SessionStart/UserPromptSubmit）")
    void nonWhitelistedEvent_setup_notInjected() throws Exception {
        // WHY（行为收窄，显式锁定）: 本 Java 通道覆盖 Setup/StopFailure（比 CC 白名单宽），
        //      CC 同位置的 hook_success 附件被 messages.ts:4540-4545 挡掉 →
        //      CC processSetupHooks（sessionStart.ts:203-205）虽 push message 但渲染为 []。
        //      按 CC 收窄后 Setup 的普通 hook 文本不得进 LLM 上下文。
        //      【可达性已证】把门改成 `if (false)` 的单点变异会让本断言变红（说明该路径真的走到了
        //      本方法，本断言不是假绿）。
        assertThat(hookInjected(runWithHookMessage("Setup", "setup hook msg")))
            .as("Setup 事件在 CC hook_success 白名单外 → 不注入")
            .isNull();
        // StopFailure 不在此断言：其唯一调用点在 max_tokens 恢复耗尽分支（需 isApiErrorMessage
        // + hasHookForEvent("StopFailure")），本单元 harness 不可达 → 断言会恒绿（假绿）。
        // 该事件由同一个 isHookSuccessRenderableEvent 判据覆盖（白名单是单一布尔方法），
        // 故不另设不可达断言。
    }

    @Test
    @DisplayName("② content 为空/空白 → 不注入（CC messages.ts:4546-4548 content === '' return []，保留既有语义）")
    void emptyContent_notInjected() throws Exception {
        assertThat(hookInjected(runWithHookMessage("SessionStart", "")))
            .as("空串不注入（CC content === '' → []）").isNull();
        assertThat(hookInjected(runWithHookMessage("SessionStart", "   ")))
            .as("纯空白不注入（保留既有 isBlank 抑制）").isNull();
    }

    @Test
    @DisplayName("读侧后果① 本消息不被 AgentState.lastUserMessageId() 选中（归属不劫持到 hook 随机 UUID）")
    void hookMessage_notSelectedAsLastUserMessage() throws Exception {
        // WHY（用户可见后果）: isMeta=false 时本消息是「最后一条非 meta user」——
        //      lastUserMessageId()（AgentState:428 `!m.isMeta()` 过滤）会选中它的随机 UUID
        //      → 事件/落库 user_message_id 归属指向 hook 消息而非真实用户输入。CC 侧同位置是
        //      isMeta:true，故必须与 CC 一致地"不被选中"。
        AgentState state = runWithHookMessage("SessionStart", "hook message 1");

        ChatMessageDto injected = hookInjected(state);
        assertThat(injected).isNotNull();
        String realUserMessageId = state.rawMessages().stream()
            .filter(m -> m.role() == Role.user && !m.isMeta())
            .map(ChatMessageDto::id)
            .reduce((a, b) -> b)   // 最后一条非 meta user = 真实用户输入 "hello"
            .orElseThrow();

        assertThat(state.lastUserMessageId())
            .as("归属必须落在真实用户消息上")
            .isEqualTo(realUserMessageId);
        assertThat(state.lastUserMessageId())
            .as("归属不得指向 hook 注入消息的随机 UUID")
            .isNotEqualTo(injected.id());
    }

    @Test
    @DisplayName("读侧后果② 本消息不计入非 meta user 口径（轨迹条数不虚高）")
    void hookMessage_notCountedAsNonMeta() throws Exception {
        // WHY: MessageService.countNonMetaMessages 的 WHERE is_meta IS NULL OR is_meta != 1
        //      决定轨迹徽标条数；isMeta=false 会让每次 hook 注入都虚增 1 条。此处用同口径
        //      的内存谓词锁定（本 run 非 meta user 恰好只有真实输入 "hello" 一条）。
        AgentState state = runWithHookMessage("SessionStart", "hook message 1");

        assertThat(nonMetaUsers(state))
            .as("非 meta user 只应有真实用户输入，hook 注入不得计入")
            .extracting(ChatMessageDto::content)
            .containsExactly("hello");
    }

    @Test
    @DisplayName("hookName 优先级：attachment 自带 hookName 覆盖事件名（CC attachment.hookName 同源）")
    void attachmentHookName_takesPrecedence() throws Exception {
        // WHY: 配置驱动 hook 的 hookName（如 "PreToolUse:Bash" / "config-command:check.sh"）由
        //      HookRegistry 计算并与 CC attachment.hookName 同源；本通道的 String 消息不携带
        //      hookName 才回落到事件名（resolveHookName）。两条来源都不得产出 null/空前缀。
        AgentState state = runSessionBound("UserPromptSubmit",
            AttachmentMessageDto.hookUserMessage("PreToolUse:Bash", "toolu_1", "UserPromptSubmit", "hi from hook"),
            null);

        ChatMessageDto injected = hookInjected(state);
        assertThat(injected).isNotNull();
        assertThat(injected.content())
            .as("attachment 自带 hookName 优先于事件名")
            .isEqualTo("<system-reminder>\nPreToolUse:Bash hook success: hi from hook\n</system-reminder>");
        assertThat(injected.isMeta()).isTrue();
    }

    @Test
    @DisplayName("模型面不变：注入消息仍在 state.rawMessages() 内（isMeta 绝不用于模型侧过滤，CC 同）")
    void injectedMessage_stillVisibleToModel() throws Exception {
        // WHY: isMeta=true 只影响 UI 与读侧归属/计数口径，绝不从 messagesForLlm 剔除 ——
        //      CC 的 meta user 消息同样进 API 请求（否则 hook 反馈到不了模型，本通道即失效）。
        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(history));
        setHookRegistry(loop, registryReturningMessage("SessionStart", "hook message 1"));

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(hookInjected(state)).as("消息确实落在 state.rawMessages()（transcript/模型面同源）").isNotNull();
        assertThat(history.get())
            .as("LLM 请求 history 必须含该 hook 文本（模型可见）")
            .anySatisfy(m -> assertThat(m.content()).contains("SessionStart hook success: hook message 1"));
    }
}
