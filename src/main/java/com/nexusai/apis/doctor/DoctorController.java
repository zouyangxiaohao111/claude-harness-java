package com.nexusai.apis.doctor;

import com.nexusai.common.BundledGit;
import com.nexusai.infra.util.GitAvailabilityChecker;
import com.nexusai.infra.util.RuntimeInstallHints;
import com.nexusai.infra.util.RuntimeProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Doctor REST 端点 · 对齐 CC /doctor 命令.
 *
 * <p>FIX-R10-1: 真调 {@link GitAvailabilityChecker} 跑诊断.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>GET /api/v1/doctor - 运行 doctor 诊断 (git / path / java / 内存)</li>
 *   <li>GET /api/v1/doctor/context-warnings - 上下文警告</li>
 *   <li>GET /api/v1/doctor/diagnostics - 详细诊断报告</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/doctor")
public class DoctorController {

    private static final Logger log = LoggerFactory.getLogger(DoctorController.class);

    @GetMapping
    public Map<String, Object> diagnose() {
        log.info("[DoctorController] diagnose invoked");
        Map<String, Object> git = checkGit();
        Map<String, Object> path = checkPath();
        Map<String, Object> java = checkJava();
        Map<String, Object> memory = checkMemory();

        boolean allOk = Boolean.TRUE.equals(git.get("available"))
                && Boolean.TRUE.equals(path.get("writable"));

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(Map.of("name", "git_installed", "status",
                Boolean.TRUE.equals(git.get("available")) ? "pass" : "fail",
                "detail", git));
        checks.add(Map.of("name", "path_writable", "status",
                Boolean.TRUE.equals(path.get("writable")) ? "pass" : "fail",
                "detail", path));
        checks.add(Map.of("name", "java_version", "status",
                java.get("version") != null ? "pass" : "fail", "detail", java));
        checks.add(Map.of("name", "memory_ok", "status",
                Boolean.TRUE.equals(memory.get("ok")) ? "pass" : "warn",
                "detail", memory));

        List<String> warnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(git.get("available"))) {
            warnings.add("git is not available on PATH");
        }
        if (!Boolean.TRUE.equals(path.get("writable"))) {
            warnings.add("user.dir is not writable: " + path.get("path"));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", allOk ? "ok" : "degraded");
        response.put("checks", checks);
        response.put("warnings", warnings);
        return response;
    }

    @GetMapping("/context-warnings")
    public List<Map<String, Object>> contextWarnings() {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> path = checkPath();
        if (!Boolean.TRUE.equals(path.get("writable"))) {
            warnings.add(Map.of(
                    "type", "path_not_writable",
                    "severity", "warning",
                    "message", "user.dir is not writable"));
        }
        Map<String, Object> git = checkGit();
        if (!Boolean.TRUE.equals(git.get("available"))) {
            warnings.add(Map.of(
                    "type", "git_unavailable",
                    "severity", "warning",
                    "message", "git is not available on PATH"));
        }
        return warnings;
    }

    /**
     * Node / Python 等外部运行时探测（只读，不安装）。
     *
     * <p>WHY 单独端点：MCP 的 stdio server 常用 {@code npx} / {@code uvx} 启动，
     * 缺运行时表现为「MCP 起不来」而不是「环境缺东西」。这里把可用性、路径、版本与
     * 安装提示一次性给前端，避免用户面对裸 {@code CreateProcess error=2}。
     *
     * <p>判据见 {@link RuntimeProbe}：实际执行 {@code --version}，0 字节的 Windows
     * Store 别名会被排除（不执行、不弹商店）。
     */
    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> runtimes = new LinkedHashMap<>();
        runtimes.put("node", withHint(RuntimeProbe.probe("node", "--version", "node")));
        runtimes.put("npm", withHint(RuntimeProbe.probe("npm", "--version", "npm")));
        runtimes.put("npx", withHint(RuntimeProbe.probe("npx", "--version", "npx")));
        runtimes.put("python", withHint(RuntimeProbe.probe("python", "--version", "python", "python3", "py")));
        runtimes.put("pip", withHint(RuntimeProbe.probe("pip", "--version", "pip", "pip3")));
        runtimes.put("uv", withHint(RuntimeProbe.probe("uv", "--version", "uv")));
        runtimes.put("uvx", withHint(RuntimeProbe.probe("uvx", "--version", "uvx")));

