package com.nexusai.kb.app.graph.store;

import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;

import java.util.List;

/**
 * 图谱存储接缝 · 让「抽出来的实体关系」可以落在<b>本地 SQLite</b> 或<b>远程 Neo4j</b>。
 *
 * <h2>⭐ 为什么需要这一层（2026-11-11 用户要求「兼容 Neo4j，随时可配远程实例」）</h2>
 * 本仓图谱最初硬编码在 {@code kb_graph_entity} / {@code kb_graph_relation} 两张 SQLite 表，
 * 三个消费点（抽取写入、独立检索、问答链实体路）直接调 MyBatis mapper。
 * 要支持远程 Neo4j，必须先把「怎么存、怎么查」抽象掉 —— 否则改 Neo4j 就得同时改三处，
 * 且每处都要写两份分支。
 *
 * <h2>方法面为什么只有这四个</h2>
 * 逐条核对源侧 {@code interfaces.RetrieveGraphRepository}（Go）与三个消费点的实际调用：
 * <table>
 *   <tr><th>方法</th><th>谁用</th><th>对应源站</th></tr>
 *   <tr><td>{@link #replaceGraph}</td><td>抽取服务（先清后写）</td>
 *       <td>{@code DelGraph} + {@code AddGraph}</td></tr>
 *   <tr><td>{@link #deleteByKnowledge}</td><td>文档重解析 / 删除</td><td>{@code DelGraph}</td></tr>
 *   <tr><td>{@link #searchNodes}</td><td>问答链实体路（种子 + 一跳邻居）</td>
 *       <td>{@code SearchNode}</td></tr>
 *   <tr><td>{@link #readAll}</td><td>独立 graph-search 接口 + 前端可视化</td>
 *       <td>{@code SearchGraph}</td></tr>
 * </table>
 *
 * <h2>幂等语义</h2>
 * {@link #replaceGraph} 是「整篇文档的图谱替换」：先按 knowledgeId 清空，再写入本次抽取结果。
 * 远程实现里对应 {@code apoc.merge.node} + 先删旧边 —— ⛔ 不要改成「追加」，
 * 那会在重解析后留下过期实体。
 */
public interface KbGraphStore {

    /**
     * 用本次抽取结果<b>替换</b>某文档的图谱。
     *
     * @param knowledgeBaseId 知识库 id
     * @param knowledgeId     文档 id
     * @param entities        实体（已回填 chunkIds）
     * @param relations       关系
     */
    void replaceGraph(String knowledgeBaseId, String knowledgeId,
                      List<KbGraphEntityRecord> entities,
                      List<KbGraphRelationRecord> relations);

    /** 删除某文档的全部图谱（重解析前 / 文档删除时）。 */
    void deleteByKnowledge(String knowledgeId);

    /**
     * 按知识库 + 实体名取实体行（供问答链做子串匹配）。
     *
     * <p>⚠️ 返回的是**存储形态**的原始行（含 {@code chunkIds} JSON），
     * 匹配/排序/截断等语义由调用方负责 —— 这样本地与远程实现的行为差异最小。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 该库全部实体（空库 ⇒ 空表，⛔ 不返回 null）
     */
    List<KbGraphEntityRecord> listEntities(String knowledgeBaseId);

    /**
     * 按知识库取全部关系（供一跳邻居展开）。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 该库全部关系（空库 ⇒ 空表）
     */
    List<KbGraphRelationRecord> listRelations(String knowledgeBaseId);
}
