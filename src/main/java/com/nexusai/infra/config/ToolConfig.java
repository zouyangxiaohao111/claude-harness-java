package com.nexusai.infra.config;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tool.PathGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 工具层 Spring 配置 · 给文件类工具提供 PathGuard bean。
 *
 * <p><b>对齐 CC expandPath(baseDir=getCwd()) 每调用取（INV-1）</b>：
 * {@link #workspacePathGuard()} 返回<b>动态 workdir</b> PathGuard。文件工具相对路径基准随会话
 * cd / worktree 入口动态变化，对齐 CC「cd 后下一条文件工具用新 cwd」。
 *
 * <p><b>DEL-01</b>：旧 bean {@code new PathGuard(Paths.get(System.getProperty("user.dir")))}
 * 把 workdir 冻结为 JVM 启动目录（恒 user.dir），与会话 projectRoot / worktree / bash cd 隔离
 * （G5）。本 bean 改动态 supplier 后直读 user.dir 逻辑删除（不留别名 / 双轨）。
 *
 * <p><b>会话 cwd 由调用方显式传入</b>：{@link PathGuard} 的会话感知重载
 * {@code guard.resolve(ctx.sessionId(), path)} / {@code guard.workdir(ctx.sessionId())} 以会话 cwd
 * 为相对路径基准（{@link PathGuard} 内部默认解析器 = {@code CwdResolution.getCwd(sessionId)}，
 * 每调用取）。文件工具（Read / Write / Edit / Glob / Grep / NotebookEdit）均持
 * {@code ToolUseContext}，<b>必须</b>走该重载。
 *
 * <p><b>本 bean 的 supplier 仅是无会话兜底</b>：{@link PathGuard} 的动态 supplier 是
 * {@code Supplier<Path>}（无 sessionId 形参），且本 bean 为进程级单例（所有会话共享同一实例），
 * 故它只能提供「无会话」基准（{@code CwdResolution.getCwdForNonSession()} → 仅 override / 进程 user.dir 层）；
 * 无会话调用方（测试 / 静态工具）才命中它。此处<b>不再</b>启动期 WARN —— 缺会话是调用方的事
 * （{@link PathGuard} 在无会话入参时按需 WARN 一次）。
 *
 * <p>所有 file 工具（Read / Write / Edit / Glob / Grep）共享同一个 PathGuard —— workspace 一致。
 */
@Configuration
public class ToolConfig {

    /**
     * workspace 路径防护 · 动态 workdir 每调用取（对齐 CC getCwd per-call）。
     *
     * <p>File 工具的相对路径基准（无会话时兜底）。会话 cwd 由调用方经
     * {@code guard.resolve(ctx.sessionId(), …)} 显式传入，不经本 supplier。
     */
    @Bean
    public PathGuard workspacePathGuard() {
        // 无会话兜底：仅当调用方未传 sessionId（测试 / 静态工具）时命中。
        // 会话 cwd 的解析器是 PathGuard 内置默认（CwdResolution.getCwd(sessionId)），无需在此注入。
        return new PathGuard(() -> Path.of(CwdResolution.getCwdForNonSession()));
    }
}
