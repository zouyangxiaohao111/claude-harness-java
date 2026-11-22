package com.nexusai.application.schedule;

import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.model.session.dto.SessionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [V330] 第3类：新会话执行必须绑渠道 + 写 Brief/无人值守；失败不留壳会话。 */
class ScheduleSessionResolverCronTypesTest {

    private ScheduleSessionResolver resolver;
    private SessionService sessionService;
    private ImChatService imChatService;
    private SessionModeService sessionModeService;

    @BeforeEach
    void setUp() {
        resolver = new ScheduleSessionResolver();
        sessionService = mock(SessionService.class);
        imChatService = mock(ImChatService.class);
        sessionModeService = mock(SessionModeService.class);
        com.nexusai.domain.project.ProjectService projectService =
            mock(com.nexusai.domain.project.ProjectService.class);
        ProjectDto project = mock(ProjectDto.class);
        when(project.id()).thenReturn("proj-cron");
        when(projectService.register(any())).thenReturn(project);
        SessionDto created = mock(SessionDto.class);
        when(created.id()).thenReturn("sess-new");
        when(sessionService.create(any())).thenReturn(created);
        ReflectionTestUtils.setField(resolver, "sessionService", sessionService);
        ReflectionTestUtils.setField(resolver, "projectService", projectService);
        ReflectionTestUtils.setField(resolver, "imChatService", imChatService);
        ReflectionTestUtils.setField(resolver, "sessionModeService", sessionModeService);
    }

    private static ScheduleDto dto(SessionMode mode, List<String> chats) {
        return new ScheduleDto(
            "sch-1", "任务", com.nexusai.model.schedule.dto.ScheduleKind.cron,
            "0 9 * * *", null, null, "跑", "desc",
            null, null, com.nexusai.model.schedule.dto.ScheduleScope.DURABLE,
            null, null, null, mode, chats, null, null);
    }

    @Test
    void newEachBindsChatsAndTurnsOnUnattended() {
        String id = resolver.resolve(dto(SessionMode.NEW_EACH, List.of("imch-1")), "quartz");

        assertThat(id).isEqualTo("sess-new");
        verify(imChatService).bind("imch-1", "sess-new", "auto");
        verify(sessionModeService).setUnattended("sess-new", true);
        verify(sessionService, never()).delete(anyString());
    }

    @Test
    void bindFailureDeletesNewSessionAndReportsFailure() {
        doThrow(new IllegalStateException("bind boom"))
            .when(imChatService).bind(eq("imch-1"), anyString(), anyString());

        String id = resolver.resolve(dto(SessionMode.NEW_EACH, List.of("imch-1")), "quartz");

        assertThat(id).isNull();
        verify(sessionService).delete("sess-new");
        verify(sessionModeService, never()).setUnattended(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void missingChannelFailsWithoutCreatingShellSession() {
        String id = resolver.resolve(dto(SessionMode.NEW_EACH, List.of()), "quartz");

        assertThat(id).isNull();
        verify(sessionService).delete("sess-new");
    }
}
