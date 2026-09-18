package com.nexusai.application.chat;

import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [附件 path 通道 · 全扩展名放行] 「所有文件都允许走 path 通道」（用户 2026-09-18 裁定）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：path 通道的语义是「前后端同机（Tauri 桌面
 * local-read）时，前端把本地绝对路径随消息直传，后端零拷贝登记附件表并把真实磁盘路径告知模型」。
 * 此前 {@code ChatService.PATH_ATTACHMENT_ALLOWED_EXTENSIONS} 是一道 <b>15 项扩展名白名单</b>
 * （pdf/doc/docx/xls/xlsx/mp4/mov/mp3/wav/webm/jpg/jpeg/png/gif/webp）⇒ {@code zip} / {@code txt} /
 * {@code csv} / {@code md} / {@code ogg} / {@code m4a} / {@code bmp} 等一律被 warn 跳过并返回 null，
 * 到不了模型。本类锁定「任意扩展名均可登记」这一新意图，并<b>同时</b>锁定放开后仍必须生效的三道门
 * （存在性 / 200MB 上限 / 防穿越）——这四者共同构成 path 通道的完整契约；只测「放行」会让三道门被
 * 顺带删掉时测试仍然全绿，故两类负例与正例同批写入。
 *
 * <p><b>断言口径</b>：{@code resolveAttachments} / {@code resolveAttachmentPath} 均为 ChatService
 * private → 反射注入依赖（{@code attachmentService} mock + {@code localRead=true}）+ 反射调用
 * （同 {@link ChatServiceBase64PdfRegisterTest} 的 setField 范式）。
 */
@DisplayName("[附件 path 通道] 任意扩展名均可登记（放开白名单）· 三道门仍生效")
class ChatServicePathAttachmentAnyExtensionTest {

    private static final long MAX_BYTES = 200L * 1024 * 1024;

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

    /** 构造 ChatService：attachmentService=register 返回 7 的 mock，localRead=passthrough。 */
    private ChatService serviceWith(AttachmentService attachmentService, boolean localRead) throws Exception {
        ChatService service = new ChatService();
        setField(service, "attachmentService", attachmentService);
        setField(service, "localRead", localRead);
        return service;
    }

