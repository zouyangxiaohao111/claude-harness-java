package com.nexusai.application.chat;

import com.nexusai.application.agent.attachment.PdfAttachmentStore;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [skill-attach-register] resolveAttachments base64 直传 PDF → 落盘 + 注册附件表（统一 contentId）测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：≤5MB base64 直传 PDF（前端契约 ≤5MB 一律 base64 无
 * contentId）此前 resolveAttachments 仅透传 base64 → user_attachments.contentId=null → 出站
 * resolveAttachmentUrls 拼不出 /attachments/content/{sessionId}/{contentId} → F5 重拉前端无法按内容端点
 * 预览（乐观 base64 仅前端内存，重拉丢）。本批使 base64 PDF 在 resolveAttachments 阶段落盘（pdfAttachmentStore）
 * + 注册附件表（attachmentService.register）→ contentId 落 user_attachments → F5 url 可拼。图 ≤5MB 保持
 * base64 直传（image-cache 独立通道，不注册）。
 *
 * <p>resolveAttachments/registerBase64Pdf 均为 ChatService private · 反射注入附件依赖（pdfAttachmentStore/
 * attachmentService）+ 反射调用（同 CommandServiceToggleRefreshTest setField 范式）。
 */
@DisplayName("[skill-attach-register] resolveAttachments base64 PDF → 注册附件表 contentId")
class ChatServiceBase64PdfRegisterTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static List<AttachmentRequest> resolve(ChatService service, String sessionId,
                                                   List<AttachmentRequest> raw) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("resolveAttachments", String.class, List.class);
        m.setAccessible(true);
        return (List<AttachmentRequest>) m.invoke(service, sessionId, raw);
    }

    private ChatService serviceWith(PdfAttachmentStore pdfStore, AttachmentService attachmentService) throws Exception {
        ChatService service = new ChatService();
        setField(service, "pdfAttachmentStore", pdfStore);
        setField(service, "attachmentService", attachmentService);
        return service;
    }

    /** 假 PDF 内容 base64（非空、可 decode）。 */
    private static String base64Of(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    @DisplayName("base64 直传 PDF → 落盘 store + 注册附件表 → 输出 contentId 路径通道（base64=null）")
    void base64Pdf_registersAttachmentTable_andOutputsContentIdPathChannel() throws Exception {
        PdfAttachmentStore pdfStore = mock(PdfAttachmentStore.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(pdfStore.store(any(), any(), anyLong(), anyString()))
            .thenReturn(new PdfAttachmentStore.StoredPdf(1L, "D:/cache/1.pdf", "桥梁结构1.pdf", 4_900_000L));
        // register(String sessionId, String path, String mediaType, String filename, long size, String sourceType)
        when(attachmentService.register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(7L);
        ChatService service = serviceWith(pdfStore, attachmentService);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("pdf", null, "桥梁结构1.pdf", "application/pdf",
                base64Of(new byte[]{0x25, 0x50, 0x44, 0x46}), null)));

        // 输出走 contentId 路径通道：base64 已清空、contentId=附件表注册 id、path=store 落盘路径
        assertThat(resolved).hasSize(1);
        AttachmentRequest out = resolved.get(0);
        assertThat(out.contentId()).as("base64 PDF 注册附件表后 contentId=附件表自增 id").isEqualTo("7");
        assertThat(out.base64()).as("已转路径通道，base64 清空").isNull();
        assertThat(out.path()).as("path=store 落盘路径（下游附件表读盘）").isEqualTo("D:/cache/1.pdf");
        assertThat(out.type()).isEqualTo("pdf");
        // 落盘 + 注册真实发生
        verify(pdfStore).store(any(), any(), anyLong(), anyString());
        verify(attachmentService).register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("≤5MB base64 图 → 不注册附件表（image-cache 独立通道），原样保留 base64")
    void base64Image_keepsBase64_noRegister() throws Exception {
        PdfAttachmentStore pdfStore = mock(PdfAttachmentStore.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatService service = serviceWith(pdfStore, attachmentService);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("image", null, "剪贴板.png", "image/png",
                base64Of(new byte[]{1, 2, 3}), null)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).contentId()).as("图保持 base64 直传无 contentId（imagePasteIds/image-cache 通道）").isNull();
        assertThat(resolved.get(0).base64()).as("图 base64 原样保留（乐观预览）").isNotNull();
        verify(attachmentService, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("store 落盘失败 → 降级保留 base64 直传（附件不丢，仅 F5 url 缺失）")
    void storeFailure_fallsBackToBase64() throws Exception {
        PdfAttachmentStore pdfStore = mock(PdfAttachmentStore.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(pdfStore.store(any(), any(), anyLong(), anyString())).thenReturn(null); // 落盘失败
        ChatService service = serviceWith(pdfStore, attachmentService);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("pdf", null, "a.pdf", "application/pdf",
                base64Of(new byte[]{0x25, 0x50, 0x44, 0x46}), null)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).contentId()).as("注册失败 → 无 contentId").isNull();
        assertThat(resolved.get(0).base64()).as("降级保留 base64（模型仍可读）").isNotNull();
        verify(attachmentService, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }
}
