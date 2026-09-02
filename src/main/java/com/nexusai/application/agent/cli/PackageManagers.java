package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Package managers detection · 对齐 CC utils/nativeInstaller/packageManagers.ts (337 行).
 *
 * <p>L1: 8 种包管理器检测 — 通过 which/where 命令 + PATH 上是否存在可执行;
 *      上层决定 (返回值 Map).
 *
 * <p>L2 契约: 检测结果 Map<PackageManager, Boolean>;key 顺序定义如下.
 */
public final class PackageManagers {

    private static final Logger log = LoggerFactory.getLogger(PackageManagers.class);

    public enum PackageManager {
        HOMEBREW,
        WINGET,
        PACMAN,
        DEB,
        RPM,
        APK,
        MISE,
        ASDF
    }

    private final List<String> whichCommand;
    private final ProcessRunner runner;

    public PackageManagers() {
        this(isWindows() ? List.of("where") : List.of("which"));
    }

    public PackageManagers(List<String> whichCommand) {
        this(whichCommand, defaultRunner());
    }

    public PackageManagers(List<String> whichCommand, ProcessRunner runner) {
        this.whichCommand = whichCommand;
        this.runner = runner;
    }

    /** 执行一次 `which cmd` 检测;返回 exit code 0 表示存在. */
    public boolean isAvailable(String cmd) {
        try {
            int rc = runner.run(whichCommand, List.of(cmd));
            return rc == 0;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("which failed for {}: {}", cmd, e.getMessage());
            return false;
        }
    }

    /** 检测全部 8 种包管理器. */
    public Map<PackageManager, Boolean> detect() {
        Map<PackageManager, Boolean> map = new EnumMap<>(PackageManager.class);
        // 顺序与 TS 一致 — 不可改
        map.put(PackageManager.HOMEBREW,
            isWindows() ? false : (isAvailable("brew")));
        map.put(PackageManager.WINGET,
            isWindows() ? (isAvailable("winget")) : false);
        map.put(PackageManager.PACMAN,
            isWindows() ? false : (isAvailable("pacman")));
        map.put(PackageManager.DEB,
            isWindows() ? false : (isAvailable("dpkg") || isAvailable("apt")));
        map.put(PackageManager.RPM,
            isWindows() ? false : (isAvailable("rpm") || isAvailable("dnf") || isAvailable("yum")));
        map.put(PackageManager.APK,
            isWindows() ? false : (isAvailable("apk")));
        map.put(PackageManager.MISE,
            isWindows() ? (isAvailable("mise") || isAvailable("mise.exe")) : (isAvailable("mise")));
        map.put(PackageManager.ASDF,
            isWindows() ? (isAvailable("asdf") || isAvailable("asdf.exe")) : (isAvailable("asdf")));
        if (log.isDebugEnabled()) log.debug("PackageManagers detect result={}", map);
        return map;
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /** 命令执行抽象 — 便于注入与测试. */
    public interface ProcessRunner {
        int run(List<String> cmd, List<String> args) throws IOException, InterruptedException;
    }

    private static ProcessRunner defaultRunner() {
        return (cmd, args) -> {
            List<String> full = new java.util.ArrayList<>(cmd);
            full.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = is.readAllBytes();
            }
            return p.waitFor();
        };
    }
}
