package com.nexusai.kb.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.entity.KbAgentRecord;
import com.nexusai.kb.agent.mapper.KbAgentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 自定义智能体扩展配置（{@code kb_agent}）· 批次 W10。
 *
 * <p><b>⭐ 这里只读写「扩展」</b>（用户 2026-11-05 裁定）：
 * 系统提示词 / 模型 / 工具全在 {@code agents/<id>.md}，本服务⛔ 不碰那些。
 * 主键 {@code id} 就是 MD 的 agentType —— 同一个标识贯穿两侧。
 *
 * <p><b>三态语义</b>（与本仓既有约定一致）：读返回 {@code null} = 未配置；
 * 写传 {@code null} = 不改，传空串 = 清空（回落默认）。
 *
 * <p><b>为什么用参数化原生 SQL 之外的写法</b>：本表字段没有「DEFAULT 不生效」的风险
 * （新建时全部显式赋值），且都是简单标量 ⇒ 直接走 Flex 的 mapper；只有
 * 「部分更新」这一类需要区分「没传 / 传 null」的地方由 {@link UpdateRequest} 的
 * {@code hasXxx} 三元表达（不入库）。
 */
@Service
public class KbAgentService {

    private static final Logger log = LoggerFactory.getLogger(KbAgentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合法的知识库选择模式（对齐源侧 KBSelectionMode）。 */
    public static final List<String> SELECTION_MODES = List.of("all", "selected", "none");

    @Autowired private KbAgentMapper mapper;

    /**
     * 读扩展配置。
     *
     * @param agentId MD 的 agentType
     * @return 记录；不存在返回 {@code null}（= 该智能体没配扩展，一切走默认）
     */
    public KbAgentRecord find(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return mapper.selectOneById(agentId.trim());
        } catch (RuntimeException e) {
            // fail-soft：读扩展失败不该让智能体整体不可用（会用默认范围）。
            log.warn("[kb_agent] 读扩展配置失败 agent={}，按未配置处理: {}", agentId, e.toString());
            return null;
        }
    }

    /**
     * 读知识库白名单。
     *
     * <p>解析规则（对齐源侧 {@code resolveKnowledgeBasesFromAgent} 的三值语义）：
     * <ul>
     *   <li>没有扩展行 / {@code kb_selection_mode='all'} ⇒ {@code null}（= 全部可检索库）</li>
     *   <li>{@code 'none'} ⇒ 空列表（不检索）</li>
     *   <li>{@code 'selected'} ⇒ 解析 JSON 数组</li>
     * </ul>
     *
     * @return {@code null} = 不限制（全部库）；空列表 = 明确不检索；非空 = 白名单
     */
    public List<String> resolveKnowledgeBaseIds(String agentId) {
        KbAgentRecord r = find(agentId);
        if (r == null) {
            return null;
        }
        String mode = r.getKbSelectionMode() == null || r.getKbSelectionMode().isBlank()
            ? "all" : r.getKbSelectionMode().trim();
        if ("none".equals(mode)) {
            return List.of();
        }
        if (!"selected".equals(mode)) {
            return null;
        }
        return parseIdList(r.getKnowledgeBases());
    }

