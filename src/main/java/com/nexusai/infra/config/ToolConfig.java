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
 * {@link #workspacePathGuard()} 返回<b>动态 workdir</b> PathGuard，其 supplier 每调用经
 * 统一入口 {@link CwdResolution#getCwd()} 解析当前会话 cwd（override ?? sessionCwd ??
 * boundProject ?? user.dir，对齐 CC pwd/getCwd 三层 + user.dir 兜底）。文件工具相对路径基准
 * 随会话 cd / worktree 入口动态变化，对齐 CC「cd 后下一条文件工具用新 cwd」。
 *
 * <p><b>DEL-01</b>：旧 bean {@code new PathGuard(Paths.get(System.getProperty("user.dir")))}
 * 把 workdir 冻结为 JVM 启动目录（恒 user.dir），与会话 projectRoot / worktree / bash cd 隔离
 * （G5）。本 bean 改动态 supplier 后直读 user.dir 逻辑删除（不留别名 / 双轨）。
 *
 * <p>所有 file 工具（Read / Write / Edit / Glob / Grep）共享同一个 PathGuard —— workspace 一致。
 */
@Configuration
public class ToolConfig {

    /**
     * workspace 路径防护 · 动态 workdir 经统一入口 {@link CwdResolution#getCwd()} 每调用取。
     *
     * <p>File 工具只能在该目录下操作，防止 LLM 误调读 /etc/passwd 等。workdir 随会话
     * projectRoot / worktree / bash cd 动态变化（对齐 CC getCwd per-call）。
     */
    @Bean
    public PathGuard workspacePathGuard() {
        // 每调用经统一入口解析当前会话 cwd（对齐 CC expandPath baseDir=getCwd() per-call · INV-1）
        return new PathGuard(() -> Path.of(CwdResolution.getCwd()));
    }
}
