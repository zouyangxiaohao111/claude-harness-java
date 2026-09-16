package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [批 D3 2026-09-16] {@code UserContextProvider} <b>降级态</b>（{@code claudemdEngine == null}）
 * 的 CLAUDE.md <b>扫描根</b>必须锚 {@link CwdResolution#getOriginalCwdLayer(String)}
 * （= CC {@code getOriginalCwd()}，<b>随 worktree 变</b>），⛔ 不得沿用构造期字段
 * （= 稳定会话项目根，<b>不</b>随 worktree 变）。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * {@code claudeMd()} 有两条路：
 * <ul>
 *   <li><b>路 A（引擎存在）</b>：扫描根由 {@code ClaudemdEngine.resolveOriginalCwd(sessionId)}
 *       解析（{@code ClaudemdEngine:563}，即 {@code getOriginalCwdLayer}）—— ✅ 本类不涉及；</li>
 *   <li><b>路 B（{@code claudemdEngine == null} 降级态）</b>：原实现
 *       {@code projectRoot.resolve("CLAUDE.md")} 直接用<b>构造期字段</b>。该字段在
 *       {@code LlmAgentLoop:4427-4433} 处的生产值 = 稳定会话项目根（[批 P23] 刻意选定，因为
 *       <b>引擎存在时</b>它当 AutoMem/TeamMem 基址，那个用途要稳定）。</li>
 * </ul>
 * 而 CC 的 CLAUDE.md 扫描根逐字是 {@code getOriginalCwd()}（{@code claudemd.ts:850}：
 * {@code const originalCwd = getOriginalCwd()}，{@code claudemd.ts:851} 起向上遍历）—— <b>会</b>
 * 随 {@code EnterWorktreeTool} 重锚。⇒ 降级态下 worktree 会话会读到「主项目」的 CLAUDE.md，
 * 而 CC 会读「worktree 自己」的那份（本类要钉住的用户可见差异）。
 *
 * <h2>同一字段两用途 · 锚方向相反但都对（⛔ 不是写错）</h2>
 * <ul>
 *   <li><b>AutoMem/TeamMem 基址</b>（引擎存在时）= 要<b>稳定</b> ⇒ {@code getProjectRoot}
 *       （[批 P23]，判据 CC {@code state.ts:498-508}）；</li>
 *   <li><b>降级态扫描根</b>（引擎缺失时）= 要<b>随 worktree 变</b> ⇒ {@code getOriginalCwdLayer}
 *       （本批 D3，判据 CC {@code claudemd.ts:850}）。</li>
 * </ul>
 * 二者服务不同用途、各有 CC 锚，故不矛盾。本类的鉴别力正来自「让两个锚指向<b>不同</b>目录」。
 *
 * <h2>RED 条件（反向实验配方）</h2>
 * <ol>
 *   <li>把 {@code UserContextProvider.degradedClaudeMdScanRoot()} 改回
 *       {@code return projectRoot}（用字段值）⇒
 *       {@link #degradedScanRoot_followsWorktreeReanchor()} 红（读 BOUND_MARKER，非 WORKTREE_MARKER）；</li>
 *   <li>把该方法的回落腿改成静默（删掉 {@code log.warn}）⇒
 *       {@link #unresolvableSessionState_fallsBackToFieldWithoutThrowing()} /
 *       {@link #sessionlessProvider_fallsBackToFieldWithoutThrowing()} 的 WARN 断言红。</li>
 * </ol>
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>三个候选目录各放一个可区分的 CLAUDE.md 哨兵</b>（BOUND / WORKTREE / PROCESS_DIR）——
 *       断言「读到哪一个」而不是路径字符串；三值相同则任何实现都绿。
 *       PROCESS_DIR 哨兵的作用是<b>把「回落字段」与「回落进程 user.dir」分开</b>：
 *       只断言 {@code isEqualTo(BOUND_MARKER)} 无法排除「回落 user.dir」这种错法。</li>
 *   <li><b>必须显式 {@code SessionProjectRoot.setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对任意 sessionId 答 sessionless
 *       （见 {@code SessionProjectRootTestSupport}），不显式登记稳定锚会落到进程 user.dir。</li>
 *   <li><b>{@code claudemdEngine} 必须为 null</b>：否则走引擎链（路 A），降级态分支观测不到
 *       （断言恒绿）。</li>
 * </ul>
 */
@DisplayName("[批 D3] UserContextProvider 降级态（engine==null）扫描根 = getOriginalCwdLayer（随 worktree 变）")
class UserContextProviderDegradedScanRootTest {

    private static final String SESSION = "sess-d3-degraded-scan-root";

    /** 稳定锚：DB 绑定项目根 = {@code LlmAgentLoop:4427-4433} 兜底腿（{@code getProjectRoot}）的值。 */
    @TempDir
    Path boundProject;

    /** 重锚槽：模拟 {@code EnterWorktreeTool} 重锚后的 worktree 目录。 */
    @TempDir
    Path worktree;

    /** 进程 {@code user.dir}（第三值 —— 把「回落字段」与「回落 user.dir」区分开）。 */
    @TempDir
    Path processDir;

    private String savedUserDir;

    @BeforeEach
    void setUp() throws Exception {
        savedUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", processDir.toString());
        Files.writeString(boundProject.resolve("CLAUDE.md"), "BOUND_MARKER");
        Files.writeString(worktree.resolve("CLAUDE.md"), "WORKTREE_MARKER");
        Files.writeString(processDir.resolve("CLAUDE.md"), "PROCESS_DIR_MARKER");
        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
        SessionCwdHolder.clearOriginalCwd(SESSION);
        SessionCwdHolder.clear(SESSION);
        CwdResolution.resetWarnGatesForTesting();
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        } else {
            System.clearProperty("user.dir");
        }
    }

    /**
     * 生产形态（{@code LlmAgentLoop:4427-4433} 同款四参构造）：{@code projectRoot} = 稳定会话项目根，
     * {@code claudemdEngine} = null（降级态）。
     */
    private UserContextProvider degradedProvider() {
        return new UserContextProvider(boundProject, System::getenv, null, SESSION);
    }

    /** 挂 ListAppender 抓本类日志（先例：{@code CwdResolutionTest#warnGatesAreIndependentPerEntry}）。 */
    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            attachAppender(ch.qos.logback.classic.Logger logger) {
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        return app;
    }

    private static List<String> warnMessages(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app) {
        return app.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @Test
    @DisplayName("夹具有效性自检：两锚确实指向不同目录且都能取到自己的值（否则本类恒绿）")
    void fixture_actuallySeparatesTheTwoAnchors() {
        // WHY（规则十二 · 反恒绿）：本类全部鉴别力来自「稳定锚与重锚槽指向不同目录且哨兵不同」。
        // 夹具写错（如忘了 setOriginalCwd）时，任何实现都会读到同一个值 ⇒ 断言恒绿。
        assertThat(CwdResolution.getProjectRoot(SESSION))
            .as("稳定锚必须真的生效（显式 setForSession）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("未进 worktree 时，重锚槽回落稳定锚（两层回落语义）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));

        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("重锚槽必须真的生效（否则 getOriginalCwdLayer 与 getProjectRoot 同值 ⇒ 恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(worktree.toString()));
        assertThat(CwdResolution.normalizeCwd(worktree.toString()))
            .as("两锚必须不同值（同值则本类的核心断言无鉴别力）")
            .isNotEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
    }

    @Test
    @DisplayName("⭐ 进出 worktree ⇒ 降级态扫描根跟着变（读到 worktree 自己的 CLAUDE.md）")
    void degradedScanRoot_followsWorktreeReanchor() {
        UserContextProvider provider = degradedProvider();

        assertThat(provider.claudeMd())
            .as("未进 worktree：原始 cwd == 稳定锚 ⇒ 读到稳定锚的 CLAUDE.md")
            .isEqualTo("BOUND_MARKER");

        // 进 worktree（EnterWorktreeTool 的同款效果）
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        assertThat(provider.claudeMd())
            .as("⭐ 进 worktree ⇒ 必须读 worktree 自己的 CLAUDE.md"
                + "（改回「用构造期字段」⇒ 读 BOUND_MARKER ⇒ 红；CC claudemd.ts:850 getOriginalCwd）")
            .isEqualTo("WORKTREE_MARKER");
        assertThat(provider.claudeMd())
            .as("⛔ 不得锚到稳定会话项目根（那是 AutoMem 用途的锚，P23 领地）")
            .isNotEqualTo("BOUND_MARKER");

        // 出 worktree（ExitWorktreeTool 的同款效果）
        SessionCwdHolder.clearOriginalCwd(SESSION);
        assertThat(provider.claudeMd())
            .as("出 worktree ⇒ 重锚槽清空 ⇒ 回落稳定锚（同一 provider 实例按调用现算，⛔ 非构造期冻结）")
            .isEqualTo("BOUND_MARKER");

        // ⭐ 同一 provider 第二次进出必须仍然跟着变 —— 钉住「按调用现算」
        //   （若实现改成构造期解析一次并缓存，本断言红）
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        assertThat(provider.claudeMd())
            .as("⭐ 扫描根必须每次调用现算（构造期冻结的实现在这里必然红）")
            .isEqualTo("WORKTREE_MARKER");
    }

    @Test
    @DisplayName("⭐ 拿不到会话态（fail-loud 态）⇒ 不抛 · 回落字段值 · ≥WARN")
    void unresolvableSessionState_fallsBackToFieldWithoutThrowing() {
        // 装置：清掉绑定 + DB 明确答「无此会话」⇒ getOriginalCwdLayer 走 fail-loud 抛分支
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        assertThatCode(() -> CwdResolution.getOriginalCwdLayer(SESSION))
            .as("装置自检：该会话态下 CwdResolution 确实 fail-loud（否则本用例守不住「不抛」）")
            .isInstanceOf(com.nexusai.infra.exception.UnresolvedProjectRootException.class);

        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UserContextProvider.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            attachAppender(logger);
        try {
            UserContextProvider provider = degradedProvider();
            String md = provider.claudeMd();

            assertThat(md)
                .as("⭐ 拿不到会话态 ⇒ 回落构造期字段值（BOUND_MARKER）"
                    + "，⛔ 不得回落进程 user.dir（PROCESS_DIR_MARKER）")
                .isEqualTo("BOUND_MARKER");
            assertThat(md)
                .as("⛔ 不得回落进程 user.dir —— 那是「无会话出口」，与「字段兜底」是两回事")
                .isNotEqualTo("PROCESS_DIR_MARKER");

            assertThat(warnMessages(app))
                .as("⭐ 回落必须响：≥WARN 日志（把 log.warn 删成静默 ⇒ 本断言红）")
                .anyMatch(m -> m.contains("降级态") && m.contains("回落"));
        } finally {
            logger.detachAppender(app);
            app.stop();
        }
    }

    @Test
    @DisplayName("⭐ 构造器拿不到 sessionId（无会话）⇒ 不抛 · 回落字段值 · ≥WARN")
    void sessionlessProvider_fallsBackToFieldWithoutThrowing() {
        // 装置 = 全参构造但 sessionId 为 null（= 上下文 analyzer / MCP 等「结构上确无会话」的形态）
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UserContextProvider.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            attachAppender(logger);
        try {
            UserContextProvider provider = new UserContextProvider(boundProject, System::getenv, null);
            String md = provider.claudeMd();

            assertThat(md)
                .as("无会话 ⇒ 回落构造期字段值（BOUND_MARKER），⛔ 不回落进程 user.dir")
                .isEqualTo("BOUND_MARKER");
            assertThat(warnMessages(app))
                .as("⭐ 回落必须响：≥WARN 日志（静默回落 ⇒ 本断言红）")
                .anyMatch(m -> m.contains("降级态") && m.contains("回落"));
        } finally {
            logger.detachAppender(app);
            app.stop();
        }
    }
}
