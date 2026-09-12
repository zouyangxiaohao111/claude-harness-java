package com.nexusai.domain.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.core.util.IdUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [seq 跨进程基数] {@code MessageService.nextSeq/nextSeqBlock} 的 <b>per-session DB 基数 seed</b>
 * + <b>候选低于基数时的抬升（有声）</b> + <b>兜底分支去墙钟量纲</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：{@code messages.seq} 是会话内位置键，读侧一律
 * {@code ORDER BY seq}（{@code listRawForTranscript} / {@code listPageBySession}），且
 * {@code BoundaryReader.getMessagesAfterCompactBoundary} 按该序切片。修前的缺陷是：
 * <ul>
 *   <li>{@code lastSeq} 是<b>进程内</b> {@code AtomicLong}，重启归零，<b>从不与 DB 的 max(seq) 比较</b>
 *       —— 而姊妹字段 {@code created_at} 早有 DB 基数 seed（{@code seedMaxNanos}），这就是不对称的缺口；</li>
 *   <li>兜底分支回落 {@code nowMillis*1000}（≈1.79e15），与真实雪花号（≈2.098e18）差约 1170 倍
 *       （3 个数量级）。</li>
 * </ul>
 * 两者叠加「进程重启 + 墙钟回拨（NTP 回跳 / VM 快照恢复 / 手工改时间）」→ 新行 seq &lt; 旧行 →
 * {@code ORDER BY seq ASC} 把<b>最新写入的消息排到会话最前</b> → 被 boundary 切片当旧消息整段剪掉 →
 * 模型看不到刚发生的这一轮，且零日志。
 *
 * <p><b>不变量（本测试锁死）</b>：
 * <ol>
 *   <li>新取号<b>恒大于该会话 DB 已存在的 max(seq)</b>（即使 DB 里的号比本进程任何候选都大）；</li>
 *   <li>seed 是 <b>per-session</b> 且<b>每会话只查一次</b> DB（{@code ORDER BY seq DESC LIMIT 1} 取该会话最大号）；</li>
 *   <li>候选号低于 DB 基数时<b>必留 ERROR 一次</b>（静默错序 → 有声）并把号抬到 {@code max+1}；</li>
 *   <li>雪花取号异常的兜底<b>不含墙钟量纲</b>（值远小于 1e15）且仍单调。</li>
 * </ol>
 *
 * <p><b>RED 条件（mutation 自证）</b>：去掉 DB 基数 seed/clamp（回到纯进程内 {@code lastSeq}）→
 * ①③ 红（雪花号 ≈2.1e18 &lt; 测试里的 DB max 9e18）；seed 每次取号都查库 → ① 的 query 次数断言红；
 * 兜底改回 {@code nowMillis*1000} → ④ 的量纲断言红。
 */
@DisplayName("[seq 跨进程基数] DB max(seq) seed + 低于基数抬升（ERROR 有声）+ 兜底去墙钟量纲")
class MessageServiceSeqSeedTest {

    private static final String SESSION = "sess-seqseed";

    /** 模拟「DB 里已有的大号」：9e18 量级（远大于当前雪花号 ≈2.1e18，等价于回拨/未来时间基写入的行）。 */
    private static final long DB_BIG_SEQ = 9_000_000_000_000_000_000L;

    /** 雪花号量级上界（真实雪花 &lt; 5e18；兜底分支若带墙钟量纲会是 ≈1.79e15 → 本条断言可抓出）。 */
    private static final long WALL_CLOCK_MAGNITUDE = 1_000_000_000_000_000L;   // 1e15

    private MessageService service;
    private MessageMapper messageMapper;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        logger = (Logger) LoggerFactory.getLogger(MessageService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    /** mock DB：该会话最大 seq = 给定值（null → 空结果集，等价「该会话无行」）。 */
    private void givenDbMaxSeq(Long maxSeq) {
        if (maxSeq == null) {
            when(messageMapper.selectListByQuery(any())).thenReturn(List.of());
            return;
        }
        MessageRecord row = new MessageRecord();
        row.setId("msg-max");
        row.setSessionId(SESSION);
        row.setSeq(maxSeq);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(row));
    }

