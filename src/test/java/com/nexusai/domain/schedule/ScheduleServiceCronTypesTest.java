package com.nexusai.domain.schedule;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** [V330] 三类 cron：无头 / 新会话执行必须有渠道；绑定历史的 REUSE 不要求。 */
class ScheduleServiceCronTypesTest {

    private ScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleService();
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        when(mapper.selectAll()).thenReturn(List.of());
        when(mapper.selectOneById(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
    }

    private static ScheduleCreateRequest request(SessionMode mode, List<String> chats) {
        return new ScheduleCreateRequest(
            "测试任务",                 // name
            ScheduleKind.cron,          // kind
            "0 9 * * *",                // cron
            null,                       // intervalSeconds
            null,                       // runAt
            "跑一下",                   // command
            "desc",                     // description
            ScheduleScope.SESSION,      // scope
            "sess-1",                   // sessionId
            null,                       // agentId
            null,                       // boundProject
            mode,                       // sessionMode
            chats,                      // notifyChats
            null,                       // notifyChannelIds
            null,                       // mainThreadAgent
            null);                      // id
    }

    @Test
    void noneWithoutChannelIsRejected() {
        assertThatThrownBy(() -> service.create(request(SessionMode.NONE, List.of())))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("需先配置渠道");
    }

    @Test
    void newEachWithoutChannelIsRejected() {
        assertThatThrownBy(() -> service.create(request(SessionMode.NEW_EACH, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("需先配置渠道");
    }

    @Test
    void blankChannelDoesNotCount() {
        assertThatThrownBy(() -> service.create(request(SessionMode.NEW_ONCE, List.of(" "))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("需先配置渠道");
    }

    @Test
    void reuseWithoutChannelPassesChannelGate() {
        // 第1类：结果写回原会话，不要求渠道 ⇒ 不能被渠道门拦住（后续装配缺依赖导致的异常不算）。
        try {
            service.create(request(SessionMode.REUSE, null));
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).doesNotContain("需先配置渠道");
        }
    }
}
