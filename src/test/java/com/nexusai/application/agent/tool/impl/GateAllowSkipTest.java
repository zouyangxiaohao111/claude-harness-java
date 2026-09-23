package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 批 gate-allow-skip · Edit/Write 门禁 1「免门例外」+ 门禁 2 守卫 + Write 内容兜底。
 *
 * <h2>WHY（意图验证 · backend/CLAUDE.md 规则九）</h2>
 * <ol>
 *   <li><b>用户口径「本来就批准了就不用问」</b>（2026-09-22 原话；追问「本来就批准了指哪一种」→
 *       用户答「CC 怎么做」）⇒ 免门判据 = CC {@code Wu(path, permissions)} =
 *       <b>该路径的 read 权限层判 allow</b>。本类用<b>真实</b> read 权限链（
 *       {@link ReadPermissionChecker#readLayerIsAllow}）取判据，⛔ 不 mock 判据 —— 若判据接线错位，
 *       「锚内路径」与「锚外路径」两组用例会同时红或同时绿（本类的反向鉴别力来源）。</li>
 *   <li><b>用户口径「权限没批准就拦」</b> ⇒ 锚外路径（read 层兜底 ask）必须<b>仍然拒</b>
 *       （Edit errorCode 6 / Write errorCode 2）。这条如果失效，等于把 read-before-write
 *       门禁整个拆掉。</li>
 *   <li><b>撤门不能崩</b>：免门放行后 {@code readState} 可能为 null，而门禁 2 直接解引用
 *       {@code readState.mtimeMillis()} ⇒ 缺守卫即 NPE。本类 {@code beltAndBraces} 组用
 *       「assertThatCode(...).doesNotThrowAnyException()」把「不崩」写成断言。</li>
 *   <li><b>免门合格的路径类不豁免门禁 2</b>：⭐ 更正本类初版措辞（原写「免门之后对未读文件唯一的
 *       保护只剩门禁 2 内容兜底」，过强且自相矛盾 —— {@code readState == null} 时门禁 2 的
 *       {@code readState != null} 守卫本就不触发；CC 的免门分支同理直接
 *       {@code return {result:!0}}，exe off 203520944 块）。
 *       真正可断言的是：「免门合格」（read 层判 allow）的<b>锚内</b>路径并不豁免门禁 2 ——
 *       本类 {@code writeStaleProtection} 组用**锚内**路径钉住：有 entry + mtime 更新 +
 *       内容真变 ⇒ 仍拒 errorCode 3；有 entry + mtime 更新 + 内容未变（云同步/杀软 touch）⇒ 放行。
 *       ⚠️ 夹具前提：「免门合格」必须落在**锚内**（锚外 read 层为 ask ⇒ 免门判据恒假）；
 *       ⚠️ 边界：该半段带 entry ⇒ 门禁 1 不触发，故它钉的是「路径类」，不是「免门命中之后」。</li>
 * </ol>
 *
 * <h2>夹具前提（⚠️ 必须显式声明，否则用例静默失去鉴别力）</h2>
 * <ul>
 *   <li><b>工作目录锚</b> = {@code CwdResolution.getOriginalCwdLayer(sessionId)}，单测环境恒 =
 *       归一化进程 {@code user.dir}（= 本 maven 模块目录）。故「锚内」目标 = {@code <user.dir>/target/…}，
 *       「锚外」目标 = {@code @TempDir}。两条前提在 {@link #assertInsideAnchor}/{@link #assertOutsideAnchor}
 *       写成断言（对齐规则十二：前提不成立必须红，不得静默跳过）。</li>
 *   <li><b>不注入 bean 的对照组</b>：{@code new EditFileTool(...)}（无 setReadPermissionChecker）
 *       ⇒ 判据 fail-closed ⇒ 行为与本批之前逐字节一致（{@code failClosedWithoutChecker} 组钉住）。</li>
 *   <li>单测环境 DB 姿态：见 {@code SessionProjectRootTestSupport.declareNoDatabase()}（沿用既有夹具）。</li>
 * </ul>
 */
@DisplayName("批 gate-allow-skip · Edit/Write 门禁 1 免门（CC Wu）+ 门禁 2 守卫 + Write 内容兜底")
class GateAllowSkipTest {

    // ── [S2 · F-09/F-20/F-10] 夹具 DB 姿态显式声明（沿用 EditWriteToolGateTest 同款） ──
    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 本类创建在 {@code <user.dir>/target} 下的探针文件，逐条清理（不污染模块目录之外的任何位置）。 */
    private final List<Path> probesInAnchor = new ArrayList<>();

    @AfterEach
    void deleteInAnchorProbes() {
        for (Path p : probesInAnchor) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // 清理失败不影响断言结论（target/ 属构建产物目录）
            }
        }
        probesInAnchor.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════════

    private static ToolUseBlock editCall(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return new ToolUseBlock("call-edit", "edit_file", input);
    }

    private static ToolUseBlock writeCall(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "write_file", input);
    }

    private static ToolPermissionContext permCtx(PermissionMode mode) {
        return ToolPermissionContext.of(mode, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** 13 参工厂：显式 permissionContext + effectiveCwd（同 {@code EditImpliesReadTest} 夹具形态）。 */
    private static ToolUseContext ctx(Path effectiveCwd, ToolPermissionContext pc) {
        return ToolUseContext.of(
            UUID.nameUUIDFromBytes(("gas-agent-" + effectiveCwd).getBytes()),
            UUID.nameUUIDFromBytes(("gas-sess-" + effectiveCwd + pc.mode()).getBytes()).toString(),
            PermissionMode.DEFAULT, List.of(), "", AbortController.NOOP, List.of(),
            pc, pc.mode(), Map.of(), false, "", effectiveCwd);
    }

    /**
     * 生产形态的 EditFileTool：{@code ReadPermissionChecker} 用<b>真实</b> read 权限链
     * （内部注入 WritePermissionChecker 以支撑 step5 edit-implies-read）。
     */
    private static EditFileTool editToolWithRealChecker(PathGuard guard) {
        EditFileTool t = new EditFileTool(guard);
        t.setReadPermissionChecker(new ReadPermissionChecker(new WritePermissionChecker()));
        return t;
    }

    private static WriteFileTool writeToolWithRealChecker(PathGuard guard) {
        WriteFileTool t = new WriteFileTool(guard);
        t.setReadPermissionChecker(new ReadPermissionChecker(new WritePermissionChecker()));
        return t;
    }

    private static Path anchorRoot() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static void assertInsideAnchor(Path target) {
        assertThat(target.toAbsolutePath().normalize().startsWith(anchorRoot()))
            .as("夹具前提：%s 必须落在工作目录锚 %s 之内（否则「判据 allow」组会落空）",
                target, anchorRoot())
            .isTrue();
    }

    private static void assertOutsideAnchor(Path target) {
        assertThat(target.toAbsolutePath().normalize().startsWith(anchorRoot()))
            .as("夹具前提：%s 必须落在工作目录锚 %s 之外（否则「判据不 allow」组会落空）",
                target, anchorRoot())
            .isFalse();
    }

    /** 在锚内 {@code <user.dir>/target} 造一个探针文件（内容 + 后缀可指定），用后自动清理。 */
    private Path probeInsideAnchor(String name, String content) throws Exception {
        Path dir = anchorRoot().resolve("target");
        Files.createDirectories(dir);
        Path f = dir.resolve("gate-skip-" + UUID.randomUUID().toString().substring(0, 8) + "-" + name);
        Files.writeString(f, content);
        probesInAnchor.add(f);
        return f;
    }

    private static Path probeOutsideAnchor(Path tmp, String name, String content) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    // ══════════════════════════════════════════════════════════════════════
    // (a)/(b) 免门命中 / 未命中 —— 用户口径的两条腿
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(a)(b) 免门命中 ⇒ 放行；判据不 allow ⇒ 仍然拦（守用户口径「权限没批准就拦」）")
    class SkipAndBlock {

        @Test
        @DisplayName("Edit · 未读 + read 层 allow（锚内）→ 放行（新行为）")
        void editUnreadInsideAnchorPasses(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("a.txt", "hello\n");
            assertInsideAnchor(f);
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            // 前提自检：确实「未读」（门禁 1 的触发条件）
            assertThat(ctx.readFileState().get(
                    ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString())))
                .as("前提：readFileState 无该路径 entry")
                .isNull();

            Tool.ValidationResult vr =
                tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok())
                .as("read 层判 allow ⇒ 免 read-before-write 门禁（CC Wu 分支）;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isTrue();
        }

        @Test
        @DisplayName("Edit · 未读 + read 层不 allow（锚外）→ 仍然拒 errorCode=6（守「权限没批准就拦」）")
        void editUnreadOutsideAnchorStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "a.txt", "hello\n");
            assertOutsideAnchor(f);
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr =
                tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok())
                .as("read 层判 ask ⇒ 不得免门（锚外路径必须仍被 read-before-write 拦住）")
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("6");
            assertThat(vr.message()).isEqualTo("File has not been read yet. Read it first before writing to it.");
        }

        @Test
        @DisplayName("Write · 未读 + read 层 allow（锚内）→ 放行（新行为）")
        void writeUnreadInsideAnchorPasses(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("b.txt", "old\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("read 层判 allow ⇒ 免 read-before-write 门禁;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isTrue();
        }

        @Test
        @DisplayName("Write · 未读 + read 层不 allow（锚外）→ 仍然拒 errorCode=2")
        void writeUnreadOutsideAnchorStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "b.txt", "old\n");
            assertOutsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok()).as("锚外路径不得免门").isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");
            assertThat(vr.message()).isEqualTo("File has not been read yet. Read it first before writing to it.");
        }

        @Test
        @DisplayName("Edit · bypassPermissions 模式 + 锚外（read 层「非规则归因 ask」）→ 放行（CC Wu path-mode-ask 分支）")
        void editUnreadOutsideAnchorBypassModePasses(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "c.txt", "hello\n");
            assertOutsideAnchor(f);
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.BYPASS_PERMISSIONS));

            Tool.ValidationResult vr =
                tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok())
                .as("CC Wu: 'path-mode-ask' 档在 bypassPermissions 下放行;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (c) 回归钉子：有 entry 且非 partialView ⇒ 与本批之前完全一致
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(c) 有 entry 且非 partialView ⇒ 门禁 2 行为与本批之前一致（回归钉子）")
    class EntryPresentUnchanged {

        @Test
        @DisplayName("Edit · entry 存在 + 盘未变 ⇒ 放行；盘变且内容变 ⇒ 拒 errorCode=7")
        void editEntryPresentStaleBehaviour(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "d.txt", "hello\n");
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            String key = ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString());

            long mtime = Files.getLastModifiedTime(f).toMillis();
            ctx.readFileState().set(key, ToolUseContext.ReadState.full(mtime, "hello\n"));

            assertThat(tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx).ok())
                .as("盘未变 ⇒ 放行（与本批之前一致）")
                .isTrue();

            // 盘上内容真变 + mtime 前进 ⇒ 内容兜底不成立 ⇒ 拒 7
            Files.writeString(f, "hello changed\n");
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtime + 10_000L));
            Tool.ValidationResult stale =
                tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx);
            assertThat(stale.ok()).isFalse();
            assertThat(stale.errorCode()).isEqualTo("7");
        }

        @Test
        @DisplayName("Write · entry 存在 + 盘未变 ⇒ 放行（与本批之前一致）")
        void writeEntryPresentNotStalePasses(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "e.txt", "old\n");
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            String key = ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString());

            long mtime = Files.getLastModifiedTime(f).toMillis();
            ctx.readFileState().set(key, ToolUseContext.ReadState.full(mtime, "old\n"));

            assertThat(tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx).ok())
                .as("盘未变 ⇒ 放行（与本批之前一致）")
                .isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (d) Write 的第二道保护（门禁 2 内容兜底）真生效 —— 撤门之后不能裸奔
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(d) Write 第二道保护：mtime 更新 + 内容真变 ⇒ 拒；mtime 更新 + 内容未变 ⇒ 放行")
    class WriteStaleProtection {

        @Test
        @DisplayName("Write · entry 存在 + mtime 前进 + **内容真变** ⇒ 拒 errorCode=3（不再裸奔覆盖）")
        void writeEntryPresentContentChangedRejected(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "f.txt", "old\n");
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            String key = ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString());

            long mtime = Files.getLastModifiedTime(f).toMillis();
            ctx.readFileState().set(key, ToolUseContext.ReadState.full(mtime, "old\n"));

            Files.writeString(f, "SOMEONE ELSE CHANGED IT\n");
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtime + 10_000L));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);
            assertThat(vr.ok())
                .as("盘上内容确变 ⇒ 必须拒（否则免门 = 裸奔覆盖）")
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("3");
        }

        @Test
        @DisplayName("Write · entry 存在 + mtime 前进 + **内容未变** ⇒ 放行（CC o6r 内容兜底，防杀软/云同步误拒）")
        void writeEntryPresentContentSamePasses(@TempDir Path tmp) throws Exception {
            Path f = probeOutsideAnchor(tmp, "g.txt", "old\n");
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            String key = ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString());

            long mtime = Files.getLastModifiedTime(f).toMillis();
            ctx.readFileState().set(key, ToolUseContext.ReadState.full(mtime, "old\n"));
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtime + 10_000L));

            assertThat(Files.readString(f)).as("夹具前提：盘上内容确实未变").isEqualTo("old\n");
            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("mtime 前进但内容未变 ⇒ 内容兜底放行（CC Write: g2(he) && o6r(he, diskBytes)）;"
                    + "实际 errorCode=%s", vr.errorCode())
                .isTrue();
        }

        @Test
        @DisplayName("Write · ① 锚内未读 ⇒ 免门放行；② 免门合格的锚内路径带 entry + 内容确变 ⇒ 仍拒 3")
        void writeUnreadSkipReachesSecondProtectionNotBareOverwrite(@TempDir Path tmp) throws Exception {
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));

            // ① 未读 + 免门命中（锚内）⇒ 门禁 1 放行，validateInput 通过（= 免门生效，不再被拦死）
            Path inAnchor = probeInsideAnchor("n.txt", "old\n");
            assertInsideAnchor(inAnchor);
            ToolUseContext skipCtx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            assertThat(tool.validateInput(writeCall(inAnchor.toString(), "NEW").input(), skipCtx).ok())
                .as("免门命中 ⇒ 门禁 1 放行")
                .isTrue();

            // ② ⭐ 该半段用**锚内**路径（复验者 2026-09-22 指定）：锚内 = 「免门合格」那一类路径。
            //    ⚠️ 鉴别力边界（如实登记，⛔ 不许读过）：本半段**带 entry** ⇒ 门禁 1 本就不触发
            //    （免门不参与本半段判定）⇒ 它钉住的是「**免门合格的路径类**并不豁免门禁 2」，
            //    ⛔ 不是「免门命中之后又撞上第二道保护」。
            //    ⭐ 更强的形态在本实现里**结构上不可构造**：免门要求 readState==null，
            //    门禁 2 的 stale 判定要求 readState!=null，二者在**同一次 validateInput 调用内互斥**。
            //    （上一版此处用 probeOutsideAnchor：该路径连「免门合格」这一属性都不具备 ⇒
            //     措辞「双向钉住 / 不是裸奔」过强，已更正。）
            //    实测：抹掉免门例外（变异 ②）时本用例在 ①（:391）即红。
            Path inAnchorWithEntry = probeInsideAnchor("p.txt", "old\n");
            assertInsideAnchor(inAnchorWithEntry);
            ToolUseContext staleCtx = ctx(tmp, permCtx(PermissionMode.DEFAULT));
            String key = ToolUseContext.keyForReadFileState(
                new PathGuard(tmp), inAnchorWithEntry.toString());
            long mtime = Files.getLastModifiedTime(inAnchorWithEntry).toMillis();
            staleCtx.readFileState().set(key, ToolUseContext.ReadState.full(mtime, "old\n"));
            Files.writeString(inAnchorWithEntry, "CHANGED\n");
            Files.setLastModifiedTime(inAnchorWithEntry,
                java.nio.file.attribute.FileTime.fromMillis(mtime + 10_000L));

            Tool.ValidationResult stale =
                tool.validateInput(writeCall(inAnchorWithEntry.toString(), "NEW").input(), staleCtx);
            assertThat(stale.ok())
                .as("免门合格的锚内路径同样不得裸奔：门禁 2 内容兜底必须拦下「内容确变」的覆盖")
                .isFalse();
            assertThat(stale.errorCode()).isEqualTo("3");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (d2) CC Write 收窄 jPt：未读 .ipynb 不得免门（本批新补的真洞）
    //      CC 真源（我 dd 读发行产物看到 · Python 读 bytes）：
    //        exe off 203517756  function jPt(e){return sz(e.replace(/[. ]+$/,""))}
    //        exe off 197061992  function sz(e){return p(e).toLowerCase()===".ipynb"}   // p = path.extname
    //      ⇒ CC Write 免门 xe = !he && !jPt(y) && !Jbt(…) && G9(…) ⇒ 未读 notebook 恒 errorCode 2。
    //      本批之前 WriteFileTool 全文无 .ipynb 代码检查（grep 只命中注释）⇒ 真洞。
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(d2) CC Write 收窄 jPt(.ipynb)：未读 notebook 不得免门（锚内免门合格路径也不例外）")
    class IpynbNarrowing {

        @Test
        @DisplayName("Write · 锚内未读 .ipynb ⇒ 仍拒 errorCode 2（本批之前此处放行 = 盲覆盖 notebook）")
        void writeUnreadIpynbInsideAnchorStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("nb.ipynb", "{\"cells\":[]}\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            assertThat(ctx.readFileState().get(
                    ToolUseContext.keyForReadFileState(new PathGuard(tmp), f.toString())))
                .as("前提：readFileState 无该路径 entry（未读）")
                .isNull();

            Tool.ValidationResult vr = tool.validateInput(
                writeCall(f.toString(), "{\"cells\":[{}]}").input(), ctx);

            assertThat(vr.ok())
                .as("jPt(path)=true ⇒ 不免门 ⇒ errorCode 2;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");
            assertThat(vr.message())
                .isEqualTo("File has not been read yet. Read it first before writing to it.");
        }

        @Test
        @DisplayName("Write · 锚内未读 .IPYNB（大写）⇒ 仍拒 errorCode 2（sz 走 toLowerCase）")
        void writeUnreadIpynbUppercaseInsideAnchorStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("nb-upper.IPYNB", "{\"cells\":[]}\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(
                writeCall(f.toString(), "{\"cells\":[{}]}").input(), ctx);

            assertThat(vr.ok())
                .as("sz 先 toLowerCase ⇒ .IPYNB 同 .ipynb;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");
        }

        @Test
        @DisplayName("Write · 锚内未读 `x.ipynb.`（尾部点）⇒ 仍拒 errorCode 2（但拦它的是 read 层，不是 jPt）")
        void writeUnreadIpynbTrailingDotInsideAnchorStillErrorCode2(@TempDir Path tmp) throws Exception {
            // 夹具：Windows 由 OS 剥离尾部点后落盘、POSIX 原样落盘 —— 两侧 Files.writeString/
            // exists/deleteIfExists 对同一个 Path 对象都可往返，故本用例两平台均可跑。
            Path f = probeInsideAnchor("nb-dot.ipynb.", "{\"cells\":[]}\n");
            assertInsideAnchor(f);
            assertThat(Files.exists(f))
                .as("夹具前提：尾部点路径可 stat（否则会走 ENOENT 豁免，本用例失去鉴别力）")
                .isTrue();
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(
                writeCall(f.toString(), "{\"cells\":[{}]}").input(), ctx);

            assertThat(vr.ok())
                .as("尾部点 .ipynb 路径不得免门;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");

            // ⚠️ 如实登记（本用例的**鉴别力边界**，实测取证）：这条 errorCode 2 **不是** jPt 拦下的
            //    —— 尾部点本身就命中 read 层的「可疑 Windows 路径」第 4 类 `[.\s]+$`
            //    （PathValidation.hasSuspiciousWindowsPathPattern，CC filesystem.ts:574-576），
            //    read 决策 = ask ⇒ Wu=false ⇒ 免门本就为假。
            //    实测：抹掉 jPt 收窄（变异 ①）后本用例**仍绿**（见 /tmp/mut1.log：
            //    `read 层判「非规则归因 ask」（path-mode-ask）… reason=Other[reason=suspicious
            //    Windows path]`）。⇒ 本用例只钉「该形状不得免门」这一行为不变量，
            //    ⛔ 不能当 jPt 归一化的证据 —— jPt 的鉴别力由上面两条（普通/大写 .ipynb）承担。
        }

        @Test
        @DisplayName("对照 · Write 锚内未读 .txt ⇒ 仍放行（收窄只砍 notebook，未把免门整体关掉）")
        void writeUnreadTxtInsideAnchorStillPasses(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("plain.txt", "old\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("非 notebook ⇒ jPt=false ⇒ 免门仍生效;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isTrue();
        }

        @Test
        @DisplayName("对照 · Write 锚内未读 `x.ipynb.txt` ⇒ 放行（扩展名按 CC path.extname 取最后一段）")
        void writeUnreadIpynbLikeNameStillPasses(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("x.ipynb.txt", "old\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("extname = .txt ⇒ 非 .ipynb ⇒ 免门生效;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isTrue();
        }

        @Test
        @DisplayName("Edit 侧不变 · 锚内未读 .ipynb ⇒ 仍拒 errorCode 5（更靠前的 ipynb 门，本批未动）")
        void editUnreadIpynbInsideAnchorStillErrorCode5(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("edit-nb.ipynb", "{\"cells\":[]}\n");
            assertInsideAnchor(f);
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(
                editCall(f.toString(), "{\"cells\":[]}", "{\"cells\":[{}]}").input(), ctx);

            assertThat(vr.ok()).as("Edit 的 ipynb 门（errorCode 5）行为不变").isFalse();
            assertThat(vr.errorCode()).isEqualTo("5");
            assertThat(vr.message())
                .isEqualTo("File is a Jupyter Notebook. Use the NotebookEditTool to edit this file.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (e) 不崩：免门放行 + readState == null ⇒ 门禁 2 的 null 守卫必须挡住 NPE
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(e) 免门放行 + readState == null ⇒ 不抛（门禁 2 的 readState != null 守卫）")
    class BeltAndBraces {

        @Test
        @DisplayName("Edit · 免门命中且 readState==null ⇒ validateInput 不抛（NPE 由守卫挡住）")
        void editNoEntryNoThrow(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("h.txt", "hello\n");
            assertInsideAnchor(f);
            EditFileTool tool = editToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            assertThatCode(() -> tool.validateInput(
                    editCall(f.toString(), "hello", "CHANGED").input(), ctx))
                .as("若门禁 2 缺 readState != null 守卫，此处 NPE")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Write · 免门命中且 readState==null ⇒ validateInput 不抛（NPE 由守卫挡住）")
        void writeNoEntryNoThrow(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("i.txt", "old\n");
            assertInsideAnchor(f);
            WriteFileTool tool = writeToolWithRealChecker(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            assertThatCode(() -> tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx))
                .as("若门禁 2 缺 readState != null 守卫，此处 NPE")
                .doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 对照组：未注入 ReadPermissionChecker ⇒ 判据 fail-closed，行为与本批之前一致
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("对照组 · 未注入 ReadPermissionChecker（POJO/未装配）⇒ 判据恒 false，门禁维持拒绝")
    class FailClosedWithoutChecker {

        @Test
        @DisplayName("Edit · 锚内未读 + 未注入 ⇒ 仍拒 6（不静默放宽）")
        void editInsideAnchorWithoutCheckerStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("j.txt", "hello\n");
            assertInsideAnchor(f);
            // 不调 setReadPermissionChecker
            EditFileTool tool = new EditFileTool(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr =
                tool.validateInput(editCall(f.toString(), "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok()).as("未注入 ⇒ fail-closed，不得免门").isFalse();
            assertThat(vr.errorCode()).isEqualTo("6");
        }

        @Test
        @DisplayName("Write · 锚内未读 + 未注入 ⇒ 仍拒 2（不静默放宽）")
        void writeInsideAnchorWithoutCheckerStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("k.txt", "old\n");
            assertInsideAnchor(f);
            WriteFileTool tool = new WriteFileTool(new PathGuard(tmp));
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            Tool.ValidationResult vr = tool.validateInput(writeCall(f.toString(), "NEW").input(), ctx);

            assertThat(vr.ok()).as("未注入 ⇒ fail-closed，不得免门").isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");
        }

        @Test
        @DisplayName("Edit/Write · permissionContext == null（POJO ctx）⇒ 判据不抛、门禁维持拒绝")
        void nullPermissionContextNoThrow(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("l.txt", "hello\n");
            assertInsideAnchor(f);
            EditFileTool edit = editToolWithRealChecker(new PathGuard(tmp));
            WriteFileTool write = writeToolWithRealChecker(new PathGuard(tmp));
            // 3 参工厂：permissionContext = null
            ToolUseContext noPerm = ToolUseContext.of(
                UUID.randomUUID(), "gas-noperm",
                PermissionMode.DEFAULT);

            assertThatCode(() -> {
                Tool.ValidationResult ev = edit.validateInput(
                    editCall(f.toString(), "hello", "CHANGED").input(), noPerm);
                assertThat(ev.ok()).as("permCtx=null ⇒ fail-closed（不静默放宽）").isFalse();
                assertThat(ev.errorCode()).isEqualTo("6");

                Tool.ValidationResult wv = write.validateInput(
                    writeCall(f.toString(), "NEW").input(), noPerm);
                assertThat(wv.ok()).as("permCtx=null ⇒ fail-closed（不静默放宽）").isFalse();
                assertThat(wv.errorCode()).isEqualTo("2");
            }).doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (f) Notebook 门行为不变（本批未动它，用对照钉住）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(f) NotebookEditTool 门未动：即便目标落在锚内（免门判据本会为真）仍拒 errorCode=9")
    class NotebookGateUnchanged {

        @Test
        @DisplayName("Notebook · 锚内未读 .ipynb ⇒ 仍拒 9（本批未给它加免门）")
        void notebookInsideAnchorStillRejected(@TempDir Path tmp) throws Exception {
            Path f = probeInsideAnchor("m.ipynb", "{\"cells\":[]}\n");
            assertInsideAnchor(f);
            NotebookEditTool tool = new NotebookEditTool();
            ToolUseContext ctx = ctx(tmp, permCtx(PermissionMode.DEFAULT));

            ObjectNode input = JSON.createObjectNode();
            input.put("notebook_path", f.toString());
            input.put("cell_id", "cell-1");
            input.put("new_source", "x");
            input.put("edit_mode", "replace");

            Tool.ValidationResult vr = tool.validateInput(input, ctx);

            assertThat(vr.ok())
                .as("Notebook 门未加免门（本批 SCOPE 明令不动）;实际 errorCode=%s message=%s",
                    vr.errorCode(), vr.message())
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("9");
            assertThat(vr.message())
                .isEqualTo("File has not been read yet. Read it first before writing to it.");
        }
    }
}
