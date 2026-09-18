package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.tool.FileReadingLimits;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [附件通道 · 文本类正文内联 · 对齐 CC @提及] path 通道的 <b>文本类</b> 附件在阈值内把正文内联给模型。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 里 {@code @提及} 一个 {@code .txt}
 * 会把<b>正文内联</b>进上下文（{@code Open-ClaudeCode/src/utils/attachments.ts:3043-3063}
 * {@code generateFileAttachment(mode='at-mention')} → 阈值内 {@code FileReadTool.call} 读出正文 →
 * {@code type:'file'} attachment → {@code utils/messages.ts:3545-3572} 渲染成合成
 * Read tool_use/tool_result 对）；超阈值（{@code 0.25*1024*1024 = 262144B}，{@code utils/file.ts:48}
 * {@code MAX_OUTPUT_SIZE}）则 {@code logEvent('tengu_attachment_file_too_large') + return null}
 * —— <b>静默丢弃</b>。本仓附件通道此前对 {@code type=file} 一律只产出一行
 * 「本地路径=…」（{@code buildMediaAttachmentNotes}），正文要模型自己调工具读 ⇒ 与 CC 行为不符。
 *
 * <p><b>本批裁定</b>：阈值内内联正文（沿用本仓既有 CC 对齐常量
 * {@link FileReadingLimits#DEFAULT_MAX_SIZE_BYTES} = 256KB，与 CC {@code MAX_OUTPUT_SIZE} 同值）；
 * 超阈值 <b>不学 CC 的静默 null</b>（违反本仓红线十二 fail loud）⇒ <b>降级保留原有「本地路径」说明</b>
 * + 打 WARN；非文本类（二进制扩展名 / 图片 / PDF）<b>绝不读正文</b>（不破坏 path 通道零拷贝设计）。
 *
 * <p><b>RED tooth</b>：① 去掉内联腿 → 第 1/2/3/4/5 条断言红（只有路径没有正文）；
 * ② 去掉阈值（无上限内联）→ 第 6 条断言红（262145B 的正文被内联 + 无 WARN）。
 */
@DisplayName("[附件通道] 文本类附件正文内联（阈值内）+ 超阈值降级保留路径说明（fail loud）")
class LlmAgentLoopTextAttachmentInlineTest {

    /** 阈值（与 CC MAX_OUTPUT_SIZE 同值 · 本仓单点常量 FileReadingLimits.DEFAULT_MAX_SIZE_BYTES）。 */
    private static final long INLINE_MAX_BYTES = FileReadingLimits.DEFAULT_MAX_SIZE_BYTES;

    private Logger loopLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        loopLogger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        previousLevel = loopLogger.getLevel();
        loopLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        loopLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        loopLogger.detachAppender(appender);
        appender.stop();
        loopLogger.setLevel(previousLevel);
    }

    /** 走 {@code buildMediaAttachmentNotes} 的 ①号 path 分支（path 附件 · 附件表 contentId 已登记）。 */
    private static String notesFor(String filename, String mediaType, String absPath) {
        return LlmAgentLoop.buildMediaAttachmentNotes(
            null, null, "sess-t",
            List.of(new AttachmentRequest("file", "7", filename, mediaType, null, absPath)));
    }

    private static String oneLine(String s) {
        return s == null ? null : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<String> warns() {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    // ── 1. 阈值内：正文必须出现在模型侧 prompt ────────────────────────────────

    @Test
    @DisplayName("① 阈值内 .txt → 正文出现在模型侧说明（改前只有「本地路径=」，红）")
    void txtWithinThreshold_inlinesBody(@TempDir Path dir) throws Exception {
        String body = "第一行：项目约定\n第二行：不要用 grep 当证据\n第三行：end";
        Path file = Files.writeString(dir.resolve("阅读笔记.txt"), body);
        String abs = file.toAbsolutePath().toString();

        String notes = oneLine(notesFor("阅读笔记.txt", "application/octet-stream", abs));

        assertThat(notes).as("正文必须内联（CC @提及 语义：模型不必自己调工具读）").contains(body);
        assertThat(notes).as("原有「本地路径=」说明必须保留（既有测试 LlmAgentLoopAnyExtensionPathNoteTest 依赖）")
            .contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("② 阈值内 .csv → 正文内联")
    void csvWithinThreshold_inlinesBody(@TempDir Path dir) throws Exception {
        String body = "id,name\n1,甲\n2,乙";
        Path file = Files.writeString(dir.resolve("清单.csv"), body);
        String abs = file.toAbsolutePath().toString();

        String notes = oneLine(notesFor("清单.csv", "application/octet-stream", abs));

        assertThat(notes).contains(body);
        assertThat(notes).contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("③ 阈值内 .md → 正文内联")
    void mdWithinThreshold_inlinesBody(@TempDir Path dir) throws Exception {
        String body = "# 标题\n\n- 要点一\n- 要点二";
        Path file = Files.writeString(dir.resolve("说明.md"), body);
        String abs = file.toAbsolutePath().toString();

        String notes = oneLine(notesFor("说明.md", "application/octet-stream", abs));

        assertThat(notes).contains(body);
        assertThat(notes).contains("本地路径=" + abs);
    }

    @Test
    @DisplayName("④ 阈值内无扩展名文件（LICENSE）→ 正文内联")
    void noExtensionWithinThreshold_inlinesBody(@TempDir Path dir) throws Exception {
        String body = "MIT License\nCopyright (c) 2026";
        Path file = Files.writeString(dir.resolve("LICENSE"), body);
        String abs = file.toAbsolutePath().toString();

        String notes = oneLine(notesFor("LICENSE", null, abs));

        assertThat(notes).contains(body);
    }

    @Test
    @DisplayName("⑤ 恰好等于阈值（262144B）→ 内联（CC 判据 stats.size <= maxSizeBytes）")
    void exactlyAtThreshold_inlinesBody(@TempDir Path dir) throws Exception {
        byte[] pad = new byte[(int) INLINE_MAX_BYTES];
        java.util.Arrays.fill(pad, (byte) 'a');
        Path file = dir.resolve("boundary.txt");
        Files.write(file, pad);
        assertThat(Files.size(file)).as("夹具自检：必须恰好等于阈值").isEqualTo(INLINE_MAX_BYTES);

        String notes = notesFor("boundary.txt", "application/octet-stream",
            file.toAbsolutePath().toString());

        assertThat(notes).as("CC 是 <= 判据：恰好等于上限仍在内（不得提前降级）")
            .contains("aaaa");
        assertThat(warns()).as("阈值内不得有 WARN").isEmpty();
    }

    // ── 2. 超阈值：降级保留路径说明 + WARN（不是静默丢） ──────────────────────

    @Test
    @DisplayName("⑥ 超阈值（262145B）→ 不内联正文 + 保留「本地路径=」+ WARN（fail loud，不学 CC 静默 null）")
    void overThreshold_degradesToPathOnly_andWarns(@TempDir Path dir) throws Exception {
        byte[] pad = new byte[(int) INLINE_MAX_BYTES + 1];
        java.util.Arrays.fill(pad, (byte) 'a');
        Path file = dir.resolve("大文件.txt");
        Files.write(file, pad);
        String abs = file.toAbsolutePath().toString();

        String notes = oneLine(notesFor("大文件.txt", "application/octet-stream", abs));

        assertThat(notes).as("正文不得内联").doesNotContain("aaaa");
        assertThat(notes).as("降级而非丢弃：路径说明必须保留（红线十二 fail loud 的降级腿）")
            .contains("本地路径=" + abs);
        assertThat(warns())
            .as("超阈值必须打 ≥WARN（CC 是静默 null，本仓红线十二要求 fail loud）")
            .anyMatch(m -> m.contains("大文件.txt") && m.contains("超内联上限"));
    }

    // ── 3. 非文本类：行为不变（仍只给路径，且绝不读正文） ────────────────────

    @Test
    @DisplayName("⑦ 非文本类 .zip → 行为不变（只给路径，不读正文、不 WARN）")
    void zipAttachment_behaviorUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("打包产物.zip");
        Files.write(file, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x01});
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("打包产物.zip", "application/octet-stream", abs);

        assertThat(notes).as("非文本类不进内联腿").contains("本地路径=" + abs);
        assertThat(notes).as("不得出现正文标记").doesNotContain("【附件文件");
        assertThat(warns()).as("非文本类是正常路径（不是异常），不得 WARN").isEmpty();
    }

    @Test
    @DisplayName("⑧ 非文本类 .docx（Office 通道）→ 行为不变（只给路径）")
    void docxAttachment_behaviorUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("周报.docx");
        Files.write(file, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00});
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("周报.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", abs);

        assertThat(notes).contains("本地路径=" + abs);
        assertThat(notes).doesNotContain("【附件文件");
    }

    @Test
    @DisplayName("⑨ 既有通道不回归：type=image / type=pdf 仍不进媒体说明通道（产物不变）")
    void imageAndPdfTypes_notInMediaNotes(@TempDir Path dir) throws Exception {
        Path png = Files.write(dir.resolve("截图.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Path pdf = Files.writeString(dir.resolve("文档.pdf"), "%PDF-1.4");
        String pngAbs = png.toAbsolutePath().toString();
        String pdfAbs = pdf.toAbsolutePath().toString();

        assertThat(LlmAgentLoop.buildMediaAttachmentNotes(null, null, "sess-t",
            List.of(new AttachmentRequest("image", "7", "截图.png", "image/png", null, pngAbs))))
            .as("image 走大图路径说明（buildLargeImagePathNotes）通道，不由媒体说明负责")
            .isEmpty();
        assertThat(LlmAgentLoop.buildMediaAttachmentNotes(null, null, "sess-t",
            List.of(new AttachmentRequest("pdf", "8", "文档.pdf", "application/pdf", null, pdfAbs))))
            .as("pdf 走 PdfAttachmentProcessor 通道")
            .isEmpty();
    }

    @Test
    @DisplayName("⑩ 防御：图片被误标 type=file → 不得把二进制当正文内联（仍只给路径 + WARN）")
    void mislabeledImageAsFile_notInlined(@TempDir Path dir) throws Exception {
        // PNG 魔数 0x89 是非法 UTF-8 起始字节 ⇒ 解码必然失败（结构性，不依赖偶然）
        Path file = dir.resolve("误标.png");
        Files.write(file, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        String abs = file.toAbsolutePath().toString();

        String notes = notesFor("误标.png", "image/png", abs);

        assertThat(notes).contains("本地路径=" + abs);
        assertThat(notes).as("图片扩展名不得进内联腿（非文本类不读正文）").doesNotContain("【附件文件");
    }
}
