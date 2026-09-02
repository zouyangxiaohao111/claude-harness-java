package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
        CwdResolution.clearCurrentOverride();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    /**
     * 生产 bean 形态的 PathGuard：supplier = {@code () -> Path.of(CwdResolution.getCwd())}。
     * 对齐 {@link com.nexusai.infra.config.ToolConfig#workspacePathGuard()} 生产 bean。
     */
    private static PathGuard productionLikeGuard() {
        return new PathGuard(() -> Path.of(CwdResolution.getCwd()));
    }

    @Test
    @DisplayName("场景①: override 非空 → workdir()/resolve() 返回 override 目录（非 user.dir）")
    void overrideLayerDrivesWorkdir(@TempDir Path overrideDir) throws Exception {
        // WHY: CC pwd() 优先取 override（cwd.ts:19-21）。文件工具 workdir 必须随 override 变，
        // 否则并发 agent 各自的 cwd 隔离失效。
        PathGuard guard = productionLikeGuard();
        Path expected = overrideDir.toRealPath();

        Path workdir = CwdResolution.runWithCwdOverride(overrideDir.toString(), guard::workdir);

        assertThat(workdir).isEqualTo(expected);
    }

    @Test
    @DisplayName("场景②: sessionCwd 非空 → workdir()/resolve(rel) 解析到会话 cwd 子路径（非 user.dir）")
    void sessionCwdLayerDrivesResolve(@TempDir Path sessionDir) throws Exception {
        // WHY: bash cd / worktree 入口写 SessionCwdHolder（合并存储 [Fix-R1]）。
        // 文件工具相对路径必须解析到 sessionCwd 下，而非恒 user.dir。
        // 生产链路：guard supplier 调 CwdResolution.getCwd() 无参重载 → 读 RequestContext.sessionId()
        // → 据此取 SessionCwdHolder 槽。故测试须 setSession 镜像生产（ChatService/LlmAgentLoop 入口设置）。
        String sid = "sess-wf1a-2";
        RequestContext.setSession(sid);
        SessionCwdHolder.set(sid, sessionDir.toString());
        PathGuard guard = productionLikeGuard();
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
        PathGuard guard = productionLikeGuard();
        Path d1 = dir1.toRealPath();
        Path d2 = dir2.toRealPath();

        String sid = "sess-wf1a-3";
        RequestContext.setSession(sid);
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
    @DisplayName("场景④: expandPath(raw, null) baseDir 缺省走统一入口 → 解析到 sessionCwd 子路径（非 user.dir · INV-6）")
    void expandPathFallbackUsesCwdResolution(@TempDir Path sessionDir) throws Exception {
        // WHY: CC expandPath baseDir ?? getCwd()。旧 Java 直读 user.dir（PathGuard.expandPath:119），
        // 会话 cwd 变化时 expandPath 兜底仍解析到 user.dir 下。本测试锁定兜底走统一入口。
        // expandPath 静态兜底调 CwdResolution.getCwd() 无参 → 读 RequestContext.sessionId()，
        // 须 setSession 镜像生产。
        String sid = "sess-wf1a-4";
        RequestContext.setSession(sid);
        SessionCwdHolder.set(sid, sessionDir.toString());

        String expanded = PathGuard.expandPath("rel.txt", null);

        assertThat(expanded)
            .as("expandPath baseDir 缺省必须经 CwdResolution 解析到 sessionCwd 子路径")
            .isEqualTo(sessionDir.toRealPath().resolve("rel.txt").normalize().toString());
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
        RequestContext.setSession(sid);
        SessionCwdHolder.set(sid, sessionDir.toString());
        Path rel = sessionDir.resolve("in-session-cwd.txt");
        Files.writeString(rel, "hello from session cwd\n");

        // 同一动态 guard 实例注入 ReadFileTool（镜像生产 bean ToolConfig.workspacePathGuard）
        PathGuard guard = productionLikeGuard();
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
