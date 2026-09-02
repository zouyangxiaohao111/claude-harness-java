package com.nexusai.application.agent.cost;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话 usage/cost 持久化桥 + 按模型桶合并引擎 · 对齐 CC cost-tracker.ts。
 *
 * <p><b>架构（避免双源）</b>：Java 生产路径的<b>单源</b>是 {@link AgentState} 会话累计字段
 * （LlmAgentLoop 每 message_delta 累加 state.sessionInputTokens/sessionCostYuan/sessionModelUsage；
 * ChatService 装配 complete 事件读 state）。本类承担两个职责：
 * <ol>
 *   <li><b>会话持久化桥</b>：sessions 表列（V48 {@code total_cost_yuan} + {@code model_usage_json}）
 *       跨 turn 权威 —— {@link #restoreCostStateForSession}（会话启动，LlmAgentLoop doRun）读列 →
 *       写 AgentState；{@link #saveCurrentSessionCosts}（轮结束，ChatService）读 AgentState → 写列。
 *       CC 真源是进程级 STATE 单例 + project config 持久化（state.ts:704-710 total_cost_usd /
 *       lastModelUsage）；Java 多会话并发 → 按 sessionId 隔离 + 存 sessions 表列
 *       （multi-session-vs-cc-single-session 铁律）。</li>
 *   <li><b>CC-faithful 桶合并原语</b>：{@link #computeModelUsageIncrement}（静态纯函数，镜像 CC
 *       {@code addToTotalModelUsage} cost-tracker.ts:250-276）+ {@link #addToTotalSessionCost}
 *       （合并进本实例 {@link #modelUsageBuckets} + QuotaInfo，供直调/测试/未来非 state 通道复用）。</li>
 * </ol>
 *
 * <p>本类改造前是全仓死代码（0 bean / 0 调用 / configSupplier 通道空转），实施时真正接线。
 *
 * <p>{@code @Autowired(required = false)} 双点注入：非 Spring 单测 new 时 sessionMapper 为 null
 * → restore/save 跳过持久化（不 NPE）。
 */
@Component
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    /**
     * 按模型 8 字段用量桶值 · 对齐 CC {@code ModelUsage}（cost-tracker.ts:29-38，全 camelCase）——
     * {@code modelUsage} JSON 值直接序列化本 record（字段名对齐 CC，前端按 camelCase 消费）。
     */
    public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests,
        double costUSD,
        long contextWindow,
        long maxOutputTokens
    ) {}

    public record QuotaInfo(
        double currentCost,
        double totalCostUSD,
        double totalAPIDuration,
        double totalToolDuration,
        long totalLinesAdded,
        long totalLinesRemoved
    ) {}

    /**
     * 会话恢复快照 · 从 sessions 表列读出（V48 total_cost_yuan + model_usage_json），
     * 供 LlmAgentLoop 会话启动写入 AgentState 会话累计字段（E4）。
     */
    public record RestoredSessionCosts(
        long inputTokens,
        double totalCostYuan,
        Map<String, ModelUsage> modelUsage
    ) {}

    @Autowired(required = false) private SessionMapper sessionMapper;

    private volatile QuotaInfo cumulative = null;
    /** 按模型桶（保序）· CC original: {@code STATE.modelUsage}（cost-tracker.ts:250-276）。 */
    private final Map<String, ModelUsage> modelUsageBuckets =
        Collections.synchronizedMap(new LinkedHashMap<>());

    public CostTracker() {
        // @Component 无参构造（Spring 实例化）· 原 3 参 configSupplier 构造已删（死代码通道）
    }

    /**
     * 静态纯函数 · 镜像 CC {@code addToTotalModelUsage}（cost-tracker.ts:250-276）单次增量：
     * {@code modelUsage.inputTokens += usage.input_tokens} / outputTokens / cacheRead /
     * cacheCreation / webSearchRequests（server_tool_use.web_search_requests ?? 0）/
     * costUSD += cost；contextWindow / maxOutputTokens 取 last（本次传入值）。
     *
     * @param cost          本次折算花费（元）
     * @param usage         本次 API usage（null → 零初始化 EMPTY）
     * @param model         模型名（仅透传，不参与增量计算）
     * @param contextWindow 本次窗口（未知模型回退 flash 1M）
     * @param maxOutput     本次输出上限（未知模型回退 flash 384K）
     */
    public static ModelUsage computeModelUsageIncrement(double cost, AgentUsage usage, String model,
                                                        int contextWindow, long maxOutput) {
        if (usage == null) {
            usage = AgentUsage.EMPTY;
        }
        return new ModelUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L,
            usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L,
            usage.serverToolUse() != null ? usage.serverToolUse().webSearchRequests() : 0L,
            cost,
            contextWindow,
            maxOutput);
    }

    /**
     * CC 对齐累计签名 · 合并增量进本实例桶 + QuotaInfo（tokens/costUSD 累加，窗口取 last）。
     *
     * <p><b>生产单源说明</b>：LlmAgentLoop 累计走 {@link AgentState#mergeSessionModelUsage}（单源），
     * 本方法供直调/测试/未来非 state 通道复用（CC cost-tracker.ts:250-276 语义保真）。
     */
    public double addToTotalSessionCost(double cost, AgentUsage usage, String model,
                                        int contextWindow, long maxOutput) {
        ModelUsage inc = computeModelUsageIncrement(cost, usage, model, contextWindow, maxOutput);
        mergeModelUsage(model, inc);
        QuotaInfo cur = cumulative != null ? cumulative : new QuotaInfo(0, 0, 0, 0, 0, 0);
        cumulative = new QuotaInfo(cur.currentCost() + cost, cur.totalCostUSD() + cost,
            cur.totalAPIDuration(), cur.totalToolDuration(),
            cur.totalLinesAdded(), cur.totalLinesRemoved());
        return cost;
    }

    /** 桶合并（同 AgentState.mergeSessionModelUsage 语义）· null model/inc 跳过。 */
    private void mergeModelUsage(String model, ModelUsage inc) {
        if (model == null || inc == null) {
            return;
        }
        ModelUsage existing = modelUsageBuckets.get(model);
        if (existing == null) {
            modelUsageBuckets.put(model, inc);
            return;
        }
        modelUsageBuckets.put(model, new ModelUsage(
            existing.inputTokens() + inc.inputTokens(),
            existing.outputTokens() + inc.outputTokens(),
            existing.cacheReadInputTokens() + inc.cacheReadInputTokens(),
            existing.cacheCreationInputTokens() + inc.cacheCreationInputTokens(),
            existing.webSearchRequests() + inc.webSearchRequests(),
            existing.costUSD() + inc.costUSD(),
            inc.contextWindow(),      // CC 取 last
            inc.maxOutputTokens()));  // CC 取 last
    }

    /** 按模型桶只读视图（CC modelUsage 语义）。 */
    public Map<String, ModelUsage> modelUsage() {
        return Collections.unmodifiableMap(modelUsageBuckets);
    }

    /** 本实例累计花费（元；addToTotalSessionCost 累计 / restore 恢复值）。 */
    public double totalCostYuan() {
        QuotaInfo c = cumulative;
        return c != null ? c.totalCostUSD() : 0.0;
    }

    /**
     * 会话启动恢复 · 读 sessions 表 {@code total_cost_yuan} + {@code model_usage_json}（V48）
     * → 快照 + 镜像本实例桶（CC restoreCostStateForSession 语义，state.ts:704-710）。
     * 列 NULL → 零累计；sessionMapper null（非 Spring 单测）或会话不存在 → null（调用方跳过）。
     */
    public RestoredSessionCosts restoreCostStateForSession(String sessionId) {
        if (sessionMapper == null || sessionId == null) {
            return null;
        }
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) {
            return null;
        }
        double totalCost = s.getTotalCostYuan() != null ? s.getTotalCostYuan() : 0.0;
        Map<String, ModelUsage> buckets = parseModelUsageJson(s.getModelUsageJson());
        long input = 0;
        for (ModelUsage u : buckets.values()) {
            input += u.inputTokens();
        }
        this.cumulative = new QuotaInfo(totalCost, totalCost, 0, 0, 0, 0);
        this.modelUsageBuckets.clear();
        this.modelUsageBuckets.putAll(buckets);
        if (log.isDebugEnabled()) {
            log.debug("[CostTracker] restoreCostStateForSession: sessionId={} totalCostYuan={} buckets={} inputTokens={}",
                sessionId, totalCost, buckets.size(), input);
        }
        return new RestoredSessionCosts(input, totalCost, buckets);
    }

    /**
     * 会话结束保存 · 从 AgentState（单源）读累计 → 写 sessions 表
     * {@code total_cost_yuan} + {@code model_usage_json}（读-改-写，镜像 SessionService 同款；
     * 会话单 turn 串行，无需原子 @Update）。sessionMapper null → 跳过（非 Spring 单测）。
     */
    public void saveCurrentSessionCosts(String sessionId, AgentState state) {
        if (sessionMapper == null || sessionId == null || state == null) {
            return;
        }
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) {
            log.warn("[CostTracker] saveCurrentSessionCosts 跳过: 会话不存在 sessionId={}", sessionId);
            return;
        }
        s.setTotalCostYuan(state.sessionCostYuan());
        s.setModelUsageJson(JSON.toJSONString(state.sessionModelUsage()));
        sessionMapper.update(s);
        if (log.isDebugEnabled()) {
            log.debug("[CostTracker] saveCurrentSessionCosts 已保存: sessionId={} totalCostYuan={} buckets={}",
                sessionId, state.sessionCostYuan(), state.sessionModelUsage().size());
        }
    }

    /** sessions.model_usage_json 列 → 桶 Map · null/空白/解析失败 → 空（fail-soft，对齐 SessionService parseTodos）。 */
    private static Map<String, ModelUsage> parseModelUsageJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, ModelUsage> parsed = JSON.parseObject(json,
                new TypeReference<LinkedHashMap<String, ModelUsage>>() {});
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("[CostTracker] parseModelUsageJson 解析失败（回落零累计）: err={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 花费格式化（元）。 */
    public String formatCost(double cost) {
        if (cost > 0.5) {
            return String.format("¥%.2f", Math.round(cost * 100) / 100.0);
        }
        return String.format("¥%.4f", cost);
    }
}
