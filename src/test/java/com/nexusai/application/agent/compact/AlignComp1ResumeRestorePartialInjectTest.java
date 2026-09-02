package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [ALIGN-COMP-1] resume 恢复（CS-1）+ partial 注入（CS-2）端到端测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: 压缩存活 3 真实缺口之二
 * （探查 compression-survival ✗ M-34/CS-6 + ✗ M-33/CS-27）：
 * <ol>
 *   <li><b>CS-1 resume 恢复</b>: 会话接续（compact 后 resume / 新会话继续）时 invokedSkills
 *       是 AgentState 内存态（@JsonIgnore local-only），压缩时注入的 invoked_skills attachment
 *       留在消息列表；resume 侧必须从消息重建 invokedSkills（CC conversationRecovery.ts:382-403
 *       restoreSkillStateFromMessages），否则 resume 后再压缩技能丢失（CC javadoc :382-386 原义）。</li>
 *   <li><b>CS-2 partial 注入</b>: partial 压缩成功路径必须像全量压缩一样注入 invoked_skills
 *       附件（CC compact.ts:950-953 createSkillAttachmentIfNeeded→push），Java partial 此前
 *       仅 restore() 不 populate（探查 ✗ M-33）。</li>
 * </ol>
 *
 * <p>测试数据流 = 生产数据流（无 mock 核心逻辑）：
 * {@code addInvokedSkill → skillAttachmentForAgent（全量压缩输出，JSON payload）→
 * restoreSkillStateFromMessages（resume 重建）→ populateInvokedSkillsAttachment（partial 注入，
 * PartialCompactConversation step 13）→ result.attachments() 含 subtype='invoked_skills'}。
 */
class AlignComp1ResumeRestorePartialInjectTest {

    /** 会话 UUID（registry 注册键）· 生产 sessionId 为 "sess-xxx" 经 parseSessionUuid 归一。 */
    private static final String SESSION_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String SESSION = SESSION_UUID.toString();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 消息工厂（镜像 PartialCompactConversationTest 惯例）────────────────

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 附件消息（author='attachment'，subtype=subtype，content=载荷）· 镜像 buildAttachmentMessage 契约。 */
    private static ChatMessageDto attachmentMsg(String id, String subtype, String content) {
        return new ChatMessageDto(id, SESSION, Role.user, PostCompactAttachmentRestorer.ATTACHMENT_AUTHOR,
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false,
            subtype);
    }

    // ════════════════════════════════════════════════════════════════════
    // CS-1 · restoreSkillStateFromMessages（CC conversationRecovery.ts:382-403）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CS-1: 全量压缩输出 → resume（fresh state）→ restore 重建 invokedSkills（agentId=null 主会话）")
    void restoreRebuildsInvokedSkillsFromCompactedAttachment() {
        // ── Run 1: 会话调用 skill → 全量压缩输出 invoked_skills attachment（JSON payload）──
        AgentState run1 = new AgentState("test");
        run1.addInvokedSkill("skill-x", "/skills/skill-x.md", "Skill X setup instructions", null);
        ChatMessageDto compactedAttachment = PostCompactAttachmentRestorer.skillAttachmentForAgent(run1, null);
        assertThat(compactedAttachment).isNotNull();
        assertThat(compactedAttachment.subtype()).isEqualTo("invoked_skills");

        // ── 转录（含压缩输出附件 + 前后普通消息）──
        List<ChatMessageDto> transcript = new ArrayList<>();
        transcript.add(msg("u0", Role.user, "early"));
        transcript.add(msg("a0", Role.assistant, "assistant 0"));
        transcript.add(compactedAttachment);

        // ── Run 2: resume（新 run 的 fresh AgentState，invokedSkills 为空）──
        AgentState resumed = new AgentState("test");
        assertThat(resumed.getInvokedSkillsForAgent(null)).isEmpty();

        // 恢复（CC conversationRecovery.ts:382-403 语义 · P2-23 resume 标志：会话有历史 → resume=true）
        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed, transcript, new AtomicBoolean(false), true);

