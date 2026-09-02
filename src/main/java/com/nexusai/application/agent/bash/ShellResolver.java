package com.nexusai.application.agent.bash;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * bash/zsh 可执行 shell 探测 · 对齐 CC {@code utils/Shell.ts:73-137 findSuitableShell()}。
 *
 * <p><b>CC 真源</b>（自验，不信注释）：
 * <ul>
 *   <li>{@code findSuitableShell()}（Shell.ts:73-137）：探测顺序 = {@code CLAUDE_CODE_SHELL}
 *       env（须含 bash/zsh 且 {@code isExecutable} :50-68）→ {@code SHELL} env（同条件）→
 *       {@code which('zsh'/'bash')} → 常见路径 {@code /bin /usr/bin /usr/local/bin /opt/homebrew/bin}
 *       + {@code {bash,zsh}}，shell 顺序按 {@code preferBash = SHELL.includes('bash')} 排序（:105）；</li>
 *   <li>{@code isExecutable()}（:50-68）：{@code accessSync(X_OK)}，失败回退执行 {@code --version}；</li>
 *   <li>找不到 → 抛错：{@code "No suitable shell found. Claude CLI requires a Posix shell environment.
 *       Please ensure you have a valid shell installed and the SHELL environment variable set."}（:128-134）。</li>
 *   <li>{@code getShellConfig} memoize（:146）：会话内只探测一次。</li>
 * </ul>
 *
 * <p><b>Windows 分支</b>：定位 Git Bash（bash.exe），解析顺序对齐
 * {@code CommandHookExecutor.resolveGitBashPath}（CommandHookExecutor.java:1446-1461）：
 * {@code CLAUDE_CODE_GIT_BASH_PATH} → PATH 中 {@code git} 推导 {@code <gitRoot>/bin/bash.exe} →
 * PATH 扫描 {@code bash.exe}。CC 在 Windows 上同样走 Git Bash（Shell.ts 对 bash 工具无 cmd.exe
 * 回退；findSuitableShell 的 which('bash') 即解析到 Git Bash bash.exe）。
 *
 * <p><b>与 CommandHookExecutor.resolveGitBashPath 差异</b>：本类 Git Bash 定位<b>不兜底
 * 返回 "bash" 字符串</b>（CommandHookExecutor 兜底依赖 PATH 存在 bash，Windows 无 Git Bash 时
 * spawn 会静默失败）；本类找不到 → 继续 PATH 扫描 + 常见路径 → 最终显式抛 CC 同款错误（fail-loud）。
 *
 * <p>CC 原名/行号：{@code findSuitableShell}（Open-ClaudeCode/src/utils/Shell.ts:73-137）；
 * {@code isExecutable}（:50-68）；{@code getShellConfig}（:146）。
 *
 * @see com.nexusai.application.agent.permission.hook.CommandHookExecutor#resolveGitBashPath
 */
public final class ShellResolver {

    /** 会话内缓存（对齐 CC getShellConfig memoize Shell.ts:146）。 */
    private static volatile String cached;

    private ShellResolver() {
    }

    /**
     * 解析 bash/zsh 可执行 shell 路径；找不到抛 {@link IllegalStateException}（CC 同款信息）。
     *
     * @return 可执行 shell 绝对/探测路径
     * @throws IllegalStateException 无可用 Posix shell（对齐 CC Shell.ts:128-134）
     */
    public static String resolveShell() {
        String c = cached;
        if (c != null) {
            return c;
        }
        synchronized (ShellResolver.class) {
            if (cached != null) {
                return cached;
            }
            cached = resolveShell(System::getenv, ShellResolver::defaultExists, isWindows());
            return cached;
        }
    }

    /**
     * 可注入探测（envResolver / exists / windows）· 测试与 {@code CommandHookExecutor.buildProcessSpec}
     * 等复用；生产走无参 {@link #resolveShell()}（memoize）。语义同
     * {@code CommandHookExecutor.resolveGitBashPath(envResolver, pathExists)}（:1446）。
     *
     * @param envResolver env 取值器（生产 System::getenv；测试注入假 env）
     * @param exists      可执行判定（生产 defaultExists；测试注入假文件系统）
     * @param windows     平台判定（生产 isWindows()；测试注入，保证跨平台测试结果一致）
     * @return 可执行 shell 路径
     * @throws IllegalStateException 无可用 Posix shell（对齐 CC Shell.ts:128-134）
     */
    public static String resolveShell(Function<String, String> envResolver, Function<String, Boolean> exists,
                                      boolean windows) {
        // 调用方（如 CommandHookExecutor.buildProcessSpec）可能传 null → 回落 System::getenv / defaultExists
        Function<String, String> env = envResolver != null ? envResolver : System::getenv;
        Function<String, Boolean> ex = exists != null ? exists : ShellResolver::defaultExists;
        return doResolve(env, ex, windows);
    }

    private static String doResolve(Function<String, String> envResolver, Function<String, Boolean> exists,
                                    boolean windows) {
        // 1. CLAUDE_CODE_SHELL env 覆盖 · 对齐 CC Shell.ts:75-89（须含 bash/zsh 且可执行）
        String override = envResolver.apply("CLAUDE_CODE_SHELL");
        if (override != null && !override.isBlank()
                && (override.contains("bash") || override.contains("zsh"))
                && Boolean.TRUE.equals(exists.apply(override))) {
            return override;
        }

        // 2. SHELL env（须含 bash/zsh 且可执行）· 对齐 CC Shell.ts:92-96
        String envShell = envResolver.apply("SHELL");
        boolean isEnvShellSupported = envShell != null && !envShell.isBlank()
                && (envShell.contains("bash") || envShell.contains("zsh"));
        boolean preferBash = envShell != null && envShell.contains("bash");

        // 3. Windows：Git Bash 定位（不兜底 "bash"）· 对齐 CommandHookExecutor:1446-1461
        if (windows) {
            String gitBash = resolveGitBashPath(envResolver, exists, windows);
            if (gitBash != null) {
                return gitBash;
            }
        }

        // 4. which bash / zsh（PATH 扫描）+ 常见路径，shell 顺序按 preferBash（对齐 CC :99-118）
        List<String> candidates = new ArrayList<>();
        String[] shellOrder = preferBash ? new String[] {"bash", "zsh"} : new String[] {"zsh", "bash"};
        for (String sh : shellOrder) {
            String whichPath = findExecutableOnPath(sh, envResolver, exists, windows);
            if (whichPath != null) {
                candidates.add(whichPath);
            }
        }
        String[] shellPaths = {"/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"};
        for (String dir : shellPaths) {
            for (String sh : shellOrder) {
                candidates.add(dir + "/" + sh);
            }
        }

        // 5. SHELL env 置顶优先（对齐 CC :121-123 unshift）
        if (isEnvShellSupported && Boolean.TRUE.equals(exists.apply(envShell))) {
            candidates.add(0, envShell);
        }

        // 6. 首个可执行者 · 对齐 CC :125
        for (String candidate : candidates) {
            if (Boolean.TRUE.equals(exists.apply(candidate))) {
                return candidate;
            }
        }

        // 7. 找不到 → CC 同款显式错误（fail-loud，绝不静默 cmd.exe 兜底）· 对齐 CC :128-134
        throw new IllegalStateException(
            "No suitable shell found. Claude CLI requires a Posix shell environment. "
            + "Please ensure you have a valid shell installed and the SHELL environment variable set.");
    }

    /**
     * 默认可执行判定 · 对齐 CC {@code isExecutable}（Shell.ts:50-68）。
     * Windows 上 {@code File.canExecute()} 对 .exe 不可靠（权限模型差异），改用 isFile。
     */
    private static boolean defaultExists(String shellPath) {
        File f = new File(shellPath);
        if (!f.exists()) {
            return false;
        }
        return isWindows() ? f.isFile() : f.canExecute();
    }

    /**
     * Windows Git Bash 定位 · 对齐 {@code CommandHookExecutor.resolveGitBashPath}（:1446-1461），
     * 但<b>不兜底返回 "bash"</b>（找不到返回 null，继续后续探测）。
     *
     * <p>解析顺序：{@code CLAUDE_CODE_GIT_BASH_PATH} → PATH 中 {@code git} 推导
     * {@code <gitRoot>/bin/bash.exe}（windowsPaths.ts:113 同源）。
     */
    private static String resolveGitBashPath(Function<String, String> env, Function<String, Boolean> exists,
                                             boolean windows) {
        String envPath = env.apply("CLAUDE_CODE_GIT_BASH_PATH");
        if (envPath != null && !envPath.isBlank() && Boolean.TRUE.equals(exists.apply(envPath))) {
            return envPath;
        }
        String gitPath = findExecutableOnPath("git", env, exists, windows);
        if (gitPath != null) {
            File gitFile = new File(gitPath);
            File parent = gitFile.getParentFile();
            if (parent != null) {
                File gitRoot = parent.getParentFile();
                if (gitRoot != null) {
                    File bash = new File(gitRoot, "bin" + File.separator + "bash.exe");
                    if (Boolean.TRUE.equals(exists.apply(bash.getAbsolutePath()))) {
                        return bash.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    /**
     * PATH 中查找可执行文件 · where/which 语义（Windows 加 .exe 后缀）。
     * 对齐 {@code CommandHookExecutor.findExecutableOnPath}（:2205-2220）。
     */
    private static String findExecutableOnPath(String exe, Function<String, String> env,
                                               Function<String, Boolean> exists, boolean windows) {
        String pathValue = env.apply("PATH");
        if (pathValue == null) {
            return null;
        }
        String sep = windows ? ";" : ":";
        for (String dir : pathValue.split(Pattern.quote(sep))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            File base = new File(dir);
            File direct = new File(base, exe);
            if (Boolean.TRUE.equals(exists.apply(direct.getAbsolutePath()))) {
                return direct.getAbsolutePath();
            }
            if (windows) {
                File withExe = new File(base, exe + ".exe");
                if (Boolean.TRUE.equals(exists.apply(withExe.getAbsolutePath()))) {
                    return withExe.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Windows 平台判定 · 等价 CC {@code getPlatform() === 'windows'}（platform.ts:18）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
