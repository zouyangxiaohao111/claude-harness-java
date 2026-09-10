package com.nexusai.domain.session;

import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [created_at 单调分配器] {@code MessageService.nextCreatedAt} = per-session 单调取号（所有写路径单点）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：{@code messages.created_at} 承载会话消息的<b>时间语义</b>
 * （展示「X 分钟前」+ 分配器取号）；<b>位置语义</b>自 V70 起归 {@code seq}
 * （{@code listBySession}/{@code listPageBySession} ORDER BY seq；读侧
 * {@code BoundaryReader.getMessagesAfterCompactBoundary} 按该序切片剪枝）。若新写入的时间戳早于该会话
 * 已有行（旧实现：实时落库用 run 起始 baseTs、compact 追加用调用时 now()，两条时间基不同源），
 * 则时间序倒挂（V70 前因 created_at 兼任位置键 → boundary 切片把「边界之后本应保留的那轮」整段剪掉，
 * 还拆散 tool_use/tool_result 配对）。
 *
 * <p>本测试锁死分配器三条不变量：
 * <ol>
 *   <li><b>seed = DB 该会话 max(created_at)</b> 且首次 seed 只查一次 DB（之后内存自增，不回落）；</li>
 *   <li><b>连续取号严格递增</b>（同一毫秒内也 +1ns），即「任何新写入恒晚于该会话已有的所有行」；</li>
 *   <li><b>DB max 在未来（时钟回拨/未来时间戳）时不回退</b>：从 max+1ns 起（恒 &gt; DB max）。</li>
 * </ol>
 *
 * <p><b>RED 条件</b>：改回 {@code OffsetDateTime.now()} 取号 → DB max 很大时首次取号早于 max → ③ 红；
 * 去掉 {@code max(now, prev+1)} 的 +1ns → ② 红；seed 不查 DB（恒 0）→ ③ 红；seed 每次取号都查 DB
 * → ① 的 query 次数断言红。
 */
@DisplayName("[created_at 单调分配器] MessageService.nextCreatedAt = per-session 单调取号")
class MessageServiceNextCreatedAtTest {

    private static final String SESSION = "sess-ts01";