        var restored = resumed.getInvokedSkillsForAgent(null);
        assertThat(restored).hasSize(1);
        var info = restored.values().iterator().next();
        assertThat(info.skillName()).isEqualTo("skill-x");
        assertThat(info.skillPath()).isEqualTo("/skills/skill-x.md");
        assertThat(info.content()).isEqualTo("Skill X setup instructions");
        // CC :391 "Resume only happens for the main session, so agentId is null"
        assertThat(info.agentId()).isNull();
    }

    @Test
    @DisplayName("CS-1/M-36: 恢复条件 name/path/content 全真（CC conversationRecovery.ts:388-390），不完整条目跳过")
    void restoreSkipsIncompleteSkills() throws Exception {
        // 手工构造 payload：条目 1 content 空 → 跳过；条目 2 path 空 → 跳过；条目 3 完整 → 恢复
        String payload = "{\"type\":\"invoked_skills\",\"skills\":["
            + "{\"name\":\"no-content\",\"path\":\"/s/no-content.md\",\"content\":\"\"},"
            + "{\"name\":\"no-path\",\"path\":\"\",\"content\":\"content\"},"
            + "{\"name\":\"complete\",\"path\":\"/s/complete.md\",\"content\":\"full content\"}"
            + "]}";
        AgentState resumed = new AgentState("test");

        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed,
            List.of(attachmentMsg("att-1", "invoked_skills", payload)), new AtomicBoolean(false), true);

        var restored = resumed.getInvokedSkillsForAgent(null);
        assertThat(restored).hasSize(1);
        assertThat(restored.values().iterator().next().skillName()).isEqualTo("complete");
    }

    @Test
    @DisplayName("CS-1: 非附件消息 / 其他 subtype 消息不参与恢复；载荷非法 JSON 安全跳过")
    void restoreIgnoresNonInvokedSkillsMessages() {
        AgentState resumed = new AgentState("test");
        List<ChatMessageDto> mixed = new ArrayList<>();
        mixed.add(msg("u0", Role.user, "plain user message"));
        mixed.add(attachmentMsg("att-1", "file", "File: x\n\ncontent"));
        mixed.add(attachmentMsg("att-2", "skill_listing", "listing content"));
        mixed.add(attachmentMsg("att-3", "invoked_skills", "not-json{"));

        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed, mixed, new AtomicBoolean(false), true);

        assertThat(resumed.getInvokedSkillsForAgent(null)).isEmpty();
    }

    @Test
    @DisplayName("CS-1/suppress: skill_listing 附件 → suppressNextSkillListing 置真（CC conversationRecovery.ts:399-401 一次性 latch）")
    void restoreArmsSuppressNextSkillListingWhenSkillListingAttachmentPresent() {
        AgentState resumed = new AgentState("test");
        AtomicBoolean suppress = new AtomicBoolean(false);
        List<ChatMessageDto> transcript = List.of(
            attachmentMsg("att-listing", "skill_listing", "skills-available reminder"),
            attachmentMsg("att-skills", "invoked_skills",
                "{\"type\":\"invoked_skills\",\"skills\":[{\"name\":\"s1\",\"path\":\"/s/s1.md\",\"content\":\"c\"}]}"));

        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed, transcript, suppress, true);

        assertThat(suppress.get())
            .as("skill_listing 附件恢复时必须把 suppressNextSkillListing 置真（一次性 latch），"
                + "避免 resume 重复注入 ~600 token skills-available 清单")
            .isTrue();
        // invoked_skills 半段不受影响，仍正常恢复
        assertThat(resumed.getInvokedSkillsForAgent(null)).hasSize(1);
    }

    @Test
    @DisplayName("CS-1/suppress: 无 skill_listing 附件 → suppressNextSkillListing 不置真（保持正常注入）")
    void restoreDoesNotArmSuppressWithoutSkillListing() {
        AgentState resumed = new AgentState("test");
        AtomicBoolean suppress = new AtomicBoolean(false);
        List<ChatMessageDto> transcript = List.of(
            attachmentMsg("att-skills", "invoked_skills",
                "{\"type\":\"invoked_skills\",\"skills\":[{\"name\":\"s1\",\"path\":\"/s/s1.md\",\"content\":\"c\"}]}"));

        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed, transcript, suppress, true);

        assertThat(suppress.get())
            .as("无 skill_listing 附件时 suppressNextSkillListing 必须保持 false（正常注入 skills-available）")
            .isFalse();
        assertThat(resumed.getInvokedSkillsForAgent(null)).hasSize(1);
    }

    @Test
    @DisplayName("P2-23 返工: resume=false 即使转录含 invoked_skills/skill_listing 附件也跳过恢复（guard 短路）")
    void restoreWithResumeFalse_shortCircuitsEvenWhenTranscriptHasAttachments() {
        // 转录残留上一轮进程的附件（压缩注入的 invoked_skills + skill_listing）
        AgentState fresh = new AgentState("test");
        AtomicBoolean suppress = new AtomicBoolean(false);
        List<ChatMessageDto> transcript = List.of(
            attachmentMsg("att-listing", "skill_listing", "skills-available reminder"),
            attachmentMsg("att-skills", "invoked_skills",
                "{\"type\":\"invoked_skills\",\"skills\":[{\"name\":\"s1\",\"path\":\"/s/s1.md\",\"content\":\"c\"}]}"));

        // resume=false（全新会话首 run，LlmAgentLoop 排除当前 in-flight 用户消息后转录无历史）→
        // 即使转录含附件也必须短路：不把上一轮进程状态误植进 fresh 会话
        // （CC restoreSkillStateFromMessages 仅 resume 路径调用，conversationRecovery.ts:556-558）。
        PostCompactAttachmentRestorer.restoreSkillStateFromMessages(fresh, transcript, suppress, false);

        assertThat(fresh.getInvokedSkillsForAgent(null))
            .as("resume=false 时不得恢复 invoked_skills（guard 短路，CC 仅 resume 路径恢复）")
            .isEmpty();
        assertThat(suppress.get())
            .as("resume=false 时不得置真 suppressNextSkillListing（guard 短路，不重武装）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // CS-2 · partial 注入（CC compact.ts:950-953）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CS-2: partial 压缩成功路径经 holder 注入 invoked_skills 附件（registry holder 装配）")
    void partialCompactReinjectsInvokedSkills() {
        // 会话主 AgentState（已调用 skill）+ registry 装配
        AgentState state = new AgentState("test");
        state.addInvokedSkill("skill-y", "/skills/skill-y.md", "Skill Y instructions", null);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION_UUID, state);
        PartialCompactConversation.setSessionAgentStateRegistry(registry);

        try {
            // 消息：u0 → a0 → u1（pivot=2, up_to 摘要前两条）
            List<ChatMessageDto> all = new ArrayList<>();
            all.add(msg("u0", Role.user, "early 0"));
            all.add(msg("a0", Role.assistant, "assistant 0"));
            all.add(msg("u1", Role.user, "kept recent"));

            CompactConversationContext c = new CompactConversationContext();
            c.setSessionId(SESSION);
            c.setAgentId("main");
            c.setSummaryProducer((messages, prompt, preTokens) ->
                new CompactConversation.SummaryResult("summary ok",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)));
            c.setOnCompactProgress(e -> { });

            CompactionResult result = PartialCompactConversation.partialCompactConversation(
                all, 2, c, null, CompactPrompt.Direction.UP_TO);

            // 附件列表含 subtype='invoked_skills'（CC compact.ts:950-953 注入位）
            ChatMessageDto skillAtt = result.attachments().stream()
                .filter(a -> a != null && "invoked_skills".equals(a.subtype()))
                .findFirst()
                .orElse(null);
            assertThat(skillAtt).as("partial 压缩后应注入 invoked_skills 附件").isNotNull();
            assertThat(skillAtt.content()).contains("skill-y");
            assertThat(skillAtt.content()).contains("/skills/skill-y.md");
        } finally {
            PartialCompactConversation.setSessionAgentStateRegistry(null);
        }
    }

    /**
     * [ALIGN-COMP-1 r2 P1] 生产 sessionId 格式（"sess-xxxxxxxx"）下 partial 注入必须真实命中。
     *
     * <p><b>WHY（reflection P1 修复回归钉死）</b>: 上一轮测试用裸 UUID 作 ctx.sessionId，
     * 掩盖了生产路径失配——REST 路径变量是 {@code "sess-xxxxxxxx"}（SessionService.generateId
     * 前缀格式），buildContext 未归一化时 step 13 populate 的 {@code UUID.fromString} 必抛
     * IllegalArgumentException → catch → 跳过，partial 压缩生产恒不注入 invoked_skills
     * （M-33 缺口未闭合）。本测试走 {@link PartialCompactService#partialCompact} 全链路
     * （真实 buildContext 归一化），registry 键 = {@link ChatService#parseSessionUuid}
     * 归一 UUID（与 LlmAgentLoop 主会话注册键同函数一致），断言重组消息含 invoked_skills
     * 附件 —— 裸 UUID 无法通过本用例。
     */
    @Test
    @DisplayName("CS-2/P1: 'sess-' 前缀 sessionId（registry 键为归一 UUID）经服务层全链路注入 invoked_skills 附件")
    void partialCompactSessPrefixedSessionId_reinjectsInvokedSkills() {
        // 生产格式 sessionId："sess-" + 8 hex（SessionService.generateId 前缀格式）
        // [session-id-short] registry 键 = short 直键（不再 parseSessionUuid 归一化）
        String sessSessionId = "sess-ab12cd34";

        AgentState state = new AgentState("test");
        state.addInvokedSkill("skill-sess", "/skills/skill-sess.md", "Skill sess instructions", null);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(sessSessionId, state);
        PartialCompactConversation.setSessionAgentStateRegistry(registry);
        try {
            MessageService messageService = mock(MessageService.class);
            SessionService sessionService = mock(SessionService.class);
            StreamCompactSummary summary = mock(StreamCompactSummary.class);
            // partialCompact 走 listForResume（续聊加载历史通道，S1 中断语义漏斗）——mock 该调用
            when(messageService.listForResume(anyString())).thenReturn(fourMessages(sessSessionId));
            when(messageService.replaceSessionMessages(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));
            // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）
            when(summary.summarize(anyString(), anyList()))
                .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
            PartialCompactService svc = new PartialCompactService(
                messageService, sessionService, summary, registry, null, null);

            PartialCompactResponse resp = svc.partialCompact(sessSessionId,
                new PartialCompactRequest("u1", PartialCompactRequest.Direction.UP_TO, null));

            // 重组消息含 subtype='invoked_skills' 附件（CC compact.ts:950-953 注入位）
            ChatMessageDto skillAtt = resp.messages().stream()
                .filter(m -> m != null && "invoked_skills".equals(m.subtype()))
                .findFirst()
                .orElse(null);
            assertThat(skillAtt).as("'sess-' 前缀 sessionId 下 partial 压缩必须注入 invoked_skills 附件")
                .isNotNull();
            assertThat(skillAtt.content()).contains("skill-sess");
            assertThat(skillAtt.content()).contains("/skills/skill-sess.md");
        } finally {
            PartialCompactConversation.setSessionAgentStateRegistry(null);
        }
    }

    /** 无 boundary 会话：[u0, a0, u1, a1]（镜像 PartialCompactServiceTest.fourMessages，sessionId 参数化）。 */
    private static List<ChatMessageDto> fourMessages(String sessionId) {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(new ChatMessageDto("u0", sessionId, Role.user, "user",
            "content-u0", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false));
        list.add(new ChatMessageDto("a0", sessionId, Role.assistant, "assistant",
            "content-a0", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false));
        list.add(new ChatMessageDto("u1", sessionId, Role.user, "user",
            "content-u1", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false));
        list.add(new ChatMessageDto("a1", sessionId, Role.assistant, "assistant",
            "content-a1", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false));
        return list;
    }

    // ════════════════════════════════════════════════════════════════════
    // CS-1 + CS-2 · 端到端（全量压缩 → resume → partial 压缩）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端: 全量压缩 → resume → partial 压缩，skill 内容两轮压缩均存活")
    void skillContentSurvivesCompactThenResumeThenPartialCompact() throws Exception {
        // ── Run 1: 会话调用 skill → 全量压缩 → 转录含 invoked_skills attachment ──
        AgentState run1 = new AgentState("test");
        run1.addInvokedSkill("skill-z", "/skills/skill-z.md", "Skill Z setup", null);
        ChatMessageDto fullCompactAttachment = PostCompactAttachmentRestorer.skillAttachmentForAgent(run1, null);
        List<ChatMessageDto> persistedTranscript = new ArrayList<>();
        persistedTranscript.add(msg("u0", Role.user, "early"));
        persistedTranscript.add(fullCompactAttachment);

        // ── Run 2: resume（fresh AgentState + registry 重注册新 run 状态，invokedSkills 为空）──
        AgentState resumed = new AgentState("test");
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION_UUID, resumed);
        PartialCompactConversation.setSessionAgentStateRegistry(registry);
        try {
            // 没有恢复 → populate 无数据源 → 第二次压缩丢失 skill（CC javadoc 场景）
            // 恢复后 → populate 重新注入
            PostCompactAttachmentRestorer.restoreSkillStateFromMessages(resumed, persistedTranscript, new AtomicBoolean(false), true);

            // ── Run 2 触发 partial 压缩（加载的转录 + 新消息）──
            List<ChatMessageDto> all = new ArrayList<>(persistedTranscript);
            all.add(msg("u1", Role.user, "new work"));

            CompactConversationContext c = new CompactConversationContext();
            c.setSessionId(SESSION);
            c.setAgentId("main");
            c.setSummaryProducer((messages, prompt, preTokens) ->
                new CompactConversation.SummaryResult("summary ok",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)));
            c.setOnCompactProgress(e -> { });

            CompactionResult result = PartialCompactConversation.partialCompactConversation(
                all, 2, c, null, CompactPrompt.Direction.FROM);

            ChatMessageDto skillAtt = result.attachments().stream()
                .filter(a -> a != null && "invoked_skills".equals(a.subtype()))
                .findFirst()
                .orElse(null);
            assertThat(skillAtt).as("resume 后 partial 压缩应再次注入 invoked_skills 附件").isNotNull();

            JsonNode root = MAPPER.readTree(skillAtt.content());
            JsonNode skills = root.path("skills");
            assertThat(skills.isArray()).isTrue();
            assertThat(skills).anySatisfy(s -> {
                assertThat(s.path("name").asText()).isEqualTo("skill-z");
                assertThat(s.path("path").asText()).isEqualTo("/skills/skill-z.md");
                assertThat(s.path("content").asText()).isEqualTo("Skill Z setup");
            });
        } finally {
            PartialCompactConversation.setSessionAgentStateRegistry(null);
        }
    }
}
