package com.nexusai.application.chat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.domain.session.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code ChatService.nextSeq} 的 <b>-1 退化出口</b>行为锁（不是正确性锁）。
 *
 * <p><b>被锁定的现状</b>：{@code ChatService.java:2427-2434} —— 当
 * {@code messageService == null}（非 Spring 上下文 / plain JUnit 单测，字段为
 * {@code @Autowired(required=false)}）时，{@code nextSeq} 先 {@code log.error} 再返回 {@code -1L}。
 *
 * <p><b>WHY 需要显式锁定（CLAUDE.md 规则 9）</b>：{@code -1L} 与雪花号（恒 &gt; 0）混排在
 * {@code ORDER BY seq ASC} 下，会让该行排到<b>会话最前</b> —— 位置序被破坏。这是<b>已知的、故意的</b>
 * 退化出口：生产 Spring 上下文恒注入 {@code messageService}（{@code @Autowired(required=false)} 的
 * false 只在无上下文时成立），故生产路径不可达；但「不可达」这一事实此前只存在于注释里，
 * 没有测试把它变成可执行的契约。本测试把该出口变成显式的：返回值恒 {@code -1L} + 必留 ERROR 日志。
 *
 * <p><b>⚠️ 这是行为锁，不是正确性锁</b>：如果将来有人要「修掉」这个退化（例如改为抛异常、
 * 或分配一个安全基数而不是 -1），<b>本测试会变红，且这个红是预期的</b> —— 请连同本测试一起更新，
 * 不要为了变绿把断言放宽。
 *
 * <p><b>RED 条件（硬指标）</b>：
 * <ul>
 *   <li>把 {@code ChatService.nextSeq} 中 {@code messageService == null} 分支的
 *       {@code return -1L;} 改成其它值（如 {@code 0L} / 分配安全基数）→
 *       {@link #messageServiceMissing_returnsMinusOne()} 红。</li>
 *   <li>删掉该分支的 {@code log.error(...)} → {@link #messageServiceMissing_logsError()} 红。</li>
 *   <li>把 {@code messageService != null} 分支改成不委托（例如恒返回常量）→
 *       {@link #messageServicePresent_delegatesToSnowflake()} 红。</li>
 * </ul>
 *
 * <p><b>不可测部分（如实声明）</b>：「{@code -1} 在 SQLite {@code ORDER BY seq ASC} 下排最前」这一
 * 排序后果需要真实 DB 才能端到端断言 —— 本测试<b>不</b>伪造它（无假 DB 断言），只在 JavaDoc 中说明；
 * 排序语义由 {@code MessageService.listRawForTranscript}/{@code listPageBySession} 的 {@code ORDER BY seq}
 * 契约承载（见该两方法 JavaDoc）。
 */
@DisplayName("[seq 排序键] ChatService.nextSeq -1 退化出口（行为锁）")
class ChatServiceNextSeqDegradationTest {

    private static final String SESSION = "sess-1";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    private void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(ChatService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    @DisplayName("messageService 未注入 → nextSeq 返回 -1L（已知退化出口 · 行为锁）")
    void messageServiceMissing_returnsMinusOne() {
        // RED: 把 ChatService.java:2431 的 `return -1L;` 改成别的值 → 本断言红。
        ChatService service = new ChatService();   // messageService 留 null（非 Spring 直构）

        Long seq = ReflectionTestUtils.invokeMethod(service, "nextSeq", SESSION);

        assertThat(seq)
            .as("messageService 未注入时 nextSeq 恒 -1L（已知退化出口；-1 在 ORDER BY seq ASC 下排最前，"
                + "仅非生产/单测路径可达 —— 生产 Spring 恒注入）")
            .isEqualTo(-1L);
    }

    @Test
    @DisplayName("messageService 未注入 → 退化必留 ERROR 日志（fail loud，不许静默降级）")
    void messageServiceMissing_logsError() {
        // RED: 删掉 ChatService.java:2429 的 log.error(...) → 本断言红。
        attachLogAppender();
        ChatService service = new ChatService();   // messageService 留 null

        ReflectionTestUtils.invokeMethod(service, "nextSeq", SESSION);

        assertThat(appender.list)
            .as("seq 退化到 -1 必须 ERROR 可见（静默降级会让顺序错乱无从排查）")
            .anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("seq=-1")
                && e.getFormattedMessage().contains(SESSION));
    }

    @Test
    @DisplayName("messageService 已注入 → nextSeq 委托雪花取号（正数 · 恒增），不走退化出口")
    void messageServicePresent_delegatesToSnowflake() {
        // WHY: 对照断言 —— 证明 -1 只在 messageService 缺失时出现；正常装配下 seq 恒为正且严格递增。
        //   RED: 把 nextSeq 改成不委托 messageService（恒返回常量）→ 本断言红。
        ChatService service = new ChatService();
        MessageService messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", mock(
            com.nexusai.repository.session.mapper.MessageMapper.class));
        ReflectionTestUtils.setField(service, "messageService", messageService);

        Long first = ReflectionTestUtils.invokeMethod(service, "nextSeq", SESSION);
        Long second = ReflectionTestUtils.invokeMethod(service, "nextSeq", SESSION);

        assertThat(first).as("正常装配下 seq 为雪花号（正数）").isPositive();
        assertThat(second).as("调用顺序 = seq 顺序（严格递增）").isGreaterThan(first);
    }
}
