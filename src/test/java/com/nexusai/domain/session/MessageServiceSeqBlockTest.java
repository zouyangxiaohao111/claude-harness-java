package com.nexusai.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM/compact 对齐 CC · seq 块] {@code MessageService.nextSeqBlock} = <b>一次 CAS 原子占位整块</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：compact 落库（{@code appendPostCompactMessages}）
 * 的整块（boundary → summary → messagesToKeep → attachments → hookResults）必须<b>连续</b>占据 DB 位置序。
 * 旧实现「按数组序逐个 {@code nextSeq}」= count 次独立 CAS；实时落库路径
 * （{@code ChatService.persistAppendedMessage}，在 {@code synchronized (ctx.lock)} 内）与本路径
 * （{@code @Transactional}）<b>两把锁不同源</b>，故并发 append 的号可以插进 boundary 与 summary 之间。
 * 下轮 run 以 DB(seq) 恢复 → DB 序 {@code [boundary][并发行][summary][kept...]} 与内存视图
 * {@code [boundary][summary][kept...][并发行]} 不一致 → 若并发行是 tool_result 而对应 tool_use 在 kept 段，
 * boundary 切片后 <b>tool_result 出现在 tool_use 之前</b>（provider 配对校验失败 / 被中断语义剥离）。
 *
 * <p><b>不变量（本测试锁死）</b>：
 * <ol>
 *   <li>{@code nextSeqBlock(n)} 返回 n 个<b>连续且严格递增</b>的号，且整块 &gt; 此前<b>所有</b>已分配号；</li>
 *   <li>并发单号取号（{@code nextSeq}）<b>绝不</b>落在块内部（块内无缺口）；</li>
 *   <li>{@code count <= 0} → 空数组且不消耗号。</li>
 * </ol>
 *
 * <p><b>RED 条件（mutation 自证）</b>：把 {@code nextSeqBlock} 的实现改回「循环 {@code nextSeq} 逐个取号」
 * → {@link #concurrentSingleAllocationsCannotInterruptBlock()} 的<b>块内连续性</b>断言必红
 * （并发号落进块内 → {@code block[i] != block[i-1]+1}）。改回「逐次取号」但去掉并发线程时它是绿的 ——
 * 即该断言恰好钉住「原子批量占位」这一条，而非泛泛的单调性。
 */
@DisplayName("[seq 块] nextSeqBlock = 一次 CAS 原子占位整块（并发取号不可插入块内）")
class MessageServiceSeqBlockTest {

    private static final String SESSION = "sess-seqblock";

    /** 无 mapper 依赖：nextSeq/nextSeqBlock 只用进程内 AtomicLong（+ 雪花候选）。 */
    private final MessageService service = new MessageService();

    @Test
    @DisplayName("nextSeqBlock(n) → n 个连续严格递增号，整块 > 此前所有已分配号，且后续单号仍在块之后")
    void blockIsContiguousAndAboveAllPreviouslyAllocated() {
        // GIVEN: 先单号取 64 次（模拟该会话此前的实时落库行）
        long maxBefore = 0L;
        for (int i = 0; i < 64; i++) {
            maxBefore = Math.max(maxBefore, service.nextSeq(SESSION));
        }

        // WHEN: 整块取 100 个
        long[] block = service.nextSeqBlock(SESSION, 100);

        // THEN ① 长度 + 整块 > 此前所有已分配号（否则与已有行 seq 重叠 → ORDER BY seq 位置序不稳）
        assertThat(block).hasSize(100);
        assertThat(block[0])
            .as("块首严格大于此前所有已分配号（含并发/其它会话的分配）")
            .isGreaterThan(maxBefore);
        // THEN ② 块内连续严格递增（这是「块不可被打断」的直接观测面）
        for (int i = 1; i < block.length; i++) {
            assertThat(block[i])
                .as("块内第 %d 个号必须 = 前一个 + 1（连续无空洞）", i)
                .isEqualTo(block[i - 1] + 1);
        }
        // THEN ③ 块末已被登记为 lastSeq → 之后取的单号必在块之后（不会复用块内号）
        assertThat(service.nextSeq(SESSION))
            .as("块取完后单号取号仍在块末之后（整块占位对后续分配可见）")
            .isGreaterThan(block[block.length - 1]);
    }

    @Test
    @DisplayName("count<=0 → 空数组且不消耗号（不产生空洞，也不推进 lastSeq）")
    void nonPositiveCountReturnsEmptyWithoutConsuming() {
        assertThat(service.nextSeqBlock(SESSION, 0)).isEmpty();
        assertThat(service.nextSeqBlock(SESSION, -3)).isEmpty();

        long single = service.nextSeq(SESSION);
        long[] block = service.nextSeqBlock(SESSION, 2);
        assertThat(block[0])
            .as("空块调用不推进 lastSeq（否则每次空调用都浪费/打乱号段）")
            .isGreaterThan(single);
        assertThat(block[1]).isEqualTo(block[0] + 1);
    }

    @Test
    @DisplayName("count=1 与 nextSeq 同语义（同一 CAS 序列化点）：交叉取号严格递增、不重叠")
    void countOneSharesSerializationPointWithNextSeq() {
        long a = service.nextSeqBlock(SESSION, 1)[0];
        long b = service.nextSeq(SESSION);
        long c = service.nextSeqBlock(SESSION, 1)[0];
        assertThat(b).as("nextSeq 已改用 nextSeqBlock(1) → 与块取号共用 lastSeq 序列化点").isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }

    /**
     * <b>本任务核心 RED 用例</b>：并发单号取号（模拟实时落库 {@code persistAppendedMessage} 的
     * {@code nextSeq}）持续轰炸时，{@code nextSeqBlock(100)} 拿到的 100 个号必须两两连续。
     *
     * <p><b>WHY 它会红</b>：若实现是「循环逐个取号」，则块的 100 个号来自 100 次独立 CAS，
     * 并发线程的 CAS 会落进这些 CAS 之间 → 其号 &gt; 块内第 i 个且 &lt; 第 i+1 个 → 第 i+1 个号
     * 被顶到「并发号 + 1」→ {@code block[i+1] != block[i]+1} → 断言红。
     * 正确实现下块由<b>单次</b> CAS 占位，并发号只能在块之前/之后 → 断言恒绿（无 flaky）。
     */
    @Test
    @DisplayName("RED 核心：并发单号取号无法插入 nextSeqBlock(100) 块内部（连续无缺口）")
    void concurrentSingleAllocationsCannotInterruptBlock() throws Exception {
        final int workers = 3;
        final int rounds = 200;
        final int blockSize = 100;
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        service.nextSeq(SESSION); // = 实时落库 persistAppendedMessage 的取号
                    }
                } catch (Throwable e) {
                    workerError.set(e);
                }
            }, "seq-hammer-" + w);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        try {
            quietMessageServiceDebug(() -> {
                for (int round = 0; round < rounds; round++) {
                    long[] block = service.nextSeqBlock(SESSION, blockSize);
                    for (int i = 1; i < block.length; i++) {
                        assertThat(block[i])
                            .as("round=%d 块内第 %d 个号必须 = 前一个 + 1 —— 出现缺口即「并发号落进 compact 块内」，"
                                    + "下轮 DB(seq) 恢复序会与内存视图错位（tool_result 可能先于 tool_use）",
                                round, i)
                            .isEqualTo(block[i - 1] + 1);
                    }
                }
            });
        } finally {
            stop.set(true);
            for (Thread t : threads) {
                t.join(5_000);
            }
        }
        assertThat(workerError.get()).as("并发取号线程自身不得抛错（取号路径须线程安全）").isNull();
    }

    /**
     * 并发用例期间把 {@code MessageService} 的日志抬到 WARN，用例后恢复。
     *
     * <p><b>WHY</b>：{@code src/test/resources/logback-test.xml} 给 {@code com.nexusai} 开了 DEBUG，
     * 而 hammer 线程以极高频率调 {@code nextSeq} → 每条一条 DEBUG 日志会变成 I/O 瓶颈，主线程被日志写
     * 饿死（实测 300 行用例跑数分钟、日志涨到 GB 级），<b>与断言正确性无关，纯噪声</b>。
     * 这里只影响本类执行窗口（恢复为继承级别），不修改全局测试配置。
     */
    private static void quietMessageServiceDebug(Runnable body) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MessageService.class);
        ch.qos.logback.classic.Level prev = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            body.run();
        } finally {
            logger.setLevel(prev); // null = 恢复继承 logback-test.xml 的 com.nexusai=DEBUG
        }
    }
}
