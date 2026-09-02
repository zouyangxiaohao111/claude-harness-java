package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-AL-01 · PDF document/页图块模型送达对齐（P-CC-01 P1 缺口修复）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>tool_result 载荷必须只含文本摘要</b> —— CC
 *       {@code mapToolResultToToolResultBlockParam} pdf/parts case 输出
 *       {@code "PDF file read: ..."} / {@code "PDF pages extracted: ..."} 摘要文本
 *       （FileReadTool.ts:672-685），base64 绝不进入 tool_result 载荷。
 *       旧 Java 把 data(JsonNode) 全量 stringify → 20MB PDF → ~27MB base64 文本进单条
 *       tool_result（token 爆炸）。若此断言不成立，PDF 读取会再次污染模型上下文。</li>
 *   <li><b>PDF 二进制必须走 newMessages/isMeta document block 通道</b> —— CC
 *       {@code FileReadTool.ts:999-1016} {@code newMessages: [createUserMessage({content:
 *       [document block], isMeta: true})]}。旧 Java 无 newMessages 送达，模型根本看不到
 *       PDF 内容（P-CC-01 reflector 判定「功能实际不可用」级缺口）。</li>
 *   <li><b>页图必须走 newMessages/isMeta image blocks 通道</b> —— CC
 *       {@code FileReadTool.ts:938-945}（单条 user message 携带全部页图 image blocks）。</li>
 *   <li><b>Provider 层必须渲染 user 消息的 contentBlocks document/image 块</b> —— CC
 *       user message content 即块数组；Java ChatMessageDto.contentBlocks 需要
 *       AnthropicSdkProvider/OpenAiSdkProvider 在 role=user 序列化分支真实渲染（此前仅
 *       role=tool 分支支持 image/text）。</li>
 * </ol>
 */
