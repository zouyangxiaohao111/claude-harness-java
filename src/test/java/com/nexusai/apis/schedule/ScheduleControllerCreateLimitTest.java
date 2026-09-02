package com.nexusai.apis.schedule;

import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.MaxJobsExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [CRON-B4-3 决策 #13] REST 创建超限 → 409 + errorCode "3"（对齐工具路径 errorCode3）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：CC CronCreateTool.ts:97-104 validateInput 在任务数
 * >= MAX_JOBS(50) 时返回 {@code {result:false, message:'Too many scheduled jobs (max 50).
 * Cancel one first.', errorCode:3}}。工具路径（CronCreateTool.validateInput）已对齐 errorCode3；
 * REST 直达用户路径（POST /api/v1/schedules）旧实现抛 {@link IllegalStateException} → HTTP 500，
 * 偏离 CC 语义。本测试钉死：create 超限必须 409 + RFC 7807 Problem 带 errorCode:"3" + CC 消息，
 * 使 REST 与工具路径在「超限即 errorCode3」语义上一致（决策 #13）。
 */
class ScheduleControllerCreateLimitTest {

    private ScheduleController controller;
    private ScheduleService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ScheduleController();
        service = mock(ScheduleService.class);
        ReflectionTestUtils.setField(controller, "scheduleService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("POST /api/v1/schedules 任务数超限 → 409 + errorCode:\"3\" + CC 消息（对齐 CronCreateTool.ts:101）")
    void createOverLimit_returns409WithErrorCode3() throws Exception {
        when(service.create(any())).thenThrow(new MaxJobsExceededException(
            "Too many scheduled jobs (max 50). Cancel one first."));

        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"over\",\"kind\":\"cron\",\"cron\":\"0 9 * * *\","
                    + "\"command\":\"echo\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.errorCode").value("3"))
            .andExpect(jsonPath("$.detail").value("Too many scheduled jobs (max 50). Cancel one first."));
    }
}
