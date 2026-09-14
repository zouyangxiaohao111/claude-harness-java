package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-1A · 文件操作域 cwd 统一入口接线验证 · 对齐 CC expandPath(baseDir=getCwd()) 每调用取（INV-1）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * CC 文件工具相对路径基准 = {@code expandPath(path, baseDir)} 的 {@code baseDir} 默认
 * {@code getCwd()}（CC {@code utils/path.ts:32-35}），<b>每调用取</b>当前会话 cwd。
 * 旧 Java 端 {@link PathGuard} 把 workdir 冻结为 {@code user.dir}（{@code ToolConfig:29}），
 * 与会话 projectRoot / worktree / bash cd 隔离（G5），导致 cd 或进 worktree 后文件工具仍用
 * 旧 cwd。本测试锁定<b>每调用经统一入口</b>取 cwd 的不变量：若有人把 PathGuard 退回
 * 构造时冻结字段，或 expandPath baseDir 兜底退回直读 user.dir，本测试即报错。
 *
 * <p>场景对应 AC-1 三场景（worktree/会话cwd/绑定项目 → 非恒 user.dir）+ AC-2 每调用取值。
 */
@DisplayName("[WF-1A] PathGuard 动态 cwd + expandPath 兜底经统一入口（INV-1）")
class PathGuardCwdResolutionTest {

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    /**
     * 生产 bean 形态的 PathGuard：supplier = {@code () -> Path.of(CwdResolution.getCwd(sessionId))}。
     * 对齐 {@link com.nexusai.infra.config.ToolConfig#workspacePathGuard()} 生产 bean。
     *
     * <p>[批 3c] {@code CwdResolution.getCwd} 已从 0 参改为**显式 sessionId 形参**（原 0 参内部读
     * 裸 MDC 会话槽，该槽已整类删除）⇒ 测试须把本用例自己的会话变量显式传进 supplier。
     */
    private static PathGuard productionLikeGuard(String sessionId) {
        return new PathGuard(() -> Path.of(CwdResolution.getCwd(sessionId)));
    }

    @Test
    @DisplayName("场景①: boundProject 层 → workdir()/resolve() 返回绑定项目根（非 user.dir）")
    void boundProjectLayerDrivesWorkdir(@TempDir Path projectDir) throws Exception {
        // WHY: 文件工具 workdir 必须能随「会话绑定的项目根」变，否则绑定项目后文件工具仍用
        //   JVM 启动目录（跨项目污染）。
        // [S2 · F-07 2026-09-14] 原装置 = CwdResolution.runWithCwdOverride(overrideDir, guard::workdir)
        //   （override ThreadLocal 层，对齐 CC cwd.ts:19-21）。该通道已按用户裁定 #8 **整条删除**
        //   ⇒ 改锚到**生产真路径**：SessionProjectRoot 绑定层（bind / resolveSessionProjectRoot
        //   写入的那层，getCwd 的 L2）。
        String sid = "sess-wf1a-1";
        SessionProjectRoot.setForSession(sid, projectDir.toString());
        PathGuard guard = productionLikeGuard(sid);
        Path expected = projectDir.toRealPath();

        assertThat(guard.workdir())
            .as("workdir() 必须取会话绑定项目根（L2），而非进程 user.dir")
            .isEqualTo(expected)
            .isNotEqualTo(Path.of(System.getProperty("user.dir")).toRealPath());
        assertThat(guard.resolve("rel.txt"))
            .as("resolve(rel) 必须落在绑定项目根下")
            .isEqualTo(expected.resolve("rel.txt").normalize());
    }

    @Test
    @DisplayName("场景②: sessionCwd 非空 → workdir()/resolve(rel) 解析到会话 cwd 子路径（非 user.dir）")
    void sessionCwdLayerDrivesResolve(@TempDir Path sessionDir) throws Exception {
        // WHY: bash cd / worktree 入口写 SessionCwdHolder（合并存储 [Fix-R1]）。
        // 文件工具相对路径必须解析到 sessionCwd 下，而非恒 user.dir。
        // 生产链路：guard supplier 调 CwdResolution.getCwd(sessionId) 取 SessionCwdHolder 槽。
        // [批 3c] 会话不再经 ambient 槽传递 ⇒ 测试把会话变量显式传进 supplier（原 setSession 装置已删）。
        String sid = "sess-wf1a-2";
        SessionCwdHolder.set(sid, sessionDir.toString());
        PathGuard guard = productionLikeGuard(sid);
        Path expected = sessionDir.toRealPath().resolve("rel.txt").normalize();

        // 无 override → 走 sessionCwd 层
        Path out = guard.resolve("rel.txt");

        assertThat(out).isEqualTo(expected);
    }

