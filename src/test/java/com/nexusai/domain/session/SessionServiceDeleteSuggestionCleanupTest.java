package com.nexusai.domain.session;

import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore;
import com.nexusai.application.agent.team.InProcessTeammateTaskRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.chat.ChatService;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SessionService.delete} 会话删除清理链路意图测试（DEL-SH-01 + S4 + A1 + A2）.
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：会话删除时挂有多条 best-effort 清理接线，各接线若被
 * 「只定义不调用」静默移除，测试必须变红：
 * <ul>
 *   <li><b>DEL-SH-01</b>：{@code SkillImprovementSuggestionStore.removeBySession} —— 旧 30 分钟
 *       TTL + daemon 清扫线程已删（CC {@code skillImprovement.ts} 无 TTL，suggestion 随会话消亡），
 *       若未接入删除链路则该 session 的 suggestion 条目永久滞留。</li>
 *   <li><b>S4</b>：{@code ChatService.cancelSession} 先 cancel 在飞 turn（对齐 CC onCancel
 *       REPL.tsx:2147），否则已删会话 in-flight turn 继续向已删 topic 推 STOMP。</li>
 *   <li><b>A1</b>：{@code TeamHelpers.cleanupSessionTeams} 清理本会话孤儿 team 目录（对齐 CC
 *       init.ts:224-229 gracefulShutdown → cleanupSessionTeams）。A1 原缺陷恰是「0 生产调用」
 *       静默死接线——本测试锁定 {@code SessionService.delete} 为唯一生产触发点。</li>
 *   <li><b>A2</b>：{@code SpawnInProcess.registry().cleanupSession} abort 本会话 in-process
 *       teammate（对齐 CC spawnInProcess.ts:184-188 registerCleanup abort）。</li>
 * </ul>
 * 各 best-effort 依赖均 {@code @Autowired(required=false)}，缺失时必须静默跳过、不阻塞删 DB 主流程。
 */
class SessionServiceDeleteSuggestionCleanupTest {

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private SessionService newService(SessionMapper sessionMapper,
                                      MessageMapper messageMapper,
                                      SessionFileMapper sessionFileMapper,
                                      ChatService chatService,
                                      SkillImprovementSuggestionStore suggestionStore,
                                      SimpMessagingTemplate wsTemplate,
                                      TeamHelpers teamHelpers,
                                      SpawnInProcess spawnInProcess) throws Exception {
        SessionService svc = new SessionService();
        inject(svc, "sessionMapper", sessionMapper);
        inject(svc, "messageMapper", messageMapper);
        inject(svc, "sessionFileMapper", sessionFileMapper);
        inject(svc, "chatService", chatService);
        inject(svc, "suggestionStore", suggestionStore);
        inject(svc, "wsTemplate", wsTemplate);
        inject(svc, "teamHelpers", teamHelpers);
        inject(svc, "spawnInProcess", spawnInProcess);
        return svc;
    }

    private SessionRecord sessionRecord(String sessionId) {
        SessionRecord rec = new SessionRecord();
        rec.setId(sessionId);
        return rec;
    }

