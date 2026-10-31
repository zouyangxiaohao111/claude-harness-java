package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import com.nexusai.repository.usage.mapper.LlmUsageCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * [turn-usage] 历史会话「按轮聚合」的用量回填源（<b>只读</b>）· 供前端在消息级
 * {@code input_tokens/output_tokens} 缺失（旧消息未回填）时回落到「本轮消耗」。
 *
 * <h2>WHY（问题与真源）</h2>
 * {@code messages} 表的 token 列是<b>后来才落库</b>的：历史轮次 {@code input_tokens/output_tokens}
 * 为 NULL（实测某会话最近 500 条只有 4 条有值）⇒ 重新打开历史会话时轮次底部只剩「模型 · 时间」。
 * 但 {@code llm_usage_calls}（V98 明细表）<b>逐次调用都有完整用量</b>（同会话实测 27k+ 行），
 * 因此按「用户消息 → 下一次用户消息」的时间窗把明细行分桶，即可还原每一轮的消耗。
 *
 * <h2>轮边界</h2>
 * 取该会话 {@code role=user} 且<b>非 meta</b>（{@code is_meta IS NULL OR is_meta != 1}，与
 * {@code /messages/count} 同口径）的消息，按 {@code created_at} 升序；每条 user 消息是它那一轮的
 * <b>起点</b>，下一条 user 消息的时间是<b>终点</b>（左闭右开：恰在边界上的明细行归<b>后</b>一轮）；
 * 最后一轮终点 = 计算时刻 now。
 *
 * <h2>口径（与前端展示一致）</h2>
 * <ul>
 *   <li>该轮<b>全部</b>明细行都算 —— MAIN / SUBAGENT / TEAMMATE / COMPACT / BACKGROUND / SIDE_CALL …
 *       ⛔ <b>不按 category 过滤</b>：前端显示的是「本轮消耗」，子代理与压缩消耗同属本轮。</li>
 *   <li>{@code costYuan} = 该轮 {@code cost_yuan} 求和（8 位小数取整，对齐 DECIMAL(14,8) 列精度）；
 *       整轮<b>没有任何非 null cost 行</b> → {@code null}（前端显示「—」，⛔ 不伪造 0）。</li>
 *   <li>{@code model} = 该轮 {@code cost_yuan} 合计最大的 {@code model_name}（并列取<b>最后出现</b>的那个）；
 *       整轮没有任何带 cost 的模型名 → {@code null}。</li>
 *   <li><b>无任何明细行的轮次不出现在结果里</b>（{@link #bucket} 的契约，前端回落原文案
 *       「模型 · 时间」）—— 而不是返回 tokens=0 的假轮次（那会和「真消耗 0」混淆）。</li>
 * </ul>
 *
 * <h2>时间格式（本模块最易错处）</h2>
 * {@code llm_usage_calls.started_at} 两种格式<b>并存</b>：V101 之前是 UTC {@code ...Z}
 * （实测 96570 行，如 {@code 2026-10-13T05:57:37.114Z}），V101 起是本地带偏移
 * （如 {@code 2026-10-29T07:50:14.783+08:00}）。因此<b>一律</b>
 * {@link OffsetDateTime#parse} → {@link Instant} 再比较，⛔ <b>绝不字符串比较</b>：
 * 跨格式字符串序与真实时间序相反（{@code 14:39Z} 早于 {@code 22:39+08:00}，但字典序更小）。
 * 解析失败的行/边界：<b>跳过 + debug</b>，⛔ 不抛异常（单行脏数据不得让整个接口 500）。
 *
 * <h2>性能</h2>
 * 27k 行量级可接受（一次查询 + 内存分桶，⛔ 不为每轮发 SQL）。另加<b>按会话 10 秒内存缓存</b>
 * （{@link ConcurrentHashMap}，key=sessionId）：前端每次打开会话都会打这个接口，缓存避免重复全量扫。
 * 会话删除后同 id 不会复现 ⇒ 缓存「自然失效」（不主动清）；条目数超过 {@value #CACHE_MAX_ENTRIES}
 * 时先清过期项、仍超则整体丢弃（纯防内存无界）。
 *
 * <h2>只读</h2>
 * 本类只发 SELECT：不写任何表，不改迁移。
 */
@Service
public class SessionUsageTurnService {

    private static final Logger log = LoggerFactory.getLogger(SessionUsageTurnService.class);

    /** 缓存有效期（ms）· 见类 javadoc「性能」。 */
    static final long CACHE_TTL_MS = 10_000L;

    /** 缓存条目上限（超过先清过期项；仍超则整体丢弃）。 */
    static final int CACHE_MAX_ENTRIES = 512;

    /** 明细行时间字段名（日志/解析用）。 */
    private static final String FIELD_STARTED_AT = "llm_usage_calls.started_at";

    /** 消息时间字段名（日志/解析用）。 */
    private static final String FIELD_CREATED_AT = "messages.created_at";

    /** 消息表 mapper · {@code required=false}：非 Spring 单测/未装配 → 返回空轮次（fail-soft）。 */
    @Autowired(required = false)
    private MessageMapper messageMapper;

    /** 明细表 mapper · {@code required=false}：同上 → 返回空轮次。 */
    @Autowired(required = false)
    private LlmUsageCallMapper usageCallMapper;

    /**
     * 可注入时钟（毫秒）。生产 = {@link System#currentTimeMillis}；单测可替换以验证 TTL，
     * ⛔ 不为测试开后门 API（包内可见字段）。
     */
    LongSupplier clock = System::currentTimeMillis;

    /** 会话级结果缓存（key=sessionId）。 */
    private final Map<String, CachedTurns> cache = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 对外入口
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 该会话每一轮（以非 meta user 消息为起点）的用量聚合，按 {@code userMessageId} 时间升序。
     *
     * @param sessionId 会话 ID（DB 键 "sess-xxx"）；null/空白 → 空列表（⛔ 不抛，前端回落）
     * @return 仅含「至少 1 条明细行」的轮次；无数据 → 空列表（HTTP 200 {@code {"turns": []}}）
     */
    public List<TurnUsage> listTurnUsage(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] sessionId 为空 → 返回空轮次列表（不查库）");
            }
            return List.of();
        }
        long nowMs = clock.getAsLong();
        CachedTurns cached = cache.get(sessionId);
        if (cached != null && nowMs - cached.computedAtMillis() < CACHE_TTL_MS) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] 缓存命中: session={} 轮次={} 缓存年龄={}ms（TTL={}ms）",
                    sessionId, cached.turns().size(), nowMs - cached.computedAtMillis(), CACHE_TTL_MS);
            }
            return cached.turns();
        }

        long t0 = System.nanoTime();
        List<UserTurnStart> turnStarts = loadUserTurns(sessionId);
        List<UsagePoint> usagePoints = loadUsagePoints(sessionId);
        List<TurnUsage> turns = bucket(turnStarts, usagePoints, Instant.ofEpochMilli(nowMs));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        if (log.isInfoEnabled()) {
            log.info("[SessionUsageTurn] 轮次用量计算: session={} 用户消息={} 明细行={} 结果轮次={} "
                    + "耗时={}ms（缓存 {}s）",
                sessionId, turnStarts.size(), usagePoints.size(), turns.size(), elapsedMs,
                CACHE_TTL_MS / 1000);
        }
        cache.put(sessionId, new CachedTurns(turns, nowMs));
        purgeIfNeeded(nowMs);
        return turns;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 数据装载（包内可见 → 单测可覆写，无需 Spring / 真库）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 该会话全部「轮起点」= 非 meta user 消息（id + created_at，升序）。
     *
     * <p>只取两列：{@code content} 等大字段对分桶无用（同 {@code listPivotCandidates} 的最小列集做法）。
     * 解析失败的行<b>跳过</b>（debug）—— 少一个边界只影响该轮归属，不影响其它轮。
     *
     * @return 按 {@link Instant} 升序；mapper 未装配/查询异常 → 空列表（fail-soft）
     */
    List<UserTurnStart> loadUserTurns(String sessionId) {
        if (messageMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] messageMapper 未装配 → 轮起点按空处理: session={}", sessionId);
            }
            return List.of();
        }
        try {
            List<MessageRecord> rows = messageMapper.selectListByQuery(QueryWrapper.create()
                .select("id", "created_at")
                .where("session_id = ? AND role = 'user' AND (is_meta IS NULL OR is_meta != 1)", sessionId)
                .orderBy("created_at", true));
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<UserTurnStart> out = new ArrayList<>(rows.size());
            for (MessageRecord r : rows) {
                if (r == null || r.getId() == null) {
                    continue;
                }
                Instant startedAt = parseInstantOrNull(r.getCreatedAt(), FIELD_CREATED_AT, sessionId);
                if (startedAt == null) {
                    continue;
                }
                out.add(new UserTurnStart(r.getId(), startedAt));
            }
            // SQL 的 orderBy 只是取数顺序；跨格式（Z / +08:00）字典序 ≠ 时间序 ⇒ 内存按 Instant 重排
            out.sort(Comparator.comparing(UserTurnStart::startedAt));
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] 轮起点装载: session={} 行={} 有效边界={}", sessionId,
                    rows.size(), out.size());
            }
            return out;
        } catch (Exception e) {
            log.warn("[SessionUsageTurn] 轮起点查询失败（按空轮次回落）: session={} err={}",
                sessionId, e.toString());
            return List.of();
        }
    }

    /**
     * 该会话全部明细行（只取分桶需要的 5 列）· <b>一次查询</b>，⛔ 不为每轮发 SQL。
     *
     * <p>解析失败的行<b>跳过</b>（debug，见类 javadoc「时间格式」）。
     *
     * @return 未排序（{@link #bucket} 内部统一按 {@link Instant} 排序）；异常/未装配 → 空列表（fail-soft）
     */
    List<UsagePoint> loadUsagePoints(String sessionId) {
        if (usageCallMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] usageCallMapper 未装配 → 明细按空处理: session={}", sessionId);
            }
            return List.of();
        }
        try {
            List<LlmUsageCallRecord> rows = usageCallMapper.selectListByQuery(QueryWrapper.create()
                .select("started_at", "input_tokens", "output_tokens", "cost_yuan", "model_name")
                .where("session_id = ?", sessionId));
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<UsagePoint> out = new ArrayList<>(rows.size());
            int skipped = 0;
            for (LlmUsageCallRecord r : rows) {
                if (r == null) {
                    continue;
                }
                Instant startedAt = parseInstantOrNull(r.getStartedAt(), FIELD_STARTED_AT, sessionId);
                if (startedAt == null) {
                    skipped++;
                    continue;
                }
                out.add(new UsagePoint(startedAt, r.getInputTokens(), r.getOutputTokens(),
                    r.getCostYuan(), r.getModelName()));
            }
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] 明细装载: session={} 行={} 有效={} 时间解析失败跳过={}",
                    sessionId, rows.size(), out.size(), skipped);
            }
            return out;
        } catch (Exception e) {
            log.warn("[SessionUsageTurn] 明细查询失败（按空用量回落）: session={} err={}",
                sessionId, e.toString());
            return List.of();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 分桶（纯函数 · 单测直调，不依赖 Spring / DB）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 按「轮起点 → 下一次轮起点」的<b>左闭右开</b>时间窗把明细行分桶。
     *
     * <p><b>契约</b>：
     * <ul>
     *   <li>恰在边界时刻的行归<b>后</b>一轮（{@code [start_i, start_{i+1})}）；</li>
     *   <li>第一轮起点<b>之前</b>的行无轮可归 → 丢弃（debug 计数）；</li>
     *   <li>时刻 {@code >= endExclusive}（now）的行 → 丢弃（最后一轮终点 = now；只可能是
     *       计算期间的在途调用，缓存过期后自然出现）；</li>
     *   <li><b>无明细行的轮次不出现在结果</b>（见类 javadoc 口径）；</li>
     *   <li>结果按轮起点时间升序（= {@code userMessageId} 时间序）。</li>
     * </ul>
     *
     * <p>复杂度 O(m log m + n)，m = 明细行数，n = 轮数；⛔ 不是每轮扫一遍全部行。
     *
     * @param userTurns    轮起点（顺序任意，内部排序；null/空 → 空结果）
     * @param usagePoints  明细行（顺序任意，内部按 {@link Instant} 排序；null → 空结果）
     * @param endExclusive 最后一轮终点（now）
     * @return 有数据的轮次聚合（升序）
     */
    static List<TurnUsage> bucket(List<UserTurnStart> userTurns,
                                  List<UsagePoint> usagePoints,
                                  Instant endExclusive) {
        if (userTurns == null || userTurns.isEmpty()) {
            return List.of();
        }
        List<UserTurnStart> starts = new ArrayList<>(userTurns);
        starts.removeIf(t -> t == null || t.startedAt() == null);
        if (starts.isEmpty()) {
            return List.of();
        }
        starts.sort(Comparator.comparing(UserTurnStart::startedAt));

        List<UsagePoint> points = usagePoints == null ? new ArrayList<>() : new ArrayList<>(usagePoints);
        points.removeIf(p -> p == null || p.startedAt() == null);
        // ⚠️ 必须按 Instant 排序：started_at 字符串跨格式（Z / +08:00）字典序 ≠ 时间序
        points.sort(Comparator.comparing(UsagePoint::startedAt));

        List<TurnAccumulator> accs = new ArrayList<>(starts.size());
        for (UserTurnStart s : starts) {
            accs.add(new TurnAccumulator(s.userMessageId()));
        }

        Instant firstStart = starts.get(0).startedAt();
        int droppedBefore = 0;
        int droppedAfter = 0;
        int turnIndex = 0;
        for (int i = 0; i < points.size(); i++) {
            UsagePoint p = points.get(i);
            if (p.startedAt().isBefore(firstStart)) {
                droppedBefore++;
                continue;
            }
            if (endExclusive != null && !p.startedAt().isBefore(endExclusive)) {
                droppedAfter++;
                continue;
            }
            // 推进到下一条轮起点之前（右边界），即 p ∈ [start_ti, start_{ti+1})
            while (turnIndex + 1 < starts.size()
                && !p.startedAt().isBefore(starts.get(turnIndex + 1).startedAt())) {
                turnIndex++;
            }
            accs.get(turnIndex).add(p, i);
        }

        List<TurnUsage> out = new ArrayList<>(accs.size());
        for (TurnAccumulator a : accs) {
            TurnUsage t = a.toResult();
            if (t != null) {
                out.add(t);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[SessionUsageTurn] 分桶完成: 轮起点={} 明细={} 结果轮={} 首轮前丢弃={} 终点后丢弃={}",
                starts.size(), points.size(), out.size(), droppedBefore, droppedAfter);
        }
        return List.copyOf(out);
    }

    /**
     * 时间串 → {@link Instant}（统一比较口径）· 解析失败返回 null（调用方跳过 + 已有 debug 日志）。
     *
     * <p>顺序：{@link OffsetDateTime#parse}（同时覆盖 {@code Z} 与 {@code +08:00}）→
     * {@link Instant#parse}（兜底）→ {@link LocalDateTime#parse} 按 <b>UTC</b> 解释（仅覆盖
     * SQLite {@code datetime('now')} 默认文本 {@code yyyy-MM-dd HH:mm:ss}，该函数产出 UTC）。
     *
     * @param raw          原始时间串（null/空白 → null）
     * @param field        字段名（仅日志）
     * @param sessionId    会话 ID（仅日志）
     */
    static Instant parseInstantOrNull(String raw, String field, String sessionId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // 落到下一种格式
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // 落到下一种格式
        }
        try {
            String iso = v.indexOf(' ') > 0 ? v.replace(' ', 'T') : v;
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageTurn] 时间解析失败（已跳过该行/该边界）: session={} field={} raw={}",
                    sessionId, field, raw);
            }
            return null;
        }
    }

    /** 缓存条目（不可变结果 + 计算时刻）。 */
    private record CachedTurns(List<TurnUsage> turns, long computedAtMillis) {}

    /** 缓存容量兜底：超上限先清过期项，仍超则整体丢弃（纯防内存无界，不影响正确性）。 */
    private void purgeIfNeeded(long nowMs) {
        if (cache.size() <= CACHE_MAX_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(e -> nowMs - e.getValue().computedAtMillis() >= CACHE_TTL_MS);
        if (cache.size() > CACHE_MAX_ENTRIES) {
            log.warn("[SessionUsageTurn] 缓存条目超上限（{}）且无可清理过期项 → 整体丢弃（只影响命中率）",
                cache.size());
            cache.clear();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 数据记录
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 一轮的用量聚合（接口 {@code GET /api/v1/sessions/{sessionId}/usage/turns} 的元素）。
     *
     * @param userMessageId 轮起点用户消息 ID（前端按它挂到对应轮次底部）
     * @param inputTokens   本轮全部明细行 input_tokens 之和（null 行按 0）
     * @param outputTokens  本轮全部明细行 output_tokens 之和（null 行按 0）
     * @param costYuan      本轮 cost_yuan 之和（8 位小数取整）；整轮无非 null cost → {@code null}
     * @param model         本轮 cost 合计最大的 model_name（并列取最后出现）；无带 cost 的模型 → {@code null}
     */
    public record TurnUsage(String userMessageId, long inputTokens, long outputTokens,
                            Double costYuan, String model) {}

    /** 轮起点（user 消息 id + 解析后的起始时刻）。 */
    record UserTurnStart(String userMessageId, Instant startedAt) {}

    /** 明细行投影（只含分桶需要的 5 列）。 */
    record UsagePoint(Instant startedAt, Integer inputTokens, Integer outputTokens,
                      Double costYuan, String modelName) {}

    /** 单轮累加器（仅 bucket 内部使用）。 */
    private static final class TurnAccumulator {

        private final String userMessageId;
        private long inputTokens;
        private long outputTokens;
        /** null 直到出现第一条非 null cost 行（区分「无 cost 数据」与「cost 真为 0」）。 */
        private Double costYuan;
        private int rows;
        private final Map<String, ModelAccumulator> models = new LinkedHashMap<>();

        private TurnAccumulator(String userMessageId) {
            this.userMessageId = userMessageId;
        }

        private void add(UsagePoint p, int rowIndex) {
            rows++;
            inputTokens += p.inputTokens() != null ? p.inputTokens() : 0;
            outputTokens += p.outputTokens() != null ? p.outputTokens() : 0;
            if (p.costYuan() != null) {
                costYuan = (costYuan != null ? costYuan : 0.0) + p.costYuan();
            }
            if (p.modelName() != null) {
                ModelAccumulator m = models.computeIfAbsent(p.modelName(), k -> new ModelAccumulator());
                if (p.costYuan() != null) {
                    m.costYuan += p.costYuan();
                    m.hasCost = true;
                }
                m.lastRowIndex = rowIndex;
            }
        }

        /** 无明细行 → null（该轮不出现在结果里）。 */
        private TurnUsage toResult() {
            if (rows == 0) {
                return null;
            }
            String model = null;
            double bestCost = 0.0;
            int bestLastIndex = -1;
            boolean found = false;
            for (Map.Entry<String, ModelAccumulator> e : models.entrySet()) {
                ModelAccumulator m = e.getValue();
                if (!m.hasCost) {
                    continue;
                }
                // 并列（sum 相等）取最后出现的那个（lastRowIndex 更大）
                if (!found || m.costYuan > bestCost
                    || (Double.compare(m.costYuan, bestCost) == 0 && m.lastRowIndex > bestLastIndex)) {
                    found = true;
                    bestCost = m.costYuan;
                    bestLastIndex = m.lastRowIndex;
                    model = e.getKey();
                }
            }
            return new TurnUsage(userMessageId, inputTokens, outputTokens, roundCost(costYuan), model);
        }

        /** 对齐 DECIMAL(14,8) 列精度取整，抹掉 double 求和尾差（如 0.30000000000000004）。 */
        private static Double roundCost(Double v) {
            if (v == null) {
                return null;
            }
            return Math.round(v * 1e8) / 1e8;
        }
    }

    /** 单模型累加（model 选择用：合计 cost + 最后出现位置）。 */
    private static final class ModelAccumulator {
        private double costYuan;
        private boolean hasCost;
        private int lastRowIndex = -1;
    }
}
