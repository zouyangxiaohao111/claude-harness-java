package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 工具结果持久化器 · 对齐 CC {@code src/utils/toolResultStorage.ts}.
 *
 * <p>CC 源注释：
 * <ul>
 *   <li>Utility for persisting large tool results to disk instead of truncating them.</li>
 *   <li>When exceeded, the result is saved to a file and the model receives a preview with the file path instead of the full content.</li>
 * </ul>
 *
 * <h2>L1 / L2 契约（保留，来自 replicating-python-to-java-systems）</h2>
 * <ul>
 *   <li><b>L1 行为</b>：超阈值 → 写文件 + 给 LLM 看 &lt;persisted-output&gt; 标签 + 路径 + 前 2KB preview</li>
 *   <li><b>L2 契约</b>：路径格式 / 阈值 / preview 大小 / 文件已存在跳过 —— 与 CC 1:1</li>
 *   <li><b>L3 实现</b>：Java NIO + CompletableFuture 异步（CC 同步 await）</li>
 * </ul>
 *
 * <h2>CC 关键路径对齐</h2>
 * <pre>{@code
 * // CC toolResultStorage.ts:27-34
 * export const TOOL_RESULTS_SUBDIR = 'tool-results'
 * export const PERSISTED_OUTPUT_TAG = '<persisted-output>'
 * export const PERSISTED_OUTPUT_CLOSING_TAG = '</persisted-output>'
 * export const TOOL_RESULT_CLEARED_MESSAGE = '[Old tool result content cleared]'
 * export const PREVIEW_SIZE_BYTES = 2000
 * }</pre>
 *
 * <h2>CC 关键函数对齐</h2>
 * <ul>
 *   <li>{@link #getPersistenceThreshold(String, int)} → CC getPersistenceThreshold()</li>
 *   <li>{@link #getPerMessageBudgetLimit()} → CC getPerMessageBudgetLimit()</li>
 *   <li>{@link #getToolResultsDir(Path, String)} → CC getToolResultsDir()</li>
 *   <li>{@link #getToolResultPath(Path, String, String, boolean)} → CC getToolResultPath()</li>
 *   <li>{@link #persistToolResult(Path, String, String, String)} → CC persistToolResult() (async)</li>
 *   <li>{@link #buildLargeToolResultMessage(PersistedToolResult)} → CC buildLargeToolResultMessage()</li>
 *   <li>{@link #generatePreview(String, int)} → CC generatePreview()</li>
 * </ul>
 *
 * @see <a href="https://github.com/.../Open-Claude-code/blob/main/src/utils/toolResultStorage.ts">CC toolResultStorage.ts</a>
 */
public final class ToolResultStorage {

    private static final Logger log = LoggerFactory.getLogger(ToolResultStorage.class);

    private ToolResultStorage() { /* utility class */ }

    // ── CC constants (toolResultStorage.ts:27-34 + toolLimits.ts:13,49) ──

    /** Per-tool persistence threshold（单一真理源 {@link ToolLimits#DEFAULT_MAX_RESULT_SIZE_CHARS}，CC toolLimits.ts:13 = 50_000）。 */
    public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = (int) ToolLimits.DEFAULT_MAX_RESULT_SIZE_CHARS;

    /** Per-message aggregate budget（单一真理源 {@link ToolLimits#MAX_TOOL_RESULTS_PER_MESSAGE_CHARS}，CC toolLimits.ts:49 = 200_000）。 */
    public static final int MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = (int) ToolLimits.MAX_TOOL_RESULTS_PER_MESSAGE_CHARS;

    /** Preview size in bytes (CC PREVIEW_SIZE_BYTES = 2000). */
    public static final int PREVIEW_SIZE_BYTES = 2000;

    /**
     * Bash 输出持久化上限（CC original: {@code MAX_PERSISTED_SIZE = 64 * 1024 * 1024}
     * Open-ClaudeCode/src/tools/BashTool/BashTool.tsx:732）。
     * <p>超过该大小的输出先 truncate 源文件到上限再 link/copy（对齐 CC :741-748），
     * {@code persistedOutputSize} 记录 truncate 前的原始大小。
     */
    public static final long MAX_PERSISTED_SIZE = 64L * 1024 * 1024;

    /** Subdirectory for tool results (CC TOOL_RESULTS_SUBDIR = "tool-results"). */
    public static final String TOOL_RESULTS_SUBDIR = "tool-results";

    /** XML tag wrapper (CC PERSISTED_OUTPUT_TAG). */
    public static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";

    /** XML closing tag (CC PERSISTED_OUTPUT_CLOSING_TAG). */
    public static final String PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>";

    // ── CC getPersistenceThreshold (toolResultStorage.ts:55-78) ──

    /**
     * Resolve effective persistence threshold for a tool.
     * <p>对齐 CC: getPersistenceThreshold(toolName, declaredMaxResultSizeChars)
     * <ul>
     *   <li>declaredMaxResultSizeChars = {@code Integer.MAX_VALUE} 时 → 透传（等同于 Infinity = hard opt-out）</li>
     *   <li>否则 → Math.min(declaredMaxResultSizeChars, DEFAULT_MAX_RESULT_SIZE_CHARS)</li>
     * </ul>
     *
     * @param toolName tool name（用于将来 GrowthBook override 留位）
     * @param declaredMaxResultSizeChars 工具声明的 maxResultSizeChars
     * @return effective threshold
     */
    public static int getPersistenceThreshold(String toolName, int declaredMaxResultSizeChars) {
        // CC: if (!Number.isFinite(declaredMaxResultSizeChars)) return declaredMaxResultSizeChars
        // Java 端：Integer.MAX_VALUE 标记 opt-out
        if (declaredMaxResultSizeChars == Integer.MAX_VALUE) {
            return declaredMaxResultSizeChars;
        }
        // GrowthBook override 在 R28-3 中尚未集成（CC tengu_satin_quoll flag 暂未移植）
        // 当前 Java 端采用 CC 默认行为：Math.min(declaredMaxResultSizeChars, DEFAULT_MAX_RESULT_SIZE_CHARS)
        return Math.min(declaredMaxResultSizeChars, DEFAULT_MAX_RESULT_SIZE_CHARS);
    }

    /** Default per-message budget limit (CC tengu_hawthorn_window flag 暂未移植，常量值). */
    public static int getPerMessageBudgetLimit() {
        return MAX_TOOL_RESULTS_PER_MESSAGE_CHARS;
    }

    // ── CC getToolResultsDir / getToolResultPath (toolResultStorage.ts:97-117) ──

    /**
     * Get the tool results directory for this session: {configHome}/projects/{slug}/{sessionId}/tool-results/
     * <p>对齐 CC: getToolResultsDir() = join(getSessionDir(), TOOL_RESULTS_SUBDIR)
     * （S2 迁移：锚点从 workspaceDir 迁 config-home，seam 内部做 config-home 派生）
     */
    public static Path getToolResultsDir(Path workspaceDir, String sessionId) {
        return SessionStorage.getProjectDir(workspaceDir).resolve(sessionId).resolve(TOOL_RESULTS_SUBDIR);
    }

    /**
     * Get the filepath where a tool result would be persisted.
     * <p>对齐 CC: getToolResultPath(id, isJson) → {dir}/{id}.{ext}
     */
    public static Path getToolResultPath(Path workspaceDir, String sessionId, String toolUseId, boolean isJson) {
        String ext = isJson ? "json" : "txt";
        return getToolResultsDir(workspaceDir, sessionId).resolve(toolUseId + "." + ext);
    }

    /**
     * 确保会话级 tool-results 目录存在。
     * <p>对齐 CC {@code ensureToolResultsDir()}（Open-ClaudeCode/src/utils/toolResultStorage.ts:122，
     * {@code mkdir(recursive)} 包 try/catch{}，目录已存在时静默通过）。
     *
     * @param workspaceDir 工作区根目录（{@code ToolUseContext.effectiveCwd()}）
     * @param sessionId    会话 ID
     */
    public static void ensureToolResultsDir(Path workspaceDir, String sessionId) {
        try {
            Files.createDirectories(getToolResultsDir(workspaceDir, sessionId));
        } catch (Exception e) {
            // 目录可能已存在（CC catch{} 静默）· 后续 link/copy 若仍失败由外层 catch 降级
            if (log.isDebugEnabled()) {
                log.debug("ToolResultStorage ensureToolResultsDir 静默跳过: dir={} err={}",
                    getToolResultsDir(workspaceDir, sessionId), e.toString());
            }
        }
    }

    // ── CC persistToolResult (toolResultStorage.ts:137-184, async via CompletableFuture) ──

    /**
     * Persist a tool result to disk.
     * <p>对齐 CC persistToolResult 返回 PersistedToolResult | PersistToolResultError.
     * Java 端 L3 升级：CompletableFuture 异步（CC 是同步 await）.
     *
     * <p>行为：
     * <ol>
     *   <li>确保目录存在（mkdir -p）</li>
     *   <li>Files.writeString with CREATE_NEW (= CC 'wx' flag) — 文件已存在则跳过</li>
     *   <li>生成 preview（generatePreview）</li>
     *   <li>返回 PersistedToolResult（包含 filepath + size + preview + hasMore）</li>
     * </ol>
     *
     * @return CompletableFuture&lt;PersistedToolResult&gt; 失败时返回 null（CC error 通道）
     */
    public static CompletableFuture<PersistedToolResult> persistToolResult(
            Path workspaceDir, String sessionId, String content, String toolUseId) {
        if (workspaceDir == null || sessionId == null || content == null || toolUseId == null) {
            return CompletableFuture.completedFuture(null);
        }
        Path filepath = getToolResultPath(workspaceDir, sessionId, toolUseId, false);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // mkdir -p
                Path parent = filepath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // CREATE_NEW = exclusive create, fail if exists (CC 'wx' flag)
                try {
                    Files.writeString(filepath, content,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    log.debug("ToolResultStorage persisted: path={} size={}",
                        filepath, content.length());
                } catch (FileAlreadyExistsException e) {
                    // CC: EEXIST → fall through to preview generation
                    log.debug("ToolResultStorage already persisted: path={}", filepath);
                }
                Preview preview = generatePreview(content, PREVIEW_SIZE_BYTES);
                return new PersistedToolResult(filepath.toString(), content.length(), false,
                    preview.text(), preview.hasMore());
            } catch (Exception e) {
                // R28-3.7 §1.11: 区分 FS 错误码 (CC toolResultStorage.ts:1017-1040 getFileSystemErrorMessage)
                String fsError = getFileSystemErrorMessage(e, filepath);
                log.warn("ToolResultStorage persist failed: path={} error={} msg={}",
                    filepath, fsError, e.getMessage());
                // Oops, write failed — return null to signal error
                throw new CompletionException(e);
            }
        }).handle((result, ex) -> {
            // Map exception to null (CC error path)
            if (ex != null) {
                return null;
            }
            return result;
        });
    }

    // ── CC BashTool.call 持久化块 (BashTool.tsx:731-752) 的等价文件载体 ──

    /**
     * Bash 输出落盘：把 BashTool 捕获的完整输出文件持久化到 tool-results 目录。
     * <p>镜像 CC {@code BashTool.call} 大输出分支（Open-ClaudeCode/src/tools/BashTool/BashTool.tsx:731-752）：
     * <ol>
     *   <li>stat 源文件 → {@code persistedOutputSize = 原大小}（截断前，CC :738）</li>
     *   <li>ensureToolResultsDir（:739）→ dest=getToolResultPath(outputTaskId,false)（:740）</li>
     *   <li>size &gt; maxPersistedSize 先 truncate 源文件（:741-743）</li>
     *   <li>try link（:745）→ catch copyFile REPLACE_EXISTING（:747-748）</li>
     *   <li>persistedOutputPath=dest（:749）；preview 取源文件前 {@link #PREVIEW_SIZE_BYTES} 字节</li>
     * </ol>
     * 整体包 try/catch{}（CC :736/:750）：任何失败返回 null，由调用方降级保留 stdout preview，不抛错。
     *
     * <p>Java 偏离（WF-E-D2 登记）：CC 的 {@code outputTaskId} 是 shell 任务 ID，Java 无 shell 任务机制，
     * 用工具调用 ID（{@code call.id()}）替代；dest={workspaceDir}/{sessionId}/tool-results/{outputTaskId}.txt，
     * 与组装层 {@link #persistToolResult} 同目录。preview 直接从源文件前 2000 字节读取（等价 CC
     * {@code generatePreview(processedStdout, PREVIEW_SIZE_BYTES)}，CC 的 processedStdout 即模型侧 stdout 前缀，
     * 与落盘文件前缀一致）。
     *
     * @param workspaceDir    工作区根目录（{@code ToolUseContext.effectiveCwd()}）
     * @param sessionId       会话 ID
     * @param outputTaskId    CC outputTaskId 的 Java 等价（工具调用 ID）
     * @param sourceFile      BashTool 捕获的完整输出临时文件
     * @param maxPersistedSize 超过该字节数先 truncate 源（CC MAX_PERSISTED_SIZE，默认 64MB）
     * @return 成功 {@link PersistedToolResult}（filepath=dest、originalSize=截断前原始大小）；失败 null
     */
    public static PersistedToolResult persistOutputFile(Path workspaceDir, String sessionId,
            String outputTaskId, Path sourceFile, long maxPersistedSize) {
        if (workspaceDir == null || sessionId == null || outputTaskId == null
                || sourceFile == null) {
            return null;
        }
        try {
            // CC :738 — 先 stat 记录原始大小（截断前），再决定是否 truncate
            long originalSize = Files.size(sourceFile);
            ensureToolResultsDir(workspaceDir, sessionId);
            Path dest = getToolResultPath(workspaceDir, sessionId, outputTaskId, false);
            // CC :741-743 — 超上限先 truncate 源文件（硬链接共享 inode，截断后 link 落盘内容即截断版）
            if (originalSize > maxPersistedSize) {
                truncateTo(sourceFile, maxPersistedSize);
                if (log.isDebugEnabled()) {
                    log.debug("ToolResultStorage persistOutputFile truncate 源到 {} 字节: source={}",
                        maxPersistedSize, sourceFile);
                }
            }
            // CC :745-748 — 先 link，失败（跨盘/权限/已存在）回退 copy 覆盖
            try {
                Files.createLink(dest, sourceFile);
            } catch (Exception linkFail) {
                Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
                if (log.isDebugEnabled()) {
                    log.debug("ToolResultStorage persistOutputFile link 失败回退 copy: dest={} err={}",
                        dest, linkFail.toString());
                }
            }
            Preview preview = generatePreview(readFirstBytes(sourceFile, PREVIEW_SIZE_BYTES),
                PREVIEW_SIZE_BYTES);
            if (log.isDebugEnabled()) {
                log.debug("ToolResultStorage persistOutputFile 落盘成功: dest={} originalSize={} previewLen={}",
                    dest, originalSize, preview.text().length());
            }
            return new PersistedToolResult(dest.toString(), (int) originalSize, false,
                preview.text(), preview.hasMore());
        } catch (Exception e) {
            // CC :736/:750 — 整体 catch{}：文件可能已消失，stdout preview 足够，降级不抛错
            if (log.isDebugEnabled()) {
                log.debug("ToolResultStorage persistOutputFile 降级（保留 stdout preview）: source={} err={}",
                    sourceFile, e.toString());
            }
            return null;
        }
    }

    /** 把文件长度截断到指定字节数（CC fsTruncate(path, len) 等价）。 */
    private static void truncateTo(Path file, long length) throws java.io.IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(length);
        }
    }

    /** 读取文件前 maxBytes 字节为 UTF-8 字符串（文件不足则读全部）。 */
    private static String readFirstBytes(Path file, int maxBytes) throws java.io.IOException {
        byte[] buffer = new byte[maxBytes];
        try (var in = Files.newInputStream(file)) {
            int n = in.read(buffer);
            if (n < 0) {
                return "";
            }
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        }
    }

    /**
     * 把文件系统异常翻译到可读消息（对齐 CC toolResultStorage.ts:1017-1040 getFileSystemErrorMessage).
     * <p>R28-3.7 §1.11 修复：Java 端原先一律 log.warn + 静默化为 null, 不区分错误类型也不向 caller
     * 暴露 ENOENT/EACCES/ENOSPC 等可操作的信息。现区分：
     * <ul>
     *   <li>ENOENT (NoSuchFileException) → "Directory not found: {path}"</li>
     *   <li>EACCES (AccessDeniedException) → "Permission denied: {path}"</li>
     *   <li>ENOSPC → "No space left on device"</li>
     *   <li>EROFS → "Read-only file system"</li>
     *   <li>EEXIST (FileAlreadyExistsException) → "File already exists" (正常路径，不当作 error)</li>
     *   <li>其它 → "{className}: {message}"</li>
     * </ul>
     */
    public static String getFileSystemErrorMessage(Throwable error, Path path) {
        if (error == null) return null;
        // Java NIO 已经把 FS 错误抽象成具名异常，省去对应 errno code
        if (error instanceof NoSuchFileException) {
            return "Directory not found: " + (path != null ? path : "unknown path");
        }
        if (error instanceof AccessDeniedException) {
            return "Permission denied: " + (path != null ? path : "unknown path");
        }
        if (error instanceof FileSystemException) {
            // FileSystemException 子类（非上面三个）: 可能是 ENOSPC / EROFS / EMFILE 等
            // 通过 message 字符串匹配（Java NIO 没有 getReason() 这种语义化 API）
            String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            String lower = msg.toLowerCase();
            if (lower.contains("no space")) {
                return "No space left on device";
            }
            if (lower.contains("read-only")) {
                return "Read-only file system";
            }
            return msg;
        }
        if (error instanceof FileAlreadyExistsException) {
            return "File already exists: " + (path != null ? path : "unknown path");
        }
        String className = error.getClass().getSimpleName();
        String message = error.getMessage() != null ? error.getMessage() : "(no message)";
        return className + ": " + message;
    }

    // ── CC buildLargeToolResultMessage (toolResultStorage.ts:189-199) ──

    /**
     * Build a message for large tool results with preview.
     * <p>对齐 CC buildLargeToolResultMessage() 完整格式:
     * <pre>
     * &lt;persisted-output&gt;
     * Output too large (X). Full output saved to: /path/to/file
     *
     * Preview (first 2KB):
     * <前 2KB 内容>
     * ...
     * &lt;/persisted-output&gt;
     * </pre>
     */
    public static String buildLargeToolResultMessage(PersistedToolResult result) {
        if (result == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(PERSISTED_OUTPUT_TAG).append("\n");
        sb.append("Output too large (").append(formatFileSize(result.originalSize()))
          .append("). Full output saved to: ").append(result.filepath()).append("\n\n");
        sb.append("Preview (first ").append(formatFileSize(PREVIEW_SIZE_BYTES)).append("):\n");
        sb.append(result.preview());
        sb.append(result.hasMore() ? "\n...\n" : "\n");
        sb.append(PERSISTED_OUTPUT_CLOSING_TAG);
        return sb.toString();
    }

    // ── CC generatePreview (toolResultStorage.ts:339-355) ──

    /**
     * Generate a preview of content, truncating at a newline boundary when possible.
     * <p>CC: cut at lastNewline if > 50% of maxBytes, else at maxBytes exact.
     */
    public static Preview generatePreview(String content, int maxBytes) {
        if (content == null) return new Preview("", false);
        if (content.length() <= maxBytes) {
            return new Preview(content, false);
        }
        String truncated = content.substring(0, maxBytes);
        int lastNewline = truncated.lastIndexOf('\n');
        int cutPoint = lastNewline > maxBytes * 0.5 ? lastNewline : maxBytes;
        return new Preview(content.substring(0, cutPoint), true);
    }

    // ── CC formatFileSize ──

    /**
     * Format file size · 对齐 CC {@code formatFileSize}（utils/format.ts:9-23）。
     *
     * <p>[G28① TR-E2-DEC-1] 旧实现整数截断 + MB 封顶（"1KB"/"2MB"）→ 对齐 CC：{@code <1KB →
     * "{size} bytes"}；{@code <1024KB → "{kb.toFixed(1)}KB"}（去 .0 尾）；MB/GB 同理。
     * 例：{@code formatFileSize(1536) → "1.5KB"}。影响 MCP binary 落盘消息文本 + File 族 saved
     * 消息（McpOutputStorage/McpResultTransformer/EditFileTool/ReadFileTool/PdfSupport 等，域 D/E 协调）。
     */
    public static String formatFileSize(long bytes) {
        double kb = bytes / 1024.0;
        if (kb < 1) {
            return bytes + " bytes";
        }
        if (kb < 1024) {
            return trimTrailingDotZero(String.format(java.util.Locale.ROOT, "%.1f", kb)) + "KB";
        }
        double mb = kb / 1024;
        if (mb < 1024) {
            return trimTrailingDotZero(String.format(java.util.Locale.ROOT, "%.1f", mb)) + "MB";
        }
        double gb = mb / 1024;
        return trimTrailingDotZero(String.format(java.util.Locale.ROOT, "%.1f", gb)) + "GB";
    }

    /** CC {@code toFixed(1).replace(/\.0$/, '')}（format.ts:15/19/22）等价：去掉 {@code .0} 尾。 */
    private static String trimTrailingDotZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ── CC mcpOutputStorage.ts persistBinaryContent / extensionForMimeType（RV-FOLLOWUP MCP-01）──

    /**
     * 写原生二进制字节到 tool-results 目录 · 对齐 CC {@code mcpOutputStorage.ts:148-174 persistBinaryContent}。
     *
     * <p>与 {@link #persistToolResult}（字符串化）不同：本方法按原生字节写盘，产物可由 Read /
     * 原生工具直接打开。目录语义 = {@code {workspaceDir}/{sessionId}/tool-results}（session 级，
     * 复用 {@link #getToolResultsDir(Path, String)}，不建第二份 dir 逻辑）。
     *
     * <p>返回 {@link BinaryPersistResult}：{@code error == null} 成功（携带 filepath/size/ext）；
     * {@code error != null} 失败（携带可读 FS 错误消息，供 MCP audio 失败模板使用）。
     * Java 端 L3 升级：CompletableFuture 异步（CC 同步 await）。
     *
     * @param workspaceDir workspace 根目录（{@code ToolUseContext.effectiveCwd()}）
     * @param sessionId    会话 ID（目录段）
     * @param bytes        原生字节（audio base64 decode 产物）
     * @param mimeType     MIME 类型（可为 null → 默认 'bin'）
     * @param persistId    持久化 ID（CC client.ts:2604 {@code mcp-${normalizeNameForMCP(serverName)}-blob-...}）
     * @return 成功 (filepath, size, ext) 或失败 (error 非空) 的 CompletableFuture
     */
    public static CompletableFuture<BinaryPersistResult> persistBinaryContent(
            Path workspaceDir, String sessionId, byte[] bytes, String mimeType, String persistId) {
        if (workspaceDir == null || sessionId == null || bytes == null || persistId == null) {
            return CompletableFuture.completedFuture(
                new BinaryPersistResult(null, 0, null, "workspaceDir/sessionId/bytes/persistId is null"));
        }
        String ext = extensionForMimeType(mimeType);
        Path filepath = getToolResultsDir(workspaceDir, sessionId).resolve(persistId + "." + ext);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path parent = filepath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);   // mkdir -p（镜像 persistToolResult）
                }
                // 对齐 CC writeFile(filepath, bytes) 默认 'w' 覆写语义（persistId 唯一，无 EEXIST 冲突）
                Files.write(filepath, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log.debug("ToolResultStorage persisted binary: path={} size={} ext={}",
                    filepath, bytes.length, ext);
                return new BinaryPersistResult(filepath.toString(), bytes.length, ext, null);
            } catch (Exception e) {
                // 对齐 CC {error: err.message} · 复用 FS 错误码翻译
                String fsError = getFileSystemErrorMessage(e, filepath);
                log.warn("ToolResultStorage binary persist failed: path={} error={} msg={}",
                    filepath, fsError, e.getMessage());
                return new BinaryPersistResult(null, 0, null, fsError);
            }
        });
    }

    /**
     * MIME → 文件扩展名 · 对齐 CC {@code mcpOutputStorage.ts:66-116 extensionForMimeType}。
     *
     * <p>保守映射：已知类型给正确 ext，未知 → 'bin'（ext 决定 Read 工具分发）。先剥离 charset/
     * boundary 参数（{@code split(';')[0] trim toLowerCase}），再 switch 全表。
     */
    public static String extensionForMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "bin";
        }
        String mt = mimeType.split(";")[0].trim().toLowerCase();
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

    // ── CC PersistedToolResult (toolResultStorage.ts:81-87) ──

    /**
     * 对齐 CC PersistedToolResult type.
     */
    public record PersistedToolResult(
        String filepath,
        int originalSize,
        boolean isJson,
        String preview,
        boolean hasMore
    ) {}

    /** Preview holder (CC inline object). */
    public record Preview(String text, boolean hasMore) {}

    /**
     * 二进制持久化结果联合 · 对齐 CC {@code PersistBinaryResult}（mcpOutputStorage.ts:139-141，
     * {@code {filepath, size, ext} | {error: string}}）。
     *
     * <p>{@code error == null} 成功（filepath/size/ext 有效）；{@code error != null} 失败
     * （携带可读消息，供 MCP audio 失败文本模板使用）。
     */
    public record BinaryPersistResult(String filepath, long size, String ext, String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // collectCandidatesByMessage 合并器 · 对齐 CC toolResultStorage.ts:600-639
    //（:557-573 collectCandidatesFromMessage）
    //
    // 【IMP-13 D-17】宿主迁移：原宿主（管线级工具结果预算压缩器类）已删除
    // （管线级截断为 DRIFT-19 双实现漂移，OD-10 裁决删除）。本合并器为全仓唯一宿主
    // （D-05/IMP-22 迁移去重，3 处留 1），随 D-17 迁回 CC 真源同名类 toolResultStorage.ts。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 按 API-level user message group 收集工具结果候选（合并器）· 对齐 CC
     * {@code toolResultStorage.ts:600-639 collectCandidatesByMessage}
     * + {@code :557-573 collectCandidatesFromMessage}。
     *
     * <p><b>为何重要 (intent)</b>: normalizeMessagesForAPI 会把同 ID 的助手片段合并成一条
     * wire assistant 消息（messages.ts:2126 用 {@code continue} 跨过不同 ID 的 assistant）。
     * CC 的 {@code seenAsstIds} Set 追踪已见 assistant 消息 ID：同 ID 助手片段<b>不应</b>作为
     * group 边界 — 它们其实是同一 assistant 消息的不同片段。两种典型场景（toolResultStorage.ts:614-622）：
     * <ul>
     *   <li><b>连续</b>：streamingToolExecution 每个 {@code content_block_stop} 产出一条同 ID 的
     *       AssistantMessage，快速工具在 blocks 间 drain 留下 {@code [asst(X), tool(trA), asst(X), tool(trB)]}。</li>
     *   <li><b>交错</b>：coordinator/teammate 流混合产生
     *       {@code [asst(X), tool(trA), asst(Y), tool(trB), asst(X), tool(trC)]}。</li>
     * </ul>
     * 两种情况下 normalizeMessagesForAPI 都会把 X 片段合并成一条 wire assistant，budget 校验
     * 也应把它们视为同一 group — 否则同 ID 助手片段的 tool_results 会被预算器分到两组各算一次，
     * 可能将一组冻结的内容错认为 fresh 再次替换。
     *
     * <p><b>候选载体（Java 模型对应）</b>: CC 的 tool_result 是 user message 内的 block
     * （collectCandidatesFromMessage 从 {@code message.type==='user'} 提取
     * {@code block.type==='tool_result'}）。Java 端把 tool_result 扁平化为 {@link Role#tool}
     * ChatMessageDto（{@code LlmAgentLoop.toolResultMessage} 工厂；Provider 翻译时
     * Role.tool → role=user，LLM 视角即 user 消息），因此候选载体是 Role.tool 消息而非 Role.user。
     * 过滤条件（collectCandidatesFromMessage :561-565）：有 toolUseId + 非空 content +
     * 非已压缩（isContentAlreadyCompacted :498-504，content 以
     * {@value ToolResultStorage#PERSISTED_OUTPUT_TAG} 开头）+ 非含 image 块
     * （hasImageBlock :507-516 / :564，带图片的 tool_result 不可替换成文本 preview 丢失）。
     *
     * @param messages 消息列表（按时间顺序）
     * @return 分组后的候选（每组内 Role.tool 候选视为同一 API-level user message）
     */
    public static List<List<ChatMessageDto>> collectCandidatesByMessage(List<ChatMessageDto> messages) {
        List<List<ChatMessageDto>> groups = new ArrayList<>();
        List<ChatMessageDto> current = new ArrayList<>();
        Set<String> seenAsstIds = new HashSet<>();

        if (messages != null) {
            for (ChatMessageDto message : messages) {
                if (message.role() == Role.tool) {
                    // collectCandidatesFromMessage (:561-565): tool_result + content + 非已压缩 + 非含 image 块
                    if (isEligibleToolResultCandidate(message)) {
                        current.add(message);
                    }
                } else if (message.role() == Role.assistant) {
                    // seenAsstIds: 同 ID 助手片段不作为 group 边界（:627-631）
                    String asstId = message.assistantMessageId() != null
                        ? message.assistantMessageId()
                        : message.id();
                    if (!seenAsstIds.contains(asstId)) {
                        flush(current, groups);
                        seenAsstIds.add(asstId);
                    }
                }
                // progress / system / attachment 被 normalizeMessagesForAPI 过滤或合并 —
                // 不创建 wire 边界（toolResultStorage.ts:633-634）
            }
        }
        flush(current, groups);

        if (log.isDebugEnabled()) {
            log.debug("[ToolResultStorage.collectCandidatesByMessage] 候选收集完成: {} 条消息 → {} 组 (seenAsstIds={})",
                messages != null ? messages.size() : 0, groups.size(), seenAsstIds.size());
        }
        return groups;
    }

    /**
     * 候选资格判定 · 对齐 CC collectCandidatesFromMessage (toolResultStorage.ts:561-565)：
     * {@code block.type === 'tool_result' && block.content && !isContentAlreadyCompacted(content)
     * && !hasImageBlock(content)}。Java 端 Role.tool 即 tool_result block 的扁平表示。
     */
    private static boolean isEligibleToolResultCandidate(ChatMessageDto m) {
        return m.toolCallId() != null && m.content() != null
            && !m.content().startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG)
            && !hasImageBlock(m.contentBlocks());
    }

    /**
     * 是否含 image 块 · 对齐 CC hasImageBlock（CC original: hasImageBlock,
     * toolResultStorage.ts:564 collectCandidatesFromMessage 内调用；定义 :507-516）。
     *
     * <p>CC 行为：{@code Array.isArray(content) && content.some(b => b.type === 'image')}
     * —— 带图片的 tool_result 不是消息级总预算（budgetAggregateGate）的候选替换对象
     * （图片不能被替换成文本 preview 丢失）。Java 端 tool_result 的 content 块数组扁平化为
     * {@link ChatMessageDto#contentBlocks()}（透传 List&lt;JsonNode&gt;，块含 {@code "type"} 字段），
     * 与 LlmAgentLoop.countImageBlocks 同源判断。空 / 纯文本 / document / tool_reference 块 → false。
     *
     * @param contentBlocks 消息 content 块列表（可 null）
     * @return 含 type==='image' 块 → true
     */
    private static boolean hasImageBlock(List<?> contentBlocks) {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return false;
        }
        for (Object block : contentBlocks) {
            if (block instanceof ContentBlockParam.ImageBlockParam) {
                return true;
            }
            if (block instanceof JsonNode node
                && node.isObject() && node.has("type") && "image".equals(node.get("type").asText())) {
                return true;
            }
        }
        return false;
    }

    /** flush 当前组（对齐 CC collectCandidatesByMessage 的 {@code flush()}，:606-609）。 */
    private static void flush(List<ChatMessageDto> current, List<List<ChatMessageDto>> groups) {
        if (!current.isEmpty()) {
            groups.add(new ArrayList<>(current));
            current.clear();
        }
    }
}
