package com.nexusai.kb.agent.api;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.eval.KbEvalService;
import com.nexusai.kb.agent.eval.KbEvalTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测端点 · 批次 W11 · 路径对齐源侧 {@code /evaluation}（2 条）。
 *
 * <p><b>⭐ 用户裁定「基于知识库真跑」</b>：{@code POST} 真的在当前知识库上做混合检索，
 * 算 hitRate / recall / mrr。⛔ 不复刻源侧「仓库内 parquet 样例数据集」
 * （源侧 {@code GetDatasetByID} 忽略入参恒返回同一份，照抄只能产出与用户知识库无关的数字）。
 *
 * <p>⚠️ <b>本仓额外提供数据集 CRUD</b>（源侧没有）：没有数据集就没有可跑的用例 ——
 * 源侧的数据集是打包在仓库里的，本仓没有那份文件，只能由用户维护。
 * 这些是辅助端点，不计入源侧 2 条的账。
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    @Autowired private KbEvalService evalService;

    /**
     * 执行评测（源侧 {@code POST /evaluation/}）。
     *
     * <p>⚠️ 同步执行：数据集是几十条量级，检索每条几十毫秒，整体通常 < 10 秒。
     * 异步要引入任务状态轮询这一层，而用户真正要的是结果。
     *
     * @param req 请求体（指定数据集）
     * @return 任务结果（含指标与逐条明细）
     */
    @PostMapping({"", "/"})
    public Map<String, Object> run(@RequestBody RunRequest req) {
        if (req == null || req.datasetId() == null || req.datasetId().isBlank()) {
            throw new ValidationException("dataset_id 不能为空");
        }
        KbEvalTaskRecord task = evalService.run(req.datasetId().trim());
        log.info("[评测] POST /evaluation 完成 task={} status={}", task.getId(), task.getStatus());
        return evalService.getTask(task.getId());
    }

    /**
     * 取评测结果（源侧 {@code GET /evaluation/}）。
     *
     * <p>无参 ⇒ 任务列表；带 {@code task_id} ⇒ 该任务详情。
     *
     * @param taskId    任务 id（可选）
     * @param datasetId 按数据集过滤列表（可选）
     * @return 列表或详情
     */
    @GetMapping({"", "/"})
    public Object result(@RequestParam(required = false, name = "task_id") String taskId,
                         @RequestParam(required = false, name = "dataset_id") String datasetId) {
        if (taskId != null && !taskId.isBlank()) {
            return evalService.getTask(taskId.trim());
        }
        List<Map<String, Object>> tasks = evalService.listTasks(datasetId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", tasks);
        body.put("count", tasks.size());
        return body;
    }

    // ── 数据集辅助端点（源侧无；本仓必需，见类注释）───────────

    /** 建数据集（含用例）。 */
    @PostMapping("/datasets")
    public Map<String, Object> createDataset(@RequestBody CreateDatasetRequest req) {
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        var ds = evalService.createDataset(req.name(), req.description(),
            req.knowledgeBaseId(), req.cases());
        return evalService.getDataset(ds.getId());
    }

    /** 数据集列表。 */
    @GetMapping("/datasets")
    public List<Map<String, Object>> listDatasets() {
        return evalService.listDatasets();
    }

    /** 数据集详情。 */
    @GetMapping("/datasets/{id}")
    public Map<String, Object> getDataset(@PathVariable String id) {
        return evalService.getDataset(id);
    }

    // ── DTO ────────────────────────────────────────────────

    /** 执行评测请求。 */
    public record RunRequest(String datasetId) {
    }

    /** 建数据集请求。 */
    public record CreateDatasetRequest(
        String name, String description, String knowledgeBaseId,
        List<KbEvalService.CaseInput> cases
    ) {
    }
}
