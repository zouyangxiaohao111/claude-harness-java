package com.nexusai.application.agent.config;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话级显式无人值守 / Brief 读取（V329 列）。
 *
 * <h2>WHY</h2>
 * 旧无人值守判据是 {@code im_chat.bound_session_id} 的隐式副作用（绑定渠道即摘 Ask）。
 * 本测试钉住新契约：<b>只有显式列</b> {@code sessions.unattended_mode = 1} 才算无人值守；
 * 读不到/未接线/读失败一律 false（fail-open，Ask 保持可用）。
 * 变异点：把判定改回「有绑定行即真」或「读失败按 true」→ 对应用例 RED。
 */
class SessionModeStateTest {

    @AfterEach
    void tearDown() {
        SessionModeState.reset();
    }

    private static SessionMapper mapperFor(SessionRecord record) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(record);
        return mapper;
    }

    @Test
    @DisplayName("unattended_mode=1 ⇒ 无人值守；brief_mode=0 ⇒ Brief 关")
    void explicitColumnsAreRead() {
        SessionRecord r = new SessionRecord();
        r.setId("sess-1");
        r.setUnattendedMode(1);
        r.setBriefMode(0);
        SessionModeState.setSessionMapper(mapperFor(r));

        assertThat(SessionModeState.isUnattended("sess-1")).isTrue();
        assertThat(SessionModeState.isBrief("sess-1")).isFalse();
    }

    @Test
    @DisplayName("列 null / 0 ⇒ 不开启（旧行按未开启）")
    void nullOrZeroIsOff() {
        SessionRecord r = new SessionRecord();
        r.setId("sess-1");
        SessionModeState.setSessionMapper(mapperFor(r));

        assertThat(SessionModeState.isUnattended("sess-1")).isFalse();
        assertThat(SessionModeState.isBrief("sess-1")).isFalse();

        r.setUnattendedMode(0);
        r.setBriefMode(0);
        assertThat(SessionModeState.isUnattended("sess-1")).isFalse();
        assertThat(SessionModeState.isBrief("sess-1")).isFalse();
    }

    @Test
    @DisplayName("未接线 / sessionId 空 ⇒ false（不能因为缺配置把会话判成无人值守）")
    void notWiredOrBlankIsFalse() {
        SessionModeState.reset();
        assertThat(SessionModeState.isUnattended("sess-1")).isFalse();
        SessionModeState.setSessionMapper(mapperFor(new SessionRecord()));
        assertThat(SessionModeState.isUnattended(null)).isFalse();
        assertThat(SessionModeState.isUnattended("  ")).isFalse();
    }

    @Test
    @DisplayName("读库抛异常 ⇒ false（fail-open，agent 不能失去提问能力）")
    void readFailureFailsOpen() {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenThrow(new IllegalStateException("db down"));
        SessionModeState.setSessionMapper(mapper);

        assertThat(SessionModeState.isUnattended("sess-1")).isFalse();
        assertThat(SessionModeState.isBrief("sess-1")).isFalse();
    }

    @Test
    @DisplayName("按会话隔离：A 无人值守不影响 B")
    void perSessionIsolation() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord a = new SessionRecord();
        a.setId("sess-A");
        a.setUnattendedMode(1);
        SessionRecord b = new SessionRecord();
        b.setId("sess-B");
        b.setUnattendedMode(0);
        when(mapper.selectOneById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return "sess-A".equals(id) ? a : "sess-B".equals(id) ? b : null;
        });
        SessionModeState.setSessionMapper(mapper);

        assertThat(SessionModeState.isUnattended("sess-A")).isTrue();
        assertThat(SessionModeState.isUnattended("sess-B")).isFalse();
    }
}
