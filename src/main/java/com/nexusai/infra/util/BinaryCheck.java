package com.nexusai.infra.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * BinaryCheck · 对齐 CC utils/binaryCheck.ts.
 *
 * <p>L1 语义: 检查文件/路径是否为 binary (基于 NUL byte 检测)。
 * <ul>
 *   <li>{@link #isBinary(Path, int)} — read first N bytes, check NUL byte</li>
 *   <li>{@link #isBinary(byte[])} — pure array check</li>
 *   <li>{@link #isPathBinary(Path, java.util.function.BiPredicate)} — custom IO abstraction</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 静态方法 + 注入式 BiPredicate (testable IO)</li>
 *   <li><b>A2 Golden Trace</b>: contains NUL → binary;no NUL → text;empty → false;read 4KB</li>
 *   <li><b>A3 纯函数</b>: 同 input→同 output;read 注入式</li>
 *   <li><b>A4 边界</b>: null path→false;empty buffer→false;short bytes no NUL→false</li>
 *   <li><b>A5 业务场景</b>: 决定文件处理路径 (text vs binary);Skip UTF-16 LE BOM (has NUL bytes → binary)</li>
 * </ul>
 *
 * <p>L3 升级: TS fs.readFile + check NUL → Java NIO Files + BiPredicate 注入式.
 */
public final class BinaryCheck {

    private static final int PROBE_BYTES = 4096;
    private static final String UTF16_LE_BOM = "﻿";

    private BinaryCheck() {}

    /**
     * Check if bytes contain binary content (NUL byte in first {@value #PROBE_BYTES}).
     */
    public static boolean isBinary(byte[] buffer) {
        if (buffer == null || buffer.length == 0) return false;
        int limit = Math.min(buffer.length, PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (buffer[i] == 0) return true;
        }
        return false;
    }

    /**
     * Read file via {@code pathReader} and check NUL byte.
     * @param pathReader  takes (path, byteCount) → byte[] (testable IO)
     */
    public static boolean isPathBinary(
        Path path, java.util.function.BiFunction<Path, Integer, byte[]> pathReader) {
        if (path == null || pathReader == null) return false;
        try {
            byte[] buf = pathReader.apply(path, PROBE_BYTES);
            return isBinary(buf);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Read actual file via {@link Files#probeContentType} + bytes.
     */
    public static boolean isBinary(Path path) {
        return isPathBinary(path, (p, len) -> {
            try {
                if (!Files.exists(p)) return new byte[0];
                byte[] buf = new byte[len];
                try (var in = Files.newInputStream(p)) {
                    int total = 0;
                    while (total < len) {
                        int read = in.read(buf, total, len - total);
                        if (read < 0) break;
                        total += read;
                    }
                    if (total < len) {
                        byte[] smaller = new byte[total];
                        System.arraycopy(buf, 0, smaller, 0, total);
                        buf = smaller;
                    }
                }
                return buf;
            } catch (IOException e) {
                return new byte[0];
            }
        });
    }

    /** Predicate convenience: detects if a Path is binary via {@link #isBinary(Path)}. */
    public static Predicate<Path> asPredicate() {
        return BinaryCheck::isBinary;
    }
}
