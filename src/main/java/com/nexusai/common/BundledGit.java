package com.nexusai.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 随包 Git（Windows Git for Windows PortableGit）目录解析。
 *
 * <p><b>WHY</b>：新装 Windows 电脑没有 Git 时，{@code BashTool} 找不到 {@code bash.exe}
 * 会直接抛「No suitable shell found」（{@code ShellResolver.java}），且没有任何 cmd 回退。
 * PortableGit 随安装包分发后，本类负责把「随包 Git 在哪」收敛成单一出口，供
 * ShellResolver / CommandHookExecutor / GitProcessRunner / TerminalShellResolver 复用。
 *
 * <p><b>⛔ 为什么不是 MinGit</b>（2026-11-15 两版实测）：MinGit 2.45.2 与 2.56.1
 * 解压后都只有 {@code usr/bin/sh.exe}（dash），**没有 {@code bin/bash.exe}**
 * ⇒ BashTool 无法使用（{@code [[ ]]} / shopt / source 等 bash 语法全废）。
 * 必须用 PortableGit（含 GNU bash 5.x）。
 *
 * <p><b>解析顺序</b>（全部要求 {@code bin/bash.exe} 与 {@code cmd/git.exe} 同时存在，
 * 避免把半个目录当成可用运行时）：
 * <ol>
 *   <li>{@code NEXUSAI_BUNDLED_GIT_DIR}：Tauri {@code spawn_backend} 注入（最直接）；</li>
 *   <li>{@code NEXUSAI_INSTALL_DIR/backend/git}：安装器/壳注入的安装目录；
 *       dev 回退时 {@code NEXUSAI_INSTALL_DIR} = {@code front/src-tauri}，同样命中
 *       {@code front/src-tauri/backend/git}；</li>
 *   <li>{@code CLAUDE_CODE_GIT_BASH_PATH} 推导：{@code <root>/bin/bash.exe} → 上两级。
 *       这一条同时覆盖「用户/安装器把系统 Git 的 bash 路径写进 HKCU」的场景。</li>
 * </ol>
 *
 * <p><b>仅 Windows 生效</b>：macOS/Linux 自带可用的 shell 与 git，随包目录不存在，
 * 也不需要引入第二套运行时。
 */
public final class BundledGit {

    /** 壳注入的随包 Git 根目录（不含 bin/cmd 子目录）。 */
    public static final String DIR_ENV = "NEXUSAI_BUNDLED_GIT_DIR";

    /** 安装目录（后端目录的父目录），壳已有注入。 */
    public static final String INSTALL_DIR_ENV = "NEXUSAI_INSTALL_DIR";

    /** Git Bash 路径约定（与 ShellResolver / CommandHookExecutor 同源）。 */
    public static final String GIT_BASH_ENV = "CLAUDE_CODE_GIT_BASH_PATH";

    private BundledGit() {
    }

    /**
     * 解析可用的随包 Git 根目录。
     *
     * @return 同时含 {@code bin/bash.exe} 与 {@code cmd/git.exe} 的目录；非 Windows 恒为空
     */
    public static Optional<Path> dir() {
        if (!isWindows()) {
            return Optional.empty();
        }
        Path fromExplicit = validDir(env(DIR_ENV));
        if (fromExplicit != null) {
            return Optional.of(fromExplicit);
        }
        String installDir = env(INSTALL_DIR_ENV);
        if (installDir != null && !installDir.isBlank()) {
            Path fromInstall = validDir(Path.of(installDir.trim(), "backend", "git").toString());
            if (fromInstall != null) {
                return Optional.of(fromInstall);
            }
        }
        String gitBash = env(GIT_BASH_ENV);
        if (gitBash != null && !gitBash.isBlank()) {
            try {
                Path bash = Path.of(gitBash.trim());
                Path bin = bash.getParent();
                Path root = bin != null ? bin.getParent() : null;
                if (root != null) {
                    Path fromBash = validDir(root.toString());
                    if (fromBash != null) {
                        return Optional.of(fromBash);
                    }
                }
            } catch (RuntimeException ignored) {
                // 非法路径（含通配符等）⇒ 视为未配置
            }
        }
        return Optional.empty();
    }

    /** 随包 {@code bin/bash.exe}；不存在返回空。 */
    public static Optional<Path> bashExe() {
        return dir().map(d -> d.resolve("bin").resolve("bash.exe")).filter(Files::isRegularFile);
    }

    /** 随包 {@code cmd/git.exe}；不存在返回空。 */
    public static Optional<Path> gitExe() {
        return dir().map(d -> d.resolve("cmd").resolve("git.exe")).filter(Files::isRegularFile);
    }

    /**
     * 校验目录可用性：{@code bin/bash.exe} 与 {@code cmd/git.exe} 必须同时是普通文件。
     *
     * @param raw 目录原始值（可为 null/空白）
     * @return 规范化前的原始 Path；不可用返回 null
     */
    private static Path validDir(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path d = Path.of(raw.trim());
            if (Files.isRegularFile(d.resolve("bin").resolve("bash.exe"))
                && Files.isRegularFile(d.resolve("cmd").resolve("git.exe"))) {
                return d;
            }
        } catch (RuntimeException ignored) {
            // 非法路径 ⇒ 不可用
        }
        return null;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
