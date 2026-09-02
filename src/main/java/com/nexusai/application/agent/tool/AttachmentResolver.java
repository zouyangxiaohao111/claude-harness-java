package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BriefTool 附件验证与解析 · 对齐 CC tools/BriefTool/attachments.ts.
 *
 * <p>L1 语义: 共享附件 validation + resolution (SendUserMessage + SendUserFile).
 *            - validateAttachmentPaths: stat 每个 path,检查 isFile,处理 ENOENT/EACCES/EPERM.
 *            - resolveAttachments: 串行 stat (本地,快,确定性顺序) → if BRIDGE_MODE 并行上传拿 uuid.
 *              上传失败 → attachment 仍带 {path, size, isImage},本地渲染不受影响.
 *            - IMAGE_EXTENSION_REGEX 判断 isImage 字段.
 *
 * <p><b>[G20②] read-deny 静默跳过已删除</b>：旧实现（[Session L · G3]）在 stat 前查
 * {@code isFileReadDenied}（对齐 CC utils/attachments.ts:3144）并静默跳过命中附件。拍板 OPD-TR-H2-02：
 * CC BriefTool 附件链（BriefTool/attachments.ts:63-110）<b>无此逻辑</b>——{@code isFileReadDenied}
 * 属 utils/attachments.ts 的 IDE 选区 / at-mention 路径（:1629/:1873/:1909/:2083/:3041），非
 * Brief 附件解析路径。删除 permCtxSupplier / isReadDenied / READ_TOOL_STUB，resolveAttachments 回归
 * CC 纯 stat 链（BriefTool/attachments.ts:70-82）。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: ResolvedAttachment 4 字段 (path/size/isImage/fileUuid?);
 *       ValidationResult (result+message?+errorCode?);
 *       2 个公开函数 (validateAttachmentPaths + resolveAttachments);
 *       bridge mode 门控 (replBridgeEnabled || CLAUDE_CODE_BRIEF_UPLOAD).</li>
 *   <li><b>A2 Golden Trace</b>: validate: for each path → stat → isFile check → ENOENT/EACCES/EPERM message → return ValidationResult.
 *       resolve: for each path → stat → build ResolvedAttachment → if bridge → 并行 upload → 合并 file_uuid.</li>
 *   <li><b>A3</b>: 状态: stat 结果 → 4 种分支 (file/ENOENT/EACCES/other throw);
 *       bridge 模式: off → 直接 return stated;on → 尝试上传 (失败不阻断).</li>
 *   <li><b>A4</b>: ENOENT → message 含 cwd;EACCES/EPERM → permission denied message;
 *       其他异常 → throw;not a regular file → not a regular file message;
 *       上传失败 (exception/non-201) → file_uuid=null,attachment 仍有效.</li>
 *   <li><b>A5</b>: 真实场景 — 用户发图 .png → resolve → isImage=true → 上传到 bridge → 拿到 file_uuid;
 *       上传失败 → 本地渲染 path/size 仍生效.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `fs/promises stat` → 注入式 FileStat Function;
 *                    TS `process.env.CLAUDE_CODE_BRIEF_UPLOAD` → 注入式 BooleanSupplier;
 *                    TS `feature('BRIDGE_MODE')` → BooleanSupplier;
 *                    TS `IMAGE_EXTENSION_REGEX.test()` → Pattern matcher.
 */
