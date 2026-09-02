package com.nexusai.infra.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

/**
 * GitIgnoreHelper · 对齐 CC utils/git/gitignore.ts.
 *
 * <p>L1 语义: git check-ignore wrapper + global gitignore 路径 + add file glob rule to gitignore。
 * <ul>
 *   <li>{@link #isPathGitignored(String, String, BiFunction)} — execFile 返回 0 → ignored</li>
 *   <li>{@link #getGlobalGitignorePath(String)} — ~/.config/git/ignore</li>
 *   <li>{@link #addFileGlobRuleToGitignore(String, String, BiFunction, BiFunction, BiFunction)} — appendEntry if not exists</li>
 * </ul>
 * 全部依赖注入 (testable)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 静态方法 + 5 Function/BiFunction 注入式 (exec, stat, mkdir, read, write, append)</li>
 *   <li><b>A2 Golden Trace</b>: exit 0→ignored;exit 1→not ignored;exit 128→false (fail open);global path ~/.config/git/ignore;addFile → mkdir + read-or-write pattern</li>
 *   <li><b>A3 副作用</b>: 写文件;mkdir dir</li>
 *   <li><b>A4 边界</b>: dirIsInGitRepo false→return;existing pattern in gitignore→return;read fail ENOENT→write new file</li>
 *   <li><b>A5 业务场景</b>: 用户添加 'session-env/' to gitignore by safe /add-dir command</li>
 * </ul>
 *
 * <p>L3 升级: TS fs/promises → Java Function/BiFunction 注入式 (testable);
 * TS execFile → Java BiFunction (cmd, args) → Result.
 */
public final class GitIgnoreHelper {

    public static final String GLOBAL_GITIGNORE_RELATIVE = ".config/git/ignore";

    private GitIgnoreHelper() {}

    public record ExecResult(int exitCode, String stdout, String stderr) {}

    public static boolean isPathGitignored(
        String filePath, String cwd,
        BiFunction<String[], String, ExecResult> execGitCheckIgnore) {
        if (filePath == null || execGitCheckIgnore == null) return false;
        ExecResult r = execGitCheckIgnore.apply(
            new String[]{"check-ignore", filePath}, cwd);
        // exit 0 = ignored; 1 = not; 128 = not in repo (fail open)
        return r != null && r.exitCode() == 0;
    }

    public static String getGlobalGitignorePath(String homedir) {
        String home = (homedir == null || homedir.isEmpty()) ? "/" : homedir;
        // Avoid double slash when home is "/"
        if (home.endsWith("/")) {
            return home + GLOBAL_GITIGNORE_RELATIVE;
        }
        return home + "/" + GLOBAL_GITIGNORE_RELATIVE;
    }

    public static void addFileGlobRuleToGitignore(
        String filename, String cwd,
        BiFunction<String[], String, ExecResult> execGitCheck,
        java.util.function.Predicate<String> dirIsInGitRepoFn,
        java.util.function.BiFunction<String, Boolean, Boolean> mkdirFn,
        java.util.function.Function<String, String> readFileFn,
        java.util.function.BiFunction<String, String, Boolean> writeFileFn,
        java.util.function.BiConsumer<String, String> appendFileFn) {
        if (filename == null) return;
        if (dirIsInGitRepoFn != null && !dirIsInGitRepoFn.test(cwd)) return;
        String gitignoreEntry = "**/" + filename;
        String testPath = filename.endsWith("/")
            ? filename + "sample-file.txt"
            : filename;
        // Check if pattern already exists
        if (isPathGitignored(testPath, cwd, execGitCheck)) return;
        String globalPath = getGlobalGitignorePath(System.getProperty("user.home"));
        if (mkdirFn != null) {
            try {
                Boolean ok = mkdirFn.apply(
                    Paths.get(globalPath).getParent().toString(), true);
                // ok indicates success/failure (ignored for simplicity)
            } catch (RuntimeException e) {
                return; // mkdir failed
            }
        }
        try {
            String content = readFileFn != null ? readFileFn.apply(globalPath) : null;
            if (content != null && content.contains(gitignoreEntry)) return;
            if (appendFileFn != null && content != null) {
                appendFileFn.accept(globalPath, "\n" + gitignoreEntry + "\n");
            } else if (writeFileFn != null) {
                writeFileFn.apply(globalPath, gitignoreEntry + "\n");
            }
        } catch (RuntimeException e) {
            // CC: ENOENT → create new file
            if (writeFileFn != null) {
                try {
                    writeFileFn.apply(globalPath, gitignoreEntry + "\n");
                } catch (RuntimeException ignored) {}
            }
        }
    }
}
