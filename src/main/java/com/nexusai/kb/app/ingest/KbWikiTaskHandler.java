package com.nexusai.kb.app.ingest;

import com.nexusai.kb.app.wiki.KbWikiIngestService;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Wiki 子任务的适配器 · 把「入库任务」翻译成 {@link KbWikiIngestService#runBatch} 的一次调用。
 *
 * <p><b>为什么单独一个类而不是塞进 worker</b>：worker 只该知道「四种结果 + 槽位账」；
 * Wiki 的编排（Map → Reduce → Finalize、目录规划、死链清理）是一大块独立逻辑，
 * 混在一起会让 worker 变成第二个编排器。
 *
 * <p><b>⚠️ 一次任务只处理一篇文档</b>：源侧 Wiki 是知识库级批量任务（带 30s 防抖聚合），
 * 本仓单机没有防抖队列 ⇒ 每次入库只为自己那篇跑一轮。
 * 代价：多篇连续上传时，同一批页面会被增量更新多次（Reduce 是幂等的合并，不是重复建页）。
 */
@Component
public class KbWikiTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(KbWikiTaskHandler.class);

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbWikiIngestService ingestService;

    public KbWikiTaskHandler(KbKnowledgeMapper knowledgeMapper, KbWikiIngestService ingestService) {
        this.knowledgeMapper = knowledgeMapper;
        this.ingestService = ingestService;
    }

    /**
     * 为单篇文档跑一轮 Wiki 生成。
     *
     * @param knowledgeId 文档 ID
     * @param attempt     本轮解析 attempt（陈旧任务识别）
     * @param modelName   模型名（null ⇒ 视为未配置，跳过）
     * @return 结果分类（永不 null）
     */
    public KbPostProcessOutcome run(String knowledgeId, int attempt, String modelName) {
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            return KbPostProcessOutcome.skipped("knowledge_not_found");
        }
        int current = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        if (current != attempt) {
            return KbPostProcessOutcome.superseded(
                "解析已被新一轮取代 任务attempt=" + attempt + " 当前=" + current);
        }
        String status = knowledge.getParseStatus();
        if ("deleting".equals(status) || "cancelled".equals(status)) {
            return KbPostProcessOutcome.skipped("文档处于 " + status);
        }
        if (modelName == null || modelName.isBlank()) {
            return KbPostProcessOutcome.skipped("未配置 Wiki 生成模型");
        }
        try {
            KbWikiIngestService.RunSummary summary = ingestService.runBatch(
                knowledge.getKnowledgeBaseId(), List.of(knowledgeId), modelName, false);
            if (summary.skipped()) {
                return KbPostProcessOutcome.skipped(summary.skipReason());
            }
            return KbPostProcessOutcome.done(
                "页数=" + summary.createdPages().size() + " 更新=" + summary.updatedPages().size());
        } catch (RuntimeException e) {
            // ⭐ Wiki 失败不阻塞文档可用：文档本身已可检索，Wiki 只是衍生展示层
            log.warn("[Wiki] 生成失败 knowledge={} 原因={}", knowledgeId, e.toString());
            return KbPostProcessOutcome.failed("wiki 生成失败: " + e.getMessage());
        }
    }
}
