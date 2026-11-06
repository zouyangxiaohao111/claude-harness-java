package com.nexusai.kb.agent.eval;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 评测用例（{@code kb_eval_case}）· V275 建表 · 批次 W11。
 *
 * <p>一条 = 一个问题 + 可选的标准答案 + 可选的期望 chunk。
 * 标准答案为空 ⇒ 只评检索；{@code expectChunkIds} 为空 ⇒ 不评召回。
 */
@Table(
    value = "kb_eval_case",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbEvalCaseRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String datasetId;

    private String question;

    /** 标准答案（可空 = 只评检索）。 */
    private String expectedAnswer;

    /** JSON 数组：期望命中的 chunk id（'[]' = 不评召回）。 */
    private String expectChunkIds;

    /** 执行顺序（保证结果可复现）。 */
    private Integer seq;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String v) { this.datasetId = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { this.question = v; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public void setExpectedAnswer(String v) { this.expectedAnswer = v; }
    public String getExpectChunkIds() { return expectChunkIds; }
    public void setExpectChunkIds(String v) { this.expectChunkIds = v; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer v) { this.seq = v; }
}
