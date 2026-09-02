package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.ToolResultStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * MCP 二进制内容落盘 · 对齐 CC {@code src/utils/mcpOutputStorage.ts}（仅移植 D2 需要的三个函数：
 * {@code extensionForMimeType} / {@code persistBinaryContent} / {@code getBinaryBlobSavedMessage}）。
 *
 * <p>CC 真源（grep 自验，不信注释）：
 * <ul>
 *   <li>{@code extensionForMimeType} (mcpOutputStorage.ts:66-118)：已知 mime 类型 → 对应扩展名，
 *       未知/无 → {@code 'bin'}（Read 工具按扩展名分发 PDF/图片等）。</li>
 *   <li>{@code persistBinaryContent} (mcpOutputStorage.ts:148-174)：把原始字节按原样写入
 *       tool-results 目录 {@code {persistId}.{ext}}；失败返回 {@code {error}}，不抛。</li>
 *   <li>{@code getBinaryBlobSavedMessage} (mcpOutputStorage.ts:181-189)：
 *       {@code `${sourceDescription}Binary content (${mimeType||'unknown type'}, ${formatFileSize(size)}) saved to ${filepath}`}。</li>
 * </ul>
 *
 * <p>Java 偏离（登记 WF-D-O4）：CC {@code persistBinaryContent} 内部调用
 * {@code ensureToolResultsDir() + getToolResultsDir()}（会话级目录）；Java 端落盘介质
 * 由调用方传入目录（工具在 execute 内基于 {@code ToolUseContext} 计算），
 * 本类只做「字节 → 文件」纯函数，不隐式依赖会话目录。
 *
 * <p>数据流日志：slf4j + logback + 中文 + {@code if (log.isDebugEnabled())} 包裹 debug。
 */
public final class McpOutputStorage {

    private static final Logger log = LoggerFactory.getLogger(McpOutputStorage.class);

    private McpOutputStorage() {
        // utility class
    }

    /**
     * Mime 类型 → 文件扩展名 · 对齐 CC {@code mcpOutputStorage.ts:66-118 extensionForMimeType}.
     *
     * <p>已知类型返回正规扩展名；未知类型返回 {@code 'bin'}。扩展名重要：Read 工具按扩展名
     * 分发（PDF / 图片等需要正确后缀）。
     *
     * @param mimeType MIME 类型（可为 null / 带 charset 参数）
     * @return 文件扩展名（无前导点）
     */
    public static String extensionForMimeType(String mimeType) {
        // CC :67 if (!mimeType) return 'bin'
        if (mimeType == null || mimeType.isBlank()) {
            return "bin";
        }
        // CC :68-69 strip charset/boundary 参数 + trim + lowercase
        String mt = (mimeType.split(";")[0]).trim().toLowerCase();
        return switch (mt) {
            case "application/pdf" -> "pdf";
            case "application/json" -> "json";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "text/markdown" -> "md";
            case "application/zip" -> "zip";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/msword" -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    /** CC original: PersistBinaryResult（mcpOutputStorage.ts:138-140）— 成功 {filepath,size,ext} | 失败 {error}. */
    public record PersistBinaryResult(String filepath, long size, String ext, String error) {

        /** 成功结果（CC {filepath,size,ext}）。 */
        public static PersistBinaryResult ok(String filepath, long size, String ext) {
            return new PersistBinaryResult(filepath, size, ext, null);
        }

        /** 失败结果（CC {error}）。 */
        public static PersistBinaryResult failed(String error) {
            return new PersistBinaryResult(null, 0L, null, error);
        }

        public boolean isError() {
            return error != null;
        }
    }

    /**
     * 把原始二进制字节写入 tool-results 目录 · 对齐 CC {@code mcpOutputStorage.ts:148-174 persistBinaryContent}.
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:154 扩展名 = extensionForMimeType(mimeType)</li>
     *   <li>:155 filepath = join(getToolResultsDir(), '{persistId}.{ext}')</li>
     *   <li>:157-163 writeFile 失败 → 返回 {error}（不抛）</li>
     *   <li>:165-171 logEvent + 返回 {filepath, size: bytes.length, ext}</li>
     * </ul>
     *
     * <p>Java 偏离：落盘目录由调用方传入（WF-D-O4 登记），非隐式会话目录。
     *
     * @param toolResultsDir 落盘目录（tool-results，调用方基于 ToolUseContext 计算）
     * @param bytes          原始字节（CC Buffer，blob base64 解码后）
     * @param mimeType       MIME 类型（可为 null）
     * @param persistId      持久化标识（CC 由调用方生成，如 {@code mcp-resource-{ts}-{i}-{rand}}）
     * @return PersistBinaryResult（ok 含 filepath/size/ext；失败含 error）
     */
    public static PersistBinaryResult persistBinaryContent(
            Path toolResultsDir, byte[] bytes, String mimeType, String persistId) {
        if (toolResultsDir == null) {
            return PersistBinaryResult.failed("tool-results directory is null");
        }
        String ext = extensionForMimeType(mimeType);
        Path filepath = toolResultsDir.resolve(persistId + "." + ext);
        try {
            Files.createDirectories(toolResultsDir);
            Files.write(filepath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (log.isDebugEnabled()) {
                log.debug("[McpOutputStorage] 二进制内容已落盘: 路径={} 大小={}B 扩展名={} mime={}",
                    filepath, bytes.length, ext, mimeType != null ? mimeType : "unknown");
            }
            return PersistBinaryResult.ok(filepath.toString(), bytes.length, ext);
        } catch (IOException e) {
            log.warn("[McpOutputStorage] 二进制内容落盘失败: 路径={} mime={} 原因={}",
                filepath, mimeType, e.getMessage());
            return PersistBinaryResult.failed(e.getMessage());
        }
    }

    /**
     * 构建"二进制内容已保存到何处"的提示消息 · 对齐 CC {@code mcpOutputStorage.ts:181-189 getBinaryBlobSavedMessage}.
     *
     * <p>只陈述路径，无建议性提示（模型能用文件做什么取决于 provider/tooling）。
     *
     * @param filepath          保存路径（CC persisted.filepath）
     * @param mimeType          MIME 类型（可为 null → 'unknown type'）
     * @param size              字节数
     * @param sourceDescription 来源描述前缀（CC ReadMcpResourceTool.ts:135 {@code `[Resource from ${serverName} at ${c.uri}] `}）
     * @return 提示消息文本
     */
    public static String getBinaryBlobSavedMessage(
            String filepath, String mimeType, long size, String sourceDescription) {
        // CC :187 const mt = mimeType || 'unknown type'
        String mt = (mimeType == null || mimeType.isBlank()) ? "unknown type" : mimeType;
        // CC :188 return `${sourceDescription}Binary content (${mt}, ${formatFileSize(size)}) saved to ${filepath}`
        return (sourceDescription != null ? sourceDescription : "")
            + "Binary content (" + mt + ", " + ToolResultStorage.formatFileSize(size) + ") saved to " + filepath;
    }
}
