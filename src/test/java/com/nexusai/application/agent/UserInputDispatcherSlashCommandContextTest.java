package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserInputDispatcher.SlashCommandContext} 承载面回归 · 批 r10（裁定 #10 首个入手域）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：本批把「命令 handler 各自按 sessionId 反查 cwd」改成
 * 「边界构造上下文、惰性槽承载」。这条改造有<b>两个不可见的失守面</b>，现有测试<b>一个都抓不住</b>：
 * <ol>
 *   <li><b>惰性失守</b>：若槽在构造期 eager 求值，则<b>所有</b>注册命令都会在「会话存在但未绑定项目根」
 *       时新增 fail-loud 抛出（改造前只有真正读 cwd 的 9 个命令会抛）。</li>
 *   <li><b>双槽串味</b>：若 {@code projectRoot()} 用 {@code cwdResolver} 求值（「看起来等价」），
 *       则在发生过 bash {@code cd} 的会话里 {@code /insights} 会读错 transcript。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么装置必须显式 {@code setDbResolver}（⛔ 不要删）</b>：测试环境下 fail-loud 分支
 * <b>结构上不可达</b> —— 全局 JUnit 扩展
 * {@code com.nexusai.test.support.NoDatabaseSessionProjectRootExtension}（经 {@code META-INF/services}
 * 自动注册）对<b>任意</b> sessionId 都答 {@code Lookup.sessionlessEnvironment()} ⇒ 走「确无会话」
 * 命名出口（进程 user.dir），<b>永不抛</b>。因此本类每个用例都显式覆盖为
 * {@code Lookup.unbound()}（= {@code projectRoot=null, sessionKnown=true}，cwd 域<b>真实会抛</b>）。
 * ⛔ 否则「惰性」用例会<b>恒绿</b>（假绿灯），把 eager 缺陷放过去。
 */
@DisplayName("[批 r10] SlashCommandContext 惰性双槽承载面")
class UserInputDispatcherSlashCommandContextTest {

