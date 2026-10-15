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
 * <p><b>V101 新增列（费用分项）</b>：{@code cost_input}/{@code cost_output}/{@code cost_cache_read}/
 * {@code cost_cache_write}/{@code cost_no_cache_input}=计价点的分项（元，见
 * {@code ModelCostCalculator.CostBreakdown}；不变式 {@code cost_yuan == cost_input + cost_output +
 * cost_cache_read + cost_cache_write}，反事实项 {@code cost_no_cache_input} 不计入总额）；
 * {@code cost_detail_source}=分项来源（{@code PRICED} = V101 起本次调用已落分项；
 * {@code LEGACY} = V101 之前的历史行 → 五列全 null，聚合时单列统计、⛔ 不摊分）。
 * V101 同时给 {@code started_at} 加单列索引，并把写入格式统一为「本地时区 + 偏移」的
 * ISO-8601 文本（详见 {@code startedAt} 字段注释）。
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
    /** 可读标签（可 null）。**子代理/fork/队友节点 = 该次任务的名称**（CC AgentTool 的
     *  {@code description}，3-5 词任务短名 · 与任务注册表同源；取不到则回落 agent 类型名如
     *  {@code general-purpose}）；旁路调用 = 用途 slug；压缩 = 压缩标签；cron = workload 名。 */
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
    /**
     * 输入侧分项费用（元；V101 列）。
     *
     * <p>anthropic = 全部 input 全价；非 anthropic（deepseek/openai，input 已含 cache hit）= 扣掉命中后的
     * miss 全价。<b>可 null</b>：{@code cost_detail_source='LEGACY'} 的历史行（V101 之前）没有分项，
     * 分项列全为 null（读侧单列统计，⛔ 不摊分不伪造）。
     */
    private Double costInput;
    /** 输出侧分项费用（元；V101 列；两分支同式）。可 null（LEGACY 行）。 */
    private Double costOutput;
    /** cache 命中（读）分项费用（元；V101 列；按命中折价）。可 null（LEGACY 行）。 */
    private Double costCacheRead;
    /** cache 写入分项费用（元；V101 列；仅 anthropic，非 anthropic = 0.0）。可 null（LEGACY 行）。 */
    private Double costCacheWrite;
    /**
     * <b>反事实诊断项</b>：该轮若一次 cache 都没有（命中/写入都按 input 全价）input 侧要花多少（元；V101 列）。
     *
     * <p>⛔ <b>不计入</b> {@code cost_yuan}。读侧「cache 省了多少钱」=
     * {@code cost_no_cache_input − (cost_input + cost_cache_read + cost_cache_write)}
     * （anthropic = 命中×(全价−命中价) + 写入×(全价−写入价)；非 anthropic = 命中×(全价−命中价)）。
     * 可 null（LEGACY 行）。
     */
    private Double costNoCacheInput;
    /**
     * 分项来源（V101 列；NOT NULL DEFAULT 'LEGACY'）。
     *
     * <p>{@code "PRICED"} = V101 起本次调用已在计价点算出分项（上面五列有值）；
     * {@code "LEGACY"} = V101 之前的历史行、以及计价器缺失时的兜底（五列全 null）。
     * 聚合时 LEGACY 单列统计，⛔ 不摊分、不按比例倒推。
     */
    private String costDetailSource;
    /** 计价时段（1 = 高峰档）。 */
    private Integer peakHour;
    /** 调用状态短标签（ok|aborted|error；可 null）。 */
    private String status;
    /** 错误类别（可 null；仅 status != ok 时填）。 */
    private String errorKind;
    /**
     * 调用开始时刻（ISO-8601 文本，对齐 sessions 时间列 V1/V50 风格）。
     *
     * <p><b>V101 格式统一（DM1 裁定：业务时间列 = ISO-8601 带偏移文本，应用显式写入）</b>：
     * 由 {@code SessionUsageLedger} 写「本地时区 + 偏移」（例 {@code 2026-10-13T10:24:38.645+08:00}），
     * ⛔ 不用 {@code Instant.toString()} 的 UTC {@code Z} 结尾形式 —— 同秒内 {@code ...38Z} 与
     * {@code ...38.645Z} 的字典序相反（{@code Z}=90 &gt; {@code .}=46），会让区间过滤错序；
     * 本地带偏移后 {@code +}=43 &lt; {@code .}=46 &lt; 数字 ⇒ 字典序 == 时间序（同偏移），
     * 区间过滤可直接用字符串比较并吃 V101 的 {@code idx_llm_usage_calls_started_at}。
     */
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

    public Double getCostInput() {
        return costInput;
    }

    public void setCostInput(Double costInput) {
        this.costInput = costInput;
    }

    public Double getCostOutput() {
        return costOutput;
    }

    public void setCostOutput(Double costOutput) {
        this.costOutput = costOutput;
    }

    public Double getCostCacheRead() {
        return costCacheRead;
    }

    public void setCostCacheRead(Double costCacheRead) {
        this.costCacheRead = costCacheRead;
    }

    public Double getCostCacheWrite() {
        return costCacheWrite;
    }

    public void setCostCacheWrite(Double costCacheWrite) {
        this.costCacheWrite = costCacheWrite;
    }

    public Double getCostNoCacheInput() {
        return costNoCacheInput;
    }

    public void setCostNoCacheInput(Double costNoCacheInput) {
        this.costNoCacheInput = costNoCacheInput;
    }

    public String getCostDetailSource() {
        return costDetailSource;
    }

    public void setCostDetailSource(String costDetailSource) {
        this.costDetailSource = costDetailSource;
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
