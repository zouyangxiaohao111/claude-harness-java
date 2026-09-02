package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexusai.application.agent.compact.CompactBoundaryMessage.CompactMetadata;
import com.nexusai.application.agent.compact.CompactBoundaryMessage.MicrocompactMetadata;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-05 · boundary 结构化契约 + 读侧切片单测 · 对齐 CC messages.ts:4530-4656。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-05 的目标是把边界从「文本前缀
 * {@code [Compact boundary: X]}」双轨统一为 CC 结构化 subtype 单一表示（OD-18 裁决），
 * 并让读侧按 subtype 判别边界。本测试逐条验证 IMP-05 §5 验收标准：
 * <ol>
 *   <li>compactMetadata 4 字段（trigger/preTokens/userContext/messagesSummarized）+ logicalParentUuid</li>
 *   <li>读侧 findLastCompactBoundaryIndex + getMessagesAfterCompactBoundary（含/不含边界行为）</li>
 *   <li>序列化后 subtype 为 compact_boundary / microcompact_boundary（REQ-05）</li>
 *   <li>无边界返回全量（REQ-29）</li>
 * </ol>
 */
class CompactBoundaryMessageCcTest {

    private static final String SUBTYPE_COMPACT = "compact_boundary";
    private static final String SUBTYPE_MICRO = "microcompact_boundary";

