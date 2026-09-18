package com.nexusai.apis.attachment;

import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.application.agent.attachment.PdfAttachmentStore;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [ATT-DOC · 用户裁定 2026-09-18] upload（multipart）通道白名单放开到<b>文档类</b>
 * （doc / docx / xls / xlsx）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：本通道原先只收 pdf / image* / video* / audio*
 * 与一串媒体扩展名 ⇒ {@code >5MB 的 Word} 会命中 {@code isAllowedType} 的
 * {@code "不支持的文件类型"} 400（{@code AttachmentController:172}），用户拖入大 Word 必然失败。
 * 本类锁定「文档类扩展名可上传，且必须真的过 {@code verifyMagic} 魔数门」这一新意图。
 *
 * <p><b>为什么必须与魔数门同批测（否则会得到「白名单删了却仍然传不上」）</b>：
 * docx/xlsx 是 <b>OOXML = ZIP 容器</b>（{@code 50 4B 03 04}），doc/xls 是 <b>旧版 OLE2 复合文档</b>
 * （{@code D0 CF 11 E0 A1 B1 1A E1}）。{@code verifyMagic} 原表只认媒体/PDF 魔数，
 * 且 {@code File.type} 在 {@code application/octet-stream} 时走「全组尝试」腿 ⇒ 真 docx 会被
 * 魔数门 400。故「白名单」与「魔数」必须同时放开，只加扩展名是**假绿**。
 *
 * <p><b>负例与正例同批（规则九）</b>：只测「放行」会让 {@code verifyMagic} 被顺带删空、
 * 或让「ZIP 魔数」变成任意 ZIP 的通行证而测试仍全绿 —— 故本类同时锁死：
 * 内容与扩展名不符（HTML/PNG 冒充 .docx、ZIP 冒充 .doc）必须 400；
 * 未放开的类型（.txt / .zip）必须仍 400；{@code >100MB} 上限不得放宽。
 *
 * <p><b>与 {@link AttachmentControllerUploadTest} 的关系</b>：那个类只注入了 {@code pdfAttachmentStore}
 * （且其两条 200 用例因 {@code attachmentService} 未注入而 NPE → 500，属<b>既存红</b>）。
 * 本类独立接线 media/image store + mock {@code AttachmentService}，<b>不改动那个类的任何一行</b>
 * ——避免把既存红「顺手修绿」而掩盖。
 */
@DisplayName("[ATT-DOC] upload 通道放开文档类（doc/docx/xls/xlsx）· 魔数门同步放开且不误放")
class AttachmentControllerUploadDocTest {

    private static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String SESSION = "sess-doc";
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private final PdfAttachmentStore pdfStore = new PdfAttachmentStore();
    private final MediaAttachmentStore mediaStore = new MediaAttachmentStore();
    private final ImageAttachmentStore imageStore = new ImageAttachmentStore();
    private MockMvc mvc;

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        // G5 适配：store 写 nexusai 自有根 → 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        AttachmentController controller = new AttachmentController();
        ReflectionTestUtils.setField(controller, "pdfAttachmentStore", pdfStore);
        ReflectionTestUtils.setField(controller, "mediaAttachmentStore", mediaStore);
        ReflectionTestUtils.setField(controller, "imageAttachmentStore", imageStore);
        // 附件表 mock：register 返回递增 contentId（真 AttachmentService 需 DB，单测不拉库）
        AttachmentService attachmentService = mock(AttachmentService.class);
        AtomicLong ids = new AtomicLong(1);
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenAnswer(inv -> ids.getAndIncrement());
        ReflectionTestUtils.setField(controller, "attachmentService", attachmentService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 夹具：各容器/媒体的最小魔数头
    // ════════════════════════════════════════════════════════════════════

    /** OOXML（docx/xlsx）= ZIP 容器：本地文件头 {@code PK\x03\x04} + 首个条目名（真实 docx 首条目恒为 [Content_Types].xml）。 */
    private static byte[] ooxmlBytes() {
        byte[] out = new byte[64];
        out[0] = 0x50; out[1] = 0x4B; out[2] = 0x03; out[3] = 0x04;   // PK\x03\x04
        out[4] = 0x14; out[5] = 0x00; out[6] = 0x06; out[7] = 0x00;   // version needed / flags（真实 ZIP 常见值）
        byte[] name = "[Content_Types].xml".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, out, 8, name.length);
        return out;
    }

