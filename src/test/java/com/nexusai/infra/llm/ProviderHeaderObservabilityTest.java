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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[D · provider-hdr 2026-09-24] 「header 到底出去没有」必须在日志里 5 分钟可判读。</b>
 *
 * <h2>为什么要单独存在（本次排查 90% 的成本）</h2>
 * <p>{@link ProviderHeaderInjector#apply} 的计数日志是 <b>DEBUG</b>（运行级别 INFO ⇒ 实测
 * backend.log 0 行），而 {@code [前缀缓存探针] 出站} 只打 sessionId、<b>从不打 header 信息</b>
 * ⇒ 用户报「配了 {@code ${session_id}} 仍经常 400」时，<b>没有任何日志能证明 header 是否注入</b>。
 * 两条互补的可观测性落点：
 * <ol>
 *   <li><b>①探针行 {@code hdrs=N}</b>（逐请求口径）：本次请求真的会注入几条自定义 header
 *       —— 由 {@link ProviderHeaderInjector#injectableCount} 提供，与注入侧<b>同一谓词</b>；</li>
 *   <li><b>②空 Map 指纹 WARN</b>（指认成因）：{@code extraHeaders} 是「非 null 但空 Map」=
 *       「2 参便捷构造落 {@code Map.of()}」的指纹 ⇒ 一次 WARN 直接指向构造点，而不是让人逐行读码。</li>
 * </ol>
 *
 * <h2>本类用「缺陷形态 / 正常形态」两侧钉住判据的可分辨性</h2>
 * <ul>
 *   <li><b>有</b>：空 Map（缺陷）⇒ WARN + {@code hdrs=0}；</li>
 *   <li><b>无</b>：{@code null}（未配置 header，绝大多数请求的正常态）⇒ <b>静默</b>；非空 Map ⇒ 静默；</li>
 *   <li><b>量控</b>：缺陷形态每 {@value #WARN_EVERY} 次才复述一次（逐条 WARN 会刷屏 —— 本仓有前科）。</li>
 * </ul>
 *
 * <p>纯 JUnit：⛔ 无 Spring / ⛔ 无 {@code @SpringBootTest} / ⛔ 无真 API / 无真 DB
 * （只驱动两个静态方法 + logback appender）。
 */
@DisplayName("[D] header 注入可观测性：探针 hdrs=N + 空 Map 配置指纹 WARN")
class ProviderHeaderObservabilityTest {

    /** 与 ProviderHeaderInjector 的复述周期常量同值（那边是 private static，此处只做用例口径）。 */
    private static final int WARN_EVERY = 1000;

    private static final String SESSION_ID = "sess-obs-abc";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-24T10:00:00+08:00");

    /** 每个测试类实例一份唯一会话 id ⇒ 静态探针桶不与其它用例串味。 */
    private final String probeSid = "sess-obs-" + UUID.randomUUID();

    private ch.qos.logback.classic.Logger injectorLogger;
    private ch.qos.logback.classic.Logger providerLogger;
    private Level injectorLevel;
    private Level providerLevel;
    private ListAppender<ILoggingEvent> injectorAppender;
    private ListAppender<ILoggingEvent> providerAppender;

    @BeforeEach
    void attachAppenders() {
        injectorLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ProviderHeaderInjector.class);
        injectorLevel = injectorLogger.getLevel();
        injectorAppender = new ListAppender<>();
        injectorAppender.start();
        injectorLogger.addAppender(injectorAppender);
        injectorLogger.setLevel(Level.INFO);

        providerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenAiSdkProvider.class);
        providerLevel = providerLogger.getLevel();
        providerAppender = new ListAppender<>();
        providerAppender.start();
        providerLogger.addAppender(providerAppender);
        providerLogger.setLevel(Level.INFO);   // 探针是 INFO 级 ⇒ 必须放行（内部 log.isInfoEnabled 会短路）

        // 频控状态是 static（进程内），既有用例（ProviderHeaderInjectorTest）会调
        // apply(..., Map.of(), ...) 把「首次」标志吃掉 ⇒ 用例顺序会让断言假红 ⇒ 先复位。
        ProviderHeaderInjector.resetEmptyMapConfigWarnForTest();
    }

    @AfterEach
    void detachAppenders() {
        injectorLogger.detachAppender(injectorAppender);
        injectorAppender.stop();
        injectorLogger.setLevel(injectorLevel);
        providerLogger.detachAppender(providerAppender);
        providerAppender.stop();
        providerLogger.setLevel(providerLevel);
        ProviderHeaderInjector.resetEmptyMapConfigWarnForTest();
    }

    // ════════════════════════════════════════════════════════════════
    // ① 口径同源：injectableCount == apply 的 putHeader 次数
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① 口径同源：injectableCount 必须等于 apply 的 putHeader 次数（逐档矩阵，含禁止头/脏条目）")
    void injectableCount_matchesApplyPutHeaderCount() {
        ProviderHeaderInjector.installGateSource(() -> true);
        try {
            List<Map<String, String>> matrix = new ArrayList<>();
            matrix.add(null);
            matrix.add(Map.of());
            matrix.add(Map.of("X-A", "v"));
            matrix.add(Map.of("X-Session", DynamicHeaderExpander.SESSION_ID_TOKEN));
            matrix.add(Map.of("X-A", "v", "X-B", "w"));
            // 禁止头（凭据类）必须被两边同时排除
            matrix.add(new LinkedHashMap<>(Map.of("Authorization", "Bearer x", "X-Keep", "v")));
            // 脏数据：null 值条目（expandAll 丢弃）
            Map<String, String> dirty = new HashMap<>();
            dirty.put("X-NullValue", null);
            dirty.put("X-Ok", "v");
            matrix.add(dirty);

            for (Map<String, String> headers : matrix) {
                AtomicInteger puts = new AtomicInteger();
                BiConsumer<String, String> putHeader = (k, v) -> puts.incrementAndGet();
                ProviderHeaderInjector.apply(putHeader, headers, SESSION_ID);

                assertThat(ProviderHeaderInjector.injectableCount(headers))
                    .as("探针 hdrs= 与实际注入数必须同源（headers=%s）；"
                        + "两处判据一旦漂移，日志会指向错误的一格", headers)
                    .isEqualTo(puts.get());
            }

            // 非空性自证：矩阵里必须至少有一档真的注入 >0 条（否则上面的等值退化成 0==0 恒绿）
            assertThat(ProviderHeaderInjector.injectableCount(Map.of("X-A", "v", "X-B", "w")))
                .as("对照组：2 条合法 header 必须计为 2")
                .isEqualTo(2);
        } finally {
            ProviderHeaderInjector.installGateSource(null);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ② 空 Map 指纹 WARN：缺陷形态有 / 正常形态静默 / 量控
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("② 缺陷形态（空 Map）⇒ WARN 且指向 ProviderConfig 构造点；正常形态（null / 非空）⇒ 静默")
    void emptyMapConfig_isWarned_unconfiguredIsSilent() {
        // 缺陷形态：非 null 空 Map（= 2 参便捷构造指纹）
        ProviderHeaderInjector.apply((k, v) -> {}, Map.of(), SESSION_ID);
        List<String> warns = emptyMapConfigWarns();
        assertThat(warns)
            .as("空 Map 配置必须留痕（否则下次同类问题又只能靠读码推断）——这正是本次 90% 排查成本的来源")
            .hasSize(1);
        assertThat(warns.get(0))
            .as("WARN 必须给出**成因 + 下一步动作**（不是只报现象）：指向 2 参构造 / 3 参先例 / 护栏测试；"
                + "且 sessionId 必须原样落位（防「文案里的字面 {} 吞掉实参」的错位 —— 已实测踩过）")
            .contains("空 Map")
            .contains("ProviderConfig")
            .contains("ModelConfigResolver")
            .contains("ProviderConfigConstructionGuardTest")
            .contains("sessionId=" + SESSION_ID)
            .contains("本进程累计命中=1");

        // 正常形态 1：未配置 header（null）—— 绝大多数请求，⛔ 不该打
        ProviderHeaderInjector.apply((k, v) -> {}, null, SESSION_ID);
        // 正常形态 2：配了 header
        ProviderHeaderInjector.apply((k, v) -> {}, Map.of("X-A", "v"), SESSION_ID);
        assertThat(emptyMapConfigWarns())
            .as("null（未配置）与正常注入都不得打该 WARN —— 否则 INFO 级日志被刷屏（本仓有日志刷屏前科）")
            .hasSize(1);
    }

    @Test
    @DisplayName("② 量控：缺陷形态逐请求命中时只首次打 + 每 1000 次复述一次（不是每条 WARN）")
    void emptyMapConfig_warnIsRateLimitedPerProcess() {
        BiConsumer<String, String> noop = (k, v) -> {};
        for (int i = 0; i < 3; i++) {
            ProviderHeaderInjector.apply(noop, Map.of(), SESSION_ID);
        }
        assertThat(emptyMapConfigWarns())
            .as("前 3 次命中只应有 1 条 WARN（首次必打，其余静默）—— fork 家族每轮触发，逐条打会瞬间淹没日志")
            .hasSize(1);

        // 补到第 1000 次命中 ⇒ 复述一次（证明「还在发生」，且频次可算）
        for (int i = 3; i < WARN_EVERY; i++) {
            ProviderHeaderInjector.apply(noop, Map.of(), SESSION_ID);
        }
        assertThat(emptyMapConfigWarns())
            .as("第 %d 次命中必须复述一条（否则长跑进程里缺陷会彻底静默）", WARN_EVERY)
            .hasSize(2);
    }

    // ════════════════════════════════════════════════════════════════
    // ① 探针行：hdrs=N / hdrs=- ；12-param 主实现端到端
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① 探针行带 hdrs=N（真的注入几条）；未提供载荷时打 hdrs=-（如实标注，不编造 0）")
    void outboundProbeLine_carriesInjectedHeaderCount() {
        List<ChatMessageDto> msgs = List.of(meta("CTX", probeSid), user("m1", "问题", probeSid));
        ArrayNode tools = tools("d1");

        // 12-param 主实现（生产 4 条路径都走这个重载）：3 条注入
        OpenAiSdkProvider.buildRequestParams("m", "SYS", msgs, tools, null, false, null, null, null,
            false, true, 3);
        // 11-param 重载（未提供载荷）：应打 sentinel，而不是 0
        OpenAiSdkProvider.buildRequestParams("m", "SYS", msgs, tools, null, false, null, null, null,
            false, true);
        // 直驱探针（既有测试路径）：0 = 真的零 header（缺陷形态）
        OpenAiSdkProvider.logHeadProbeOutbound("SYS", msgs, msgs.size(), tools, true, true, 0);

        List<String> lines = probeLines();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0))
            .as("⭐ 本次请求真的会注入 3 条自定义 header ⇒ 探针行必须能读出 hdrs=3"
                + "（排查 header 丢失时看的是这个字段，不是 sessionId）")
            .contains("sessionId=" + probeSid).contains("hdrs=3");
        assertThat(lines.get(1))
            .as("11-param 重载（未提供载荷）必须打 hdrs=- （哨兵）—— ⛔ 不得写成 0，"
                + "否则「没测」会被读成「没发」")
            .contains("hdrs=-");
        assertThat(lines.get(2))
            .as("传 0（= 真的零 header）必须打 hdrs=0 —— 这是缺陷的可判据形态"
                + "（opencode 强制要 header ⇒ 这类请求必 400）")
            .contains("hdrs=0");
    }

    // ─────────────────────────────── 脚手架 ───────────────────────────────

    private List<String> injectorWarns() {
        return injectorAppender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    /**
     * 只取「空 Map 配置指纹」那一条 WARN。
     *
     * <p>为什么不能数全部 WARN：同一 logger 上还有<b>无关</b>的 WARN（如「gate 读源未安装」——
     * 前面用 {@code null} / 空 Map 短路时不会读 gate，一旦调用非空 Map 的 apply 就可能冒出来，
     * 且它是<b>进程内一次性</b>的，会让「只应有 1 条」这类断言随用例顺序漂移）。
     * 用文案里的固定片段筛出被测的那一条，副作用噪声不影响判据。
     */
    private List<String> emptyMapConfigWarns() {
        return injectorWarns().stream()
            .filter(m -> m.contains("成因是 config.extraHeaders"))
            .toList();
    }

    private List<String> probeLines() {
        return providerAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.startsWith("[前缀缓存探针] 出站"))
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
