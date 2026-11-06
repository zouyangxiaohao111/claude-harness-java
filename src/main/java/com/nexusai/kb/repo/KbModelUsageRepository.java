package com.nexusai.kb.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 模型引用检查 · 「这个模型被哪些知识库/全局配置用着」。
 *
 * <h2>为什么必须做这件事（用户 2026-11-04 裁定「推荐拦」）</h2>
 * 停用或删除一个 embedding / rerank 模型后，知识库的检索会**静默降级**：
 * <ul>
 *   <li>embedding 模型失效 ⇒ 向量路整段跳过，只剩关键词召回</li>
 *   <li>rerank 模型失效 ⇒ 重排降级，排序质量下降</li>
 * </ul>
 * 两种情况都<b>不报错</b>——日志里最多一条 WARN。用户能感知到的只有
 * 「怎么突然搜不准了」，排查成本极高。
 * 宁可在删除时明确拦住（409），也不要制造这种静默故障。
 *
 * <h2>查哪些位置</h2>
 * <table>
 *   <tr><th>位置</th><th>列</th></tr>
 *   <tr><td>知识库（每库覆盖）</td><td>{@code kb_knowledge_base.embedding_model_id} / {@code rerank_model_id}</td></tr>
 *   <tr><td>知识条目（单条覆盖）</td><td>{@code kb_knowledge.embedding_model_id}</td></tr>
 *   <tr><td>全局默认</td><td>{@code settings.kb_embedding_model_id} / {@code kb_rerank_model_id}</td></tr>
 * </table>
 */
@Repository
public class KbModelUsageRepository {

    private final JdbcTemplate jdbc;

    public KbModelUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 引用统计。
     *
     * @param knowledgeBaseCount 引用该模型的知识库数（未删除的）
     * @param knowledgeCount     引用该模型的知识条目数（未删除的）
     * @param usedAsGlobalDefault 是否被设为全局默认
     */
    public record Usage(long knowledgeBaseCount, long knowledgeCount, boolean usedAsGlobalDefault) {

        /** 是否完全没人引用。 */
        public boolean isUnused() {
            return knowledgeBaseCount == 0 && knowledgeCount == 0 && !usedAsGlobalDefault;
        }

        /** 人话描述（用于 409 错误消息）。 */
        public String describe() {
            if (isUnused()) {
                return "未被使用";
            }
            StringBuilder sb = new StringBuilder();
            if (knowledgeBaseCount > 0) {
                sb.append(knowledgeBaseCount).append(" 个知识库");
            }
            if (knowledgeCount > 0) {
                if (sb.length() > 0) sb.append("、");
                sb.append(knowledgeCount).append(" 个知识条目");
            }
            if (usedAsGlobalDefault) {
                if (sb.length() > 0) sb.append("、");
                sb.append("全局默认配置");
            }
            return sb.toString();
        }
    }

    /**
     * 统计某模型的引用情况。
     *
     * <p>⚠️ 只统计 {@code deleted = 0} 的行 —— 已删除的知识库不该阻止用户清理模型。
     *
     * @param modelId 模型 ID（{@code models.id}）
     * @return 引用统计
     */
    public Usage countUsage(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return new Usage(0, 0, false);
        }
        long kbCount = queryLong(
            "SELECT COUNT(*) FROM kb_knowledge_base "
                + "WHERE deleted = 0 AND (embedding_model_id = ? OR rerank_model_id = ?)",
            modelId, modelId);
        long knowledgeCount = queryLong(
            "SELECT COUNT(*) FROM kb_knowledge "
                + "WHERE deleted = 0 AND embedding_model_id = ?",
            modelId);
        boolean global = queryLong(
            "SELECT COUNT(*) FROM settings "
                + "WHERE kb_embedding_model_id = ? OR kb_rerank_model_id = ?",
            modelId, modelId) > 0;
        return new Usage(kbCount, knowledgeCount, global);
    }

    private long queryLong(String sql, Object... args) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, args);
            return v == null ? 0L : v;
        } catch (Exception e) {
            // 表不存在（老库未迁移）⇒ 视为无引用，⛔ 不因统计失败而阻断正常删除
            return 0L;
        }
    }
}
