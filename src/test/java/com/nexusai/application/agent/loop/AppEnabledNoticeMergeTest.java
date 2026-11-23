package com.nexusai.application.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.app.AppEnablement;
import com.nexusai.application.agent.config.SessionEnabledAppsConfig;
import com.nexusai.application.agent.tool.SessionToolVisibility;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动通知「发送前合并」回归（真机重复通知）：
 * 连续多次 PATCH enabledApps <b>不</b>发通知；下一次发送只按「最终状态 vs 历史最后一条」发 1 条；
 * 净变化为 0 ⇒ 0 条。
 */
@DisplayName("app_enabled_change · 多次 PATCH 合并为下一次发送的 1 条")
class AppEnabledNoticeMergeTest {

    private static final String SID = "sess-merge";
    private final ObjectMapper om = new ObjectMapper();
    private SessionService sessions;
    private AgentState state;

    @BeforeEach
    void setUp() {
        AppEnablement.reset();
        SessionRecord row = new SessionRecord();
        row.setId(SID);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(row);
        SessionEnabledAppsConfig.setSessionMapper(mapper);
        sessions = new SessionService();
        ReflectionTestUtils.setField(sessions, "sessionMapper", mapper);

        state = new AgentState("sys", SID, null);
        state.appendMessage(new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), SID, Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false));
    }

    @AfterEach
    void tearDown() {
        SessionEnabledAppsConfig.reset();
        AppEnablement.reset();
    }

    private void patch(List<String> apps) {
        sessions.update(SID, new SessionUpdateRequest(null, null, null, null, null, null, null,
            null, null, null, om.valueToTree(apps)));
    }

    private void emitOnce() {
        AgentLoopContext.maybeEmitAppEnabledChange(state, QuerySource.REPL_MAIN_THREAD,
            SessionToolVisibility.effectiveEnabledAppKeys(SID), Map.of());
    }

    private long enabledNotices() {
        return state.rawMessages().stream()
            .filter(m -> m != null && "app_enabled_change".equals(m.subtype()))
            .count();
    }

    @Test
    @DisplayName("+A / -A / +B 三次 PATCH 后发送：1 条，内容只含最终 +B（无中间 A）")
    void multiplePatches_mergeToFinalOnce() {
        int before = state.rawMessages().size();
        patch(List.of("A"));
        patch(List.of());
        patch(List.of("B"));

        // PATCH 本身不落任何通知
        assertThat(state.rawMessages()).hasSize(before);

        emitOnce();

        assertThat(enabledNotices()).isEqualTo(1);
        String content = state.rawMessages().get(state.rawMessages().size() - 1).content();
        assertThat(content).contains("+启动 B").contains("当前已启动：B");
        assertThat(content).doesNotContain("A");
    }

    @Test
    @DisplayName("净零（+A 后 -A）：下一次发送 0 条")
    void netZero_noNotice() {
        int before = state.rawMessages().size();
        patch(List.of("A"));
        patch(List.of());

        emitOnce();

        assertThat(state.rawMessages()).hasSize(before);
        assertThat(enabledNotices()).isZero();
    }

    @Test
    @DisplayName("同状态重复发送：仍只有历史中的 1 条（比较键=排序后 enabled key）")
    void sameState_afterEmit_noDuplicate() {
        patch(List.of("B", "A"));
        emitOnce();
        int afterFirst = state.rawMessages().size();

        emitOnce();

        assertThat(state.rawMessages()).hasSize(afterFirst);
        assertThat(enabledNotices()).isEqualTo(1);
    }
}
