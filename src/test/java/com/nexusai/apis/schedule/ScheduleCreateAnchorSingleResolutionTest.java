package com.nexusai.apis.schedule;

import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [P11a] REST 建单链上「会话 → 项目锚」<b>只解析一次</b>。
 *
 * <h2>WHY（规则九 · 意图，不是行为）</h2>
 * DURABLE 的项目锚（{@code bound_project}）是 fire 时刻 cwd / 权限 / transcript 的基址，
 * 其唯一合法来源是「会话 id → 绑定项目根」的解析结果。本仓此前在<b>同一条 REST 链</b>上把它
 * 解析了<b>两遍</b>：{@code ScheduleController} 先解析、用它重建请求体并做 400 判据，
 * 随后 {@code ScheduleService.create} 又对同一 sessionId 解析一遍、并<b>丢弃</b>上游已解析的值
 * 改用自己那份。两遍解析在语义上是「同一个事实有两个来源」，本仓反复栽过的失效模式
 * （「同一能力两套判据」）即由此而生：只要上游的解析口径被改动，下游会静默用自己的旧口径覆盖。
 *
 * <p>⇒ 收口后：REST 链由 Controller 把<b>它自己刚解析出的锚</b>显式下传（形参），Service
 * <b>不再</b>重解析；工具链（{@code CronCreateTool} 无 ctx）仍传 {@code null} 由 Service 自解析。
 *
 * <h2>RED（改前必红）</h2>
 * 本用例在收口<b>之前</b>跑：{@code SessionProjectRoot.lookup(sessionId)} 被调用 <b>2 次</b>
 * ⇒ {@code monitor.verify(..., times(1))} 抛 {@code TooManyActualInvocations}
 * （报错文案逐字含 {@code But was 2 times}）。⛔ 这条断言守护的是「同链单次解析」，
 * 不是「锚存在」（后者由 {@code ScheduleControllerCreateDurableTest} ⑤ 与
 * {@code ScheduleServiceCreateStorageTest} 守护）。
 *
 * <p><b>反向实验（改后必红）</b>：把 {@code ScheduleController.create} 末行改回
 * {@code scheduleService.create(normalized)}（不再下传已解析锚）⇒ Service 自行解析
 * ⇒ 本断言重新变红（2 次）。
 *
 * <h2>⚠️ 为什么计数点落在 {@code lookup()} 而不是 DB 回源器</h2>
 * 回源器（{@code setDbResolver} 注入）天然<b>只被查一次</b>：首次 {@code lookup} 命中 DB 后经
 * {@code refillFromDb → setForSession} <b>回填进程内冻结表</b>，第二次 {@code lookup} 是内存命中
 * （{@code SessionProjectRoot:357-360}），<b>不会</b>再触达回源器。故回源器计数器在改前/改后
 * 都是 1，<b>无鉴别力</b>；能区分「解析了两遍」的只有 {@code lookup()} 的调用次数本身。
 * 本用例仍把回源器计数打出来（见断言消息），用于记录这条「DB 热点被冻结表吸收」的读数。
 */
class ScheduleCreateAnchorSingleResolutionTest {

    private static final String SESSION_ID = "sess-p11a-anchor";

    private ScheduleController controller;
    private ScheduleMapper mapper;
    private QuartzScheduleService quartzScheduleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mapper = mock(ScheduleMapper.class);
        quartzScheduleService = mock(QuartzScheduleService.class);

