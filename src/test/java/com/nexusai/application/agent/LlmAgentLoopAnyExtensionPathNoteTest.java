package com.nexusai.application.agent;

import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [附件 path 通道 · 全扩展名放行] 下游「本地路径=…」说明对任意扩展名安全（Q4 取证）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>：ChatService 放开 path 白名单只是「登记进附件表」；
 * 用户真正要的是「模型拿到本地路径」。这条链的最后一跳是
 * {@code LlmAgentLoop.buildMediaAttachmentNotes} 的 path 分支——它按 {@code type ∈ {video,audio,file}}
 * 分流，<b>不看扩展名</b>：前端对非图片非 PDF 一律标 {@code type='file'}（Composer 契约）⇒ 任意扩展名的
 * path 附件都能产出说明。本类把这一跳钉住：改了白名单却在这一跳被扩展名二次过滤的话，用户仍看不到路径，
 * 而 ChatService 侧测试仍会全绿（= 只看单测会漏的洞）。
 *
 * <p>本类在改动前后<b>都应绿</b>（它守的是下游不回归，不是本次放行本身）。
 */
@DisplayName("[附件 path 通道] 任意扩展名的 path 附件 → 产出「本地路径=…」说明")
class LlmAgentLoopAnyExtensionPathNoteTest {

    private static String notesFor(String type, String filename, String mediaType, String path) {
        return LlmAgentLoop.buildMediaAttachmentNotes(
            null, null, "sess-t",
            List.of(new AttachmentRequest(type, "7", filename, mediaType, null, path)));
    }

    @Test
    @DisplayName("type=file + .txt 的 path 附件 → 说明含 filename/contentId/本地路径=（与扩展名无关）")
    void txtPathAttachment_producesLocalPathNote(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("阅读笔记.txt"), "内容");
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("file", "阅读笔记.txt", "application/octet-stream", abs);

        assertThat(notes).as("type=file 属 isMediaAttachmentType → 必须产出说明").isNotEmpty();
        assertThat(notes).contains("阅读笔记.txt");
        assertThat(notes).contains("contentId=7");
        assertThat(notes).contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("type=file + .zip 的 path 附件 → 说明含「本地路径=…」")
    void zipPathAttachment_producesLocalPathNote(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("打包产物.zip");
        Files.write(file, new byte[]{0x50, 0x4B, 0x03, 0x04});
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("file", "打包产物.zip", "application/octet-stream", abs);

        assertThat(notes).as(".zip 不再被扩展名二次过滤").contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("无扩展名 + mediaType=null 的 path 附件 → 仍产出「本地路径=…」")
    void noExtensionNullMediaType_producesLocalPathNote(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("LICENSE"), "MIT");
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("file", "LICENSE", null, abs);

        assertThat(notes).as("mediaType 缺失也不阻断（说明里省略 MIME 段）")
            .contains("LICENSE").contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("对照：非媒体 type（image/pdf）不进媒体说明通道")
    void nonMediaType_notIncluded(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.pdf"), "x");
        String abs = file.toAbsolutePath().toString();

        assertThat(notesFor("pdf", "a.pdf", "application/pdf", abs))
            .as("pdf 走 PdfAttachmentProcessor 通道，不由媒体说明负责").isEmpty();
        assertThat(notesFor("image", "a.png", "image/png", abs))
            .as("image 走大图路径说明（buildLargeImagePathNotes）通道").isEmpty();
    }
}
