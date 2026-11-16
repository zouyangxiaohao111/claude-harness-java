package com.nexusai.infra.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Node / Python 等外部运行时的只读探测。
 *
 * <p><b>WHY 必须实际执行 {@code --version}</b>：Windows 上
 * {@code %LOCALAPPDATA%\Microsoft\WindowsApps\python.exe} 是 <b>0 字节的 App Execution Alias</b>
 * （reparse point）——文件存在但执行会失败或弹微软商店。只看「PATH 里有这个文件」会误判为已安装。
 * 因此判据 = 文件可执行 + 实际执行退出码 0 + 输出非空。
 *
 * <p><b>只探测，不安装</b>：Node / Python 属于用户生态运行时，产品不内置、不静默下载。
 * 探测结果附安装提示（命令 + 官方地址），由用户或 Agent 经既有权限审批后自行安装。
 *
 * <p><b>.cmd/.bat 处理</b>：Windows 的 {@code npm.cmd} / {@code npx.cmd} 不能由
 * CreateProcess 直接执行（与 {@code GitProcessRunner} 的既有结论一致），需 {@code cmd /c} 包装。
 */
public final class RuntimeProbe {

    /** 单次 {@code --version} 超时（缺失时 ENOENT 立即返回；别名卡住时由此兜底）。 */
    private static final int PROBE_TIMEOUT_MS = 3_000;

    private RuntimeProbe() {
    }

    /**
     * 探测结果。
     *
     * @param name      逻辑名（node / python / uv / ...）
     * @param available 是否真的可用（可执行 + 退出码 0 + 输出非空）
     * @param path      命中的可执行文件路径（未命中时为 null）
     * @param version   版本输出原文（如 {@code v20.11.1} / {@code Python 3.12.1}）
     * @param error     未命中原因（available=true 时为 null）
     */
    public record Result(String name, boolean available, String path, String version, String error) {
    }

    /**
     * 探测一个运行时：按 PATH 顺序找候选文件名，逐个实际执行 {@code --version}。
     *
     * @param name         逻辑名
     * @param versionArg   版本参数（通常 {@code --version}）
     * @param exeNames     PATH 中查找的可执行文件名（Windows 可含 .exe/.cmd/.bat）
     * @return 首个可用候选；都不可用返回 available=false
     */
    public static Result probe(String name, String versionArg, String... exeNames) {
        List<Path> candidates = findOnPath(exeNames);
        if (candidates.isEmpty()) {
            return new Result(name, false, null, null, "not found on PATH");
        }
        for (Path candidate : candidates) {
            if (!isPlausibleExecutable(candidate)) {
                continue;
            }
            String version = exec(candidate, versionArg);
            if (version != null && !version.isBlank()) {
                return new Result(name, true, candidate.toString(), version.trim(), null);
            }
        }
        return new Result(name, false, candidates.get(0).toString(), null,
            "found on PATH but --version failed");
    }

    /**
     * 在 PATH 中按文件名查找（Windows 自动补 .exe/.cmd/.bat）。
     *
     * @param exeNames 候选文件名；null/空白项跳过
     * @return 命中的普通文件（按 PATH 顺序，可能多个同名目录）
     */
    static List<Path> findOnPath(String... exeNames) {
        List<Path> found = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return found;
        }
        boolean windows = isWindows();
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String name : exeNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                List<String> names = new ArrayList<>(2);
                names.add(name);
                if (windows && !hasExtension(name)) {
                    names.add(name + ".exe");
                    names.add(name + ".cmd");
                    names.add(name + ".bat");
                }
                for (String n : names) {
                    Path candidate = Path.of(dir, n);
                    if (Files.isRegularFile(candidate)) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found;
    }

    /**
     * 文件形态是否可能是真可执行文件。
     *
     * <p>关键判据：Windows 的 App Execution Alias 是 <b>0 字节</b>，直接排除，
     * 避免执行时弹微软商店（实测 python.exe 别名会打开商店）。
     */
    static boolean isPlausibleExecutable(Path candidate) {
        try {
            if (!Files.isRegularFile(candidate)) {
                return false;
            }
            if (Files.size(candidate) == 0) {
                return false;
            }
            return isWindows() || Files.isExecutable(candidate);
        } catch (IOException e) {
            return false;
        }
    }

    /** 实际执行 {@code <exe> <arg>}，成功返回一行输出；失败返回 null。 */
    static String exec(Path exe, String arg) {
        ProcessBuilder pb = new ProcessBuilder(commandOf(exe, arg));
        pb.redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return out.isEmpty() ? null : out.lines().findFirst().orElse(null);
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** .cmd/.bat 在 Windows 上必须经 cmd /c 包装（CreateProcess 不直接执行批处理）。 */
    private static List<String> commandOf(Path exe, String arg) {
        List<String> cmd = new ArrayList<>(4);
        String lower = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(exe.toString());
        if (arg != null && !arg.isBlank()) {
            cmd.add(arg);
        }
        return cmd;
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > sep + 1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
