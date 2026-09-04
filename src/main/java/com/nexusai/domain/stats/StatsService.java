package com.nexusai.domain.stats;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
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

    /** 聚合结果：{totals, byDay, byModel}。 */
    public record StatsResult(Totals totals, List<DayStat> byDay, List<ModelStat> byModel) {}

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
        return new StatsResult(new Totals(sessionCount, totalTokens, totalCost), byDay, byModel);
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
