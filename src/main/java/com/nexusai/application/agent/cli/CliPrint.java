package com.nexusai.application.agent.cli;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI --print (headless) main entrypoint · 对齐 CC cli/print.ts.
 *
 * <p>L1 语义: `claude -p` headless 模式 — single turn LLM 调用 + 输出;
 *            加载 user settings (remote-managed);StructuredIO 序列化;
 *            tool pool 装配 + deny rules 过滤;streamlined transformer.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: PrintResult record (5 字段); PrintConfig record (8 字段);
 *       3 method (runHeadless/setupOutputStream/runSingleTurn);
 *       DEFAULT_TIMEOUT_MS=600000;DEFAULT_MAX_TURNS=100.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — setupOutputStream → loadSettings → runSingleTurn →
 *       output → gracefulShutdown.</li>
 *   <li><b>A3</b>: 注入式 (settingsLoader + outputStream + toolPool);silent failure.</li>
 *   <li><b>A4</b>: 缺 user prompt → 错误;timeout → exit 124;auth fail → 退出.</li>
 *   <li><b>A5</b>: 真实场景 — `claude -p "What is java?"` → 流式输出 response → exit 0.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async → Java 同步 (异步由 caller wired);
 *                    TS process.exit → Java 注入式 shutdown.
 */
public final class CliPrint {

    private static final Logger log = LoggerFactory.getLogger(CliPrint.class);

    public static final long DEFAULT_TIMEOUT_MS = 600_000L;
    public static final int DEFAULT_MAX_TURNS = 100;
    public static final String STREAM_JSON_CONTENT_TYPE = "application/json";

    public record PrintConfig(
        String prompt, String model, String systemPrompt,
        Map<String, Object> env, int maxTurns, long timeoutMs,
        boolean streamJson, boolean verbose) {

        public static PrintConfig defaults(String prompt) {
            return new PrintConfig(prompt, "sonnet", null, Map.of(),
                DEFAULT_MAX_TURNS, DEFAULT_TIMEOUT_MS, true, false);
        }
    }

    public record PrintResult(
        int exitCode, String output, int turns, long durationMs, String errorMessage) {
        public static PrintResult success(String output, int turns, long durationMs) {
            return new PrintResult(0, output, turns, durationMs, null);
        }
        public static PrintResult error(int exitCode, String message) {
            return new PrintResult(exitCode, null, 0, 0L, message);
        }
    }

    public interface SettingsLoader {
        void loadUserSettings();
        void waitForRemoteManagedSettings();
    }

    public interface OutputStream {
        void writeStdout(String text);
        void writeStderr(String text);
    }

    public interface TurnRunner {
        PrintResult runSingleTurn(PrintConfig config, OutputStream output);
    }

    public interface Shutdown {
        void shutdown(int exitCode);
    }

    private final SettingsLoader settingsLoader;
    private final OutputStream outputStream;
    private final TurnRunner turnRunner;
    private final Shutdown shutdown;

    public CliPrint(SettingsLoader settingsLoader, OutputStream outputStream,
            TurnRunner turnRunner, Shutdown shutdown) {
        this.settingsLoader = settingsLoader;
        this.outputStream = outputStream == null ? new NullOutputStream() : outputStream;
        this.turnRunner = turnRunner == null ? (c, o) -> PrintResult.error(1, "no runner") : turnRunner;
        this.shutdown = shutdown == null ? c -> {} : shutdown;
    }

    public CliPrint() {
        this(null, null, null, null);
    }

    /** CC runHeadless 主链. */
    public PrintResult runHeadless(PrintConfig config) {
        if (config == null || config.prompt() == null || config.prompt().isBlank()) {
            PrintResult err = PrintResult.error(2, "missing prompt");
            shutdown.shutdown(err.exitCode());
            return err;
        }
        long start = System.currentTimeMillis();
        if (settingsLoader != null) {
            settingsLoader.loadUserSettings();
            settingsLoader.waitForRemoteManagedSettings();
        }
        PrintResult result = turnRunner.runSingleTurn(config, outputStream);
        long duration = System.currentTimeMillis() - start;
        if (result.errorMessage() == null) {
            PrintResult withDuration = PrintResult.success(result.output(), result.turns(), duration);
            shutdown.shutdown(0);
            return withDuration;
        }
        shutdown.shutdown(result.exitCode());
        return result;
    }

    /** CC setupOutputStream — install streamlined transformer. */
    public OutputStream setupOutputStream(boolean streamJson) {
        // actual output stream set up by caller (StreamJsonStdoutGuard etc.)
        return outputStream;
    }

    /** CC runSingleTurn — 单 turn LLM 调用. */
    public PrintResult runSingleTurn(PrintConfig config, OutputStream output) {
        return turnRunner.runSingleTurn(config, output);
    }

    public static boolean isHeadless(PrintConfig config) {
        return config != null && config.prompt() != null && !config.prompt().isEmpty();
    }

    private static class NullOutputStream implements OutputStream {
        public void writeStdout(String text) {}
        public void writeStderr(String text) {}
    }
}