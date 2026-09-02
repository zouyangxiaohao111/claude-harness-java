package com.nexusai.infra.util;

import java.util.function.Predicate;

/**
 * FileReadMode · 对齐 CC utils/fileRead.ts (processTextPrompt 部分).
 *
 * <p>L1 语义: 文本处理模式工具 — 模式检测 + 注入式 predicate 决定是否需要特殊处理。
 * <ul>
 *   <li>{@link #needsSpecialProcessing(String, Predicate)} — mode-based filter</li>
 *   <li>{@link #processLine(String)} — 单行处理 (trim/normalise)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + 注入式 Predicate</li>
 *   <li><b>A2 Golden Trace</b>: shell special (path with shell metachar)→true;plain text→false;line trimmed/normalized</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: null line→"";empty line→"";special filter null→false</li>
 *   <li><b>A5 业务场景</b>: stdin text 处理 (Bash tool input)</li>
 * </ul>
 *
 * <p>L3 升级: TS function predicate → Java Predicate 注入式;
 * TS string method chain → Java String methods.
 */
public final class FileReadMode {

    public enum Mode { SHELL, TEXT, BINARY, UNKNOWN }

    private FileReadMode() {}

    /**
     * Determine mode from file extension.
     */
    public static Mode detectMode(String filePath) {
        if (filePath == null) return Mode.UNKNOWN;
        if (filePath.endsWith(".sh") || filePath.endsWith(".bash") || filePath.endsWith(".zsh")) {
            return Mode.SHELL;
        }
        if (filePath.endsWith(".txt") || filePath.endsWith(".md") || filePath.endsWith(".json")) {
            return Mode.TEXT;
        }
        if (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".gif")) {
            return Mode.BINARY;
        }
        return Mode.UNKNOWN;
    }

    /**
     * Check if the input needs special processing per the predicate.
     * Default predicate: shell special characters (`, |, &, ;, $, <, >, `).
     */
    public static boolean needsSpecialProcessing(String line, Predicate<String> shellSpecial) {
        if (line == null || line.isEmpty()) return false;
        if (shellSpecial == null) return false;
        return shellSpecial.test(line);
    }

    /** Normalize a line: trim trailing whitespace + CR (per CC). */
    public static String processLine(String line) {
        if (line == null) return "";
        // Strip trailing CR (CC) + whitespace
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == '\r' || c == ' ' || c == '\t') end--;
            else break;
        }
        return line.substring(0, end);
    }
}
