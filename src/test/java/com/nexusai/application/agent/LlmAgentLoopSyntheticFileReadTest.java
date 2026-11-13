package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.infra.llm.OpenAiSdkProvider;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件附件预读 · 完全对齐 CC/CCB（两条 {@code isMeta:true} 的 user 纯文本消息）。
 *
 * <p><b>真源</b>：CCB {@code messages.ts:4765-4773} / {@code 4728-4763} 与 claude.exe 2.1.323
 * （off≈320,946,194 / 320,946,388）逐字一致：
 * <pre>
 *   Called the ${toolName} tool with the following input: ${jsonStringify(input)}
 *   Result of calling the ${tool.name} tool:\n${content}
 * </pre>
 * 两条都是 {@code createUserMessage({content, isMeta:true})}，不是 tool_use/tool_result 块。
 *
 * <p><b>为什么断言 wire</b>：用户报的问题是「第二轮模型看不到文件正文」；只有两条消息
 * 真的出现在 Anthropic / OpenAI 的请求 JSON 里，且历史落库可回放，才算修好。
 * 本仓落库出口 = {@code ChatService.persistAppendedMessage} 的
 * {@code isMeta && author="attachment"} 分支，故断言 author/subtype 契约。
 *
 * <p><b>真实记录</b>：正文取真实行 {@code msg-f585405e}（docx 正文片段）；docx 用真实文件名样本。
 */
class LlmAgentLoopSyntheticFileReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 真实行 msg-f585405e 的正文片段（脱敏前原文节选）。 */
    private static final String LIVE_FILE_BODY =
        "深耕住建领域数字化建设，聚焦行业实际业务痛点，持续打磨数字化产品与解决方案，"
            + "在智慧监管、数字住建、城乡数字化治理等方向积累了丰富实践经验。";

    /** 真实行 msg-f585405e 的文件名。 */
    private static final String LIVE_DOCX_FILENAME =
        "喜报｜我院下属数字公司两个项目成功入选全省住建领域“人工智能+”重点应用场景项目建设实施主体名单.docx";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("真实文本正文 + type=file → 两条 meta user 消息：CCB/exe 原样文案，author=attachment 触发落库")
    void textFile_generatesCcSyntheticPair() throws Exception {
        Path file = tmp.resolve("report.md");
        Files.writeString(file, LIVE_FILE_BODY, StandardCharsets.UTF_8);

        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "report.md", "text/markdown", null, file.toString())),
            null, "sess-afbae75d");

        assertThat(messages).hasSize(2);
        String inputJson = JSON.writeValueAsString(Map.of("file_path", file.toString()));
        assertThat(messages.get(0).content())
            .isEqualTo("<system-reminder>\nCalled the Read tool with the following input: "
                + inputJson + "\n</system-reminder>");
        assertThat(messages.get(1).content())
            .isEqualTo("<system-reminder>\nResult of calling the Read tool:\n"
                + LIVE_FILE_BODY + "\n</system-reminder>");
        for (ChatMessageDto m : messages) {
            assertThat(m.role()).isEqualTo(Role.user);
            assertThat(m.isMeta()).isTrue();
            assertThat(m.author()).isEqualTo("attachment");
            assertThat(m.subtype()).isEqualTo(LlmAgentLoop.SYNTHETIC_FILE_READ_SUBTYPE);
            assertThat(m.contentBlocks()).isEmpty();
            assertThat(m.id()).startsWith("msg-file-read-");
        }
    }

    @Test
    @DisplayName("两条消息在 Anthropic / OpenAI 真实 wire 上都是 role=user + content 字符串（历史原样回放）")
    void syntheticPair_onBothProviderWires() throws Exception {
        Path file = tmp.resolve("report.md");
        Files.writeString(file, LIVE_FILE_BODY, StandardCharsets.UTF_8);
        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "report.md", "text/markdown", null, file.toString())),
            null, "sess-afbae75d");

        JsonNode anthropic = anthropicWire(messages);
        assertThat(anthropic).hasSize(2);
        assertThat(anthropic.get(0).path("role").asText()).isEqualTo("user");
        assertThat(anthropic.get(0).path("content").isTextual()).isTrue();
        assertThat(anthropic.get(0).path("content").asText())
            .startsWith("<system-reminder>\nCalled the Read tool with");
        assertThat(anthropic.get(1).path("role").asText()).isEqualTo("user");
        assertThat(anthropic.get(1).path("content").asText())
            .isEqualTo("<system-reminder>\nResult of calling the Read tool:\n"
                + LIVE_FILE_BODY + "\n</system-reminder>");

        JsonNode openai = openAiWire(messages);
        assertThat(openai).hasSize(2);
        assertThat(openai.get(0).path("role").asText()).isEqualTo("user");
        assertThat(openai.get(0).path("content").asText())
            .startsWith("<system-reminder>\nCalled the Read tool with");
        assertThat(openai.get(1).path("content").asText())
            .isEqualTo("<system-reminder>\nResult of calling the Read tool:\n"
                + LIVE_FILE_BODY + "\n</system-reminder>");
    }

    @Test
    @DisplayName("2500 行文本 → 第二条只含前 2000 行 + 第三条 CCB 截断 Note（messages.ts:3994-3997）")
    void longText_truncatedTo2000Lines() throws Exception {
        Path file = tmp.resolve("long.md");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2500; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "long.md", "text/markdown", null, file.toString())),
            null, "sess-afbae75d");

        assertThat(messages).hasSize(3);
        String result = messages.get(1).content();
        assertThat(result).startsWith("<system-reminder>\nResult of calling the Read tool:\nline-1\n");
        assertThat(result).contains("line-2000");
        assertThat(result).doesNotContain("line-2001");
        assertThat(messages.get(2).content()).isEqualTo(
            "<system-reminder>\nNote: The file long.md was too large and has been truncated to the first 2000 lines. "
                + "Don't tell the user about this truncation. Use Read to read more of the file if you need."
                + "\n</system-reminder>");
        assertThat(messages.get(2).isMeta()).isTrue();
        assertThat(messages.get(2).author()).isEqualTo("attachment");
        assertThat(messages.get(2).subtype()).isEqualTo(LlmAgentLoop.SYNTHETIC_FILE_READ_SUBTYPE);
    }

    @Test
    @DisplayName("真实 docx（二进制）→ 一条路径说明（不预读，但模型要拿到本地路径；CC 靠用户原文 @path，本仓无）")
    void docx_binary_getsPathNote() throws Exception {
        Path file = tmp.resolve(LIVE_DOCX_FILENAME);
        Files.write(file, new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00});

        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, LIVE_DOCX_FILENAME,
                "application/octet-stream", null, file.toString())),
            null, "sess-afbae75d");

        assertThat(messages).hasSize(1);
        String note = messages.get(0).content();
        assertThat(note).startsWith("<system-reminder>\n- file 附件 " + LIVE_DOCX_FILENAME);
        assertThat(note).contains("本地路径=" + file);
        assertThat(note).contains("二进制文件");
        assertThat(note).contains("内容未预读");
        assertThat(messages.get(0).role()).isEqualTo(Role.user);
        assertThat(messages.get(0).isMeta()).isTrue();
        assertThat(messages.get(0).author()).isEqualTo("attachment");
        assertThat(messages.get(0).subtype()).isEqualTo(LlmAgentLoop.SYNTHETIC_FILE_READ_SUBTYPE);
    }

    @Test
    @DisplayName("文本超 256KB → 一条路径说明（含上限与分段读取引导，模型可按 offset/limit 自行 Read）")
    void oversizeText_getsPathNote() throws Exception {
        Path file = tmp.resolve("large.md");
        Files.writeString(file, "x".repeat(300 * 1024), StandardCharsets.UTF_8);

        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "large.md", "text/markdown", null, file.toString())),
            null, "sess-afbae75d");

        assertThat(messages).hasSize(1);
        String note = messages.get(0).content();
        assertThat(note).contains("本地路径=" + file);
        assertThat(note).contains("超过预读上限 262144B");
        assertThat(note).contains("offset/limit");
        assertThat(messages.get(0).isMeta()).isTrue();
        assertThat(messages.get(0).author()).isEqualTo("attachment");
    }

    @Test
    @DisplayName("边界 · 路径不可读（文件不存在）→ 仍给一条路径说明，不静默丢弃")
    void unreadablePath_stillGetsNote() {
        Path ghost = tmp.resolve("ghost.md");
        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "ghost.md", "text/markdown", null, ghost.toString())),
            null, "sess-afbae75d");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).contains("本地路径=" + ghost);
        assertThat(messages.get(0).content()).contains("预读失败");
        assertThat(messages.get(0).isMeta()).isTrue();
    }

    @Test
    @DisplayName("边界 · file 无 path 且无 contentId → 零消息（不留文件名占位）")
    void noPathNoContentId_skipped() {
        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(new AttachmentRequest("file", null, "orphan.md", "text/markdown", null, null)),
            null, "sess-afbae75d");

        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("边界 · 非 file 类型（image/pdf/video/audio）不进入本通道")
    void nonFileTypes_ignored() throws Exception {
        Path file = tmp.resolve("x.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        List<ChatMessageDto> messages = LlmAgentLoop.buildSyntheticFileReadMessages(
            List.of(
                new AttachmentRequest("image", null, "x.png", "image/png", null, file.toString()),
                new AttachmentRequest("pdf", null, "x.pdf", "application/pdf", null, file.toString()),
                new AttachmentRequest("video", null, "x.mp4", "video/mp4", null, file.toString())),
            null, "sess-afbae75d");

        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("大 PDF（15 页）→ pdf_reference 独立 isMeta 消息（含真实路径/页数/pages 引导）")
    void bigPdf_referenceMessage() throws Exception {
        com.nexusai.application.agent.attachment.PdfAttachmentProcessor.PendingPdf pdf =
            new com.nexusai.application.agent.attachment.PdfAttachmentProcessor.PendingPdf(
                "报告.pdf", List.of(), true, 15, List.of(), "D:/docs/报告.pdf");
        Method m = LlmAgentLoop.class.getDeclaredMethod("buildPdfGuidanceNote",
            com.nexusai.application.agent.attachment.PdfAttachmentProcessor.PendingPdf.class,
            boolean.class, String.class);
        m.setAccessible(true);
        String note = (String) m.invoke(null, pdf, true, null);
        assertThat(note).contains("D:/docs/报告.pdf").contains("15 页").contains("pages");

        ChatMessageDto msg = LlmAgentLoop.pdfReferenceMessage("sess-afbae75d", note);
        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.isMeta()).isTrue();
        assertThat(msg.author()).isEqualTo("attachment");
        assertThat(msg.subtype()).isEqualTo(LlmAgentLoop.PDF_REFERENCE_SUBTYPE);
        assertThat(msg.content()).startsWith("<system-reminder>\n").contains("D:/docs/报告.pdf");
        assertThat(msg.content()).endsWith("\n</system-reminder>");
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.id()).startsWith("msg-pdf-ref-");
    }

    @Test
    @DisplayName("大 PDF 引导 stash → 主消息 append 后 drain 一次（不重复注入）")
    void pdfReferenceNotes_stashAndDrain() {
        com.nexusai.application.agent.attachment.PdfAttachmentProcessor p =
            new com.nexusai.application.agent.attachment.PdfAttachmentProcessor();
        p.stashReferenceNotes("sess-x", List.of("note-1", "note-2"));
        assertThat(p.drainReferenceNotes("sess-x")).containsExactly("note-1", "note-2");
        assertThat(p.drainReferenceNotes("sess-x")).isEmpty();
    }

    // ─────────── helpers ───────────

    private static JsonNode anthropicWire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(m.invoke(null, history)));
    }

    private static JsonNode openAiWire(List<ChatMessageDto> history) throws Exception {
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(OpenAiSdkProvider.buildSdkMessages(history)));
    }
}
