package com.nexusai.application.agent;

import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.MatchedHook;
import com.nexusai.apis.command.CommandController;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.command.CommandService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * [IMP-LL-02 · OPD-WF4-LC-03] SessionStart source 补 'resume'/'clear'（前端触发对齐）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: CC {@code processSessionStartHooks} 的 source
 * union = 'startup'|'resume'|'clear'|'compact'（utils/sessionStart.ts:36 + utils/hooks.ts:3868），
 * 且 SESSION_START hook 按 source 匹配（hooks.ts:3887 {@code matchQuery: source}，Java
 * HookMatcherEngine:235 SESSION_START → matchQuery=data.source）。探查发现 Java 仅发射 'startup'
 * （LlmAgentLoop）+ 'compact'（CompactHooks），'resume'/'clear' 无发射点（✗-1/✗-2，EV-WF4-LC-040）——
 * 配置 matcher='resume'/'clear' 的 SessionStart hook 永不触发。本测试锁定修复后行为：
 * <ol>
 *   <li>续聊既有会话（会话已有历史）→ SessionStart source='resume'（CC REPL.tsx:1782）</li>
 *   <li>全新会话首条消息（转录仅当前 in-flight 用户消息）→ SessionStart source='startup'（CC main.tsx:2437）</li>
 *   <li>前端 {@code /clear} 命令 → CommandController /clear 分支发射 SessionStart source='clear'
 *       （CC conversation.ts:245）</li>
 * </ol>
 *
 * <p><b>RED 条件</b>: 修复前 LlmAgentLoop 恒传 "startup"（无 resume 发射），CommandController /clear
 * 分支不发射任何 hook（无 clear 发射）→ 本测试断言全部失败。
 */
@DisplayName("[IMP-LL-02] SessionStart source 补 resume/clear（前端触发对齐）")
class LlmAgentLoopSessionStartSourceCcTest {

    /** 生产 sessionId 原始键（"sess-xxx" 格式 · SessionService.generateId 前缀）。 */
    private static final String SESSION_KEY = "sess-ab12cd34";
    private static final String CURRENT_MSG_ID = "msg-current";

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── 基建：捕获事件的 StubMatcherEngine + HookRegistry ──

    /**
     * 捕获事件的 HookRegistry：HookMatcherEngine 匿名子类 override getMatchingHooks
     * 记录事件并返回空（只捕获，不执行命令 hook）。null 构造参数仅为过构造器；
     * override 方法不触 hooksConfigSnapshot/permissionRuleValueParser。
     */
    private static HookRegistry capturingRegistry(List<HookEvent> captured) {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(
            new com.nexusai.application.agent.permission.hook.HookMatcherEngine(null, null) {
                @Override
                public List<MatchedHook> getMatchingHooks(HookEvent event) {
                    captured.add(event);
                    return java.util.List.of();
                }
            });
        return registry;
    }