    private MessageService service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
    }

    /** mock DB：该会话只有一行，created_at = 给定文本（null → 空结果集，等价无行）。 */
    private void givenDbMax(String createdAt) {
        if (createdAt == null) {
            when(messageMapper.selectListByQuery(any())).thenReturn(List.of());
            return;
        }
        MessageRecord row = new MessageRecord();
        row.setId("msg-1");
        row.setSessionId(SESSION);
        row.setCreatedAt(createdAt);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("① seed 取 DB max（首次只查一次 DB）→ 两次取号严格递增")
    void seedFromDbMax_thenStrictlyIncreasing() {
        String dbMax = "2026-01-01T00:00:00+08:00";
        givenDbMax(dbMax);

        OffsetDateTime t1 = service.nextCreatedAt(SESSION);
        OffsetDateTime t2 = service.nextCreatedAt(SESSION);

        assertThat(t1).as("恒晚于 DB 已有行（seed 为过去 → 取 max(now, seed+1) = now）")
            .isAfter(OffsetDateTime.parse(dbMax));
        assertThat(t2).as("同毫秒内连续取号仍严格递增（+1ns 保序）").isAfter(t1);
        // seed 只查一次 DB 且只查一次该会话（之后内存自增；key 命中不再回查）
        verify(messageMapper, times(1)).selectListByQuery(any());
    }

    @Test
    @DisplayName("② 无行（空结果集）→ 从 now 起单调（不抛、不并列）")
    void noRows_monotonicFromNow() {
        givenDbMax(null);
        // 分配器以毫秒为基（nanos 仅用于 +1 排序）→ 比较基准先截到毫秒，避免 OffsetDateTime.now()
        //   的微秒尾数造成「取号值早于调用前时刻」的假红。
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        OffsetDateTime t1 = service.nextCreatedAt(SESSION);
        OffsetDateTime t2 = service.nextCreatedAt(SESSION);
        OffsetDateTime t3 = service.nextCreatedAt(SESSION);

        assertThat(t1.toInstant()).as("无行 → seed=0 → 首号 = now（不早于调用前时刻，毫秒基）")
            .isAfterOrEqualTo(before.toInstant());
        assertThat(t2).as("严格递增").isAfter(t1);
        assertThat(t3).as("严格递增（连续取号不并列）").isAfter(t2);
    }

    @Test
    @DisplayName("③ DB max 在未来 → 首次取号 = max+1ns（不回退到 now）")
    void dbMaxInFuture_noRegression() {
        String future = "2100-01-01T00:00:00+08:00";
        givenDbMax(future);
        OffsetDateTime seed = OffsetDateTime.parse(future);

        OffsetDateTime t1 = service.nextCreatedAt(SESSION);
        OffsetDateTime t2 = service.nextCreatedAt(SESSION);

        assertThat(t1.toInstant()).as("首号 = DB max + 1ns（恒晚于该会话所有已有行，即使 max 在未来）")
            .isEqualTo(seed.plusNanos(1).toInstant());
        assertThat(t1).as("不回退到 now（旧 now() 实现 → 顺序倒挂 → RED）").isAfter(seed);
        assertThat(t2).as("后续仍严格递增").isAfter(t1);
    }

    @Test
    @DisplayName("④ DB max 解析失败（历史脏格式）→ 回落 now 并保持单调（fail loud 不抛）")
    void unparsableDbMax_fallsBackToNowWithoutThrowing() {
        givenDbMax("2026-01-01 00:00:00");   // SQLite CURRENT_TIMESTAMP 风格（无 T/offset）
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        OffsetDateTime t1 = service.nextCreatedAt(SESSION);
        OffsetDateTime t2 = service.nextCreatedAt(SESSION);

        assertThat(t1.toInstant()).as("解析失败 → seed=0 → 回落 now（不抛异常打断落库）")
            .isAfterOrEqualTo(before.toInstant());
        assertThat(t2).as("仍严格递增").isAfter(t1);
    }

    // ════════════════════════════════════════════════════════════════════
    // [seq 排序键] 雪花取号 · 全局单调 long（免 seed 查询）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑤ [seq] 雪花取号：连续调用严格递增且互不相等，且零 DB 交互（免 seed）")
    void seqSnowflakeStrictlyIncreasingUniqueNoDbSeed() {
        // WHY（CLAUDE.md 规则九 · 不变量）：{@code seq} 是会话内位置键，{@code listPageBySession} 用它做
        //   游标（{@code qw.lt("seq", pivot.getSeq())}）。旧 per-session 实现需 seed=DB max(seq) 查询，
        //   且 seed 失败要回落安全基数（否则与存量行 1..N 重复 → 游标并列丢行）。雪花实现全局唯一且
        //   单调 → 免 seed 查询、免 per-JVM 重复（多实例安全）、免回落边角。此处锁死这三条意图。
        //   RED：取号改回 seed 查询 → never() 红；返回值改常量/不推进 → 递增或唯一性红。
        long s1 = service.nextSeq(SESSION);
        long s2 = service.nextSeq(SESSION);
        long s3 = service.nextSeq(SESSION);

        assertThat(s1).as("雪花号为正（hutool Snowflake 恒 > 0）").isPositive();
        assertThat(s2).as("严格递增").isGreaterThan(s1);
        assertThat(s3).as("严格递增（连续取号不并列 → 游标不丢行）").isGreaterThan(s2);
        assertThat(java.util.Set.of(s1, s2, s3)).as("互不相等（全局唯一）").hasSize(3);
        assertThat(s1).as("雪花号恒大于 V70 回填的 1..N（混排后新行恒排在旧行之后）")
            .isGreaterThan(1_000_000L);
        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("⑥ [seq] 雪花取号全局单调：跨 sessionId 仍共享同一递增序列（不再 per-session seed）")
    void seqSnowflakeGlobalAcrossSessions() {
        // WHY：旧实现 key=sessionId 各自 seed → 两个会话可分配出相同 seq（per-JVM 内不冲突但语义上
        //   非全局唯一）；雪花号全局唯一 → 跨会话仍递增且互不相等。RED：改回 seqAlloc per-session 槽位
        //   （各自从 0/seed 起）→ sB 不 > sA 或两者相等 → 红。
        long sA = service.nextSeq("sess-a");
        long sB = service.nextSeq("sess-b");

        assertThat(sB).as("跨会话仍严格递增（雪花全局单调，非 per-session 自增）").isGreaterThan(sA);
    }
}
