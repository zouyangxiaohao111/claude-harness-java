package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-22/IMP-13 · 合并器行为测试（统一宿主 ToolResultStorage.collectCandidatesByMessage）·
 * 对齐 CC toolResultStorage.ts:600-639 collectCandidatesByMessage + :557-573 collectCandidatesFromMessage。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-22 把三处旧合并实现去重为唯一宿主，
 * IMP-13 随 D-17 删除管线级预算压缩器类后，宿主迁移到 CC 真源同名类
 * {@code ToolResultStorage}（toolResultStorage.ts 的 Java 镜像）。本测试锁定 CC 分组语义
 * 不变量（3 处只留 1 处，无影子实现）：
 * <ol>
 *   <li><b>同 ID 助手片段不产生 group 边界</b>（seenAsstIds，:627-631）：normalizeMessagesForAPI
 *       （messages.ts:2126）会把同 ID 助手片段合并为同一条 wire assistant，预算器必须同样视为
 *       同一 group —— 否则同 ID 助手的 tool_results 被分两组各算一次，可能把已冻结内容错当 fresh
 *       再次替换（破坏 prompt cache 前缀稳定）。</li>
 *   <li><b>候选载体为 Role.tool</b>（Java 扁平化 tool_result；Provider 翻译 Role.tool → role=user），
 *       过滤条件 = collectCandidatesFromMessage (:561-565)：有 toolUseId + 非空 content +
 *       非 isContentAlreadyCompacted（:498-504，content 以
 *       {@code ToolResultStorage.PERSISTED_OUTPUT_TAG} 开头 → double-compact 保护）+
 *       非含 image 块（hasImageBlock :507-516 / :564 —— 图片不能替换成文本 preview，T9/T10）。</li>
 *   <li><b>progress/system/attachment 不创建 wire 边界</b>（:633-634）→ 不 flush 当前组。</li>
 * </ol>
 */
@DisplayName("[IMP-22/IMP-13] ToolResultStorage.collectCandidatesByMessage（统一合并宿主 · CC 600-639）")
class ToolResultStorageCollectCandidatesTest {

    /** 构造 content 块 JsonNode（hasImageBlock 检查 contentBlocks 里 type=='image' 的块）。 */
    private static final ObjectMapper IMAGE_MAPPER = new ObjectMapper();

    private ChatMessageDto asst(String id, String asstMsgId) {
        return new ChatMessageDto(id, "s1", Role.assistant, "assistant",
            "assistant text " + id, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, asstMsgId,
            null, null, List.of());
    }

