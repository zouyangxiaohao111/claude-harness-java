package com.nexusai.application.agent.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.UserInputDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [misc1 §三] {@code "diff"} 命令<b>双注册</b>实测探针 —— 哪一份在跑？
 *
 * <p><b>背景</b>：命令名 {@code "diff"} 被注册两次 ——
 * {@code CommandRegistrationConfig28#registerDiffHandler} 与
 * {@code CommandRegistrationConfigGroupAGitDir#registerDiffHandler}（两个类各有一份同名私有方法），
 * 二者都调 {@link UserInputDispatcher#registerSlashCommandCtx}，而该方法内部是
 * {@code slashCommandHandlers.put(name, handler)}（已代码核对）⇒ <b>后注册者覆盖先注册者</b>。
 *
 * <p><b>判据（WHY 这样测）</b>：装配顺序不是语言层可断言的 —— Spring 不承诺
 * {@code @Configuration} 的 refresh 顺序，它由 <b>component scan 的候选顺序</b>决定
 * （{@code ClassPathBeanDefinitionScanner} 按同一 classpath 遍历结果注册 bean definition，
 * {@code ConfigurationClassParser} 按该顺序解析、再按该顺序注册各 {@code @Bean} 方法）。
 * 而全量 {@code @SpringBootTest} 在本环境<b>结构性不可用</b>（
 * {@code BrowserWebSocketConfig.createWebSocketContainer} 在 MOCK/NONE web 环境下
 * 拿不到 {@code jakarta.websocket.server.ServerContainer} ⇒ context 起不来；
 * <b>基线既有</b>：{@code McpFullContextStartupTest} 2/2 同因失败，与本测试无关）。
 *
 * <p>⇒ 本探针用<b>同一个 scanner</b>（{@code useDefaultFilters=true}，与
 * {@code ClassPathBeanDefinitionScanner.registerDefaultFilters} 一致）扫描
 * {@code @SpringBootApplication} 所在包 {@code com.nexusai}，取两个 {@code @Configuration}
 * 的相对候选序，再据此顺序装配<b>最小上下文</b>并<b>真实分派 {@code /diff}</b>，
 * 观察哪一份 handler 真的执行（两条互斥的类名前缀日志恰好一条）。
 *
 * <p>⚠️ 本探针<b>只登记不删</b>（用户裁定）—— 不改任何 {@code CommandRegistrationConfig*} 生产逻辑。
 * 两份 handler 的<b>行为等价</b>（都用 {@code ctx.cwd()} 构造 {@code GitStatusProvider} 后取
 * {@code getGitStatus()}），差异仅在日志文案 ⇒ 双注册是<b>冗余</b>而非行为缺陷。
 *
 * <p>⚠️ 残留不确定性：最小上下文只含这两个 {@code @Configuration} + dispatcher，
 * 而真实上下文还有其它 bean。本测试测的是<b>决定胜负的那个量</b>（两者的相对注册序），
 * 不是全量 refresh 的逐字节复刻。
 */
class DiffDualRegistrationProbeTest {

    private static final String CFG_28 =
        "com.nexusai.application.agent.config.CommandRegistrationConfig28";
    private static final String CFG_GROUPA =
        "com.nexusai.application.agent.config.CommandRegistrationConfigGroupAGitDir";

    /** 用与 {@code ClassPathBeanDefinitionScanner} 一致的 scanner 量出两个 @Configuration 的候选相对序。 */
    private static List<String> scannedRelativeOrder() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(true);
        // 与 @SpringBootApplication 的扫描基包一致
        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.nexusai");
        List<String> names = new ArrayList<>();
        for (BeanDefinition bd : candidates) {
            String cn = bd.getBeanClassName();
            if (CFG_28.equals(cn) || CFG_GROUPA.equals(cn)) {
                names.add(cn);
            }
        }
        return names;
    }

    @Test
    @DisplayName("[misc1 §三] /diff 双注册：按实测装配序分派后恰好命中一个实现（贴出命中类名）")
    void diffDispatchHitsExactlyOneImplementation() {
        List<String> order = scannedRelativeOrder();
        System.out.println("[misc1 §三 probe] scanner 候选相对序（先注册者在前）= " + order);

        assertThat(order)
            .as("两个 @Configuration 都必须被 com.nexusai 扫描到（否则装备序无从谈起）")
            .containsExactlyInAnyOrder(CFG_28, CFG_GROUPA);

        // 按实测序装配最小上下文：先扫描到的先 register ⇒ 其 @Bean 侧效应先执行 ⇒ 后注册者覆盖它
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(UserInputDispatcher.class);
            for (String cn : order) {
                ctx.register(forName0(cn));
            }
            ctx.refresh();

            UserInputDispatcher dispatcher = ctx.getBean(UserInputDispatcher.class);
            ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> app = new ListAppender<>();
            app.start();
            root.addAppender(app);
            try {
                // sessionId = null ⇒ SlashCommandContext.cwd() 走 getCwdForNonSession()（user.dir），
                // 不触发「会话存在但未绑定项目根」的 fail-loud（见 CwdResolution.getCwd 空参分支）。
                dispatcher.dispatch("/diff", null, null);
            } finally {
                root.detachAppender(app);
                app.stop();
            }

            List<String> completed = app.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("/diff 执行完成"))
                .toList();
            boolean from28 = completed.stream()
                .anyMatch(m -> m.startsWith("[CommandRegistrationConfig28]"));
            boolean fromGroupA = completed.stream()
                .anyMatch(m -> m.startsWith("[CommandRegistrationConfigGroupAGitDir]"));

            System.out.println("[misc1 §三 probe] /diff 分派产出完成日志 " + completed.size()
                + " 条 · CRC28=" + from28 + " · GroupAGitDir=" + fromGroupA
                + " ⇒ 实测在跑的是: "
                + (from28 ? "CommandRegistrationConfig28"
                          : fromGroupA ? "CommandRegistrationConfigGroupAGitDir" : "（一份都没跑）"));

            assertThat(completed)
                .as("两份注册至少一份必须可达；为空 = 分派链断裂")
                .isNotEmpty();
            assertThat(from28 ^ fromGroupA)
                .as("⭐⭐ 双注册 + map.put ⇒ 同一命令名**只能有一份**在跑；"
                    + "两份同时为真 = 双发（真缺陷），同时为假 = 都没跑。"
                    + "实测 CRC28=" + from28 + " GroupAGitDir=" + fromGroupA)
                .isTrue();
        }
    }

    /**
     * 反向实验：把两个 {@code @Configuration} 的 register 顺序<b>反过来</b>装配 ⇒ 赢家必须翻转。
     *
     * <p>WHY（这是本探针的鉴别力证明）：若不做这一步，「CRC28 先扫到却输给 GroupAGitDir」可能被
     * 误读成「与顺序无关的固定结论」。顺序一翻赢家就翻 ⇒ 证明胜负<b>确由装配序唯一决定</b>，
     * 上一条测出的「GroupAGitDir 在跑」是<b>顺序的结论</b>而非巧合。
     */
    @Test
    @DisplayName("[misc1 §三] 反向实验：装配序翻转 ⇒ 赢家必须翻转（证明胜负由顺序唯一决定）")
    void reversedAssemblyOrderFlipsTheWinner() {
        String winner = dispatchInOrderAndReportWinner(
            List.of(CFG_GROUPA, CFG_28));
        System.out.println("[misc1 §三 probe 反向] 装配序 [" + CFG_GROUPA + ", " + CFG_28
            + "] ⇒ 在跑的是: " + winner);
        assertThat(winner)
            .as("顺序翻转后赢家必须变成 CommandRegistrationConfig28（否则说明赢家与顺序无关，"
                + "上一条的「GroupAGitDir 在跑」就不是装配序的结论）")
            .isEqualTo(CFG_28);
    }

    /** 按给定顺序装配最小上下文并真实分派 /diff，返回执行了的那份实现的类全名（一份都没跑 ⇒ "（无）"）。 */
    private static String dispatchInOrderAndReportWinner(List<String> registerOrder) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(UserInputDispatcher.class);
            for (String cn : registerOrder) {
                ctx.register(forName0(cn));
            }
            ctx.refresh();

            UserInputDispatcher dispatcher = ctx.getBean(UserInputDispatcher.class);
            ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> app = new ListAppender<>();
            app.start();
            root.addAppender(app);
            try {
                dispatcher.dispatch("/diff", null, null);
            } finally {
                root.detachAppender(app);
                app.stop();
            }

            boolean from28 = app.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("[CommandRegistrationConfig28]")
                    && m.contains("/diff 执行完成"));
            boolean fromGroupA = app.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("[CommandRegistrationConfigGroupAGitDir]")
                    && m.contains("/diff 执行完成"));
            assertThat(from28 ^ fromGroupA)
                .as("恰好一份在跑（same as 主测试的不变量）: CRC28=" + from28 + " GroupA=" + fromGroupA)
                .isTrue();
            return from28 ? CFG_28 : CFG_GROUPA;
        }
    }

    /** 小工具：避免在方法体内写 try/catch ClassNotFound（名字来自扫描，必然存在）。 */
    private static Class<?> forName0(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 扫描结果本身不应为空（防空扫描把断言变成恒真）。 */
    @Test
    @DisplayName("[misc1 §三] 前置：com.nexusai 扫描非空且含目标两个 @Configuration")
    void scanFindsBothConfigs() {
        List<String> order = scannedRelativeOrder();
        assertThat(new LinkedHashSet<>(order))
            .as("扫描必须真的看到两个类（否则 scanner 参数写错 ⇒ 上一条测试的装备序是假的）")
            .containsExactlyInAnyOrder(CFG_28, CFG_GROUPA);
    }
}