    private static AttachmentService mockAttachmentServiceReturning(long contentId) {
        AttachmentService svc = mock(AttachmentService.class);
        // register(String sessionId, String path, String mediaType, String filename, long size, String sourceType)
        when(svc.register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(contentId);
        return svc;
    }

    /** .txt 的 path 附件：改前被白名单拒（返回 null → 列表空）→ 改后登记 contentId。 */
    @Test
    @DisplayName("① .txt 走 path 通道 → 登记附件表并产出 contentId（改前红）")
    void txtPathAttachment_registersContentId(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("阅读笔记.txt"), "任意文本内容");
        AttachmentService svc = mockAttachmentServiceReturning(7L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "阅读笔记.txt", "application/octet-stream",
                null, file.toAbsolutePath().toString())));

        assertThat(resolved).as(".txt 必须能走 path 通道登记（用户裁定：所有文件都允许）").hasSize(1);
        AttachmentRequest out = resolved.get(0);
        assertThat(out.contentId()).as("contentId=附件表自增 id").isEqualTo("7");
        assertThat(out.type()).as("前端对非图片非 PDF 一律标 type=file（Composer 契约）").isEqualTo("file");
        assertThat(out.base64()).as("path 通道 base64 恒 null（零拷贝，不复制进 store）").isNull();
        assertThat(out.path()).as("path=登记的绝对路径（下游附件表读盘唯一真源）")
            .isEqualTo(file.toAbsolutePath().toString());
        verify(svc).register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    /** .zip 的 path 附件：改前被白名单拒 → 改后登记。 */
    @Test
    @DisplayName("② .zip 走 path 通道 → 登记附件表并产出 contentId（改前红）")
    void zipPathAttachment_registersContentId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("打包产物.zip");
        Files.write(file, new byte[]{0x50, 0x4B, 0x03, 0x04});
        AttachmentService svc = mockAttachmentServiceReturning(11L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "打包产物.zip", "application/octet-stream",
                null, file.toAbsolutePath().toString())));

        assertThat(resolved).as(".zip 必须能走 path 通道登记").hasSize(1);
        assertThat(resolved.get(0).contentId()).isEqualTo("11");
        assertThat(resolved.get(0).mediaType()).as("扩展名不在媒体映射表 → octet-stream 兜底")
            .isEqualTo("application/octet-stream");
    }

    /** 无扩展名文件：也应放行（extensionOf → ""；此前同样落白名单外）。 */
    @Test
    @DisplayName("③ 无扩展名文件走 path 通道 → 登记附件表（改前红）")
    void noExtensionPathAttachment_registersContentId(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("LICENSE"), "MIT");
        AttachmentService svc = mockAttachmentServiceReturning(3L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "LICENSE", null, null, file.toAbsolutePath().toString())));

        assertThat(resolved).as("无扩展名文件同样放行（白名单已移除）").hasSize(1);
        assertThat(resolved.get(0).contentId()).isEqualTo("3");
    }

    // ─────────────────────────── 三道门负例（改前改后都必须绿） ───────────────────────────

    /** 门 1 · Files.exists：不存在的路径 → 拒（放开白名单不得连带放开存在性校验）。 */
    @Test
    @DisplayName("门1 Files.exists：不存在的路径 → 拒（正例扩展名放行不影响此门）")
    void gate1_nonexistentPath_rejected(@TempDir Path dir) throws Exception {
        Path ghost = dir.resolve("不存在的文件.txt");
        AttachmentService svc = mockAttachmentServiceReturning(7L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "不存在的文件.txt", null, null,
                ghost.toAbsolutePath().toString())));

        assertThat(resolved).as("不存在 → fail loud 跳过").isEmpty();
        verify(svc, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    /** 门 1b · 目录 → 拒（Files.isDirectory）。 */
    @Test
    @DisplayName("门1b isDirectory：目录路径 → 拒")
    void gate1b_directoryPath_rejected(@TempDir Path dir) throws Exception {
        Path sub = Files.createDirectory(dir.resolve("一个目录.txt"));
        AttachmentService svc = mockAttachmentServiceReturning(7L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "一个目录.txt", null, null,
                sub.toAbsolutePath().toString())));

        assertThat(resolved).as("目录 → 拒（非普通文件）").isEmpty();
        verify(svc, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    /** 门 2 · size ≤ 200MB：201MB 稀疏文件 → 拒。 */
    @Test
    @DisplayName("门2 200MB 上限：201MB 文件 → 拒")
    void gate2_oversizePath_rejected(@TempDir Path dir) throws Exception {
        Path big = dir.resolve("超大.txt");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(MAX_BYTES + 1); // NTFS 稀疏：瞬间完成，不实占 201MB 数据
        }
        assertThat(Files.size(big)).as("夹具前置：文件确为 200MB+1B").isEqualTo(MAX_BYTES + 1);

        AttachmentService svc = mockAttachmentServiceReturning(7L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "超大.txt", null, null, big.toAbsolutePath().toString())));

        assertThat(resolved).as("> 200MB → 拒（防读盘拖垮）").isEmpty();
        verify(svc, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    /**
     * 门 3 · 防穿越：含 {@code ..} 的路径。
     *
     * <p><b>实测口径</b>：{@code normalizeAndValidatePath} 先做
     * {@code Path.of(raw).toAbsolutePath().normalize()}，Windows 下 normalize <b>lexically 消解全部
     * {@code ..}</b>（无任何残留形式——{@code D:/a/../../x} / {@code \\?\D:\a\..\b} / {@code C:..\x}
     * 实测 {@code hasDotDot=false}）⇒ 「逐段禁 {@code ..}」循环在 Windows 上结构性不可达，真正拦住
     * 穿越的是 <b>normalize 本身</b>。故本用例断言的是那条活腿：{@code ..} 被消解、且路径<b>不依赖中间
     * 目录存在</b>（{@code 不存在的中间层/../目标.txt} 仍解析到目标.txt）。若有人删掉 {@code .normalize()}
     * （= 拆掉防穿越），本用例转红（见反向实验读数）。
     */
    @Test
    @DisplayName("门3 防穿越：.. 段被 normalize 消解（不依赖中间目录存在，不逃逸）")
    void gate3_dotDotNeutralizedByNormalize(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("目标.txt"), "内容");
        Path traversalRaw = dir.resolve("不存在的中间层").resolve("..").resolve("目标.txt");
        assertThat(traversalRaw.toString()).as("夹具前置：原始串确实含 .. 段").contains("..");

        AttachmentService svc = mockAttachmentServiceReturning(9L);
        ChatService service = serviceWith(svc, true);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "目标.txt", null, null, traversalRaw.toString())));

        assertThat(resolved).as(".. 被 normalize 消解 → 解析到真实目标文件并登记").hasSize(1);
        assertThat(resolved.get(0).path())
            .as("解析结果不含 .. 段（穿越无法逃逸；若 normalize 被删则 .. 保留 → 中间层不存在 → 本例转红）")
            .isEqualTo(target.toAbsolutePath().toString())
            .doesNotContain("..");
    }

    /** 对照门（未被本批触及，仅作回归护栏）：localRead=false 时 path 附件一律不走本地读盘。 */
    @Test
    @DisplayName("对照：localRead=false → path 附件不激活（远程应走 upload）")
    void localReadFalse_pathAttachmentNotActivated(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("阅读笔记.txt"), "x");
        AttachmentService svc = mockAttachmentServiceReturning(7L);
        ChatService service = serviceWith(svc, false);

        List<AttachmentRequest> resolved = resolve(service, "sess-t", List.of(
            new AttachmentRequest("file", null, "阅读笔记.txt", null, null, file.toAbsolutePath().toString())));

        assertThat(resolved).as("local-read=false → path 分支不激活").isEmpty();
        verify(svc, never())
            .register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }
}