    private ChatMessageDto tool(String id, String toolCallId, String asstId) {
        return new ChatMessageDto(id, "s1", Role.tool, "tool",
            "tool result " + id, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), toolCallId, asstId,
            null, null, List.of());
    }

    private ChatMessageDto toolWithContent(String id, String toolCallId, String asstId, String content) {
        return new ChatMessageDto(id, "s1", Role.tool, "tool",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), toolCallId, asstId,
            null, null, List.of());
    }

    /**
     * 构造带 content 块数组的 tool 消息（hasImageBlock 检查 Java 端
     * {@code ChatMessageDto.contentBlocks} 里的 {@code type=='image'} 块）。
     */
    private ChatMessageDto toolWithContentBlocks(String id, String toolCallId, String asstId, List<?> contentBlocks) {
        return new ChatMessageDto(id, "s1", Role.tool, "tool",
            "tool result " + id, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), toolCallId, asstId,
            null, contentBlocks, List.of());
    }

    private ChatMessageDto system(String id) {
        return new ChatMessageDto(id, "s1", Role.system, "system",
            "system msg " + id, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, null, List.of());
    }

    @Test
    @DisplayName("T1 空 / null 消息 → 空分组")
    void emptyAndNull_returnsEmptyGroups() {
        assertThat(ToolResultStorage.collectCandidatesByMessage(List.of())).isEmpty();
        assertThat(ToolResultStorage.collectCandidatesByMessage(null)).isEmpty();
    }

    @Test
    @DisplayName("T2 简单场景: [asst(A), tool(t1,A)] → 1 组 [t1]")
    void singleAssistantGroup_singleGroup() {
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(asst("asst-1", "A"), tool("tool-1", "call_t1", "A")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t1");
    }

    @Test
    @DisplayName("T3 同 ID 助手片段不产生边界: [asst(X), tool(t1), asst(X), tool(t2)] → 1 组 [t1,t2]")
    void sameIdAssistantFragments_mergedIntoOneGroup() {
        // CC streamingToolExecution 多 content_block_stop: 同 ID 的 asst 片段不应作为 group 边界
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-frag-1", "X"),
                tool("tool-1", "call_t1", "X"),
                asst("asst-frag-2", "X"),  // 同 ID 助手片段 — 不作为边界
                tool("tool-2", "call_t2", "X")
            ));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId)
            .containsExactly("call_t1", "call_t2");
    }

    @Test
    @DisplayName("T4 不同 ID 助手片段产生边界: [asst(X), tool(t1), asst(Y), tool(t2)] → 2 组")
    void differentAssistantIds_splitGroups() {
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                tool("tool-1", "call_t1", "X"),
                asst("asst-2", "Y"),
                tool("tool-2", "call_t2", "Y")
            ));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t1");
        assertThat(groups.get(1)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t2");
    }

    @Test
    @DisplayName("T5 交错回跳: [asst(X), tool(t1), asst(Y), tool(t2), asst(X), tool(t3)] → 2 组 [t1],[t2,t3]")
    void interleavedSameIdReappears_mergesTrailing() {
        // coordinator/teammate 流交错: X 在 Y 之后重新出现 → X 的 t3 与后续属于同一 wire assistant
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                tool("tool-1", "call_t1", "X"),
                asst("asst-2", "Y"),
                tool("tool-2", "call_t2", "Y"),
                asst("asst-3", "X"),
                tool("tool-3", "call_t3", "X")
            ));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t1");
        assertThat(groups.get(1)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t2", "call_t3");
    }

    @Test
    @DisplayName("T6 isContentAlreadyCompacted 跳过: content 以 <persisted-output> 开头 → 不收集（防 double-compact）")
    void alreadyCompactedContent_skipped() {
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                toolWithContent("tool-1", "call_t1", "X", ToolResultStorage.PERSISTED_OUTPUT_TAG + " 已落盘"),
                tool("tool-2", "call_t2", "X")
            ));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t2");
    }

    @Test
    @DisplayName("T7 无 toolUseId 的 tool 消息不收集（collectCandidatesFromMessage: 需 block.tool_use_id）")
    void toolWithoutToolCallId_skipped() {
        ChatMessageDto noId = new ChatMessageDto("tool-x", "s1", Role.tool, "tool",
            "no call id", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, "X",
            null, null, List.of());

        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(asst("asst-1", "X"), noId, tool("tool-2", "call_t2", "X")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t2");
    }

    @Test
    @DisplayName("T8 system/progress 消息不产生 wire 边界: [asst(X), tool(t1), system, tool(t2)] → 1 组 [t1,t2]")
    void systemMessage_noGroupBoundary() {
        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                tool("tool-1", "call_t1", "X"),
                system("sys-1"),
                tool("tool-2", "call_t2", "X")
            ));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId)
            .containsExactly("call_t1", "call_t2");
    }

    @Test
    @DisplayName("T9 含 image block 的 tool_result 不收集（CC hasImageBlock, toolResultStorage.ts:564）：图片不能被替换成文本 preview 丢失")
    void toolResultWithImageBlock_skipped() {
        // CC collectCandidatesFromMessage (:564) `if (hasImageBlock(block.content)) return []`：
        // content 块数组含 type==='image' 的 tool_result 不是消息级总预算的候选替换对象。
        // Java 端 image 块经 contentBlocks（List<JsonNode>）承载，块 shape = {type:"image", source:{...}}。
        ObjectNode imageBlock = IMAGE_MAPPER.createObjectNode();
        imageBlock.put("type", "image");
        ObjectNode source = imageBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                toolWithContentBlocks("tool-img", "call_img", "X", List.of(imageBlock)),
                tool("tool-2", "call_t2", "X")
            ));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId).containsExactly("call_t2");
    }

    @Test
    @DisplayName("T10 无 image 块的 tool_result 仍为候选（text block / null contentBlocks 均不影响收集）")
    void toolResultWithoutImageBlock_stillCandidate() {
        // hasImageBlock 仅排除 type==='image' 块；text block 与 null contentBlocks 维持候选资格
        // （CC contentSize 也只对 text 块计数，文档/document/tool_reference 块不受影响）。
        ObjectNode textBlock = IMAGE_MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "some text");

        List<List<ChatMessageDto>> groups = ToolResultStorage.collectCandidatesByMessage(
            List.of(
                asst("asst-1", "X"),
                toolWithContentBlocks("tool-text", "call_t1", "X", List.of(textBlock)),
                toolWithContentBlocks("tool-null", "call_t2", "X", null)
            ));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(ChatMessageDto::toolCallId)
            .containsExactly("call_t1", "call_t2");
    }
}
