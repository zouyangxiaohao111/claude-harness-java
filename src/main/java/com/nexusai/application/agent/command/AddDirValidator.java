package com.nexusai.application.agent.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AddDirValidator · 对齐 CC commands/add-dir/validation.ts.
 *
 * <p>L1 语义: /add-dir slash command 的目录验证 4 状态机:
 * <ul>
 *   <li>{@code emptyPath} — directoryPath 为空</li>
 *   <li>{@code pathNotFound} — stat 抛 ENOENT/ENOTDIR/EACCES/EPERM (或 not directory 之外错误)</li>
 *   <li>{@code notADirectory} — exists 但不是 directory</li>
 *   <li>{@code alreadyInWorkingDirectory} — 在已存在 working dir 内</li>
 *   <li>{@code success} — 全部通过</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 5 状态 {@link Result} record (sealed 风格) + {@link #validateDirectoryForWorkspace(String, List, Function)} 5 参</li>
 *   <li><b>A2 Golden Trace</b>: emptyPath → emptyPath;stat isDirectory()=false → notADirectory;stat throws ENOENT → pathNotFound;pathInWorkingPath(absolutePath, workingDir) → alreadyInWorkingDirectory;pass → success</li>
 *   <li><b>A3 纯函数 + 副作用受控</b>: 注入式 {@link Stat} (实际 NIO 调用 or test fake);其余纯函数</li>
 *   <li><b>A4 边界</b>: null directoryPath → emptyPath;stat throws 非已知 errno → 透传 (re-throw)</li>
 *   <li><b>A5 业务场景</b>: /add-dir /tmp/newcode → expand → stat 失败 (不存在) → pathNotFound</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code fs/promises.stat} + {@code chmod 0o755 dir} →
 * Java {@link Files#isDirectory(Path)};TS {@code getErrnoCode} 检查 ENOENT/ENOTDIR/EACCES/EPERM →
 * Java 自定义 errno 字段 (caller 提供);TS {@code pathInWorkingPath} →
 * Java {@code Path.startsWith} 子路径检查。
 */
public final class AddDirValidator {

    private static final Logger log = LoggerFactory.getLogger(AddDirValidator.class);

    public sealed interface Result permits EmptyPath, PathNotFound, NotADirectory,
        AlreadyInWorkingDirectory, SuccessResult {

        String resultType();
    }
    public record EmptyPath() implements Result {
        public String resultType() { return "emptyPath"; }
    }
    public record PathNotFound(String directoryPath, String absolutePath) implements Result {
        public String resultType() { return "pathNotFound"; }
    }
    public record NotADirectory(String directoryPath, String absolutePath) implements Result {
        public String resultType() { return "notADirectory"; }
    }
    public record AlreadyInWorkingDirectory(String directoryPath, String workingDir) implements Result {
        public String resultType() { return "alreadyInWorkingDirectory"; }
    }
    public record SuccessResult(String absolutePath) implements Result {
        public String resultType() { return "success"; }
    }

    /** Stat function returning isDir or throwing an IOException-like Throwable. */
    public interface Stat {
        record Result(boolean isDirectory) {}
        Result stat(Path path) throws Exception;
    }

    /** Resolves a path after expanding ~ and strips trailing slash. */
    public static String expandAndResolve(String input) {
        if (input == null) return "";
        String expanded = input.startsWith("~/")
            ? System.getProperty("user.home") + input.substring(1)
            : input.startsWith("~")
                ? System.getProperty("user.home") + input.substring(1)
                : input;
        return Paths.get(expanded).toAbsolutePath().normalize().toString();
    }

    /**
     * Validate directory for workspace addition.
     *
     * @param directoryPath    user-supplied dir path
     * @param currentWorkingDirs list of existing working dirs
     * @param stat injected stat function (defaults to Files.isDirectory)
     */
    public static Result validateDirectoryForWorkspace(
        String directoryPath, List<String> currentWorkingDirs, Stat stat) {
        if (directoryPath == null || directoryPath.isEmpty()) {
            return new EmptyPath();
        }
        String absolutePath;
        try {
            absolutePath = expandAndResolve(directoryPath);
        } catch (RuntimeException ex) {
            return new PathNotFound(directoryPath, directoryPath);
        }
        // Single stat call: check existence + isDirectory
        try {
            Stat.Result sr = stat.stat(Path.of(absolutePath));
            if (!sr.isDirectory()) {
                return new NotADirectory(directoryPath, absolutePath);
            }
        } catch (Exception e) {
            // Match CC: treat ENOENT/ENOTDIR/EACCES/EPERM as pathNotFound.
            // Java 不区分 errno 字段 — caller 通过异常 message 携带或 stat 实现抛 EOF-like 异常。
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("ENOENT") || msg.contains("ENOTDIR")
                || msg.contains("EACCES") || msg.contains("EPERM")) {
                return new PathNotFound(directoryPath, absolutePath);
            }
            throw new RuntimeException(e);
        }
        for (String workingDir : currentWorkingDirs) {
            if (pathInWorkingPath(absolutePath, workingDir)) {
                return new AlreadyInWorkingDirectory(directoryPath, workingDir);
            }
        }
        return new SuccessResult(absolutePath);
    }

    /**
     * Default Files-based stat. Equivalent to CC's fs/promises.stat().
     */
    public static final Stat DEFAULT_STAT = path -> {
        if (!Files.exists(path)) throw new java.io.IOException("ENOENT: " + path);
        if (Files.isDirectory(path)) return new Stat.Result(true);
        throw new java.io.IOException("ENOTDIR: " + path);
    };

    private static boolean pathInWorkingPath(String absolutePath, String workingDir) {
        Path child = Paths.get(absolutePath).toAbsolutePath().normalize();
        Path parent = Paths.get(workingDir).toAbsolutePath().normalize();
        return child.startsWith(parent);
    }

    /**
     * Render the user-facing help message for a result, mirroring CC addDirHelpMessage.
     */
    public static String addDirHelpMessage(Result result) {
        return switch (result) {
            case EmptyPath ignored -> "Please provide a directory path.";
            case PathNotFound r -> "Path " + r.absolutePath() + " was not found.";
            case NotADirectory r -> r.directoryPath() + " is not a directory. Did you mean to add the parent directory "
                + Paths.get(r.absolutePath()).getParent() + "?";
            case AlreadyInWorkingDirectory r -> r.directoryPath() + " is already accessible within the existing "
                + "working directory " + r.workingDir() + ".";
            case SuccessResult r -> "Added " + r.absolutePath() + " as a working directory.";
        };
    }
}
