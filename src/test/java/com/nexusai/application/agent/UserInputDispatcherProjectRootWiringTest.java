package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 P16 · 接线层守护] {@code UserInputDispatcher} 的 <b>生产分派路径</b>构造出的
 * {@link UserInputDispatcher.SlashCommandContext}，其 {@code projectRoot} 槽必须锚
 * <b>稳定会话绑定项目根</b>（{@link SessionStorage#sessionProjectRoot(String)}），
 * ⛔ 不得回落到随 worktree 重锚的 {@code originalCwd} 槽
 * （{@link CwdResolution#getOriginalCwdLayer(String)}）。
 *
 * <h2>WHY（规则九 · 测试验证意图；为什么必须另立一类）</h2>
 * 批 P13 把本槽的解析器由 {@code CwdResolution::getOriginalCwdLayer} 换成
 * {@code SessionStorage::sessionProjectRoot}（{@code UserInputDispatcher:322-329}
 * {@code newSlashCommandContext}；接线表达式在 {@code :328}）。同批的 {@code SessionStorageTranscriptRootParityTest}
 * 的守护①②③④ <b>全部断言在 {@code SessionStorage} seam 层</b>（测 {@code SessionStorage} 自己），
 * 而既有的 {@link UserInputDispatcherSlashCommandContextTest} 的 R4 用例<b>直接 new
 * {@code SlashCommandContext}</b>（手工传 {@code CwdResolution::getOriginalCwdLayer} 作
 * projectRoot 解析器）—— <b>两处都不经过生产构造点</b>。
 *
 * <p>实证（主 agent 验收 P13 时的变异 F）：把 {@code newSlashCommandContext} 的 resolver 改回
 * {@code CwdResolution::getOriginalCwdLayer}，上述两类<b>各 25 run 全绿</b> ⇒
 * 「生产调用方是否真接了新 seam」<b>没有任何一条断言守着</b>。本类补这一个洞：
 * <b>唯一入口 = 生产分派面</b>（{@link UserInputDispatcher#dispatch} /
 * {@link UserInputDispatcher#dispatchResult}），handler 内读 {@code ctx.projectRoot()}。
 *
 * <h2>RED 条件（反向实验配方 · 实测见批 P16 报告）</h2>
 * 把 {@code UserInputDispatcher:328} 的 {@code SessionStorage::sessionProjectRoot} 改回
 * {@code CwdResolution::getOriginalCwdLayer} ⇒ 本类两条用例同时翻红。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>必须显式 {@code SessionProjectRoot.setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对<b>任意</b> sessionId 答
 *       {@code sessionlessEnvironment()}（详见 {@link SessionProjectRootTestSupport} 的类 javadoc），
 *       不显式登记则两槽会落到同一个「无会话命名出口」值 ⇒ 断言无鉴别力（假绿）。</li>
 *   <li><b>必须让三槽取不同值</b>（绑定根 ≠ originalCwd 层 ≠ sessionCwd 层）：
 *       这是本类<b>唯一</b>的鉴别力来源。若两槽同值，「改回旧锚」与「新锚」产出同一个字符串 ⇒
 *       本类不可能变红。故用例里同时断言「= 绑定根」与「≠ originalCwd 层」。</li>
 * </ul>
 */
@DisplayName("[批 P16] UserInputDispatcher 接线：SlashCommandContext.projectRoot 槽锚稳定会话根")
class UserInputDispatcherProjectRootWiringTest {

    private static final String SESSION = "sess-p16-uid-wiring";

    @TempDir
    Path tempDir;

    @BeforeEach
    void declareNoDatabase() {
        // 本夹具不接 DB（见 SessionProjectRootTestSupport javadoc）—— 本类用例的会话经 setForSession
        // 显式登记，命中冻结表；该声明只服务于「可能出现的其它 sessionId 查询」不撞 fail-loud。
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void cleanup() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.reset();
    }

    /** 会话绑定项目根（会话身份锚 · 稳定）· 真实目录（isValidProjectRoot 要求绝对路径 + 存在）。 */
    private Path boundProject() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
    }

    /** worktree 目录（一次性隔离目录 · originalCwd 重锚后的值）· 真实目录。 */
    private Path worktree() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-worktree")).toRealPath();
    }

    /**
     * 三槽夹具（本类全部用例共用）：
     * <ul>
     *   <li>{@code SessionProjectRoot.setForSession(proj)} —— 会话绑定项目根（稳定锚）</li>
     *   <li>{@code SessionCwdHolder.setOriginalCwd(wt)} —— worktree 重锚层（EnterWorktreeTool 的效果）</li>
     *   <li>{@code SessionCwdHolder.set(wt/sub)} —— sessionCwd 层（bash {@code cd} 的效果）</li>
     * </ul>
     * <b>先决条件断言（装置上膛证明）</b>：originalCwd 层与 sessionCwd 层确实都 ≠ 绑定根，
     * 否则本类用例无鉴别力。
     */
    private void armThreeDistinctSlots(Path proj, Path wt, Path cwdSub) {
        SessionProjectRoot.setForSession(SESSION, proj.toString());
        SessionCwdHolder.setOriginalCwd(SESSION, wt.toString());
        SessionCwdHolder.set(SESSION, cwdSub.toString());

        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("夹具前置：originalCwd 层 = worktreePath（EnterWorktreeTool.applySessionCwd 的效果）"
                + "—— 否则「改回旧锚」与「新锚」同值 ⇒ 本用例无鉴别力")
            .isEqualTo(wt.toString());
        assertThat(CwdResolution.getCwd(SESSION))
            .as("夹具前置：cwd 层 = cd 后子目录（getCwd 语义）—— 与上面两槽三值互异")
            .isEqualTo(cwdSub.toString());
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("夹具前置：originalCwd 层必须 ≠ 绑定根（本类全部断言的前提）")
            .isNotEqualTo(proj.toString());
    }

    @Test
    @DisplayName("dispatch（void 面）构造的 ctx：projectRoot() = 稳定绑定根，⛔ 不是 originalCwd 槽")
    void dispatchBuildsContext_whoseProjectRootIsStableAnchor() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        Path cwdSub = Files.createDirectories(wt.resolve("sub")).toRealPath();
        armThreeDistinctSlots(proj, wt, cwdSub);

        UserInputDispatcher dispatcher = new UserInputDispatcher();
        String[] seen = new String[2];
        // 唯一入口 = 生产分派面：ctx 由 UserInputDispatcher.newSlashCommandContext 构造，
        //   handler 只是读取者（不参与构造）——这正是「接线」与「seam」的区别。
        dispatcher.registerSlashCommandCtx("p16-root", ctx -> {
            seen[0] = ctx.projectRoot();
            seen[1] = ctx.cwd();
        });

        UserInputDispatcher.RoutingResult routed = dispatcher.dispatch("/p16-root", SESSION, "msg-1");
        assertThat(routed.routedTo()).as("命令确实被分派（否则下面的断言是空跑）").isEqualTo("p16-root");

        assertThat(seen[0])
            .as("生产接线（UserInputDispatcher:328）必须把 projectRoot 槽接到"
                + " SessionStorage::sessionProjectRoot（稳定会话绑定项目根）。"
                + "反向实验：改回 CwdResolution::getOriginalCwdLayer ⇒ 本断言得到 worktree 路径 ⇒ 红")
            .isEqualTo(proj.toString());
        assertThat(seen[0])
            .as("⛔ projectRoot 槽不得取自 originalCwd（worktree 重锚层）—— 否则 /insights 等命令"
                + "在进过 worktree 的会话里会去 worktree slug 下读 transcript（统计恒空/读错）")
            .isNotEqualTo(wt.toString());
        assertThat(seen[1])
            .as("cwd 槽仍是 getCwd 语义（= cd 后子目录）⇒ 两槽确实分叉，"
                + "证明上面两条不是「两个槽恰好同值」的假装置")
            .isEqualTo(cwdSub.toString());
        assertThat(seen[0]).as("两槽在 cd 过的会话里必然不同值").isNotEqualTo(seen[1]);
    }

    @Test
    @DisplayName("dispatchResult（result 面）构造的 ctx 同样锚稳定绑定根（两个分派点同源）")
    void dispatchResultBuildsContext_whoseProjectRootIsStableAnchor() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        Path cwdSub = Files.createDirectories(wt.resolve("sub")).toRealPath();
        armThreeDistinctSlots(proj, wt, cwdSub);

        UserInputDispatcher dispatcher = new UserInputDispatcher();
        String[] seen = new String[1];
        dispatcher.registerSlashCommandResultCtx("p16-root-result", ctx -> {
            seen[0] = ctx.projectRoot();
            return UserInputDispatcher.LocalCommandResult.text("ok");
        });

        UserInputDispatcher.LocalCommandResult r =
            dispatcher.dispatchResult("/p16-root-result", SESSION, "msg-2");
        assertThat(r).isNotNull();
        assertThat(r.kind()).as("命令确实被分派").isEqualTo("text");

        assertThat(seen[0])
            .as("dispatchResult 与 dispatch 共用 newSlashCommandContext ⇒ 同样必须锚稳定绑定根"
                + "（反向实验：改回 getOriginalCwdLayer ⇒ 得到 worktree 路径 ⇒ 红）")
            .isEqualTo(proj.toString());
        assertThat(seen[0]).isNotEqualTo(wt.toString());
    }
}
