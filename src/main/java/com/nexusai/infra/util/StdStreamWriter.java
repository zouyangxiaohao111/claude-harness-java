package com.nexusai.infra.util;

import java.io.PrintStream;

/**
 * StdStreamWriter · 对齐 CC utils/process.ts (writeToStdout/Stderr + exitWithError).
 *
 * <p>L1 语义: 注入式 stdout/stderr writer + exit handler。
 * 原始 CC 还含 EPIPE 处理 + peekForStdinData;本类聚焦 writeToStdout + writeToStderr + exitWithError 3 静态 helper。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: writeTo(out, data)→void;writeToStdout(data)/writeToStderr(data) 默认 System.out/System.err;exitWithError(msg) 不返回</li>
 *   <li><b>A2 Golden Trace</b>: writeToStdout('hello')→stdout streams 'hello';destroyed stream→no-op</li>
 *   <li><b>A3 副作用</b>: writeTo 实际写入;null data→NPE (caller guard)</li>
 *   <li><b>A4 边界</b>: null/null 优雅 (no-op);空 string 仍然 write</li>
 *   <li><b>A5 业务场景</b>: CLI -p mode 输出 answer stream;EPIPE (pipe broken | head -1) 不抛</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS NodeJS WriteStream → Java PrintStream (or OutputStream);
 * TS destroyed flag → Java PrintStream {@code checkError()} check;
 * TS process.exit(1) → Java {@link System#exit(int)} (test 通过 Consumer 注入式替换)。
 */
public final class StdStreamWriter {

    private StdStreamWriter() {}

    /**
     * Write {@code data} to a stream. If the stream is broken (e.g. EPIPE),
     * the write is a no-op. Mirrors CC writeOut(stream, data).
     *
     * @param out destination stream; null is a no-op
     * @param data text to write
     */
    public static void writeTo(PrintStream out, String data) {
        if (out == null || data == null) return;
        try {
            // PrintStream.println auto-flushes; we use print+newline behavior equivalent
            if (!out.checkError()) {
                out.print(data);
            }
        } catch (RuntimeException ignored) {
            // broken pipe / stream closed — swallow per CC
        }
    }

    /** Mirror CC writeToStdout — writes to System.out. */
    public static void writeToStdout(String data) {
        writeTo(System.out, data);
    }

    /** Mirror CC writeToStderr — writes to System.err. */
    public static void writeToStderr(String data) {
        writeTo(System.err, data);
    }

    /**
     * Mirror CC exitWithError — prints to stderr and calls exit. The {@code exitHook}
     * allows tests to capture the call without terminating the JVM.
     *
     * @param message  error message printed to stderr
     * @param exitHook default System.exit; tests inject a captor
     */
    public static void exitWithError(String message, java.util.function.IntConsumer exitHook) {
        System.err.println(message);
        exitHook.accept(1);
    }

    /** Convenience overload using System.exit. */
    public static void exitWithError(String message) {
        exitWithError(message, System::exit);
    }
}
