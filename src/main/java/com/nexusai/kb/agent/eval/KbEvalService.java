package com.nexusai.kb.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.eval.mapper.KbEvalCaseMapper;
import com.nexusai.kb.agent.eval.mapper.KbEvalDatasetMapper;
import com.nexusai.kb.agent.eval.mapper.KbEvalTaskMapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.config.KbConfigService;
import com.nexusai.kb.domain.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库评测 · 批次 W11 · 用户裁定「基于知识库真跑」。
 *
 * <p><b>⭐ 与源侧的根本差异</b>：源侧数据集是仓库里打包的 parquet 样例
 * （{@code service/dataset.go} 的 {@code DefaultDataset()}，且
 * {@code GetDatasetByID} **忽略入参**恒返回同一份）⇒ 照抄只能得到「跟用户自己知识库无关」的数字。
 * 本仓改为：用户在数据集里维护 QA 对，评测**真的走当前知识库的混合检索**。
 *
 * <p><b>算的指标</b>（全部基于真实检索结果，⛔ 不编）：
 * <ul>
 *   <li><b>hitRate</b>：top-k 里至少命中一个期望 chunk 的用例占比</li>
 *   <li><b>recall</b>：命中的期望 chunk / 全部期望 chunk（只对配了期望 chunk 的用例算）</li>
 *   <li><b>mrr</b>：第一个命中位置的倒数均值（1/rank）</li>
 *   <li><b>avgScore</b>：命中片段的平均检索分（观察检索质量趋势）</li>
 * </ul>
 *
 * <p><b>⚠️ 不评测生成质量</b>：那要额外调 LLM（花钱 + 慢 + 不可比）。
 * 需要时后续单开——⛔ 不要在没有明确需求时偷偷加 LLM 调用。
 */
@Service
public class KbEvalService {

    private static final Logger log = LoggerFactory.getLogger(KbEvalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 检索取多少条来算指标（也是 recall 的分母窗口）。 */
    private static final int TOP_K = 10;

    @Autowired private KbEvalDatasetMapper datasetMapper;
    @Autowired private KbEvalCaseMapper caseMapper;
    @Autowired private KbEvalTaskMapper taskMapper;
    @Autowired private KbHybridSearcher searcher;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired(required = false)
    private KbConfigService kbConfigService;

    // ── 数据集 ─────────────────────────────────────────────

    /** 建数据集（含用例）。 */
    public KbEvalDatasetRecord createDataset(String name, String description,
                                             String knowledgeBaseId, List<CaseInput> cases) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("数据集名称不能为空");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new ValidationException("knowledge_base_id 不能为空");
        }
        // ⚠️ 知识库必须存在：否则评测会跑出「全部未命中」，用户以为检索坏了
        knowledgeBaseService.getById(knowledgeBaseId);

        KbEvalDatasetRecord ds = new KbEvalDatasetRecord();
        ds.setId("eval-ds-" + UUID.randomUUID().toString().substring(0, 8));
        ds.setName(name.trim());
        ds.setDescription(description);
        ds.setKnowledgeBaseId(knowledgeBaseId.trim());
        datasetMapper.insert(ds);

