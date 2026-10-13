package com.nexusai.repository.usage.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * llm_usage_calls 表（V98）记录 · 每 call LLM 用量/费用明细行（新增表，新无既有 owner）。
 *
 * <p><b>粒度</b>：一次「尝试」一行 —— 常规调用 1 行；流式失败后回落非流式 2 行
 * （两次上游都可能计费）；被 abort 的流式 1 行。行载荷源头为
 * {@code infra.llm.LlmCallRecord}（+ {@code UsageAttribution} 归属维度）。
 *
 * <p>列映射（MyBatis-Flex camelCase → snake_case 自动映射）逐列与
 * {@code db/migration/V98__create_llm_usage_calls.sql} 对齐：
 * {@code id}=「uc- 前缀 + UUID8」（{@code LlmCallRecord.newCallId()}）；
 * {@code session_id}/{@code agent_id}/{@code category}/{@code parent_agent_id}/{@code depth}/
 * {@code label}=归属树的五维 + 标签（{@code UsageAttribution}）；
 * {@code model_name}（计费键）/ {@code sdk_model_name}（上游实际名）/ {@code provider_type}/
 * {@code query_source}=调用来源四元组；{@code stream}/{@code phase}/{@code token_source}=
 * 结局与数值可信度；{@code input_tokens}/{@code output_tokens}/{@code cache_read_input_tokens}/
 * {@code cache_creation_input_tokens}=用量（cache 两列可 null = 无 cache 数据）；
 * {@code cost_yuan}=该次尝试折算费用（元，可 null）；{@code peak_hour}=计价时段；
 * {@code status}/{@code error_kind}=调用状态与错误类别；{@code started_at}=调用开始时刻
 * （ISO-8601 文本，对齐 sessions 时间列风格）；{@code elapsed_ms}=耗时；
 * {@code created_at}=行写入时刻（SQLite UTC）。
 *
 * <p><b>local-only 边界</b>：本表只走「网关 → 账本 → 明细表」链路，不进 STOMP / outbound DTO /
 * LLM 请求 payload。数值来源为 {@code ESTIMATED} 的行仅观测，不得与 {@code UPSTREAM} 相加当账单。
 */
@Table("llm_usage_calls")
public class LlmUsageCallRecord {

    /** 调用标识（应用生成「uc- 前缀 + UUID8」；TEXT PRIMARY KEY，⛔ 非自增）。 */
    @Id
    private String id;
    /** 归属会话（可 null = 无会话上下文的旁路调用）。 */
    private String sessionId;
    /** 归属 agent（主 agent = 会话短 id；子代理 = a+16hex；teammate = 全名；可 null）。 */
    private String agentId;
    /** 归类维度（MAIN|SUBAGENT|TEAMMATE|COMPACT|BACKGROUND|HOOK|SIDE_CALL|CLASSIFIER|UNKNOWN）。 */
    private String category;
    /** 父 agent id（null = 顶层）。 */
    private String parentAgentId;
    /** 层级深度（0 = 顶层 agent；子代理 1；嵌套 2…）。 */
    private Integer depth;
    /** 可读标签（subagentName / skill 名 / cron workload / 旁路用途名；可 null）。 */
    private String label;
    /** 计费用模型名（DB 模型名，价格解析键）。 */
    private String modelName;
    /** 实际发给上游的模型名（已剥 provider 前缀）。 */
    private String sdkModelName;
    /** provider 类型（openai_sdk / anthropic / mock…）。 */
    private String providerType;
    /** 调用来源字符串（QuerySource 值或旁路自述名；可 null）。 */
    private String querySource;
    /** 是否流式（1/0）。 */
    private Integer stream;
    /** 尝试结局（SETTLED|ABORTED|FAILED）。 */
    private String phase;
    /** 数值来源可信度（UPSTREAM|ESTIMATED|UNKNOWN）。 */
    private String tokenSource;
    /** 输入 token（默认 0）。 */
    private Integer inputTokens;
    /** 输出 token（默认 0）。 */
    private Integer outputTokens;
    /** cache 读 token（可 null = 无 cache 数据）。 */
    private Integer cacheReadInputTokens;
    /** cache 写 token（可 null = 无 cache 数据）。 */
    private Integer cacheCreationInputTokens;
    /** 该次尝试折算费用（元；DECIMAL(14,8)，可 null = 无计费）。 */
    private Double costYuan;
    /** 计价时段（1 = 高峰档）。 */
    private Integer peakHour;
    /** 调用状态短标签（ok|aborted|error；可 null）。 */
    private String status;
    /** 错误类别（可 null；仅 status != ok 时填）。 */
    private String errorKind;
    /** 调用开始时刻（ISO-8601 文本，对齐 sessions 时间列 V1/V50 风格）。 */
    private String startedAt;
    /** 调用耗时（ms）。 */
    private Integer elapsedMs;
    /** 行写入时刻（SQLite UTC）。 */
    private String createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getParentAgentId() {
        return parentAgentId;
    }

    public void setParentAgentId(String parentAgentId) {
        this.parentAgentId = parentAgentId;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSdkModelName() {
        return sdkModelName;
    }

    public void setSdkModelName(String sdkModelName) {
        this.sdkModelName = sdkModelName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getQuerySource() {
        return querySource;
    }

    public void setQuerySource(String querySource) {
        this.querySource = querySource;
    }

    public Integer getStream() {
        return stream;
    }

    public void setStream(Integer stream) {
        this.stream = stream;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getTokenSource() {
        return tokenSource;
    }

    public void setTokenSource(String tokenSource) {
        this.tokenSource = tokenSource;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    public void setCacheReadInputTokens(Integer cacheReadInputTokens) {
        this.cacheReadInputTokens = cacheReadInputTokens;
    }

    public Integer getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public Double getCostYuan() {
        return costYuan;
    }

    public void setCostYuan(Double costYuan) {
        this.costYuan = costYuan;
    }

    public Integer getPeakHour() {
        return peakHour;
    }

    public void setPeakHour(Integer peakHour) {
        this.peakHour = peakHour;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorKind() {
        return errorKind;
    }

    public void setErrorKind(String errorKind) {
        this.errorKind = errorKind;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public Integer getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Integer elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