    private static final ObjectMapper MAPPER =
        new ObjectMapper().registerModule(new JavaTimeModule());

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · boundary 字段单测：compactMetadata 4 字段 + logicalParentUuid
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("compact_boundary: compactMetadata 4 字段 + logicalParentUuid (REQ-05 / messages.ts:4530-4555)")
    void compactBoundaryStructuredFields() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 12345, "parent-uuid-1", "用户上下文", 42);

        assertThat(boundary.subtype()).isEqualTo(SUBTYPE_COMPACT);
        assertThat(boundary.content()).isEqualTo("Conversation compacted");   // CC content 常量
        assertThat(boundary.level()).isEqualTo("info");
        assertThat(boundary.isMeta()).isFalse();
        assertThat(boundary.logicalParentUuid()).isEqualTo("parent-uuid-1");

        CompactMetadata meta = boundary.compactMetadata();
        assertThat(meta).isNotNull();
        assertThat(meta.trigger()).isEqualTo("auto");
        assertThat(meta.preTokens()).isEqualTo(12345);
        assertThat(meta.userContext()).isEqualTo("用户上下文");
        assertThat(meta.messagesSummarized()).isEqualTo(42);
        // CC 契约红线：compactMetadata 恰 4 字段，无多余字段（01 §2）
        assertThat(boundary.microcompactMetadata()).isNull();
    }

    @Test
    @DisplayName("compact_boundary: 无 userContext/messagesSummarized 时默认空值 (messages.ts:4539-4551)")
    void compactBoundaryOptionalMetadataFields() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "manual", 100, null, null, null);

        assertThat(boundary.logicalParentUuid()).isNull();
        CompactMetadata meta = boundary.compactMetadata();
        assertThat(meta.userContext()).isNull();
        assertThat(meta.messagesSummarized()).isNull();
    }

    @Test
    @DisplayName("microcompact_boundary: subtype 表示 + microcompactMetadata (messages.ts:4557-4575)")
    void microcompactBoundaryRepresentation() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createMicrocompactBoundaryMessage(
            "auto", 500, 200, List.of("Read"), List.of("att-1"));

        assertThat(boundary.subtype()).isEqualTo(SUBTYPE_MICRO);
        assertThat(boundary.content()).isEqualTo("Context microcompacted");
        assertThat(boundary.isMeta()).isFalse();
        assertThat(boundary.compactMetadata()).isNull();

        MicrocompactMetadata micro = boundary.microcompactMetadata();
        assertThat(micro).isNotNull();
        assertThat(micro.trigger()).isEqualTo("auto");
        assertThat(micro.preTokens()).isEqualTo(500);
        assertThat(micro.tokensSaved()).isEqualTo(200);
        assertThat(micro.compactedToolIds()).containsExactly("Read");
        assertThat(micro.clearedAttachmentUUIDs()).containsExactly("att-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · 序列化单测断言 subtype（compact_boundary / microcompact_boundary）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("序列化: ChatMessageDto 携带 subtype=compact_boundary (OD-18 单一表示)")
    void boundaryChatMessageDtoSerializesSubtype() throws Exception {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, null, null, null);
        ChatMessageDto dto = boundary.toChatMessageDto();

        // 读侧判别依据是 subtype，而不是文本前缀（D-23 删除后 content 为 CC 常量文本）
        assertThat(dto.subtype()).isEqualTo(SUBTYPE_COMPACT);
        assertThat(dto.content()).isEqualTo("Conversation compacted");
        assertThat(dto.role()).isEqualTo(Role.system);

        // JSON 序列化后必须含 subtype 字段（前端/SDK 依据 subtype 渲染边界）
        String json = MAPPER.writeValueAsString(dto);
        assertThat(json).contains("\"subtype\":\"" + SUBTYPE_COMPACT + "\"");
        // 文本前缀表示已删除（INV-4）
        assertThat(json).doesNotContain("[Compact boundary:");
    }

    @Test
    @DisplayName("序列化: microcompact_boundary 的 toChatMessageDto 携带 microcompact subtype")
    void microcompactBoundarySerializesSubtype() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createMicrocompactBoundaryMessage(
            "auto", 500, 200, List.of("Read"), List.of());
        ChatMessageDto dto = boundary.toChatMessageDto();

        assertThat(dto.subtype()).isEqualTo(SUBTYPE_MICRO);
        assertThat(dto.content()).isEqualTo("Context microcompacted");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 + 4 · 读侧单测：findLastCompactBoundaryIndex + getMessagesAfterCompactBoundary
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读侧: findLastCompactBoundaryIndex 取最后一个 boundary（无边界返回 -1）(messages.ts:4618)")
    void findLastCompactBoundaryIndex() {
        List<ChatMessageDto> messages = List.of(
            userMsg("u1", "之前消息"),
            boundaryMsg("parent-1"),
            userMsg("u2", "中间消息"),
            boundaryMsg("parent-2"),
            userMsg("u3", "最近消息"));

        assertThat(BoundaryReader.findLastCompactBoundaryIndex(messages)).isEqualTo(3);
        assertThat(BoundaryReader.findLastCompactBoundaryIndex(List.of())).isEqualTo(-1);
        assertThat(BoundaryReader.findLastCompactBoundaryIndex(
            List.of(userMsg("a", "x"), userMsg("b", "y")))).isEqualTo(-1);
    }

    @Test
    @DisplayName("读侧: getMessagesAfterCompactBoundary 从最后一个 boundary（含）向后切片 (REQ-29)")
    void getMessagesAfterCompactBoundaryIncludesLastBoundary() {
        List<ChatMessageDto> messages = List.of(
            userMsg("u1", "pre-boundary"),
            boundaryMsg("parent-1"),
            userMsg("u2", "post-first-boundary"),
            boundaryMsg("parent-2"),
            userMsg("u3", "最新"));

        List<ChatMessageDto> sliced = BoundaryReader.getMessagesAfterCompactBoundary(messages);

        assertThat(sliced).hasSize(2);
        assertThat(sliced.get(0).content()).isEqualTo("Conversation compacted"); // 含最后一个 boundary
        assertThat(sliced.get(1).content()).isEqualTo("最新");
    }

    @Test
    @DisplayName("读侧: 无 boundary 返回全量 (REQ-29 / messages.ts:4647)")
    void getMessagesAfterCompactBoundaryNoBoundaryReturnsAll() {
        List<ChatMessageDto> messages = List.of(
            userMsg("u1", "a"), userMsg("u2", "b"), userMsg("u3", "c"));

        assertThat(BoundaryReader.getMessagesAfterCompactBoundary(messages))
            .isEqualTo(messages);
    }

    @Test
    @DisplayName("读侧: isCompactBoundaryMessage 仅按 subtype 判别，不误判普通 system 消息 (messages.ts:4608)")
    void isCompactBoundaryMessageBySubtypeOnly() {
        assertThat(BoundaryReader.isCompactBoundaryMessage(boundaryMsg("p"))).isTrue();
        // 普通 system 消息（subtype 为 null / informational）不是边界
        ChatMessageDto informational = new ChatMessageDto(
            "id", null, Role.system, "system", "系统提示", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, true, false, null);
        assertThat(BoundaryReader.isCompactBoundaryMessage(informational)).isFalse();
        // 非 system 消息不是边界
        assertThat(BoundaryReader.isCompactBoundaryMessage(userMsg("u", "hi"))).isFalse();
        assertThat(BoundaryReader.isCompactBoundaryMessage(null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-14 · 验收 5 · round-trip：boundary 元数据序列化闭环（△-6/△-16）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("round-trip: compact_boundary 全元数据经 ChatMessageDto → JSON → 反序列化字段一致 (messages.ts:4530-4555)")
    void compactBoundaryMetadataRoundTrip() throws Exception {
        CompactMetadata.PreservedSegment segment = new CompactMetadata.PreservedSegment(
            "head-1", "anchor-1", "tail-1");
        CompactMetadata meta = new CompactMetadata(
            "auto", 12345, "用户上下文", 42,
            List.of("Read", "ToolSearch"), segment);
        CompactBoundaryMessage boundary = new CompactBoundaryMessage(
            CompactBoundaryMessage.SUBTYPE_COMPACT_BOUNDARY,
            CompactBoundaryMessage.CONTENT_COMPACTED,
            false,
            OffsetDateTime.now(),
            "uuid-1",
            CompactBoundaryMessage.LEVEL_INFO,
            meta,
            null,
            "parent-uuid-1");

        ChatMessageDto dto = boundary.toChatMessageDto();
        // DTO 层携带全元数据（△-6/△-16 断头修复：不再只 content/subtype/isMeta/timestamp）
        assertThat(dto.logicalParentUuid()).isEqualTo("parent-uuid-1");
        assertThat(dto.compactMetadata()).isNotNull();
        assertThat(dto.compactMetadata().get("trigger")).isEqualTo("auto");
        assertThat(dto.compactMetadata().get("preTokens")).isEqualTo(12345);
        assertThat(dto.compactMetadata().get("userContext")).isEqualTo("用户上下文");
        assertThat(dto.compactMetadata().get("messagesSummarized")).isEqualTo(42);
        assertThat(dto.compactMetadata().get("preCompactDiscoveredTools"))
            .asList().containsExactly("Read", "ToolSearch");
        assertThat(dto.compactMetadata().get("preservedSegment"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("headUuid", "head-1")
            .containsEntry("anchorUuid", "anchor-1")
            .containsEntry("tailUuid", "tail-1");
        // microcompact_boundary 场景下 microcompactMetadata=null（CC 契约红线）
        assertThat(dto.microcompactMetadata()).isNull();

        // JSON 序列化（SDK/DB 通道）→ 反序列化（加载域读回）→ 字段一致
        String json = MAPPER.writeValueAsString(dto);
        assertThat(json).contains("\"compactMetadata\"");
        assertThat(json).contains("\"logicalParentUuid\":\"parent-uuid-1\"");
        ChatMessageDto restored = MAPPER.readValue(json, ChatMessageDto.class);
        assertThat(restored.logicalParentUuid()).isEqualTo("parent-uuid-1");
        assertThat(restored.subtype()).isEqualTo(SUBTYPE_COMPACT);
        assertThat(restored.compactMetadata()).isNotNull();
        assertThat(restored.compactMetadata().get("trigger")).isEqualTo("auto");
        assertThat(restored.compactMetadata().get("preTokens")).isEqualTo(12345);
        assertThat(restored.compactMetadata().get("userContext")).isEqualTo("用户上下文");
        assertThat(restored.compactMetadata().get("messagesSummarized")).isEqualTo(42);
        assertThat(restored.compactMetadata().get("preCompactDiscoveredTools"))
            .asList().containsExactly("Read", "ToolSearch");
        assertThat(restored.compactMetadata().get("preservedSegment"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("headUuid", "head-1")
            .containsEntry("anchorUuid", "anchor-1")
            .containsEntry("tailUuid", "tail-1");
    }

    @Test
    @DisplayName("round-trip: microcompact_boundary 元数据经 ChatMessageDto → JSON → 反序列化一致 (messages.ts:4557-4575)")
    void microcompactBoundaryMetadataRoundTrip() throws Exception {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createMicrocompactBoundaryMessage(
            "auto", 500, 200, List.of("Read"), List.of("att-1"));

        ChatMessageDto dto = boundary.toChatMessageDto();
        assertThat(dto.microcompactMetadata()).isNotNull();
        assertThat(dto.microcompactMetadata().get("trigger")).isEqualTo("auto");
        assertThat(dto.microcompactMetadata().get("preTokens")).isEqualTo(500);
        assertThat(dto.microcompactMetadata().get("tokensSaved")).isEqualTo(200);
        assertThat(dto.microcompactMetadata().get("compactedToolIds"))
            .asList().containsExactly("Read");
        assertThat(dto.microcompactMetadata().get("clearedAttachmentUUIDs"))
            .asList().containsExactly("att-1");
        // compact_boundary 场景下 compactMetadata=null
        assertThat(dto.compactMetadata()).isNull();
        assertThat(dto.logicalParentUuid()).isNull();

        String json = MAPPER.writeValueAsString(dto);
        ChatMessageDto restored = MAPPER.readValue(json, ChatMessageDto.class);
        assertThat(restored.subtype()).isEqualTo(SUBTYPE_MICRO);
        assertThat(restored.microcompactMetadata()).isNotNull();
        assertThat(restored.microcompactMetadata().get("tokensSaved")).isEqualTo(200);
        assertThat(restored.microcompactMetadata().get("compactedToolIds"))
            .asList().containsExactly("Read");
    }

    @Test
    @DisplayName("boundary 无元数据/无 logicalParentUuid 时 DTO 对应字段为 null（CC 可选字段 undefined 语义）")
    void boundaryOptionalMetadataNullInDto() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "manual", 100, null, null, null);
        ChatMessageDto dto = boundary.toChatMessageDto();
        assertThat(dto.logicalParentUuid()).isNull();
        assertThat(dto.compactMetadata()).isNotNull();   // compactMetadata 对象恒存在（trigger/preTokens 必有）
        assertThat(dto.compactMetadata().get("trigger")).isEqualTo("manual");
        assertThat(dto.compactMetadata().get("preTokens")).isEqualTo(100);
        // 可选字段 undefined → 不输出（JS JSON 序列化丢 undefined，Java 等价不 put）
        assertThat(dto.compactMetadata()).doesNotContainKey("userContext");
        assertThat(dto.compactMetadata()).doesNotContainKey("messagesSummarized");
        assertThat(dto.compactMetadata()).doesNotContainKey("preCompactDiscoveredTools");
        assertThat(dto.compactMetadata()).doesNotContainKey("preservedSegment");
    }

    // ── 测试辅助 ────────────────────────────────────────────────────────

    private static ChatMessageDto userMsg(String id, String content) {
        return new ChatMessageDto(id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
    }

    private static ChatMessageDto boundaryMsg(String logicalParentUuid) {
        return CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, logicalParentUuid, null, null).toChatMessageDto();
    }
}