        List<CaseInput> list = cases == null ? List.of() : cases;
        for (int i = 0; i < list.size(); i++) {
            CaseInput c = list.get(i);
            if (c == null || c.question() == null || c.question().isBlank()) {
                throw new ValidationException("第 " + (i + 1) + " 条用例的问题不能为空");
            }
            KbEvalCaseRecord row = new KbEvalCaseRecord();
            row.setId("eval-case-" + UUID.randomUUID().toString().substring(0, 8));
            row.setDatasetId(ds.getId());
            row.setQuestion(c.question().trim());
            row.setExpectedAnswer(c.expectedAnswer());
            row.setExpectChunkIds(writeIds(c.expectChunkIds()));
            row.setSeq(i);
            caseMapper.insert(row);
        }
        log.info("[评测] 数据集已创建 id={} 用例数={} kb={}", ds.getId(), list.size(), knowledgeBaseId);
        return ds;
    }

    /** 数据集列表（带用例数）。 */
    public List<Map<String, Object>> listDatasets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbEvalDatasetRecord ds : datasetMapper.selectAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ds.getId());
            m.put("name", ds.getName());
            m.put("description", ds.getDescription());
            m.put("knowledge_base_id", ds.getKnowledgeBaseId());
            m.put("case_count", casesOf(ds.getId()).size());
            out.add(m);
        }
        return out;
    }

    /** 数据集详情（含用例）。 */
    public Map<String, Object> getDataset(String id) {
        KbEvalDatasetRecord ds = datasetMapper.selectOneById(id);
        if (ds == null) {
            throw new NotFoundException("数据集不存在: " + id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("name", ds.getName());
        m.put("description", ds.getDescription());
        m.put("knowledge_base_id", ds.getKnowledgeBaseId());
        List<Map<String, Object>> cases = new ArrayList<>();
        for (KbEvalCaseRecord c : casesOf(id)) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("question", c.getQuestion());
            cm.put("expected_answer", c.getExpectedAnswer());
            cm.put("expect_chunk_ids", readIds(c.getExpectChunkIds()));
            cm.put("seq", c.getSeq());
            cases.add(cm);
        }
        m.put("cases", cases);
        return m;
    }

    // ── 评测执行 ───────────────────────────────────────────

    /**
     * 执行一次评测（**同步**）。
     *
     * <p>⚠️ 同步而不是异步：数据集是几十条的量级，检索每条几十毫秒，整体通常 < 10 秒。
     * 异步会引入「任务状态查询」这一层复杂度，而用户真正要的是结果。
     * 任务行仍然落表（便于与历史对比）。
     *
     * @param datasetId 数据集
     * @return 任务结果
     */
    public KbEvalTaskRecord run(String datasetId) {
        KbEvalDatasetRecord ds = datasetMapper.selectOneById(datasetId);
        if (ds == null) {
            throw new NotFoundException("数据集不存在: " + datasetId);
        }
        List<KbEvalCaseRecord> cases = casesOf(datasetId);
        if (cases.isEmpty()) {
            throw new ValidationException("数据集里没有用例，无法评测");
        }

        KbEvalTaskRecord task = new KbEvalTaskRecord();
        task.setId("eval-task-" + UUID.randomUUID().toString().substring(0, 8));
        task.setDatasetId(datasetId);
        task.setKnowledgeBaseId(ds.getKnowledgeBaseId());
        task.setStatus("running");
        task.setTotal(cases.size());
        task.setFinished(0);
        task.setMetrics("{}");
        task.setDetails("[]");
        task.setStartedAt(now());
        taskMapper.insert(task);

        String modelId = kbConfigService == null ? null : kbConfigService.readEmbeddingModelId();
        int retrievedCases = 0;      // 配了期望 chunk 的用例数（recall/mrr 的分母）
        int hitCases = 0;            // 至少命中一个期望 chunk
        double recallSum = 0;
        double mrrSum = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        int noResult = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        try {
            int idx = 0;
            for (KbEvalCaseRecord c : cases) {
                idx++;
                List<KbHybridSearcher.Hit> hits;
                try {
                    hits = searcher.search(ds.getKnowledgeBaseId(), c.getQuestion(), TOP_K, modelId);
                } catch (RuntimeException e) {
                    // 单条失败不中断整轮，但要记进明细（⛔ 不静默当成「未命中」）
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("question", c.getQuestion());
                    d.put("error", e.toString());
                    d.put("hit", false);
                    details.add(d);
                    continue;
                }
                if (hits == null) {
                    hits = List.of();
                }
                if (hits.isEmpty()) {
                    noResult++;
                }
                List<String> hitIds = hits.stream().map(KbHybridSearcher.Hit::chunkId).toList();
                Set<String> expected = new HashSet<>(readIds(c.getExpectChunkIds()));

                Map<String, Object> d = new LinkedHashMap<>();
                d.put("question", c.getQuestion());
                d.put("retrieved", hitIds.size());
                d.put("top_chunk_ids", hitIds.subList(0, Math.min(3, hitIds.size())));
                d.put("expected", new ArrayList<>(expected));

                if (!expected.isEmpty()) {
                    retrievedCases++;
                    int found = 0;
                    int firstRank = 0;
                    for (int i = 0; i < hitIds.size(); i++) {
                        if (expected.contains(hitIds.get(i))) {
                            found++;
                            if (firstRank == 0) {
                                firstRank = i + 1;
                            }
                        }
                    }
                    double recall = (double) found / expected.size();
                    recallSum += recall;
                    if (firstRank > 0) {
                        hitCases++;
                        mrrSum += 1.0 / firstRank;
                    }
                    d.put("hit", found > 0);
                    d.put("recall", round4(recall));
                    d.put("first_rank", firstRank == 0 ? null : firstRank);
                    if (found > 0 && !hits.isEmpty()) {
                        scoreSum += hits.get(0).score();
                        scoreCount++;
                    }
                } else {
                    // 没配期望 chunk ⇒ 不评召回，只记录「有没有检索到东西」
                    d.put("hit", !hits.isEmpty());
                    d.put("recall", null);
                }
                details.add(d);
                task.setFinished(idx);
            }

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("top_k", TOP_K);
            metrics.put("case_count", cases.size());
            metrics.put("scored_case_count", retrievedCases);
            metrics.put("no_result_count", noResult);
            metrics.put("hit_rate", retrievedCases == 0 ? null : round4((double) hitCases / retrievedCases));
            metrics.put("recall", retrievedCases == 0 ? null : round4(recallSum / retrievedCases));
            metrics.put("mrr", retrievedCases == 0 ? null : round4(mrrSum / retrievedCases));
            metrics.put("avg_top_score", scoreCount == 0 ? null : round4(scoreSum / scoreCount));
            if (retrievedCases == 0) {
                metrics.put("note", "没有任何用例配置 expect_chunk_ids ⇒ 无法计算召回指标（只统计了检索条数）");
            }

            task.setStatus("completed");
            task.setMetrics(JSON.writeValueAsString(metrics));
            task.setDetails(JSON.writeValueAsString(details));
            task.setFinishedAt(now());
            taskMapper.update(task);
            log.info("[评测] 完成 task={} 用例={} 命中率={} recall={} mrr={}",
                task.getId(), cases.size(), metrics.get("hit_rate"),
                metrics.get("recall"), metrics.get("mrr"));
            return task;
        } catch (Exception e) {
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(now());
            try {
                task.setDetails(JSON.writeValueAsString(details));
            } catch (Exception ignored) {
                // 明细序列化失败不影响「失败状态」本身
            }
            taskMapper.update(task);
            log.error("[评测] 执行失败 task={}", task.getId(), e);
            return task;
        }
    }

    /** 任务列表（可按数据集过滤）。 */
    public List<Map<String, Object>> listTasks(String datasetId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbEvalTaskRecord t : taskMapper.selectAll()) {
            if (datasetId != null && !datasetId.isBlank() && !datasetId.equals(t.getDatasetId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("dataset_id", t.getDatasetId());
            m.put("status", t.getStatus());
            m.put("total", t.getTotal());
            m.put("finished", t.getFinished());
            m.put("metrics", parseJson(t.getMetrics()));
            m.put("started_at", t.getStartedAt());
            m.put("finished_at", t.getFinishedAt());
            m.put("error", t.getErrorMessage());
            out.add(m);
        }
        return out;
    }

    /** 任务详情（含逐条明细）。 */
    public Map<String, Object> getTask(String taskId) {
        KbEvalTaskRecord t = taskMapper.selectOneById(taskId);
        if (t == null) {
            throw new NotFoundException("评测任务不存在: " + taskId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("dataset_id", t.getDatasetId());
        m.put("knowledge_base_id", t.getKnowledgeBaseId());
        m.put("status", t.getStatus());
        m.put("total", t.getTotal());
        m.put("finished", t.getFinished());
        m.put("metrics", parseJson(t.getMetrics()));
        m.put("details", parseJson(t.getDetails()));
        m.put("started_at", t.getStartedAt());
        m.put("finished_at", t.getFinishedAt());
        m.put("error", t.getErrorMessage());
        return m;
    }

    // ── 内部 ───────────────────────────────────────────────

    private List<KbEvalCaseRecord> casesOf(String datasetId) {
        List<KbEvalCaseRecord> all = new ArrayList<>();
        for (KbEvalCaseRecord c : caseMapper.selectAll()) {
            if (datasetId != null && datasetId.equals(c.getDatasetId())) {
                all.add(c);
            }
        }
        all.sort((a, b) -> Integer.compare(
            a.getSeq() == null ? 0 : a.getSeq(),
            b.getSeq() == null ? 0 : b.getSeq()));
        return all;
    }

    private static String writeIds(List<String> ids) {
        List<String> clean = ids == null ? List.of()
            : ids.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        try {
            return JSON.writeValueAsString(clean);
        } catch (Exception e) {
            throw new ValidationException("expect_chunk_ids 序列化失败");
        }
    }

    private static List<String> readIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> l = JSON.readValue(json, new TypeReference<List<String>>() { });
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /**
     * 评测用例输入。
     *
     * @param question        问题（必填）
     * @param expectedAnswer  标准答案（可空）
     * @param expectChunkIds  期望命中的 chunk id（可空 = 不评召回）
     */
    public record CaseInput(String question, String expectedAnswer, List<String> expectChunkIds) {
    }
}
