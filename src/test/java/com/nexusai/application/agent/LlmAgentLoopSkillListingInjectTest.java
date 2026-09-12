package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.loop.SubagentLoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.skill.SkillCatalog;
import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [skill-listing-cc-align 2026-09-10] skill_listing 注入（真实 run）实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：回归 CC 后，skill_listing 不再是每轮请求头部块，
 * 而是 doRun 内每 run 一次决策 + 真实消息（state.appendMessage）尾随当前用户消息注入。本测试经
 * <b>真实 LlmAgentLoop.run</b>（mocked provider 首调 stop + mocked SkillCatalog/ToolRegistry）断言：
 * <ol>
 *   <li>全新会话首 run（resume=false）→ state.rawMessages() 中出现一条 subtype=skill_listing 的
 *       isMeta user 消息，且<b>紧随当前用户消息之后</b>（= CC processTextPrompt.ts:97
 *       {@code [userMessage, ...attachmentMessages]}）；</li>
 *   <li>内容形态 = CC messages.ts:4160-4170 渲染契约。</li>
 * </ol>
 * 删掉 doRun 注入接线即 RED。
 */
class LlmAgentLoopSkillListingInjectTest {

    private static final String SESSION_KEY = "sess-sk12cd34";

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
    }

    /** provider 首调返回 stop 纯文本 → loop 正常退出（对齐 LlmAgentLoopResumeRestoreEntryTest）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    private static Command cmd(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        return c;
    }

    /** 捕获 run 内构建的 LoopSessionState 载体（与生产 forSession 5 参重载同构）。 */
    private static final class CapturingContextFactory extends AgentLoopContextFactory {
        @Override
        public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
                AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
            return super.forSession(streamTopic, streamSessionId, streamUserMessageId, session, overridePublisher);
        }
    }

    @Test
    @DisplayName("全新会话首 run → skill_listing 真实消息紧随用户消息注入（subtype/isMeta/content 契约）")
    void freshRun_injectsSkillListingMessageAfterUserMessage() {
        SkillListingSentRegistry.reset();

        // 转录仅含当前 in-flight 用户消息 → resume=false（全新会话首 run）
        ChatMessageDto currentUserMsg = new ChatMessageDto("msg-1", SESSION_KEY, Role.user, null,
            "fresh query", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false, null);
        MessageService messageService = mock(MessageService.class);
        when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(currentUserMsg));

        // SkillCatalog：全量 = [commit, review]，formatListing(子集) → 固定文本
        SkillCatalog catalog = mock(SkillCatalog.class);
        List<Command> commands = List.of(cmd("commit"), cmd("review"));
        when(catalog.getModelInvocableCommandsForListing()).thenReturn(commands);
        when(catalog.getModelInvocableCommands()).thenReturn(commands);
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenReturn("- commit: 提交代码\n- review: 审查代码");

        // ToolRegistry.all() 含 Skill 工具 → hasSkillToolInAvailableTools=true（CC attachments.ts:2669-2672 守卫）
        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of(skillTool));

        LlmProvider provider = stopProvider("fresh response");
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(llmProviderFactory, null, toolRegistry);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(llmProviderFactory);
        ReflectionTestUtils.setField(contextFactory, "skillCatalog", catalog);
        // [skill-listing-cc-align 修复轮] 必须接生产统一队列 bean：web 主线程当前用户消息走
        //   notificationQueue 入队 → turn-0 drain 才 append（LlmAgentLoop :4721→:8320）。不接队列
        //   会落进 else「直拼」分支（用户消息先于清单 append）→ 位置断言假绿，掩盖「队列路径清单
        //   排到用户消息之前」的 blocker。对齐 LlmAgentLoopUnifiedQueuePromptTest:87。
        NotificationQueue queue = new NotificationQueue();
        contextFactory.setNotificationQueue(queue);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, SESSION_KEY, "msg-1");

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session("fresh query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // 定位注入消息（subtype=skill_listing）
        List<ChatMessageDto> msgs = state.rawMessages();
        int listingIdx = -1;
        int userIdx = -1;
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessageDto m = msgs.get(i);
            if (m == null) continue;
            if ("skill_listing".equals(m.subtype())) listingIdx = i;
            if (Role.user.equals(m.role()) && !Boolean.TRUE.equals(m.isMeta()) && m.subtype() == null) userIdx = i;
        }

        assertThat(listingIdx).as("全新会话首 run 必须注入一条 subtype=skill_listing 的真实消息").isGreaterThanOrEqualTo(0);
        assertThat(userIdx).as("当前用户消息应在 messages 中").isGreaterThanOrEqualTo(0);
        // 证明走的是生产「队列」路径：turn-0 drain 的当前用户消息 uuid = streamUserMessageId（"msg-1"）；
        //   else 直拼分支传 null → 随机 UUID。若断言失败说明测试没覆盖生产路径（位置断言会假绿）。
        assertThat(msgs.get(userIdx).id())
            .as("当前用户消息 id 必须是 streamUserMessageId（证明走了队列 drain 生产路径，非 else 直拼）")
            .isEqualTo("msg-1");
        assertThat(queue.size()).as("队列已被 turn-0 drain 消费清空").isZero();
        assertThat(listingIdx).as("skill_listing 必须紧随当前用户消息之后（CC [userMessage, ...attachments]）")
            .isGreaterThan(userIdx);

        ChatMessageDto listing = msgs.get(listingIdx);
        assertThat(listing.isMeta()).as("CC createUserMessage({content, isMeta:true})").isTrue();
        assertThat(listing.author()).isEqualTo("attachment");
        assertThat(listing.sessionId()).as("真实消息落库必需 sessionId").isEqualTo(sessionUuid);
        assertThat(listing.content())
            .as("对齐 CC messages.ts:4160-4170 渲染契约")
            .startsWith("<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n")
            .contains("- commit: 提交代码")
            .endsWith("\n</system-reminder>");
    }

    /**
     * [finding-3 修复 2026-09-10] <b>消费层回归守卫</b>：真正塞进 {@code state.rawMessages()} 的清单必须
     * 只含本次 decision 的增量技能，而不是渲染全量 commands。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：{@code injectSkillListingForRun} 用
     * {@code decision.names()} 过滤 {@code commands} 后才 {@code formatListing}（LlmAgentLoop:8756-8758）。
     * 旧测试把 {@code formatListing} 打桩成 {@code anyList() → 固定文本}、且只跑 scenario a（此时
     * decision.names()==全量）→ 把该 filter 删掉改渲染全量 commands 也全绿 = 本任务最核心验收项
     * （新技能只发增量、不重发整份）在注入消费层零守卫。本测试令 formatListing 文本由<b>入参子集</b>
     * 派生 → 若消费层忽略 decision.names() 渲染全量，第二 run 断言 RED。
     */
    @Test
    @DisplayName("第二 run（resume 已初始化）新增技能 → 注入消息只含增量技能（不重发整份）")
    void secondRun_deltaInjectsOnlyNewSkill() {
        SkillListingSentRegistry.reset();
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        ChatMessageDto firstUser = userMsg("msg-1", SESSION_KEY, "first query");
        ChatMessageDto prevAssistant = assistantMsg("a-1", SESSION_KEY, "previous answer");
        ChatMessageDto secondUser = userMsg("msg-2", SESSION_KEY, "second query");

        MessageService messageService = mock(MessageService.class);
        when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(firstUser));

        // SkillCatalog：run1 全量=[commit,review]；run2 全量=[commit,review,new-skill]。
        //   formatListing 文本由「传入的 Command 子集」派生 → 全量 vs 增量在消息内容上可区分。
        SkillCatalog catalog = mock(SkillCatalog.class);
        when(catalog.getModelInvocableCommandsForListing())
            .thenReturn(List.of(cmd("commit"), cmd("review")))
            .thenReturn(List.of(cmd("commit"), cmd("review"), cmd("new-skill")));
        when(catalog.getModelInvocableCommands()).thenReturn(List.of(cmd("commit"), cmd("review"), cmd("new-skill")));
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(Command::getName).collect(java.util.stream.Collectors.joining("\n"));
        });

        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of(skillTool));

        LlmProvider provider = stopProvider("ok");
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(llmProviderFactory, null, toolRegistry);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(llmProviderFactory);
        ReflectionTestUtils.setField(contextFactory, "skillCatalog", catalog);
        contextFactory.setNotificationQueue(new NotificationQueue());
        loop.setContextFactory(contextFactory);

        // run 1：全新会话首 run（!resume）→ 注入整份 [commit, review]
        loop.setStreamContext(null, SESSION_KEY, "msg-1");
        AgentState s1 = loop.run(RunRequest.session("first query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));
        String listing1 = listingContent(s1.rawMessages());
        assertThat(listing1).as("首 run 注入整份").contains("commit").contains("review");

        // run 2：转录含历史（id a-1 ≠ msg-2）→ resume=true；全量新增 new-skill → 只应发增量
        when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(prevAssistant, secondUser));
        when(messageService.listForResumeExcluding(anyList(), anyString())).thenReturn(List.of(prevAssistant));
        loop.setStreamContext(null, SESSION_KEY, "msg-2");
        AgentState s2 = loop.run(RunRequest.session("second query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        String listing2 = listingContent(s2.rawMessages());
        assertThat(listing2)
            .as("第二 run 只注入增量 new-skill —— 消费层若忽略 decision.names() 渲染全量则 RED")
            .isNotNull()
            .contains("new-skill")
            .doesNotContain("commit")
            .doesNotContain("review");
    }

    /**
     * [finding-1 修复 2026-09-10] <b>子代理 loop 也拿自己的 turn-0 清单</b>。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：CC 的 skill_listing 是 attachment，由共享 {@code query()} 内的
     * {@code getAttachments} 无条件挂载；{@code sentSkillNames} 注释明写「Keyed by agentId (empty
     * string = main thread) so subagents get their own turn-0 listing」（attachments.ts:2672-2676）。
     * 修复前注入被 {@code params.deps().isMainLoop()} 门控 → 子代理/hook（isMainLoop=false）拿不到任何
     * 清单（相对上一版 A8 块的回退）。本测试用 {@code SubagentLoopDeps} 驱动真实 {@code queryLoop} →
     * 子代理 AgentState（agentId 非 null）必须出现 subtype=skill_listing 消息、且注册表键为该 agentId。
     * 重新加回 isMainLoop 门 → 本测试 RED。
     */
    @Test
    @DisplayName("子代理 loop（isMainLoop=false）→ 注入其自身 turn-0 skill_listing（finding-1：去 isMainLoop 门）")
    void subagentLoop_injectsOwnTurnZeroListing() {
        SkillListingSentRegistry.reset();

        SkillCatalog catalog = mock(SkillCatalog.class);
        List<Command> commands = List.of(cmd("commit"), cmd("review"));
        when(catalog.getModelInvocableCommandsForListing()).thenReturn(commands);
        when(catalog.getModelInvocableCommands()).thenReturn(commands);
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(Command::getName).collect(java.util.stream.Collectors.joining("\n"));
        });

        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");

        LlmProvider provider = stopProvider("subagent done");
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(provider);

        // 32 参 compat ctor（位置 6 = skillCatalog）；其余基础设施 null（loop 走容错跳过）
        AgentLoopContext ctx = new AgentLoopContext(
            null, null, null, null, null, catalog, null, null, null,
            null, llmProviderFactory, null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null,
            null, null, null, null, null, null, null);

        String sessionUuid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        AgentState state = new AgentState("subagent sys", sessionUuid, agentId);

        ToolUseContext tuc = new ToolUseContext(agentId, sessionUuid, PermissionMode.DEFAULT,
            Map.of(), List.of(skillTool));
        LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.rawMessages(), null, tuc, QuerySource.USER, "test-model",
                null, null, null, null, null, new SubagentLoopDeps(ctx), ProviderConfig.empty()),
            state, new ArrayList<>());

        String listing = listingContent(state.rawMessages());
        assertThat(listing)
            .as("子代理（isMainLoop=false）必须拿到自己的 turn-0 skill_listing（CC attachments.ts:2672-2676）")
            .isNotNull()
            .contains("commit");
        assertThat(SkillListingSentRegistry.isInitialized(sessionUuid, agentId.toString()))
            .as("子代理按自身 agentKey 独立建槽（非主线程槽）").isTrue();
    }

    /**
     * [2026-09-10 对抗核验 high 修复轮] <b>首 run 零技能不得永久抑制</b>。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：生产最贴近的复现 —— 用户在「零技能」状态开会话
     * （{@code getModelInvocableCommandsForListing()} 空）→ 修复前 {@code injectSkillListingForRun}
     * 在「候选为空」处提前 return、从未调用 {@code decide} → 注册表 INITIALIZED 不含该会话；随后往
     * {@code ~/.claude/skills} 丢技能，第二个 run（resume=true）落分支 3「全量标 sent + 不注入」→
     * 整份清单与新增技能<b>永不注入</b>。CC 此场景必然注入（allCommands 空 → suppressNext 仍被无条件
     * 消费、sent 保持空 → 下一 pass {@code sent.size()===0} → isInitial=true → 注入整份，
     * attachments.ts:2791-2809）。去掉「空候选提前 return」→ 本测试 RED 前行为即修复前行为。
     */
    @Test
    @DisplayName("首 run 零技能 → 第二 run 技能出现必须注入整份（不得被永久 suppress）")
    void zeroSkillFirstRun_thenSkillAppears_injectsOnSecondRun() {
        SkillListingSentRegistry.reset();
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        ChatMessageDto firstUser = userMsg("msg-1", SESSION_KEY, "first query");
        ChatMessageDto prevAssistant = assistantMsg("a-1", SESSION_KEY, "previous answer");
        ChatMessageDto secondUser = userMsg("msg-2", SESSION_KEY, "second query");

        MessageService messageService = mock(MessageService.class);
        when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(firstUser));

        // run1：零技能（空候选）；run2：技能出现（late-skill）。
        SkillCatalog catalog = mock(SkillCatalog.class);
        when(catalog.getModelInvocableCommandsForListing())
            .thenReturn(List.of())
            .thenReturn(List.of(cmd("late-skill")));
        when(catalog.getModelInvocableCommands()).thenReturn(List.of(cmd("late-skill")));
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(Command::getName).collect(java.util.stream.Collectors.joining("\n"));
        });

        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of(skillTool));

        LlmProvider provider = stopProvider("ok");
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(llmProviderFactory, null, toolRegistry);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(llmProviderFactory);
        ReflectionTestUtils.setField(contextFactory, "skillCatalog", catalog);
        contextFactory.setNotificationQueue(new NotificationQueue());
        loop.setContextFactory(contextFactory);

        // run 1：全新会话首 run（!resume），零技能 → 不注入
        loop.setStreamContext(null, SESSION_KEY, "msg-1");
        AgentState s1 = loop.run(RunRequest.session("first query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));
        assertThat(listingContent(s1.rawMessages())).as("零技能首 run → 不注入").isNull();

        // run 2：转录含历史（id a-1 ≠ msg-2）→ resume=true + 技能出现 → 必须注入整份
        when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(prevAssistant, secondUser));
        when(messageService.listForResumeExcluding(anyList(), anyString())).thenReturn(List.of(prevAssistant));
        loop.setStreamContext(null, SESSION_KEY, "msg-2");
        AgentState s2 = loop.run(RunRequest.session("second query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(listingContent(s2.rawMessages()))
            .as("技能出现后必须注入 —— 修复前该会话被永久 suppress（INITIALIZED 未置 → resume 落分支 3）")
            .isNotNull()
            .contains("late-skill");
    }

    /**
     * [2026-09-10 收尾轮] <b>无 Skill 工具守卫</b>（fix r3 第 4 点）的端到端验证 —— 该修复从未被独立镜头看过。
     *
     * <p><b>WHY（CLAUDE.md 规则 9 + CC attachments.ts:2750-2755 的段顺序）</b>：CC 的「无 Skill 工具」
     * 守卫位于 get-or-create/suppress 段<b>之前</b>并直接 return ⇒ 不消耗 {@code suppressNext}、不建 sent 槽。
     * nexusai 等价态：仅 {@code !resume}（= CC {@code suppressNext===false} 的新会话首 run）在守卫处补记
     * {@code markInitialized}；冷 resume（resume=true）保持未初始化 = suppress 待消费。本测试钉死两侧：
     * <ul>
     *   <li>fresh 侧：首 run 无 Skill 工具（!resume）→ 补记 → 第二 run 技能出现必须注入整份（不永久抑制）；</li>
     *   <li>冷 resume 侧：首 run 无 Skill 工具但 resume=true → 不补记 → 第二 run 仍走 suppress（对齐 CC 守卫
     *       不消耗 suppressNext）。若在守卫处<b>无条件</b>补记（被 fix r3 拒绝的 finding 字面主张），本侧会由
     *       「抑制」变「注入整份」= 反向偏离 CC —— 本侧断言即该拒绝的守卫。</li>
     * </ul>
     */
    @Test
    @DisplayName("无 Skill 工具守卫：fresh(!resume) 补记 initialized；冷 resume(resume=true) 不补记（fix r3 端到端）")
    void noSkillToolGuard_freshMarksInitialized_coldResumeDoesNot() {
        SkillListingSentRegistry.reset();

        // ── fresh 侧 ──
        String freshSession = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        MessageService fMsgSvc = mock(MessageService.class);
        when(fMsgSvc.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(userMsg("msg-1", SESSION_KEY, "first query")));
        SkillCatalog freshCatalog = catalogFor(List.of(cmd("late-skill")));

        // run1：!resume + 无 Skill 工具 → 守卫补记 markInitialized（无注入）
        AgentState f1 = runOnce(freshSession, "msg-1", "first query",
            toolRegistryWithoutSkill(), fMsgSvc, freshCatalog);
        assertThat(listingContent(f1.rawMessages())).as("无 Skill 工具 → 不注入").isNull();
        assertThat(SkillListingSentRegistry.isInitialized(freshSession, ""))
            .as("fresh(!resume) 守卫必须补记 initialized（= CC suppressNext=false）").isTrue();

        // run2：有 Skill 工具 + 转录有历史(resume=true) + 技能出现 → 必须注入整份（不得再抑制）
        when(fMsgSvc.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(
            assistantMsg("a-1", SESSION_KEY, "ans"), userMsg("msg-2", SESSION_KEY, "second query")));
        when(fMsgSvc.listForResumeExcluding(anyList(), anyString())).thenReturn(List.of(assistantMsg("a-1", SESSION_KEY, "ans")));
        AgentState f2 = runOnce(freshSession, "msg-2", "second query",
            toolRegistryWithSkill(), fMsgSvc, freshCatalog);
        assertThat(listingContent(f2.rawMessages()))
            .as("fresh 无工具 run 补记后技能出现 → 必须注入整份")
            .isNotNull().contains("late-skill");

        // ── 冷 resume 侧 ──
        String coldSession = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        MessageService cMsgSvc = mock(MessageService.class);
        when(cMsgSvc.listRawForTranscript(SESSION_KEY)).thenReturn(List.of(
            assistantMsg("a-1", SESSION_KEY, "prev"), userMsg("msg-2", SESSION_KEY, "second query")));
        when(cMsgSvc.listForResumeExcluding(anyList(), anyString())).thenReturn(List.of(assistantMsg("a-1", SESSION_KEY, "prev")));
        SkillCatalog coldCatalog = catalogFor(List.of(cmd("late-skill")));

        // run1：resume=true（转录含历史）+ 无 Skill 工具 → 守卫**不**补记
        runOnce(coldSession, "msg-2", "second query", toolRegistryWithoutSkill(), cMsgSvc, coldCatalog);
        assertThat(SkillListingSentRegistry.isInitialized(coldSession, ""))
            .as("冷 resume 无工具 run 不得补记 initialized（CC 守卫不消耗 suppressNext；finding 字面主张在此被拒）")
            .isFalse();

        // run2：有 Skill 工具 + 技能 → 落 branch 3 suppress（转录已含上一进程清单），不得注入整份
        AgentState c2 = runOnce(coldSession, "msg-3", "third query",
            toolRegistryWithSkill(), cMsgSvc, coldCatalog);
        assertThat(listingContent(c2.rawMessages()))
            .as("冷 resume 无工具 run 后首个 decide 仍 suppress（无条件补记会反向偏离 CC）").isNull();
    }

    /** 单次真实 run（独立 LlmAgentLoop 实例，共享同一 messageService/注册表状态）。 */
    private static AgentState runOnce(String sessionUuid, String userMessageId, String query,
                                      ToolRegistry toolRegistry, MessageService messageService, SkillCatalog catalog) {
        LlmProvider provider = stopProvider("ok");
        LlmProviderFactory f = mock(LlmProviderFactory.class);
        when(f.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(f, null, toolRegistry);
        loop.setMessageService(messageService);
        CapturingContextFactory cf = new CapturingContextFactory();
        cf.setLlmProviderFactory(f);
        ReflectionTestUtils.setField(cf, "skillCatalog", catalog);
        cf.setNotificationQueue(new NotificationQueue());
        loop.setContextFactory(cf);
        loop.setStreamContext(null, SESSION_KEY, userMessageId);
        return loop.run(RunRequest.session(query, sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));
    }

    /** formatListing 文本由入参子集派生 → 全量 vs 增量在注入消息内容上可区分。 */
    private static SkillCatalog catalogFor(List<Command> commands) {
        SkillCatalog catalog = mock(SkillCatalog.class);
        when(catalog.getModelInvocableCommandsForListing()).thenReturn(commands);
        when(catalog.getModelInvocableCommands()).thenReturn(commands);
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(Command::getName).collect(java.util.stream.Collectors.joining("\n"));
        });
        return catalog;
    }

    private static ToolRegistry toolRegistryWithSkill() {
        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        ToolRegistry tr = mock(ToolRegistry.class);
        when(tr.all()).thenReturn(List.of(skillTool));
        return tr;
    }

    private static ToolRegistry toolRegistryWithoutSkill() {
        Tool bash = mock(Tool.class);
        when(bash.name()).thenReturn("Bash");
        ToolRegistry tr = mock(ToolRegistry.class);
        when(tr.all()).thenReturn(List.of(bash));
        return tr;
    }

    private static ChatMessageDto userMsg(String id, String sessionId, String content) {
        return new ChatMessageDto(id, sessionId, Role.user, null, content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null);
    }

    private static ChatMessageDto assistantMsg(String id, String sessionId, String content) {
        return new ChatMessageDto(id, sessionId, Role.assistant, null, content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null);
    }

    /** 取 state.rawMessages() 中 subtype=skill_listing 消息的 content（无则 null）。 */
    private static String listingContent(List<ChatMessageDto> msgs) {
        for (ChatMessageDto m : msgs) {
            if (m != null && "skill_listing".equals(m.subtype())) {
                return m.content();
            }
        }
        return null;
    }
}
