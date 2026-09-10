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
import org.junit.jupiter.api.BeforeEach;
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
 * HookMatcherEngine:235 SESSION_START → matchQuery=data.source）。
 *
 * <p><b>[C 级 2026-09-07 后端重启副作用补回]</b>：§14 SessionStart 判据 = JVM 冷/热（进程级
 * {@link SessionStartSeenRegistry}，key=streamSessionId，putIfAbsent 成功=cold），非历史空不空。cold
 * （本进程首跑该会话，含后端重启后老会话的首条消息）→ §14 整块执行（executeEvent + 副作用重跑 + V1 注入
 * 去重）；hot（同进程已跑过）→ 整块跳过（对齐 CC 会话开始只发生在 startup/resume/clear/compact 四个进程
 * 边界、同进程内轮不重跑，见 LlmAgentLoop §14 门控注释）。重启后老会话因 DB 有先前历史 → source 推导为
 * 'resume'（对齐 CC conversationRecovery.ts:565 resume 边界「无条件重跑」）；fresh 空会话 → 'startup'。
 * 本测试锁定 C 行为（@AfterEach reset 进程级标记，防同 JVM 测试间污染）：
 * <ol>
 *   <li>旧会话重启后首条消息（DB 有先前历史、进程级标记空=cold）→ §14 执行、捕获 SessionStart source='resume'
 *       （B 级「续聊跳过」语义已反转：不能按历史空不空判，否则丢 watchPaths 等副作用补回）</li>
 *   <li>全新会话首条消息（转录仅当前 in-flight 用户消息）→ source='startup' 触发一次（CC main.tsx:2437）</li>
 *   <li>同进程第二 run（同 session key 已 cold 注册）→ 热 → §14 跳过、不再捕获（对齐 CC 同进程内轮不重跑）</li>
 *   <li>前端 {@code /clear} 命令 → CommandController /clear 分支发射 SessionStart source='clear'
 *       （CC conversation.ts:245）+ 移除该会话进程级 key（下 run 恢复 cold，见 CommandController）</li>
 * </ol>
 *
 * <p><b>RED 条件（C 后）</b>: 若把 §14 门控改回按「历史空不空」续聊判据（B 级 resumedFromDb）→ 重启后老会话
 * 首条消息被当续聊跳过 → 本类用例 1 的 captured 无 SESSION_START → 断言红（副作用补回丢失，即本批要修的
 * 回归）。注：本类 capture registry 只捕获不注入，注入单份断言由 PersistChainTest 覆盖（V1 存在即跳过）。
 */
@DisplayName("[IMP-LL-02] SessionStart source 补 resume/clear（前端触发对齐）")
class LlmAgentLoopSessionStartSourceCcTest {

