package com.nexusai.application.agent;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.chat.ChatService;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * [session-start-cc-align C 级] hook_additional_context 单份链：cold 注入/落库 → hot/resume 固定重放 → 不重复。
 *
 * <p><b>根因回归锁（规则九 · 验证意图）</b>：注入消息必须落库（P0-1）+ 单份固定；不得每 run 在尾部重塞。
 *
 * <p><b>[C 级 2026-09-07 后端重启副作用补回] 机制演进（替换 B resumedFromDb）</b>：§14 SessionStart 判据 =
 * JVM 冷/热（进程级 {@link SessionStartSeenRegistry}，key=streamSessionId，{@code putIfAbsent==null}=cold）。
 * <ul>
 *   <li><b>cold</b>（本进程首跑该会话，含后端重启后老会话的首条消息）→ §14 整块执行：hook 副作用重跑
 *       （watchPaths 等）+ <b>覆盖式注入</b>（DB deleteBySessionAndSubtype 删旧 + state.removeMessagesBySubtype
 *       清内存旧份 + append 新份并落库）→ 恒 1 份，且 hook 输出变更能被刷新</li>
 *   <li><b>hot</b>（同进程已 cold 跑过）→ §14 整段跳过：不 executeEvent / 不重注入；模型仍见注入——A 级
 *       (P0-1) 已把 cold 首轮的 hook 落库，恢复历史含其单一固定副本（跨 run 字节稳定）</li>
 *   <li>/clear（CommandController）remove 该会话 key → 下 run 恢复 cold（副作用重跑 + 覆盖式刷新，恒 1 份）</li>
 * </ul>
 *
 * <p><b>与 CC 语义映射</b>：CC 默认不落盘 hook_additional_context（sessionStorage.ts isLoggableMessage 过滤，
 * 仅 CLAUDE_CODE_SAVE_HOOK_ADDITIONAL_CONTEXT 开时放行）；resume 无条件重跑并 push
 * （conversationRecovery.ts:565-568，与保存开关无关）→ 开关开时 resume 每次堆副本。nexusai = 等效「开关开」
 * 架构（A 级已落库）→ cold 边界 + <b>覆盖式写（删旧插新）</b>双闸 = 触发时机对齐 CC resume 边界、
 * 同时规避 CC-with-flag 的副本累积（DB/内存恒 1 条）。
 *
 * <p><b>链式断言</b>（本文件多处复用同一 harness：真实 LlmAgentLoop.run + InMemoryMessageService +
 * 真实 ChatService 实时落库 listener）：
 * <ol>
 *   <li>fresh（DB 空 / 无 hook 副本）cold → §14 注入 1 份并经 ChatService.appendMessage 落库</li>
 *   <li>同进程第二 run（key 已 cold 注册 = hot）→ §14 整段跳过 → executeEvent 零调用、请求仍恰 1 份
 *       （id == 已落库 id、位于 user1-asst1 间固定位置）</li>
 *   <li>重启模拟（reset 清空进程级标记）旧会话已有落库副本 → cold → executeEvent 被调（副作用重跑）+
 *       覆盖式删旧插新 → 请求/DB 恒 1 份且为<b>新</b>注入（新需求核心，防重启后副本累积）</li>
 *   <li>重启模拟旧会话无副本 → cold → executeEvent 被调 + 注入 1 份并落库（老会话补注入）</li>
 *   <li>/clear 移除 key → 下 run 恢复 cold（executeEvent 重跑）但 DB/请求恒 1 份（clear 不删 DB 行、转录
 *       保留 → 覆盖式写；0 或 1 份绝无 2 份，见 CommandController C 级 remove + B-4 复核）</li>
 * </ol>
 *
 * <p><b>RED 条件（C 后）</b>：
 * <ul>
 *   <li>删 cold 判据（每次 run 都进 §14）→ 同进程第二 run executeEvent 被调 → hot 用例 calls 断言红
 *       （每轮重跑回归）</li>
 *   <li>删「删旧」任一半（DB deleteBySessionAndSubtype / state.removeMessagesBySubtype）→ 重启-有副本用例
 *       新旧两份并存 → 请求/DB 2 份 → hasSize(1) 红（重启堆副本，本批核心回归）</li>
 *   <li>改回「存在即跳过」（不重注入）→ 重启-有副本用例 id == 旧副本 id → isNotEqualTo 红（hook 输出变更
 *       无法刷新的回归锁）</li>
 *   <li>删 P0-1 落库分支 → fresh/重启-无副本注入不落库 → 后续 run 恢复历史无 hook → 依赖副本的断言缺位变红</li>
 * </ul>
 */
