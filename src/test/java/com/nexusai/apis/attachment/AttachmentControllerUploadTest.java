package com.nexusai.apis.attachment;

import com.nexusai.application.agent.attachment.PdfAttachmentStore;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AttachmentController POST /api/v1/attachments/upload 端点测试（U1 混合传输 C &gt;5MB 大 PDF 上传落盘）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>&gt;5MB 有效 PDF → 200 + contentId 可引用</b>——前端拿 contentId 放
 *       {@code SendMessageRequest.attachments} 的 {@code {type:'pdf', contentId}} 引用已落盘 PDF；
 *       若 200 但 contentId 缺失/不可解析 → 前端无法回引，路径通道断裂。</li>
 *   <li><b>非 PDF / 缺 %PDF- magic → 400</b>——HTML 改名 .pdf 进库会让后续每次 API 调用
 *       400（CC pdf.ts:77-86 corrupted 语义），必须在入口拦截。</li>
 *   <li><b>空文件 → 400</b>——CC pdf.ts:50-55 empty 语义。</li>
 *   <li><b>落盘文件字节与上传一致</b>——路径通道下游 PdfSupport.readPDF 读到的必须是原文件。</li>
 * </ol>
 */
@DisplayName("[U1] AttachmentController POST /api/v1/attachments/upload")
class AttachmentControllerUploadTest {

    private final PdfAttachmentStore store = new PdfAttachmentStore();
    private MockMvc mvc;

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        // G5 适配：PdfAttachmentStore 写 nexusai 自有根 → 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        AttachmentController controller = new AttachmentController();
        // 生产由 @Autowired 注入；单测 ReflectionTestUtils 接线（AwaySummaryControllerTest 同模式）
        ReflectionTestUtils.setField(controller, "pdfAttachmentStore", store);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    /** 最小有效 PDF（%PDF- magic 通过）。 */
    private static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }

    /** >5MB 有效 PDF：base64 直传阈值（API_IMAGE_MAX_BASE64_SIZE=5MB）之上，走 multipart 路径通道。 */
    private static byte[] largePdfBytes() {
        byte[] base = pdfBytes();
        int target = 5 * 1024 * 1024 + 1;
        byte[] large = new byte[target];
        System.arraycopy(base, 0, large, 0, base.length);
        for (int i = base.length; i < target; i++) {
            large[i] = ' ';
        }
        return large;
    }

    @Test
    @DisplayName(">5MB 有效 PDF → 200 + contentId/filename/size + 落盘字节一致 · U1 路径通道")
    void upload_validPdf_returnsContentId() throws Exception {
        byte[] pdf = largePdfBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "big-report.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);

        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").value("1"))
                .andExpect(jsonPath("$.filename").value("big-report.pdf"))
                .andExpect(jsonPath("$.mediaType").value("application/pdf"))
                .andExpect(jsonPath("$.size").value(pdf.length));

        // 落盘字节与原文件一致（路径通道下游 PdfSupport.readPDF 读原文件）
        Path stored = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1", "1.pdf");
        assertThat(Files.exists(stored)).isTrue();
        assertThat(Files.readAllBytes(stored)).isEqualTo(pdf);
        // 内存索引可解析 contentId（SendMessageRequest.attachments type=pdf contentId 引用侧）
        assertThat(store.get("sess-1", 1).filename()).isEqualTo("big-report.pdf");
    }

    @Test
    @DisplayName("空文件 → 400 · CC pdf.ts:50-55 empty")
    void upload_emptyFile_badRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf",
                MediaType.APPLICATION_PDF_VALUE, new byte[0]);
        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", "sess-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非 PDF（mediaType + 扩展名均非 pdf）→ 400")
    void upload_nonPdf_badRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, pdfBytes());
        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", "sess-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("缺 %PDF- magic（HTML 改名 .pdf）→ 400 · CC pdf.ts:77-86 corrupted")
    void upload_missingPdfMagic_badRequest() throws Exception {
        // 前 5 字节非 %PDF-（HTML 头），即使扩展名/类型为 pdf 也拒绝（防假 PDF 入库污染会话）
        byte[] html = "<html><body>not a pdf</body></html>".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", MediaType.APPLICATION_PDF_VALUE, html);
        mvc.perform(multipart("/api/v1/attachments/upload").file(file).param("sessionId", "sess-1"))
                .andExpect(status().isBadRequest());
        // 校验失败不落盘
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1"))).isFalse();
    }

    @Test
    @DisplayName("同一会话连续上传 → contentId 递增（1→2），供 attachments 保序引用")
    void upload_sequentialIds() throws Exception {
        MockMultipartFile f1 = new MockMultipartFile("file", "a.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes());
        MockMultipartFile f2 = new MockMultipartFile("file", "b.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes());
        mvc.perform(multipart("/api/v1/attachments/upload").file(f1).param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").value("1"));
        mvc.perform(multipart("/api/v1/attachments/upload").file(f2).param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").value("2"))
                .andExpect(jsonPath("$.size").value(greaterThan(0)));
    }
}