    /**
     * 列出全部扩展配置的 agent id（用于发现「MD 已删、配置还在」的孤儿）。
     *
     * @return id 列表（读失败返回空列表，不抛 —— 列表接口不该因一次 DB 抖动整页失败）
     */
    public List<String> listExtensionIds() {
        try {
            return mapper.selectAll().stream()
                .map(KbAgentRecord::getId)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        } catch (RuntimeException e) {
            log.warn("[kb_agent] 列扩展配置失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 读知识库 id 列表（原始解析，不套三值语义）。
     *
     * <p>供「复制智能体」原样带走库配置用 —— 若走 {@link #resolveKnowledgeBaseIds}
     * 就会把 {@code all} 变成 {@code null}，复制后语义会漂移。
     *
     * @param agentId id
     * @return 解析出的 id 列表（无行/空 → 空列表）
     */
    public List<String> readKnowledgeBaseIds(String agentId) {
        KbAgentRecord r = find(agentId);
        return r == null ? List.of() : parseIdList(r.getKnowledgeBases());
    }

    /**
     * 新建或整体覆盖扩展配置。
     *
     * @param agentId 必填（= MD 的 agentType）
     * @param req     配置
     * @return 落库后的记录
     */
    public KbAgentRecord upsert(String agentId, UpdateRequest req) {
        String id = requireAgentId(agentId);
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        validate(req);

        KbAgentRecord existing = mapper.selectOneById(id);
        KbAgentRecord row = existing == null ? new KbAgentRecord() : existing;
        row.setId(id);
        if (existing == null) {
            // ⚠️ 新建时全部显式赋值：Flex 的 insert 会把 null 也列进 INSERT 清单，
            //    DB DEFAULT 不生效（本仓已踩过 4 次）。这些列都是 NOT NULL。
            row.setKbSelectionMode("all");
            row.setKnowledgeBases("[]");
            row.setRetrieveKbOnlyWhenMentioned(0);
        }
        if (req.kbSelectionMode() != null) {
            row.setKbSelectionMode(req.kbSelectionMode());
        }
        if (req.knowledgeBases() != null) {
            row.setKnowledgeBases(writeIdList(req.knowledgeBases()));
        }
        if (req.retrieveKbOnlyWhenMentioned() != null) {
            row.setRetrieveKbOnlyWhenMentioned(req.retrieveKbOnlyWhenMentioned() ? 1 : 0);
        }
        if (req.embeddingTopK() != null) row.setEmbeddingTopK(req.embeddingTopK());
        if (req.keywordThreshold() != null) row.setKeywordThreshold(req.keywordThreshold());
        if (req.vectorThreshold() != null) row.setVectorThreshold(req.vectorThreshold());
        if (req.rerankTopK() != null) row.setRerankTopK(req.rerankTopK());
        if (req.rerankThreshold() != null) row.setRerankThreshold(req.rerankThreshold());
        if (req.citationEnabled() != null) row.setCitationEnabled(req.citationEnabled() ? 1 : 0);

        if (existing == null) {
            mapper.insert(row);
            log.info("[kb_agent] 扩展配置已创建 agent={} 库范围={}", id,
                row.getKbSelectionMode());
        } else {
            mapper.update(row);
            log.info("[kb_agent] 扩展配置已更新 agent={} 库范围={}", id,
                row.getKbSelectionMode());
        }
        return mapper.selectOneById(id);
    }

    /**
     * 删除扩展配置（幂等）。
     *
     * <p>⚠️ 只删本表行，⛔ 不删 MD 文件 —— 文件是本体，删不删由上层（智能体服务）
     * 决定，两侧同时删才是一致状态。
     *
     * @param agentId MD 的 agentType
     */
    public void deleteExtension(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        mapper.deleteById(agentId.trim());
        log.info("[kb_agent] 扩展配置已删除 agent={}", agentId);
    }

    // ── 校验 / 序列化 ──────────────────────────────────────

    private String requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new ValidationException("agent id 不能为空（= agents/<id>.md 的 agentType）");
        }
        return agentId.trim();
    }

    private void validate(UpdateRequest req) {
        if (req.kbSelectionMode() != null && !SELECTION_MODES.contains(req.kbSelectionMode())) {
            throw new ValidationException("kb_selection_mode 只能是 " + SELECTION_MODES
                + "，收到: " + req.kbSelectionMode());
        }
        if (req.embeddingTopK() != null && req.embeddingTopK() <= 0) {
            throw new ValidationException("embedding_top_k 必须大于 0");
        }
        if (req.rerankTopK() != null && req.rerankTopK() <= 0) {
            throw new ValidationException("rerank_top_k 必须大于 0");
        }
        if (req.keywordThreshold() != null && (req.keywordThreshold() < 0 || req.keywordThreshold() > 1)) {
            throw new ValidationException("keyword_threshold 必须在 [0,1]");
        }
        if (req.vectorThreshold() != null && (req.vectorThreshold() < 0 || req.vectorThreshold() > 1)) {
            throw new ValidationException("vector_threshold 必须在 [0,1]");
        }
        if (req.rerankThreshold() != null && (req.rerankThreshold() < 0 || req.rerankThreshold() > 1)) {
            throw new ValidationException("rerank_threshold 必须在 [0,1]");
        }
    }

    private List<String> parseIdList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = JSON.readValue(json, new TypeReference<List<String>>() { });
            if (list == null) {
                return List.of();
            }
            return list.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        } catch (Exception e) {
            // 脏数据（历史手改等）⇒ 当空列表，不抛（配置坏了不该让检索整体失败）。
            log.warn("[kb_agent] knowledge_bases JSON 解析失败，按空列表处理: {}", e.toString());
            return List.of();
        }
    }

    private String writeIdList(List<String> ids) {
        List<String> clean = ids == null ? List.of()
            : ids.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        try {
            return JSON.writeValueAsString(clean);
        } catch (Exception e) {
            throw new ValidationException("knowledge_bases 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 更新请求 · 全部字段可选。
     *
     * <p>⚠️ {@code null} = **不改**这一项（不是清空）。要清空库列表传空数组，
     * 要改回「全部库」传 {@code kbSelectionMode="all"}。
     *
     * @param kbSelectionMode            all / selected / none
     * @param knowledgeBases             库 id 列表（selected 时生效）
     * @param retrieveKbOnlyWhenMentioned 仅 @ 提及时才检索
     * @param embeddingTopK              向量召回条数
     * @param keywordThreshold           关键词阈值
     * @param vectorThreshold            向量阈值
     * @param rerankTopK                 重排保留条数
     * @param rerankThreshold            重排阈值
     * @param citationEnabled            是否带引用
     */
    public record UpdateRequest(
        String kbSelectionMode,
        List<String> knowledgeBases,
        Boolean retrieveKbOnlyWhenMentioned,
        Integer embeddingTopK,
        Double keywordThreshold,
        Double vectorThreshold,
        Integer rerankTopK,
        Double rerankThreshold,
        Boolean citationEnabled
    ) {
        /** 空请求（全部字段 null = 什么都不改）。 */
        public static UpdateRequest empty() {
            return new UpdateRequest(null, null, null, null, null, null, null, null, null);
        }
    }
}
