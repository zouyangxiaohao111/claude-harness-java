package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [OD-14 D-1] PartialCompactService 编排单测 · 对齐 CC REPL.tsx:4918-4972 onSummarize。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 服务层是 CC REPL onSummarize 语义的
 * Java 翻译（剥离→pivot→摘要→重组→写回→cleanup→新 conversationId）。本测试锁定：
 * <ol>
 *   <li><b>direction-aware 重组顺序</b>（REPL.tsx:4950-4952）：from 时 keep 在 summary
 *       之前（前缀保留段先于摘要）；up_to 时 summary 在前（摘要先于后缀保留段）。若顺序错位
 *       → 前端 setMessages 后对话语义错乱（被摘要内容恢复顺序反了）。</li>
 *   <li><b>写回调用</b>（REPL.tsx:4964/4971）：replaceSessionMessages 收到重组列表 +
 *       updateConversationId 收到新 randomUUID；若未写回 → 下次 partial 无法重复剥离 boundary。</li>
 *   <li><b>错误翻译</b>：nothing_to_summarize → ValidationException(400)（CC compact.ts:802-808
 *       抛错）；messageId 不在剥离后列表 → NotFoundException(404)（REPL.tsx:4923-4930 warning）；
 *       NO_SUMMARY 生成失败 → 原样 IllegalArgumentException(500)（compact.ts:900-916），
 *       <b>不得</b>被并入 400。</li>
 * </ol>
 */
@DisplayName("[OD-14 D-1] PartialCompactService 编排")
class PartialCompactServiceTest {

    private static final String SESSION = "s1";

