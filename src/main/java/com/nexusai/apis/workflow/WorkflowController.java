package com.nexusai.apis.workflow;

import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import com.nexusai.infra.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow 面板 REST 端点 · CC original: workflow 面板直读 {@code getWorkflowService()} store
 * （TUI 进程内）；Java Web 后端跨进程，前端须经 HTTP 拉取（W-4c 前端面板承接）。
 *
 * <h2>路由</h2>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/v1/workflows/runs</td><td>全部 run 进度（先水合历史 state.json，
 *       再返回 listRuns() 降序）</td></tr>
 *   <tr><td>GET</td><td>/api/v1/workflows/runs/{runId}</td><td>单个 run 进度（内存 miss →
 *       读磁盘 state.json，getRunAsync）</td></tr>
 * </table>
 *
 * <p><b>架构定案（W-3b）</b>：CC workflow 模块无 {@code emitTaskProgress} 调用点，
 * {@code workflow_progress} 是预留缝（面板直读 store 不走 SDK 事件）。Java Web 前端经本
 * controller 拉取 store（对齐 CC 面板直读语义），不依赖 SDK {@code workflow_progress} 字段。
 *
 * <p><b>DTO 映射</b>：{@link WorkflowRunDto} 把后端 {@link RunProgress.Status} 大写枚举归一为
 * CC 小写值域（running/completed/failed/killed），前端按 CC 契约消费。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowService workflowService;

    /** Spring 注入 WorkflowService（进程单例，与 WorkflowTool 共享 ports/registry/store）。 */
    @Autowired
    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 全部 run 进度 · CC original: {@code loadPersistedRuns() + listRuns()}（面板打开时水合历史 +
     * 实时快照）。返回按 updatedAt 降序。
     *
     * @return WorkflowRunDto 列表（运行中 + 历史）
     */
    @GetMapping("/runs")
    public List<WorkflowRunDto> listRuns() {
        // 先水合磁盘历史（进程单例只扫一次，幂等；失败 log + 重置 flag 允许重试）· CC service.ts:290-309
        workflowService.loadPersistedRuns();
        List<RunProgress> runs = workflowService.listRuns();
        if (log.isDebugEnabled()) {
            log.debug("WorkflowController.listRuns: 返回 {} 个 run（含历史水合，对齐 CC service.ts:290-309 + store.list）",
                    runs.size());
        }
        return runs.stream().map(WorkflowRunDto::from).toList();
    }

    /**
     * 单个 run 进度 · CC original: {@code getRunAsync(runId)}（内存命中直接返回；miss →
     * 读磁盘 state.json，不注入内存）。
     *
     * @param runId 目标 run id
     * @return 该 run 的进度 DTO
     * @throws NotFoundException runId 内存 + 磁盘均未命中（404）
     */
    @GetMapping("/runs/{runId}")
    public WorkflowRunDto getRun(@PathVariable String runId) {
        CompletableFuture<RunProgress> future = workflowService.getRunAsync(runId);
        RunProgress run = future.join();
        if (run == null) {
            log.warn("WorkflowController.getRun: runId={} 内存 + 磁盘均未命中（404）", runId);
            throw new NotFoundException("Workflow run not found: " + runId);
        }
        if (log.isDebugEnabled()) {
            log.debug("WorkflowController.getRun: runId={} status={}（getRunAsync：mem miss → 磁盘，service.ts:285-289）",
                    runId, run.status());
        }
        return WorkflowRunDto.from(run);
    }

    /**
     * 杀单个 run · POST /api/v1/workflows/runs/{runId}/kill · CC original: {@code kill(runId)}
     * (service.ts:68) → taskRegistrar.kill。
     *
     * <p>前置用 {@link #getRun} 同款 {@code getRunAsync} 双 miss 判定 → 404（与 getRun 一致）；
     * 命中后 {@link WorkflowService#kill} 为 void，已终态 run 调 kill 是幂等 no-op（CC
     * taskRegistrar.kill 守卫），只返回 {@code {success: true}}，不加 409（计划决策 D5）。
     *
     * @param runId 目标 run id
     * @return {@code {success: true}}
     * @throws NotFoundException runId 内存 + 磁盘均未命中（404）
     */
    @PostMapping("/runs/{runId}/kill")
    public Map<String, Object> killRun(@PathVariable String runId) {
        CompletableFuture<RunProgress> future = workflowService.getRunAsync(runId);
        RunProgress run = future.join();
        if (run == null) {
            log.warn("WorkflowController.killRun: runId={} 内存 + 磁盘均未命中（404）", runId);
            throw new NotFoundException("Workflow run not found: " + runId);
        }
        workflowService.kill(runId);
        log.info("WorkflowController.killRun: runId={} kill 已下发（CC service.ts:68 → taskRegistrar.kill）", runId);
        return Map.of("success", true);
    }
}
