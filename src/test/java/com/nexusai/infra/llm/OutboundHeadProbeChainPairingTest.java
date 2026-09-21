package com.nexusai.infra.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[探针可判读化] 流式主链的出站行 ↔ 响应行必须严格 1:1 可对排（侧查询不得顶掉主链序号）。</b>
 *
 * <h2>被修的缺陷（可判读性，不是行为）</h2>
 * <p>出站探针在<b>四条</b>发送路径都打（stream / chatWithRaw / chatWithOptions /
 * chatWithOptionsMessage —— 落点都是 {@code buildRequestParams}），而响应探针<b>只在流式主链</b>
 * （{@code doStream}）打，二者原先共用同一会话级 {@code HEAD_PROBE_STATE} 桶 ⇒ 只要该会话发过
 * 一次<b>侧查询</b>（标题 / 分类器 / 解释器），主链桶的 {@code requestSeq} 就被顶掉一格：
 * 日志上「主链出站 turn」<b>跳号</b>，且无法判断某条响应属于哪一次出站请求
 * ⇒ 步骤 1 的核心用途（跨 run 逐条对排、指认哪个字节在漂）<b>结构上不可达</b>。
 *
 * <h2>判据（本类钉住的三条，缺一即退化）</h2>
 * <ol>
 *   <li><b>主链连号</b>：同一 sessionId 下 {@code chain=stream} 的 turn 必须是 1,2,…
 *       ——<u>即使中间夹了两次侧查询</u>（这是本类最能分辨的那一条：共用桶时第二次主链会是 turn=4）。</li>
 *   <li><b>侧查询独立桶</b>：{@code chain=side} 行 turn 自成一列（本用例 1,2），不占主链号。</li>
 *   <li><b>响应可对排</b>：响应行带 {@code turn}，等于最近一次<b>主链</b>出站的 turn
 *       ⇒ 「主链出站行 ↔ 响应行」按 {@code sessionId + turn} 逐条 1:1。</li>
 * </ol>
 * <p>另断言「响应行的『上一条 input』基线不被侧查询踩脏」（侧查询只走出站行、不写基线）。
 *
 * <h2>反向实验配方（分辨力自证）</h2>
 * <p>把 {@code headProbeStateLocked(sessionId, streamingMainChain)} 的键改回只用 sessionId
 * （＝撤销本修复）⇒ 判据 1 的 {@code turn=2} 断言变红（实际会是 turn=4）；把响应行的
 * {@code turn} 去掉 ⇒ 判据 3 变红。
 *
 * <p>纯 JUnit：⛔ 无 Spring / ⛔ 无 {@code @SpringBootTest} / ⛔ 无真 API / ⛔ 无真 DB
 * （只驱动静态探针方法 + logback appender；探针方法对 wire 零影响）。
 */