@DisplayName("[session-start-cc-align C 级] hook_additional_context 单份链：cold 注入/落库 → hot 固定重放")
class LlmAgentLoopHookAdditionalContextPersistChainTest {

    /** 生产 sessionId 原始键（"sess-xxx" 格式 · SessionService.generateId 前缀）。 */
    private static final String SESSION_KEY = "sess-chain01";
    private static final String USER1_ID = "msg-u1";
    private static final String USER2_ID = "msg-u2";
    private static final String ASST1_ID = "asst-run1";
    private static final String HOOK_ID = "hook-run1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION_KEY + "/stream";
    /** run1 注入的 hook 原文（<system-reminder> 包装体 · 对齐 LlmAgentLoop §14 :2687-2688）。 */
    private static final String HOOK_CONTENT =
        "<system-reminder>\nSessionStart hook additional context: zjkycode orchestrating\n</system-reminder>";

    @BeforeEach
    void setUp() {
        // [C 级 2026-09-07] 进程级 sessionStartSeen 清空：多用例共享 SESSION_KEY，每用例从 cold 起算
        //   （重启模拟 = reset 后首 run 判 cold）；防止同 JVM 前一用例已注册 key 使本例误判 hot。
        SessionStartSeenRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        SessionStartSeenRegistry.reset();
    }

    // ── 内存 MessageService（非 mock）：ChatService.appendMessage 落库 → listRawForTranscript 读回 → resume ──

    /** 真实 {@link MessageService} 子类，仅把 appendMessage 落库到内存 rows（listRawForTranscript 读回）。
     *  非 final：用例 5 以匿名子类覆写 appendMessage / deleteBySessionAndSubtype 模拟失败路径。 */
    private static class InMemoryMessageService extends MessageService {
        final List<ChatMessageDto> rows = new ArrayList<>();

        @Override
        public ChatMessageDto appendMessage(ChatMessageDto dto, OffsetDateTime createdAt) {
            rows.add(dto);
            return dto;
        }

        @Override
        public List<ChatMessageDto> listRawForTranscript(String sessionId) {
            // 插入序 = created_at ASC（本测试 seed + append 均按时间序追加）
            return new ArrayList<>(rows);
        }

        /** [先插后删不变量] §14 用 existsById 复核「新份是否真的落库」后才允许删旧份
         *  （生产 = DB 真查询；此处 = rows 里查得到）。未落库 → 不删旧份。 */
        @Override
        public boolean existsById(String id) {
            if (id == null || id.isBlank()) {
                return false;
            }
            return rows.stream().anyMatch(m -> m != null && id.equals(m.id()));
        }

        /** [SM/compact 对齐 CC] 覆盖式写通道：§14 cold 注入后清本会话 hook_additional_context 其它旧份
         *  （先插后删 · DB 恒 1 条）。过滤口径与生产 SQL 一致 = <b>sessionId + subtype 双条件 + 排除新份 id</b>
         *  （rows 里的 DTO 带 sessionId）。 */
        @Override
        public int deleteBySessionAndSubtype(String sessionId, String subtype, String excludeId) {
            if (sessionId == null || subtype == null) {
                return 0;
            }
            int before = rows.size();
            rows.removeIf(m -> m != null && sessionId.equals(m.sessionId()) && subtype.equals(m.subtype())
                && (excludeId == null || !excludeId.equals(m.id())));
            return before - rows.size();
        }
        // listForResumeExcluding(List, String) 不 override → 走真实 SessionResumeDeserializer 中断语义漏斗
    }

