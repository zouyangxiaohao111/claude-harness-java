package com.nexusai.application.agent.prompt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「无会话标识 ⇒ 走广播清」分支必须打醒目跨会话隐患 WARN</b>（可判绿的留痕判据）。
 *
 * <h2>WHY（为什么这条 WARN 值得一条测试）</h2>
 * <p>本仓 provider 已改为<b>跨 run 长期存活</b>（会话级 store），而
 * {@link SystemPromptInjection#clearUserOnlyProviderCaches()} 是按「<b>全部已注册 provider</b>」清的。
 * 一旦某调用方漏传 sessionId，就会「<b>一个会话压缩 ⇒ 打掉全部会话的 claudeMd 头部</b>」——
 * 即跨会话串味，且正是本批要消除的每轮重建类浪费。现状生产调用方<b>均显式传 sessionId</b>
 * ⇒ 该分支生产不可达（latent），但「潜在」不等于「可以不留痕」：漏传是<b>将来</b>最容易发生的
 * 回归形态，且发生时<b>没有一行日志</b>指向它 ⇒ 故障不可归因。
 *
 * <h2>判据（本类逐条钉住）</h2>
 * <ol>
 *   <li><b>有 WARN</b>：无会话标识分支必须发出含
 *       {@link SystemPromptInjection#NO_SESSION_BROADCAST_WARNING} 的 WARN
 *       ——用「只放行 WARN+」的 logback appender 捕获（生产 root=INFO，debug 不算留痕）；
 *       两个调用点各一条用例（{@link PostCompactCleanup} / {@link ToolRegistrationConfig}）。</li>
 *   <li><b>广播真的执行了</b>：同用例内注册观察钩子，计数必须为 1 ⇒ 排除「静默跳过也算过」
 *       （若有人把广播改成 no-op 而不改本条 WARN 文案，本条即红）。</li>
 *   <li><b>对照（不带 sessionId 的另一侧）</b>：显式传 sessionId 时必须<b>不</b>打这条 WARN，
 *       且走的是按会话精确清（{@link SessionPromptCacheRegistry} 的 INFO 行）——
 *       这钉死「该 WARN 只属无会话分支」，防被改成无条件告警。</li>
 *   <li><b>文案契约</b>：常量本身必须含关键短语 ⇒ 杀掉「常量被改成空串、捕获断言仍全绿」的
 *       退化失效模式（规则 12：不许假绿）。</li>
 * </ol>
 *
 * <h2>反向实验配方（本类可自证分辨力）</h2>
 * <p>① 删掉任一调用点的 {@code log.warn(NO_SESSION_BROADCAST_WARNING, …)} ⇒ 对应用例红；
 * ② 把 WARN 提到分支之外（无条件打）⇒ 对照用例红；
 * ③ 把 {@code NO_SESSION_BROADCAST_WARNING} 改成空串 ⇒ 文案契约用例红。
 *
 * <p>纯 JUnit：⛔ 无 Spring / ⛔ 无 {@code @SpringBootTest} / ⛔ 无真 API / ⛔ 无真 DB。
 * 本类在 {@code prompt} 包内 ⇔ 可访问包级可见的 {@code registerUserCacheClearHook}
 * （与 {@code PostCompactCleanupCcContractTest} / {@code CacheInvalidationTest} 同机制）。
 */
@DisplayName("[跨会话隐患留痕] 无会话标识走广播清 ⇒ 必须打醒目 WARN（两调用点 + 对照）")
class SystemPromptInjectionNoSessionBroadcastWarnTest {

    private static final String SID = "sess-broadcast-warn-" + java.util.UUID.randomUUID();

    @AfterEach
    void resetStaticState() {
        // 会话级 store 静态表跨用例归零（evict → close → provider 回调成对注销）
        SessionPromptCacheRegistry.resetForTest();
        // PostCompactCleanup 静态协作器复位（避免上一个用例 wire 过的 spy 残留）
        new PostCompactCleanup(null, null);
    }

    // ════════════════ 调用点 ①：PostCompactCleanup 集合B 无会话分支 ════════════════

    @Test
    @DisplayName("PostCompactCleanup 无会话标识 ⇒ 广播清 + 跨会话隐患 WARN（可被 WARN+ 探针捕获）")
    void noSessionId_postCompactCleanup_emitsCrossSessionWarning() {
        ListAppender<ILoggingEvent> appender = attach(PostCompactCleanup.class, Level.WARN);
        AtomicInteger broadcasts = new AtomicInteger();
        Runnable hook = broadcasts::incrementAndGet;
        SystemPromptInjection.registerUserCacheClearHook(hook);
        try {
            // 无参口径入口（querySource 有、sessionId 无）＝ 历史无会话调用方语义
            PostCompactCleanup.runPostCompactCleanup("repl_main_thread:test");
        } finally {
            SystemPromptInjection.unregisterUserCacheClearHook(hook);
            detach(appender, PostCompactCleanup.class);
        }

        assertThat(broadcasts.get())
            .as("前置：广播清真的执行了（清到 1 个已注册 provider）——排除「静默跳过也算过」")
            .isEqualTo(1);
        assertThat(warnTexts(appender))
            .as("无会话标识 ⇒ 必须捕获跨会话隐患 WARN（文案单点 = NO_SESSION_BROADCAST_WARNING）")
            .anyMatch(m -> m.contains(SystemPromptInjection.NO_SESSION_BROADCAST_WARNING));
    }

    // ════════════════ 调用点 ②：ToolRegistrationConfig clearUserContextCache 无会话分支 ════════════════

    @Test
    @DisplayName("ToolRegistrationConfig clearUserContextCache 无会话标识 ⇒ 广播清 + 跨会话隐患 WARN")
    void noSessionId_toolRegistrationClearUserContext_emitsCrossSessionWarning() throws Exception {
        ListAppender<ILoggingEvent> appender = attach(ToolRegistrationConfig.class, Level.WARN);
        AtomicInteger broadcasts = new AtomicInteger();
        Runnable hook = broadcasts::incrementAndGet;
        SystemPromptInjection.registerUserCacheClearHook(hook);
        try {
            // 直取生产接线（package-private 私有方法经反射取实例，绕开 17 参 ctx 构造噪音）
            Method m = ToolRegistrationConfig.class
                .getDeclaredMethod("clearUserContextCacheRunnable", String.class);
            m.setAccessible(true);
            Runnable clearUserContextCache =
                (Runnable) m.invoke(new ToolRegistrationConfig(), (Object) null);   // sessionId = null
            clearUserContextCache.run();
        } finally {
            SystemPromptInjection.unregisterUserCacheClearHook(hook);
            detach(appender, ToolRegistrationConfig.class);
        }

        assertThat(broadcasts.get())
            .as("前置：clearUserContextCache 无会话分支真的走了广播（清到 1 个已注册 provider）")
            .isEqualTo(1);
        assertThat(warnTexts(appender))
            .as("无会话标识 ⇒ 必须捕获跨会话隐患 WARN")
            .anyMatch(m -> m.contains(SystemPromptInjection.NO_SESSION_BROADCAST_WARNING));
    }

    // ════════════════ 对照：显式 sessionId ⇒ 不走广播、不打该 WARN ════════════════

    @Test
    @DisplayName("对照：显式传 sessionId ⇒ 按会话精确清（INFO 留痕），不得打广播 WARN")
    void withSessionId_takesPerSessionPath_noBroadcastWarning() {
        ListAppender<ILoggingEvent> ccAppender = attach(PostCompactCleanup.class, Level.WARN);
        ListAppender<ILoggingEvent> regAppender = attach(SessionPromptCacheRegistry.class, Level.INFO);
        AtomicInteger broadcasts = new AtomicInteger();
        Runnable hook = broadcasts::incrementAndGet;
        SystemPromptInjection.registerUserCacheClearHook(hook);
        try {
            // 该会话先建 store（否则 clearPromptCaches 未命中 ⇒ 走「该会话未建过 store」分支）
            SessionPromptCacheRegistry.forSession(SID, "2026-09-20");
            PostCompactCleanup.runPostCompactCleanup("repl_main_thread:test", SID);
        } finally {
            SystemPromptInjection.unregisterUserCacheClearHook(hook);
            detach(regAppender, SessionPromptCacheRegistry.class);
            detach(ccAppender, PostCompactCleanup.class);
        }

        assertThat(broadcasts.get())
            .as("对照：显式会话标识 ⇒ 绝不得走广播清（否则就是跨会话串味）")
            .isZero();
        assertThat(warnTexts(ccAppender))
            .as("对照：显式会话标识 ⇒ 不得打广播 WARN（该 WARN 只属无会话分支）")
            .noneMatch(m -> m.contains(SystemPromptInjection.NO_SESSION_BROADCAST_WARNING));
        assertThat(infoTexts(regAppender))
            .as("对照：必须真走「按集合清会话缓存」这条 INFO 留痕（不是什么都没发生）")
            .anyMatch(m -> m.contains("按集合清会话缓存") && m.contains(SID));
    }

    // ════════════════ 文案契约：防「常量被改空、捕获断言仍全绿」 ════════════════

    @Test
    @DisplayName("WARN 文案契约：必须点明跨会话隐患 + 正确调用方式（⛔ 防退化常量假绿）")
    void warningText_containsHazardAndCorrectCall() {
        assertThat(SystemPromptInjection.NO_SESSION_BROADCAST_WARNING)
            .as("必须点明「无会话标识」这一触发条件")
            .contains("无会话标识")
            .as("必须点明影响面 = 全部会话")
            .contains("全部会话")
            .as("必须点明被波及的头部 = claudeMd 头部")
            .contains("claudeMd 头部")
            .as("必须给出正确调用方式（传 sessionId + 单点入口 clearPromptCaches）")
            .contains("sessionId")
            .contains("clearPromptCaches");
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /**
     * 挂一个 appender 到指定 logger，并把该 logger 限到 {@code min} 级。
     *
     * <p>WARN 用例传 {@link Level#WARN}：生产 root=INFO ⇒ debug 不算留痕，探针口径 = 生产口径。
     * INFO 用例传 {@link Level#INFO}（按会话清的 INFO 留痕要看得见）。
     */
    private static ListAppender<ILoggingEvent> attach(Class<?> owner, Level min) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(min);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender, Class<?> owner) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(owner);
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(null);   // 还原继承（logback-test.xml 里 com.nexusai=DEBUG）
    }

    private static List<String> warnTexts(ListAppender<ILoggingEvent> appender) {
        return formatted(appender, Level.WARN);
    }

    private static List<String> infoTexts(ListAppender<ILoggingEvent> appender) {
        return formatted(appender, Level.INFO);
    }

    private static List<String> formatted(ListAppender<ILoggingEvent> appender, Level min) {
        return appender.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(min))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
