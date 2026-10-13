package com.nexusai.application.agent.cost;

import com.alibaba.fastjson2.JSON;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.infra.llm.LlmCallRecord;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.UsageTokenSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级<b>用量/费用账本</b>（进程内权威）· 请求网关记账的聚合与归类载体。
 *
 * <p><b>WHY 需要它（本次改造的根因）</b>：改造前会话合计只有一条通路 —— 主 agent 流式调用经
 * {@code LlmAgentLoop.accumulateSessionUsage} 累进 {@code AgentState}，再由 {@code ChatService}
 * 单点落库。子代理 / compact fork / cron / 后台任务 / 旁路 side call 的用量要么进隔离 state
 * 后丢弃，要么根本不统计 ⇒ 会话金额系统性低估。要修这个结构性问题，必须有一个<b>与调用方解耦</b>
 * 的会话级聚合器：网关记什么，它就聚合什么，与「谁发起的调用」无关。
 *
 * <p><b>层级（用户要求「费用和 token 用量构成的层级信息」）</b>：按
 * {@link UsageAttribution#agentId()} 建节点，节点间以 {@code parentAgentId} + {@code depth}
 * 表达 session → 主 agent → 子代理/fork → 嵌套子代理 的树；每个节点自带 {@link UsageBucket}
 * （四 token 通道 + 高峰/空闲费用 + 调用次数构成）⇒ 任意层级都能同时给出「本层构成」与「子树合计」。
 *
 * <p><b>与既有字段的关系</b>：{@code AgentState.sessionInputTokens/sessionCostYuan/sessionModelUsage}
 * 仍是「视图」（用户裁定：保留，STOMP/DTO 契约零改动），由记账器从本账本回写；
 * {@code sessions.model_usage_json} 仍是按模型 rollup；本账本另存
 * {@code sessions.usage_tree_json}（新列）承载层级快照。
 *
 * <p><b>并发</b>：虚拟线程下多 agent 并发记账 ⇒ 全部写路径 {@code synchronized}
 * （每次 LLM 调用一次，频率极低）；读路径返回不可变快照。
 */
public class SessionUsageLedger implements com.nexusai.infra.llm.LlmUsageRecorder {

    /** 计价纯函数（随账本注入；null → 费用记 0，不猜价格）。 */
    private final ModelCostCalculator costCalculator;
    /** 明细表 mapper（随账本注入；null → 跳过落表）。 */
    private final com.nexusai.repository.usage.mapper.LlmUsageCallMapper usageCallMapper;
    /** 落表失败计数（诊断用；显式暴露「统计有缺口」这一事实，不静默）。 */
    private final java.util.concurrent.atomic.AtomicLong persistFailures =
        new java.util.concurrent.atomic.AtomicLong();

    /** 节点快照（不可变）· 序列化到 {@code sessions.usage_tree_json} 的单元。 */
    public record NodeSnapshot(
            String agentId,
            String category,
            String parentAgentId,
            int depth,
            String label,
            UsageBucket bucket
    ) {
        /** 由归属 + 桶构造。 */
        public static NodeSnapshot of(UsageAttribution who, UsageBucket bucket) {
            return new NodeSnapshot(
                who.agentId(),
                who.category() != null ? who.category().name() : UsageCategory.UNKNOWN.name(),
                who.parentAgentId(),
                who.depth(),
                who.label(),
                bucket);
        }
    }

    /** 节点内部态：归属（身份维度，首见即定）+ 累积桶 + 该节点的 AgentState 弱引用持有者。 */
    private static final class Entry {
        private final UsageAttribution who;
        private UsageBucket bucket;
        private AgentState owner;

        private Entry(UsageAttribution who, UsageBucket bucket) {
            this.who = who;
            this.bucket = bucket;
        }
    }

    private final String sessionId;
    /** 节点表（保序，便于快照稳定输出）· key = {@link UsageAttribution#nodeKey()}。 */
    private final Map<String, Entry> nodes = new LinkedHashMap<>();
    /** 会话累计的按模型 rollup（与 sessions.model_usage_json 同口径，供落库/对账使用）。 */
    private final Map<String, CostTracker.ModelUsage> modelUsage = new LinkedHashMap<>();
    private double totalCostYuan = 0.0;
    private long totalCalls = 0;

    /**
     * @param sessionId      归属会话（{@code __no_session__} 表示无会话上下文）
     * @param costCalculator 计价纯函数（可 null = 费用记 0）
     * @param usageCallMapper 明细表 mapper（可 null = 跳过落表）
     */
    public SessionUsageLedger(String sessionId, ModelCostCalculator costCalculator,
                              com.nexusai.repository.usage.mapper.LlmUsageCallMapper usageCallMapper) {
        this.sessionId = sessionId;
        this.costCalculator = costCalculator;
        this.usageCallMapper = usageCallMapper;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * 绑定某节点所属的 {@link AgentState}（run 启动时调用）· 记账器据此把会话视图回写到主 state。
     *
     * <p>主 agent 的 state 绑定在 {@code depth == 0} 节点上；子代理 state 绑定各自节点
     * （其构成可由子代理自身读取；会话合计仍由主 state 视图承载）。
     */
    public synchronized void attach(UsageAttribution who, AgentState owner) {
        if (who == null || owner == null) {
            return;
        }
        Entry entry = nodes.computeIfAbsent(who.nodeKey(), k -> new Entry(who, UsageBucket.empty()));
        entry.owner = owner;
    }

    /** 主 agent（depth == 0 且已绑定 owner）的 state；未绑定 → null。 */
    public synchronized AgentState mainOwner() {
        AgentState fallback = null;
        for (Entry e : nodes.values()) {
            if (e.owner == null) {
                continue;
            }
            if (e.who.depth() == 0) {
                return e.owner;
            }
            if (fallback == null) {
                fallback = e.owner;
            }
        }
        return fallback;
    }

    /** 已绑定 state 的节点（agentId → state）· 供记账器按 agent 回写各 agent 自身视图。 */
    public synchronized Map<String, AgentState> attachedOwners() {
        Map<String, AgentState> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : nodes.entrySet()) {
            if (e.getValue().owner != null) {
                out.put(e.getKey(), e.getValue().owner);
            }
        }
        return out;
    }

    /**
     * 记一次调用尝试（网关唯一入口）。
     *
     * @param who       归属（null → 归 UNKNOWN 节点，不伪造 agentId）
     * @param usage     本次尝试的累计 usage（null → EMPTY）
     * @param source    数值来源（估算/未知单列统计，防混算）
     * @param costYuan  本次尝试折算费用（元；估算值同样计入但由 source 标记）
     * @param peak      是否高峰档（决定计入 costYuanPeak 还是 costYuanOffpeak）
     * @param stream    是否流式
     * @param phase     尝试结局（决定 failed/aborted 计数）
     * @param atMs      调用开始时刻（epoch ms）
     * @param modelName 计费模型名（按模型 rollup 的键；null → 跳过 rollup）
     */
    public synchronized void record(UsageAttribution who, AgentUsage usage, UsageTokenSource source,
                                    double costYuan, boolean peak, boolean stream,
                                    com.nexusai.infra.llm.LlmUsagePhase phase, long atMs, String modelName,
                                    int contextWindow, long maxOutputTokens) {
        UsageAttribution attribution = who != null
            ? who
            : new UsageAttribution(sessionId, null, UsageCategory.UNKNOWN, null, 0, null);
        AgentUsage u = usage != null ? usage : AgentUsage.EMPTY;
        UsageTokenSource src = source != null ? source : UsageTokenSource.UNKNOWN;
        com.nexusai.infra.llm.LlmUsagePhase ph = phase != null
            ? phase : com.nexusai.infra.llm.LlmUsagePhase.SETTLED;

        long cacheRead = u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L;
        long cacheWrite = u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L;
        UsageBucket delta = new UsageBucket(
            u.inputTokens(), u.outputTokens(), cacheRead, cacheWrite,
            1L,
            src == UsageTokenSource.ESTIMATED ? 1L : 0L,
            ph == com.nexusai.infra.llm.LlmUsagePhase.FAILED ? 1L : 0L,
            ph == com.nexusai.infra.llm.LlmUsagePhase.ABORTED ? 1L : 0L,
            stream ? 1L : 0L,
            stream ? 0L : 1L,
            peak ? costYuan : 0.0,
            peak ? 0.0 : costYuan,
            atMs,
            atMs);

        Entry entry = nodes.computeIfAbsent(attribution.nodeKey(), k -> new Entry(attribution, UsageBucket.empty()));
        entry.bucket = entry.bucket.plus(delta);
        // 身份维度首见即定；后续同 key 的更完整归属（如后到的 parentAgentId）不回改（保持快照稳定）
        totalCostYuan += costYuan;
        totalCalls++;
        if (modelName != null && !modelName.isBlank()) {
            CostTracker.ModelUsage inc = CostTracker.computeModelUsageIncrement(
                costYuan, u, modelName, contextWindow, maxOutputTokens);
            CostTracker.ModelUsage existing = modelUsage.get(modelName);
            modelUsage.put(modelName, existing == null ? inc : new CostTracker.ModelUsage(
                existing.inputTokens() + inc.inputTokens(),
                existing.outputTokens() + inc.outputTokens(),
                existing.cacheReadInputTokens() + inc.cacheReadInputTokens(),
                existing.cacheCreationInputTokens() + inc.cacheCreationInputTokens(),
                existing.webSearchRequests() + inc.webSearchRequests(),
                existing.costUSD() + inc.costUSD(),
                inc.contextWindow(),
                inc.maxOutputTokens()));
        }
    }

    /** 全部节点快照（保序）。 */
    public synchronized List<NodeSnapshot> nodes() {
        List<NodeSnapshot> out = new ArrayList<>(nodes.size());
        for (Entry e : nodes.values()) {
            out.add(NodeSnapshot.of(e.who, e.bucket));
        }
        return out;
    }

    /** 指定 agent 节点的桶（无该节点 → 空桶）。 */
    public synchronized UsageBucket bucketOf(String agentId) {
        if (agentId == null) {
            return UsageBucket.empty();
        }
        Entry e = nodes.get(agentId);
        return e != null ? e.bucket : UsageBucket.empty();
    }

    /**
     * 指定节点的归属（无该节点 → null）· 供子代理 spawn 点解析「父节点归属」以派生层级
     * （嵌套子代理因此能拿到真实 depth，而不是被压平到 depth 1）。
     */
    public synchronized UsageAttribution attributionOf(String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        Entry e = nodes.get(nodeKey);
        return e != null ? e.who : null;
    }

    /** 指定 agent 节点的直接子节点桶（agentId 为空 → 返回 depth == 0 的节点）。 */
    public synchronized Map<String, UsageBucket> childrenOf(String agentId) {
        Map<String, UsageBucket> out = new LinkedHashMap<>();
        for (Entry e : nodes.values()) {
            boolean isChild = agentId == null
                ? e.who.depth() == 0
                : agentId.equals(e.who.parentAgentId());
            if (isChild) {
                out.put(e.who.nodeKey(), e.bucket);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 会话合计桶（所有节点求和）。 */
    public synchronized UsageBucket rollup() {
        UsageBucket sum = UsageBucket.empty();
        for (Entry e : nodes.values()) {
            sum = sum.plus(e.bucket);
        }
        return sum;
    }

    /** 会话累计花费（元）· 供 {@code sessions.total_cost_yuan} 落库与 /cost。 */
    public synchronized double totalCostYuan() {
        return totalCostYuan;
    }

    /** 会话累计调用次数。 */
    public synchronized long totalCalls() {
        return totalCalls;
    }

    /** 按模型 rollup 的只读视图（与 {@code sessions.model_usage_json} 同口径）。 */
    public synchronized Map<String, CostTracker.ModelUsage> modelUsage() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(modelUsage));
    }

    /** 层级快照 JSON（写 {@code sessions.usage_tree_json}）· 空账本 → null（不写空串）。 */
    public synchronized String snapshotJson() {
        if (nodes.isEmpty()) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sessionId", sessionId);
        root.put("totalCostYuan", totalCostYuan);
        root.put("totalCalls", totalCalls);
        root.put("rollup", rollup());
        root.put("nodes", nodes());
        root.put("modelUsage", modelUsage);
        return JSON.toJSONString(root);
    }

    /**
     * 从 {@code usage_tree_json} 恢复节点与按模型 rollup（会话启动时调用）。
     *
     * <p>fail-soft：null / 空白 / 解析失败 → 不恢复（保持空账本），与仓库既有 parse* 容错一致。
     */
    @SuppressWarnings("unchecked")
    public synchronized void restoreFromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> root = JSON.parseObject(json, Map.class);
            if (root == null) {
                return;
            }
            Object cost = root.get("totalCostYuan");
            this.totalCostYuan = cost instanceof Number n ? n.doubleValue() : 0.0;
            Object calls = root.get("totalCalls");
            this.totalCalls = calls instanceof Number n ? n.longValue() : 0L;
            Object nodeList = root.get("nodes");
            if (nodeList instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String agentId = str(m.get("agentId"));
                    String category = str(m.get("category"));
                    String parent = str(m.get("parentAgentId"));
                    int depth = m.get("depth") instanceof Number n ? n.intValue() : 0;
                    String label = str(m.get("label"));
                    UsageBucket bucket = parseBucket(m.get("bucket"));
                    UsageAttribution who = new UsageAttribution(sessionId, agentId,
                        parseCategory(category), parent, depth, label);
                    Entry entry = nodes.computeIfAbsent(who.nodeKey(), k -> new Entry(who, UsageBucket.empty()));
                    entry.bucket = bucket;
                }
            }
            Object models = root.get("modelUsage");
            if (models instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Map<String, Object> b = (Map<String, Object>) e.getValue();
                    if (b == null) {
                        continue;
                    }
                    modelUsage.put(String.valueOf(e.getKey()), new CostTracker.ModelUsage(
                        lng(b.get("inputTokens")), lng(b.get("outputTokens")),
                        lng(b.get("cacheReadInputTokens")), lng(b.get("cacheCreationInputTokens")),
                        lng(b.get("webSearchRequests")), dbl(b.get("costUSD")),
                        lng(b.get("contextWindow")), lng(b.get("maxOutputTokens"))));
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SessionUsageLedger.class).warn(
                "[SessionUsageLedger] usage_tree_json 恢复失败（保持空账本）: sessionId={} err={}",
                sessionId, e.getMessage());
        }
    }

    private static UsageBucket parseBucket(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return UsageBucket.empty();
        }
        return new UsageBucket(
            lng(m.get("inputTokens")), lng(m.get("outputTokens")),
            lng(m.get("cacheReadTokens")), lng(m.get("cacheWriteTokens")),
            lng(m.get("calls")), lng(m.get("estimatedCalls")), lng(m.get("failedCalls")),
            lng(m.get("abortedCalls")), lng(m.get("streamCalls")), lng(m.get("nonStreamCalls")),
            dbl(m.get("costYuanPeak")), dbl(m.get("costYuanOffpeak")),
            lng(m.get("firstAtMs")), lng(m.get("lastAtMs")));
    }

    private static UsageCategory parseCategory(String name) {
        if (name == null || name.isBlank()) {
            return UsageCategory.UNKNOWN;
        }
        try {
            return UsageCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            return UsageCategory.UNKNOWN;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * 从一条记账记录把「模型名/usage/source」投影为会话合计（供内部调用，避免重复拼参）。
     */
    public synchronized void record(LlmCallRecord call, double costYuan, boolean peak,
                                    int contextWindow, long maxOutputTokens) {
        if (call == null) {
            return;
        }
        record(call.attribution(), call.usage(), call.tokenSource(), costYuan, peak, call.stream(),
            call.phase(), call.startedAtMs(), call.modelName(), contextWindow, maxOutputTokens);
    }

    // ══════════════ [usage-gateway] LlmUsageRecorder 实现（请求网关的唯一记账出口） ══════════════
    /**
     * 记一次调用尝试（网关入口）· 三件事，顺序固定：
     * <ol>
     *   <li><b>计价</b>：{@link ModelCostCalculator}（DB 价优先 + 高峰/空闲档）折算人民币元 ——
     *       全仓唯一计价点，口径与改造前 {@code ModelCostCalculator.calculateCostYuan} 一致；</li>
     *   <li><b>明细落表</b>：{@code llm_usage_calls} 一行/尝试（用户裁定「每个 call 落表」），
     *       失败 → WARN + 计数并继续（LLM 调用链绝不因统计失败而中断）；</li>
     *   <li><b>账本聚合 + 会话视图回写</b>：进本账本（按 agent 分层），并把结果回写到主
     *       {@link AgentState} 既有字段（视图，STOMP/DTO 契约零改动）。</li>
     * </ol>
     */
    @Override
    public void record(LlmCallRecord call) {
        if (call == null) {
            return;
        }
        boolean peak = costCalculator != null && costCalculator.isPeakHour();
        double costYuan = costCalculator != null
            ? costCalculator.calculateCostYuan(call.modelName(), call.usage(), peak)
            : 0.0;
        int contextWindow = costCalculator != null ? costCalculator.contextWindowFor(call.modelName()) : 0;
        long maxOutputTokens = costCalculator != null ? costCalculator.maxOutputFor(call.modelName()) : 0L;
        LlmCallRecord enriched = call.withCost(costYuan, peak);

        persist(enriched);
        record(enriched, costYuan, peak, contextWindow, maxOutputTokens);

        AgentState owner = mainOwner();
        if (owner != null) {
            mirrorToState(owner, enriched, costYuan, contextWindow, maxOutputTokens);
        }
        if (org.slf4j.LoggerFactory.getLogger(SessionUsageLedger.class).isDebugEnabled()) {
            org.slf4j.LoggerFactory.getLogger(SessionUsageLedger.class).debug(
                "[SessionUsageLedger] 记账: session={} agent={} category={} model={} stream={} phase={} src={} "
                    + "in={} out={} cacheR={} cacheW={} costYuan={} peak={}",
                sessionId, enriched.attribution() != null ? enriched.attribution().agentId() : null,
                enriched.category(), enriched.modelName(), enriched.stream(), enriched.phase(),
                enriched.tokenSource(),
                enriched.usage() != null ? enriched.usage().inputTokens() : 0L,
                enriched.usage() != null ? enriched.usage().outputTokens() : 0L,
                enriched.usage() != null ? enriched.usage().cacheReadInputTokens() : null,
                enriched.usage() != null ? enriched.usage().cacheCreationInputTokens() : null,
                costYuan, peak);
        }
    }

    /**
     * 会话视图回写 · 与改造前 {@code LlmAgentLoop.accumulateSessionUsage} 的字段集逐字一致
     * （会话 input/output token、会话费用、按模型桶），保证既有 STOMP / DTO / 落库口径不变。
     */
    private void mirrorToState(AgentState state, LlmCallRecord call, double costYuan,
                               int contextWindow, long maxOutputTokens) {
        AgentUsage usage = call.usage();
        if (usage == null) {
            return;
        }
        state.addSessionInputTokens(usage.inputTokens());
        state.addSessionOutputTokens(usage.outputTokens());
        state.addSessionCostYuan(costYuan);
        if (call.modelName() != null && !call.modelName().isBlank()) {
            state.mergeSessionModelUsage(call.modelName(),
                CostTracker.computeModelUsageIncrement(costYuan, usage, call.modelName(),
                    contextWindow, maxOutputTokens));
        }
    }

    /** 明细落表（MyBatis-Flex insert；异常 → WARN + 计数，绝不外抛）。 */
    private void persist(LlmCallRecord call) {
        if (usageCallMapper == null) {
            return;
        }
        try {
            com.nexusai.repository.usage.entity.LlmUsageCallRecord rec =
                new com.nexusai.repository.usage.entity.LlmUsageCallRecord();
            rec.setId(call.callId());
            rec.setSessionId(call.sessionId());
            rec.setAgentId(call.attribution() != null ? call.attribution().agentId() : null);
            rec.setCategory(call.category().name());
            rec.setParentAgentId(call.attribution() != null ? call.attribution().parentAgentId() : null);
            rec.setDepth(call.attribution() != null ? call.attribution().depth() : 0);
            rec.setLabel(call.attribution() != null ? call.attribution().label() : null);
            rec.setModelName(call.modelName());
            rec.setSdkModelName(call.sdkModelName());
            rec.setProviderType(call.providerType());
            rec.setQuerySource(call.querySource());
            rec.setStream(call.stream() ? 1 : 0);
            rec.setPhase(call.phase() != null ? call.phase().name() : null);
            rec.setTokenSource(call.tokenSource() != null ? call.tokenSource().name() : null);
            AgentUsage u = call.usage();
            // 列类型为 INTEGER（实体 Integer）· token 数在单次调用量级远小于 2^31，仍做饱和转换
            // （不抛：落表是观测通道，不因异常数值丢整行；饱和上限可在明细里一眼识别）
            rec.setInputTokens(u != null ? saturatedInt(u.inputTokens()) : 0);
            rec.setOutputTokens(u != null ? saturatedInt(u.outputTokens()) : 0);
            rec.setCacheReadInputTokens(u != null && u.cacheReadInputTokens() != null
                ? saturatedInt(u.cacheReadInputTokens()) : null);
            rec.setCacheCreationInputTokens(u != null && u.cacheCreationInputTokens() != null
                ? saturatedInt(u.cacheCreationInputTokens()) : null);
            rec.setCostYuan(call.costYuan());
            rec.setPeakHour(call.peakHour() ? 1 : 0);
            rec.setStatus(call.status());
            rec.setErrorKind(call.errorKind());
            rec.setStartedAt(java.time.Instant.ofEpochMilli(call.startedAtMs()).toString());
            rec.setElapsedMs(saturatedInt(call.elapsedMs()));
            // [usage-gateway · 2026-10-12 修真 bug] 必须用 insertSelective（跳过 null 列），⛔ 不可用 insert：
            //   V98 的 created_at 是 NOT NULL DEFAULT CURRENT_TIMESTAMP，而本账本不设该列（行写入时刻由 DB 给）。
            //   MyBatis-Flex 的 insert() 会把 null 显式写进 INSERT 语句 → 绕过 DEFAULT →
            //   [SQLITE_CONSTRAINT_NOTNULL] NOT NULL constraint failed: llm_usage_calls.created_at →
            //   每次落表都进下面的 catch（只 WARN），明细表永远为空 ⇒ 前端「按调用类型/按代理层级」
            //   永远显示空态且无人察觉（既有代码正是如此，由 LlmUsageCallStorageTest 真库用例抓出）。
            //   先例：QueueAuditService.persist 同款（created_at 走 DB DEFAULT，Java 不设值）。
            usageCallMapper.insertSelective(rec);
        } catch (Exception e) {
            long failures = persistFailures.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger(SessionUsageLedger.class).warn(
                "[SessionUsageLedger] 明细落表失败（第 {} 次）: callId={} session={} model={} err={}",
                failures, call.callId(), call.sessionId(), call.modelName(), e.toString());
        }
    }

    /** long → int 饱和转换（超上限取 Integer.MAX_VALUE；负值取 0）。 */
    private static int saturatedInt(long v) {
        if (v <= 0) {
            return 0;
        }
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    /** 落表失败累计次数（诊断/测试）。 */
    public long persistFailureCount() {
        return persistFailures.get();
    }
}