    // ── 真实 ChatService 实时落库（P0-1：§14 注入的 hook 经 appendListener → appendMessage 落库）──

    /** 复刻 §14 注入构造（:2689-2695 22 参形状，isMeta=第 19 参 true）。 */
    private static ChatMessageDto injectedHook() {
        return new ChatMessageDto(
            HOOK_ID, SESSION_KEY, Role.user, "hook", HOOK_CONTENT, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, true, false,
            null, "hook_additional_context");
    }

    /** 真实 ChatService（messageMapper/toolCallMapper mock、messageService=InMemory db）：装配 P0-1 实时落库。 */
    private static ChatService realTimeChat(InMemoryMessageService db) {
        ChatService chat = new ChatService();
        ReflectionTestUtils.setField(chat, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(chat, "toolCallMapper", mock(ToolCallMapper.class));
        ReflectionTestUtils.setField(chat, "messageService", db);
        return chat;
    }

    /** mocked provider：捕获首轮 history（arg 3）到 holder + 首调返回纯文本 stop → loop 正常退出。 */
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** 反射注入 hookRegistry：SessionStart executeEvent 恒返回携带 additionalContexts 的结果（§14 消费）。
     *  @param calls SESSION_START executeEvent 调用计数（C 级断言：hot/resume run 零调用、cold 重启 run 被调）。
     *              只计 SESSION_START：doRun 每 run 还有 UserPromptSubmit 等其它事件走同一
     *              HookRegistry.executeEvent 分发（:2791），不得误计。 */
    private static void setHookRegistryReturningAdditionalContext(LlmAgentLoop loop, AtomicInteger calls) throws Exception {
        HookRegistry registry = new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                // §14 消费者读取 additionalContexts（非空 → 若 cold run 注入去重失效会真实重注入，测试才能变红）
                if (event.type() == HookEventType.SESSION_START) {
                    calls.incrementAndGet();
                }
                return new GenericHook.HookResult(
                    false, null, null, List.of("zjkycode orchestrating + Iron Law"),
                    null, null, null, null, null, GenericHook.HookOutcome.SUCCESS,
                    null, null, null, null, null, null, null, null);
            }
        };
        java.lang.reflect.Field f = LlmAgentLoop.class.getDeclaredField("hookRegistry");
        f.setAccessible(true);
        f.set(loop, registry);
    }

    private static ChatMessageDto user(String id, String content, OffsetDateTime createdAt) {
        return new ChatMessageDto(id, SESSION_KEY, Role.user, null, content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", createdAt, null, null, null,
            List.of(), List.of(), null, false, false, null, null);
    }

    private static ChatMessageDto assistant(String id, String content, OffsetDateTime createdAt) {
        return new ChatMessageDto(id, SESSION_KEY, Role.assistant, "assistant", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", createdAt, null, null, null,
            List.of(), List.of(), null, false, false, null, null);
    }

    /** 真实 LlmAgentLoop.run 一次（restore 恢复 db + cold/hot 判据 + §14 hook + 可选实时落库）。 */
    private static AgentState runLoop(InMemoryMessageService db, AtomicInteger sessionStartCalls,
            AtomicReference<List<ChatMessageDto>> history, ChatService realTime, String currentUserId)
            throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(history));
        loop.setMessageService(db);
        loop.setStreamContext(null, SESSION_KEY, currentUserId);
        setHookRegistryReturningAdditionalContext(loop, sessionStartCalls);
        if (realTime != null) {
            // 对齐 ChatService.processUserMessage 生产接线：run 前武装实时落库 listener（P0-1 注入即落库）
            loop.setPostHistoryPersistEnabler(s -> realTime.armRealTimePersist(
                s, SESSION_KEY, STREAM_TOPIC, mock(SimpMessagingTemplate.class), currentUserId));
        }
        return loop.run(RunRequest.session("current question", SESSION_KEY,
            null, ProviderConfig.empty(), "test-model", null, null));
    }

    private static List<ChatMessageDto> hooksOf(List<ChatMessageDto> messages) {
        return messages.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
    }

    // ── 1. fresh（DB 空 / 无历史）cold → 注入 1 份并落库（对齐 CC startup 边界）──

    @Test
    @DisplayName("fresh cold(C级): 空会话首 run → 注入 1 份并落库（对齐 CC startup 边界）")
    void freshSession_coldInjectsOneAndPersists() throws Exception {
        // WHY: 全新会话（DB 无历史）首 run = cold（@BeforeEach reset）→ §14 'startup' 执行 → 覆盖式：无旧份
        //      可删 → 注入 1 份并经实时落库（P0-1）落 DB → 后续 hot run 靠该副本固定重放（单份链起点）。
        // RED：删 P0-1 落库 → DB 无 hook 行 → db 断言红（副本源缺失 → 后续 run 无固定重放）；删注入 → 请求缺 hook。
        InMemoryMessageService db = new InMemoryMessageService();
        ChatService realTime = realTimeChat(db);

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, realTime, USER1_ID);

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get())
            .as("fresh 首 run（cold）→ §14 执行")
            .isGreaterThan(0);
        List<ChatMessageDto> hooks = hooksOf(history.get());
        assertThat(hooks).as("fresh cold 注入恰 1 份").hasSize(1);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("注入经 P0-1 实时落库 → DB 现恰 1 份 hook（单份链落库源）")
            .hasSize(1);
        assertThat(hooks.get(0).id())
            .as("请求单份 == DB 落库的注入 id（同一消息）")
            .isEqualTo(dbHooks.get(0).id());
    }

    // ── 2. 同进程第二 run（key 已 cold 注册 = hot）→ §14 整段跳过、executeEvent 零调用、请求仍恰 1 份 ──

    @Test
    @DisplayName("hot 第二 run(C级): key 已注册 → §14 跳过、executeEvent 零调用、恢复副本 1 份且 id/位置稳定")
    void hotSecondRun_sameProcess_executeEventNotCalled_singlePersistedHook() throws Exception {
        // WHY: 本进程 run1（cold）已把 hook 注入并落库（A 级 P0-1），key=SESSION_KEY 已注册 → 本次 run2（热）
        //      cold=markSeen→false → §14 整段跳过（不 executeEvent / 不重注入）→ 模型仍见 P0-1 落库的单一副本。
        //      为聚焦「热」机制：直接预置 key 注册（等效同进程先前 run 已发生），DB 预置 run1 落库产物。
        // RED：删 cold 判据 → run2 重进 §14 → executeEvent 被调 → calls 断言红。
        InMemoryMessageService db = new InMemoryMessageService();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        db.rows.add(user(USER1_ID, "earlier question", t0));
        db.rows.add(injectedHook());
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));
        SessionStartSeenRegistry.markSeen(SESSION_KEY);   // 等效 run1（同进程）已发生

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, null, USER2_ID);

        assertThat(state).as("run2（热）正常完成").isNotNull();
        assertThat(sessionStartCalls.get())
            .as("hot run（key 已注册）→ §14 SessionStart 整段跳过，executeEvent 不得被调用（删 cold 判据 → 红）")
            .isZero();
        List<ChatMessageDto> request = history.get();
        assertThat(request).as("模型请求非空").isNotNull();
        List<ChatMessageDto> hooks = hooksOf(request);
        assertThat(hooks).as("hot run 请求恰 1 份（恢复历史含 P0-1 落库副本，不重注）").hasSize(1);
        assertThat(hooks.get(0).id()).as("单份 = 已落库原注入 id（非新随机 UUID）").isEqualTo(HOOK_ID);
        assertThat(hooks.get(0).content()).as("内容跨 run 字节稳定").isEqualTo(HOOK_CONTENT);
        int hookIdx = request.indexOf(hooks.get(0));
        int user1Idx = request.indexOf(request.stream().filter(m -> USER1_ID.equals(m.id())).findFirst().orElse(null));
        int asst1Idx = request.indexOf(request.stream().filter(m -> ASST1_ID.equals(m.id())).findFirst().orElse(null));
        assertThat(user1Idx).as("user1 出现在请求中").isGreaterThanOrEqualTo(0);
        assertThat(asst1Idx).as("asst1 出现在请求中").isGreaterThanOrEqualTo(0);
        assertThat(hookIdx)
            .as("P0-2 决策 A：DB created_at ASC → hook 位于 user1 与 asst1 之间固定位置（非尾部重塞）")
            .isGreaterThan(user1Idx)
            .isLessThan(asst1Idx);
        assertThat(db.rows).as("hot run 不产生第二份（无注入 append）")
            .filteredOn(m -> "hook_additional_context".equals(m.subtype()))
            .hasSize(1);
    }

    // ── 2. 重启模拟（reset 清空进程级标记）旧会话已有落库副本 → cold → executeEvent 被调、覆盖式恒 1 份（核心）──

    @Test
    @DisplayName("重启模拟-旧会话有副本(C级): cold → executeEvent 被调（副作用重跑）+ 覆盖式删旧插新 → 恒 1 份")
    void restartSimulation_oldSessionWithPersistedHook_coldRuns_executeEventCalled_overwriteSingle() throws Exception {
        // WHY: 后端重启 → SessionStartSeenRegistry 天然清空（本测试 @BeforeEach reset 模拟）→ 老会话（DB 已含
        //      A 级落库 hook 副本）首条消息判 cold → §14 执行 = hook 副作用重跑（watchPaths 等补回，本批核心）；
        //      恢复历史含旧副本 → 覆盖式注入（DB 删旧 + 内存 removeMessagesBySubtype + append 新份）→ 旧份不再
        //      随恢复历史重放（hook 配置/输出变更能被刷新）、DB/请求恒 1 份（绝无翻倍）。
        // RED（本批核心回归）：删「删旧」任一半 → 新旧两份并存 → 请求/DB 2 份 → hasSize(1) 红；改回「存在即
        //      跳过」→ id == HOOK_ID（旧份）→ isNotEqualTo 红。
        InMemoryMessageService db = new InMemoryMessageService();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        db.rows.add(user(USER1_ID, "earlier question", t0));
        db.rows.add(injectedHook());
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));
        ChatService realTime = realTimeChat(db);   // 覆盖式：删旧 + 新份注入即落库
        // 无 markSeen：@BeforeEach reset 后本 run 即冷（模拟重启后首条）

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, realTime, USER2_ID);

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get())
            .as("重启后老会话首条消息（cold）必须执行 §14 hook（副作用补回）→ executeEvent 被调")
            .isGreaterThan(0);
        List<ChatMessageDto> hooks = hooksOf(history.get());
        assertThat(hooks)
            .as("覆盖式注入：内存旧份已移除 + 新份注入 → 请求仍恰 1 份（防重启堆副本）")
            .hasSize(1);
        assertThat(hooks.get(0).id())
            .as("本次新注入（非旧副本 id）—— 覆盖式刷新而非「存在即跳过」")
            .isNotEqualTo(HOOK_ID);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("DB 恒 1 条：旧份已删 + 新份落库（不再累积多份）")
            .hasSize(1);
        assertThat(dbHooks.get(0).id())
            .as("DB 单条 == 请求单条（同一次注入，id 一致）")
            .isEqualTo(hooks.get(0).id());
    }

    // ── 3. 重启模拟旧会话无副本 → cold → executeEvent 被调 + 注入 1 份并落库 ──

    @Test
    @DisplayName("重启模拟-旧会话无副本(C级): cold → executeEvent 被调 + 注入 1 份并落库（老会话补注入）")
    void restartSimulation_oldSessionWithoutPersistedHook_coldRuns_injectsOneAndPersists() throws Exception {
        // WHY: A 级上线前的老会话 / 曾注入失败会话 → DB 历史无 hook 副本；后端重启后首条消息 cold → §14 执行
        //      → 覆盖式：无旧份可删 → 注入 1 份并经实时落库（P0-1）落 DB → 后续 hot run 靠副本固定重放。
        //      （对齐 CC resume 边界：注入在起点，老会话重启 = 新的起点 → 补一次。）
        // RED：删 P0-1 落库 / 注入路径 → DB 无 hook 行 → db 断言红；注入前错误判「已有副本」→ 请求缺 hook 红。
        InMemoryMessageService db = new InMemoryMessageService();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        db.rows.add(user(USER1_ID, "earlier question", t0));
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));
        ChatService realTime = realTimeChat(db);   // P0-1：注入即落库

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, realTime, USER2_ID);

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get())
            .as("重启后无副本老会话首条消息（cold）→ §14 执行")
            .isGreaterThan(0);
        List<ChatMessageDto> hooks = hooksOf(history.get());
        assertThat(hooks)
            .as("覆盖式：恢复历史无旧份可删 → cold 注入恰 1 份")
            .hasSize(1);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("注入经 P0-1 实时落库 → DB 现恰 1 份 hook（后续 hot run 固定重放源）")
            .hasSize(1);
        assertThat(hooks.get(0).id())
            .as("请求单份 == DB 落库的注入 id（同一消息）")
            .isEqualTo(dbHooks.get(0).id());
    }

    // ── 4. /clear 移除 key → 下 run 恢复 cold（副作用重跑）；转录保留 → 覆盖式写、恒 1 份无 2 份 ──

    @Test
    @DisplayName("/clear 后(C级): key 移除 → 下 run cold 重跑 executeEvent，覆盖式写 → 注入仍 1 份无 2 份")
    void afterClear_keyRemoved_nextRunColdReruns_noDouble() throws Exception {
        // WHY: CommandController /clear（C 级接线）在清空会话时点 SessionStartSeenRegistry.remove(sessionKey) →
        //      下 run 恢复 cold（hook 副作用重跑，对齐 CC clear 边界）。/clear 不删 DB 消息行（B-4 复核：转录
        //      保留）→ 恢复历史仍含旧副本 → 覆盖式删旧插新（DB deleteBySessionAndSubtype + 内存 remove +
        //      append 新份落库）→ DB/请求恒 1 份、绝无 2 份。
        //      （字面「clear 后必见一次新注入」仅当转录真被清空（无副本）时发生——当前 /clear 不真空 DB 行，
        //      属产品行为登记项，见 session-start-cc-align.md B-4 与 CommandController C 级注释。）
        // RED：删 CommandController remove → 下 run 判 hot → executeEvent 不再被调 → calls 断言红（clear 边界
        //      的副作用补回丢失）；删「删旧」→ cold 重跑新旧并存 → 2 份红。
        InMemoryMessageService db = new InMemoryMessageService();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        db.rows.add(user(USER1_ID, "earlier question", t0));
        db.rows.add(injectedHook());
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));
        ChatService realTime = realTimeChat(db);   // 覆盖式：删旧 + 新份注入即落库
        // 先热（前 run 已发生）再 /clear 移除 key → 下 run 恢复冷
        SessionStartSeenRegistry.markSeen(SESSION_KEY);
        SessionStartSeenRegistry.remove(SESSION_KEY);   // = CommandController /clear remove(RequestContext.sessionId)

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, realTime, USER2_ID);

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get())
            .as("/clear 后 key 已移除 → 下 run 恢复 cold → §14 重跑（executeEvent 被调，副作用补回）")
            .isGreaterThan(0);
        List<ChatMessageDto> hooks = hooksOf(history.get());
        assertThat(hooks)
            .as("覆盖式写：内存旧份已移除 → 注入仍 1 份（无 2 份）")
            .hasSize(1);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("clear + cold 重跑后 DB 恒 1 份（旧份删 + 新份落库，绝无 2 份）")
            .hasSize(1);
        assertThat(dbHooks.get(0).id())
            .as("DB 单条 == 请求单条（同一次注入）")
            .isEqualTo(hooks.get(0).id());
    }

    // ── 5. [SM/compact 对齐 CC] 「先插后删」不变量：插入失败 / 删除失败 均绝不丢份（DB 恒 ≥1 条）──

    @Test
    @DisplayName("[先插后删] 补落库失败 → 不执行删旧 → DB 旧份仍在（DB 恒 1 条，绝不丢份）")
    void directPersistFails_oldRowKept_noDataLoss() throws Exception {
        // WHY（CLAUDE.md 规则九 · 不变量）：§14 覆盖式注入必须「先插新份（成功）→ 再删其它旧份」。
        //   若补落库失败仍执行删旧 → DB 变 0 条 = 丢份（比多一份更糟，与 CC 不落 transcript 的单份语义
        //   相反方向的破坏）。本用例模拟 appendMessage（2 参）抛异常 + appendListener 未武装（realTime=null）
        //   → §14 ④ 补落库失败 → newPersisted=false → ⑤ 不删 → 旧份仍在。
        // RED：删掉 newPersisted 闸 → 删旧执行 → dbHooks 空（0 条）→ 红。
        InMemoryMessageService db = new InMemoryMessageService() {
            @Override
            public ChatMessageDto appendMessage(ChatMessageDto dto, OffsetDateTime createdAt) {
                throw new IllegalStateException("模拟落库失败（先插后删不变量：插入失败不得删旧）");
            }
        };
        db.rows.add(user(USER1_ID, "earlier question", OffsetDateTime.now().minusMinutes(5)));
        db.rows.add(injectedHook());   // 旧份（DB 唯一副本）
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, null, USER2_ID);   // realTime=null → 未武装

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get()).as("cold → §14 执行").isGreaterThan(0);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("插入失败 → 不删旧 → DB 旧份仍在（DB 恒 1 条，绝不丢份）")
            .hasSize(1);
        assertThat(dbHooks.get(0).id())
            .as("保住的正是旧份（未被新份替换）")
            .isEqualTo(HOOK_ID);
    }

    @Test
    @DisplayName("[先插后删] 已武装但 ChatService 侧 insert 被吞（best-effort warn）→ 新份没落库 → 不删旧份（DB 恒 1 条旧份）")
    void armedButPersistSwallowed_oldRowKept_noDataLoss() throws Exception {
        // WHY（CLAUDE.md 规则九 · 不变量）：落库成功判据不能只看「appendListener 已武装」。
        //   ChatService.persistAppendedMessage 对 hook_additional_context 的 insert 是 best-effort
        //   （try/catch → 仅 log.warn 不上抛，ChatService:1467-1480）：insert 真失败时 appendMessage 仍
        //   正常返回。若 §14 只信 isAppendPersistenceArmed() → 误判 newPersisted=true → ⑤ 删掉 DB 旧份
        //   ⇒ **新份没入库 + 旧份被删 = DB 0 条**，模型永久丢失技能注入（直到下一次 cold 边界才补回）。
        //   本用例覆盖「已武装 + 底层 insert 被吞」路径：appendListener 正常武装（realTime != null），
        //   但 messageService.appendMessage(2 参) 静默不写 rows（等价 ChatService 内部 try/catch 吞掉的
        //   净效果：无异常、无新行）→ existsById(新份 id)==false → ⑤ 不执行 → 旧份仍在。
        // RED：把判据改回 state.isAppendPersistenceArmed() → 本用例删旧执行 → dbHooks 空（0 条）→ 红。
        InMemoryMessageService db = new InMemoryMessageService() {
            @Override
            public ChatMessageDto appendMessage(ChatMessageDto dto, OffsetDateTime createdAt) {
                // 哑写：不落 rows、不抛异常 = ChatService 对 hook 行 insert 失败的净效果（仅 log.warn）
                return dto;
            }
        };
        db.rows.add(user(USER1_ID, "earlier question", OffsetDateTime.now().minusMinutes(5)));
        db.rows.add(injectedHook());   // 旧份（DB 唯一副本 · id=HOOK_ID）
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));
        ChatService realTime = realTimeChat(db);   // 已武装：§14 走 appendListener 通道，不走 ④ 补落库

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, realTime, USER2_ID);

        assertThat(state).isNotNull();
        assertThat(sessionStartCalls.get()).as("cold → §14 执行").isGreaterThan(0);
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("新份被吞（未落库）→ 不得删旧份 → DB 仍恰 1 条（绝不 0 条）")
            .hasSize(1);
        assertThat(dbHooks.get(0).id())
            .as("保住的正是旧份 id（新份从未落库，也无从替换）")
            .isEqualTo(HOOK_ID);
        // 内存面仍注入新份（模型本轮可见）——丢的只是「DB 持久副本」，不是本次请求的注入
        assertThat(hooksOf(history.get()))
            .as("本轮请求仍恰 1 份注入（内存覆盖不受 DB 落库失败影响）")
            .hasSize(1);
        assertThat(hooksOf(history.get()).get(0).id())
            .as("内存注入 = 新随机 id（非旧份）")
            .isNotEqualTo(HOOK_ID);
    }

    @Test
    @DisplayName("[先插后删] 删旧失败 → 新份已落库 → DB 有 2 条（绝不 0 条，删除失败只 log.warn）")
    void deleteFails_newRowPersisted_neverZero() throws Exception {
        // WHY：删除失败不得回滚已插入的新份（DB 可能多 1 份但绝不丢份）。模拟 deleteBySessionAndSubtype(3 参)
        //   抛异常 + 未武装 → ④ 补落库成功（newPersisted=true）→ ⑤ 删旧抛异常被 catch（warn）→ DB = 旧 + 新 2 条。
        // RED：删旧失败时回滚/丢弃新份 → dbHooks 仅 1 条旧份或 0 条 → 红。
        InMemoryMessageService db = new InMemoryMessageService() {
            @Override
            public int deleteBySessionAndSubtype(String sessionId, String subtype, String excludeId) {
                throw new IllegalStateException("模拟删旧失败（新份已落库 → 只 log.warn，绝不回滚）");
            }
        };
        db.rows.add(user(USER1_ID, "earlier question", OffsetDateTime.now().minusMinutes(5)));
        db.rows.add(injectedHook());   // 旧份
        db.rows.add(assistant(ASST1_ID, "earlier answer", OffsetDateTime.now().minusMinutes(4)));

        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        AtomicInteger sessionStartCalls = new AtomicInteger();
        AgentState state = runLoop(db, sessionStartCalls, history, null, USER2_ID);   // realTime=null → 未武装

        assertThat(state).isNotNull();
        List<ChatMessageDto> dbHooks = db.rows.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(dbHooks)
            .as("删旧失败 → 新份已落库 + 旧份残留 = 2 条（绝不 0 条；下次 cold 覆盖式写会再清）")
            .hasSize(2);
        assertThat(dbHooks).extracting(ChatMessageDto::id)
            .as("含旧份 id + 新份 id（新份确已落库）")
            .contains(HOOK_ID)
            .doesNotContainNull();
    }
}
