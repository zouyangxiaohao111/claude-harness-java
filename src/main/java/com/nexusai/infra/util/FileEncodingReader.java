package com.nexusai.infra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * FileEncodingReader · 对齐 CC utils/fileRead.ts.
 *
 * <p>L1 语义: 文件 encoding 检测 + line endings 检测 (BOM-based)。CC 注释: 'leaf' 模块
 * — 避免 file.ts 的 settings SCC (log → types/logs → Tool → commands …)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: detectEncoding(path, bufferSupplier) + detectLineEndings(content) + readFileSyncWithMetadata(path) + readFileSync(path) + LineEndingType enum</li>
 *   <li><b>A2 Golden Trace</b>: utf16le BOM (FF FE)→utf16le;utf8 BOM (EF BB BF)→utf8;empty→utf8;CRLF > LF→CRLF;more LF→LF;windows CRLF→CRLF</li>
 *   <li><b>A3 副作用</b>: 注入式 bufferSupplier (testable);不直接 IO</li>
 *   <li><b>A4 边界</b>: null/empty content→LF;bytesRead<2→utf8;BOM not matched→utf8</li>
 *   <li><b>A5 业务场景</b>: FileEditTool 读文件 + 检测 CRLF 保留写回;API 客户端根据 encoding 选 charset</li>
 * </ul>
 *
 * <p>L3 升级: TS file fs.readSync → Java Supplier (testable);
 * TS BufferEncoding string union → Java String + Charset;
 * TS line endings loop → Java for + count.
 */
public final class FileEncodingReader {

    public enum LineEndingType { CRLF, LF }

    public record FileMetadata(String content, String encoding, LineEndingType lineEndings) {}

    public static final String UTF8 = "utf8";
    public static final String UTF16LE = "utf16le";
    public static final String ASCII = "ascii";
    public static final int BOM_PROBE_BYTES = 4096;

    private static final Logger log = LoggerFactory.getLogger(FileEncodingReader.class);

    private FileEncodingReader() {}

    /**
     * CC BufferEncoding 名 → Java {@link Charset} 名。CC detectEncoding 产出
     * {@code "utf16le"}/{@code "utf8"}（Node BufferEncoding），Java {@code Charset.forName}
     * 不认识 {@code "utf16le"}（标准名 {@code "UTF-16LE"}），直接 forName 会抛
     * {@link java.nio.charset.UnsupportedCharsetException}。映射后保证读 utf16le 文件不乱码。
     */
    public static String toJavaCharsetName(String encoding) {
        if (encoding == null) {
            return "UTF-8";
        }
        return switch (encoding) {
            case UTF16LE -> "UTF-16LE";
            case UTF8 -> "UTF-8";
            case ASCII -> "US-ASCII";
            default -> encoding;
        };
    }

    /**
     * Detect file encoding by examining the first {@value #BOM_PROBE_BYTES} bytes.
     * Caller supplies the buffer (testable; in production from {@code Files.readAllBytes}).
     *
     * @param buffer     file head bytes
     * @param bytesRead  number of valid bytes in buffer
     * @return encoding name: "utf8", "utf16le", or "ascii"
     */
    public static String detectEncoding(byte[] buffer, int bytesRead) {
        if (buffer == null || bytesRead <= 0) return UTF8;
        if (bytesRead >= 2) {
            if ((buffer[0] & 0xff) == 0xff && (buffer[1] & 0xff) == 0xfe) {
                return UTF16LE;
            }
        }
        if (bytesRead >= 3) {
            if ((buffer[0] & 0xff) == 0xef
                && (buffer[1] & 0xff) == 0xbb
                && (buffer[2] & 0xff) == 0xbf) {
                return UTF8;
            }
        }
        return UTF8;
    }

