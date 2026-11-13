package com.nexusai.application.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.infra.llm.OpenAiSdkProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史图片恢复（冷路径）· 真实记录驱动。
 *
 * <p><b>Why 用真实记录</b>：本类 fixture 不是手编对象 —— {@code LIVE_ROW_*} 取自本机
 * {@code ~/.nexusai/nexusai.db} 的 {@code messages} 行（只缩短正文），字段形状 =
 * {@code image_paste_ids} 雪花 id + {@code user_attachments} 真实 JSON；图片本体换成 1x1 PNG
 * （真实 base64 太大且不可入库测试资源）。断言落在两条 provider 的<b>真实 wire JSON</b>
 * （SDK ObjectMappers 序列化），而不是 DTO 层 —— 用户报的问题形态是「模型第二轮看不到图」，
 * 只有 wire 上出现 image block 才算修好。
 *
 * <p><b>边界</b>：
 * <ul>
 *   <li>冷路径：DB 行无 contentBlocks + image-cache 命中 → 补 text + image block；</li>
 *   <li>cache miss（200 上限逐出 / 会话清理）→ 不补，⛔ 不得退化成文件名占位；</li>
 *   <li>热路径：账本快照已带 blocks → 原实例返回，不重复；</li>
 *   <li>当前模型不支持图片 → 不补（否则 DeepSeek 等 400）；</li>
 *   <li>非 user 消息 → 不动。</li>
 * </ul>
 */
class LlmAgentLoopHistoryImageRestoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /** 真实 DB 行 · msg-2009573c（1 张图，雪花 id）· 正文缩短。 */
    private static final String LIVE_ROW_ONE_IMAGE = """
        {
          "id": "msg-2009573c",
          "sessionId": "sess-afbae75d",
          "role": "user",
          "author": null,
          "content": "首先几个问题：1. 合同审查没有单独页面显示（截图见附件）",
          "imagePasteIds": ["2100523247518068736"],
          "userAttachments": [
            {"type": "image", "filename": "image.png", "mediaType": "image/png", "contentId": null, "url": null}
          ],
          "contentBlocks": []
        }
        """;

    /** 真实 DB 行 · msg-82a8e750（3 张图）· 正文缩短。 */
    private static final String LIVE_ROW_THREE_IMAGES = """
        {
          "id": "msg-82a8e750",
          "sessionId": "sess-afbae75d",
          "role": "user",
          "author": null,
          "content": "现在布局有问题 我给你两张图 第一张是现有合同审查的布局",
          "imagePasteIds": [
            "2100832083407785984",
            "2100832083407785985",
            "2100832083407785986"
          ],
          "userAttachments": [
            {"type": "image", "filename": "image.png", "mediaType": "image/png", "contentId": null, "url": null},
            {"type": "image", "filename": "d5e049a1-5191-4980-a493-d3c37f5de256.png", "mediaType": "image/png", "contentId": null, "url": null},
            {"type": "image", "filename": "0a2cc818-b98e-4062-87e4-662db9e77789.png", "mediaType": "image/png", "contentId": null, "url": null}
          ],
          "contentBlocks": []
        }
        """;

    private final ImageAttachmentStore store = new ImageAttachmentStore();

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setAppNameOverride(null);
    }

    @Test
    @DisplayName("真实 DB 行（1 图）+ cache 命中 → 两条 provider wire 都出现 image block（Anthropic base64 / OpenAI data URL）")
    void coldRow_restoresImageBlockOnBothProviders() throws Exception {
        ChatMessageDto cold = fromLiveRow(LIVE_ROW_ONE_IMAGE);
        assertThat(cold.contentBlocks()).isEmpty();
        store.storeWithId(cold.sessionId(), 2100523247518068736L, PNG_BASE64, "image/png");

        List<ChatMessageDto> restored = LlmAgentLoop.restoreHistoryImageBlocks(
            List.of(cold), store, cold.sessionId(), true);

        assertThat(restored).hasSize(1);
        List<?> blocks = restored.get(0).contentBlocks();
        assertThat(blocks).hasSize(2);
        JsonNode text = (JsonNode) blocks.get(0);
        JsonNode image = (JsonNode) blocks.get(1);
        assertThat(text.path("type").asText()).isEqualTo("text");
        assertThat(text.path("text").asText()).isEqualTo(cold.content());
        assertThat(image.path("type").asText()).isEqualTo("image");
        assertThat(image.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(image.path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(image.path("source").path("data").asText()).isEqualTo(PNG_BASE64);

        // 真实 wire：Anthropic —— content[1].type=image + source.data
        JsonNode anthropic = anthropicWire(restored);
        JsonNode aMsg = anthropic.get(0);
        assertThat(aMsg.path("role").asText()).isEqualTo("user");
        assertThat(aMsg.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(aMsg.path("content").get(1).path("type").asText()).isEqualTo("image");
        assertThat(aMsg.path("content").get(1).path("source").path("data").asText())
            .isEqualTo(PNG_BASE64);

        // 真实 wire：OpenAI —— content[1].type=image_url + data URL
        JsonNode openai = openAiWire(restored);
        JsonNode oMsg = openai.get(0);
        assertThat(oMsg.path("role").asText()).isEqualTo("user");
        assertThat(oMsg.path("content").get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(oMsg.path("content").get(1).path("image_url").path("url").asText())
            .isEqualTo("data:image/png;base64," + PNG_BASE64);
    }

    @Test
    @DisplayName("真实 DB 行（3 图）→ 顺序恢复 3 个 image block，与 image_paste_ids 同序")
    void coldRow_threeImages_preserveOrder() throws Exception {
        ChatMessageDto cold = fromLiveRow(LIVE_ROW_THREE_IMAGES);
        store.storeWithId(cold.sessionId(), 2100832083407785984L, PNG_BASE64, "image/png");
        store.storeWithId(cold.sessionId(), 2100832083407785985L, PNG_BASE64, "image/png");
        store.storeWithId(cold.sessionId(), 2100832083407785986L, PNG_BASE64, "image/png");

        List<?> blocks = LlmAgentLoop.restoreHistoryImageBlocks(
            List.of(cold), store, cold.sessionId(), true).get(0).contentBlocks();

        assertThat(blocks).hasSize(4); // text + 3 images
        for (int i = 1; i <= 3; i++) {
            assertThat(((JsonNode) blocks.get(i)).path("type").asText()).isEqualTo("image");
        }
    }

    @Test
    @DisplayName("边界 · cache miss（id 不存在）→ 不恢复、原实例返回，且不得出现文件名占位")
    void cacheMiss_keepsPlainText_noFilenamePlaceholder() throws Exception {
        ChatMessageDto cold = fromLiveRow(LIVE_ROW_ONE_IMAGE);
        // 不落盘 → getBase64OrDisk 内存/磁盘均 miss
        List<ChatMessageDto> input = List.of(cold);

        List<ChatMessageDto> restored = LlmAgentLoop.restoreHistoryImageBlocks(
            input, store, cold.sessionId(), true);

        assertThat(restored).isSameAs(input);
        assertThat(restored.get(0).contentBlocks()).isEmpty();
        assertThat(restored.get(0).content()).doesNotContain("image.png");
    }

    @Test
    @DisplayName("边界 · 当前模型不支持图片 → 原实例返回（防 DeepSeek 等 400）")
    void unsupportedModel_doesNotRestore() throws Exception {
        ChatMessageDto cold = fromLiveRow(LIVE_ROW_ONE_IMAGE);
        store.storeWithId(cold.sessionId(), 2100523247518068736L, PNG_BASE64, "image/png");
        List<ChatMessageDto> input = List.of(cold);

        assertThat(LlmAgentLoop.restoreHistoryImageBlocks(input, store, cold.sessionId(), false))
            .isSameAs(input);
    }

    @Test
    @DisplayName("边界 · 热路径已带 contentBlocks → 原实例返回，不重复恢复")
    void hotPathWithBlocks_untouched() throws Exception {
        ChatMessageDto cold = fromLiveRow(LIVE_ROW_ONE_IMAGE);
        store.storeWithId(cold.sessionId(), 2100523247518068736L, PNG_BASE64, "image/png");
        JsonNode alreadyThere = JSON.readTree("""
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"%s"}}
            """.formatted(PNG_BASE64));
        ChatMessageDto hot = cold.withContentBlocks(List.of(alreadyThere));
        List<ChatMessageDto> input = List.of(hot);

        assertThat(LlmAgentLoop.restoreHistoryImageBlocks(input, store, cold.sessionId(), true))
            .isSameAs(input);
    }

    @Test
    @DisplayName("边界 · 非 user 消息即使带 imagePasteIds 也不动")
    void nonUserRole_untouched() throws Exception {
        List<String> ids = List.of("2100523247518068736");
        ChatMessageDto assistant = new ChatMessageDto(
            "msg-assistant", "sess-afbae75d", Role.assistant, null, "完成",
            null, null, null, null, null, null, null, null, null, null,
            List.of(), ids, null);
        store.storeWithId("sess-afbae75d", 2100523247518068736L, PNG_BASE64, "image/png");

        List<ChatMessageDto> input = List.of(assistant);
        assertThat(LlmAgentLoop.restoreHistoryImageBlocks(input, store, "sess-afbae75d", true))
            .isSameAs(input);
    }

    // ─────────── helpers ───────────

    /** 真实 DB 行 JSON → DTO（字段值全部取自 JSON，不手编）。 */
    private static ChatMessageDto fromLiveRow(String rowJson) throws Exception {
        JsonNode row = JSON.readTree(rowJson);
        List<String> imagePasteIds = JSON.readValue(
            row.path("imagePasteIds").toString(), new TypeReference<List<String>>() { });
        List<?> contentBlocks = JSON.readValue(
            row.path("contentBlocks").toString(), new TypeReference<List<Object>>() { });
        return new ChatMessageDto(
            row.path("id").asText(),
            row.path("sessionId").asText(),
            Role.valueOf(row.path("role").asText()),
            row.path("author").isNull() ? null : row.path("author").asText(),
            row.path("content").asText(),
            null, null, null, null, null, null, null, null, null, null,
            contentBlocks, imagePasteIds, null);
    }

    /** 生产 Anthropic wire：私有 buildSdkMessages 反射取（与生产同一实现）。 */
    private static JsonNode anthropicWire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(m.invoke(null, history)));
    }

    /** 生产 OpenAI wire：buildSdkMessages → SDK ObjectMappers 序列化。 */
    private static JsonNode openAiWire(List<ChatMessageDto> history) throws Exception {
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(OpenAiSdkProvider.buildSdkMessages(history)));
    }
}