        // 真实 Service（本用例要测的正是 Controller 与 Service 之间的那一跳，⛔ 不能用 mock 替身）
        ScheduleService service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartzScheduleService);

        controller = new ScheduleController();
        ReflectionTestUtils.setField(controller, "scheduleService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        when(mapper.selectAll()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    private static String durableBody() {
        return "{\"name\":\"p11a-anchor\",\"kind\":\"cron\",\"cron\":\"0 9 * * *\","
            + "\"command\":\"echo p11a\",\"scope\":\"DURABLE\","
            + "\"sessionId\":\"" + SESSION_ID + "\","
            + "\"boundProject\":\"/etc/forged\"}";
    }

    @Test
    @DisplayName("[P11a] REST 链上 SessionProjectRoot.lookup(同一 sessionId) 只被调用一次")
    void restCreateChain_resolvesSessionAnchorExactlyOnce(@TempDir Path projectDir) throws Exception {
        String anchor = projectDir.toString();
        AtomicInteger resolverCalls = new AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            resolverCalls.incrementAndGet();
            return SESSION_ID.equals(sid)
                ? SessionProjectRoot.Lookup.bound(anchor)
                : SessionProjectRoot.Lookup.unknown();
        });

        // ⭐ 监视器：CALLS_REAL_METHODS ⇒ 真实解析照常发生，只是额外记录每次静态调用
        try (MockedStatic<SessionProjectRoot> monitor =
                 Mockito.mockStatic(SessionProjectRoot.class, Mockito.CALLS_REAL_METHODS)) {

            mockMvc.perform(post("/api/v1/schedules")
                    .contentType(APPLICATION_JSON)
                    .content(durableBody()))
                .andExpect(status().isCreated());

            // RED（改前）：此处报 "Wanted 1 time ... But was 2 times"
            monitor.verify(() -> SessionProjectRoot.lookup(SESSION_ID), times(1));
        }

        // 记录性读数：DB 回源器只被查一次（首次 lookup 回填冻结表，第二次是内存命中）
        assertThat(resolverCalls.get())
            .as("同一链上 DB 回源器对同一 sessionId 不得被查两次（第 2 次 resolve 意味着冻结表回填失效）")
            .isEqualTo(1);

        // 端到端：落库锚 = 解析值；客户端伪造的 boundProject 不得落库
        ArgumentCaptor<ScheduleRecord> inserted = ArgumentCaptor.forClass(ScheduleRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getBoundProject())
            .as("落库 bound_project 必须等于会话解析出的锚")
            .isEqualTo(anchor);
        assertThat(inserted.getValue().getSessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("[P11a] Service 收到显式锚 ⇒ 采信形参、零 lookup（即便该 sessionId 另需解析也不许再解析）")
    void explicitAnchorShortCircuitsResolution(@TempDir Path projectDir) {
        String anchor = projectDir.toString();
        AtomicInteger resolverCalls = new AtomicInteger();
        // 回源器一律「解析失败」⇒ 只要 Service 还去解析，就会抛 UnresolvedProjectRootException
        SessionProjectRoot.setDbResolver(sid -> {
            resolverCalls.incrementAndGet();
            return SessionProjectRoot.Lookup.resolutionFailure();
        });

        ScheduleService service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartzScheduleService);

        try (MockedStatic<SessionProjectRoot> monitor =
                 Mockito.mockStatic(SessionProjectRoot.class, Mockito.CALLS_REAL_METHODS)) {

            // 真实会话 id + 请求体伪造锚，但形参给出已解析锚 ⇒ 形参必须胜出，且不得回落请求字段
            ScheduleDto dto = service.create(new ScheduleCreateRequest(
                "p11a-explicit", ScheduleKind.cron, "0 9 * * *", null, null,
                "echo p11a", "d", ScheduleScope.DURABLE, "sess-real-but-unresolvable", null,
                "/etc/forged", null), anchor);

            // RED：删掉 ScheduleService.create 的「resolvedProjectRoot 非空 ⇒ 直接用」分支
            //   ⇒ Service 自解析 ⇒ 回源器给 resolutionFailure ⇒ 抛 UnresolvedProjectRootException ⇒ 红
            monitor.verify(() -> SessionProjectRoot.lookup(anyString()), never());

            assertThat(dto.boundProject())
                .as("显式锚（服务端已解析值）必须被采信")
                .isEqualTo(anchor);
        }

        assertThat(resolverCalls.get())
            .as("形参非空时 DB 回源器不得被触达（否则等于又解析了一遍）")
            .isZero();
        ArgumentCaptor<ScheduleRecord> inserted = ArgumentCaptor.forClass(ScheduleRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getBoundProject())
            .as("落库锚 = 形参值；⛔ 请求体里的伪造锚不得落库")
            .isEqualTo(anchor);
    }
}
