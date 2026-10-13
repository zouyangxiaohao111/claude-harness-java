package com.nexusai.domain.stats;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话统计聚合服务 · 按天 / 按模型维度（对齐 CC /stats 维度 · 前端「按天/按模型统计图表」数据源）。
 *
 * <p><b>数据源</b>：sessions 表每会话 {@code model_usage_json}（各模型 8 字段桶，CC original:
 * {@code lastModelUsage}，CostTracker.ModelUsage cost-tracker.ts:29-38）+ {@code total_cost_yuan}
 * （CC original: {@code total_cost_usd}，值用人民币元）+ {@code created_at}。
 *
 * <p><b>聚合语义</b>：
 * <ol>
 *   <li><b>totals</b>：{@code {sessionCount, tokenCount, costYuan}} —— 全量会话数 / 全量 token
 *       （model_usage_json 各桶 input+output 求和）/ 全量花费（total_cost_yuan 求和，null → 0）。</li>
 *   <li><b>byDay</b>：按 created_at 日期分组（取 ISO 日期部分 yyyy-MM-dd），每项
 *       {@code {date, tokenCount, costYuan}}，按日期升序。无 model_usage_json → token=0 但 costYuan 计入；
 *       无有效 created_at → 跳过 byDay（totals 仍计入）。</li>
 *   <li><b>byModel</b>：按 model_usage_json 模型 key 聚合，每项 {@code {model, inputTokens, outputTokens,
 *       cacheReadInputTokens, cacheCreationInputTokens, costUSD, anthropic}}（costUSD = 各桶 costUSD
 *       累加；<b>anthropic</b> = 该模型是否 Anthropic provider，前端据此对 total 求和分派——
 *       deepseek（非 anthropic）input 已含 cache hit，只能 input+output，不能按 anthropic 4 项和），
 *       按 token（input+output）降序。</li>
 * </ol>
 *
 * <p><b>fail-soft</b>：单会话 model_usage_json null/空白/解析失败 → 该会话 token/模型桶按零跳过，
 * 不抛异常（对齐 {@code SessionService.sumTokensFromModelUsage} 容错语义）。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    /** totals：全量汇总。 */
    public record Totals(int sessionCount, long tokenCount, double costYuan) {}

    /** byDay 项：{date, tokenCount, costYuan}。 */
    public record DayStat(String date, long tokenCount, double costYuan) {}

    /** byModel 项：{model, inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, costUSD, anthropic}。 */
    public record ModelStat(String model, long inputTokens, long outputTokens,
                            long cacheReadInputTokens, long cacheCreationInputTokens, double costUSD,
                            boolean anthropic) {}

    /** 聚合结果：{totals, byDay, byModel, byCategory, byAgent}。 */
    public record StatsResult(Totals totals, List<DayStat> byDay, List<ModelStat> byModel,
                              List<CategoryStat> byCategory, List<AgentStat> byAgent) {}

    /**
     * [usage-gateway] byCategory 项 · 按<b>归类维度</b>的用量/费用构成（来自 {@code llm_usage_calls} 明细表）。
     *
     * <p>与 byModel 的区别：byModel 回答「哪些模型烧钱」，byCategory 回答「哪类调用烧钱」
     * （主 agent / 子代理 / 压缩 / 后台 / 旁路 …）—— 这正是改造前无法回答的问题
     * （旧单点统计看不到子代理与旁路调用）。
     */
    public record CategoryStat(String category, long calls, long inputTokens, long outputTokens,
                               long cacheReadTokens, long cacheWriteTokens, double costYuan,
                               long estimatedCalls, long abortedCalls, long failedCalls,
                               long streamCalls, long nonStreamCalls) {}

    /**
     * [usage-gateway] byAgent 项 · 按<b>agent 层级</b>的构成（{@code llm_usage_calls} 明细表聚合）。
     *
     * <p>{@link #parentAgentId()} + {@link #depth()} 表达层级：可直接重建
     * 「会话 → 主 agent → 子代理 → 嵌套子代理 / 旁路」的花费树。
     */
    public record AgentStat(String agentId, String category, String parentAgentId, int depth, String label,
                            long calls, long inputTokens, long outputTokens, long cacheReadTokens,
                            long cacheWriteTokens, double costYuan, long estimatedCalls,
                            long abortedCalls, long failedCalls, long streamCalls, long nonStreamCalls) {}

    @Autowired
    private SessionMapper sessionMapper;

    /**
     * [A5-2] 模型/provider mapper · byModel 行 anthropic 标志判定（isAnthropic 原料 · 前端据此
     * 对 total 求和分派：deepseek input 已含 cache hit，不能按 anthropic 4 项和）。仿
     * {@link #sessionMapper} @Autowired 注入；未注入（测试/直构）→ isAnthropic null 回落 false →
     * 行标志 anthropic=false（deepseek/openai 语义，前端按 input+output 分派，安全默认）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    @Autowired(required = false)
    private ProviderMapper providerMapper;

    /**
     * [usage-gateway] 每 call 明细表 mapper · byCategory / byAgent 两个维度的数据源；
     * 未装配（非 Spring 单测）→ 两个维度返回空列表（fail-soft，既有 totals/byDay/byModel 不受影响）。
     */
    @Autowired(required = false)
    private com.nexusai.repository.usage.mapper.LlmUsageCallMapper usageCallMapper;

    /**
     * 聚合所有会话统计 · 数据流：sessions 全表 → 逐会话解析 model_usage_json + total_cost_yuan +
     * created_at → totals / byDay / byModel 三视图。
     */
    public StatsResult aggregate() {
        List<SessionRecord> all = sessionMapper.selectAll();

        long totalTokens = 0L;
        double totalCost = 0.0;
        Map<String, DayAgg> byDayMap = new LinkedHashMap<>();
        Map<String, ModelAgg> byModelMap = new LinkedHashMap<>();

        for (SessionRecord s : all) {
            double cost = s.getTotalCostYuan() != null ? s.getTotalCostYuan() : 0.0;
            totalCost += cost;

            Map<String, JSONObject> buckets = parseModelBuckets(s.getModelUsageJson());
            long sessionTokens = 0L;
            for (Map.Entry<String, JSONObject> e : buckets.entrySet()) {
                JSONObject b = e.getValue();
                long input = b.getLongValue("inputTokens");
                long output = b.getLongValue("outputTokens");
                sessionTokens += input + output;
                ModelAgg agg = byModelMap.computeIfAbsent(e.getKey(), k -> new ModelAgg());
                agg.inputTokens += input;
                agg.outputTokens += output;
                agg.cacheReadInputTokens += b.getLongValue("cacheReadInputTokens");
                agg.cacheCreationInputTokens += b.getLongValue("cacheCreationInputTokens");
                agg.costUSD += b.getDoubleValue("costUSD");
            }
            totalTokens += sessionTokens;

            String date = toDateKey(s.getCreatedAt());
            if (date != null) {
                DayAgg d = byDayMap.computeIfAbsent(date, k -> new DayAgg());
                d.tokenCount += sessionTokens;
                d.costYuan += cost;
            } else {
                log.warn("[StatsService] aggregate: 会话无有效 created_at，跳过 byDay（totals 仍计入）sessionId={}",
                    s.getId());
            }
        }

        int sessionCount = all.size();

        // byDay 按日期升序（ISO yyyy-MM-dd 字典序 == 时间序）
        List<DayStat> byDay = new ArrayList<>(byDayMap.size());
        List<Map.Entry<String, DayAgg>> dayEntries = new ArrayList<>(byDayMap.entrySet());
        dayEntries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, DayAgg> en : dayEntries) {
            byDay.add(new DayStat(en.getKey(), en.getValue().tokenCount, en.getValue().costYuan));
        }

        // byModel 按 token（input+output）降序
        List<ModelStat> byModel = new ArrayList<>(byModelMap.size());
        List<Map.Entry<String, ModelAgg>> modelEntries = new ArrayList<>(byModelMap.entrySet());
        modelEntries.sort((a, b) -> Long.compare(
            b.getValue().inputTokens + b.getValue().outputTokens,
            a.getValue().inputTokens + a.getValue().outputTokens));
        for (Map.Entry<String, ModelAgg> en : modelEntries) {
            ModelAgg v = en.getValue();
            // [A5-2] 每行带 anthropic 标志（前端据此分派 total 求和：anthropic=4 项和，
            //   非 anthropic（deepseek input 已含 cache hit）= input+output）。mapper 不可得 →
            //   isAnthropic false → 标志 false（deepseek/openai 语义，安全默认）。
            boolean anthropic = ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, en.getKey());
            byModel.add(new ModelStat(en.getKey(), v.inputTokens, v.outputTokens,
                v.cacheReadInputTokens, v.cacheCreationInputTokens, v.costUSD, anthropic));
        }

        if (log.isDebugEnabled()) {
            log.debug("[StatsService] aggregate: sessions={} totalTokens={} totalCost={} byDay={} byModel={}",
                sessionCount, totalTokens, totalCost, byDay.size(), byModel.size());
        }
        // [usage-gateway] 明细表维度（归类 / agent 层级）· mapper 未装配 → 两个空列表（fail-soft）
        List<CategoryStat> byCategory = aggregateByCategory();
        List<AgentStat> byAgent = aggregateByAgent();
        return new StatsResult(new Totals(sessionCount, totalTokens, totalCost), byDay, byModel,
            byCategory, byAgent);
    }

    /**
     * [usage-gateway] 按<b>归类维度</b>聚合 {@code llm_usage_calls} 明细表（每 call 一行）。
     *
     * <p>口径：calls = 明细行数（每次尝试一行，含失败/中断尝试）；estimated/aborted/failed 单列
     * 便于识别「不可对账的估算值」与「可能被上游计费的失败尝试」；costYuan 求和。
     * mapper 未装配（非 Spring 单测）/ 查询失败 → 空列表（fail-soft，不抛）。
     */
    private List<CategoryStat> aggregateByCategory() {
        List<LlmUsageCallRecord> rows = listUsageCalls();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, CategoryAgg> agg = new LinkedHashMap<>();
        for (LlmUsageCallRecord r : rows) {
            String key = r.getCategory() != null ? r.getCategory() : "UNKNOWN";
            CategoryAgg a = agg.computeIfAbsent(key, k -> new CategoryAgg());
            a.add(r);
        }
        List<CategoryStat> out = new ArrayList<>(agg.size());
        for (Map.Entry<String, CategoryAgg> e : agg.entrySet()) {
            CategoryAgg a = e.getValue();
            out.add(new CategoryStat(e.getKey(), a.calls, a.input, a.output, a.cacheRead, a.cacheWrite,
                a.costYuan, a.estimated, a.aborted, a.failed, a.streamCalls, a.nonStreamCalls));
        }
        out.sort((x, y) -> Double.compare(y.costYuan(), x.costYuan()));   // 按费用降序（谁烧钱一眼可见）
        return out;
    }

    /**
     * [usage-gateway] 按<b>agent 层级</b>聚合 {@code llm_usage_calls} 明细表。
     *
     * <p>同一 agentId 的多行合并为一个节点；{@code parentAgentId}/{@code depth}/{@code label}
     * 取该节点首行（同一 agent 的这些维度应一致，异常时以首行为准并在日志可见）。
     */
    private List<AgentStat> aggregateByAgent() {
        List<LlmUsageCallRecord> rows = listUsageCalls();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, AgentAgg> agg = new LinkedHashMap<>();
        for (LlmUsageCallRecord r : rows) {
            // [identity 归一] UUID 形式与 a+16hex 是同一 agent 的两种渲染（旁路调用点历史写入 UUID 形式）
            //   → 分组键归一，否则同一 agent 在「按代理层级」裂成两行（实测），费用被拆开。
            String key = canonicalAgentKey(r.getAgentId());
            AgentAgg a = agg.computeIfAbsent(key, k -> new AgentAgg(r));
            a.add(r);
        }
        List<AgentStat> out = new ArrayList<>(agg.size());
        for (AgentAgg a : agg.values()) {
            // 父字段用纯归一（null → null）；⛔ 不可用带 cat: 兜底的分组键函数（那会把「无父」变成 cat:UNKNOWN）
            out.add(new AgentStat(a.agentId, a.category,
                com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(a.parentAgentId),
                a.depth, a.label,
                a.bucket.calls, a.bucket.input, a.bucket.output, a.bucket.cacheRead, a.bucket.cacheWrite,
                a.bucket.costYuan, a.bucket.estimated, a.bucket.aborted, a.bucket.failed,
                a.bucket.streamCalls, a.bucket.nonStreamCalls));
        }
        out.sort((x, y) -> Integer.compare(x.depth(), y.depth()));   // 层级序（父在前）
        return out;
    }

    /** 明细表全量读（mapper 未装配 / 异常 → 空列表，fail-soft）。 */
    private List<LlmUsageCallRecord> listUsageCalls() {
        if (usageCallMapper == null) {
            return List.of();
        }
        try {
            List<LlmUsageCallRecord> rows = usageCallMapper.selectAll();
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            log.warn("[StatsService] llm_usage_calls 读取失败（byCategory/byAgent 回落空）: err={}", e.toString());
            return List.of();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // [usage-gateway · 会话级视图] 前端「用量」标签页「子代理（本会话）」行的权威数据源
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 会话级用量总计（{@code /api/v1/sessions/{id}/usage} 的 totals）。
     *
     * <p>{@code elapsedMs} = 各次尝试耗时求和（**模型调用耗时**，不是任务墙钟时长——任务时长请用
     * task 事件的 {@code duration_ms}；两者语义不同，前端不得混用同一标签）。
     */
    public record SessionUsageTotals(long calls, long inputTokens, long outputTokens,
                                     long cacheReadTokens, long cacheWriteTokens, double costYuan,
                                     long elapsedMs, long estimatedCalls, long abortedCalls,
                                     long failedCalls, long streamCalls, long nonStreamCalls) {}

    /**
     * 会话内单个 agent 节点的用量（前端「子代理（本会话）」行直接消费）。
     *
     * <p><b>{@code taskId} 的由来</b>：明细表 {@code agent_id} 列存的是 agent 身份渲染
     * （子代理 = {@code a}+16hex，退化时 UUID 串；主 agent = 会话短 id）。而任务注册表 / task 事件
     * （{@code task_id}）用的是 {@code agentId.toString()}（CC 语义 taskId === agentId）——两者
     * 由 {@link com.nexusai.application.agent.subagent.AgentContext#packAgentId(String)} 确定性互转。
     * 本字段即换算后的 {@code task_id}，供前端按 {@code task_id} 精确关联（⛔ 前端不得自行解析
     * {@code agentId} 格式），无法换算（主 agent / 无会话哨兵 / 未知格式）→ {@code null}。
     */
    public record SessionAgentUsage(String agentId, String taskId, String category, String parentAgentId,
                                    String parentTaskId, int depth, String label,
                                    long calls, long inputTokens, long outputTokens,
                                    long cacheReadTokens, long cacheWriteTokens, double costYuan,
                                    long elapsedMs, long estimatedCalls, long abortedCalls,
                                    long failedCalls, long streamCalls, long nonStreamCalls) {}

    /** 会话级用量视图（totals + 按归类 + 按 agent 节点）。 */
    public record SessionUsage(String sessionId, SessionUsageTotals totals,
                               List<CategoryStat> byCategory, List<SessionAgentUsage> agents) {}

    /**
     * 聚合单个会话的用量明细（{@code llm_usage_calls} 按 session_id 过滤）。
     *
     * <p><b>为什么需要它</b>：前端「用量」页要回答「这个会话里每个子代理花了多少」——总览统计
     * （{@link #aggregate()}）是全量的，答不了；task 事件只带进度估算且失败/中断路径常常没有
     * usage（实测表现为「统计不可用」）。明细表是记账侧的权威值，按 agent 分组即为该页所需。
     *
     * <p><b>排序</b>：agent 节点按 depth 升序（父在前），同层按费用降序（前端顺序即展示顺序）；
     * byCategory 按费用降序（与全量视图一致）。
     *
     * <p>空会话 / 明细表无该会话记录 → {@code totals} 全零 + 两个空列表（fail-soft，不抛）。
     *
     * @param sessionId 会话 id（null/空白 → {@link com.nexusai.infra.exception.ValidationException}
     *                  ⇒ 400，对齐本仓「查询参数越界返回 400 而非 500」的既有先例）
     */
    public SessionUsage sessionUsage(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("sessionId 不能为空");
        }
        List<LlmUsageCallRecord> rows = listUsageCallsForSession(sessionId);

        // 按归类（复用全量视图同一累加器，口径一致）
        Map<String, CategoryAgg> byCat = new LinkedHashMap<>();
        // 按 agent 节点（首行定身份维度，与 aggregateByAgent 同语义）
        Map<String, AgentSessionAgg> byAgent = new LinkedHashMap<>();
        CategoryAgg totals = new CategoryAgg();
        long totalElapsed = 0L;
        for (LlmUsageCallRecord r : rows) {
            totals.add(r);
            totalElapsed += nz(r.getElapsedMs());
            byCat.computeIfAbsent(r.getCategory() != null ? r.getCategory() : "UNKNOWN",
                k -> new CategoryAgg()).add(r);
            // 同上：身份归一（含归一前写入的历史行）
            String key = canonicalAgentKey(r.getAgentId());
            AgentSessionAgg a = byAgent.computeIfAbsent(key, k -> new AgentSessionAgg(r));
            a.considerIdentity(r);
            a.bucket.add(r);
            a.elapsedMs += nz(r.getElapsedMs());
        }

        List<CategoryStat> byCategory = new ArrayList<>(byCat.size());
        for (Map.Entry<String, CategoryAgg> e : byCat.entrySet()) {
            CategoryAgg a = e.getValue();
            byCategory.add(new CategoryStat(e.getKey(), a.calls, a.input, a.output, a.cacheRead, a.cacheWrite,
                a.costYuan, a.estimated, a.aborted, a.failed, a.streamCalls, a.nonStreamCalls));
        }
        byCategory.sort((x, y) -> Double.compare(y.costYuan(), x.costYuan()));

        List<SessionAgentUsage> agents = new ArrayList<>(byAgent.size());
        for (AgentSessionAgg a : byAgent.values()) {
            agents.add(new SessionAgentUsage(a.agentId, taskIdOf(a.agentId), a.category,
                com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(a.parentAgentId),
                taskIdOf(a.parentAgentId), a.depth, a.label,
                a.bucket.calls, a.bucket.input, a.bucket.output, a.bucket.cacheRead, a.bucket.cacheWrite,
                a.bucket.costYuan, a.elapsedMs, a.bucket.estimated, a.bucket.aborted, a.bucket.failed,
                a.bucket.streamCalls, a.bucket.nonStreamCalls));
        }
        agents.sort((x, y) -> {
            int byDepth = Integer.compare(x.depth(), y.depth());
            return byDepth != 0 ? byDepth : Double.compare(y.costYuan(), x.costYuan());
        });

        if (log.isDebugEnabled()) {
            log.debug("[StatsService] sessionUsage: session={} rows={} agents={} categories={} costYuan={}",
                sessionId, rows.size(), agents.size(), byCategory.size(), totals.costYuan);
        }
        return new SessionUsage(sessionId,
            new SessionUsageTotals(totals.calls, totals.input, totals.output, totals.cacheRead,
                totals.cacheWrite, totals.costYuan, totalElapsed, totals.estimated, totals.aborted,
                totals.failed, totals.streamCalls, totals.nonStreamCalls),
            byCategory, agents);
    }

    /**
     * 明细表 agentId → 任务注册表的 {@code task_id}（前端关联键）。
     *
     * <p>换算规则：{@code a}+16hex（{@code AgentContext.createAgentId} 产物）→
     * {@code packAgentId(hex).toString()}（UUID 串 = {@code registerAgentForeground} 的 taskId）；
     * 已是 UUID 串 → 原样；其余（主 agent 会话短 id / 无会话哨兵 / 未知格式）→ {@code null}
     * （无对应任务，前端不据此关联）。
     */
    static String taskIdOf(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        if (agentId.matches("a[0-9a-f]{16}")) {
            java.util.UUID uuid = com.nexusai.application.agent.subagent.AgentContext.packAgentId(agentId);
            return uuid != null ? uuid.toString() : null;
        }
        return agentId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            ? agentId
            : null;
    }

    /** 明细表按会话读（mapper 未装配 / 异常 → 空列表，fail-soft；与 {@link #listUsageCalls()} 同语义）。 */
    private List<LlmUsageCallRecord> listUsageCallsForSession(String sessionId) {
        if (usageCallMapper == null) {
            return List.of();
        }
        try {
            List<LlmUsageCallRecord> rows = usageCallMapper.selectListByQuery(
                com.mybatisflex.core.query.QueryWrapper.create().eq("session_id", sessionId));
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            log.warn("[StatsService] llm_usage_calls 按会话读取失败（sessionUsage 回落空）: session={} err={}",
                sessionId, e.toString());
            return List.of();
        }
    }

    /** 会话视图的 agent 节点累加器（身份维度按归类优先级取 + 耗时求和）。 */
    private static final class AgentSessionAgg {
        private final String agentId;
        private String category;
        private String parentAgentId;
        private int depth;
        private String label;
        private int identityRank = Integer.MAX_VALUE;
        private final CategoryAgg bucket = new CategoryAgg();
        private long elapsedMs;

        private AgentSessionAgg(LlmUsageCallRecord first) {
            this.agentId = canonicalAgentKey(first.getAgentId());
            considerIdentity(first);
        }

        /** 身份行取舍（与 {@link AgentAgg#considerIdentity} 同语义）。 */
        private void considerIdentity(LlmUsageCallRecord r) {
            int rank = categoryRank(r.getCategory());
            if (rank < identityRank) {
                identityRank = rank;
                this.category = r.getCategory();
                this.label = r.getLabel();
                this.depth = r.getDepth() != null ? r.getDepth() : 0;
                this.parentAgentId = r.getParentAgentId();
            }
        }
    }

    /**
     * 明细表 agentId → 聚合键（归一：UUID 形式 → a+16hex；无 agentId → {@code cat:<归类>} 兜底）。
     *
     * <p>同一 agent 的历史行可能同时存在两种渲染（旁路调用点在归一前写 UUID 形式）——不归一 ⇒
     * 「按代理层级」同一 agent 裂成两行、费用被拆开。归一实现单点 =
     * {@link com.nexusai.application.agent.subagent.AgentContext#canonicalAgentId(String)}。
     */
    private static String canonicalAgentKey(String agentId) {
        String canonical = com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(agentId);
        return canonical != null ? canonical : "cat:UNKNOWN";
    }

    /**
     * 节点归类的**身份优先级**：agent 自身身份（MAIN / SUBAGENT / TEAMMATE / COMPACT / BACKGROUND）
     * 优先于它在过程中发起的调用（SIDE_CALL / HOOK / CLASSIFIER / UNKNOWN）。
     *
     * <p><b>WHY</b>：节点身份原取「该 agentId 的首行」——首行是哪条调用纯属写入时序，实测出现
     * 「子代理节点被它自己的旁路调用命名」（同一 agent 一会儿显示「子代理 · 通用型」、一会儿显示
     * 「旁路调用 · 技能目录摘要」，用户读不出这是什么）。改为按本优先级取身份（同级取先到），
     * 并连带把 label / depth / parentAgentId 都取自该身份行，保证一个节点只有一种说法。
     */
    private static final List<String> NODE_CATEGORY_PRIORITY = List.of(
        "MAIN", "SUBAGENT", "TEAMMATE", "COMPACT", "BACKGROUND", "HOOK", "SIDE_CALL",
        "CLASSIFIER", "UNKNOWN");

    /** 归类身份优先级（越小越优先；未知归类排最后）。 */
    private static int categoryRank(String category) {
        int idx = NODE_CATEGORY_PRIORITY.indexOf(category != null ? category : "UNKNOWN");
        return idx < 0 ? NODE_CATEGORY_PRIORITY.size() : idx;
    }

    /** byCategory 累加器。 */
    private static final class CategoryAgg {
        private long calls;
        private long input;
        private long output;
        private long cacheRead;
        private long cacheWrite;
        private double costYuan;
        private long estimated;
        private long aborted;
        private long failed;
        private long streamCalls;
        private long nonStreamCalls;

        private void add(LlmUsageCallRecord r) {
            calls++;
            input += nz(r.getInputTokens());
            output += nz(r.getOutputTokens());
            cacheRead += nz(r.getCacheReadInputTokens());
            cacheWrite += nz(r.getCacheCreationInputTokens());
            costYuan += r.getCostYuan() != null ? r.getCostYuan().doubleValue() : 0.0;
            if ("ESTIMATED".equalsIgnoreCase(r.getTokenSource())) {
                estimated++;
            }
            if ("ABORTED".equalsIgnoreCase(r.getPhase())) {
                aborted++;
            }
            if ("FAILED".equalsIgnoreCase(r.getPhase())) {
                failed++;
            }
            if (r.getStream() != null && r.getStream() == 1) {
                streamCalls++;
            } else {
                nonStreamCalls++;
            }
        }
    }

    /** byAgent 累加器（身份维度按归类优先级取，见 {@link #categoryRank}）。 */
    private static final class AgentAgg {
        private final String agentId;
        private String category;
        private String parentAgentId;
        private int depth;
        private String label;
        /** 当前身份行的归类优先级（越小越优先；同级保留先到者）。 */
        private int identityRank = Integer.MAX_VALUE;
        private final CategoryAgg bucket = new CategoryAgg();

        private AgentAgg(LlmUsageCallRecord first) {
            this.agentId = canonicalAgentKey(first.getAgentId());
            considerIdentity(first);
        }

        private void add(LlmUsageCallRecord r) {
            considerIdentity(r);
            bucket.add(r);
        }

        /** 身份行取舍：优先级更高（或首行）→ 用它的 category/label/depth/parent。 */
        private void considerIdentity(LlmUsageCallRecord r) {
            int rank = categoryRank(r.getCategory());
            if (rank < identityRank) {
                identityRank = rank;
                this.category = r.getCategory();
                this.label = r.getLabel();
                this.depth = r.getDepth() != null ? r.getDepth() : 0;
                this.parentAgentId = r.getParentAgentId();
            }
        }
    }

    private static long nz(Integer v) {
        return v != null ? v : 0L;
    }

    /**
     * model_usage_json 列 → 模型桶 Map（JSONObject 值）· null/空白/非对象/解析失败 → 空 Map
     * （fail-soft，跳过该会话 token/模型桶，不抛；对齐 SessionService.sumTokensFromModelUsage 容错）。
     */
    private static Map<String, JSONObject> parseModelBuckets(String modelUsageJson) {
        if (modelUsageJson == null || modelUsageJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject root = JSON.parseObject(modelUsageJson);
            if (root == null) {
                return new LinkedHashMap<>();
            }
            Map<String, JSONObject> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getValue() instanceof JSONObject bucket) {
                    result.put(e.getKey(), bucket);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[StatsService] parseModelBuckets 解析失败（fail-soft 跳过该会话）: err={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * created_at（ISO 时间串，如 2026-08-26T10:00:00+08:00）→ 日期键（yyyy-MM-dd）。
     * null/空白/非 ISO 日期 → null（调用方跳过 byDay）。仅首 10 字符形如 yyyy-MM-dd 才返回。
     */
    private static String toDateKey(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        String datePart = createdAt;
        int t = datePart.indexOf('T');
        if (t >= 0) {
            datePart = datePart.substring(0, t);
        }
        if (datePart.length() >= 10 && datePart.charAt(4) == '-' && datePart.charAt(7) == '-') {
            return datePart.substring(0, 10);
        }
        return null;
    }

    /** byDay 累加器（可变）· 单会话 token/cost 累加进对应日期。 */
    private static final class DayAgg {
        long tokenCount;
        double costYuan;
    }

    /** byModel 累加器（可变）· 单模型各桶字段跨会话累加。 */
    private static final class ModelAgg {
        long inputTokens;
        long outputTokens;
        long cacheReadInputTokens;
        long cacheCreationInputTokens;
        double costUSD;
    }
}
