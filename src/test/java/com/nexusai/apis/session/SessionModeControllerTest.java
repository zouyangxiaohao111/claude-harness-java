package com.nexusai.apis.session;

import com.nexusai.domain.session.SessionModeService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionModeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话模式端点（V329）· 统一返回体 + 数字 code（用户裁定）。
 *
 * <h2>WHY</h2>
 * 前端成功判据是 {@code success === true && code === 0}；业务失败（无渠道 / Q8 冲突）
 * 必须仍是 HTTP 200 + {@code success:false} + 数字码，否则前端只能拿到笼统的 HTTP 错误。
 * 变异点：抛异常走 500 / 成功不回 CommonResult → 用例 RED。
 */
class SessionModeControllerTest {

    private SessionModeController controller;
    private SessionModeService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SessionModeController();
        service = mock(SessionModeService.class);
        ReflectionTestUtils.setField(controller, "sessionModeService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static SessionModeDto dto(boolean unattended, boolean brief, int channels) {
        return new SessionModeDto("sess-1", unattended, brief, channels, channels > 0);
    }

    @Test
    @DisplayName("GET /mode → 200 + CommonResult{code:0, success:true, data}")
    void getReturnsCommonResult() throws Exception {
        when(service.getState("sess-1")).thenReturn(dto(false, false, 0));

        mockMvc.perform(get("/api/v1/sessions/sess-1/mode"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.unattended").value(false))
            .andExpect(jsonPath("$.data.canEnableUnattended").value(false));
    }

    @Test
    @DisplayName("PUT unattended 无渠道 → HTTP 200 + code 400 + success:false（业务失败不是 5xx）")
    void enableWithoutChannelReturnsBusinessError() throws Exception {
        when(service.setUnattended("sess-1", true))
            .thenThrow(new ValidationException(SessionModeService.NEED_CHANNEL_MESSAGE));
        when(service.getState("sess-1")).thenReturn(dto(false, false, 0));

        mockMvc.perform(put("/api/v1/sessions/sess-1/mode/unattended")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.msg").value(SessionModeService.NEED_CHANNEL_MESSAGE));
    }

    @Test
    @DisplayName("PUT brief 在无人值守下关闭 → HTTP 200 + code 409 + 状态不变（Q8）")
    void briefOffWhileUnattendedReturnsConflict() throws Exception {
        when(service.setBrief("sess-1", false))
            .thenThrow(new IllegalStateException(SessionModeService.BRIEF_CONFLICT_MESSAGE));
        when(service.getState("sess-1")).thenReturn(dto(true, true, 1));

        mockMvc.perform(put("/api/v1/sessions/sess-1/mode/brief")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data.unattended").value(true))
            .andExpect(jsonPath("$.data.brief").value(true));
    }

    @Test
    @DisplayName("缺 enabled 字段 → code 400，且不调用服务（不把缺字段当 false）")
    void missingEnabledIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/sessions/sess-1/mode/unattended")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
        verify(service, never()).setUnattended(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