    /**
     * Detect line ending style. Counts CR (before LF) vs standalone LF. Returns
     * CRLF if more CRLF, otherwise LF.
     */
    public static LineEndingType detectLineEndings(String content) {
        if (content == null || content.isEmpty()) return LineEndingType.LF;
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                if (i > 0 && content.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        return crlf > lf ? LineEndingType.CRLF : LineEndingType.LF;
    }

    /**
     * Read file with full metadata in one filesystem pass.
     * Caller supplies the buffer (so tests can run without real I/O).
     *
     * @param bufferSupplier returns BufferReadResult
     * @return FileMetadata containing content, encoding, lineEndings
     */
    public static FileMetadata readFileSyncWithMetadata(Supplier<BufferReadResult> bufferSupplier) {
        if (bufferSupplier == null) {
            return new FileMetadata("", UTF8, LineEndingType.LF);
        }
        BufferReadResult r = bufferSupplier.get();
        if (r == null) {
            return new FileMetadata("", UTF8, LineEndingType.LF);
        }
        String encoding = detectEncoding(r.buffer(), r.bytesRead());
        // CC: read head only (4096 bytes) to detect line endings before CRLF normalization
        String javaCharset = toJavaCharsetName(encoding);
        int headLen = Math.min(r.buffer().length, BOM_PROBE_BYTES);
        String head = new String(r.buffer(), 0, headLen, Charset.forName(javaCharset));
        LineEndingType endings = detectLineEndings(head);
        // Normalize CRLF to LF
        String full = new String(r.buffer(), 0, r.bytesRead(), Charset.forName(javaCharset))
            .replace("\r\n", "\n");
        return new FileMetadata(full, encoding, endings);
    }

    /**
     * Normalize a line: trim trailing whitespace + CR (per CC).
     */
    public static String processLine(String line) {
        if (line == null) return "";
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == 0x0d || c == ' ' || c == '\t') end--;
            else break;
        }
        return line.substring(0, end);
    }

    /**
     * Convenience: just the content (without encoding/endings metadata).
     */
    public static String readFileSync(Supplier<BufferReadResult> bufferSupplier) {
        return readFileSyncWithMetadata(bufferSupplier).content();
    }

    /**
     * Direct path convenience overload · 对齐 CC {@code readFileSyncWithMetadata}
     * (utils/fileRead.ts:75-98)。读全部字节 → 检测 encoding（BOM）→ 解码（utf16le
     * 保留 ﻿，与 Node readFileSync('utf16le') 一致）→ CRLF 归一化。
     *
     * <p>WHY（IMP-D2 编码保留）：Edit/Write 写回前必须拿到 encoding + lineEndings，
     * 否则 utf16le 恒 UTF-8 读会乱码、CRLF 编辑后转 LF（EV-D2-020/034/091/093）。
     *
     * @param filePath 待读文件
     * @return {content(CRLF 归一), encoding, lineEndings}
     * @throws IOException 读失败（ENOENT 由调用方判 Files.exists）
     */
    public static FileMetadata readFileMetadata(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return readFileSyncWithMetadata(() -> new BufferReadResult(bytes, bytes.length));
    }

    /**
     * 写回保留 encoding + lineEndings · 对齐 CC {@code writeTextContent}
     * (utils/file.ts:84-98)。
     *
     * <p>语义（逐条对齐 CC）：
     * <ul>
     *   <li><b>CRLF</b>：先归一现有 CRLF→LF，再把所有 \n 转 \r\n——防 new_string 已含
     *       \r\n（模型原始输出）变成 \r\r\n（CC :91-95 注释同款）。</li>
     *   <li><b>utf16le</b>：忠实编码 content（UTF-16LE）。<b>不强制前置 BOM</b>——BOM 仅在
     *       content 首字符为 U+FEFF 时随编码产出（Edit 读回保留 BOM 字符 → 写回带 BOM；
     *       Write 模型输入无 U+FEFF → 写回无 BOM）。对齐 CC {@code writeTextContent}
     *       （utils/file.ts:84-98，Node writeFileSync('utf16le') 无自动 BOM，E4 实证：
     *       无 U+FEFF 内容落盘 `68 00 65 00...`；含 U+FEFF 内容落盘 `ff fe 68 00...`）。
     *       [PROBE-BOM DC-1] 旧实现强制前置 BOM 为 Java 独有 ⊕ 行为，已收敛删除
     *       （探查/tool_v4/implementation/probe-bom/probe-report.md §6.2）。</li>
     *   <li><b>utf8</b>：UTF-8 写（含 UTF-8 BOM 时 ﻿ 字符原样保留，与 Java
     *       Files.readString 读回一致，避免 readFileState 内容兜底比对失配）。</li>
     * </ul>
     *
     * @param filePath 目标文件
     * @param content  写回内容（Edit=updatedFile；Write=模型 content 原样）
     * @param encoding detectEncoding 产出的编码名（"utf8"/"utf16le"）
     * @param endings  检测产出的行尾（Edit 保留原行尾；Write 恒 LF）
     * @throws IOException 写失败
     */
    public static void writeTextContent(Path filePath, String content, String encoding,
                                        LineEndingType endings) throws IOException {
        String toWrite = content;
        if (endings == LineEndingType.CRLF) {
            toWrite = content.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        if (UTF16LE.equals(encoding)) {
            // 对齐 CC：忠实编码 content，不强制前置 BOM（PROBE-BOM EV-IMP-001/004）
            byte[] body = toWrite.getBytes(Charset.forName(toJavaCharsetName(encoding)));
            Files.write(filePath, body);
        } else {
            Files.writeString(filePath, toWrite, StandardCharsets.UTF_8);
        }
        if (log.isDebugEnabled()) {
            log.debug("FileEncodingReader: 写回完成 encoding={} endings={} bytes={}（对齐 CC writeTextContent）",
                encoding, endings, toWrite.length());
        }
    }

    /** Test helper record holding a file read. */
    public record BufferReadResult(byte[] buffer, int bytesRead) {
        public BufferReadResult {
            if (buffer == null) buffer = new byte[0];
            if (bytesRead < 0) bytesRead = 0;
            if (bytesRead > buffer.length) bytesRead = buffer.length;
        }
    }
}
