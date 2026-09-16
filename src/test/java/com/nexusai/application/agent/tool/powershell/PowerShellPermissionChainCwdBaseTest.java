package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [裁定 #15 2026-09-14] 权限越界基准 cwd 缺失 ⇒ 宁问不放（⛔ 不回落进程 user.dir）。
 *
 * <p><b>WHY（意图，不是行为快照）</b>：{@code PowerShellPermissionChain} 的裸仓库守卫 / git-internal
 * 写守卫 / .git 写守卫 / step5 cd-to-CWD 过滤，全部以 cwd 作<b>权限越界基准</b>。该基准原先有三档兜底
 * （末档 {@code System.getProperty("user.dir")}），而本仓是<b>一 JVM 多会话 Web</b>：进程
 * {@code user.dir} = <b>服务器启动目录</b>，不是任何会话的项目根（CC 是单进程单会话，其
 * {@code getCwd()} 才等于会话目录 —— 形态像、语义反，见 {@code utils/cwd.ts:26-32} +
 * {@code bootstrap/state.ts:527-533}）。用服务器目录当基准 ⇒ 守卫在错基址上判「没写 git 内部」
 * ⇒ <b>静默放行</b>。故：基准取不到会话态来源 ⇒ 返回 null + ≥WARN，消费点一律走<b>安全方向</b>
 * （宁可 ask，绝不静默放行 / 不过宽过滤）。
 *
 * <p><b>本类的每条断言都配了反向实验（改坏 ⇒ 必红）</b>，配方见各用例 javadoc。
 * ⚠️ 前提自证：{@code ToolUseContext} 紧凑构造器会回填 {@code effectiveCwd}
 * （{@code ToolUseContext:451-453} 经 {@code CwdResolution.getCwd(sessionId)}），故正常构造的
 * {@code ctx.effectiveCwd()} <b>非 null</b> ⇒ 生产路径上基准<b>不缺失</b>，本类守的是
 * 「ctx == null / 未来装配退化」下的方向正确性（详见交付报告）。
 */
class PowerShellPermissionChainCwdBaseTest {

    // ════════════════════════════════════════════════════════════════════════
    // 桩：不启动 pwsh
    // ════════════════════════════════════════════════════════════════════════

    /** 可控 AST 桩（不启动 pwsh，返回预置 ParsedResult）。 */
    static final class StubAst extends PowerShellAstService {
        private PowerShellAstService.ParsedResult result;

        void stub(PowerShellAstService.ParsedResult r) {
            this.result = r;
        }

        @Override
        public PowerShellAstService.ParsedResult parseAst(String script) {
            return result;
        }
    }

    private static PowerShellAstService.CommandElement cmd(String name, String nameType, String... args) {
        List<String> elementTypes = new java.util.ArrayList<>();
        elementTypes.add("StringConstant");
        for (String ignored : args) {
            elementTypes.add("StringConstant");
        }
        String text = name + (args.length > 0 ? " " + String.join(" ", args) : "");
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
    }

    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new java.util.ArrayList<>();
        stmts.add(new PowerShellAstService.Statement("PipelineAst",
            cmds[0].text() + (cmds.length > 1 ? " ; " + cmds[1].text() : ""),
            List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false, false,
            false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
    }

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    /** 走 public 入口 checkPermissions（ctx 传 null = 无会话态可取 = 基准缺失）。 */
    private static PermissionResult checkWithNullCtx(PowerShellAstService.CommandElement... cmds) {
        StubAst ast = new StubAst();
        ast.stub(single(cmds));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        return tool.checkPermissions(input(cmds[0].text()), null);
    }

    /** ctx 显式带会话态 cwd（正常路径）。 */
    private static ToolUseContext ctxWithCwd(Path cwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", cwd);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. effectiveCwd：只接受会话态来源，取不到 ⇒ null（⛔ 不回落进程 user.dir）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("effectiveCwd：无会话态可取（ctx=null）⇒ 返回 null，⛔ 不回落进程 user.dir")
    void effectiveCwd_missingBase_returnsNull() {
        // WHY：原实现 ctx=null 时经 CwdResolution.getCwd(null) 落回其「无会话出口」= 进程 user.dir
        //（服务器启动目录）⇒ 权限越界基准锚到后端目录。⛔ 该值不得再作为基准（裁定 #15）。
        assertNull(PowerShellPermissionChain.effectiveCwd(null),
            "基准缺失必须返回 null（消费点据此宁问不放）；返回任何路径都等于拿进程目录冒充会话项目根");

        // 反向实验：把 System.getProperty("user.dir", ".") 兜底放回 effectiveCwd ⇒ 本断言必红
        // （实测：返回 D:\\...\\backend = 服务器启动目录，正是要消灭的那个值）。
    }

    @Test
    @DisplayName("effectiveCwd：ctx 带会话态快照（TUC.effectiveCwd）⇒ 原样返回（正常路径零变化）")
    void effectiveCwd_sessionSnapshot_usedAsIs(@TempDir Path tmp) {
        // WHY：正常路径必须行为不变 —— 基准仍取会话态快照，不得被本改造改成 ask 或 null。
        // ctxWithCwd 走的正是生产构造链（ToolUseContext.of → 紧凑构造器）。
        assertNotNull(ctxWithCwd(tmp).effectiveCwd(), "TUC 构造器保证会话态快照非 null（前提自证）");
        assertEqualsPath(tmp, PowerShellPermissionChain.effectiveCwd(ctxWithCwd(tmp)));

        // 反向实验：让 effectiveCwd 忽略 ctx.effectiveCwd() 直接返回 null ⇒ 本断言必红。
    }

    private static void assertEqualsPath(Path expected, Path actual) {
        assertNotNull(actual, "有会话态快照时不得返回 null");
        assertTrue(actual.toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize()),
            "应原样返回会话态快照，实测=" + actual);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. 裸仓库守卫：基准缺失 ⇒ 按「存在裸仓库指示」处理（宁问不放）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("裸仓库守卫：基准缺失（cwd=null）⇒ true（宁问不放），⛔ 不得返回 false")
    void bareRepoGuard_missingBase_isConservative(@TempDir Path tmp) throws Exception {
        // WHY：false = 「判不出来」被说成「不危险」⇒ 守卫形同不存在（静默放行面扩张）。
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo((Path) null),
            "基准缺失无法判定裸仓库 ⇒ 必须按危险侧处理（调用点据此 ask）");
        Files.createDirectories(tmp.resolve("objects"));
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(tmp),
            "对照：正常基准下的既有 OR 语义不变（objects/ 指示 ⇒ true）");
        assertFalse(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(Files.createDirectory(
            tmp.resolve("clean"))),
            "对照：正常基准下无指示 ⇒ false（基准存在时判定不变）");

        // 反向实验：把 cwd == null 分支改为 return false ⇒ 第一条断言必红。
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. step5 cd-to-CWD 过滤：基准缺失 ⇒ 不过滤（子命令留在待审批列表）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("step5 cd-to-CWD 过滤：基准缺失 ⇒ 不过滤（留在待审批列表），⛔ 不得当「无操作」放行")
    void cwdToSameCwdNoOp_missingBase_doesNotFilter() {
        // WHY：该判定的语义是「Set-Location 到<cwd 自己>」= 无操作。没有基准就没有「自己」可言 ⇒
        // 判不出来时必须留在待审批列表（不妨宽审批面）。
        assertFalse(PowerShellPermissionChain.isCwdToSameCwdNoOp(null, "/tmp/p15rel"),
            "基准缺失 ⇒ 不得判定为无操作（⛔ 返回 true 会把 cd 子命令从待审批列表里过滤掉）");

        // 对照：有基准时既有语义不变（cd 到当前目录 = 无操作 ⇒ 过滤）。
        Path cwd = Path.of("/tmp/p15rel");
        assertTrue(PowerShellPermissionChain.isCwdToSameCwdNoOp(cwd, "/tmp/p15rel"),
            "对照：cd 到基准目录本身 ⇒ 无操作（CC :1403-1404 resolve(cwd,target)===cwd）");
        assertFalse(PowerShellPermissionChain.isCwdToSameCwdNoOp(cwd, "/tmp/other"),
            "对照：cd 到别处 ⇒ 不是无操作");

        // 反向实验：把 cwd == null 分支改成返回 true（=「缺失即无操作」）⇒ 第一条断言必红；
        //   直接删掉 cwd != null 守卫 ⇒ 进入 resolveCwd 后 .equals(cwd) 对 null 调用 ⇒ NPE（红）。
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. ⭐ 硬指标：基准缺失 ⇒ git 守卫不再静态放行（整链，走 public checkPermissions）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("硬指标：基准缺失 + git 命令 ⇒ ask（原实现落回 user.dir ⇒ Passthrough 放行）")
    void gitGuards_missingBase_asksInsteadOfSilentlyAllowing() {
        // WHY：`git status` 在只读 allowlist 里，原实现基准落回进程 user.dir（后端目录）⇒ 三守卫在
        // 错基址上判「无裸仓库指示 / 未写 git 内部」⇒ 不作为 ⇒ 链继续 ⇒ Passthrough（放行）。
        // 这正是「拿服务器启动目录冒充会话项目根」的直接后果：守卫静默失效。
        PermissionResult r = checkWithNullCtx(cmd("git", "application", "status"));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "基准缺失时 git 三守卫无法判定 ⇒ 宁问不放（不得静默放行）实测=" + r);
        assertTrue(((PermissionResult.Ask) r).message().contains("cwd 基准缺失"),
            "ask 文案必须点明「基准缺失，无法判定是否安全」实测=" + ((PermissionResult.Ask) r).message());

        // 反向实验（唯一硬指标配方）：把 System.getProperty("user.dir", ".") 兜底放回 effectiveCwd
        //   ⇒ 实测变 Passthrough（静默放行）⇒ 本用例红。把 isCurrentDirectoryBareGitRepo 的
        //   cwd == null 分支改 return false 且删掉 check() 里的基准缺失 ask ⇒ 同样红。
    }

    @Test
    @DisplayName("硬指标·写类 cmdlet：基准缺失 ⇒ ask（.git 写守卫无法判定 ⇒ 宁问不放）")
    void gitWriteGuard_missingBase_asks() {
        PermissionResult r = checkWithNullCtx(cmd("Set-Content", "cmdlet", ".git/config"));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "写类 cmdlet 的路径参数需按 cwd 解析 ⇒ 基准缺失时 .git 写守卫无法判定 ⇒ 宁问不放 实测=" + r);
        assertTrue(((PermissionResult.Ask) r).message().contains("cwd 基准缺失"),
            "ask 文案必须点明基准缺失 实测=" + ((PermissionResult.Ask) r).message());

        // 反向实验：删掉 gitGuardsDependOnCwd 中「写类 cmdlet 有参数」的分支 ⇒ 本用例红（无 ask）。
    }

    @Test
    @DisplayName("反向守卫：与基准无关的只读命令（Get-Process）不因基准缺失被误 ask")
    void baseIndependentReadOnlyCommand_notFalseAsked() {
        // WHY：三守卫只扫「重定向目标 + 写类 cmdlet 路径参数」。两者皆空 ⇒ 任何基准下都返回 false
        // ⇒ 不得把这类命令也变成 ask（那是产品行为变更 = 大面积误 ask）。
        PermissionResult r = checkWithNullCtx(cmd("Get-Process", "cmdlet"));
        assertInstanceOf(PermissionResult.Allow.class, r,
            "与基准无关的只读命令必须行为不变（不因基准缺失被误 ask）实测=" + r);

        // 反向实验：把 check() 里的基准缺失 ask 改成无条件（cwd == null ⇒ 必 ask）⇒ 本用例红。
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. 路径校验：基准缺失 ⇒ 相对路径不按进程 user.dir 解析（fail-closed）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("基准缺失：相对路径不按进程 user.dir 解析 ⇒ 不判为工作目录内（fail-closed）")
    void relativePathWithMissingBase_isNotResolvedAgainstProcessUserDir() {
        // WHY：resolveAgainstCwd 原实现无条件 Paths.get(cwd.toString(), path) ⇒ cwd=null 时
        //   NPE（异常经 1c 降级）或（若有人补个 user.dir 兜底）把越界路径误判为工作目录内。
        PowerShellPathValidator.PathCheck pc =
            PowerShellPathValidator.validatePath("./p15-rel.txt", null, null, null, "read");
        assertFalse(pc.allowed(),
            "会话基准缺失 ⇒ 相对路径无法校验 ⇒ 不得放行（宁问不放）实测=" + pc.resolvedPath());
        assertFalse(pc.resolvedPath().startsWith(System.getProperty("user.dir")),
            "⛔ 不得回落进程 user.dir 解析相对路径 实测=" + pc.resolvedPath());

        // 反向实验：删掉 resolveAgainstCwd 的 cwd == null 分支 ⇒ NPE ⇒ 本用例红。
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. 不抛：基准缺失下整链不得因解析失败而异常逃逸
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("基准缺失：cd 复合命令整链不抛异常（过滤判定不得对 null 基准调用 resolveCwd）")
    void cdCompoundWithMissingBase_doesNotThrow() {
        assertDoesNotThrow(() -> checkWithNullCtx(cmd("Set-Location", "cmdlet", "/tmp/p15rel")),
            "基准缺失时不得进入 resolveCwd(null, target)（其 .equals(cwd) 会对 null 调用 ⇒ NPE）");

        // 反向实验：删掉 isCwdToSameCwdNoOp 的 cwd != null 守卫 ⇒ NPE ⇒ 本用例红。
    }
}