    // ── 消息工厂 ───────────────────────────────────────────────────────

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 无 boundary 会话：[u0, a0, u1, a1]（初次 partial）。 */
    private static List<ChatMessageDto> fourMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(msg("u0", Role.user));
        list.add(msg("a0", Role.assistant));
        list.add(msg("u1", Role.user));
        list.add(msg("a1", Role.assistant));
        return list;
    }

    /** 构造服务：mock MessageService/SessionService + StreamCompactSummary 摘要生产。 */
    private static PartialCompactService service(List<ChatMessageDto> sessionMessages,
                                                 String summaryText) {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(sessionMessages);
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1)); // 模拟归一化：返回原列表
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult(summaryText, null));
        return new PartialCompactService(messageService, sessionService, summary);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("from 选 u1：keep=[u0,a0] 在 summary 之前（REPL.tsx:4950-4952）→ 写回 + 新 conversationId")
    void fromReorg_keepBeforeSummary() {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null));

        // 重组顺序 = [boundary, ...keep, summary]（from：前缀保留段先于摘要）
        List<ChatMessageDto> messages = resp.messages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).subtype()).isEqualTo("compact_boundary");
        assertThat(messages.get(1).id()).isEqualTo("u0");   // keep 在 summary 之前
        assertThat(messages.get(2).id()).isEqualTo("a0");
        assertThat(messages.get(3).subtype()).isEqualTo(CompactConversation.SUMMARY_SUBTYPE);

        // 写回：replaceSessionMessages(sessionId, 重组列表) + updateConversationId(sessionId, new UUID)
        ArgumentCaptor<List<ChatMessageDto>> writtenCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageService).replaceSessionMessages(org.mockito.ArgumentMatchers.eq(SESSION),
            writtenCaptor.capture());
        assertThat(writtenCaptor.getValue()).extracting(ChatMessageDto::id)
            .containsExactly("compact-boundary-compact_boundary", "u0", "a0", writtenCaptor.getValue().get(3).id());
        ArgumentCaptor<String> convCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionService).updateConversationId(org.mockito.ArgumentMatchers.eq(SESSION),
            convCaptor.capture());
        assertThat(convCaptor.getValue()).isNotBlank();
        assertThat(resp.conversationId()).isEqualTo(convCaptor.getValue());
        // conversationId 必须为新 randomUUID（REPL.tsx:4971，前端 row key 刷新）
        assertThat(resp.conversationId()).isNotNull();
    }

    @Test
    @DisplayName("up_to 选 a1：summary 在 keep=[a1] 之前（REPL.tsx:4950-4951）")
    void upToReorg_summaryBeforeKeep() {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        // 重组顺序 = [boundary, summary, ...keep]（up_to：摘要先于后缀保留段）
        List<ChatMessageDto> messages = resp.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).subtype()).isEqualTo("compact_boundary");
        assertThat(messages.get(1).subtype()).isEqualTo(CompactConversation.SUMMARY_SUBTYPE);
        assertThat(messages.get(2).id()).isEqualTo("a1");
    }

    @Test
    @DisplayName("up_to 选首条 u0：pivot=0 无前段可摘要 → 400 ValidationException（compact.ts:802-808）")
    void upToFirst_400_nothingToSummarizeBefore() {
        PartialCompactService svc = service(fourMessages(), "summary ok");
        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u0", PartialCompactRequest.Direction.UP_TO, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Nothing to summarize before the selected message.");
    }

    @Test
    @DisplayName("nothing_to_summarize_after 错误翻译 → 400 ValidationException（不只 BEFORE 分支）")
    void nothingToSummarizeAfter_400_translation() {
        // 经 API 真实消息触发 after-empty 不可达（pivot=indexOf 恒 < size，from 段至少含选中消息）；
        // 直接验证服务层翻译分支：底层抛 AFTER 文本 → ValidationException(400)。
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(summary.summarize(anyString(), anyList()))
            .thenThrow(new IllegalArgumentException(
                PartialCompactConversation.ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_AFTER));
        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);

        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Nothing to summarize after the selected message.");
    }

    @Test
    @DisplayName("messageId 不在剥离后 active 列表 → 404 NotFoundException（REPL.tsx:4923-4930）")
    void messageNotFound_404() {
        PartialCompactService svc = service(fourMessages(), "summary ok");
        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("nonexistent", PartialCompactRequest.Direction.FROM, null)))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("no longer in the active context");
    }

    @Test
    @DisplayName("有 boundary 时剥离后选 boundary 前消息 → 404（REPL.tsx:4921 先剥离再 indexOf）")
    void boundaryStrippedMessageNotFound_404() {
        // 会话 = [u0(已压缩), boundary, u1]：选 u0（boundary 前）→ 剥离后不在 → 404
        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(msg("u0", Role.user));
        messages.add(CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, null, null, null).toChatMessageDto());
        messages.add(msg("u1", Role.user));
        PartialCompactService svc = service(messages, "summary ok");
        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u0", PartialCompactRequest.Direction.FROM, null)))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("no longer in the active context");
    }

    @Test
    @DisplayName("无摘要（NO_SUMMARY）→ 原样 IllegalArgumentException(500)，不并入 400（compact.ts:900-916）")
    void noSummary_rethrown_notTranslated() {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(summary.summarize(anyString(), anyList())).thenReturn(null); // 模型未产出摘要
        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);

        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(PartialCompactConversation.ERROR_MESSAGE_NO_SUMMARY);
    }

    @Test
    @DisplayName("feedback 透传注入压缩提示词（compact.ts:827-834，REPL.tsx:4943 userFeedback）")
    void feedbackPassedThrough() {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        List<String> capturedPrompts = new ArrayList<>();
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(summary.summarize(anyString(), anyList())).thenAnswer(inv -> {
            capturedPrompts.add(inv.getArgument(0));
            // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）
            return new CompactConversation.SummaryResult("summary ok", null);
        });
        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);

        svc.partialCompact(SESSION,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, "keep this context"));

        assertThat(capturedPrompts).isNotEmpty();
        assertThat(capturedPrompts.get(0)).contains("User context: keep this context");
    }

    // ════════════════════════════════════════════════════════════════════
    // [RES-C3] OPD-SP-33 生产 partial 注入会话 AgentState 组装链原料
    //   buildContext 补齐四原料 + gate → buildCacheSafeParamsForPartial 非 null（fork 缓存共享生效）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [RES-C3] 生产 partial（REST 编排）是 CC REPL.tsx:4943 触发层的对应方：REPL 在 partial 触发层
     * 构建 cacheSafeParams（systemPrompt/userContext/systemContext/toolUseContext/forkContextMessages，
     * REPL.tsx:4935-4942）后传入 partialCompactConversation。Java REST 无 REPL 上下文 → 由
     * {@link PartialCompactService#buildContext} 从会话 AgentState 组装同款原料（对齐 R1 manual
     * ToolRegistrationConfig:1302-1310）。本测试钉死接线后的契约：注册会话 AgentState（含
     * currentToolUseContext + systemPrompt/appendSystemPrompt）→ buildCacheSafeParamsForPartial
     * 非 null → summarize 期间 CacheSafeParamsHolder 非 null（fork 缓存共享路径可达）；压缩后
     * finally clear 槽位空。
     * WHY（规则 9）：OPD-SP-33「生产 partial 未注入」——修复前 buildContext 缺原料 → build 返回 null
     * → save(null) → fork 缓存共享跳过（无缓存共享）；本测试把「会话 AgentState 可得 → 非 null」钉死
     * 为不可回归。
     */
    @Test
    @DisplayName("[RES-C3] 注册会话 AgentState（TUC+systemPrompt 齐全）→ summarize 期间 CacheSafeParamsHolder 非 null（fork 缓存共享生效）→ finally clear")
    void registeredSessionState_producesCacheSafeParams() {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionId = sessionUuid.toString();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), sessionUuid, PermissionMode.DEFAULT, Map.of(), List.of(),
            "", new AbortController(), List.of());
        AgentState state = new AgentState("CUSTOM-PROMPT", sessionUuid, null, "APPEND");
        state.setCurrentToolUseContext(tuc);
        registry.register(sessionUuid, state);

        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(summary.summarize(anyString(), anyList())).thenAnswer(inv -> {
            // 摘要生产期间读 ThreadLocal 槽位（StreamCompactSummary cacheSafeParamsSupplier=Holder.get() 读侧）
            seenDuringSummarize.set(CacheSafeParamsHolder.get());
            // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）
            return new CompactConversation.SummaryResult("summary ok", null);
        });
        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summary, registry, null, null);

        PartialCompactResponse resp = svc.partialCompact(sessionId,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        // 会话 AgentState 可得 → buildCacheSafeParamsForPartial 非 null → 摘要期间槽位非 null（修复前恒 null → RED）
        CacheSafeParams cs = seenDuringSummarize.get();
        assertThat(cs).as("会话 AgentState 已注册 → fork 缓存共享原料注入 → summarize 期间 Holder 非 null")
            .isNotNull();
        // 6 字段完整（forkedAgent.ts:57-68 + betas.ts:227-233）
        assertThat(cs.toolUseContext()).isSameAs(tuc);          // 会话一致 TUC 透传
        assertThat(cs.systemPrompt()).contains("CUSTOM-PROMPT"); // custom 替换 default（I-13）
        assertThat(cs.userContext()).containsKey("currentDate"); // 会话级 userContext（sessionStartDate 冻结）
        // 压缩后槽位清空（finally clear，防串台/泄漏到下一流程）
        assertThat(CacheSafeParamsHolder.get()).isNull();
        assertThat(resp.messages()).isNotEmpty();
    }

    @Test
    @DisplayName("[RES-C3] AC4 provider 生命周期：partialCompact 结束 finally close → CACHE_CLEAR_HOOKS 回到基线（register/unregister 成对）")
    void providerLifecycle_hookReturnsToBaseline() throws Exception {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionId = sessionUuid.toString();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), sessionUuid, PermissionMode.DEFAULT, Map.of(), List.of(),
            "", new AbortController(), List.of());
        AgentState state = new AgentState("CUSTOM-PROMPT", sessionUuid, null, "APPEND");
        state.setCurrentToolUseContext(tuc);
        registry.register(sessionUuid, state);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summary, registry, null, null);

        int before = hookTableSize();
        try {
            svc.partialCompact(sessionId,
                new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));
        } finally {
            RequestContext.clear();
        }
        // buildContext 新建 SystemPromptContextProvider（构造注册缓存清理回调）→ partialCompact finally close()
        // 注销 → 静态表回到基线（RES-C2 契约；若删掉 finally close → 每次 partial +1 有界累积 → 红）
        assertThat(hookTableSize()).as("partialCompact 结束后 provider finally close → CACHE_CLEAR_HOOKS 回到基线")
            .isEqualTo(before);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP2-03 返工 r2] partial 路径 async/plan/plan_mode 附件生产
    //   （CC partialCompactConversation compact.ts:925-948）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [IMP2-03 返工 r2] partial 压缩路径补齐 async-agent/plan/plan_mode 附件生产（反思修正
     * 清单 2）。CC partialCompactConversation（compact.ts:925-953）同样生产
     * file+async+plan+plan_mode+skill+3×delta；返工前 Java partial 路径仅 3×delta 接入
     * （PartialCompactConversation restore 尾部），async/plan/plan_mode 三工厂 0 生产。
     * WHY（规则 9）：CC 设计意图是「so the model doesn't spawn a duplicate」（compact.ts:1564-1567）
     * 与「otherwise it would lose the plan mode instructions」（compact.ts:1536-1541）——
     * partial 压缩后模型若看不到运行中 async 任务/plan 模式会重复派发或丢失模式指令。
     * 本测试钉死：注册会话 AgentState（tuc permissionMode=PLAN）+ 注入 taskFrameworkService
     * （LOCAL_AGENT 运行中任务）+ planProvider（fake）→ partialCompact 输出消息含
     * task_status/plan_file_reference/plan_mode 附件（restore 读取 additional → 进入重组列表）。
     */
    @Test
    @DisplayName("[IMP2-03 r2] partial 路径: async-agent/plan/plan_mode 附件生产（compact.ts:925-948）")
    void partialPathProducesAsyncPlanPlanModeAttachments() {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionId = sessionUuid.toString();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        // tuc permissionMode=PLAN（CC toolPermissionContext.mode==='plan' → plan_mode 附件）
        ToolUseContext tuc = ToolUseContext.of(
            null, sessionUuid, PermissionMode.PLAN, List.of(), "",
            com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.PLAN);
        AgentState state = new AgentState("CUSTOM-PROMPT", sessionUuid, null, "APPEND");
        state.setCurrentToolUseContext(tuc);
        registry.register(sessionUuid, state);

        // async-agent 数据源（CC appState.tasks local_agent，compact.ts:1571-1574）
        TaskFrameworkService tfs = new TaskFrameworkService();
        UUID asyncAgentId = UUID.randomUUID();
        tfs.registerTask(new BackgroundTask(
            asyncAgentId.toString(), TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "正在整理调研报告", null, System.currentTimeMillis(), null, null,
            com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(asyncAgentId.toString()), 0L, false,
            asyncAgentId, true));
        // plan 数据源（fake PlanProvider · CC getPlan/getPlanFilePath）· PlanProvider 非
        // 函数式接口（5 抽象方法），须匿名类
        PlanProvider planProvider = new PlanProvider() {
            @Override public String getPlanFilePath(UUID agentId) { return "plans/main.md"; }
            @Override public String getPlan(UUID agentId) { return "# 当前计划\n1. 完成附件接线"; }
            @Override public boolean copyPlanForResume(String targetSessionId, String sourceSlug) { return false; }
            @Override public boolean copyPlanForFork(String targetSessionId, String sourceSlug) { return false; }
            @Override public AttachmentMessageDto.PlanRef createPlanAttachmentIfNeeded(UUID agentId) {
                return new AttachmentMessageDto.PlanRef("plans/main.md", "# 当前计划\n1. 完成附件接线");
            }
        };

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summary, registry, null, null);
        svc.setTaskFrameworkService(tfs);
        svc.setPlanProvider(planProvider);

        PartialCompactResponse resp = svc.partialCompact(sessionId,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        // 重组输出消息含三类附件（restore 的 additional → attachments → messages）
        List<String> subtypes = resp.messages().stream()
            .map(ChatMessageDto::subtype).toList();
        assertThat(subtypes).as("partial 输出含 async-agent/plan/plan_mode 附件（compact.ts:925-948）")
            .contains("task_status", "plan_file_reference", "plan_mode");
        // 顺序对齐 CC：async → plan → plan_mode（compact.ts:935-948）
        assertThat(subtypes.indexOf("task_status"))
            .isLessThan(subtypes.indexOf("plan_file_reference"));
        assertThat(subtypes.indexOf("plan_file_reference"))
            .isLessThan(subtypes.indexOf("plan_mode"));
    }

    /**
     * [IMP2-03 返工 r4] partial 路径补齐 invoked_skills 附件生产（反思复检 r4 修正清单 2）。
     * CC partialCompactConversation（compact.ts:950-953）在 plan_mode 之后生产 skill 附件
     * （createSkillAttachmentIfNeeded(context.agentId)）；返工前 partial 路径
     * populateInvokedSkillsAttachment 0 生产（全仓唯一调用点 CompactConversation:274 为全量
     * 路径）→ partial 压缩后模型丢失 invoked skill 内容（INV-15 partial 未闭环）。
     * WHY（规则 9）：与全量路径（CompactConversation:274）同一数据源/同一方法，partial 必须
     * 与全量同构——否则 partial 压缩后 skill 行为退化。
     * 本测试钉死：注册会话 AgentState（tuc permissionMode=PLAN 供 plan_mode 顺序断言 +
     * addInvokedSkill 主会话技能）→ partialCompact → 输出消息含 subtype='invoked_skills'
     * 且顺序位于 plan_mode 之后（compact.ts:944-953）。
     */
    @Test
    @DisplayName("[IMP2-03 r4] partial 路径: invoked_skills 附件生产（compact.ts:950-953）且位于 plan_mode 之后")
    void partialPathProducesInvokedSkillsAttachment() {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionId = sessionUuid.toString();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        // tuc permissionMode=PLAN（plan_mode 附件存在，用于顺序断言）
        ToolUseContext tuc = ToolUseContext.of(
            null, sessionUuid, PermissionMode.PLAN, List.of(), "",
            com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.PLAN);
        AgentState state = new AgentState("CUSTOM-PROMPT", sessionUuid, null, "APPEND");
        state.setCurrentToolUseContext(tuc);
        // invokedSkills 数据源（CC STATE.invokedSkills bootstrap/state.ts:1502-1524；
        // null agentId = 主会话技能，createSkillAttachmentIfNeeded(context.agentId="main")
        // → parseUuidOrNull null → getInvokedSkillsForAgent(null) 命中）
        state.addInvokedSkill("调研工具", "/skills/research.md", "# 调研工具\n使用方法说明", null);
        registry.register(sessionUuid, state);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summary, registry, null, null);

        PartialCompactResponse resp = svc.partialCompact(sessionId,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        // 重组输出消息含 invoked_skills 附件（restore 的 additional → attachments → messages）
        List<String> subtypes = resp.messages().stream()
            .map(ChatMessageDto::subtype).toList();
        assertThat(subtypes).as("partial 输出含 invoked_skills 附件（compact.ts:950-953）")
            .contains("invoked_skills");
        // 顺序对齐 CC：plan_mode → skill（compact.ts:944-953）
        assertThat(subtypes.indexOf("plan_mode"))
            .as("skill 位于 plan_mode 之后（compact.ts:950-953）")
            .isLessThan(subtypes.indexOf("invoked_skills"));
        // 载荷断言：skill 名/内容进入 payload（附件恢复集完整 · INV-15）
        ChatMessageDto skillMsg = resp.messages().stream()
            .filter(m -> "invoked_skills".equals(m.subtype()))
            .findFirst().orElseThrow();
        assertThat(skillMsg.content()).contains("调研工具").contains("invoked_skills");
    }

    /** 反射读 SystemPromptInjection 静态表当前大小（ToolRegistrationConfigCompactCloseTest 同款观察点）。 */
    private static int hookTableSize() throws Exception {
        java.lang.reflect.Field field = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> table = (List<Runnable>) field.get(null);
        return table.size();
    }
}
