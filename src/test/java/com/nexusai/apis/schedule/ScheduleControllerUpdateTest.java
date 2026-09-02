package com.nexusai.apis.schedule;

import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.ScheduleUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FIX-3 / RV-C-03 G3/G4 · POST /api/v1/schedules/{id} 部分更新端点。
 *
 * <p><b>WHY (规则九 · 意图验证)</b>: RemoteTriggerTool update 对齐 CC
 * {@code RemoteTriggerTool.ts:120-126 update=POST base/{trigger_id}} 后，服务端必须提供
 * POST /{id} 端点承接（否则工具发 POST 撞 405，update 能力断链）。本测试钉死：POST /{id}
 * 必须返回 200 并委托 {@link ScheduleService#update}（partial-update），不复用 create 的
 * {@code @NotBlank name} 语义（update 允许只改 cron/description 不动 name）。
 */
class ScheduleControllerUpdateTest {

    private ScheduleController controller;
    private ScheduleService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ScheduleController();
        service = mock(ScheduleService.class);
        ReflectionTestUtils.setField(controller, "scheduleService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/schedules/{id} → 200 + scheduleService.update 调用（对齐 CC RemoteTriggerTool.ts:120-126）")
    void updateEndpoint_callsServiceUpdate() throws Exception {
        ScheduleDto updated = new ScheduleDto("sch-1", "renamed", ScheduleKind.cron, "0 9 * * *",
            null, null, "echo", "desc", null, null, ScheduleScope.DURABLE, null, null, null);
        when(service.update(eq("sch-1"), any(ScheduleUpdateRequest.class))).thenReturn(updated);

        mockMvc.perform(post("/api/v1/schedules/sch-1")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"renamed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("sch-1"))
            .andExpect(jsonPath("$.name").value("renamed"));

        verify(service).update(eq("sch-1"), any(ScheduleUpdateRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/schedules/{id} 只改 description 不动 name → 请求体不含 name（partial-update 语义，去 @NotBlank）")
    void updateEndpoint_partialUpdateOnlyDescription() throws Exception {
        ScheduleDto updated = new ScheduleDto("sch-2", "original", ScheduleKind.cron, "0 9 * * *",
            null, null, "echo", "new", null, null, ScheduleScope.DURABLE, null, null, null);
        when(service.update(eq("sch-2"), any(ScheduleUpdateRequest.class))).thenReturn(updated);

        mockMvc.perform(post("/api/v1/schedules/sch-2")
                .contentType(APPLICATION_JSON)
                .content("{\"description\":\"new\"}"))
            .andExpect(status().isOk());

        verify(service).update(eq("sch-2"), any(ScheduleUpdateRequest.class));
    }
}