        // 缺失项按具体运行时名列出（前端据此取安装提示）：
        //   npx 类 stdio server 需要 node；uvx 类需要 uv；python 类需要 python。
        List<String> missingForMcp = new ArrayList<>();
        for (String name : List.of("node", "python", "uv")) {
            if (!Boolean.TRUE.equals(((Map<?, ?>) runtimes.get(name)).get("available"))) {
                missingForMcp.add(name);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", System.getProperty("os.name"));
        response.put("runtimes", runtimes);
        response.put("missingForMcp", missingForMcp);
        return response;
    }

    /** RuntimeProbe.Result → 可序列化 Map + 安装提示（不执行安装，只给命令与地址）。 */
    private static Map<String, Object> withHint(RuntimeProbe.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", r.available());
        m.put("path", r.path());
        m.put("version", r.version());
        m.put("error", r.error());
        Map<String, String> hint = installHintOf(r.name());
        if (!hint.isEmpty()) {
            m.put("installCommand", hint.get("command"));
            m.put("installUrl", hint.get("url"));
        }
        return m;
    }

    /** 运行时名 → 安装提示（命令按 os.name 平台化：windows / macos / linux）。 */
    private static Map<String, String> installHintOf(String name) {
        String runtime = switch (name) {
            case "node", "npm", "npx" -> "node";
            case "python", "pip" -> "python";
            case "uv", "uvx" -> "uv";
            default -> null;
        };
        if (runtime == null) {
            return Map.of();
        }
        RuntimeInstallHints.Hint hint = RuntimeInstallHints.of(runtime);
        Map<String, String> m = new LinkedHashMap<>();
        if (!hint.command().isBlank()) {
            m.put("command", hint.command());
        }
        if (!hint.url().isBlank()) {
            m.put("url", hint.url());
        }
        return m;
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", "1.0.0");
        response.put("platform", System.getProperty("os.name"));
        response.put("javaVersion", System.getProperty("java.version"));
        response.put("memory", checkMemory());
        response.put("git", checkGit());
        response.put("path", checkPath());
        response.put("java", checkJava());
        return response;
    }

    // ── 真实诊断实现 ──

    /**
     * 用 GitAvailabilityChecker 真跑 git --version, 拿版本号.
     */
    private Map<String, Object> checkGit() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            GitAvailabilityChecker checker = new GitAvailabilityChecker(() -> {
                try {
                    Process p = new ProcessBuilder("git", "--version")
                            .redirectErrorStream(true).start();
                    boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                    if (!finished) {
                        p.destroyForcibly();
                        return "";
                    }
                    if (p.exitValue() != 0) {
                        return "";
                    }
                    return "git";
                } catch (Exception e) {
                    log.warn("[DoctorController] git --version probe failed: {}", e.getMessage());
                    return "";
                }
            });
            boolean available = checker.checkGitAvailable();
            if (!available) {
                // 随包 PortableGit 兜底：新装机器 PATH 上没有系统 Git，但安装目录里有。
                // 不做这一步，doctor 会在「Bash 工具其实可用」的机器上误报 git unavailable。
                Path bundled = BundledGit.gitExe().orElse(null);
                if (bundled != null) {
                    String bundledVersion = readVersionOf(bundled);
                    if (bundledVersion != null) {
                        result.put("available", true);
                        result.put("version", bundledVersion);
                        result.put("path", bundled.toString());
                        result.put("bundled", true);
                        return result;
                    }
                }
            }
            result.put("available", available);
            if (available) {
                result.put("version", readGitVersion());
            } else {
                result.put("version", null);
            }
        } catch (Exception e) {
            log.warn("[DoctorController] GitAvailabilityChecker failed: {}", e.getMessage());
            result.put("available", false);
            result.put("version", null);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 读指定 git 可执行文件的版本号（随包 PortableGit 用绝对路径，不依赖 PATH）。 */
    private static String readVersionOf(Path gitExe) {
        try {
            Process p = new ProcessBuilder(gitExe.toString(), "--version")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                int idx = out.lastIndexOf(' ');
                return idx >= 0 ? out.substring(idx + 1) : out;
            }
        } catch (Exception e) {
            log.warn("[DoctorController] bundled git --version failed: {}", e.getMessage());
            return null;
        }
    }

    private static String readGitVersion() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                return null;
            }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                int idx = out.lastIndexOf(' ');
                return idx >= 0 ? out.substring(idx + 1) : out;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查 user.dir 是否可写.
     */
    private Map<String, Object> checkPath() {
        Map<String, Object> result = new LinkedHashMap<>();
        String cwd = System.getProperty("user.dir");
        result.put("path", cwd);
        try {
            Path p = Paths.get(cwd);
            boolean writable = Files.isDirectory(p) && Files.isWritable(p);
            result.put("writable", writable);
            result.put("exists", Files.exists(p));
        } catch (Exception e) {
            log.warn("[DoctorController] path check failed: {}", e.getMessage());
            result.put("writable", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> checkJava() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", System.getProperty("java.version"));
        result.put("vendor", System.getProperty("java.vendor"));
        result.put("home", System.getProperty("java.home"));
        return result;
    }

    private static Map<String, Object> checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        double usedPct = max > 0 ? (double) used / max : 0;
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("max", max);
        mem.put("total", total);
        mem.put("free", free);
        mem.put("used", used);
        mem.put("usedPct", usedPct);
        mem.put("ok", usedPct < 0.95);
        return mem;
    }
}