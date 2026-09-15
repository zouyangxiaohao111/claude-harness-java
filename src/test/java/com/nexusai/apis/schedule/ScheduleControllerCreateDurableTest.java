package com.nexusai.apis.schedule;

import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [cwd3 · 用户裁定 2026-09-15 步骤 1a] {@code POST /api/v1/schedules} 直建 DURABLE 的强制会话锚。
 *
 * <p><b>WHY（规则九 · 意图，不是行为）</b>：用户原话「REST 直建 DURABLE 时 <b>必须带上 sessionId
 * 没有 id 不允许建立</b>」+「有 sessionId ⇒ 必须能从它解析出 boundProject，解析不到 ⇒ 400」。
 * 这条不变量的意义是：<b>DURABLE 任务的项目锚不能由调用方指定</b>（否则可把任务锚到任意路径 ⇒
 * fire 时 cwd/权限/transcript 全落错项目），必须由服务端按会话推导。
 *
 * <p>若把这层判据删掉（RE-1a-1：判据改 {@code if (false)}），本类的 ①②③ 三条<b>必须全红</b>
 * （只红一条 ⇒ 反向实验本身不完整：①②③ 是三条<b>独立</b>入口 —— 缺 id / 假 id(哨兵) / 解析不到，
 * 各自守护一个不同的事）；④ 守护「锚由解析值决定」。
 */
class ScheduleControllerCreateDurableTest {

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

    @AfterEach
    void tearDown() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    /** 让 lookup 返回一个有效绑定（生产里由 DB 回源器给出）。 */
    private static void wireResolver(Path projectDir) {
        SessionProjectRoot.clearSession("sess-anchor-1");
        SessionProjectRoot.setDbResolver(sid -> "sess-anchor-1".equals(sid)
            ? SessionProjectRoot.Lookup.bound(projectDir.toString())
            : SessionProjectRoot.Lookup.unknown());
    }

    private static String body(String extra) {
        return "{\"name\":\"b-1a\",\"kind\":\"cron\",\"cron\":\"0 9 * * *\"" + extra + "}";
    }

    @Test
    @DisplayName("① DURABLE 缺 sessionId ⇒ 400 Validation Failed（判据 (a)）")
    void durableWithoutSessionId_is400() throws Exception {
        // RED（RE-1a-1）：把 ScheduleController.create 的判据改成 if (false) ⇒ 本用例返回 201 ⇒ 红
        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"DURABLE\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation Failed"))
            .andExpect(jsonPath("$.status").value(400));

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("①-b [acc8] scope 缺省 ⇒ **不再**走 DURABLE 判据（对齐 CC 默认 session-only）⇒ 委派给 service")
    void defaultScopeWithoutSessionId_delegatesInsteadOfDurableJudgement() throws Exception {
        // [acc8 · 用户裁定 · 对齐 CC] 缺省 scope 由 DURABLE 改为 SESSION（CC CronCreateTool.ts:117
        //   `durable = false` 默认 + ScheduleCronTool/prompt.ts:78「By default … lives only in this
        //   Claude session」）⇒ Controller 的 ①a/②/③ 三条 DURABLE 判据**不再适用**于缺省形态。
        //   ⛔ 与 ScheduleService.create 的缺省**同改**（两处都 SESSION），否则同一能力两套判据。
        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body("")))
            .andExpect(status().isCreated());

        // 委派给 service（缺省形态的 400 由 service 侧 SESSION 校验给出，不在 Controller 层）
        verify(service).create(any());
    }

    @Test
    @DisplayName("② DURABLE + 空白 sessionId ⇒ 400（判据 (a) 的空白臂）")
    void durableWithBlankSessionId_is400() throws Exception {
        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"DURABLE\",\"sessionId\":\"   \"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation Failed"));

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("③ DURABLE + 确无会话哨兵 ⇒ 400（判据 (b)：哨兵不是一个 id，不得绕过强制）")
    void durableWithNoSessionSentinel_is400() throws Exception {
        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"DURABLE\",\"sessionId\":\"" + SessionKeys.NO_SESSION + "\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation Failed"));

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("④ DURABLE + 解析不到绑定项目根 ⇒ 400 Unresolved Project Root（判据 (c)）")
    void durableWithUnresolvableSession_is400() throws Exception {
        // 会话存在但未绑定项目（unbound）= 数据链路异常 ⇒ 400
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());

        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"DURABLE\",\"sessionId\":\"sess-unbound-1\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Unresolved Project Root"))
            .andExpect(jsonPath("$.status").value(400));

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("⑤ DURABLE + 可解析 sessionId ⇒ 201，且 boundProject 被解析值覆盖（客户端伪造锚被丢弃）")
    void durableWithResolvableSession_anchorComesFromLookup(@TempDir Path projectDir) throws Exception {
        wireResolver(projectDir);
        ScheduleDto dto = new ScheduleDto("sch-new", "b-1a", ScheduleKind.cron, "0 9 * * *",
            null, null, "echo", "d", null, null, ScheduleScope.DURABLE,
            "sess-anchor-1", null, projectDir.toString());
        when(service.create(any(ScheduleCreateRequest.class))).thenReturn(dto);

        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"DURABLE\",\"sessionId\":\"sess-anchor-1\","
                    + "\"boundProject\":\"/etc/evil\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("sch-new"));

        ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(service).create(captor.capture());
        // RED（RE-1a-2）：把 controller 改回直接 scheduleService.create(req) ⇒ 本断言拿到 "/etc/evil" ⇒ 红
        assertThat(captor.getValue().boundProject())
            .as("[RE-1a-2] 锚必须来自 sessionId 解析结果，客户端伪造值被覆盖")
            .isEqualTo(projectDir.toString());
        assertThat(captor.getValue().sessionId()).isEqualTo("sess-anchor-1");
    }

    @Test
    @DisplayName("SESSION scope 不经过本判据（回归臂：只动 DURABLE）")
    void sessionScopeIsUnaffected() throws Exception {
        ScheduleDto dto = new ScheduleDto("sch-s", "b-1a", ScheduleKind.cron, "0 9 * * *",
            null, null, "echo", "d", null, null, ScheduleScope.SESSION, "sess-s1", null, null);
        when(service.create(any(ScheduleCreateRequest.class))).thenReturn(dto);

        mockMvc.perform(post("/api/v1/schedules")
                .contentType(APPLICATION_JSON)
                .content(body(",\"scope\":\"SESSION\",\"sessionId\":\"sess-s1\"")))
            .andExpect(status().isCreated());
    }
}
