package com.nexusai.domain.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [seq NULL 兜底 · 读侧] {@code listRawForTranscript} / {@code listPageBySession} 对 seq 为 NULL 的行的
 * <b>排序处置（不冒充真实位置）</b> + <b>ERROR 告警（静默变有声）</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：{@code seq} 是 {@code INTEGER NULL}（V70），
 * 写侧恒写非 NULL，但结构上没有任何约束拦住 NULL（根因入口由 V71 触发器封堵）。NULL 的实测后果是
 * <b>同一份数据两条通道顺序相反</b>：{@code ORDER BY seq ASC} 里 NULL 排<b>最前</b>（先于第一条真实
 * 消息 → 被送进模型上下文顶部，还被 boundary 切片当旧消息静默剪掉）；{@code ORDER BY seq DESC ...
 * LIMIT pageSize+1} 里 NULL 排<b>最后</b>（被挤出尾页 → 前端分页通道永远看不到它）。而全仓此前
 * <b>无任何 NULL 告警</b> —— 坏数据只会表现为「顺序莫名其妙」。
 *
 * <p><b>本测试锁死</b>：
 * <ol>
 *   <li><b>front-of-flex（最大风险点）</b>：MyBatis-Flex 的 {@code orderByUnSafely} 把
 *       {@code seq <方向> NULLS LAST} <b>原样</b>发出 —— 不包装成列名、不转义、不追加 {@code ASC}。
 *       实测生成 SQL 形如 {@code ... ORDER BY seq ASC NULLS LAST}。任何包装都会让 SQLite 报语法错
 *       或让排序失效，故这条断言是「排序片段能被生产栈正确发出」的唯一守卫；</li>
 *   <li>ASC 通道用 {@link MessageService#SEQ_ASC_NULLS_LAST_ORDER}、DESC 通道用
 *       {@link MessageService#SEQ_DESC_NULLS_LAST_ORDER}，且 DESC 侧<b>不得</b>用 {@code NULLS FIRST}
 *       （那会把 NULL 顶成「最新」）；</li>
 *   <li>结果集里出现 NULL seq → <b>ERROR 一条</b>（含会话 / 通道 / 条数 / 样本 id）。</li>
 * </ol>
 *
 * <p><b>RED 条件（变异验证，两条都实测过）</b>：把常量改回 {@code "seq IS NULL, seq ASC"}（表达式前置键）
 * → ①② 红（片段不匹配）；把 {@code NULLS LAST} 去掉（改裸 {@code "seq ASC"}）→ ①② 红。
 * 删掉 {@code warnNullSeqIfAny} 调用 → ③ 红。
 */
@DisplayName("[seq NULL 兜底·读侧] 排序不冒充真实位置 + ERROR 有声")
class MessageServiceNullSeqReadTest {

    private static final String SESSION = "sess-nullseq";

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
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

    /** DB 行（seq=null 模拟「位置键未落」的存量坏行）。 */
    private static MessageRecord row(String id, Long seq) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(SESSION);
        m.setRole("user");
        m.setContent("内容-" + id);
        m.setCreatedAt("2026-09-11T10:00:00+08:00");
        m.setSeq(seq);
        return m;
    }

    private List<ILoggingEvent> errorLogs() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    private QueryWrapper captureListQuery() {
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).selectListByQuery(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("listRawForTranscript（ASC）：flex 原样发出 `seq ASC NULLS LAST`（NULL 推末尾）；有 NULL 行 → ERROR 一条")
    void listRawForTranscript_ordersNullsLastAndLogsError() {
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(row("m1", 10L), row("m-dirty", null), row("m2", 11L)));

        service.listRawForTranscript(SESSION);

        String sql = captureListQuery().toSQL();
        // 【最大风险点】flex 必须原样发出：不得包装成列名/转义/追加方向。任一处改写都会让 SQLite 语法错或排序失效。
        assertThat(sql)
            .as("flex orderByUnSafely 必须原样发出 `seq ASC NULLS LAST`（不包装/不转义/不追加方向）")
            .contains("ORDER BY seq ASC NULLS LAST");
        // 防「旁路叠加」：若调用方又叠了 orderBy("seq", true)，会变成 `... NULLS LAST, seq ASC`（语义冗余且说明有人没读文法）
        assertThat(sql)
            .as("方向已含在片段内，不得再叠加第二个排序键 —— 出现 `NULLS LAST,` 即表示有人把 orderBy(\"seq\", ..) 加回来了")
            .doesNotContain("NULLS LAST,");
        // 防回归：旧表达式前置键必须彻底消失（它是废索引的元凶）
        assertThat(sql)
            .as("旧写法 `seq IS NULL` 是表达式 → SQLite 必做全量临时排序，本批已淘汰，不得复活")
            .doesNotContain("seq IS NULL");

        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors).as("NULL seq = 数据异常，必须 ERROR 有声（静默变有声是这条的核心价值）").hasSize(1);
        String msg = errors.get(0).getFormattedMessage();
        assertThat(msg).contains("listRawForTranscript").contains(SESSION).contains("m-dirty");
    }

    @Test
    @DisplayName("listPageBySession（DESC 尾页）：原样发出 `seq DESC NULLS LAST`（而非 NULLS FIRST）→ NULL 不冒充「最新」")
    void listPageBySession_descKeepsNullsAwayFromNewest() {
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(row("m2", 11L), row("m1", 10L), row("m-dirty", null)));

        service.listPageBySession(SESSION, null, 50);

        String sql = captureListQuery().toSQL();
        assertThat(sql)
            .as("DESC 侧必须原样发出 `seq DESC NULLS LAST` → NULL 落在 DESC 结果末尾（= 最旧那头）")
            .contains("ORDER BY seq DESC NULLS LAST");
        assertThat(sql)
            .as("绝不能用 NULLS FIRST：那会把 NULL 顶到 DESC 结果最前 = 位置未知的行冒充「最新」挤进尾页")
            .doesNotContain("NULLS FIRST");
        assertThat(sql).as("分页仍按 seq 降序取尾页").contains("seq DESC");
        assertThat(errorLogs()).as("分页通道同样必须对 NULL 行 ERROR 有声").hasSize(1);
    }

    @Test
    @DisplayName("无 NULL 行 → 零 ERROR（避免把正常读取噪音化成告警，掩盖真异常）")
    void noNullRows_noErrorLog() {
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(row("m1", 10L), row("m2", 11L)));

        service.listRawForTranscript(SESSION);
        service.listPageBySession(SESSION, null, 50);

        assertThat(errorLogs()).isEmpty();
    }
}