    /** 生产 sessionId 原始键（"sess-xxx" 格式 · SessionService.generateId 前缀）。 */
    private static final String SESSION_KEY = "sess-ab12cd34";
    private static final String CURRENT_MSG_ID = "msg-current";

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        // [C 级 2026-09-07] 进程级 sessionStartSeen 清空：多用例共享 SESSION_KEY（sess-ab12cd34），
        //   每用例须从 cold 起算（否则前例已注册 → 本例热 → §14 跳过 → 断言误绿/误红）。
        SessionStartSeenRegistry.reset();
    }

    @BeforeEach
    void setUp() {
        // 双保险：与 tearDown 同效（个别早退用例仍隔离）；进程级标记是静态共享，防同类其它用例污染。
        SessionStartSeenRegistry.reset();
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
        // [B 级 2026-09-07] resume 恢复块（doRun）经 listForResumeExcluding(raw, streamUserMessageId) 读派生
        //   历史 —— 不 stub 则 mock 返回 null → resumeHistory null → resumedFromDb 恒 false（误绿，测不到
        //   B 门控）。这里 stub 为「排除当前在途消息后的历史」，驱动 resumedFromDb 真实判定。
        when(ms.listForResumeExcluding(anyList(), anyString())).thenAnswer(inv -> {
            String excludeId = inv.getArgument(1);
            List<ChatMessageDto> resumable = new ArrayList<>();
            for (ChatMessageDto m : transcript) {
                if (m != null && !excludeId.equals(m.id())) {
                    resumable.add(m);
                }
            }
            return resumable;
        });
        return ms;
    }

    private static String sourceOfSessionStart(List<HookEvent> captured) {
        return captured.stream()
            .filter(e -> e.type() == HookEventType.SESSION_START)
            .findFirst()
            .map(e -> String.valueOf(e.data().get("source")))
            .orElse("<no session_start event>");
    }

    // ── 1. 旧会话重启后首条消息（DB 有先前历史、进程级标记空 = cold）→ §14 执行 source='resume'（C 级）──

    @Test
    @DisplayName("旧会话重启后首条消息(C级): 进程级标记空=cold → §14 执行、捕获 SessionStart source='resume'（副作用补回，对齐 CC conversationRecovery.ts resume 边界）")
    void oldSession_firstRunAfterRestart_isCold_firesResume() throws Exception {
        // WHY: C 级判据 = JVM 冷/热，非历史空不空。后端重启 → 进程级 sessionStartSeen 天然清空 →
        //      DB 有先前历史的老会话首条消息仍判 cold → §14 SessionStart 执行（watchPaths 等动态副作用
        //      补回 = 本批核心）。因 DB 有先前历史（除当前 in-flight 外仍有消息）→ source 推导为 'resume'
        //      （对齐 CC conversationRecovery.ts:565 loadConversationForResume 边界 processSessionStartHooks('resume')）。
        //      B 级 resumedFromDb（按历史续聊跳过）语义已反转：照它老会话重启后首条消息会被跳过、丢副作用。
        // RED 条件（回归防线）：若把门控改回「按历史续聊跳过」→ captured 无 SESSION_START → 本断言红。
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
            .as("旧会话重启后首条消息（cold）必须发射 SessionStart（source='resume'，副作用补回；历史非空故非 startup）")
            .isEqualTo("resume");
    }

    // ── startup：全新会话首条消息（转录仅当前 in-flight）→ source='startup' ──

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

    // ── 3. 同进程第二 run（同 session key 已 cold 注册）→ 热 → §14 整段跳过（C 级）──

    @Test
    @DisplayName("同进程第二 run(C级): key 已注册=hot → §14 整段跳过、不再捕获 SessionStart（对齐 CC 同进程内轮不重跑）")
    void secondRun_sameProcess_isHot_skipsSessionStart() throws Exception {
        // WHY: C 级判据 = putIfAbsent：本进程首次 run（cold）注册 SESSION_KEY 后，第二次 run（热）
        //      markSeen 返回 false → §14 整段跳过（不 executeEvent / 不重注入）→ 不再捕获 SESSION_START。
        //      这正是「同进程内每轮不重跑 hook」的锁：模型仍见注入（A 级已落库副本随恢复历史固定重放）。
        // RED 条件（回归防线）：若删除 cold 判据（每次 run 都冷）→ 第二次 run 仍进 §14 → captured2 出现
        //      SESSION_START → 本断言红（每轮重跑回归，同 B 修复前的每轮重注入）。
        List<ChatMessageDto> transcript = List.of(msg(CURRENT_MSG_ID, Role.user, "fresh question"));

        // run1：cold（@BeforeEach reset 保证本方法从空表起算）→ source='startup'
        List<HookEvent> captured1 = new ArrayList<>();
        LlmAgentLoop loop1 = new LlmAgentLoop(captureFactory());
        loop1.setMessageService(messageServiceReturning(transcript));
        setHookRegistry(loop1, capturingRegistry(captured1));
        loop1.setStreamContext(null, SESSION_KEY, CURRENT_MSG_ID);
        AgentState s1 = loop1.run(RunRequest.session("fresh question",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, ProviderConfig.empty(), "test-model", null, null));
        assertThat(s1).isNotNull();
        assertThat(sourceOfSessionStart(captured1))
            .as("run1（cold）必须发射 SessionStart(source='startup')")
            .isEqualTo("startup");

        // run2：同进程、同 SESSION_KEY → hot（run1 已注册）→ §14 跳过
        List<HookEvent> captured2 = new ArrayList<>();
        LlmAgentLoop loop2 = new LlmAgentLoop(captureFactory());
        loop2.setMessageService(messageServiceReturning(transcript));
        setHookRegistry(loop2, capturingRegistry(captured2));
        loop2.setStreamContext(null, SESSION_KEY, CURRENT_MSG_ID);
        AgentState s2 = loop2.run(RunRequest.session("second question",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, ProviderConfig.empty(), "test-model", null, null));

        assertThat(s2).isNotNull();
        assertThat(captured2.stream().filter(e -> e.type() == HookEventType.SESSION_START))
            .as("同进程第二 run（hot）不得再发射 SessionStart（cold 判据 → §14 整段跳过）")
            .isEmpty();
    }

    // ── 4. clear：前端 /clear 命令 → CommandController /clear 分支发射 SessionStart('clear') ──

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

        Object dto = controller.executeBuiltin("clear", null, null);

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

        Object dto = controller.executeBuiltin("clear", null, null);

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