@DisplayName("[探针可判读化] 主链出站↔响应严格 1:1（侧查询走独立桶，不顶号）")
class OutboundHeadProbeChainPairingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-20T10:00:00+08:00");

    /** 每个测试类实例一份唯一会话 id ⇒ 静态探针桶不与其它用例串味。 */
    private final String sid = "sess-probe-" + UUID.randomUUID();

    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachInfoAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenAiSdkProvider.class);
        originalLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // 探针本身是 INFO 级 ⇒ 必须放行 INFO 才可观测（探针内部 log.isInfoEnabled() 会短路）
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("同一会话内夹两次侧查询 ⇒ 主链 turn 仍连号，响应行 turn 与末次主链出站一致")
    void sideQueries_doNotConsumeStreamChainTurn() {
        List<ChatMessageDto> msgs = List.of(meta("CTX", sid), user("m1", "问题", sid));
        ArrayNode tools = tools("d1");

        // 主链 #1 → turn=1
        outbound(msgs, tools, true);
        // 侧查询 #1 / #2 → side 桶 turn=1 / turn=2（⛔ 不得占主链号）
        outbound(msgs, tools, false);
        outbound(msgs, tools, false);
        // 主链 #2 → turn=2（共用桶时这里会是 turn=4 = 本修复的核心判据）
        outbound(msgs, tools, true);
        // 主链响应（唯一会打响应探针的路径）→ turn 必须 = 2（与末次主链出站对排）
        OpenAiSdkProvider.logHeadProbeResponse(sid, 1_000L, 900L, 0L);
        // 第二次响应：证明「上一条 input」基线只由主链响应维护（侧查询不写基线）
        OpenAiSdkProvider.logHeadProbeResponse(sid, 2_000L, 1_800L, 0L);

        List<String> streamLines = outboundLines().stream()
            .filter(l -> l.contains("chain=stream")).toList();
        List<String> sideLines = outboundLines().stream()
            .filter(l -> l.contains("chain=side")).toList();

        assertThat(streamLines).as("主链出站行应有 2 条（侧查询不得混进来）").hasSize(2);
        assertThat(streamLines.get(0))
            .as("主链出站行必须带 sessionId + chain 标记 + turn")
            .contains("sessionId=" + sid).contains("chain=stream").contains("turn=1");
        assertThat(streamLines.get(1))
            .as("⭐ 主链第二个出站必须是 turn=2 —— 侧查询若顶号这里会是 turn=4（本修复的可分辨判据）")
            .contains("turn=2");

        assertThat(sideLines).as("侧查询出站行应有 2 条（chain=side）").hasSize(2);
        assertThat(sideLines.get(0)).as("侧查询桶自成一列（turn 从 1 起，与主链互不顶号）")
            .contains("chain=side").contains("turn=1");
        assertThat(sideLines.get(1)).contains("chain=side").contains("turn=2");

        List<String> respLines = lines("[前缀缓存探针] 响应");
        assertThat(respLines).hasSize(2);
        assertThat(respLines.get(0))
            .as("响应行必须带 turn（= 最近一次主链出站的 turn）⇒ 出站↔响应可按 sessionId+turn 逐条对排")
            .contains("sessionId=" + sid).contains("turn=2");
        assertThat(respLines.get(0))
            .as("首条响应无前置基线 ⇒ 上一条input=-1（如实标注，不编造）")
            .contains("上一条input=-1");
        assertThat(respLines.get(1))
            .as("⭐ 第二条响应的基线必须来自上一条主链响应（1000）⇒ 侧查询没有踩脏基线")
            .contains("上一条input=1000");
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /** 驱动一次出站探针（与生产同口径：sessionId 由 outbound 里解析）。 */
    private static void outbound(List<ChatMessageDto> msgs, ArrayNode tools, boolean streamingMainChain) {
        OpenAiSdkProvider.logHeadProbeOutbound("SYS\n\nPrimary working directory: /w", msgs, msgs.size(),
            tools, true, streamingMainChain);
    }

    /** 已捕获的出站探针行。 */
    private List<String> outboundLines() {
        return lines("[前缀缓存探针] 出站");
    }

    private List<String> lines(String marker) {
        return appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.startsWith(marker))
            .toList();
    }

    private static ChatMessageDto meta(String content, String sessionId) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.user, "system",
            content, null, List.of(), null, null, null,
            "刚刚", T, null, null,
            null, List.of(), List.of(), null, true);
    }

    private static ChatMessageDto user(String id, String content, String sessionId) {
        return new ChatMessageDto(
            id, sessionId, Role.user, "u",
            content, null, List.of(), null, null, null,
            "刚刚", T, null, null,
            null, List.of(), List.of(), null, false);
    }

    private static ArrayNode tools(String description) {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode wrapper = arr.addObject();
        wrapper.put("type", "function");
        ObjectNode fn = wrapper.putObject("function");
        fn.put("name", "Bash");
        fn.put("description", description);
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        return arr;
    }
}