    /** 旧版 Office（doc/xls）= OLE2 复合文档魔数。 */
    private static byte[] ole2Bytes() {
        byte[] out = new byte[64];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, out, 0, magic.length);
        return out;
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private static byte[] mp4Bytes() {
        return new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }

    private static byte[] mp3Bytes() {
        return new byte[]{'I', 'D', '3', 0x03, 0, 0, 0, 0};
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] htmlBytes() {
        return "<html><body>not a document</body></html>".getBytes(StandardCharsets.US_ASCII);
    }

    /** 落盘路径断言用：media-cache 的文件名扩展名由 mediaType 推（{@code mediaType.split('/')[1]}）。 */
    private Path mediaCacheFile(String sessionId, long id, String ext) {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "media-cache", sessionId, id + "." + ext);
    }

    // ════════════════════════════════════════════════════════════════════
    // 正例：文档类必须真的能传上（改前红）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① .docx + 真实 OOXML MIME → 200 + contentId + 落盘字节一致（改前 400「不支持的文件类型」）")
    void docxUpload_realMime_ok() throws Exception {
        byte[] bytes = ooxmlBytes();
        MockMultipartFile file = new MockMultipartFile("file", "季度报告.docx", DOCX_MIME, bytes);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"))
            .andExpect(jsonPath("$.filename").value("季度报告.docx"))
            .andExpect(jsonPath("$.mediaType").value(DOCX_MIME))
            .andExpect(jsonPath("$.size").value(bytes.length));

        // 落盘字节与原文件一致（下游附件表 path 读盘 = 路径通道唯一真源）
        Path stored = mediaCacheFile(SESSION, 1, "vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(Files.exists(stored)).as("docx 必须落盘到 media-cache（%s）", stored).isTrue();
        assertThat(Files.readAllBytes(stored)).isEqualTo(bytes);
        // 内存索引可解析（下游按 contentId 反查 filename）
        assertThat(mediaStore.get(SESSION, 1).filename()).isEqualTo("季度报告.docx");
    }

    @Test
    @DisplayName("② .docx + application/octet-stream（前端 File.type 为空时的形态）→ 200（改前 400）")
    void docxUpload_octetStream_ok() throws Exception {
        byte[] bytes = ooxmlBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "季度报告.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"));

        assertThat(Files.exists(mediaCacheFile(SESSION, 1, "octet-stream")))
            .as("octet-stream 腿的落盘扩展名由 mediaType 推（仅影响落盘文件名，不影响可读性）")
            .isTrue();
    }

    @Test
    @DisplayName("③ .xlsx + 真实 spreadsheetml MIME → 200（改前 400）")
    void xlsxUpload_realMime_ok() throws Exception {
        byte[] bytes = ooxmlBytes();
        MockMultipartFile file = new MockMultipartFile("file", "数据表.xlsx", XLSX_MIME, bytes);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"))
            .andExpect(jsonPath("$.filename").value("数据表.xlsx"));
    }

    @Test
    @DisplayName("④ .doc（旧版 OLE2）+ application/msword → 200（改前 400）")
    void docUpload_ole2_ok() throws Exception {
        byte[] bytes = ole2Bytes();
        MockMultipartFile file = new MockMultipartFile("file", "合同.doc", "application/msword", bytes);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"))
            .andExpect(jsonPath("$.mediaType").value("application/msword"));

        assertThat(Files.exists(mediaCacheFile(SESSION, 1, "msword"))).isTrue();
    }

    @Test
    @DisplayName("⑤ .xls（旧版 OLE2）+ application/vnd.ms-excel → 200（改前 400）")
    void xlsUpload_ole2_ok() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "台账.xls", "application/vnd.ms-excel", ole2Bytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 负例：魔数门必须拦住「改名冒充」
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑥ HTML 冒充 .docx → 400（魔数门；且不落盘）")
    void docxWithHtmlContent_badRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "假文档.docx", DOCX_MIME, htmlBytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());

        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "media-cache", SESSION)))
            .as("魔数校验失败绝不落盘")
            .isFalse();
    }

    @Test
    @DisplayName("⑦ PNG 冒充 .docx → 400（ZIP 容器判定是真门，不是「有扩展名就放」）")
    void docxWithPngContent_badRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "伪装.docx", DOCX_MIME, pngBytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("⑧ ZIP 冒充 .doc（旧版 OLE2）→ 400（扩展名与容器不匹配时 fail closed）")
    void legacyDocWithZipContent_badRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "冒充.doc", "application/msword", ooxmlBytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("⑨ .zip 不在白名单 → 400（ZIP 魔数只对文档类扩展名生效，未把任意 ZIP 放进来）")
    void zipExtensionStillRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "打包产物.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE, ooxmlBytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("⑩ .txt 不在白名单 → 400（本次只放开文档类，未顺带放开文本类）")
    void txtExtensionStillRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("⑪ >100MB 的 .docx → 400（MEDIA_MAX_SIZE 未放宽；文件头是合法 ZIP，故 400 只能来自大小门）")
    void oversizedDocx_badRequest() throws Exception {
        byte[] huge = new byte[(int) MAX_BYTES + 1];
        byte[] head = ooxmlBytes();
        System.arraycopy(head, 0, huge, 0, head.length);
        MockMultipartFile file = new MockMultipartFile("file", "超大报告.docx", DOCX_MIME, huge);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());

        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "media-cache", SESSION)))
            .as("超限文件绝不落盘")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 回归：既有类型照常
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑭ .docx（ZIP）却声明 application/pdf → 400（文档类分支不得绕开 PDF 魔数门）")
    void docxDeclaringPdf_badRequest() throws Exception {
        // 声明 application/pdf 时分流走 isPdfFile → pdf-cache；若文档类分支先于 PDF 判定生效，
        // ZIP 内容会被放行并落进 pdf-cache —— 正是「非 PDF 入库污染会话」那条既有红线。
        MockMultipartFile file = new MockMultipartFile(
            "file", "报告.docx", MediaType.APPLICATION_PDF_VALUE, ooxmlBytes());

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", SESSION))
            .andExpect(status().isBadRequest());

        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", SESSION)))
            .as("非 PDF 内容绝不落进 pdf-cache")
            .isFalse();
    }

    @Test
    @DisplayName("⑫ 回归 · image/png 与 application/pdf 照常 200（分流未受影响）")
    void imageAndPdfStillUpload() throws Exception {
        mvc.perform(multipart("/api/v1/attachments/upload")
                .file(new MockMultipartFile("file", "图.png", MediaType.IMAGE_PNG_VALUE, pngBytes()))
                .param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"));

        mvc.perform(multipart("/api/v1/attachments/upload")
                .file(new MockMultipartFile("file", "文.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes()))
                .param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("2"));
    }

    @Test
    @DisplayName("⑬ 回归 · video/mp4 与 audio/mpeg 照常 200（media-cache 腿未受影响）")
    void videoAndAudioStillUpload() throws Exception {
        mvc.perform(multipart("/api/v1/attachments/upload")
                .file(new MockMultipartFile("file", "片.mp4", "video/mp4", mp4Bytes()))
                .param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("1"));

        mvc.perform(multipart("/api/v1/attachments/upload")
                .file(new MockMultipartFile("file", "音.mp3", "audio/mpeg", mp3Bytes()))
                .param("sessionId", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value("2"));
    }
}
