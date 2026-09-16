package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-AL-02 · read 路径 step5 edit-implies-read 对齐（CC {@code filesystem.ts:1124-1134}）。
 *
 * <p><b>对齐锚点（CC 真源，行号当次 read 自验）</b>：
 * <ol>
 *   <li>{@code checkReadPermissionForTool} step5（filesystem.ts:1124-1134）：
 *       read-specific deny/ask 规则之后、working-dir 检查之前调用
 *       {@code checkWritePermissionForTool(tool, input, ctx, pathsToCheck)}，
 *       {@code editResult.behavior === 'allow'} 时直接返回 editResult（edit 授权蕴含 read 授权）；
 *       deny/ask 结果<b>忽略</b>（继续后续步骤）——read-specific 规则优先，显式 read 限制不被覆盖。</li>
 *   <li>checkWritePermissionForTool 的 allow 来源（filesystem.ts:1205-1412）：
 *       edit 桶规则匹配仅限<b>带 ruleContent 的 content 规则</b>
 *       （matchingRuleForInput → getPatternsByRoot → getRuleByContentsForToolName 过滤
 *       {@code ruleContent !== undefined}，permissions.ts:380-388）——whole-tool 'Edit' allow
 *       <b>不</b>蕴含 read allow（CC 语义，测试锁定）。</li>
 * </ol>
 *
 * <p><b>夹具设计</b>（⚠️ 本节 2026-09-16 修正 —— 此前 javadoc 自陈的前提与实现<b>相反</b>，
 * 是本类三条用例长期恒红的真因）：
 * <ul>
 *   <li><b>工作目录锚 ≠ effectiveCwd</b>：权限链的「工作目录」锚取
 *       {@code CwdResolution.getOriginalCwdLayer(ctx.sessionId())}
 *       （{@code ReadPermissionChecker.isInWorkingDir}，对齐 CC
 *       {@code allWorkingDirectories = getOriginalCwd()}，filesystem.ts:666-674）；
 *       {@code ctx.effectiveCwd()} <b>只</b>作 {@code expandPath} 的相对路径 baseDir，
 *       <b>不</b>参与界内判定。
 *       单测环境里该锚恒 = 归一化进程 {@code user.dir}（= 本 maven 模块目录
 *       {@code <worktree>/backend}）：{@code NoDatabaseSessionProjectRootExtension} 对任意
 *       sessionId 答 {@code sessionlessEnvironment()} ⇒ 走无会话命名出口
 *       {@code getOriginalCwdLayerForNonSession()}。
 *       ⛔ 故旧夹具的相对路径 {@code target/pal02-*} 实际<b>落在锚内</b>
 *       （{@code <backend>/target/...}）⇒ step6 working-dir allow 直接放行，
 *       「工作目录外」场景根本没被复现。</li>
 *   <li><b>现夹具</b>：目标落在 {@code @TempDir}（{@code java.io.tmpdir} 之下，恒在
 *       {@code user.dir} 之外，满足「界外」），并令 <b>会话 cwd = 目标的父目录</b> ⇒
 *       目标在 effectiveCwd <b>之内</b>、在真实锚<b>之外</b>。于是：① 复现"工作目录外"场景；
 *       ② 若有人把锚改回读 {@code effectiveCwd}，目标会被判成界内 ⇒ 本类用例转红
 *       （锚的反向变异门；见 {@link #assertFixtureOutsideWorkingDirAnchor} 的前提自检）。</li>
 *   <li><b>可疑 Windows 模式</b>：{@code hasSuspiciousWindowsPathPattern} 的 8.3 短名正则
 *       是 {@code ~\d}，只命中含 {@code ~数字} 的路径段（如 {@code ADMINI~1.DES}）。
 *       本机实测 {@code java.io.tmpdir} = {@code C:\Users\WIN\AppData\Local\Temp}（无短名），
 *       故 {@code @TempDir} 不触发 step2 提前 ask；用例 1 断言 {@code Allow(Rule)} 本身即该前提
 *       的活证据（若命中 step2，返回值会是 Ask 而非 Allow）。
 *       ⛔ 旧 javadoc 写「本机 %TEMP% 含 8.3 短名」已不成立（实测无短名），故不再据此回避 temp。</li>
 *   <li><b>规则形态</b>：{@link #toGlob} 产出与生产链路同形（见该方法的 javadoc）。</li>
 *   <li>input 路径用绝对路径（{@code ReadFileTool.getPath} 直读 {@code file_path}）。</li>
 * </ul>
 *
 * <p><b>RED 双证</b>：本测试在 step5 实现前运行（{@code ReadPermissionChecker} 1 参构造器）
 * 必须失败（工作目录外 + Edit 内容 allow → 旧实现兜底 Ask/workingDir-Allow，而非
 * Allow(Rule)）；实现后改 2 参构造器（注入 {@link WritePermissionChecker}）转 GREEN。
 */
@DisplayName("P-AL-02 · read step5 edit-implies-read（CC filesystem.ts:1124-1134）")
class EditImpliesReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /**
     * 13 参工厂：显式 effectiveCwd。
     *
     * <p>⚠️ effectiveCwd <b>不是</b>权限链的工作目录锚（锚 =
     * {@code CwdResolution.getOriginalCwdLayer(ctx.sessionId())}，见类 javadoc）；
     * 它只作 {@code expandPath} 的相对路径 baseDir 与规则匹配基准。传 null 时由 TUC 构造器回填
     * {@code CwdResolution.getCwd(sessionId)}（{@code ToolUseContext.java:451-453}）。
     * ⛔ 旧 javadoc 写「null 会被 ToolUseContext 归一为进程 CWD（:311-312）」——行号与机制<b>都不对</b>：
     * 那是<b>按会话</b>解析（会 fail-loud），只在单测 sessionless 环境下才恒等于进程 {@code user.dir}。
     */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext allowCtx(Map<PermissionRuleSource, Set<PermissionRule>> allow) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());
    }

    private static ToolPermissionContext denyCtx(Map<PermissionRuleSource, Set<PermissionRule>> deny) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
    }

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 目标文件所在目录：会话 cwd（{@code @TempDir}）之下，而会话 cwd 自身在真实工作目录锚
     * （单测 = 进程 {@code user.dir}）之外 —— 见类 javadoc「夹具设计」。
     *
     * @param cwd 会话 cwd（{@code @TempDir}，同时作为 {@code ctx.effectiveCwd()}）
     */
    private static Path targetDir(Path cwd) {
        return cwd.resolve("pal02-" + rand());
    }

    /**
     * 夹具前提自检（fail-loud）· 防「夹具前提与实现不符 ⇒ 用例静默失去鉴别力」重演。
     *
     * <p>旧夹具正是栽在这里：目标写成相对路径 {@code target/pal02-*}，误以为它在工作目录锚之外，
     * 实际落在 {@code <user.dir>/target/...} = 锚内 ⇒ 三条用例的 Ask/Allow(Rule) 断言全部落空。
     * 本方法把「目标必须在真实锚之外」与「目标必须在 effectiveCwd 之内」两条前提写成断言
     * （对齐规则十二「显式失败」：前提不成立必须红，不得静默跳过）。
     *
     * @param target 目标文件路径
     * @param cwd    会话 cwd（{@code ctx.effectiveCwd()}）
     */
    private static void assertFixtureOutsideWorkingDirAnchor(Path target, Path cwd) {
        Path anchor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path absTarget = target.toAbsolutePath().normalize();
        assertThat(absTarget.startsWith(anchor))
            .as("夹具前提①：目标 %s 必须在工作目录锚（单测 = 进程 user.dir = %s）之外", absTarget, anchor)
            .isFalse();
        assertThat(absTarget.startsWith(cwd.toAbsolutePath().normalize()))
            .as("夹具前提②：目标 %s 必须在会话 cwd %s 之内（锚若=effectiveCwd ⇒ 判界内 ⇒ 用例转红）",
                absTarget, cwd)
            .isTrue();
    }

    /**
     * 规则内容（glob）· 与生产链路 <b>同形</b>：
     * {@link PermissionUpdates#createReadRuleSuggestion} 的 ①{@code toPosixPath}（Windows
     * 盘符 → {@code /c/…}，{@code CommandHookExecutor.windowsPathToPosixPath}）
     * + ②绝对路径前置 {@code "/"}（PermissionUpdates.java:130-134）⇒ Windows 得
     * {@code //c/Users/…/**}、POSIX 得 {@code //tmp/…/**}。
     *
     * <p>该形态是 {@code RuleQuery.matchesPathRuleRootRelative} 的合法根前缀之一
     * （{@code //…} 文件系统/盘符根）。⛔ 旧实现 {@code dir.toString().replace('\\','/') + "/**"}
     * 产出 {@code C:/Users/…/**}（盘符+正斜杠）：既非 {@code //} 根、也非 {@code ~}、{@code /}
     * 或无前缀 ⇒ 落进「无前缀 ⇒ root=cwd」分支 ⇒ 相对模式以盘符开头永不匹配（规则静默失效）。
     */
    private static String toGlob(Path dir) {
        String abs = dir.toAbsolutePath().normalize().toString();
        String posix = CommandHookExecutor.isWindows()
            ? CommandHookExecutor.windowsPathToPosixPath(abs)
            : abs.replace('\\', '/');
        return "/" + posix + "/**";
    }

    @Test
    @DisplayName("Edit 内容 allow 规则（工作目录外路径）→ read 直接 allow，不再兜底 ask")
    void editContentAllow_impliesReadAllow(@TempDir Path cwd) {
        Path workspace = targetDir(cwd);
        assertFixtureOutsideWorkingDirAnchor(workspace, cwd);
        PermissionRule allowRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent("Edit", toGlob(workspace)));
        ToolUseContext ctx = ctx(allowCtx(Map.of(PermissionRuleSource.SESSION, Set.of(allowRule))), cwd);
        // GREEN 阶段：2 参构造器（step5 已实现，注入 WritePermissionChecker）→ 应 Allow(Rule)
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: edit 内容 allow 蕴含 read allow（filesystem.ts:1132-1134 editResult.behavior==='allow' → return editResult）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("CC: 返回原 editResult（decisionReason=Rule(edit allow rule)，非 workingDir/Other）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("Edit whole-tool allow（无 ruleContent）→ 不蕴含 read allow（CC content-only 匹配）")
    void editWholeToolAllow_doesNotImplyReadAllow(@TempDir Path cwd) {
        Path workspace = targetDir(cwd);
        assertFixtureOutsideWorkingDirAnchor(workspace, cwd);
        PermissionRule allowRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Edit"));
        ToolUseContext ctx = ctx(allowCtx(Map.of(PermissionRuleSource.SESSION, Set.of(allowRule))), cwd);
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: matchingRuleForInput 仅匹配 ruleContent 非空规则（permissions.ts:380-388）→ whole-tool Edit allow 不命中 step5，工作目录外仍 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Edit deny 规则 → read 不受阻（CC: step5 仅消费 allow，deny/ask 忽略继续）")
    void editContentDeny_doesNotBlockRead(@TempDir Path cwd) {
        Path workspace = targetDir(cwd);
        assertFixtureOutsideWorkingDirAnchor(workspace, cwd);
        PermissionRule denyRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Edit", toGlob(workspace)));
        ToolUseContext ctx = ctx(denyCtx(Map.of(PermissionRuleSource.SESSION, Set.of(denyRule))), cwd);
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: read step5 忽略 checkWritePermissionForTool 的 deny（filesystem.ts:1132 仅 behavior==='allow' 返回）→ 工作目录外兜底 Ask，而非 Deny")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(result)
            .as("edit deny 不得把 read 拒死（显式 read deny 规则才拒绝 read）")
            .isNotInstanceOf(PermissionResult.Deny.class);
    }
}
