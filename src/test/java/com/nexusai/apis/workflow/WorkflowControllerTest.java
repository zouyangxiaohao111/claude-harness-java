package com.nexusai.apis.workflow;

import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.progress.AgentProgress;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workflow 面板 REST 端点意图测试（W-4c 前端面板数据源）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：CC workflow 面板直读进程内 store（TUI）；Java Web
 * 跨进程须经 HTTP 拉取。本测试钉死 {@code /api/v1/workflows/runs} 契约——①先水合历史再列全部
 * （loadPersistedRuns 前置）；②status 大写枚举归一 CC 小写值域（前端按 CC 契约消费）；
 * ③getRunAsync 内存 miss → 磁盘。这三个契约是前端面板对接的前提。
 */
class WorkflowControllerTest {

    private WorkflowController controller;
    private WorkflowService workflowService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        controller = new WorkflowController(workflowService);
        // setControllerAdvice：GlobalExceptionHandler 将 NotFoundException → 404
        //（无 advice 则 500 传播为 ServletException，MemoryControllerTest:112-114 同款模式）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static RunProgress completedRun() {
        return RunProgress.builder()
                .runId("r-1")
                .workflowName("spec")
                .status(RunProgress.Status.COMPLETED)
                .phases(List.of(new RunProgress.Phase("Doc", RunProgress.PhaseState.DONE)))
                .declaredPhases(List.of("Doc"))
                .currentPhase(null)
                .agents(List.of(new AgentProgress(1, "agent-1", "Doc", AgentProgress.Status.DONE,
                        "ok", "text", "deepseek-v4-flash", 100, 3)))
                .agentCount(1)
                .returnValue("done")
                .startedAt(1000L)
                .description("spec 工作流")
                .updatedAt(2000L)
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/workflows/runs 先水合历史再返回全部（listRuns）")
    void listRuns_hydratesThenReturnsAll() throws Exception {
        when(workflowService.listRuns()).thenReturn(List.of(completedRun()));

        mockMvc.perform(get("/api/v1/workflows/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("r-1"))
                .andExpect(jsonPath("$[0].workflowName").value("spec"))
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].phases[0].title").value("Doc"))
                .andExpect(jsonPath("$[0].phases[0].status").value("done"))
                .andExpect(jsonPath("$[0].agents[0].label").value("agent-1"))
                .andExpect(jsonPath("$[0].agents[0].status").value("done"))
                .andExpect(jsonPath("$[0].agentCount").value(1));

        verify(workflowService).loadPersistedRuns();
    }

    @Test
    @DisplayName("GET /api/v1/workflows/runs/{runId} 返回单个 run（getRunAsync）")
    void getRun_returnsSingleRun() throws Exception {
        when(workflowService.getRunAsync("r-1"))
                .thenReturn(CompletableFuture.completedFuture(completedRun()));

        mockMvc.perform(get("/api/v1/workflows/runs/r-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("r-1"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.returnValue").value("done"));

        verify(workflowService).getRunAsync("r-1");
    }

    @Test
    @DisplayName("GET /api/v1/workflows/runs/{runId} 未命中 → 404")
    void getRun_missReturnsNotFound() throws Exception {
        when(workflowService.getRunAsync("missing"))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(get("/api/v1/workflows/runs/missing"))
                .andExpect(status().isNotFound());

        verify(workflowService).getRunAsync("missing");
    }

    @Test
    @DisplayName("status 大写枚举归一 CC 小写值域（RUNNING→running / FAILED→failed / KILLED→killed）")
    void statusMappedToLowerCaseCcValueDomain() throws Exception {
        RunProgress running = completedRun().toBuilder()
                .runId("r-running").status(RunProgress.Status.RUNNING).build();
        RunProgress failed = completedRun().toBuilder()
                .runId("r-failed").status(RunProgress.Status.FAILED).build();
        RunProgress killed = completedRun().toBuilder()
                .runId("r-killed").status(RunProgress.Status.KILLED).build();
        when(workflowService.listRuns()).thenReturn(List.of(killed, failed, running));

        mockMvc.perform(get("/api/v1/workflows/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("killed"))
                .andExpect(jsonPath("$[1].status").value("failed"))
                .andExpect(jsonPath("$[2].status").value("running"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/runs/{runId}/kill → 200 {success:true} 且下发 kill")
    void killRun_sendsKillForExistingRun() throws Exception {
        // WHY（#139）：前端 workflow 卡「停止」按钮调 kill；命中 getRunAsync → workflowService.kill 下发
        when(workflowService.getRunAsync("r-1"))
                .thenReturn(CompletableFuture.completedFuture(completedRun()));

        mockMvc.perform(post("/api/v1/workflows/runs/r-1/kill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(workflowService).kill("r-1");
    }

    @Test
    @DisplayName("POST /api/v1/workflows/runs/{runId}/kill 未命中 → 404（getRun 同款判定）")
    void killRun_missingRunReturnsNotFound() throws Exception {
        // WHY: runId 内存 + 磁盘双 miss → 404（与 getRun 一致）；kill 不能对未知 run 静默成功
        when(workflowService.getRunAsync("missing"))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/v1/workflows/runs/missing/kill"))
                .andExpect(status().isNotFound());

        verify(workflowService, org.mockito.Mockito.never()).kill("missing");
    }
}
