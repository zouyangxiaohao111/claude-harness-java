package com.nexusai.kb.app.attachment;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbParseException;
import com.nexusai.kb.app.parser.KbParsedDocument;
import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.repo.entity.KbChatAttachmentRecord;
import com.nexusai.kb.repo.mapper.KbChatAttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库对话「会话临时附件」· 对齐源站 {@code TemporaryDocument}。
 *
 * <h2>与主会话附件的边界（⛔ 不能混）</h2>
 * <ul>
 *   <li>主会话：{@code POST /api/v1/attachments/upload} → 各 store 只存字节，
 *       模型靠 {@code <attachments>} 清单 + Read 工具按路径读。本类<b>一行不碰</b>那条链路。</li>
 *   <li>知识库对话：本类负责「上传即解析成正文」——因为 KB 各人格的工具池里没有 Read
 *       （且 Read 对 docx/xlsx 是二进制黑名单，见 {@code ReadFileTool.BINARY_EXTENSIONS}）。</li>
 * </ul>
 *
 * <h2>解析与内联</h2>
 * 上传时同步解析（复用 {@link KbParserRegistry} 同一套解析器），正文落 {@code content}；
 * 发消息时由网关按 token 预算内联（源站 12000 / agent 6000）。
 */
@Service
public class KbChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(KbChatAttachmentService.class);

    /** 单条正文内联上限（字符数近似 token ×2，与源站 12000 token 同量级）。 */
    static final int MAX_INLINE_CHARS_PER_DOC = 24_000;

    /** 单轮多条附件的总内联上限（源站 agent 轮 6000 token ⇒ 12000 字符）。 */
    static final int MAX_INLINE_CHARS_TOTAL = 12_000;

    public static final String STATUS_UPLOADED = "uploaded";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    private final KbChatAttachmentMapper mapper;
    private final KbParserRegistry parserRegistry;

    public KbChatAttachmentService(KbChatAttachmentMapper mapper, KbParserRegistry parserRegistry) {
        this.mapper = mapper;
        this.parserRegistry = parserRegistry;
    }

    /**
     * 上传并解析一个文件。
     *
     * @param sessionId 会话 id
     * @param file      multipart 文件
     * @return 落库后的记录（成功为 ready，解析失败为 failed 且 errorMessage 有原因）
     */
    public KbChatAttachmentRecord upload(String sessionId, MultipartFile file) {
        String id = "kba-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extensionOf(original);
        Path dir = NexusaiDataRoots.chatRoot().resolve("attachments").resolve(sessionId);
        Path target = dir.resolve(id + (ext.isEmpty() ? "" : "." + ext));

        KbChatAttachmentRecord row = new KbChatAttachmentRecord();
        row.setId(id);
        row.setSessionId(sessionId);
        row.setFileName(original);
        row.setFileType(ext);
        row.setFileSize((int) Math.min(Integer.MAX_VALUE, file.getSize()));
        row.setCreateTime(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        row.setDeleted(0);
        row.setTokenCount(0);
        row.setStatus(STATUS_PROCESSING);

        try {
            Files.createDirectories(dir);
            file.transferTo(target.toFile());
        } catch (IOException e) {
            row.setStatus(STATUS_FAILED);
            row.setErrorMessage("保存文件失败: " + e.getMessage());
            mapper.insert(row);
            log.warn("[KB附件] 落盘失败 session={} file={} 原因={}", sessionId, original, e.toString());
            return row;
        }

        try {
            KbParsedDocument parsed = parserRegistry.parse(target, KbParseContext.defaults());
            String text = parsed == null ? "" : parsed.text();
            if (text == null || text.isBlank()) {
                row.setStatus(STATUS_FAILED);
                row.setErrorMessage("解析结果为空（可能是扫描件或不受支持的格式）");
            } else {
                row.setContent(text);
                // token 粗估：中文约 1 字 ≈ 0.7 token，按 2 字符/token 保守折算
                row.setTokenCount(Math.max(1, text.length() / 2));
                row.setStatus(STATUS_READY);
                // ⭐ 解析后的正文**另存一份 .md**：Agent 轮不内联，模型用 Read 按需读
                //   （源站 agent 轮同构：清单 + read_file 的 attachment:// 路径）。
                //   写失败不置 failed —— 正文还在 DB（RAG 轮内联仍可用）。
                try {
                    Files.writeString(parsedTextPath(id, ext, sessionId), text);
                } catch (IOException io) {
                    log.warn("[KB附件] 解析文本落盘失败（不影响 RAG 内联）id={} 原因={}", id, io.toString());
                }
            }
        } catch (KbParseException e) {
            row.setStatus(STATUS_FAILED);
            row.setErrorMessage("解析失败: " + e.getMessage());
            log.warn("[KB附件] 解析失败 session={} file={} 原因={}", sessionId, original, e.getMessage());
        } catch (RuntimeException e) {
            row.setStatus(STATUS_FAILED);
            row.setErrorMessage("解析异常: " + e.getMessage());
            log.warn("[KB附件] 解析异常 session={} file={} 原因={}", sessionId, original, e.toString());
        }

        mapper.insert(row);
        log.info("[KB附件] 已上传 session={} id={} file={} status={} 字符={}",
            sessionId, id, original, row.getStatus(),
            row.getContent() == null ? 0 : row.getContent().length());
        return row;
    }

    /**
     * 取本轮要内联的附件正文。
     *
     * @param sessionId 会话 id（越权保护：只取本会话的附件）
     * @param ids       附件 id 列表（null/空 ⇒ 空）
     * @return 按入参顺序的「文件名 + 正文」片段列表（每条已截断，总和受 {@link #MAX_INLINE_CHARS_TOTAL} 约束）
     */
    public List<InlineDoc> resolveForPrompt(String sessionId, List<String> ids) {
        if (ids == null || ids.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<InlineDoc> out = new ArrayList<>();
        int budget = MAX_INLINE_CHARS_TOTAL;
        for (String id : ids) {
            if (budget <= 0) {
                break;
            }
            KbChatAttachmentRecord row = findOwned(sessionId, id);
            if (row == null || !STATUS_READY.equals(row.getStatus()) || row.getContent() == null) {
                continue;
            }
            String text = row.getContent();
            int cap = Math.min(MAX_INLINE_CHARS_PER_DOC, budget);
            if (text.length() > cap) {
                text = text.substring(0, cap) + "\n…（内容过长已截断）";
            }
            budget -= text.length();
            out.add(new InlineDoc(row.getId(), row.getFileName(), row.getFileType(), text));
        }
        return out;
    }

    /** 查本会话名下、未删除的一条附件。 */
    public KbChatAttachmentRecord findOwned(String sessionId, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return mapper.selectOneByQuery(QueryWrapper.create()
            .where("id = ?", id)
            .and("session_id = ?", sessionId)
            .and("deleted = 0"));
    }

    /**
     * 附件在磁盘上的位置（预览原文件 / 下载用）。
     *
     * <p>与 {@link #upload} 的落盘规则必须同源 —— ⛔ 不要各写一份路径拼接。
     */
    public Path fileOf(KbChatAttachmentRecord row) {
        Path dir = NexusaiDataRoots.chatRoot().resolve("attachments").resolve(row.getSessionId());
        String ext = row.getFileType() == null ? "" : row.getFileType();
        return dir.resolve(row.getId() + (ext.isEmpty() ? "" : "." + ext));
    }

    /** 解析后正文（`.md`）的磁盘位置。 */
    public Path parsedTextPath(String id, String ext, String sessionId) {
        return NexusaiDataRoots.chatRoot()
            .resolve("attachments").resolve(sessionId)
            .resolve(id + ".md");
    }

    /**
     * 解析后正文的**会话 cwd 相对路径** —— 模型用 Read 读它。
     *
     * <p>相对基准 = 会话 cwd = {@code chatRoot()}（KB 项目的 path），
     * 所以 `Read("attachments/{sid}/{id}.md")` 能命中，不需要放宽任何沙箱。
     */
    public String relativeParsedPath(KbChatAttachmentRecord row) {
        return "attachments/" + row.getSessionId() + "/" + row.getId() + ".md";
    }

    /**
     * Agent 轮的附件**清单**（不内联正文）· 对齐源站 {@code describeTemporaryAttachmentsForAgent}。
     *
     * <p>模型拿到文件名/类型/路径，需要时用 `Read` 读解析后的 `.md`。
     *
     * @return 只含 ready 且解析文件存在的项；否则模型读了会空
     */
    public List<ManifestDoc> manifestForPrompt(String sessionId, List<String> ids) {
        if (ids == null || ids.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ManifestDoc> out = new ArrayList<>();
        for (String id : ids) {
            KbChatAttachmentRecord row = findOwned(sessionId, id);
            if (row == null || !STATUS_READY.equals(row.getStatus())) {
                continue;
            }
            Path textFile = parsedTextPath(row.getId(), row.getFileType(), row.getSessionId());
            if (!Files.isRegularFile(textFile)) {
                continue;
            }
            out.add(new ManifestDoc(row.getFileName(), row.getFileType(),
                relativeParsedPath(row), row.getTokenCount() == null ? 0 : row.getTokenCount()));
        }
        return out;
    }

    /** 清单条目（Agent 轮：模型按 path 用 Read 读）。 */
    public record ManifestDoc(String fileName, String fileType, String path, int tokenCount) {
    }

    /** 一条可内联的附件。 */
    public record InlineDoc(String id, String fileName, String fileType, String text) {
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
