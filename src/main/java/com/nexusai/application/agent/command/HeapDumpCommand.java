package com.nexusai.application.agent.command;

import java.util.function.Supplier;

/**
 * Heap dump 命令 · 对齐 CC commands/heapdump/heapdump.ts:3-17 call.
 *
 * <p>L1 语义: 调 performHeapDump() → 失败返回错误文本; 成功返回 heapPath + diagPath 两行.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(Supplier&lt;HeapDumpResult&gt;) → CommandResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: 失败 → "Failed to create heap dump: {error}"; 成功 → "{heapPath}\\n{diagPath}"</li>
 *   <li><b>A3</b>: pure dispatcher — 失败信息透传</li>
 *   <li><b>A4</b>: heapPath 和 diagPath 都非空 (CC heapDumpService 约定)</li>
 *   <li><b>A5</b>: 真实场景 — heap dump → /tmp/heap-2026-07-20.heapsnapshot + /tmp/diag.txt</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Supplier 替代 CC performHeapDump(); record 替代 TS interface.
 */
public final class HeapDumpCommand {

    /** CC heapDumpService 返回. */
    public record HeapDumpResult(boolean success, String heapPath, String diagPath, String error) {
        public static HeapDumpResult ok(String heap, String diag) {
            return new HeapDumpResult(true, heap, diag, null);
        }
        public static HeapDumpResult fail(String error) {
            return new HeapDumpResult(false, null, null, error);
        }
    }

    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) { return new CommandResult("text", value); }
    }

    private HeapDumpCommand() {}

    /**
     * 执行 heap dump 命令.
     *
     * @param heapDumper heap dump 副作用函数 (CC performHeapDump 替代)
     * @return "Failed..." 或 "{heapPath}\\n{diagPath}"
     */
    public static CommandResult execute(Supplier<HeapDumpResult> heapDumper) {
        HeapDumpResult r = heapDumper.get();
        if (!r.success()) {
            return CommandResult.text("Failed to create heap dump: " + r.error());
        }
        return CommandResult.text(r.heapPath() + "\n" + r.diagPath());
    }
}