    private List<ILoggingEvent> errorLogs() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    @DisplayName("① 核心回归：DB 已有大 seq + 进程内 lastSeq 归零（重启）→ 新取号恒 > DB max（且 seed 只查一次）")
    void newSeqAlwaysAboveDbMaxAfterRestart() {
        // GIVEN: 进程刚重启（lastSeq=0），而 DB 里该会话已有 max(seq)=9e18
        //   （= 墙钟回拨/未来时间基写入的行；此时本进程的雪花候选 ≈2.1e18 比它小）
        givenDbMaxSeq(DB_BIG_SEQ);

        // WHEN
        long s1 = service.nextSeq(SESSION);
        long s2 = service.nextSeq(SESSION);

        // THEN: 新号恒大于 DB max —— 否则新行会排在旧行之前，被 boundary 切片剪掉（本批要根治的故障）
        assertThat(s1).as("重启后首个 seq 必须 > 该会话 DB max(seq)（修前：雪花号 2.1e18 < 9e18 → 错序）")
            .isGreaterThan(DB_BIG_SEQ);
        assertThat(s2).as("继续严格递增").isGreaterThan(s1);
        // seed 每会话只查一次（之后内存自增；key 命中不再回查）
        verify(messageMapper, times(1)).selectListByQuery(any());
    }

    @Test
    @DisplayName("② seed 是 per-session：按 session_id 查该会话最大号（ORDER BY seq DESC LIMIT 1），两会话各查一次")
    void seedIsPerSessionAndScopedBySessionId() {
        givenDbMaxSeq(DB_BIG_SEQ);

        service.nextSeq(SESSION);
        service.nextSeq(SESSION);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper, times(1)).selectListByQuery(captor.capture());
        String sql = captor.getValue().toSQL();
        assertThat(sql).as("基数 seed 必须按会话限定（不是全表 max）").contains("session_id");
        assertThat(sql).as("取的是该会话 seq 最大的一行").contains("ORDER BY seq DESC");
        assertThat(sql).as("只取一行（seed 查询开销 = 1 行）").containsIgnoringCase("limit");

