package com.nexusai.domain.stats;

import com.nexusai.application.agent.cost.CacheHitRate;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计聚合服务 · 按天 / 按模型 / 按归类 / 按 agent 层级四维 + 会话级视图。
 *
 * <h2>数据源（唯一真源）</h2>
 * {@code llm_usage_calls} 明细表（V98 建表 · V101 加分项列）——<b>每次 LLM 尝试一行</b>。
 * ⛔ <b>不再读</b> {@code sessions.model_usage_json} / {@code sessions.total_cost_yuan}：
 * 那两个列是<b>会话级快照</b>（写入时机 = 会话保存点，且只覆盖主 agent 的调用），
 * 与明细账天然不等（历史 bug：本服务的 {@code totals.costYuan} 取自快照、
 * {@code byCategory} 取自明细表 → 同一页面两个数字对不上，且子代理/旁路花费只在一边可见）。
 * 现在四个维度与 totals <b>同源同区间</b>，因此<b>口径自洽可对账</b>
 * （{@code totals} 的各列 == 各维度同列求和，见 {@code StatsServiceRangeTest} 的对账用例）。
 *
 * <h2>聚合下推到 SQL</h2>
 * 全部维度经 {@code mapper.selectListByQueryAs(wrapper, Dto.class)} 由 SQLite 完成
 * {@code SUM/COUNT/GROUP BY}（⛔ 不再 {@code selectAll()} 后在 JVM 聚合 —— 明细表随调用量线性增长，
 * 全量拉回内存等于把统计接口的耗时挂到调用总量上）。JVM 只做三件 SQL 做不了的事：
 * <ol>
 *   <li><b>agent 身份归一</b>（{@code a+16hex} ⇄ UUID 两种渲染合并为同一节点）；</li>
 *   <li><b>节点身份取舍</b>（{@link #NODE_CATEGORY_PRIORITY}）；</li>
 *   <li><b>缓存命中率的按模型合并</b>（分母依赖「该模型是否 anthropic」，见 {@link CacheHitRate}）。</li>
 * </ol>
 *
 * <h2>时间区间（{@code from} 含 / {@code to} 不含）</h2>
 * 区间过滤直接落在 {@code started_at} <b>字符串比较</b>上（{@code started_at >= ? AND started_at < ?}），
 * 以命中 V101 的 {@code idx_llm_usage_calls_started_at}。<b>为什么字符串比较是安全的</b>：
 * 本仓 DM1 裁定业务时间列 = ISO-8601 带偏移文本（见 {@code ScanJobRecord} javadoc），且
 * {@code SessionUsageLedger} 写入侧已统一为
 * {@code OffsetDateTime.ofInstant(..., systemDefault()).toString()}（例 {@code 2026-10-13T10:24:38.645+08:00}）
 * —— 同一偏移下字典序 == 时间序（{@code '+'}(43) < {@code '.'}(46) < 数字），
 * 且「省略秒」的写法（{@code ...T10:24+08:00}）是「带秒」写法的严格前缀，
 * 比较仍与时间序一致。⛔ 若写入侧改回 UTC {@code Z} 结尾，{@code Z}(90) > {@code .}(46)
 * 会让 {@code ...38Z} 排在 {@code ...38.645Z} 之后，区间过滤静默漏行。
 *
 * <h2>L1 行为变更（相对上一版）</h2>
 * <ul>
 *   <li><b>byModel</b>：删 {@code costUSD}（改为 {@code costYuan}，与全站「元」口径统一）、
 *       删 {@code anthropic}（前端不再需要自己按协议分派求和 —— 直接用 {@code totalTokens} 与
 *       {@code cacheHitRate}）；新增 {@code calls} 与四类分项费用。</li>
 *   <li><b>byDay</b>：由「<b>会话创建日</b>」改为「<b>调用发生日</b>」（{@code substr(started_at,1,10)}，
 *       本地日期）——统计页的「按天」此前会把一段跨天会话的全部花费记在会话创建那天。</li>
 *   <li><b>totals.sessionCount</b>：由「sessions 表全量行数」改为
 *       {@code COUNT(DISTINCT session_id)} = <b>区间内发生过 LLM 调用的会话数</b>
 *       （语义变更：只建了会话没调过模型的会话不再计入）。</li>
 * </ul>
 *
 * <h2>fail-soft</h2>
 * 统计链路失败绝不外抛（调用方是只读接口，异常会变 500 弹窗）：mapper 未装配 / SQL 异常 →
 * WARN + 该维度回落空列表（totals 回落全零）；缓存节省为负（公式异常）→ WARN + 钳到 0，不抛。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 对外 JSON 契约（record 组件名 ⇄ JSON 键由 Jackson 默认策略决定，前端按字段名消费）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 本次查询的区间回显 · {@code from} 含 / {@code to} 不含（均为归一后的 ISO-8601 文本，
     * 未指定 → null）；{@code filtered} = 是否带了区间（false = 全量）。
     */
    public record Range(String from, String to, boolean filtered) {}

    /**
     * 全量汇总（区间内）。
     *
     * <p>{@code sessionCount} = {@code COUNT(DISTINCT session_id)}（区间内<b>有调用</b>的会话数，
     * 见类 javadoc 的 L1 行为变更）；{@code tokenCount} = {@code inputTokens + outputTokens}
     * （⛔ 不含 cache）。
     *
     * <p>费用四列是<b>分项</b>（{@code costYuan} 为权威总额；{@code costInput/output/cacheRead/cacheWrite}
     * = 分项求和，仅 PRICED 行贡献 —— LEGACY 行的分项列为 NULL，<b>不摊分、不伪造</b>）。
     * {@code costNoCacheInput} 是<b>反事实</b>项（「若完全无缓存命中」应付的价格），
     * ⛔ 不计入 {@code costYuan}；{@code cacheSavingsYuan} = 省下的钱
     * （{@code costNoCacheInput - (costInput + costCacheRead + costCacheWrite)}，负数钳 0 + WARN）。
     *
     * <p>{@code cacheHitRate} = 0-1 比率（{@code null} = 无 prompt token）；{@code legacyCalls}
     * = V101 之前的历史行数（分项缺失，单列统计）。
     */
    public record Totals(int sessionCount, long calls, long tokenCount, long inputTokens,
                         long outputTokens, long cacheReadTokens, long cacheWriteTokens, double costYuan,
                         double costInput, double costOutput, double costCacheRead, double costCacheWrite,
                         double costNoCacheInput, double cacheSavingsYuan, Double cacheHitRate,
                         long estimatedCalls, long abortedCalls, long failedCalls,
                         long legacyCalls, double legacyCostYuan) {}

    /** byDay 项：{@code {date, calls, tokenCount, costYuan}}（{@code date} = <b>调用发生日</b>，本地日期）。 */
    public record DayStat(String date, long calls, long tokenCount, double costYuan) {}

    /**
     * byModel 项：{@code {model, calls, inputTokens, outputTokens, cacheReadInputTokens,
     * cacheCreationInputTokens, totalTokens, costYuan, costInput, costOutput, costCacheRead,
     * costCacheWrite, cacheHitRate}}。
     *
     * <p>⛔ <b>L1 变更</b>：原 {@code costUSD} → {@code costYuan}；原 {@code anthropic} 标志删除
     * （前端改用 {@code totalTokens}（= input+output）与 {@code cacheHitRate}，不再自行按协议分派求和）。
     * {@code cacheHitRate} 按<b>该模型</b>是否 anthropic 分派分母（见 {@link CacheHitRate}）。
     */
    public record ModelStat(String model, long calls, long inputTokens, long outputTokens,
                            long cacheReadInputTokens, long cacheCreationInputTokens, long totalTokens,
                            double costYuan, double costInput, double costOutput, double costCacheRead,
                            double costCacheWrite, Double cacheHitRate) {}

    /**
     * byCategory 项 · 按<b>归类维度</b>的用量/费用构成（字段集与改造前<b>逐字一致</b>，仅数据源换为明细表）。
     *
     * <p>与 byModel 的区别：byModel 回答「哪些模型烧钱」，byCategory 回答「哪类调用烧钱」
     * （主 agent / 子代理 / 压缩 / 后台 / 旁路 …）。口径 = 每次<b>尝试</b>一行：
     * {@code calls} 含失败/中断尝试；{@code estimatedCalls} 单列（估算值不可当账单）；
     * {@code abortedCalls}/{@code failedCalls} 单列（可能被上游计费）。
     */
    public record CategoryStat(String category, long calls, long inputTokens, long outputTokens,
                               long cacheReadTokens, long cacheWriteTokens, double costYuan,
                               long estimatedCalls, long abortedCalls, long failedCalls,
                               long streamCalls, long nonStreamCalls) {}

    /**
     * byAgent 项 · 按<b>agent 层级</b>的构成（字段集与改造前<b>逐字一致</b>）。
     *
     * <p>{@link #parentAgentId()} + {@link #depth()} 表达层级：可直接重建
     * 「会话 → 主 agent → 子代理 → 嵌套子代理 / 旁路」的花费树。身份维度（category/label/depth/parent）
     * 按 {@link #NODE_CATEGORY_PRIORITY} 取舍（见 {@link #categoryRank}）。
     */
    public record AgentStat(String agentId, String category, String parentAgentId, int depth, String label,
                            long calls, long inputTokens, long outputTokens, long cacheReadTokens,
                            long cacheWriteTokens, double costYuan, long estimatedCalls,
                            long abortedCalls, long failedCalls, long streamCalls, long nonStreamCalls) {}

    /** 聚合结果：{@code {range, totals, byDay, byModel, byCategory, byAgent}}。 */
    public record StatsResult(Range range, Totals totals, List<DayStat> byDay, List<ModelStat> byModel,
                              List<CategoryStat> byCategory, List<AgentStat> byAgent) {}

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 依赖（全部 @Autowired(required=false) · 非 Spring 单测/未装配 → 对应维度 fail-soft 回落）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 明细表 mapper · 全量视图与会话视图的唯一数据源。 */
    @Autowired(required = false)
    private com.nexusai.repository.usage.mapper.LlmUsageCallMapper usageCallMapper;

    /**
     * 模型/provider mapper · 仅用于「该模型是否 anthropic」的协议分派判定
     * （缓存命中率分母 + 上下文口径）。未注入（测试/直构）→ isAnthropic 回落 false →
     * 非 anthropic 语义（input 已含 cache hit，分母 = input，安全默认，与计费侧一致）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    @Autowired(required = false)
    private ProviderMapper providerMapper;

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 全量统计
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 全量聚合（等价 {@code aggregate(null, null)}）· 保留供内部/既有调用方使用。 */
    public StatsResult aggregate() {
        return aggregate(null, null);
    }

    /**
     * 按时间区间聚合四个维度 + totals · 全部下推到 SQL。
     *
     * @param from 区间起点（<b>含</b>）· ISO-8601 带偏移文本或 epoch 毫秒；null/空白 = 不限
     * @param to   区间终点（<b>不含</b>）· 同上；null/空白 = 不限
     * @return {range, totals, byDay, byModel, byCategory, byAgent}
     * @throws ValidationException 时间参数既不是 epoch 毫秒也不是合法 ISO-8601（⇒ 400，⛔ 不是 500）
     */
    public StatsResult aggregate(String from, String to) {
        String fromIso = normalizeBound(from, "from");
        String toIso = normalizeBound(to, "to");
        Range range = new Range(fromIso, toIso, fromIso != null || toIso != null);

        List<ModelRow> modelRows = aggregateModelRows(fromIso, toIso);
        Totals totals = aggregateTotals(fromIso, toIso, modelRows);
        List<DayStat> byDay = aggregateByDay(fromIso, toIso);
        List<ModelStat> byModel = toModelStats(modelRows);
        List<CategoryStat> byCategory = aggregateByCategory(fromIso, toIso);
        List<AgentStat> byAgent = aggregateByAgent(fromIso, toIso);

        if (log.isDebugEnabled()) {
            log.debug("[StatsService] aggregate: range={} sessionCount={} calls={} costYuan={} "
                    + "byDay={} byModel={} byCategory={} byAgent={}",
                range, totals.sessionCount(), totals.calls(), totals.costYuan(),
                byDay.size(), byModel.size(), byCategory.size(), byAgent.size());
        }
        return new StatsResult(range, totals, byDay, byModel, byCategory, byAgent);
    }

    /** totals 单行聚合（SQL：COUNT/SUM 全量一行）；{@code modelRows} 供命中率逐模型分派分母。 */
    private Totals aggregateTotals(String fromIso, String toIso, List<ModelRow> modelRows) {
        QueryWrapper w = base(fromIso, toIso)
            .select(
                "COUNT(DISTINCT session_id) AS session_count",
                "COUNT(*) AS calls",
                "COALESCE(SUM(input_tokens),0) AS input_tokens",
                "COALESCE(SUM(output_tokens),0) AS output_tokens",
                "COALESCE(SUM(cache_read_input_tokens),0) AS cache_read_tokens",
                "COALESCE(SUM(cache_creation_input_tokens),0) AS cache_write_tokens",
                "COALESCE(SUM(cost_yuan),0) AS cost_yuan",
                "COALESCE(SUM(cost_input),0) AS cost_input",
                "COALESCE(SUM(cost_output),0) AS cost_output",
                "COALESCE(SUM(cost_cache_read),0) AS cost_cache_read",
                "COALESCE(SUM(cost_cache_write),0) AS cost_cache_write",
                "COALESCE(SUM(cost_no_cache_input),0) AS cost_no_cache_input",
                "COALESCE(SUM(CASE WHEN token_source = 'ESTIMATED' THEN 1 ELSE 0 END),0) AS estimated_calls",
                "COALESCE(SUM(CASE WHEN phase = 'ABORTED' THEN 1 ELSE 0 END),0) AS aborted_calls",
                "COALESCE(SUM(CASE WHEN phase = 'FAILED' THEN 1 ELSE 0 END),0) AS failed_calls",
                "COALESCE(SUM(CASE WHEN cost_detail_source = 'LEGACY' THEN 1 ELSE 0 END),0) AS legacy_calls",
                "COALESCE(SUM(CASE WHEN cost_detail_source = 'LEGACY' THEN cost_yuan ELSE 0 END),0) AS legacy_cost_yuan");
        List<TotalsRow> rows = queryAs(w, TotalsRow.class);
        if (rows.isEmpty()) {
            return new Totals(0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null,
                0, 0, 0, 0, 0.0);
        }
        TotalsRow r = rows.get(0);
        // 命中率必须逐模型分派分母（同一区间可混跑 anthropic 与 deepseek）→ 复用 byModel 的逐模型聚合行
        Double hitRate = hitRateOfModelRows(modelRows);
        return new Totals((int) r.sessionCount, r.calls,
            r.inputTokens + r.outputTokens, r.inputTokens, r.outputTokens,
            r.cacheReadTokens, r.cacheWriteTokens, r.costYuan,
            r.costInput, r.costOutput, r.costCacheRead, r.costCacheWrite,
            r.costNoCacheInput,
            cacheSavingsYuan(r.costNoCacheInput, r.costInput, r.costCacheRead,
                r.costCacheWrite, "totals"),
            hitRate,
            r.estimatedCalls, r.abortedCalls, r.failedCalls,
            r.legacyCalls, r.legacyCostYuan);
    }

    /** byDay：按<b>调用发生日</b>（{@code substr(started_at,1,10)}，本地日期）分组，日期升序。 */
    private List<DayStat> aggregateByDay(String fromIso, String toIso) {
        QueryWrapper w = base(fromIso, toIso)
            .select("substr(started_at,1,10) AS day_date",
                "COUNT(*) AS calls",
                "COALESCE(SUM(input_tokens + output_tokens),0) AS token_count",
                "COALESCE(SUM(cost_yuan),0) AS cost_yuan")
            .groupBy("substr(started_at,1,10)")
            // ⚠️ 必须用 orderBy(String, Boolean) 重载（走 RawQueryColumn 对象）；裸字符串的
            //   orderBy(String...) 走 RawQueryOrderBy，会被 SqlUtil.keepOrderBySqlSafely 拒绝
            //   （只允许「标识符 [ASC|DESC]」）→ 表达式/函数排序直接抛 IllegalArgumentException。
            .orderBy("day_date", true);   // ISO yyyy-MM-dd 字典序 == 时间序
        List<DayStat> out = new ArrayList<>();
        for (DayRow r : queryAs(w, DayRow.class)) {
            out.add(new DayStat(r.dayDate, r.calls, r.tokenCount, r.costYuan));
        }
        return out;
    }

    /** byModel：按 {@code COALESCE(model_name,'')} 分组（SQL 聚合行，供 byModel 与 totals 命中率共用）。 */
    private List<ModelRow> aggregateModelRows(String fromIso, String toIso) {
        QueryWrapper w = base(fromIso, toIso)
            .select("COALESCE(model_name,'') AS model",
                "COUNT(*) AS calls",
                "COALESCE(SUM(input_tokens),0) AS input_tokens",
                "COALESCE(SUM(output_tokens),0) AS output_tokens",
                "COALESCE(SUM(cache_read_input_tokens),0) AS cache_read_input_tokens",
                "COALESCE(SUM(cache_creation_input_tokens),0) AS cache_creation_input_tokens",
                "COALESCE(SUM(cost_yuan),0) AS cost_yuan",
                "COALESCE(SUM(cost_input),0) AS cost_input",
                "COALESCE(SUM(cost_output),0) AS cost_output",
                "COALESCE(SUM(cost_cache_read),0) AS cost_cache_read",
                "COALESCE(SUM(cost_cache_write),0) AS cost_cache_write")
            .groupBy("COALESCE(model_name,'')")
            // 表达式排序必须走 orderBy(String, Boolean)（RawQueryColumn）；裸字符串会被安全校验拒绝
            .orderBy("SUM(input_tokens + output_tokens)", false);   // token 降序（谁烧得多一眼可见）
        return queryAs(w, ModelRow.class);
    }

    /** SQL 聚合行 → 对外 byModel 项（命中率按该模型协议分派）。 */
    private List<ModelStat> toModelStats(List<ModelRow> rows) {
        List<ModelStat> out = new ArrayList<>(rows.size());
        for (ModelRow r : rows) {
            Double hitRate = CacheHitRate.ratio(r.model, r.inputTokens,
                r.cacheReadInputTokens, r.cacheCreationInputTokens, modelMapper, providerMapper).rate();
            out.add(new ModelStat(r.model, r.calls, r.inputTokens, r.outputTokens,
                r.cacheReadInputTokens, r.cacheCreationInputTokens,
                r.inputTokens + r.outputTokens, r.costYuan,
                r.costInput, r.costOutput, r.costCacheRead, r.costCacheWrite, hitRate));
        }
        return out;
    }

    /** byCategory：按 {@code category} 分组（字段集与改造前逐字一致），费用降序。 */
    private List<CategoryStat> aggregateByCategory(String fromIso, String toIso) {
        QueryWrapper w = base(fromIso, toIso)
            .select("COALESCE(NULLIF(category,''),'UNKNOWN') AS category",
                "COUNT(*) AS calls",
                "COALESCE(SUM(input_tokens),0) AS input_tokens",
                "COALESCE(SUM(output_tokens),0) AS output_tokens",
                "COALESCE(SUM(cache_read_input_tokens),0) AS cache_read_tokens",
                "COALESCE(SUM(cache_creation_input_tokens),0) AS cache_write_tokens",
                "COALESCE(SUM(cost_yuan),0) AS cost_yuan",
                "COALESCE(SUM(CASE WHEN token_source = 'ESTIMATED' THEN 1 ELSE 0 END),0) AS estimated_calls",
                "COALESCE(SUM(CASE WHEN phase = 'ABORTED' THEN 1 ELSE 0 END),0) AS aborted_calls",
                "COALESCE(SUM(CASE WHEN phase = 'FAILED' THEN 1 ELSE 0 END),0) AS failed_calls",
                "COALESCE(SUM(CASE WHEN stream = 1 THEN 1 ELSE 0 END),0) AS stream_calls",
                "COALESCE(SUM(CASE WHEN stream = 1 THEN 0 ELSE 1 END),0) AS non_stream_calls")
            .groupBy("COALESCE(NULLIF(category,''),'UNKNOWN')")
            // 表达式排序必须走 orderBy(String, Boolean)（RawQueryColumn）；裸字符串会被安全校验拒绝
            .orderBy("COALESCE(SUM(cost_yuan),0)", false);   // 费用降序（谁烧钱一眼可见）
        List<CategoryStat> out = new ArrayList<>();
        for (CategoryRow r : queryAs(w, CategoryRow.class)) {
            out.add(new CategoryStat(r.category, r.calls, r.inputTokens, r.outputTokens,
                r.cacheReadTokens, r.cacheWriteTokens, r.costYuan,
                r.estimatedCalls, r.abortedCalls, r.failedCalls,
                r.streamCalls, r.nonStreamCalls));
        }
        return out;
    }

    /**
     * byAgent：SQL 分两组（<b>数值维度</b>按 agent_id 聚合 + <b>身份维度</b>按
     * agent_id×category×label×depth×parent 聚合），JVM 只做
     * <b>身份归一</b>（{@code a+16hex} ⇄ UUID 合并为同一节点）与<b>身份取舍</b>（优先级见
     * {@link #NODE_CATEGORY_PRIORITY}）—— 这两件事 SQL 做不了（归一依赖
     * {@link com.nexusai.application.agent.subagent.AgentContext#canonicalAgentId} 的位运算）。
     */
    private List<AgentStat> aggregateByAgent(String fromIso, String toIso) {
        QueryWrapper numW = base(fromIso, toIso)
            .select("COALESCE(agent_id,'') AS agent_id",
                "COUNT(*) AS calls",
                "COALESCE(SUM(input_tokens),0) AS input_tokens",
                "COALESCE(SUM(output_tokens),0) AS output_tokens",
                "COALESCE(SUM(cache_read_input_tokens),0) AS cache_read_tokens",
                "COALESCE(SUM(cache_creation_input_tokens),0) AS cache_write_tokens",
                "COALESCE(SUM(cost_yuan),0) AS cost_yuan",
                "COALESCE(SUM(CASE WHEN token_source = 'ESTIMATED' THEN 1 ELSE 0 END),0) AS estimated_calls",
                "COALESCE(SUM(CASE WHEN phase = 'ABORTED' THEN 1 ELSE 0 END),0) AS aborted_calls",
                "COALESCE(SUM(CASE WHEN phase = 'FAILED' THEN 1 ELSE 0 END),0) AS failed_calls",
                "COALESCE(SUM(CASE WHEN stream = 1 THEN 1 ELSE 0 END),0) AS stream_calls",
                "COALESCE(SUM(CASE WHEN stream = 1 THEN 0 ELSE 1 END),0) AS non_stream_calls")
            .groupBy("COALESCE(agent_id,'')");
        List<AgentRow> rows = queryAs(numW, AgentRow.class);
        if (rows.isEmpty()) {
            return List.of();
        }
        QueryWrapper idW = base(fromIso, toIso)
            .select("COALESCE(agent_id,'') AS agent_id", "category",
                "parent_agent_id", "depth", "label", "COUNT(*) AS identity_calls")
            .groupBy("COALESCE(agent_id,'')", "category", "parent_agent_id", "depth", "label");
        List<AgentIdentityRow> identityRows = queryAs(idW, AgentIdentityRow.class);

        Map<String, AgentAgg> agg = new LinkedHashMap<>();
        for (AgentRow r : rows) {
            String key = canonicalAgentKey(r.agentId);
            AgentAgg a = agg.computeIfAbsent(key, AgentAgg::new);
            a.num.add(r.calls, r.inputTokens, r.outputTokens, r.cacheReadTokens,
                r.cacheWriteTokens, r.costYuan, r.estimatedCalls, r.abortedCalls,
                r.failedCalls, r.streamCalls, r.nonStreamCalls);
        }
        for (AgentIdentityRow r : identityRows) {
            AgentAgg a = agg.get(canonicalAgentKey(r.agentId));
            if (a != null) {
                a.considerIdentity(r.category, r.label, r.depth, r.parentAgentId);
            }
        }
        List<AgentStat> out = new ArrayList<>(agg.size());
        for (AgentAgg a : agg.values()) {
            out.add(new AgentStat(a.agentId, a.category,
                // 父字段用纯归一（null → null）；⛔ 不可用带 cat: 兜底的分组键函数
                //   （那会把「无父」变成 cat:UNKNOWN）
                com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(a.parentAgentId),
                a.depth, a.label,
                a.num.calls, a.num.input, a.num.output, a.num.cacheRead, a.num.cacheWrite,
                a.num.costYuan, a.num.estimated, a.num.aborted, a.num.failed,
                a.num.streamCalls, a.num.nonStreamCalls));
        }
        out.sort((x, y) -> Integer.compare(x.depth(), y.depth()));   // 层级序（父在前）
        return out;
    }

    /** 逐模型聚合行合并出的命中率（分子分母分别加总后一次相除；无行 → null）。 */
    private Double hitRateOfModelRows(List<ModelRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        long cacheRead = 0L;
        long prompt = 0L;
        for (ModelRow r : rows) {
            CacheHitRate.Ratio ratio = CacheHitRate.ratio(r.model, r.inputTokens,
                r.cacheReadInputTokens, r.cacheCreationInputTokens, modelMapper, providerMapper);
            cacheRead += ratio.cacheReadTokens();
            prompt += ratio.promptTokens();
        }
        return CacheHitRate.rate(cacheRead, prompt);
    }

    /**
     * 缓存节省（元）= 反事实无缓存价 − 实际缓存相关分项之和。
     *
     * <p>负数说明公式或数据有问题（例如反事实列未写）→ WARN + 钳到 0，
     * ⛔ 不把负的「省了钱」推给前端（会显示「节省 -12.3 元」）。
     */
    private static double cacheSavingsYuan(double noCacheInput, double input, double cacheRead,
                                           double cacheWrite, String where) {
        double savings = noCacheInput - (input + cacheRead + cacheWrite);
        if (savings < 0) {
            log.warn("[StatsService] cacheSavingsYuan 为负（公式/数据异常，钳到 0）: where={} "
                    + "noCacheInput={} input={} cacheRead={} cacheWrite={}",
                where, noCacheInput, input, cacheRead, cacheWrite);
            return 0.0;
        }
        return savings;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 区间参数
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 区间参数归一：null/空白 → null（不限）；纯数字 → epoch 毫秒；否则按 ISO-8601 解析
     * （带偏移文本，含 {@code Z} 结尾的 UTC 文本）。
     *
     * <p>归一化输出与写入侧<b>同款</b>（{@code OffsetDateTime.ofInstant(..., systemDefault()).toString()}）
     * —— 保证 {@code started_at} 的字符串比较是同一格式族内的比较（见类 javadoc）。
     *
     * @throws ValidationException 非法时间参数（⇒ 400）
     */
    static String normalizeBound(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            Instant instant;
            if (v.matches("[+-]?" + "\\d+")) {
                instant = Instant.ofEpochMilli(Long.parseLong(v));
            } else {
                try {
                    instant = OffsetDateTime.parse(v).toInstant();
                } catch (Exception offsetFailure) {
                    instant = Instant.parse(v);   // 兼容 ...Z 结尾的 UTC 文本
                }
            }
            return OffsetDateTime.ofInstant(instant, ZoneId.systemDefault()).toString();
        } catch (Exception e) {
            throw new ValidationException(
                "时间参数非法（需 ISO-8601 带偏移文本或 epoch 毫秒）: " + name + "=" + raw);
        }
    }

    /** 区间 where（{@code started_at} 字符串比较 · from 含 / to 不含）· 无区间 → 空 wrapper（全量）。 */
    private static QueryWrapper base(String fromIso, String toIso) {
        QueryWrapper w = QueryWrapper.create();
        if (fromIso != null) {
            w.where("started_at >= ?", fromIso);
        }
        if (toIso != null) {
            if (fromIso != null) {
                w.and("started_at < ?", toIso);
            } else {
                w.where("started_at < ?", toIso);
            }
        }
        return w;
    }

    /** SQL 聚合查询（mapper 未装配 / 异常 → 空列表，fail-soft，⛔ 不抛给接口）。 */
    private <T> List<T> queryAs(QueryWrapper wrapper, Class<T> asType) {
        if (usageCallMapper == null) {
            return List.of();
        }
        try {
            List<T> rows = usageCallMapper.selectListByQueryAs(wrapper, asType);
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            log.warn("[StatsService] 明细表聚合查询失败（该维度回落空）: as={} err={}",
                asType.getSimpleName(), e.toString());
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
     *
     * <p>[cache-savings] 末尾 8 字段为净新增（前端「缓存省了多少钱」与「有多少行没分项」）：
     * {@code costInput/costOutput/costCacheRead/costCacheWrite/costNoCacheInput/cacheSavingsYuan}
     * 与全量视图同口径（分项只统计 PRICED 行，LEGACY 行不摊分）；
     * {@code cacheHitRate} = 本会话累计命中率（按模型分派分母，0-1 比率，无数据 → null）；
     * {@code legacyCalls} = 本会话 V101 之前的历史行数。
     */
    public record SessionUsageTotals(long calls, long inputTokens, long outputTokens,
                                     long cacheReadTokens, long cacheWriteTokens, double costYuan,
                                     long elapsedMs, long estimatedCalls, long abortedCalls,
                                     long failedCalls, long streamCalls, long nonStreamCalls,
                                     double costInput, double costOutput, double costCacheRead,
                                     double costCacheWrite, double costNoCacheInput,
                                     double cacheSavingsYuan, Double cacheHitRate, long legacyCalls) {}

    /**
     * 会话内单个 agent 节点的用量（前端「子代理（本会话）」行直接消费）。
     *
     * <p><b>{@code taskId} 的由来</b>：明细表 {@code agent_id} 列存的是 agent 身份渲染
     * （子代理 = {@code a}+16hex，退化时 UUID 串；主 agent = 会话短 id）。而任务注册表 / task 事件
     * （{@code task_id}）用的是 {@code agentId.toString()}（CC 语义 taskId === agentId）——两者
     * 由 {@link com.nexusai.application.agent.subagent.AgentContext#packAgentId(String)} 确定性互转。
     * 本字段即换算后的 {@code task_id}，供前端按 {@code task_id} 精确关联（⛔ 前端不得自行解析
     * {@code agentId} 格式），无法换算（主 agent / 无会话哨兵 / 未知格式）→ {@code null}。
     *
     * <p>[cache-savings] 末尾 8 字段与 {@link SessionUsageTotals} 同口径（按<b>本节点</b>的行聚合）。
     */
    public record SessionAgentUsage(String agentId, String taskId, String category, String parentAgentId,
                                    String parentTaskId, int depth, String label,
                                    long calls, long inputTokens, long outputTokens,
                                    long cacheReadTokens, long cacheWriteTokens, double costYuan,
                                    long elapsedMs, long estimatedCalls, long abortedCalls,
                                    long failedCalls, long streamCalls, long nonStreamCalls,
                                    double costInput, double costOutput, double costCacheRead,
                                    double costCacheWrite, double costNoCacheInput,
                                    double cacheSavingsYuan, Double cacheHitRate, long legacyCalls) {}

    /** 会话级用量视图（totals + 按归类 + 按 agent 节点）。 */
    public record SessionUsage(String sessionId, SessionUsageTotals totals,
                               List<CategoryStat> byCategory, List<SessionAgentUsage> agents) {}

    /**
     * 聚合单个会话的用量明细（{@code llm_usage_calls} 按 session_id 过滤）。
     *
     * <p><b>为什么需要它</b>：前端「用量」页要回答「这个会话里每个子代理花了多少」——总览统计
     * （{@link #aggregate(String, String)}）是全量的，答不了；task 事件只带进度估算且失败/中断路径
     * 常常没有 usage（实测表现为「统计不可用」）。明细表是记账侧的权威值，按 agent 分组即为该页所需。
     *
     * <p><b>与全量视图的差异（有意）</b>：本视图按<b>明细行</b>读（单会话行数受会话规模约束），
     * 因为身份归一 + 身份取舍 + 每节点逐模型命中率需要行级信息；全量视图则把聚合下推到 SQL。
     *
     * <p><b>排序</b>：agent 节点按 depth 升序（父在前），同层按费用降序（前端顺序即展示顺序）；
     * byCategory 按费用降序（与全量视图一致）。
     *
     * <p>空会话 / 明细表无该会话记录 → {@code totals} 全零 + 两个空列表（fail-soft，不抛）。
     *
     * @param sessionId 会话 id（null/空白 → {@link ValidationException}
     *                  ⇒ 400，对齐本仓「查询参数越界返回 400 而非 500」的既有先例）
     */
    public SessionUsage sessionUsage(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("sessionId 不能为空");
        }
        List<LlmUsageCallRecord> rows = listUsageCallsForSession(sessionId);

        // 按归类（与全量视图同累加口径）
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
            byCategory.add(new CategoryStat(e.getKey(), a.num.calls, a.num.input, a.num.output,
                a.num.cacheRead, a.num.cacheWrite, a.num.costYuan, a.num.estimated, a.num.aborted,
                a.num.failed, a.num.streamCalls, a.num.nonStreamCalls));
        }
        byCategory.sort((x, y) -> Double.compare(y.costYuan(), x.costYuan()));

        List<SessionAgentUsage> agents = new ArrayList<>(byAgent.size());
        for (AgentSessionAgg a : byAgent.values()) {
            agents.add(new SessionAgentUsage(a.agentId, taskIdOf(a.agentId), a.category,
                com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(a.parentAgentId),
                taskIdOf(a.parentAgentId), a.depth, a.label,
                a.bucket.num.calls, a.bucket.num.input, a.bucket.num.output,
                a.bucket.num.cacheRead, a.bucket.num.cacheWrite, a.bucket.num.costYuan,
                a.elapsedMs, a.bucket.num.estimated, a.bucket.num.aborted, a.bucket.num.failed,
                a.bucket.num.streamCalls, a.bucket.num.nonStreamCalls,
                a.bucket.costInput, a.bucket.costOutput, a.bucket.costCacheRead, a.bucket.costCacheWrite,
                a.bucket.costNoCacheInput,
                cacheSavingsYuan(a.bucket.costNoCacheInput, a.bucket.costInput, a.bucket.costCacheRead,
                    a.bucket.costCacheWrite, "session=" + sessionId + " agent=" + a.agentId),
                hitRateOf(a.bucket), a.bucket.legacyCalls));
        }
        agents.sort((x, y) -> {
            int byDepth = Integer.compare(x.depth(), y.depth());
            return byDepth != 0 ? byDepth : Double.compare(y.costYuan(), x.costYuan());
        });

        if (log.isDebugEnabled()) {
            log.debug("[StatsService] sessionUsage: session={} rows={} agents={} categories={} costYuan={}",
                sessionId, rows.size(), agents.size(), byCategory.size(), totals.num.costYuan);
        }
        return new SessionUsage(sessionId,
            new SessionUsageTotals(totals.num.calls, totals.num.input, totals.num.output,
                totals.num.cacheRead, totals.num.cacheWrite, totals.num.costYuan, totalElapsed,
                totals.num.estimated, totals.num.aborted, totals.num.failed,
                totals.num.streamCalls, totals.num.nonStreamCalls,
                totals.costInput, totals.costOutput, totals.costCacheRead, totals.costCacheWrite,
                totals.costNoCacheInput,
                cacheSavingsYuan(totals.costNoCacheInput, totals.costInput, totals.costCacheRead,
                    totals.costCacheWrite, "session=" + sessionId + " totals"),
                hitRateOf(totals), totals.legacyCalls),
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

    /** 明细表按会话读（mapper 未装配 / 异常 → 空列表，fail-soft）。 */
    private List<LlmUsageCallRecord> listUsageCallsForSession(String sessionId) {
        if (usageCallMapper == null) {
            return List.of();
        }
        try {
            List<LlmUsageCallRecord> rows = usageCallMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId));
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
     * 明细表 agentId → 聚合键（归一：UUID 形式 → a+16hex；无 agentId → {@code cat:UNKNOWN} 兜底）。
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

    /** byAgent 累加器（SQL 聚合行累加 + 身份维度按归类优先级取，见 {@link #categoryRank}）。 */
    private static final class AgentAgg {
        private final String agentId;
        private String category;
        private String parentAgentId;
        private int depth;
        private String label;
        /** 当前身份行的归类优先级（越小越优先；同级保留先到者）。 */
        private int identityRank = Integer.MAX_VALUE;
        private final NumAgg num = new NumAgg();

        private AgentAgg(String agentId) {
            this.agentId = agentId;
        }

        /** 身份行取舍：优先级更高（或首行）→ 用它的 category/label/depth/parent。 */
        private void considerIdentity(String category, String label, Integer depth, String parentAgentId) {
            int rank = categoryRank(category);
            if (rank < identityRank) {
                identityRank = rank;
                this.category = category;
                this.label = label;
                this.depth = depth != null ? depth : 0;
                this.parentAgentId = parentAgentId;
            }
        }
    }

    /** 通用数值累加（SQL 聚合行 byCategory / byAgent 共用）。 */
    private static final class NumAgg {
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

        private void add(long calls, long input, long output, long cacheRead, long cacheWrite, double costYuan,
                         long estimated, long aborted, long failed, long streamCalls, long nonStreamCalls) {
            this.calls += calls;
            this.input += input;
            this.output += output;
            this.cacheRead += cacheRead;
            this.cacheWrite += cacheWrite;
            this.costYuan += costYuan;
            this.estimated += estimated;
            this.aborted += aborted;
            this.failed += failed;
            this.streamCalls += streamCalls;
            this.nonStreamCalls += nonStreamCalls;
        }
    }

    /**
     * 会话视图累加器：数值维度（{@link NumAgg}）+ <b>分项费用</b>（V101 五列）+
     * <b>逐模型 token</b>（{@code model → {input, cacheRead, cacheCreation}}，命中率分子分母的唯一来源）。
     */
    private static final class CategoryAgg {
        private final NumAgg num = new NumAgg();
        private double costInput;
        private double costOutput;
        private double costCacheRead;
        private double costCacheWrite;
        private double costNoCacheInput;
        private long legacyCalls;
        private final Map<String, long[]> modelTokens = new LinkedHashMap<>();

        private void add(LlmUsageCallRecord r) {
            num.add(1, nz(r.getInputTokens()), nz(r.getOutputTokens()),
                nz(r.getCacheReadInputTokens()), nz(r.getCacheCreationInputTokens()),
                r.getCostYuan() != null ? r.getCostYuan() : 0.0,
                "ESTIMATED".equalsIgnoreCase(r.getTokenSource()) ? 1 : 0,
                "ABORTED".equalsIgnoreCase(r.getPhase()) ? 1 : 0,
                "FAILED".equalsIgnoreCase(r.getPhase()) ? 1 : 0,
                r.getStream() != null && r.getStream() == 1 ? 1 : 0,
                r.getStream() != null && r.getStream() == 1 ? 0 : 1);
            // [cache-savings] 分项只统计 PRICED 行：LEGACY 行的五列为 NULL → 不摊分、不伪造
            if ("LEGACY".equalsIgnoreCase(r.getCostDetailSource())) {
                legacyCalls++;
            } else {
                costInput += r.getCostInput() != null ? r.getCostInput() : 0.0;
                costOutput += r.getCostOutput() != null ? r.getCostOutput() : 0.0;
                costCacheRead += r.getCostCacheRead() != null ? r.getCostCacheRead() : 0.0;
                costCacheWrite += r.getCostCacheWrite() != null ? r.getCostCacheWrite() : 0.0;
                costNoCacheInput += r.getCostNoCacheInput() != null ? r.getCostNoCacheInput() : 0.0;
            }
            // 命中率分子分母必须逐模型分派（同一会话可混跑 anthropic 与 deepseek）
            String model = r.getModelName() != null ? r.getModelName() : "";
            long[] t = modelTokens.computeIfAbsent(model, k -> new long[3]);
            t[0] += nz(r.getInputTokens());
            t[1] += nz(r.getCacheReadInputTokens());
            t[2] += nz(r.getCacheCreationInputTokens());
        }
    }

    /** 会话视图累加器的命中率（逐模型分派分母后合并，无数据 / 分母 0 → null）。 */
    private Double hitRateOf(CategoryAgg a) {
        if (a.modelTokens.isEmpty()) {
            return null;
        }
        long cacheRead = 0L;
        long prompt = 0L;
        for (Map.Entry<String, long[]> e : a.modelTokens.entrySet()) {
            long[] t = e.getValue();
            CacheHitRate.Ratio ratio = CacheHitRate.ratio(e.getKey(), t[0], t[1], t[2],
                modelMapper, providerMapper);
            cacheRead += ratio.cacheReadTokens();
            prompt += ratio.promptTokens();
        }
        return CacheHitRate.rate(cacheRead, prompt);
    }

    private static long nz(Integer v) {
        return v != null ? v : 0L;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // SQL 聚合行投影（MyBatis-Flex selectListByQueryAs）
    // ⚠️ 列名 ⇄ 字段名必须逐列对齐：Flex 由 asType 反推列名（camelCase → snake_case）
    //    并据此建 ResultMap，SQL 里的别名必须写成<蛇形>（如 input_tokens），
    //    ⛔ 写 camelCase 别名会让 ResultMap 找不到列 → 字段静默为 0。
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** totals 单行。 */
    private static class TotalsRow {
        private long sessionCount;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private double costYuan;
        private double costInput;
        private double costOutput;
        private double costCacheRead;
        private double costCacheWrite;
        private double costNoCacheInput;
        private long estimatedCalls;
        private long abortedCalls;
        private long failedCalls;
        private long legacyCalls;
        private double legacyCostYuan;
    }

    /** byDay 单行（{@code day_date} = 调用发生日，本地日期）。 */
    private static class DayRow {
        private String dayDate;
        private long calls;
        private long tokenCount;
        private double costYuan;
    }

    /** byModel 单行（同时供 totals 命中率逐模型分派）。 */
    private static class ModelRow {
        private String model;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;
        private long cacheCreationInputTokens;
        private double costYuan;
        private double costInput;
        private double costOutput;
        private double costCacheRead;
        private double costCacheWrite;
    }

    /** byCategory 单行。 */
    private static class CategoryRow {
        private String category;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private double costYuan;
        private long estimatedCalls;
        private long abortedCalls;
        private long failedCalls;
        private long streamCalls;
        private long nonStreamCalls;
    }

    /** byAgent 数值行。 */
    private static class AgentRow {
        private String agentId;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private double costYuan;
        private long estimatedCalls;
        private long abortedCalls;
        private long failedCalls;
        private long streamCalls;
        private long nonStreamCalls;
    }

    /** byAgent 身份行（category/label/depth/parent 的候选来源，按 {@link #NODE_CATEGORY_PRIORITY} 取舍）。 */
    private static class AgentIdentityRow {
        private String agentId;
        private String category;
        private String parentAgentId;
        private Integer depth;
        private String label;
        private long identityCalls;
    }
}