    /** 会话存在但未绑定项目根 = cwd 域 fail-loud 态（cwd3 已落地的分支）。 */
    private static final SessionProjectRoot.DbResolver UNBOUND =
        sessionId -> SessionProjectRoot.Lookup.unbound();

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // R3 · 惰性（本批最大风险面）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * R3 正向：<b>不使用 cwd 的命令，不得为「会话存在但未绑定项目根」新增 fail-loud</b>。
     *
     * <p>同用例内先证明装置<b>已上膛</b>（第二条断言：读 cwd 的命令在同一会话下<b>确实抛</b>），
     * 否则「不抛」可能是装置没接上造成的<b>假绿</b>。
     *
     * <p><b>反向实验（证伪「方案是惰性的」）</b>：把 {@link UserInputDispatcher.SlashCommandContext}
     * 的双槽改成构造期 eager 求值 ⇒ 本用例第一条断言必须变红
     * （见报告「R3 证伪配方」实测读数）。
     */
    @Test
    @DisplayName("R3: 未绑定会话下「不读 cwd 的命令」不抛；「读 cwd 的命令」仍抛（装置上膛证明）")
    void lazySlots_doNotExpandFailLoudSurface() {
        SessionProjectRoot.setDbResolver(UNBOUND);
        String sessionId = "sess-r10-lazy";
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        // ① 不使用 cwd 的命令（对齐 /usage /plan 等 27 个不读 cwd 的生产 handler）
        dispatcher.registerSlashCommandCtx("no-cwd", ctx -> {
            String ignored = ctx.args() + "/" + ctx.sessionId() + "/" + ctx.inFlightUserMessageId();
            assertThat(ignored).isNotBlank();
        });
        // ② 使用 cwd 的命令（对齐 /files /diff /release-notes）
        dispatcher.registerSlashCommandCtx("uses-cwd", ctx -> {
            String cwd = ctx.cwd();
            assertThat(cwd).isNotBlank();
        });

        // ① 必须不抛（惰性：从未读取 ⇒ 从未解析 ⇒ fail-loud 面不变）
        UserInputDispatcher.RoutingResult routed = dispatcher.dispatch("/no-cwd arg", sessionId, "msg-1");
        assertThat(routed.routedTo()).isEqualTo("no-cwd");

        // ② 装置上膛证明：同一会话下读 cwd ⇒ 真的抛（否则本用例是假绿）
        assertThatThrownBy(() -> dispatcher.dispatch("/uses-cwd", sessionId, "msg-2"))
            .as("装置上膛证明：unbound 解析器下读 cwd 必须抛，否则惰性用例无鉴别力")
            .isInstanceOf(IllegalStateException.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // R4 · 双槽不得串味（#10 硬约束）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * R4：bash {@code cd} 后 {@code cwd()} 与 {@code projectRoot()} <b>必须分叉</b>。
     *
     * <p><b>守护的 WHY</b>：{@code /insights} 要的是<b>存档锚</b>（getOriginalCwdLayer 语义，不受 cd
     * 影响），若被 {@code ctx.cwd()}（getCwd 语义，受 cd 影响）顶替，则在 cd 过的会话里会去错误的
     * 目录读 transcript ⇒ 统计恒空/读错会话。
     *
     * <p><b>反向实验</b>：把 {@code projectRoot()} 改为委托 {@code cwdResolver} ⇒ 本用例变红。
     */
    @Test
    @DisplayName("R4: bash cd 后 cwd() 与 projectRoot() 必须分叉（存档锚 ≠ 当前目录）")
    void cwdAndProjectRoot_areIndependentSlots(@TempDir Path root) throws Exception {
        Path sub = Files.createDirectories(root.resolve("sub"));
        String sessionId = "sess-r10-r4";
        SessionProjectRoot.setForSession(sessionId, root.toString());   // 绑定项目根 = root
        SessionCwdHolder.set(sessionId, sub.toString());                // 模拟 bash `cd root/sub`

        UserInputDispatcher.SlashCommandContext ctx = new UserInputDispatcher.SlashCommandContext(
            "", sessionId, null, CwdResolution::getCwd, CwdResolution::getOriginalCwdLayer);

        assertThat(ctx.cwd()).as("cwd 槽 = cd 后子目录（getCwd 语义）")
            .isEqualTo(CwdResolution.getCwd(sessionId));
        assertThat(ctx.projectRoot()).as("projectRoot 槽 = 存档锚（getOriginalCwdLayer 语义）")
            .isEqualTo(CwdResolution.getOriginalCwdLayer(sessionId));
        assertThat(ctx.cwd())
            .as("⛔ 两槽在 cd 过的会话里必然不同值 —— 用 cwd 顶替会让 /insights 读错 transcript")
            .isNotEqualTo(ctx.projectRoot());
    }

    // ════════════════════════════════════════════════════════════════════════
    // R1 · memoize（/stats 同值合并的机制基础）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * R1 机制：同一上下文内多次读取同一槽 ⇒ <b>解析器只被调用一次</b>。
     *
     * <p><b>守护的 WHY</b>：{@code /stats} 原先在同一次执行里对同一会话解析两遍（报告根 + transcript
     * 读源），两份 user.dir 兜底逻辑。memoize 是该合并成立的机制 —— 若这条失效，合并会静默退化为
     * 「仍然两遍」。
     *
     * <p><b>反向实验</b>：删掉 {@code projectRootResolved}/{@code cwdResolved} 标记 ⇒ 计数变 3 ⇒ 红。
     */
    @Test
    @DisplayName("R1: 同槽多次读取只解析一次（memoize）；两槽各自独立计数")
    void slotResolution_isMemoizedPerSlot() {
        int[] projectRootCalls = {0};
        int[] cwdCalls = {0};
        UserInputDispatcher.SlashCommandContext ctx = new UserInputDispatcher.SlashCommandContext(
            "", "sess-r10-r1", null,
            sid -> { cwdCalls[0]++; return "/cwd-" + sid; },
            sid -> { projectRootCalls[0]++; return "/root-" + sid; });

        assertThat(ctx.projectRoot()).isEqualTo("/root-sess-r10-r1");
        assertThat(ctx.projectRoot()).isEqualTo("/root-sess-r10-r1");
        assertThat(ctx.projectRoot()).isEqualTo("/root-sess-r10-r1");
        assertThat(projectRootCalls[0]).as("projectRoot 槽三次读取只解析一次").isEqualTo(1);

        assertThat(ctx.cwd()).isEqualTo("/cwd-sess-r10-r1");
        assertThat(ctx.cwd()).isEqualTo("/cwd-sess-r10-r1");
        assertThat(cwdCalls[0]).as("cwd 槽两次读取只解析一次").isEqualTo(1);
        assertThat(projectRootCalls[0]).as("读 cwd 不得触发 projectRoot 解析（两槽独立）").isEqualTo(1);
    }

    /**
     * R1 兜底单点：解析值为 null/空白 ⇒ 回落 {@code user.dir}（原 4 份 4 行样板语义）。
     *
     * <p><b>反向实验</b>：删掉 {@code nonBlankOrUserDir} 的兜底 ⇒ 返回 null ⇒ 红。
     */
    @Test
    @DisplayName("R1: null/空白解析值回落 user.dir（兜底单点，原 4 份样板语义）")
    void nullOrBlankResolvedValue_fallsBackToUserDir() {
        String userDir = System.getProperty("user.dir", ".");
        UserInputDispatcher.SlashCommandContext ctx = new UserInputDispatcher.SlashCommandContext(
            "", "s", null, sid -> null, sid -> "   ");

        assertThat(ctx.cwd()).isEqualTo(userDir);
        assertThat(ctx.projectRoot()).isEqualTo(userDir);
    }

    // ════════════════════════════════════════════════════════════════════════
    // R5 · 旧 API 薄包装零行为变化
    // ════════════════════════════════════════════════════════════════════════

    /**
     * R5：旧三参 API 经薄包装转 ctx 后，<b>三个实参逐一原样送达</b>（尤其
     * {@code inFlightUserMessageId} —— 它驱动 /compact 等在途消息归属）。
     *
     * <p><b>反向实验</b>：把包装写成 {@code h.accept(ctx.args(), ctx.sessionId(), null)} ⇒ 红。
     */
    @Test
    @DisplayName("R5: 旧 TriConsumer/TriFunction 薄包装三参原样送达（含 inFlightUserMessageId）")
    void legacyRegistration_passesAllThreeArgumentsUnchanged() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        String[] captured = new String[3];

        dispatcher.registerSlashCommand("legacy",
            (args, sessionId, inFlightUserMessageId) -> {
                captured[0] = args;
                captured[1] = sessionId;
                captured[2] = inFlightUserMessageId;
            });
        dispatcher.registerSlashCommandResult("legacy-result",
            (args, sessionId, inFlightUserMessageId) -> {
                captured[0] = args;
                captured[1] = sessionId;
                captured[2] = inFlightUserMessageId;
                return UserInputDispatcher.LocalCommandResult.text("ok");
            });

        dispatcher.dispatch("/legacy hello world", "sess-legacy", "msg-42");
        assertThat(captured).as("void 面三参")
            .containsExactly("hello world", "sess-legacy", "msg-42");

        UserInputDispatcher.LocalCommandResult r =
            dispatcher.dispatchResult("/legacy-result x", "sess-legacy", "msg-43");
        assertThat(r).isNotNull();
        assertThat(r.kind()).isEqualTo("text");
        assertThat(captured).as("result 面三参")
            .containsExactly("x", "sess-legacy", "msg-43");
    }

    /**
     * R5 半径：ctx 面与旧面<b>共用同一张注册表</b>（同名后者覆盖前者，且 {@code hasSlashCommandHandler}
     * 对两种注册都返回 true —— 它是 immediate 命令 busy 优先判定的依据）。
     */
    @Test
    @DisplayName("R5: ctx 面与旧面同表（hasSlashCommandHandler 两种注册均可见）")
    void ctxAndLegacyShareSameRegistry() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        assertThat(dispatcher.hasSlashCommandHandler("a")).isFalse();
        dispatcher.registerSlashCommand("a", (x, y, z) -> { });
        assertThat(dispatcher.hasSlashCommandHandler("a")).isTrue();
        dispatcher.registerSlashCommandCtx("b", ctx -> { });
        assertThat(dispatcher.hasSlashCommandHandler("b")).isTrue();
        dispatcher.registerSlashCommandResultCtx("c",
            ctx -> UserInputDispatcher.LocalCommandResult.skip());
        assertThat(dispatcher.dispatchResult("/c", "s", null).kind()).isEqualTo("skip");
    }
}