        // 另一个会话 → 另一次 seed（per-session 槽位，而非进程内一次性全局 seed）
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of());
        service.nextSeq("sess-other");
        verify(messageMapper, times(2)).selectListByQuery(any());
    }

    @Test
    @DisplayName("③ 候选低于 DB 基数 → ERROR 一次（含 session/候选/基数）+ 抬到 max+1；同会话后续不再 ERROR")
    void candidateBelowFloor_isLiftedWithOneErrorLog() {
        givenDbMaxSeq(DB_BIG_SEQ);

        long s1 = service.nextSeq(SESSION);
        long s2 = service.nextSeq(SESSION);
        long s3 = service.nextSeq(SESSION);

        assertThat(s1).isGreaterThan(DB_BIG_SEQ);
        assertThat(s2).isGreaterThan(s1);
        assertThat(s3).isGreaterThan(s2);

        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors)
            .as("「候选号低于该会话 DB 基数」= 静默错序的前兆，必须 ERROR 有声（含会话与基数便于排查）")
            .hasSize(1);
        String msg = errors.get(0).getFormattedMessage();
        assertThat(msg).contains(SESSION).contains("DB max(seq)").contains(String.valueOf(DB_BIG_SEQ));

        // 第二轮取号时候选号（= lastSeq+1，已高于基数）不再触发 → 不刷屏（本仓有「持续异常刷屏」前科）
        assertThat(service.nextSeq(SESSION)).isGreaterThan(s3);
        assertThat(errorLogs()).as("同一会话只 ERROR 一次（后续同类事件降 DEBUG，防刷屏）").hasSize(1);
    }

    @Test
    @DisplayName("④ 雪花取号异常兜底：纯进程内序数（无墙钟量纲 1.79e15）+ 仍单调；有 DB 基数时被抬到基数之上")
    void snowflakeFailureFallbackIsInProcessMonotonicWithoutWallClockMagnitude() {
        // 先给「无 DB 行」的会话（基数 0）→ 观测兜底值本身的量纲与单调性
        givenDbMaxSeq(null);

        try (MockedStatic<IdUtil> idUtil = Mockito.mockStatic(IdUtil.class)) {
            idUtil.when(IdUtil::getSnowflakeNextId)
                .thenThrow(new IllegalStateException("clock moved backwards"));

            long f1 = service.nextSeq(SESSION);
            long f2 = service.nextSeq(SESSION);
            long f3 = service.nextSeq(SESSION);

            assertThat(f1).as("兜底值不得是墙钟量纲 nowMillis*1000（≈1.79e15）—— 旧实现跨重启必错序")
                .isLessThan(WALL_CLOCK_MAGNITUDE);
            assertThat(f2).as("兜底仍严格递增（纯进程内序数 lastSeq+1）").isGreaterThan(f1);
            assertThat(f3).isGreaterThan(f2);
            assertThat(errorLogs()).as("无基数（无 DB 行）时不构成「低于基数」→ 不得 ERROR").isEmpty();

            // 关键组合：另一个会话 DB 已有一个比任何雪花候选都大的号 → 雪花挂掉 + 兜底值很小，
            //   仍必须被抬到该基数之上（抬升责任在 clampSeqToDbFloor，而不是靠兜底值凑大小）
            givenDbMaxSeq(DB_BIG_SEQ);
            long g1 = service.nextSeq("sess-seqseed-floor");
            long g2 = service.nextSeq("sess-seqseed-floor");

            assertThat(g1).as("雪花挂了 + DB 有大号 → 仍恒 > DB max(seq)（否则回拨后新行排到旧行前面）")
                .isGreaterThan(DB_BIG_SEQ);
            assertThat(g2).isGreaterThan(g1);
            assertThat(errorLogs()).as("该会话首次低于基数 → 一条 ERROR 有声").hasSize(1);
        }
    }

    @Test
    @DisplayName("⑤ 无 DB 行（空结果集）→ 与旧实现一致：正数雪花号、严格递增、零 ERROR（不误报）")
    void emptySessionKeepsSnowflakeBehaviourWithoutFalseAlarm() {
        givenDbMaxSeq(null);

        long s1 = service.nextSeq(SESSION);
        long s2 = service.nextSeqBlock(SESSION, 3)[0];

        assertThat(s1).as("无行 → 基数 0 → 正常雪花号（正数、雪花量级）").isPositive();
        assertThat(s1).isGreaterThan(WALL_CLOCK_MAGNITUDE);
        assertThat(s2).isGreaterThan(s1);
        assertThat(errorLogs()).as("无基数不构成异常 → 不得有 ERROR（避免噪声掩盖真异常）").isEmpty();
        verify(messageMapper, times(1)).selectListByQuery(any());
    }

    @Test
    @DisplayName("⑥ 整块取号（compact 块）同样受基数约束：块首 > DB max，块内仍连续")
    void blockAllocationAlsoRespectsDbFloor() {
        givenDbMaxSeq(DB_BIG_SEQ);

        long[] block = service.nextSeqBlock(SESSION, 5);

        assertThat(block[0]).as("compact 整块也必须落在该会话已有行之后（否则 kept 重挂后位置序仍错）")
            .isGreaterThan(DB_BIG_SEQ);
        for (int i = 1; i < block.length; i++) {
            assertThat(block[i]).as("块内连续无空洞（tool_use/tool_result 配对不被打断）").isEqualTo(block[i - 1] + 1);
        }
    }
}
