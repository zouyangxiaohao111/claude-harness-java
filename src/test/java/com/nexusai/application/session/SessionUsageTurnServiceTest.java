package com.nexusai.application.session;

import com.nexusai.application.session.SessionUsageTurnService.TurnUsage;
import com.nexusai.application.session.SessionUsageTurnService.UsagePoint;
import com.nexusai.application.session.SessionUsageTurnService.UserTurnStart;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import com.nexusai.repository.usage.mapper.LlmUsageCallMapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [turn-usage] {@link SessionUsageTurnService} 分桶/时区/跳过/缓存契约（<b>纯单测</b>：
 * ⛔ 不启 Spring、⛔ 不碰真库 —— 行数据手工构造，「分桶函数」{@code bucket} 直接调用）。
 *
 * <h2>WHY（规则九：每条守的是哪类真实故障）</h2>
 * <ul>
 *   <li><b>跨格式时间比较</b>（本批最易错）：{@code llm_usage_calls.started_at} 中 UTC {@code Z}
 *       （V101 前，实测 96570 行）与本地 {@code +08:00}（V101 起）<b>并存</b>。若退回字符串比较，
 *       {@code 14:39Z} 会被判成早于 {@code 22:39+08:00}（实际是同一秒附近）⇒ 整轮 token 归错轮或丢失。
 *       用例 {@link #mixedZAndOffsetFormats_compareAcrossZones()} 就是拿这个反向实验钉死的；</li>
 *   <li><b>轮边界左闭右开</b>：恰在下一条 user 消息时刻的明细行若归前一轮，会让「上一轮」多算一次
 *       （同一行被下一轮也看到时更是双算）；</li>
 *   <li><b>脏时间不得打断整批</b>：单行 {@code started_at} 非法必须跳过，其它行/其它轮照常；</li>
 *   <li><b>无用量轮次不出现</b>：返回 tokens=0 的假轮次会与「真消耗 0」混淆，前端也会失去
 *       「回落原文案」的判据；</li>
 *   <li><b>10 秒缓存</b>：前端每次打开会话都会打这个接口 —— 缓存失效回退成全量扫 27k 行。</li>
 * </ul>
 */
class SessionUsageTurnServiceTest {

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 1) 三条 user 消息 / 四条用量行分布在两轮
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3 个用户消息 + 4 条用量行分两轮 → 两条结果（第三轮无数据不出现），聚合正确")
    void threeUserMessages_fourUsageRows_bucketIntoTwoTurns() {
        List<UserTurnStart> turns = List.of(
            turn("msg-u1", "2026-10-21T10:00:00+08:00"),
            turn("msg-u2", "2026-10-21T10:05:00+08:00"),
            turn("msg-u3", "2026-10-21T10:10:00+08:00"));
        List<UsagePoint> rows = List.of(
            row("2026-10-21T10:00:05+08:00", 100, 10, 0.1, "deepseek-flash"),
            row("2026-10-21T10:01:00+08:00", 50, 5, 0.05, "deepseek-flash"),
            row("2026-10-21T10:06:00+08:00", 200, 20, 0.2, "deepseek-flash"),
            row("2026-10-21T10:07:00+08:00", 300, 30, null, "deepseek-flash"));

        List<TurnUsage> out = SessionUsageTurnService.bucket(
            turns, rows, Instant.parse("2026-10-21T02:20:00Z"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).userMessageId()).isEqualTo("msg-u1");
        assertThat(out.get(0).inputTokens()).isEqualTo(150L);
        assertThat(out.get(0).outputTokens()).isEqualTo(15L);
        // 0.1 + 0.05 的 double 尾差必须被抹平（对齐 DECIMAL(14,8)）
        assertThat(out.get(0).costYuan()).isEqualTo(0.15);
        assertThat(out.get(0).model()).isEqualTo("deepseek-flash");

        assertThat(out.get(1).userMessageId()).isEqualTo("msg-u2");
        assertThat(out.get(1).inputTokens()).isEqualTo(500L);
        assertThat(out.get(1).outputTokens()).isEqualTo(50L);
        assertThat(out.get(1).costYuan()).isEqualTo(0.2);
        assertThat(out.get(1).model()).isEqualTo("deepseek-flash");

        assertThat(out).extracting(TurnUsage::userMessageId).doesNotContain("msg-u3");
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 2) 混合 Z / +08:00 两种时间格式（本批最易错）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Z 与 +08:00 混合 → 按 Instant 比较（同时刻两格式解析相等），跨时区归属正确")
    void mixedZAndOffsetFormats_compareAcrossZones() {
        // 同一时刻的两种写法必须解析为同一个 Instant（统一口径的前提）
        assertThat(SessionUsageTurnService.parseInstantOrNull(
                "2026-10-21T14:39:40.237Z", "t", "s"))
            .isEqualTo(SessionUsageTurnService.parseInstantOrNull(
                "2026-10-21T22:39:40.237+08:00", "t", "s"));

        // 轮边界全部用 +08:00（真实数据里的新格式）
        List<UserTurnStart> turns = List.of(
            turn("msg-old", "2026-10-21T22:39:39.754+08:00"),
            turn("msg-utc-cross", "2026-10-22T07:00:00+08:00"));   // = 2026-10-21T23:00:00Z

        List<UsagePoint> rows = List.of(
            // UTC Z 格式（V101 前的历史行）：字符串 "14:39" < 边界 "22:39"，
            // 若按字符串比较会被误判为「首轮之前」→ 丢弃。
            row("2026-10-21T14:39:40.237Z", 47874, 407, 0.0701161, "deepseek-flash"),
            // 本地 +08:00 格式：与上一行同轮
            row("2026-10-21T22:40:50.556+08:00", 49671, 270, 0.0057503, "deepseek-flash"),
            // 跨天 Z 行：真实时刻在第二轮起点之后，但字符串 "2026-10-21T23:30" <
            // 边界 "2026-10-22T07:00" ⇒ 字符串比较会误归到第一轮。
            row("2026-10-21T23:30:00Z", 111, 1, 0.0001, "deepseek-flash"),
            row("2026-10-22T07:10:00+08:00", 222, 2, 0.0002, "deepseek-flash"));

        List<TurnUsage> out = SessionUsageTurnService.bucket(
            turns, rows, Instant.parse("2026-10-22T00:00:00Z"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).userMessageId()).isEqualTo("msg-old");
        assertThat(out.get(0).inputTokens()).isEqualTo(47874L + 49671L);
        assertThat(out.get(0).outputTokens()).isEqualTo(407L + 270L);

        assertThat(out.get(1).userMessageId()).isEqualTo("msg-utc-cross");
        assertThat(out.get(1).inputTokens()).isEqualTo(111L + 222L);
        assertThat(out.get(1).outputTokens()).isEqualTo(1L + 2L);
    }

    @Test
    @DisplayName("轮边界左闭右开：恰在下一条 user 消息时刻的行归后一轮")
    void boundaryRow_belongsToNextTurn() {
        List<UserTurnStart> turns = List.of(
            turn("msg-a", "2026-10-21T10:00:00+08:00"),
            turn("msg-b", "2026-10-21T10:05:00+08:00"));
        List<UsagePoint> rows = List.of(
            row("2026-10-21T10:04:59.999+08:00", 1, 1, 0.01, "m"),
            row("2026-10-21T10:05:00+08:00", 2, 2, 0.02, "m"));

        List<TurnUsage> out = SessionUsageTurnService.bucket(
            turns, rows, Instant.parse("2026-10-21T02:10:00Z"));

        assertThat(out.get(0).inputTokens()).isEqualTo(1L);
        assertThat(out.get(1).inputTokens()).isEqualTo(2L);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 3) 某轮无用量行 → 不出现（与「真消耗 0」区分）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("三轮只有中间一轮有用量行 → 结果只含该轮（无数据轮次不返回 tokens=0 假轮）")
    void turnWithoutUsageRows_absentFromResult() {
        List<UserTurnStart> turns = List.of(
            turn("msg-1", "2026-10-21T10:00:00+08:00"),
            turn("msg-2", "2026-10-21T10:05:00+08:00"),
            turn("msg-3", "2026-10-21T10:10:00+08:00"));
        List<UsagePoint> rows = List.of(
            row("2026-10-21T10:06:00+08:00", 7, 3, 0.01, "m"));

        List<TurnUsage> out = SessionUsageTurnService.bucket(
            turns, rows, Instant.parse("2026-10-21T02:20:00Z"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).userMessageId()).isEqualTo("msg-2");
        assertThat(out.get(0).inputTokens()).isEqualTo(7L);

        // 无轮起点 / 无明细行 / 首轮之前的行 → 都是空结果（不抛）
        assertThat(SessionUsageTurnService.bucket(List.of(), rows, Instant.now())).isEmpty();
        assertThat(SessionUsageTurnService.bucket(turns, List.of(), Instant.now())).isEmpty();
        assertThat(SessionUsageTurnService.bucket(turns,
            List.of(row("2026-10-21T09:00:00+08:00", 9, 9, 0.1, "m")), Instant.now())).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 4) 非法 started_at 行被跳过，不影响其它行（走真实装载路径，fake mapper）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非法 started_at 行被跳过且不影响其它行（装载路径 + 分桶）")
    void invalidStartedAtRows_skippedWithoutAffectingOthers() {
        // 解析器本身：垃圾串 → null（调用方据此跳过），合法格式 → Instant
        assertThat(SessionUsageTurnService.parseInstantOrNull("not-a-timestamp", "t", "s")).isNull();
        assertThat(SessionUsageTurnService.parseInstantOrNull("2026-99-99T00:00:00+08:00", "t", "s")).isNull();
        assertThat(SessionUsageTurnService.parseInstantOrNull("", "t", "s")).isNull();
        assertThat(SessionUsageTurnService.parseInstantOrNull(null, "t", "s")).isNull();

        SessionUsageTurnService svc = new SessionUsageTurnService();
        ReflectionTestUtils.setField(svc, "messageMapper", fakeMapper(MessageMapper.class, List.of(
            msg("msg-1", "2026-10-21T10:00:00+08:00"),
            msg("msg-2", "2026-10-21T10:05:00+08:00"))));
        ReflectionTestUtils.setField(svc, "usageCallMapper", fakeMapper(LlmUsageCallMapper.class, List.of(
            usage("2026-10-21T10:00:05+08:00", 100, 10, 0.1, "m"),
            usage("garbage", 999, 999, 9.9, "m"),                     // 非法 → 跳过
            usage("2026-13-45T99:99:99Z", 999, 999, 9.9, "m"),        // 非法 → 跳过
            usage("", 999, 999, 9.9, "m"),                            // 空 → 跳过
            usage("2026-10-21T10:06:00+08:00", 200, 20, null, "m"))));
        svc.clock = () -> Instant.parse("2026-10-21T02:20:00Z").toEpochMilli();

        List<TurnUsage> out = svc.listTurnUsage("sess-test");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).inputTokens()).isEqualTo(100L);
        assertThat(out.get(0).outputTokens()).isEqualTo(10L);
        assertThat(out.get(1).inputTokens()).isEqualTo(200L);
        assertThat(out.get(1).outputTokens()).isEqualTo(20L);
        // 非法行的 999 tokens / 9.9 元一律不得混入
        assertThat(out.get(0).costYuan()).isEqualTo(0.1);
        assertThat(out.get(1).costYuan()).isNull();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 5) model 选择口径
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("model = 该轮 cost 合计最大者；并列取最后出现；全 null → null")
    void modelSelection_largestCost_tieTakesLast_allNullYieldsNull() {
        List<UserTurnStart> turns = List.of(turn("msg-1", "2026-10-21T10:00:00+08:00"));
        Instant now = Instant.parse("2026-10-21T02:20:00Z");

        // (a) 合计最大：A = 0.3 + 0.3 = 0.6 > B = 0.5（尽管 B 单次更大）
        List<TurnUsage> largest = SessionUsageTurnService.bucket(turns, List.of(
            row("2026-10-21T10:00:01+08:00", 1, 1, 0.3, "model-a"),
            row("2026-10-21T10:00:02+08:00", 1, 1, 0.5, "model-b"),
            row("2026-10-21T10:00:03+08:00", 1, 1, 0.3, "model-a")), now);
        assertThat(largest.get(0).model()).isEqualTo("model-a");
        assertThat(largest.get(0).costYuan()).isEqualTo(1.1);

        // (b) 合计并列 → 最后出现的模型（model-b 的行在 model-a 之后）
        List<TurnUsage> tie = SessionUsageTurnService.bucket(turns, List.of(
            row("2026-10-21T10:00:01+08:00", 1, 1, 0.5, "model-a"),
            row("2026-10-21T10:00:02+08:00", 1, 1, 0.5, "model-b")), now);
        assertThat(tie.get(0).model()).isEqualTo("model-b");

        // (c) 整轮 cost 全 null → model/costYuan 皆 null，但 tokens 照常累加
        List<TurnUsage> noCost = SessionUsageTurnService.bucket(turns, List.of(
            row("2026-10-21T10:00:01+08:00", 5, 6, null, "model-a")), now);
        assertThat(noCost.get(0).model()).isNull();
        assertThat(noCost.get(0).costYuan()).isNull();
        assertThat(noCost.get(0).inputTokens()).isEqualTo(5L);
        assertThat(noCost.get(0).outputTokens()).isEqualTo(6L);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 6) 10 秒会话缓存
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("10 秒缓存：TTL 内只装载一次，过期后重新计算；不同会话互不干扰")
    void cache_servedWithinTtl_recomputedAfterTtl() {
        CountingService svc = new CountingService();
        long base = Instant.parse("2026-10-21T02:20:00Z").toEpochMilli();
        long[] now = {base};
        svc.clock = () -> now[0];

        svc.listTurnUsage("sess-a");
        svc.listTurnUsage("sess-a");
        assertThat(svc.turnLoads.get()).isEqualTo(1);
        assertThat(svc.pointLoads.get()).isEqualTo(1);

        // 另一会话 → 各自独立缓存
        svc.listTurnUsage("sess-b");
        assertThat(svc.turnLoads.get()).isEqualTo(2);

        // TTL 未到（+9.999s）→ 仍命中 sess-a
        now[0] = base + 9_999L;
        svc.listTurnUsage("sess-a");
        assertThat(svc.turnLoads.get()).isEqualTo(2);

        // 过期（+10s）→ 重算
        now[0] = base + SessionUsageTurnService.CACHE_TTL_MS;
        svc.listTurnUsage("sess-a");
        assertThat(svc.turnLoads.get()).isEqualTo(3);
        assertThat(svc.pointLoads.get()).isEqualTo(3);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 轮起点（时间串按 ISO-8601 带偏移解析，与生产写入格式同构）。 */
    private static UserTurnStart turn(String id, String iso) {
        return new UserTurnStart(id, OffsetDateTime.parse(iso).toInstant());
    }

    /** 明细行投影（生产装载路径解析后的形态）。 */
    private static UsagePoint row(String iso, Integer in, Integer out, Double cost, String model) {
        return new UsagePoint(OffsetDateTime.parse(iso).toInstant(), in, out, cost, model);
    }

    /** 消息行（仅 loader 读取的两列有值）。 */
    private static MessageRecord msg(String id, String createdAt) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setRole("user");
        m.setCreatedAt(createdAt);
        return m;
    }

    /** 明细行（仅 loader 读取的 5 列有值）。 */
    private static LlmUsageCallRecord usage(String startedAt, Integer in, Integer out,
                                            Double cost, String model) {
        LlmUsageCallRecord r = new LlmUsageCallRecord();
        r.setStartedAt(startedAt);
        r.setInputTokens(in);
        r.setOutputTokens(out);
        r.setCostYuan(cost);
        r.setModelName(model);
        return r;
    }

    /**
     * 极简 fake mapper（JDK 动态代理）：{@code selectListByQuery} 返回给定行。
     * ⛔ 不启 Spring、⛔ 不碰真库 —— 只验证「装载 → 解析 → 分桶」这条真实代码路径。
     */
    @SuppressWarnings("unchecked")
    private static <T> T fakeMapper(Class<T> iface, List<?> rows) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
            (proxy, method, args) -> {
                if ("selectListByQuery".equals(method.getName())) {
                    return new ArrayList<>(rows);
                }
                if ("toString".equals(method.getName())) {
                    return iface.getSimpleName() + "$fake";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return null;
            });
    }

    /** 计数子类：只为观察缓存是否真的省掉了装载（覆写包内装载钩子）。 */
    private static final class CountingService extends SessionUsageTurnService {
        final AtomicInteger turnLoads = new AtomicInteger();
        final AtomicInteger pointLoads = new AtomicInteger();

        @Override
        List<UserTurnStart> loadUserTurns(String sessionId) {
            turnLoads.incrementAndGet();
            return List.of(new UserTurnStart(sessionId + "-u1",
                OffsetDateTime.parse("2026-10-21T10:00:00+08:00").toInstant()));
        }

        @Override
        List<UsagePoint> loadUsagePoints(String sessionId) {
            pointLoads.incrementAndGet();
            return List.of(new UsagePoint(
                OffsetDateTime.parse("2026-10-21T10:00:05+08:00").toInstant(),
                10, 2, 0.01, "m"));
        }
    }
}
