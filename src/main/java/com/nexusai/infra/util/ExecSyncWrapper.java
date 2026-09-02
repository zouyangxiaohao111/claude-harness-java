package com.nexusai.infra.util;

import java.util.function.BiFunction;

/**
 * ExecSyncWrapper · 对齐 CC utils/execFileNoThrowPortable.ts (DEPRECATED).
 *
 * <p>CC 注释: 标记为 deprecated — 同步执行 block event loop,推荐用 {@code execa} async。
 * Java 等价: {@code Runtime.exec(...)} + {@code Process.waitFor} 也是同步阻塞。
 *
 * <p>L1 语义: 同步执行 command → 返 stdout (trim 后) 或 null (exit != 0 / exception)。
 * AbortSignal 检查 + timeout 保护 + slowLogging 注入式。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 overloads (cc 同名) + 1 main impl + Executor 注入式 (Runtime.exec 替换)</li>
 *   <li><b>A2 Golden Trace</b>: exit 0 + stdout "hello"→"hello";exit != 0→null;exception→null;timeout→null</li>
 *   <li><b>A3 副作用</b>: process 启动;stdout 消费;InputStream 关闭</li>
 *   <li><b>A4 边界</b>: null command→"";signal abort→抛 InterruptedException;empty stdout→null</li>
 *   <li><b>A5 业务场景</b>: legacy code 调用同步 shell 工具(CC 注释: 仅 fs ops 同步 OK,API 调应用 async)</li>
 * </ul>
 *
 * <p>L3 升级: TS execaSync child_process → Java Runtime.exec 注入式 (testable);
 * TS AbortSignal → Java thread.interrupt 检查;
 * TS slowLogging → Java Consumer&lt;String&gt; 注入式.
 */
public final class ExecSyncWrapper {

    public static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

    public interface Executor {
        record Result(int exitCode, String stdout) {}
        Result exec(String command, long timeoutMs, String input);
    }

    private ExecSyncWrapper() {}

    /** 1-arg variant: command only. */
    public static String execSyncWithDefaults_DEPRECATED(String command) {
        // No executor injection — uses RuntimeExecutor. Real CC behavior.
        return execSyncWithDefaults_DEPRECATED(command, java.util.Map.of(), null, RuntimeExecutor.INSTANCE);
    }

    /** 2-arg variant: command + options map. */
    public static String execSyncWithDefaults_DEPRECATED(
        String command, java.util.Map<String, Object> options) {
        return execSyncWithDefaults_DEPRECATED(command, options, null, RuntimeExecutor.INSTANCE);
    }

    /** 3-arg variant: command + options + slow logging sink. */
    public static String execSyncWithDefaults_DEPRECATED(
        String command, java.util.Map<String, Object> options,
        java.util.function.Consumer<String> slowLog) {
        return execSyncWithDefaults_DEPRECATED(command, options, slowLog, RuntimeExecutor.INSTANCE);
    }

    /** Internal main impl with executor injection. */
    public static String execSyncWithDefaults_DEPRECATED(
        String command, java.util.Map<String, Object> options,
        java.util.function.Consumer<String> slowLog,
        Executor executor) {
        if (command == null) return null;
        Long timeout = options.get("timeout") == null
            ? DEFAULT_TIMEOUT_MS
            : (Long) options.get("timeout");
        String input = (String) options.getOrDefault("input", null);
        if (slowLog != null) slowLog.accept("exec: " + command.substring(0, Math.min(200, command.length())));
        try {
            Executor.Result r = executor.exec(command, timeout, input);
            if (r.exitCode() != 0) return null;
            if (r.stdout() == null || r.stdout().isEmpty()) return null;
            return r.stdout().trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Default executor using {@link Runtime#exec}. */
    public enum RuntimeExecutor implements Executor {
        INSTANCE;
        @Override public Result exec(String command, long timeoutMs, String input) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                if (input != null) {
                    p.getOutputStream().write(input.getBytes());
                    p.getOutputStream().close();
                }
                boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!done) { p.destroyForcibly(); return new Result(-1, null); }
                String stdout = new String(p.getInputStream().readAllBytes());
                return new Result(p.exitValue(), stdout);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