public final class AttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(AttachmentResolver.class);

    /** CC IMAGE_EXTENSION_REGEX — 图像后缀匹配. */
    public static final Pattern IMAGE_EXTENSION_REGEX = Pattern.compile(
        "\\.(png|jpe?g|gif|webp|bmp|ico)$", Pattern.CASE_INSENSITIVE);

    private final String cwd;
    private final Function<String, FileStat> fileStatFn;
    private final Predicate<String> imageExtTest;
    private final BooleanSupplier bridgeModeFeature;
    private final BooleanSupplier envUploadFlag;
    private final Function<UploadRequest, String> uploader;

    public AttachmentResolver(String cwd,
                               Function<String, FileStat> fileStatFn,
                               Predicate<String> imageExtTest,
                               BooleanSupplier bridgeModeFeature,
                               BooleanSupplier envUploadFlag,
                               Function<UploadRequest, String> uploader) {
        this.cwd = Objects.requireNonNull(cwd);
        this.fileStatFn = Objects.requireNonNull(fileStatFn);
        this.imageExtTest = imageExtTest != null ? imageExtTest : IMAGE_EXTENSION_REGEX.asPredicate();
        this.bridgeModeFeature = Objects.requireNonNull(bridgeModeFeature);
        this.envUploadFlag = Objects.requireNonNull(envUploadFlag);
        this.uploader = uploader;
    }

    /** Validation result (CC type). */
    public record ValidationResult(boolean result, String message, Integer errorCode) {
        public static ValidationResult ok() { return new ValidationResult(true, null, null); }
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, 1);
        }
    }

    /** Resolved attachment. */
    public record ResolvedAttachment(String path, long size, boolean isImage, String fileUuid) {}

    /** File stat (CC fs.Stats 最小子集). */
    public record FileStat(long size, boolean isFile) {}

    /** Upload request. */
    public record UploadRequest(String path, long size, boolean replBridgeEnabled) {}

    /**
     * CC validateAttachmentPaths — 主链.
     *
     * <p>只查文件系统（CC BriefTool/attachments.ts:26-61），不查 deny 规则。
     */
    public ValidationResult validateAttachmentPaths(List<String> rawPaths) {
        for (String rawPath : rawPaths) {
            String fullPath = rawPath;  // expandPath (TS homedir) 由调用方处理
            try {
                FileStat stats = fileStatFn.apply(fullPath);
                if (!stats.isFile()) {
                    return ValidationResult.error(
                        "Attachment \"" + rawPath + "\" is not a regular file.");
                }
            } catch (FileSystemException e) {
                String code = e.getCode();
                if ("ENOENT".equals(code)) {
                    return ValidationResult.error(
                        "Attachment \"" + rawPath + "\" does not exist. "
                            + "Current working directory: " + cwd + ".");
                }
                if ("EACCES".equals(code) || "EPERM".equals(code)) {
                    return ValidationResult.error(
                        "Attachment \"" + rawPath + "\" is not accessible (permission denied).");
                }
                throw new RuntimeException("validate failed for " + rawPath, e);
            }
        }
        return ValidationResult.ok();
    }

    /**
     * CC resolveAttachments — 主链（G20② 已移除 read-deny 静默跳过，回归 CC 纯 stat 链）。
     *
     * <p>[G20②] 原 [Session L] read-deny 前置检查已删除（CC BriefTool/attachments.ts:70-82
     * resolveAttachments 只 stat + upload，无 isFileReadDenied）。
     */
    public List<ResolvedAttachment> resolveAttachments(List<String> rawPaths,
                                                       boolean replBridgeEnabled,
                                                       boolean uploadEnabled) {
        // Step 1: 串行 stat（CC BriefTool/attachments.ts:70-82）
        List<ResolvedAttachment> stated = new ArrayList<>(rawPaths.size());
        for (String rawPath : rawPaths) {
            String fullPath = rawPath;
            FileStat stats = fileStatFn.apply(fullPath);
            stated.add(new ResolvedAttachment(
                fullPath,
                stats.size(),
                imageExtTest.test(fullPath),
                null
            ));
        }

        // Step 2: BRIDGE_MODE + replBridgeEnabled || CLAUDE_CODE_BRIEF_UPLOAD → upload
        if (!bridgeModeFeature.getAsBoolean()) {
            return stated;
        }
        boolean shouldUpload = replBridgeEnabled || envUploadFlag.getAsBoolean();
        if (!shouldUpload || uploader == null) {
            return stated;
        }

        // Step 3: parallel upload (network, slow)
        List<String> uuids = new ArrayList<>(stated.size());
        for (ResolvedAttachment a : stated) {
            String uuid = safeUpload(a, shouldUpload);
            uuids.add(uuid);
        }

        // Step 4: merge file_uuid (uuid undefined → 不覆盖)
        List<ResolvedAttachment> result = new ArrayList<>(stated.size());
        for (int i = 0; i < stated.size(); i++) {
            ResolvedAttachment a = stated.get(i);
            String uuid = uuids.get(i);
            result.add(uuid == null ? a : new ResolvedAttachment(
                a.path(), a.size(), a.isImage(), uuid));
        }
        return result;
    }

    private String safeUpload(ResolvedAttachment a, boolean replBridgeEnabled) {
        try {
            return uploader.apply(new UploadRequest(a.path(), a.size(), replBridgeEnabled));
        } catch (Exception e) {
            log.debug("[AttachmentResolver] upload failed for {}: {}", a.path(), e.getMessage());
            return null;
        }
    }

    /** File system exception (CC errno code wrapper). */
    public static class FileSystemException extends RuntimeException {
        private final String code;
        public FileSystemException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}