@DisplayName("P-AL-01 · PDF document/页图块模型送达对齐 CC FileReadTool.ts")
class PdfDeliveryAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ═════════════ 1. tool_result 载荷 = 文本摘要（非全量 base64 JSON）═════════════

    @Test
    @DisplayName("toolResultMessage: pdf 载荷 = summary 文本，绝不包含 document_base64 —— 对齐 CC FileReadTool.ts:672-678")
    void toolResultMessagePdfPayloadIsSummaryNotBase64() {
        String summary = "PDF file read: /tmp/doc.pdf (1.2 MB)";
        ToolResult<?> pdf = ToolResult.pdf("call-pdf-1", summary,
            "JVBERi0xLjQ=", "application/pdf", 1_200_000L, List.of());

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(pdf);

        assertThat(msg.content())
            .as("tool_result 载荷必须是 CC 摘要文本，禁止全量 JSON stringify（20MB PDF → 27MB base64 文本）")
            .isEqualTo(summary);
        assertThat(msg.content())
            .as("document_base64 不得泄漏进 tool_result 载荷")
            .doesNotContain("JVBERi0xLjQ=")
            .doesNotContain("document_base64");
    }

    @Test
    @DisplayName("toolResultMessage: parts 载荷 = summary 文本（无 base64）—— 对齐 CC FileReadTool.ts:679-685")
    void toolResultMessagePartsPayloadIsSummary() {
        String summary = "PDF pages extracted: 5 page(s) from /tmp/doc.pdf (1.2 MB)";
        ToolResult<?> parts = ToolResult.parts("call-parts-1", summary,
            "/tmp/doc.pdf", 1_200_000L, 5, "/tmp/pdf-out", List.of());

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(parts);

        assertThat(msg.content()).isEqualTo(summary);
    }

    @Test
    @DisplayName("toolResultMessage: file_unchanged 载荷 = summary 文本 —— 对齐 CC FILE_UNCHANGED_STUB 语义")
    void toolResultMessageFileUnchangedPayloadIsSummary() {
        String summary = "<file_unchanged> path=doc.txt (offset=1, limit=2000)";
        ToolResult<?> unchanged = ToolResult.fileUnchanged("call-u-1", summary, "doc.txt");

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(unchanged);

        assertThat(msg.content()).isEqualTo(summary);
    }

    // ═════════════ 2. PDF document block 走 newMessages/isMeta 通道 ═════════════

    @Test
    @DisplayName("dispatchPdfFull: 真实 PDF → newMessages 携带 1 条 isMeta user 消息 + document block —— 对齐 CC :999-1016")
    void dispatchPdfFullAttachesDocumentMetaMessage(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));

        ToolResult<?> result = (ToolResult<?>) tool.execute(callWith("doc.pdf"), ctxWithSession(workspace));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        List<ChatMessageDto> newMessages = result.newMessages();
        assertThat(newMessages)
            .as("CC FileReadTool.ts:1001-1015 必须返回 document block newMessages")
            .hasSize(1);
        ChatMessageDto meta = newMessages.get(0);
        assertThat(meta.isMeta())
            .as("document block 消息必须 isMeta=true（模型可见、UI 隐藏，CC createUserMessage({isMeta:true})）")
            .isTrue();
        assertThat(meta.role()).isEqualTo(Role.user);
        JsonNode docBlock = firstContentBlock(meta);
        assertThat(docBlock.get("type").asText()).isEqualTo("document");
        assertThat(docBlock.get("source").get("type").asText()).isEqualTo("base64");
        assertThat(docBlock.get("source").get("media_type").asText()).isEqualTo("application/pdf");
        // document block 载荷 = 与 data 通道同一份 base64（CC data: pdfData + newMessages 同源）
        String dataBase64 = ((JsonNode) result.data()).get("document_base64").asText();
        assertThat(docBlock.get("source").get("data").asText()).isEqualTo(dataBase64);
        byte[] decoded = Base64.getDecoder().decode(dataBase64);
        assertThat(new String(decoded, 0, 5)).isEqualTo("%PDF-");
    }

    // ═════════════ 3. 页图 image blocks 走 newMessages/isMeta 通道 ═════════════

    @Test
    @DisplayName("dispatchPdfPages: pages=1-2 → newMessages 携带 1 条 isMeta user 消息 + 2 个 image blocks —— 对齐 CC :938-945")
    void dispatchPdfPagesAttachesPageImageMetaMessages(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));

        ToolResult<?> result = (ToolResult<?>) tool.execute(
            callWith("doc.pdf", "pages", "1-2"), ctxWithSession(workspace));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        List<ChatMessageDto> newMessages = result.newMessages();
        assertThat(newMessages)
            .as("CC FileReadTool.ts:938-945 页图必须作为 newMessages 送达")
            .hasSize(1);
        ChatMessageDto meta = newMessages.get(0);
        assertThat(meta.isMeta()).isTrue();
        assertThat(meta.role()).isEqualTo(Role.user);
        List<?> blocks = meta.contentBlocks();
        assertThat(blocks)
            .as("2 页提取 → 2 个 image blocks（CC :927-936 每个页图一个 image block）")
            .hasSize(2);
        for (Object b : blocks) {
            JsonNode block = (JsonNode) b;
            assertThat(block.get("type").asText()).isEqualTo("image");
            assertThat(block.get("source").get("media_type").asText()).isEqualTo("image/jpeg");
            byte[] img = Base64.getDecoder().decode(block.get("source").get("data").asText());
            assertThat(img.length > 2).isTrue();
            // JPEG magic FFD8（PdfSupport 100 DPI JPEG 渲染）
            assertThat((img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8)
                .as("页图必须是可解码 JPEG（FFD8 magic）")
                .isTrue();
        }
    }

    // ═════════════ 4. Provider 层渲染 user 消息 contentBlocks（document/image/text）═════════════

    @Test
    @DisplayName("AnthropicSdkProvider: role=user + contentBlocks=[document] → content 数组渲染 document 块 —— 对齐 CC user message content=块数组")
    void anthropicProviderRendersDocumentBlockInUserMessage() throws Exception {
        ChatMessageDto meta = userMetaWithDocumentBlock("QUJDRA==");
        JsonNode userMsg = invokeAnthropicBuildRequestBody(List.of(meta)).get("messages").get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");
        JsonNode content = userMsg.get("content");
        assertThat(content.isArray()).as("document 块送达时 user content 必须是块数组").isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("document");
        assertThat(content.get(0).get("source").get("type").asText()).isEqualTo("base64");
        assertThat(content.get(0).get("source").get("media_type").asText()).isEqualTo("application/pdf");
        assertThat(content.get(0).get("source").get("data").asText()).isEqualTo("QUJDRA==");
    }

    @Test
    @DisplayName("OpenAiSdkProvider: role=user + contentBlocks=[image] → content 数组渲染 image_url（P-AL-01 SDK 能力）")
    void openAiProviderRendersImageBlockInUserMessage() throws Exception {
        ChatMessageDto meta = userMetaWithImageBlock();

        JsonNode userMsg = invokeOpenAiSdkBuildMessages(List.of(meta)).get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");
        JsonNode content = userMsg.get("content");
        assertThat(content.isArray()).as("image 块送达时 user content 必须是块数组").isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(0).get("image_url").get("url").asText())
            .as("image → OpenAI image_url（CC {type:image,source:{...}} → image_url.url）")
            .isEqualTo("data:image/png;base64,QUJDRA==");
    }

    @Test
    @DisplayName("OpenAiSdkProvider: role=user + contentBlocks=[document] → document 块跳过（SDK 0.25.0 无 document part · R-U-1）")
    void openAiProviderDropsDocumentBlockInUserMessage() throws Exception {
        ChatMessageDto meta = userMetaWithDocumentBlock("QUJDRA==");

        JsonNode userMsg = invokeOpenAiSdkBuildMessages(List.of(meta)).get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");
        assertThat(userMsg.has("content"))
            .as("[OpenAI-SDK] R-U-1 · SDK 0.25.0 无 document content part → document 块跳过（受控残留，PDF 送达走 Anthropic 路径）")
            .isTrue();
        assertThat(userMsg.get("content").asText()).isEqualTo("");
    }

    // ═════════════ 3.5 [pdf-vision-align] 文本模型 PDF · 不发 document/image block（deepseek 400 根因防线）═════════════

    @Test
    @DisplayName("[pdf-vision-align] 文本模型 + store → dispatchPdfFull 返回文本成功（页图注册 contentId），不发 document/image block newMessages")
    void textModelDispatch_emitsNoDocumentOrImageNewMessages(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));
        ImageAttachmentStore imageStore = new ImageAttachmentStore();
        tool.setImageAttachmentStore(imageStore);
        ToolUseContext ctx = textModelCtx(workspace);

        ToolResult<?> result = (ToolResult<?>) tool.execute(callWith("doc.pdf"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.newMessages())
            .as("文本模型下绝不把 document/image block 发给主模型（deepseek 400 根因防线）")
            .isNullOrEmpty();
        assertThat((String) result.data())
            .contains("vision_analyze")
            .contains("contentId=")
            .contains("doc.pdf");
    }

    /** [pdf-vision-align] 文本模型上下文：appState mainLoopModel=claude-3-haiku（mappers 未注入 → 1 参回落 false → 文本模型分支）。 */
    private static ToolUseContext textModelCtx(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("pal01-haiku-agent-" + workspace).getBytes());
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return ToolUseContext.of(
            agentId, sessionId, PermissionMode.DEFAULT,
            java.util.List.<com.nexusai.application.agent.tool.Tool>of(),
            "", AbortController.NOOP,
            java.util.List.<Object>of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.<String, com.nexusai.application.agent.tool.McpClientRuntime>of(),
            false, "", null,
            null,
            java.util.Map.<String, com.nexusai.application.agent.tool.ToolDecisionInfo>of(),
            null,
            state -> state == null
                ? java.util.Map.<String, Object>of("mainLoopModel", "claude-3-haiku")
                : state,
            updater -> {
            }, m -> {
            }, s -> {
            });
    }

    @Test
    @DisplayName("AnthropicSdkProvider: role=tool + contentBlocks=[document] → document 块随 contentBlocks 渲染（CC addToolResult contentBlocks 可含任意块）")
    void anthropicProviderRoleToolRendersDocumentBlockInContentBlocks() throws Exception {
        ChatMessageDto toolMsg = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, "tool",
            "PDF file read: /tmp/doc.pdf (1.2 MB)",
            null, null, null, null, null, null, null,
            "call-pdf-1", null,
            null, List.of(documentBlockNode("QUJDRA==")), List.of(),
            null, false, false);

        JsonNode userMsg = invokeAnthropicBuildRequestBody(List.of(toolMsg)).get("messages").get(0);
        JsonNode content = userMsg.get("content");
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(content.get(1).get("type").asText())
            .as("role=tool contentBlocks 中的 document 块必须渲染（CC addToolResult contentBlocks push）")
            .isEqualTo("document");
        assertThat(content.get(1).get("source").get("data").asText()).isEqualTo("QUJDRA==");
    }

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private static JsonNode invokeAnthropicBuildRequestBody(List<ChatMessageDto> history) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params =
            com.nexusai.infra.llm.AnthropicSdkProvider.buildMessageParams(
                "claude-test", null, history, null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }

    /** [OpenAI-SDK 迁移] 生产 SDK wire：OpenAiSdkProvider.buildSdkMessages → ObjectMappers 序列化。 */
    private static JsonNode invokeOpenAiSdkBuildMessages(List<ChatMessageDto> history) throws Exception {
        java.util.List<com.openai.models.ChatCompletionMessageParam> msgs =
            com.nexusai.infra.llm.OpenAiSdkProvider.buildSdkMessages(history);
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(msgs));
    }

    // ═════════════ helpers ═════════════

    private static ChatMessageDto userMetaWithDocumentBlock(String base64) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            null, null, null, null, null, null, null, null,
            null, null,
            null, List.of(documentBlockNode(base64)), List.of(),
            null, true, false);
    }

    private static ChatMessageDto userMetaWithImageBlock() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "image");
        ObjectNode source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "QUJDRA==");
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            null, null, null, null, null, null, null, null,
            null, null,
            null, List.of(block), List.of(),
            null, true, false);
    }

    private static ObjectNode documentBlockNode(String base64) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "document");
        ObjectNode source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", base64);
        return block;
    }

    private static JsonNode firstContentBlock(ChatMessageDto msg) {
        assertThat(msg.contentBlocks()).isNotEmpty();
        return (JsonNode) msg.contentBlocks().get(0);
    }

    private static ToolUseBlock callWith(String path, Object... extras) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            String key = (String) extras[i];
            Object val = extras[i + 1];
            if (val instanceof Integer n) input.put(key, n);
            else if (val instanceof String s) input.put(key, s);
        }
        return new ToolUseBlock("call-pal01-1", "read_file", input);
    }

    private static ToolUseContext ctxWithSession(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("pal01-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("pal01-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    /** 用 pdfbox 生成真实 PDF（P-CC-01 拍板引入依赖后的测试夹具，与 ReadFileToolTest 同模式）。 */
    private static Path writeRealPdf(Path workspace, String name, int pages) throws Exception {
        Path file = workspace.resolve(name);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(file.toFile());
        }
        return file;
    }
}
