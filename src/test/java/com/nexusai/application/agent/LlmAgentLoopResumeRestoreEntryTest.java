package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
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
 * [ALIGN-COMP-1 P1] 续跑恢复触发点迁移（partial 压缩 → LlmAgentLoop.run 入口）实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: CC {@code restoreSkillStateFromMessages}
 * 触发点在 {@code loadConversationForResume:556-558}（每次 resume 加载转录后、deserialize 前），
 * 而非 partial 压缩步骤 1.5。Java 端把触发点从 {@code PartialCompactService.partialCompact}
 * 迁到 {@code LlmAgentLoop.run} 入口（镜像 CC）。本测试经<b>真实 LlmAgentLoop.run</b>（mocked
 * provider 首调 stop）+ mocked MessageService 返回含 invoked_skills / skill_listing 附件的持久化
 * 转录，断言续跑入口真实：① invokedSkills 重建进返回的 AgentState（否则 resume 后再压缩技能丢失，
 * CC javadoc :382-386 原义）；② suppressNextSkillListing 置真（否则 resume 重复注入 ~600 token
 * skills-available 清单，CC :399-401 一次性 latch）。删掉入口接线即 RED。
 *
 * <p><b>测试基建</b>: 复用 LlmAgentLoopSessionAgentStateRegistryTest 同款真实 run 模式（裸
 * {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop）。{@link CapturingContextFactory}
 * 捕获 run 内构建的 LoopSessionState（suppressNextSkillListing AtomicBoolean 载体），供断言置真。
 */
class LlmAgentLoopResumeRestoreEntryTest {

    /** 生产 sessionId 原始键（"sess-xxx" 格式 · SessionService.generateId 前缀）。 */
    private static final String SESSION_KEY = "sess-ab12cd34";

    /** provider 首调返回 stop 纯文本 → loop 正常退出（对齐 LlmAgentLoopSessionAgentStateRegistryTest）。 */
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 附件消息（author='attachment' + subtype）· 镜像 PostCompactAttachmentRestorer.buildAttachmentMessage 契约。 */
    private static ChatMessageDto attachmentMsg(String id, String subtype, String content) {
        return new ChatMessageDto(id, SESSION_KEY, Role.user, PostCompactAttachmentRestorer.ATTACHMENT_AUTHOR,
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false,
            subtype);
    }

    /**
     * 捕获 run 内构建的 LoopSessionState（经 forSession 5 参重载透传）· 供断言 suppressNextSkillListing。
     */
    private static final class CapturingContextFactory extends AgentLoopContextFactory {
        final AtomicReference<AgentLoopContext.LoopSessionState> captured = new AtomicReference<>();

        @Override
        public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
                AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
            captured.set(session);
            return super.forSession(streamTopic, streamSessionId, streamUserMessageId, session, overridePublisher);
        }
    }

    /**
     * 主会话续跑入口真实 run → 恢复 invokedSkills + 置真 suppressNextSkillListing。
     *
     * <p>若入口接线（messageService 加载转录 → restoreSkillStateFromMessages）被删，
     * 返回 state 的 invokedSkills 为空 + suppressNextSkillListing 保持 false → 断言 RED。
     */
    @Test
    @DisplayName("续跑入口: 真实 run 从持久化转录恢复 invokedSkills + suppressNextSkillListing 置真（镜像 CC loadConversationForResume:556-558）")
    void resumeEntry_realRun_restoresInvokedSkillsAndArmsSuppress() {
        // ── 持久化转录（上次压缩写回的 invoked_skills + skill_listing 附件）──
        AgentState prior = new AgentState("test");
        prior.addInvokedSkill("skill-entry", "/skills/skill-entry.md", "Entry skill content", null);
        ChatMessageDto invokedSkillsAtt = PostCompactAttachmentRestorer.skillAttachmentForAgent(prior, null);
        assertThat(invokedSkillsAtt).isNotNull();
        List<ChatMessageDto> transcript = List.of(
            invokedSkillsAtt,
            attachmentMsg("att-listing", "skill_listing", "skills-available reminder"));

        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY)).thenReturn(transcript);

        LlmProvider provider = stopProvider("resume response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        // factory 路径 ctx 经 AgentLoopContextFactory.build() 装配，llmProviderFactory 需注入
        // （否则 ctx.llmProviderFactory()==null → 调模型 NPE；对齐生产 @Autowired 注入）。
        contextFactory.setLlmProviderFactory(factory);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, SESSION_KEY, "msg-1");

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // agentId=null（主会话续跑 · ChatService.processUserMessage 归一 null 语义）
        AgentState state = loop.run(RunRequest.session("resume query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // ① invokedSkills 经转录重建进返回 AgentState（否则 resume 后再压缩技能丢失）
        var restored = state.getInvokedSkillsForAgent(null);
        assertThat(restored).as("续跑入口必须从持久化转录恢复 invokedSkills（CC conversationRecovery.ts:387-393）")
            .hasSize(1);
        assertThat(restored.values().iterator().next().skillName()).isEqualTo("skill-entry");

        // ② skill_listing → suppressNextSkillListing 置真（一次性 latch，CC :399-401）
        assertThat(contextFactory.captured.get())
            .as("续跑入口必须构建 LoopSessionState（forSession 5 参重载透传）")
            .isNotNull();
        assertThat(contextFactory.captured.get().suppressNextSkillListing().get())
            .as("检测到 skill_listing 附件 → suppressNextSkillListing 置真，避免 resume 重复注入 skills-available")
            .isTrue();
    }

    /**
     * [P2-23 返工] 全新会话首 run：mock 转录仅含当前 in-flight 用户消息（id=streamUserMessageId
     * "msg-1"，无附件）→ resume=false → 不恢复 invokedSkills + 不置真 suppressNextSkillListing。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>: 生产 {@code ChatController.send()} 第一步同步
     * {@code createUserMessage} 持久化当前用户消息 → {@code listBySession} 在 run() 入口恒含
     * 当前用户消息 → 旧实现「转录非空」恒真（{@code if(!resume) return;} 死分支）。修正后
     * resume = 转录存在<b>非当前用户消息</b>；本测试钉死 resume=false 分支：全新会话首 run
     * 跳过恢复，不把任何技能状态误植进 fresh AgentState、不重武装 suppress。若 resume 计算被
     * 改回「转录非空」或恢复被无条件调用，本测试断言 RED。
     */
    @Test
    @DisplayName("全新会话首 run: 转录仅含当前用户消息 → resume=false → 不恢复 invokedSkills + 不置真 suppress（P2-23 返工）")
    void freshSessionFirstRun_transcriptOnlyCurrentUserMsg_skipsRestore() {
        // ── 转录仅含当前 in-flight 用户消息（id == streamUserMessageId "msg-1"；author=null 普通用户消息）──
        ChatMessageDto currentUserMsg = new ChatMessageDto("msg-1", SESSION_KEY, Role.user, null,
            "fresh session query", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false, null);
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY)).thenReturn(List.of(currentUserMsg));

        LlmProvider provider = stopProvider("fresh response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, SESSION_KEY, "msg-1");

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // agentId=null（主会话首 run · ChatService.processUserMessage 归一 null 语义）
        AgentState state = loop.run(RunRequest.session("fresh query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // ① resume=false → 不恢复 invokedSkills（fresh AgentState 保持空，不得把残留附件状态误植）
        assertThat(state.getInvokedSkillsForAgent(null))
            .as("全新会话首 run resume=false：不得从转录恢复 invokedSkills（CC conversationRecovery.ts:556-558 仅 resume 路径恢复）")
            .isEmpty();

        // ② resume=false → 不重武装 suppressNextSkillListing（保持 false）
        assertThat(contextFactory.captured.get())
            .as("续跑入口必须构建 LoopSessionState（forSession 5 参重载透传）")
            .isNotNull();
        assertThat(contextFactory.captured.get().suppressNextSkillListing().get())
            .as("全新会话首 run resume=false：不得置真 suppressNextSkillListing")
            .isFalse();
    }
}