    /** mocked provider: 首调返回纯文本 stop → loop 正常退出（对齐 LlmAgentLoopHookMessageInjectionTest）。 */
    private static LlmProviderFactory captureFactory() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private static void setHookRegistry(LlmAgentLoop loop, HookRegistry registry) throws Exception {
        Field f = LlmAgentLoop.class.getDeclaredField("hookRegistry");
        f.setAccessible(true);
        f.set(loop, registry);
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION_KEY, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false, null);
    }

    private static MessageService messageServiceReturning(List<ChatMessageDto> transcript) {
        MessageService ms = mock(MessageService.class);
        when(ms.listBySession(SESSION_KEY)).thenReturn(transcript);
        return ms;
    }

    private static String sourceOfSessionStart(List<HookEvent> captured) {
        return captured.stream()
            .filter(e -> e.type() == HookEventType.SESSION_START)
            .findFirst()
            .map(e -> String.valueOf(e.data().get("source")))
            .orElse("<no session_start event>");
    }

    // ── 1. resume：续聊既有会话（转录含当前 in-flight 之外的消息）→ source='resume' ──

    @Test
    @DisplayName("续聊既有会话: 转录含历史消息 → SessionStart source='resume'（CC REPL.tsx:1782）")
    void resumeSession_priorHistory_sessionStartSourceIsResume() throws Exception {
        // WHY: 继续一个已有历史的会话 = web 端「继续会话」前端触发 → CC REPL.tsx:1782
        //      processSessionStartHooks('resume')。Java 修复前恒传 "startup" → 配置
        //      matcher='resume' 的 SessionStart hook 永不触发（✗-1，EV-WF4-LC-040）。
        List<ChatMessageDto> transcript = new ArrayList<>();
        transcript.add(msg("m-prior-1", Role.user, "earlier question"));
        transcript.add(msg("m-prior-2", Role.assistant, "earlier answer"));
        transcript.add(msg(CURRENT_MSG_ID, Role.user, "current question"));

        List<HookEvent> captured = new ArrayList<>();
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory());
        loop.setMessageService(messageServiceReturning(transcript));
        setHookRegistry(loop, capturingRegistry(captured));
        loop.setStreamContext(null, SESSION_KEY, CURRENT_MSG_ID);

        AgentState state = loop.run(RunRequest.session("current question",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        assertThat(sourceOfSessionStart(captured))
            .as("续聊既有会话必须发射 SessionStart(source='resume')（CC REPL.tsx:1782 processSessionStartHooks('resume')）")
            .isEqualTo("resume");
    }

    // ── 2. startup：全新会话首条消息（转录仅当前 in-flight）→ source='startup' ──

    @Test
    @DisplayName("全新会话首条消息: 转录仅当前 in-flight 用户消息 → SessionStart source='startup'（CC main.tsx:2437）")
    void freshSession_onlyCurrentUserMsg_sessionStartSourceIsStartup() throws Exception {
        // WHY: 全新会话首 run（ChatController.send 第一步 createUserMessage 已持久化当前消息，
        //      P2-23 返工口径：resume=转录存在非当前用户消息）→ CC main.tsx:2437
        //      processSessionStartHooks('startup')。不得把续聊误判为 resume。
        List<ChatMessageDto> transcript = List.of(msg(CURRENT_MSG_ID, Role.user, "fresh question"));

        List<HookEvent> captured = new ArrayList<>();
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory());
        loop.setMessageService(messageServiceReturning(transcript));
        setHookRegistry(loop, capturingRegistry(captured));
        loop.setStreamContext(null, SESSION_KEY, CURRENT_MSG_ID);

        AgentState state = loop.run(RunRequest.session("fresh question",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        assertThat(sourceOfSessionStart(captured))
            .as("全新会话首条消息必须发射 SessionStart(source='startup')（CC main.tsx:2437）")
            .isEqualTo("startup");
    }

    // ── 3. clear：前端 /clear 命令 → CommandController /clear 分支发射 SessionStart('clear') ──

    @Test
    @DisplayName("/clear: CommandController /clear 分支发射 SessionStart source='clear'（CC conversation.ts:245）")
    void clearCommand_firesSessionStartSourceClear() {
        // WHY: web 端「clear 会话」= 前端 POST /api/command/builtins/clear/execute → 本分支。
        //      CC conversation.ts:245 清空会话时点 processSessionStartHooks('clear')，配置
        //      matcher='clear' 的 SessionStart hook 须真实触发（✗-2，EV-WF4-LC-040）。
        List<HookEvent> captured = new ArrayList<>();
        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "commandService", mock(CommandService.class));
        ReflectionTestUtils.setField(controller, "skillRegistry", mock(com.nexusai.application.agent.skill.SkillRegistry.class));
        ReflectionTestUtils.setField(controller, "hookRegistry", capturingRegistry(captured));
        com.nexusai.common.RequestContext.setSession("00000000-0000-0000-0000-00000000000c");

        Object dto = controller.executeBuiltin("clear", null);

        assertThat(dto).isNotNull();
        assertThat(captured.stream().filter(e -> e.type() == HookEventType.SESSION_START)).hasSize(1);
        assertThat(sourceOfSessionStart(captured))
            .as("/clear 必须发射 SessionStart(source='clear')（CC conversation.ts:245 processSessionStartHooks('clear')）")
            .isEqualTo("clear");
        // 载荷对齐 CC createBaseHookInput：sessionId=当前 MDC 会话；agent_type 未传（主线程 null）
        HookEvent clearEvent = captured.stream().filter(e -> e.type() == HookEventType.SESSION_START).findFirst().orElseThrow();
        assertThat(clearEvent.sessionId()).isEqualTo("00000000-0000-0000-0000-00000000000c");
    }

    /**
     * WHY (IMP-E4-06 · E4-XP-W67-01): CC clearConversation（conversation.ts:69）在清空会话时点先发射
     * {@code executeSessionEndHooks('clear', {getAppState...})}，之后 :245 processSessionStartHooks('clear')。
     * Java /clear（CommandController）此前仅发射 SessionStart('clear')（IMP-LL-02），缺少
     * SESSION_END(reason='clear') 发射点（E4-XP-W67-01：Java 是否具备"clear 会话"并应触发 session hooks）。
     * 本测试锁定：/clear 必须发射 SessionEnd 事件，reason 载荷='clear'，且 SESSION_END 先于
     * SESSION_START（CC conversation.ts:69 → :245 顺序）。
     */
    @Test
    @DisplayName("/clear: CommandController /clear 分支先发射 SessionEnd(reason='clear') 再 SessionStart('clear')（CC conversation.ts:69→:245）")
    void clearCommand_firesSessionEndClearThenSessionStartClear() {
        List<HookEvent> captured = new ArrayList<>();
        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "commandService", mock(CommandService.class));
        ReflectionTestUtils.setField(controller, "skillRegistry", mock(com.nexusai.application.agent.skill.SkillRegistry.class));
        ReflectionTestUtils.setField(controller, "hookRegistry", capturingRegistry(captured));
        com.nexusai.common.RequestContext.setSession("00000000-0000-0000-0000-00000000000d");

        Object dto = controller.executeBuiltin("clear", null);

        assertThat(dto).isNotNull();
        // SESSION_END 必须发射且 reason='clear'（CC conversation.ts:69 executeSessionEndHooks('clear')）
        List<HookEvent> endEvents = captured.stream().filter(e -> e.type() == HookEventType.SESSION_END).toList();
        assertThat(endEvents)
            .as("/clear 必须发射 SessionEnd（CC conversation.ts:69 executeSessionEndHooks('clear')）")
            .hasSize(1);
        assertThat(String.valueOf(endEvents.get(0).data().get("reason")))
            .as("SessionEnd reason 必须为 'clear'（CC coreSchemas.ts:748 EXIT_REASONS 'clear'）")
            .isEqualTo("clear");
        // 顺序：SESSION_END 先于 SESSION_START（CC conversation.ts:69 → :245）
        int endIdx = captured.indexOf(endEvents.get(0));
        int startIdx = captured.stream().filter(e -> e.type() == HookEventType.SESSION_START).findFirst()
            .map(captured::indexOf).orElse(-1);
        assertThat(endIdx)
            .as("SessionEnd 必须先于 SessionStart 发射（CC conversation.ts:69 → :245）")
            .isLessThan(startIdx);
    }
}
