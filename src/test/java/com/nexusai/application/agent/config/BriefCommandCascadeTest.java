package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.model.session.dto.SessionModeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Q8] /brief 命令路径：命令没有弹窗能力 ⇒ 拒绝并给可执行指引，且不改任何状态。
 *
 * <p>WHY：界面可以弹确认框，但斜杠命令不能。若让它静默走级联，用户打一条命令就退出了
 * 无人值守，没有任何确认；若只回「状态冲突」又不告诉用户下一步做什么，用户会卡住。
 */
@DisplayName("[Q8] /brief 命令拒绝并给指引")
class BriefCommandCascadeTest {

    @Test
    @DisplayName("无人值守开启时 /brief off ⇒ 返回指引文案，不调用级联写")
    void briefOffUnderUnattendedReturnsGuidance() throws Exception {
        SessionModeService svc = mock(SessionModeService.class);
        when(svc.getState("sess-1")).thenReturn(new SessionModeDto("sess-1", true, true, 1, true));
        when(svc.setBrief("sess-1", false))
            .thenThrow(new IllegalStateException(SessionModeService.BRIEF_CONFLICT_MESSAGE));

        CommandRegistrationConfig cfg = new CommandRegistrationConfig();
        ReflectionTestUtils.setField(cfg, "sessionModeService", svc);
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        Method register = CommandRegistrationConfig.class
            .getDeclaredMethod("registerBriefHandler", UserInputDispatcher.class);
        register.setAccessible(true);
        register.invoke(cfg, dispatcher);

        UserInputDispatcher.LocalCommandResult result = dispatcher.dispatchResult("/brief", "sess-1", null);

        assertThat(result).isNotNull();
        assertThat(result.value())
            .as("命令路径必须给可执行指引（界面关闭 / 界面确认），而不是只报冲突")
            .isEqualTo(SessionModeService.BRIEF_COMMAND_CONFLICT_MESSAGE);
        assertThat(result.value()).doesNotContain("Q8").doesNotContain("级联");
        verify(svc, never()).setBrief(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean());
    }
}
