package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 D4] <b>工作目录白名单锚 = {@code CwdResolution.getOriginalCwdLayer(sessionId)}</b>
 * —— 即 CC 会在 mid-session {@code EnterWorktreeTool} 时<b>重锚</b>的那一层。
 *
 * <h2>被守的代码</h2>
 * <p>{@link ReadPermissionChecker#isInWorkingDir}（{@code ReadPermissionChecker.java:457-510}）
 * 的白名单根取
 * {@code CwdResolution.getOriginalCwdLayer(ctx.sessionId())}（{@code :467-468}）。
 *
 * <h2>CC 真源（本批自读，⛔ 不信注释）</h2>
 * <p>源仓 {@code D:/code/ai_project/claude-code-best}：
 * <ul>
 *   <li>{@code src/utils/permissions/filesystem.ts:667-674} {@code allWorkingDirectories(context)} =
 *       {@code new Set([getOriginalCwd(), ...additionalWorkingDirectories])} —— 锚在 {@code :671}
 *       的 <b>{@code getOriginalCwd()}</b>；{@code pathInAllowedWorkingPath}（{@code :683-707}）
 *       消费该集合做自动放行。</li>
 *   <li>{@code src/bootstrap/state.ts:494-496} {@code getOriginalCwd() = STATE.originalCwd}
 *       —— 由 mid-session {@code EnterWorktreeTool} 经 {@code setOriginalCwd} 重锚。</li>
 *   <li>{@code src/bootstrap/state.ts:498-508} {@code getProjectRoot() = STATE.projectRoot}
 *       —— javadoc 逐字「<b>Unlike getOriginalCwd(), this is never updated by mid-session
 *       EnterWorktreeTool</b> … Use for <b>project identity</b> (history, skills, sessions)
 *       <b>not file operations</b>」。</li>
 * </ul>
 * ⇒ <b>两者语义不同且 CC 在本处选的是前者的「会随 worktree 变」的那一个</b>。
 * 本仓 {@code CwdResolution} 三入口与 CC 一一对应（见该类 javadoc）：{@code getCwd} =
 * CC {@code getCwd()}、{@code getOriginalCwdLayer} = CC {@code getOriginalCwd()}、
 * {@code getProjectRoot} = CC {@code getProjectRoot()}。
 *
 * <h2>WHY 需要本类（缺口证据 · 批 D4 上游变异实测）</h2>
 * <p>把 {@code ReadPermissionChecker.java:467-468} 的锚换成
 * {@code CwdResolution.getProjectRoot(ctx.sessionId())} ⇒ 跑批 D1-FIX 刚修绿的 3 个permission
 * 测试类（{@code EditImpliesReadTest} / {@code ReadPermissionCheckerInternalPathTest} /
 * {@code SymlinkPermissionTest}）<b>13 run / 0 failure 全绿</b>。
 * <p><b>根因</b>：那 3 个夹具<b>全部没进 worktree</b>
 *（{@code setOriginalCwd} / {@code setForSession} / {@code setDbResolver} 零命中）⇒ 两锚在单测
 * 环境下同值（都落 sessionless 命名出口 = 归一化进程 {@code user.dir}）⇒ 变异<b>不可观测</b>。
 *
 * <p>⚠️ <b>照实补充（本批实测 · ⛔ 不得读成「完全无守卫」）</b>：该变异并非无人抓 —— 把
 * {@code WritePermissionCheckerTest} 一并跑会红 2 条
 *（{@code acceptEdits_outsideWorkingDir_defaultAsk} / {@code defaultAsk_outsideWorkingDir}，
 * 实测 22 run / 2 F）。但那两条只是<b>旁证</b>，理由有二：
 * ① 它们为别的目的显式设了 {@code SessionCwdHolder.setOriginalCwd}，能抓住变异靠的是
 * 「{@code getProjectRoot} 恰好吃到 sessionless 出口的 {@code user.dir}，而目标路径又恰好在
 * {@code user.dir} 之下」这<b>一串未声明的前提</b> —— 前提一变（夹具换绝对外路径 / 出口换值）
 * 守卫<b>静默失效</b>；② 只覆盖 <b>write</b> 路径的 acceptEdits 分支，<b>read 路径零覆盖</b>
 *（实测那 3 个类全绿：{@code EditImpliesReadTest} 3 / {@code ReadPermissionCheckerInternalPathTest} 4 /
 * {@code SymlinkPermissionTest} 6 run（5 skipped）/ 全 0 F）。
 * 本类把前提<b>显式断言</b>出来，并补上 read 路径（含 {@code isInWorkingDir} 直接打锚）。
 *
 * <h2>⭐ 为什么本缺口特别值得补（本仓当前趋势的反向拉力）</h2>
 * <p>批 P13/P21/P23 一路在把各种锚<b>统一到稳定锚</b>
 * {@code CwdResolution.getProjectRoot}（那些处的 CC 锚<b>确实</b>是 {@code getProjectRoot}）。
 * 而本处的 CC 锚是 <b>{@code getOriginalCwd}</b>（{@code filesystem.ts:671}）—— 若后人照
 * 「本会话的趋势」顺手把这处也改成 {@code getProjectRoot}，<b>CC 语义被破坏</b>
 * （进 worktree 后工作目录白名单不再跟着挪 ⇒ worktree 内文件读写全部掉进
 * {@code ask}/{@code deny}），而当时的测试抓不住。本类就是那个抓。
 *
 * <h2>本类的鉴别力来源（唯一）</h2>
 * <p>夹具让 {@code CwdResolution} 的<b>三个「按会话解析」入口</b>取<b>三个两两不同</b>的值
 * （对齐三入口各自对应的 CC 字段：{@code STATE.originalCwd} / {@code STATE.projectRoot} /
 * {@code STATE.cwd}）：
 * <ul>
 *   <li><b>重锚槽（= 正确实现取的值）</b>：{@code SessionCwdHolder.setOriginalCwd(SESSION, worktree)}
 *       ⇒ {@code getOriginalCwdLayer} = {@code worktree}
 *       —— 对齐 CC {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}。</li>
 *   <li><b>稳定锚（= MUT-A 会取的值）</b>：{@code SessionProjectRoot.setForSession(SESSION, boundProject)}
 *       ⇒ {@code getProjectRoot} = {@code boundProject}
 *       —— 对齐 CC {@code getProjectRoot()} 的项目身份根。</li>
 *   <li><b>cd 可覆盖槽（= MUT-B 会取的值）</b>：
 *       {@code SessionCwdHolder.set(SESSION, worktree/sub)} ⇒ {@code getCwd} = {@code worktree/sub}
 *       —— 对齐 CC {@code Shell.ts:407 setCwd} 只挪 {@code STATE.cwd}。</li>
 * </ul>
 * <p>取值相同则「锚 = X」与「锚 = Y」产出同一白名单 ⇒ 本类对该变异<b>恒绿空转</b>。故每个用例
 * 开头经 {@link #assertAnchorsDiverge()} <b>显式断言三者两两分叉</b>
 * （对齐批 P22 {@code ReadPermissionCheckerCwdWiringTest#assertTwoLegsDiverge} 的挂法）：
 * 夹具一旦退化（{@code setForSession} 被拒 / {@code setOriginalCwd} 被拒或被清 /
 * 两 {@code @TempDir} 撞成同一目录 / {@code cd} 槽未设），用例<b>立刻红</b>而不是静默失去鉴别力
 * ——「立刻红」有实测背书：折叠两锚但保留本断言 ⇒ 9 run / 9 F（见 MUT-C）。
 *
 * <h2>反向变异配方（改坏 ⇒ 必红 · 实测读数见批 D4 交付报告）</h2>
 * <ul>
 *   <li><b>MUT-A（本批主变异）</b>：{@code ReadPermissionChecker.java:467-468} 的
 *       {@code getOriginalCwdLayer} → {@code getProjectRoot} ⇒
 *       {@link #boundProjectFile_isNotInWorkingDir()} ·
 *       {@link #readBoundProjectFileOnly_fallsToAsk()} ·
 *       {@link #acceptEdits_writeBoundProjectFileOnly_fallsToAsk()} <b>必红</b>
 *       （worktree 内那三条同时由 Allow 翻成 Ask）。</li>
 *   <li><b>MUT-B（换 cd 可覆盖层）</b>：锚 → {@code CwdResolution.getCwd} ⇒
 *       {@link #worktreeFile_isInWorkingDir()} ·
 *       {@link #readWorktreeFileOnly_allowedByWorkingDirStep()} ·
 *       {@link #acceptEdits_writeWorktreeFileOnly_allowedByWorkingDirStep()} 等必红
 *       —— 夹具的 {@code cwd} 槽已被 {@code cd} 挪到 {@code worktree/sub} ⇒ 与正确锚
 *       {@code worktree} <b>不同值</b>（⚠️ 若夹具不设 cwd 槽，{@code getCwd} 会落 L2=boundProject，
 *       该变异退化成 MUT-A 的复本 ⇒ 故夹具必须显式 {@code SessionCwdHolder.set}）。</li>
 *   <li><b>MUT-C（夹具退化见证 · 自证前提断言承重）</b>：把
 *       {@code setForSession(SESSION, boundProject)} <b>折叠</b>成
 *       {@code setForSession(SESSION, worktree)}（两锚同值）+ 去掉全部
 *       {@link #assertAnchorsDiverge()} 调用 ⇒ MUT-A 下 <b>9 条里 8 条转绿</b>
 *       （实测 9 run / 1 F；唯一仍红的 {@code afterExitWorktree_anchorFallsBackToBoundProject}
 *       与 MUT-A 无关 —— 它另外依赖 {@code boundProject} 就是稳定锚，折叠把它挪走了）。
 *       ⇒ 即：<b>本类几乎全部鉴别力都来自「锚分叉」这一条前提断言</b>；
 *       反向对照（折叠但<b>保留</b>本断言、且不改实现）⇒ <b>9 run / 9 F</b>，
 *       全部红在前提 B 上 ⇒ 断言确实把「静默退化」翻成「响亮变红」。</li>
 * </ul>
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li>必须用 {@code @TempDir} 下的<b>真实目录</b>：{@code SessionProjectRoot.setForSession}
 *       内部走 {@code isValidProjectRoot}（绝对路径 + 目录存在），<b>拒绑</b>不存在的路径。</li>
 *   <li>必须<b>显式</b> {@code setForSession}：见 {@link SessionProjectRootTestSupport}
 *       与 {@code NoDatabaseSessionProjectRootExtension} —— 不显式登记则稳定锚落无会话出口。
 *       （本机 {@code %TEMP%} = {@code C:\Users\WIN\AppData\Local\Temp}，不含 8.3 短名 /
 *       ADS / 3+ 点段 ⇒ 不会误触 {@code PathValidation.hasSuspiciousWindowsPathPattern}，
 *       故用 {@code @TempDir} 安全；先例 {@code AgentLoopContextFactoryWorkspaceDirAnchorTest}。）</li>
 *   <li>两条腿必须落在<b>互不包含</b>的目录：{@code worktree} 与 {@code boundProject} 是两个
 *       独立 {@code @TempDir} ⇒ 目标路径「在 A 不在 B」成立。</li>
 *   <li>必须显式设 {@code SessionCwdHolder.set(SESSION, worktree/sub)}（第三腿）：否则
 *       {@code getCwd} 与 {@code getProjectRoot} 同值，「锚不是 cd 可覆盖层」这条<b>无鉴别力</b>。</li>
 *   <li>{@link #worktreeFile()} 必须落在 {@code worktree} <b>根</b>而不是 {@code worktree/sub}
 *       —— 否则「cd 之后仍在原 worktree 内」这一区分点（MUT-B 的抓点）不成立。</li>
 * </ul>
 */
@DisplayName("[批 D4] 工作目录白名单锚 = getOriginalCwdLayer（随 worktree 重锚 · ⛔ 不得改为 getProjectRoot）")
class ReadPermissionCheckerWorkingDirAnchorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 本类独享会话键（避免与其它类的 static 槽相互污染）。 */
    private static final String SESSION = "sess-d4-workingdir-anchor";

    /**
     * 稳定锚（会话绑定项目根）· 模拟 {@code CwdResolution.getProjectRoot(SESSION)} 的取值。
     * <p>= 若有人把锚改成 {@code getProjectRoot} 时会生效的那个目录。
     */
    @TempDir
    Path boundProject;

    /**
     * 重锚槽 · 模拟 {@code EnterWorktreeTool} 进入 worktree 后的目录
     * （CC {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}）。
     * <p>= 正确实现（{@code getOriginalCwdLayer}）取的那个目录。
     */
    @TempDir
    Path worktree;

    /**
     * 第三腿 · {@code bash cd} 可覆盖层（{@code SessionCwdHolder.set} = {@code CwdResolution.getCwd}
     * 的 L1 槽）。模拟「进 worktree 后在其中 {@code cd sub}」—— 对齐 CC {@code Shell.ts:407 setCwd}
     * 只挪 {@code STATE.cwd}，⛔ 不动 {@code STATE.originalCwd}。
     * <p>用途：让「锚 = getCwd（cd 可覆盖层）」这条变异也<b>有鉴别力</b>
     * （若本槽不设，{@code getCwd} 会落 L2 = boundProject，与 MUT-A 落点同值 ⇒ 该变异只是 MUT-A 的复本）。
     */
    private Path cdDir;

    @BeforeEach
    void enterWorktree() throws IOException {
        // 「本夹具不接 DB 回源」显式声明（对齐 NoDatabaseSessionProjectRootExtension 语义）。
        SessionProjectRootTestSupport.declareNoDatabase();
        // 稳定锚：会话绑定项目根（⛔ 与 worktree 必须不同值 —— 鉴别力的全部来源）。
        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
        // 重锚槽：进 worktree（对齐 EnterWorktreeTool.applySessionCwd → SessionCwdHolder.setOriginalCwd）。
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        // 第三腿：模拟进 worktree 后又在其中 bash `cd sub`。复刻生产形态 —— CC 进 worktree 时
        //   setCwd(worktree)（cwd 槽）与 setOriginalCwd(worktree)（originalCwd 槽）同写，
        //   随后的 cd 只挪 cwd 槽（Shell.ts:407 setCwd），originalCwd 槽不动（[Fix-R1]）。
        //   ⇒ 三锚取值三不同：getCwd=worktree/sub、getOriginalCwdLayer=worktree、getProjectRoot=boundProject。
        cdDir = worktree.resolve("sub");
        Files.createDirectories(cdDir);
        SessionCwdHolder.set(SESSION, cdDir.toString());
    }

    @AfterEach
    void exitWorktree() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION);
        SessionCwdHolder.clear(SESSION);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 夹具
    // ──────────────────────────────────────────────────────────────────────

    private static JsonNode input(String filePath) {
        return JSON.createObjectNode().put("file_path", filePath);
    }

    private static ToolPermissionContext emptyRulesCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.of());
    }

    /** 会话级 ctx：sessionId = 本类 SESSION（白名单锚按它解析）。 */
    private static ToolUseContext ctx(Path effectiveCwd) {
        return ctx(effectiveCwd, PermissionMode.DEFAULT);
    }

    private static ToolUseContext ctx(Path effectiveCwd, PermissionMode mode) {
        return ToolUseContext.of(UUID.randomUUID(), SESSION, mode,
            List.of(), "", AbortController.NOOP, List.of(),
            ToolPermissionContext.of(mode,
                Map.<PermissionRuleSource, Set<PermissionRule>>of(),
                Map.<PermissionRuleSource, Set<PermissionRule>>of(),
                Map.<PermissionRuleSource, Set<PermissionRule>>of(),
                Map.of()),
            mode, Map.of(), false, "", effectiveCwd);
    }

    /** worktree 内、boundProject 外的目标文件（在正确实现下「在白名单内」，在变异下「在外」）。 */
    private Path worktreeFile() {
        return worktree.resolve("d4-worktree-note.txt");
    }

    /** boundProject 内、worktree 外的目标文件（在正确实现下「在白名单外」，在变异下「在内」）。 */
    private Path boundProjectFile() {
        return boundProject.resolve("d4-bound-note.txt");
    }

    private static void assertTaggedAsDifferent(String a, String b, String labelA, String labelB) {
        assertThat(a).as("%s 与 %s 必须分叉（同值 ⇒ 本夹具退化）", labelA, labelB).isNotEqualTo(b);
    }

    /**
     * ⭐ <b>三锚分叉前置断言</b> —— 本类鉴别力的唯一来源，也是「禁止退化成恒绿空转」的闸门。
     *
     * <p>三腿分别取本仓 {@code CwdResolution} 三个「按会话解析」入口的值：
     * <ul>
     *   <li>正确实现的锚 {@code getOriginalCwdLayer(SESSION)} → 必须真取到 {@code worktree}；</li>
     *   <li>MUT-A 的锚 {@code getProjectRoot(SESSION)} → 必须真取到 {@code boundProject}；</li>
     *   <li>MUT-B 的锚 {@code getCwd(SESSION)} → 必须真取到 cd 之后的 {@code cdDir}。</li>
     * </ul>
     * <b>三者两两不同值</b>，否则「锚 = X」与「锚 = Y」产出同一白名单 ⇒ 断言对锚的选型
     * <b>零鉴别力</b>（夹具静默退化 —— 批 P15b 臂 8 / P17 的教训）。
     *
     * <p>⚠️ 本条断言是本类与「只靠 setOriginalCwd 的旁证」（如
     * {@code WritePermissionCheckerTest}）的关键差别：旁证依赖 {@code getProjectRoot} 恰好吃到
     * sessionless 出口的 {@code user.dir}，一旦该前提变（夹具改成绝对外路径 / 出口换值）旁证
     * <b>静默失效</b>；本条断言把前提<b>显式写死</b>，前提一破立刻红。
     */
    private void assertAnchorsDiverge() {
        String originalCwdAnchor = CwdResolution.getOriginalCwdLayer(SESSION);
        String projectRootAnchor = CwdResolution.getProjectRoot(SESSION);
        String cwdAnchor = CwdResolution.getCwd(SESSION);
        assertThat(originalCwdAnchor)
            .as("夹具前提 A（重锚槽真进了 worktree）：getOriginalCwdLayer 必须 = normalizeCwd(worktree)；"
                + "若 setOriginalCwd 被拒/被清，本夹具退化为「两锚同值」")
            .isEqualTo(CwdResolution.normalizeCwd(worktree.toString()));
        assertThat(projectRootAnchor)
            .as("夹具前提 B（稳定锚真的绑上了）：getProjectRoot 必须 = normalizeCwd(boundProject)；"
                + "若 setForSession 被拒，本夹具退化为「两锚同值」")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
        assertThat(cwdAnchor)
            .as("夹具前提 C（cd 槽真的生效）：getCwd 必须 = normalizeCwd(worktree/sub)")
            .isEqualTo(CwdResolution.normalizeCwd(cdDir.toString()));
        assertTaggedAsDifferent(originalCwdAnchor, projectRootAnchor,
            "getOriginalCwdLayer(SESSION)", "getProjectRoot(SESSION)");
        assertTaggedAsDifferent(originalCwdAnchor, cwdAnchor,
            "getOriginalCwdLayer(SESSION)", "getCwd(SESSION)");
        assertTaggedAsDifferent(projectRootAnchor, cwdAnchor,
            "getProjectRoot(SESSION)", "getCwd(SESSION)");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 0 · 夹具自检（前提本身也是被断言的）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("夹具自检：真进 worktree + cd 子目录，三锚取值两两不同（⛔ 断言去掉即静默退化）")
    void fixture_anchorsDiverge() {
        assertAnchorsDiverge();
        // 两条腿的落点必须互不包含（否则「在 A 不在 B」不成立，用例 2/3 无意义）。
        assertThat(worktreeFile().toAbsolutePath().normalize()
                .startsWith(boundProject.toAbsolutePath().normalize()))
            .as("夹具前提 D：worktree 内目标必须<b>不在</b> boundProject 之下")
            .isFalse();
        assertThat(boundProjectFile().toAbsolutePath().normalize()
                .startsWith(worktree.toAbsolutePath().normalize()))
            .as("夹具前提 E：boundProject 内目标必须<b>不在</b> worktree 之下")
            .isFalse();
        // MUT-B 的抓点：目标在 worktree 根，但 cd 之后的 cwd 是 worktree/sub ⇒ 两者必须不同。
        assertThat(worktreeFile().toAbsolutePath().normalize()
                .startsWith(cdDir.toAbsolutePath().normalize()))
            .as("夹具前提 F：worktree 根下目标必须<b>不在</b> cd 之后的 cwd（worktree/sub）之下 —— "
                + "否则 MUT-B（锚 → getCwd）与正确实现落点相同，该变异无鉴别力")
            .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 1/2 · 直接打锚：ReadPermissionChecker.isInWorkingDir（最精确的一层）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("直接打锚：worktree 内路径 ∈ 工作目录白名单（CC filesystem.ts:671 getOriginalCwd）")
    void worktreeFile_isInWorkingDir() {
        assertAnchorsDiverge();

        assertThat(ReadPermissionChecker.isInWorkingDir(
                List.of(worktreeFile().toString()), ctx(worktree)))
            .as("白名单锚 = getOriginalCwdLayer ⇒ 进 worktree 后该目录必须<b>在</b>白名单内；"
                + "锚被改成 getProjectRoot 时此处翻成 false ⇒ 红")
            .isTrue();
    }

    @Test
    @DisplayName("直接打锚：boundProject 路径 ∉ 白名单（证明锚不是 getProjectRoot 的稳定根）")
    void boundProjectFile_isNotInWorkingDir() {
        assertAnchorsDiverge();

        assertThat(ReadPermissionChecker.isInWorkingDir(
                List.of(boundProjectFile().toString()), ctx(worktree)))
            .as("⭐ 反向腿：锚 = getOriginalCwdLayer = worktree ⇒ boundProject 内路径必须<b>不在</b>白名单；"
                + "锚被改成 getProjectRoot（= boundProject）时此处翻成 true ⇒ 红")
            .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 3/4 · 端到端（ReadPermissionChecker.check 全链 · step6 工作目录内 → allow）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("端到端：worktree 内文件 → step6 工作目录内 Allow（reason=Mode(DEFAULT)）")
    void readWorktreeFileOnly_allowedByWorkingDirStep() {
        assertAnchorsDiverge();
        Path target = worktreeFile();

        PermissionResult result = new ReadPermissionChecker(new WritePermissionChecker())
            .check(new ReadFileTool(new PathGuard(worktree)), input(target.toString()), ctx(worktree));

        assertThat(result)
            .as("CC pathInAllowedWorkingPath（filesystem.ts:683-707）命中 → allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("必须归因为 step6 工作目录放行 Mode(DEFAULT)（CC filesystem.ts:1146-1149 "
                + "`decisionReason: {type:'mode', mode:'default'}`），而非其它白名单分支 —— "
                + "锚被换成 getProjectRoot 时该路径落白名单外 ⇒ 走兜底 Ask ⇒ 红")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.DEFAULT));
    }

    @Test
    @DisplayName("端到端：只在 boundProject 内的文件 → 不在工作目录白名单 ⇒ 兜底 Ask（fail-closed）")
    void readBoundProjectFileOnly_fallsToAsk() {
        assertAnchorsDiverge();
        Path target = boundProjectFile();

        PermissionResult result = new ReadPermissionChecker(new WritePermissionChecker())
            .check(new ReadFileTool(new PathGuard(worktree)), input(target.toString()), ctx(worktree));

        assertThat(result)
            .as("⭐ 锚 = worktree ⇒ boundProject 内路径在工作目录外 ⇒ 必须兜底 ask；"
                + "锚被改成 getProjectRoot 时此处翻成 Allow ⇒ 红")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("[批 E1 · O-2] 兜底 ask 的 reason 必须归因 "
                + "WorkingDir(\"Path is outside allowed working directories\")"
                + "（CC filesystem.ts:1189-1192 该处恒用 type:'workingDir'）；"
                + "旧实现 Other(\"default ask for read outside working dir\") ⇒ 本条红")
            .isInstanceOf(PermissionDecisionReason.WorkingDir.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 5/6 · 同一锚的第二个消费点：WritePermissionChecker step3（acceptEdits + 工作目录内）
    //    证明「包内共享」的那条链路也吃同一个锚（ReadPermissionChecker.java:450-451 javadoc）。
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("端到端（write · acceptEdits）：worktree 内 → Allow(Mode ACCEPT_EDITS)（CC filesystem.ts:1360-1375）")
    void acceptEdits_writeWorktreeFileOnly_allowedByWorkingDirStep() {
        assertAnchorsDiverge();
        Path target = worktreeFile();

        PermissionResult result = new WritePermissionChecker()
            .check(new ReadFileTool(new PathGuard(worktree)), input(target.toString()),
                ctx(worktree, PermissionMode.ACCEPT_EDITS));

        assertThat(result)
            .as("CC acceptEdits && isInWorkingDir 双条件（filesystem.ts:1366）⇒ Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("必须归因 Mode(acceptEdits)（filesystem.ts:1366-1374）")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS));
    }

    @Test
    @DisplayName("端到端（write · acceptEdits）：只在 boundProject 内 → 兜底 Ask(WorkingDir)")
    void acceptEdits_writeBoundProjectFileOnly_fallsToAsk() {
        assertAnchorsDiverge();
        Path target = boundProjectFile();

        PermissionResult result = new WritePermissionChecker()
            .check(new ReadFileTool(new PathGuard(worktree)), input(target.toString()),
                ctx(worktree, PermissionMode.ACCEPT_EDITS));

        assertThat(result)
            .as("⭐ 锚 = worktree ⇒ boundProject 内路径在工作目录外 ⇒ acceptEdits 不自动放行；"
                + "锚被改成 getProjectRoot 时此处翻成 Allow ⇒ 红")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("CC 兜底 reason = workingDir（filesystem.ts:1405-1410）")
            .isInstanceOf(PermissionDecisionReason.WorkingDir.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 7 · 镜像见证：同一夹具、只换目标目录归属 → 结论翻转
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("镜像见证：同一 ctx / 同一白名单锚，仅目标在 worktree 内 vs boundProject 内 ⇒ Allow vs Ask 翻转")
    void sameAnchor_sameCtx_twoTargets_flipConclusion() {
        assertAnchorsDiverge();
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(worktree));
        ToolUseContext ctx = ctx(worktree);

        PermissionResult inside = checker.check(tool, input(worktreeFile().toString()), ctx);
        PermissionResult outside = checker.check(tool, input(boundProjectFile().toString()), ctx);

        assertThat(inside)
            .as("worktree 内 → 白名单内 → Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(outside)
            .as("boundProject 内（白名单外）→ Ask。⭐ 同一锚同一 ctx、仅目标不同即结论翻转 —— "
                + "这正是「白名单根 = worktree 而不是 boundProject」的镜像见证；"
                + "若两锚同值（夹具退化），两条会得到同一结论，本用例即失效")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 8 · 退场后锚回落 boundProject（对齐 ExitWorktreeTool 语义）
    //   本用例捉 MUT-A（getProjectRoot：退场后「仍」绑 boundProject 时抓法不同）与
    //   MUT-B（getCwd：退场只清 originalCwd 槽 ⇒ cd 槽仍在 ⇒ 锚不动 ⇒ 红）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("出 worktree（clearOriginalCwd）后锚回落 boundProject（对齐 ExitWorktreeTool.ts:129-136）")
    void afterExitWorktree_anchorFallsBackToBoundProject() {
        assertAnchorsDiverge();

        SessionCwdHolder.clearOriginalCwd(SESSION);
        String afterExit = CwdResolution.getOriginalCwdLayer(SESSION);

        assertThat(afterExit)
            .as("对齐 CC ExitWorktreeTool.ts:129-136 setOriginalCwd(originalCwd) 恢复："
                + "退场后 getOriginalCwdLayer 回落 boundProject（本仓无 pre-worktree 持久化，"
                + "boundProject 即恢复值）")
            .isEqualTo(CwdResolution.getProjectRoot(SESSION));
        assertThat(ReadPermissionChecker.isInWorkingDir(
                List.of(boundProjectFile().toString()), ctx(boundProject)))
            .as("退场后 boundProject 重新在白名单内 —— ⭐ 本断言捉 MUT-B：锚若取 getCwd（cd 可覆盖层），"
                + "clearOriginalCwd 只清 originalCwd 槽、cd 槽仍在（worktree/sub）⇒ 此处翻 false ⇒ 红"
                + "（实测 MUT-B 下本用例红，见批 D4 交付报告）")
            .isTrue();
    }
}
