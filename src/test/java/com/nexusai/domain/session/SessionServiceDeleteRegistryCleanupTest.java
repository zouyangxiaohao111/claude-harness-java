package com.nexusai.domain.session;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.SessionStartSeenRegistry;
import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [skill-listing-cc-align 2026-09-10 收尾轮] {@code SessionService.delete} 的两个注册表清理接线实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：上一轮补丁在会话删除路径新增了两处清理
 * （{@link SkillListingSentRegistry} 槽位回收 + {@link SessionAgentStateRegistry} AgentState 移除），
 * 但 {@code SessionServiceTest} 用 {@code new SessionService()} + 反射注入、两字段皆 null → 新逻辑被
 * 静默跳过，27 例全绿却一个字节都没执行（finding：delete 清理链零测试覆盖）。本类以<b>真实注册表</b>
 * 注入并断言删除后状态，钉死两处接线：
 * <ol>
 *   <li>skill_listing 槽位回收走<b>纯清理</b>入口（{@code removeSessionEntries}）—— 不残留 CLEARED
 *       （残留会让被删会话 key 复现时误整份重发，且长跑 JVM 无界增长）；</li>
 *   <li>AgentState 清理覆盖<b>双桶</b> —— sessions（String）+ 后台化 agents（UUID）；上一版只清
 *       sessions 桶 → 被后台化过的会话完整历史仍泄漏。</li>
 * </ol>
 * 删掉 SessionService.delete 里的任一处调用 → 对应断言 RED。
 */
@DisplayName("[skill-listing-cc-align] SessionService.delete 注册表清理接线")
class SessionServiceDeleteRegistryCleanupTest {

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
        SessionStartSeenRegistry.reset();
    }

    private static SessionService newService(String sessionId) {
        SessionService service = new SessionService();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord rec = new SessionRecord();
        rec.setId(sessionId);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(rec);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(service, "sessionFileMapper", mock(SessionFileMapper.class));
        // chatService/wsTemplate/spawnInProcess/teamHelpers/... 保持 null → delete 内各 best-effort 段跳过
        return service;
    }

    @Test
    @DisplayName("delete → skill_listing 槽位回收（纯清理，不残留 CLEARED）")
    void delete_clearsSkillListingEntriesWithoutClearedResidue() {
        String id = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        SessionService service = newService(id);

        // 该会话已 decided（主线程 + 子代理两槽）
        SkillListingSentRegistry.decide(id, "", List.of("commit"), false);
        SkillListingSentRegistry.decide(id, "sub-1", List.of("commit"), false);
        assertThat(SkillListingSentRegistry.isInitialized(id, "")).isTrue();

        service.delete(id);

        assertThat(SkillListingSentRegistry.isInitialized(id, ""))
            .as("删除会话必须回收主线程槽（防无界增长）；删掉 removeSessionEntries 调用即 RED").isFalse();
        assertThat(SkillListingSentRegistry.isInitialized(id, "sub-1"))
            .as("删除会话必须回收子代理槽").isFalse();
        // 未置 CLEARED：残留会让同一 key 的下一次 decide 走 /clear 分支整份重发（且永不被消费 → 无界增长）。
        assertThat(SkillListingSentRegistry.decide(id, "", List.of("commit"), true).names())
            .as("删除路径不得残留 CLEARED 类重发意图").isEmpty();
    }

    @Test
    @DisplayName("delete → SessionStartSeenRegistry 键回收（P2-11，防常驻 JVM 内键泄漏）")
    void delete_clearsSessionStartSeenKey() {
        String id = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        SessionService service = newService(id);

        // 该会话本进程已跑过 SessionStart（冷启动判据落位）
        assertThat(SessionStartSeenRegistry.markSeen(id))
            .as("前置：首次 markSeen 必须为冷（true）").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen(id))
            .as("前置：同进程第二次 markSeen 必须为热（false）—— 键已驻留").isFalse();

        service.delete(id);

        // WHY 该断言重要：键若不回收，长跑 JVM 下随「删过的会话数」无界累积（本表是进程级静态
        //   HashMap<String,Boolean>）；审计 P2-11 —— 本表此前全仓唯一移除点只有 /clear，
        //   会话删除路径独漏。CC 无对应动作（一进程一会话，会话结束即进程退出）。
        assertThat(SessionStartSeenRegistry.markSeen(id))
            .as("删除会话必须回收 SessionStart 判据键；删掉 SessionService.delete 里的 "
                + "SessionStartSeenRegistry.remove(id) 即 RED").isTrue();
    }

    @Test
    @DisplayName("delete → SessionAgentStateRegistry 双桶清理（sessions + 后台化 agents）")
    void delete_clearsBothAgentStateBuckets() {
        String id = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        SessionService service = newService(id);

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ReflectionTestUtils.setField(service, "sessionAgentStateRegistry", registry);

        AgentState mainState = new AgentState("sys", id, null);
        UUID bgAgentId = UUID.randomUUID();
        // 后台化主会话：MainSessionBackgroundService 以 agentId=agentUuid 注册（agents 桶），sessionId 相同。
        AgentState bgState = new AgentState("sys", id, bgAgentId);
        registry.register(id, mainState);
        registry.register(bgAgentId, bgState);
        assertThat(registry.size()).isEqualTo(2);

        service.delete(id);

        assertThat(registry.get(id))
            .as("sessions 桶主状态必须移除").isNull();
        assertThat(registry.getByAgentId(bgAgentId))
            .as("后台化主会话注册在 agents 桶（agentUuid 键）—— 只清 sessions 桶则本断言 RED（finding：只修了一半）")
            .isNull();
    }
}
