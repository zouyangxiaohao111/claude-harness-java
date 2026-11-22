package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.chat.SlashCommandInterceptor;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用户已批准：命令经真实本地派发写同一会话状态，不生成额外模型轮次。 */
class CommandRegistrationBriefExecutionTest {
    private final Map<String, SessionRecord> rows = new HashMap<>();
    private CommandRegistrationConfig config;
    private SlashCommandInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        BundledSkills.clear();
        SessionModeState.reset();
        SessionToolDisableConfig.reset();
        for (String id : List.of("sess-a", "sess-b")) {
            SessionRecord row = new SessionRecord();
            row.setId(id);
            rows.put(id, row);
        }
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenAnswer(inv -> rows.get(inv.getArgument(0)));
        SessionModeState.setSessionMapper(mapper);
        SessionToolDisableConfig.setSessionMapper(mapper);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getRecord(anyString())).thenAnswer(inv -> rows.get(inv.getArgument(0)));
        doAnswer(inv -> {
            rows.get(inv.<String>getArgument(0)).setBriefMode(inv.<Boolean>getArgument(1) ? 1 : 0);
            return null;
        }).when(sessions).setBriefMode(anyString(), anyBoolean());
        ImChannelService channels = mock(ImChannelService.class);
        when(channels.list()).thenReturn(List.of());
        SessionModeService modes = new SessionModeService();
        ReflectionTestUtils.setField(modes, "sessionService", sessions);
        ReflectionTestUtils.setField(modes, "channelService", channels);
        config = new CommandRegistrationConfig();
        // 旧生产类还没有依赖槽；仍让旧代码跑到可观测断言，而不是因测试注入失败而假红。
        for (var field : CommandRegistrationConfig.class.getDeclaredFields()) {
            if (field.getType() == SessionModeService.class) {
                field.setAccessible(true);
                field.set(config, modes);
            }
        }
        config.commandBundledRegistration();
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandLocalSlashRegistration(dispatcher, null, null);
        interceptor = new SlashCommandInterceptor(new SkillRegistry(""), dispatcher);
    }

    @AfterEach
    void tearDown() {
        BundledSkills.clear();
        SessionModeState.reset();
        SessionToolDisableConfig.reset();
    }

    @Test
    void commandIsDiscoverableAndUsesExecutableLocalRoute() {
        var command = BundledSkills.getAll().stream().filter(c -> "brief".equals(c.getName())).findFirst().orElseThrow();
        assertThat(command.isCommandEnabled()).isTrue();
        assertThat(command.getType()).isEqualTo("local");
    }

    @Test
    void twoCommandsPersistEnableThenDisableWithoutModelQuery() {
        var enabled = interceptor.intercept("sess-a", "msg-1", "/brief", null, null);
        assertThat(enabled.handled()).isTrue();
        assertThat(enabled.shouldQuery()).isFalse();
        assertThat(enabled.resultText()).isEqualTo("<local-command-stdout>Brief-only mode enabled</local-command-stdout>");
        assertThat(rows.get("sess-a").getBriefMode()).isEqualTo(1);
        assertThat(SessionModeState.isUnattended("sess-a")).isFalse();
        var disabled = interceptor.intercept("sess-a", "msg-2", "/brief", null, null);
        assertThat(disabled.shouldQuery()).isFalse();
        assertThat(disabled.resultText()).isEqualTo("<local-command-stdout>Brief-only mode disabled</local-command-stdout>");
        assertThat(rows.get("sess-a").getBriefMode()).isZero();
    }

    @Test
    void unattendedConflictReturnsExplanationAndDoesNotChangeState() {
        rows.get("sess-a").setBriefMode(1);
        rows.get("sess-a").setUnattendedMode(1);
        var result = interceptor.intercept("sess-a", "msg-3", "/brief", null, null);
        assertThat(result.shouldQuery()).isFalse();
        assertThat(result.resultText()).contains(SessionModeService.BRIEF_CONFLICT_MESSAGE);
        assertThat(rows.get("sess-a").getBriefMode()).isEqualTo(1);
        assertThat(rows.get("sess-a").getUnattendedMode()).isEqualTo(1);
    }

    @Test
    void toggleOnlyChangesAddressedSession() {
        interceptor.intercept("sess-a", "msg-4", "/brief", null, null);
        assertThat(SessionModeState.isBrief("sess-a")).isTrue();
        assertThat(rows.get("sess-b").getBriefMode()).isNull();
        assertThat(SessionModeState.isBrief("sess-b")).isFalse();
    }
}