    @Test
    @DisplayName("DEL-SH-01: 会话删除时按写入侧同一键空间清理该 session 的 suggestion 条目")
    void delete_removesSuggestionBySession() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore,
                null, null, null).delete(sessionId);

        // [session-id-short] 写入侧与删除侧键空间已统一 short 直键（SkillImprovementHook.writeSuggestionToStore
        // 用 ctx.sessionId()=short，删除侧 removeBySession(id) 同键，裸 equals 清掉条目）。
        verify(suggestionStore).removeBySession(eq(sessionId));
        // 会话关闭与 DB 删除主流程不受影响。
        verify(chatService).closeSession(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("DEL-SH-01: suggestionStore 未注入（required=false）时静默跳过，不阻塞会话删除主流程")
    void delete_suggestionStoreNull_doesNotBlockMainFlow() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        // suggestionStore = null（@Autowired(required=false) 允许缺失），删除主流程须照常完成。
        assertThatCode(() -> newService(sessionMapper, messageMapper, sessionFileMapper, chatService, null,
                null, null, null).delete(sessionId)).doesNotThrowAnyException();
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("S4: 删除会话先 cancel 在飞 turn（cancelSession）再清理调度任务（closeSession），顺序锁定")
    void delete_cancelsInflightBeforeScheduleCleanup() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        TeamHelpers teamHelpers = mock(TeamHelpers.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore,
                wsTemplate, teamHelpers, null).delete(sessionId);

        // WHY（S4 探查）：cancel 与调度清理若乱序，已删会话 in-flight turn 会与 closeSession 竞态
        // —— cancel-first 对齐 CC onCancel REPL.tsx:2147（task.cancel + 推送 cancelled 事件），
        // 调度清理（closeSession）必须在 cancel 之后，否则先清调度仍可能残留 in-flight 推送。
        InOrder inOrder = inOrder(chatService);
        inOrder.verify(chatService).cancelSession(sessionId, wsTemplate);
        inOrder.verify(chatService).closeSession(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("S4: wsTemplate 未注入（required=false）时跳过 cancel，但 closeSession + DB 删除照常")
    void delete_wsTemplateNull_skipsCancelButStillDeletes() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);
        TeamHelpers teamHelpers = mock(TeamHelpers.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        // wsTemplate = null（@Autowired(required=false)）：cancel 分支整块跳过（cancel 需 wsTemplate
        // 向已删 topic 推 cancelled/status=idle 事件），但 closeSession 调度清理 + DB 删除须照常。
        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore,
                null, teamHelpers, null).delete(sessionId);

        verify(chatService, never()).cancelSession(any(), any());
        verify(chatService).closeSession(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("A1: 删除会话时清理该会话登记的孤儿 team 目录（cleanupSessionTeams 生产触发点）")
    void delete_callsCleanupSessionTeams() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        TeamHelpers teamHelpers = mock(TeamHelpers.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore,
                wsTemplate, teamHelpers, null).delete(sessionId);

        // WHY（A1 探查）：A1 原缺陷 = cleanupSessionTeams 0 生产调用（静默死接线），orphan
        // config.json/inboxes/tasks 永久泄漏。本测试锁定 SessionService.delete 是唯一生产触发点；
        // 若接线被移除（删调用不删方法），本测试变红。
        verify(teamHelpers).cleanupSessionTeams(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("A2: 删除会话时 abort 本会话 in-process teammate（registry.cleanupSession 生产触发点）")
    void delete_abortsTeammatesOfSession() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        TeamHelpers teamHelpers = mock(TeamHelpers.class);
        SpawnInProcess spawnInProcess = mock(SpawnInProcess.class);
        InProcessTeammateTaskRegistry registry = mock(InProcessTeammateTaskRegistry.class);
        when(spawnInProcess.registry()).thenReturn(registry);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore,
                wsTemplate, teamHelpers, spawnInProcess).delete(sessionId);

        // WHY（A2 探查）：teammate runner 线程轮循 isAborted；若会话删除不 abort，teammate 在会话
        // 已删后继续跑并向已删 topic 推消息（对齐 CC spawnInProcess.ts:184-188 registerCleanup abort）。
        // 锁定 registry.cleanupSession 生产调用。
        verify(registry).cleanupSession(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("S4/A1/A2: 清理类依赖全部未注入时静默跳过，不阻塞会话删除主流程")
    void delete_cleanupDepsNull_doesNotBlockMainFlow() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);

        String sessionId = "sess-abc12345";
        when(sessionMapper.selectOneById(sessionId)).thenReturn(sessionRecord(sessionId));

        // wsTemplate / teamHelpers / spawnInProcess / suggestionStore 均 required=false；缺失时
        // cancel/cleanup 分支整块跳过，closeSession + DB 删除主流程必须照常完成（best-effort 契约）。
        assertThatCode(() -> newService(sessionMapper, messageMapper, sessionFileMapper, chatService, null,
                null, null, null).delete(sessionId)).doesNotThrowAnyException();
        verify(chatService).closeSession(sessionId);
        verify(sessionMapper).deleteById(sessionId);
    }
}
