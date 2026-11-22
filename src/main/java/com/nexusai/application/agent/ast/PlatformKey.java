package com.nexusai.application.agent.ast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 当前平台标识 · ast-grep 二进制资源目录名（{@code <arch>-<os>}）。
 *
 * <p>本类与 {@code RipgrepRunner.resolveOs/resolveArch}（{@code tool/impl} 包内可见）逻辑相同，
 * 但那边是包级私有 + 跨包不可见；为避免把 rg 的 API 放宽成公共契约，这里自持一份 30 行实现
 * （单一职责：给 ast-grep 资源定位用）。两侧映射必须保持同源：
 * <ul>
 *   <li>{@code os.name} → {@code win32 / darwin / linux}（与 CC {@code process.platform} 同名）</li>
 *   <li>{@code os.arch} → {@code x64 / arm64}（与 CC {@code process.arch} 同名；未知回退 x64 + WARN）</li>
 * </ul>
 *
 * <p>资源矩阵与 {@code src/main/resources/rg/} 相同：{x64,arm64}-{win32,darwin,linux}。
 * 注意 Windows 上的可执行文件名是 {@code ast-grep.exe}，其余平台是 {@code ast-grep}。
 */
public final class PlatformKey {

    private static final Logger log = LoggerFactory.getLogger(PlatformKey.class);

    private PlatformKey() {}

    /** 平台目录名 · 如 {@code x64-win32}（资源目录与 tmp 缓存目录同名）。 */
    public static String dirName() {
        return resolveArch() + "-" + resolveOs();
    }

    /** 当前是否 Windows（决定可执行文件名 / 是否需要执行位）。 */
    public static boolean isWindows() {
        return "win32".equals(resolveOs());
    }

    /** 可执行文件名 · Windows {@code ast-grep.exe}，其余 {@code ast-grep}。 */
    public static String exeName() {
        return isWindows() ? "ast-grep.exe" : "ast-grep";
    }

    /** classpath 上的 gzip 资源路径 · 如 {@code /ast-grep/x64-win32/ast-grep.exe.gz}。 */
    public static String gzResource() {
        return "/ast-grep/" + dirName() + "/" + exeName() + ".gz";
    }

    /** os.name → win32/linux/darwin（与 CC process.platform 同名）。 */
    static String resolveOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    /** os.arch → x64/arm64（未知回退 x64；真缺二进制时由 available() 判 false）。 */
    static String resolveArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        log.warn("PlatformKey: 未知 os.arch='{}'，回退 x64（无对应二进制时 AST 工具自动不可用）", arch);
        return "x64";
    }
}