    @Test
    @DisplayName("场景③: cd 后下一条 workdir() 用新 cwd（每调用取 · INV-1 / AC-2）")
    void perCallResolutionAfterCdChange(@TempDir Path dir1, @TempDir Path dir2) throws Exception {
        // WHY: CC STATE.cwd 单一可变，bash cd 后 setCwd 写新值，下一次 pwd() 取新值。
        // 若 PathGuard 把 workdir 冻结为构造时快照，cd 后仍返回旧值 → 违反 INV-2。
        // 本测试用同一 PathGuard 实例（单例 bean 形态）在两次 sessionCwd 变更间复用。
        Path d1 = dir1.toRealPath();
        Path d2 = dir2.toRealPath();

        String sid = "sess-wf1a-3";
        PathGuard guard = productionLikeGuard(sid);
        SessionCwdHolder.set(sid, d1.toString());
        Path first = guard.workdir();

        // 模拟 bash cd 切到 d2（WF-2A 接线后由 BashTool 调 SessionCwdHolder.set）
        SessionCwdHolder.set(sid, d2.toString());
        Path second = guard.workdir();

        assertThat(first).isEqualTo(d1);
        assertThat(second).isEqualTo(d2);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("场景④: expandPath(raw, null) baseDir 缺省走统一入口「无会话」出口（不读会话层 · INV-6）")
    void expandPathFallbackUsesNonSessionExit(@TempDir Path sessionDir) throws Exception {
        // WHY: CC expandPath baseDir ?? getCwd()。旧 Java 直读 user.dir（PathGuard.expandPath:119），
        //   会话 cwd 变化时兜底仍解析到 user.dir 下。本测试锁定兜底走统一入口。
        //
        // [批 3c] 语义已变（已登记待裁定）：{@code expandPath(raw, null)} 是**静态**入口，没有 sessionId
        //   形参，新实现显式按「无会话」解析 {@code CwdResolution.getCwdForNonSession()}（仅进程
        //   user.dir 层）—— 即「静态兜底不再能解析到某个会话的 sessionCwd」这一前提本身随会话显式化
        //   而消失；原「解析到 sessionCwd 子路径」的断言形态已无法构造。
        // [S2 · F-07 2026-09-14] 原装置再用 {@code runWithCwdOverride} 证明「兜底确实经统一入口（而非
        //   直读 user.dir）」。override 通道已按用户裁定 #8 **整条删除** ⇒ 「经统一入口 vs 直读 user.dir」
        //   在本缝上**已不可观测**（两者同值）。改锚为「真值断言 + 反向对照」：断言真值，并同时证明
        //   任何会话的 sessionCwd 层都不得被这个静态入口读到。
        String sid = "sess-wf1a-4";
        SessionCwdHolder.set(sid, sessionDir.toString());

        String expanded = PathGuard.expandPath("rel.txt", null);

        assertThat(expanded)
            .as("expandPath baseDir 缺省 = 无会话出口（进程 user.dir）下的 rel.txt")
            .isEqualTo(Path.of(CwdResolution.getCwdForNonSession(), "rel.txt").normalize().toString())
            .as("[反向对照] 不得读到任何会话的 sessionCwd（expandPath 静态入口无会话形参）")
            .isNotEqualTo(sessionDir.toRealPath().resolve("rel.txt").normalize().toString());
    }

    /**
     * [S2 · F-10 验证 #1 · 用户裁定 #6 (B)] 解析器抛异常 ⇒ 会话感知重载<b>冒泡</b>（⛔ 不回落 user.dir）。
     *
     * <p><b>WHY（规则九 · 意图）</b>：产生侧（CwdResolution）对「有会话却解析不出项目根」是 fail-loud
     * 抛；消费侧原实现把它 catch 成一条 WARN 后继续走 ⇒ 最终回落进程 {@code user.dir} ⇒
     * 用户裁定 #7 在「相对路径校验」这条最热路径上被完全旁路（文件工具/权限判定锚到后端启动目录，
     * 用户只看到一条 WARN）。本用例钉住「消费侧默认不吞」。
     *
     * <p><b>RED（反向实验 · 有鉴别力）</b>：把 {@code PathGuard.sessionWorkdir} 的 catch 改回
     * 「log.warn 后继续走」⇒ 本用例红（返回 user.dir 而不抛）。
     *
     * <p><b>正反对照（同一用例两臂）</b>：同一 guard 的解析器<b>不抛</b>时照常解析出该目录 ⇒
     * 证明「抛」不是「guard 恒抛」。
     */
    @Test
    @DisplayName("[S2 F-10] 解析器抛异常 ⇒ workdir(sessionId) 冒泡（fail-loud，⛔ 不回落 user.dir）")
    void sessionWorkdirResolverThrowing_propagatesInsteadOfFallingBack(@TempDir Path okDir) throws Exception {
        PathGuard guard = new PathGuard(() -> Path.of(System.getProperty("user.dir")));
        guard.setSessionWorkdirResolver(sid -> {
            throw new IllegalStateException("bound project root deleted");
        });

        Throwable thrown = catchThrowable(() -> guard.workdir("sess-f10-throw"));
        assertThat(thrown)
            .as("解析器异常必须冒泡到调用方/REST 边界（⛔ 不得静默回落 user.dir）")
            .isInstanceOf(com.nexusai.infra.exception.UnresolvedProjectRootException.class)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sess-f10-throw");
        assertThat(thrown.getMessage())
            .as("消息不得暗示任何回落（回落是本次修复要消灭的行为）")
            .doesNotContain("回落");

        // 正向对照：同一 guard 换成不抛的解析器 ⇒ 照常解析（证明上面的红不是「guard 恒抛」）
        guard.setSessionWorkdirResolver(sid -> okDir);
        assertThat(guard.workdir("sess-f10-ok"))
            .as("解析器正常时照常解析该会话 cwd")
            .isEqualTo(okDir.toRealPath());
    }

    /**
     * [S2 · F-10 验证 #1 第二臂] 解析器返回 {@code null} ⇒ 抛（注入方违约，⛔ 不回落 user.dir）。
     *
     * <p><b>WHY</b>：默认解析器 {@code CwdResolution.getCwd} 恒非 null ⇒ {@code null} 只可能来自
     * 测试注入。原实现把它回落成进程 user.dir（旁路 fail-loud），现按「注入方违约」抛出。
     *
     * <p><b>RED（反向实验）</b>：把 {@code wd == null} 分支改回 {@code return currentWorkdir();} ⇒ 本用例红。
     */
    @Test
    @DisplayName("[S2 F-10] 解析器返回 null ⇒ 抛（违约，⛔ 不回落 user.dir）")
    void sessionWorkdirResolverReturningNull_throws() {
        PathGuard guard = new PathGuard(() -> Path.of(System.getProperty("user.dir")));
        guard.setSessionWorkdirResolver(sid -> null);

        assertThatThrownBy(() -> guard.workdir("sess-f10-null"))
            .as("解析器返回 null = 违反契约（默认解析器恒非 null）⇒ 必须抛，⛔ 不回落")
            .isInstanceOf(com.nexusai.infra.exception.UnresolvedProjectRootException.class)
            .hasMessageContaining("sess-f10-null");
        // 会话感知 resolve 同走本路径（不是只有 workdir 一个入口）
        assertThatThrownBy(() -> guard.resolve("sess-f10-null", "rel.txt"))
            .as("resolve(sessionId, rel) 走同一 sessionWorkdir ⇒ 同样冒泡")
            .isInstanceOf(com.nexusai.infra.exception.UnresolvedProjectRootException.class);
    }

    @Test
    @DisplayName("场景⑤: 文件工具集成——ReadFileTool.execute 用动态 PathGuard 读会话 cwd 下文件（非 user.dir）")
    void readFileToolResolvesAgainstSessionCwd(@TempDir Path sessionDir) throws Exception {
        // WHY（端到端意图 · CLAUDE.md 规则九）: CC 文件工具相对路径基准 = expandPath baseDir=getCwd()
        // per-call（INV-1）。旧实现 ReadFileTool 注入固定 user.dir 的 PathGuard → 读 sessionDir/rel.txt
        // 时 guard.resolve("rel.txt") 解析到 user.dir/rel.txt → 文件不存在 → execute 返回 "File not found"。
        // 新实现用动态 supplier → guard.resolve 经 CwdResolution 取 sessionCwd → 能读到 sessionDir 下文件。
        // 本测试不单测 guard.resolve（场景②③④已覆盖），而是走 ReadFileTool.execute 真实读取链
        // （:688 file = guard.resolve(relPath) → :803 dispatchText → Files.readString），锁定
        // 「文件工具用动态 cwd 读到会话目录下文件」这一端到端不变量：若有人把 ReadFileTool 注入退回
        // 固定 user.dir 的 PathGuard，或 PathGuard 退回构造时冻结，execute 会返回 File-not-found → 测试报错。
        String sid = "sess-wf1a-5";
        SessionCwdHolder.set(sid, sessionDir.toString());
        Path rel = sessionDir.resolve("in-session-cwd.txt");
        Files.writeString(rel, "hello from session cwd\n");

        // 同一动态 guard 实例注入 ReadFileTool（镜像生产 bean ToolConfig.workspacePathGuard）
        PathGuard guard = productionLikeGuard(sid);
        ReadFileTool tool = new ReadFileTool(guard);

        // 构造 read 调用（file_path 为相对路径 → 必须经 guard.resolve 解析到 sessionCwd）
        ObjectMapper json = new ObjectMapper();
        ObjectNode input = json.createObjectNode().put("file_path", "in-session-cwd.txt");
        ToolUseBlock call = new ToolUseBlock("call-wf1a-5", "read_file", input);

        // 无 ctx 调用：跳过 dedup（executeInternal ctx=null 分支），直接走 guard.resolve + 读取链
        ToolResult result = (ToolResult) tool.execute(call);

        // 提取渲染后文本内容（mapToToolResultBlockParam content 字段）
        Object content = tool.mapToToolResultBlockParam(result, "call-wf1a-5", false).content();
        assertThat(content).isInstanceOf(String.class);
        assertThat((String) content)
            .as("ReadFileTool.execute 用动态 cwd 须读到 sessionDir 下文件内容（非 user.dir）")
            .contains("hello from session cwd");
    }
}
