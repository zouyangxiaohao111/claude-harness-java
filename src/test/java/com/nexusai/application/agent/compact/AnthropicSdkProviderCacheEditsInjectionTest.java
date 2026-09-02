package com.nexusai.application.agent.compact;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.compact.MicroCompactResult.CacheEditsBlock;
import com.nexusai.application.agent.compact.MicroCompactResult.PinnedCacheEdits;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [cached-MC 注入] AnthropicSdkProvider.buildMessageParams 真正把 cache_edits 块注入请求体
 * （解法 A：JsonValue._json 反射构造 ContentBlockParam）· 对齐 CC addCacheBreakpoints
 * （Open-ClaudeCode/src/services/api/claude.ts:3112-3162）+ insertBlockAfterToolResults
 * （Open-ClaudeCode/src/utils/contentArray.ts:21-51）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: cached-MC 的删除旧工具结果依赖服务端 cache_edits 原样下发——
 * Java 侧 MicroCompactor 已实现状态机 + consumePendingCacheEditsBlock()/pinCacheEdits()/
 * getPinnedCacheEdits()，但 provider 请求构造点此前只 consume 不注入（warn 暴露）。本测试验证
 * 注入闭环：新块插入位置（最后 tool_result 后 / 无 tool_result 末块前）+ `.` 延续 + pin +
 * 下请求 pinned 重插 + 跨块去重，且序列化后请求体 JSON 确实承载 cache_edits。
 */
class AnthropicSdkProviderCacheEditsInjectionTest {

    private static final String MODEL = "claude-opus-4-1";

    @BeforeEach
    void setUp() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.resetMicrocompactState();
    }

    @AfterEach
    void tearDown() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.resetMicrocompactState();
    }

    @Test
    @DisplayName("注入: 新 cache_edits 块落在最后 user 消息最后一个 tool_result 后，末位追加 '.' 延续块（CC claude.ts:3141-3157 + contentArray.ts:21-51）")
    void injectCacheEdits_afterLastToolResult_appendDotContinuation() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> history = buildInjectionHistory(); // 末条 user 消息 = tool_result(t2)
        enqueueCacheEditsBlock(history);

        MessageCreateParams params = buildParams(history);

        // 最后 user 消息 = tool_result(t2) → cache_edits 应插在 tool_result 后、'.' 前
        MessageParam lastUser = params.messages().get(lastUserMessageIndex(params));
        List<ContentBlockParam> blocks = lastUser.content().asBlockParams();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).isToolResult()).as("第 0 块保持原 tool_result").isTrue();
        assertThat(isCacheEditsBlock(blocks.get(1))).as("第 1 块 = cache_edits（tool_result 后）").isTrue();
        assertThat(blocks.get(2).isText()).as("第 2 块 = text '.' 延续块（contentArray.ts:42-45）").isTrue();
        assertThat(blocks.get(2).asText().text()).isEqualTo(".");

        // pin 已生效：pinnedEdits 含该位置块
        List<PinnedCacheEdits> pinned = MicroCompactor.getPinnedCacheEdits();
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).userMessageIndex()).isEqualTo(lastUserMessageIndex(params));
        assertThat(pinned.get(0).block().edits()).isNotEmpty();

        // 序列化请求体确实承载 cache_edits（_json 通道原样输出）
        String body = serialize(lastUser);
        assertThat(body).contains("\"cache_edits\"").contains("\"delete_tool_result\"")
            .contains("\"tool_use_id\"").contains("\".\"");
    }

    @Test
    @DisplayName("注入: 无 tool_result 的 user 消息 → cache_edits 插在末块前（CC contentArray.ts:47-50）；String content 先转单 text block（claude.ts:3148-3149）")
    void injectCacheEdits_noToolResult_insertsBeforeLastBlock() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        // 末条 user 消息为纯文本（无 tool_result），前一条 user 亦纯文本——便于独立断言 String→[text] 转换
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsg("question-1"));
        history.add(userMsg("question-2"));
        enqueueCacheEditsBlock(history);

        MessageCreateParams params = buildParams(history);

        MessageParam lastUser = params.messages().get(lastUserMessageIndex(params));
        List<ContentBlockParam> blocks = lastUser.content().asBlockParams();
        assertThat(blocks).hasSize(2);
        assertThat(isCacheEditsBlock(blocks.get(0))).as("cache_edits 插在末块前（contentArray.ts:47-50）").isTrue();
        assertThat(blocks.get(1).isText()).as("原 text 块保留（String → 单 text block，claude.ts:3148-3149）").isTrue();
    }

    @Test
    @DisplayName("pinned 重插: 第二次请求无新块时在原始 user 消息位置重发已钉住块（CC claude.ts:3127-3140）")
    void pinnedCacheEdits_reinsertedOnNextRequest() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> history = buildInjectionHistory();

        // 第一次请求：注入 + pin
        enqueueCacheEditsBlock(history);
        MessageCreateParams first = buildParams(history);
        int pinnedIdx = lastUserMessageIndex(first);
        assertThat(MicroCompactor.getPinnedCacheEdits()).hasSize(1);

        // 第二次请求：不 enqueue 新块 → 仅重插 pinned
        MessageCreateParams second = buildParams(history);
        List<ContentBlockParam> blocks = second.messages().get(pinnedIdx).content().asBlockParams();
        assertThat(countCacheEditsBlocks(blocks)).as("pinned 块重插到原始位置（claude.ts:3127-3140）").isEqualTo(1);
        assertThat(isCacheEditsBlock(blocks.get(1))).isTrue();
    }

    @Test
    @DisplayName("跨块去重: 第二次请求新块与 pinned 块同 tool_use_id → 新块被去重不重复插入（CC claude.ts:3112-3125/3143）")
    void pinnedAndNewDeduped_noDuplicateBlocks() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> history = buildInjectionHistory();

        // 第一次请求：注入 + pin（8 个删除，tool ids t0..t7）
        enqueueCacheEditsBlock(history);
        buildParams(history);
        assertThat(MicroCompactor.getPinnedCacheEdits()).hasSize(1);

        // 第二次请求：enqueue 相同删除集 → 新块与 pinned 完全去重 → 仅 pinned 重插，无重复块
        enqueueCacheEditsBlock(history);
        MessageCreateParams second = buildParams(history);
        MessageParam lastUser = second.messages().get(lastUserMessageIndex(second));
        List<ContentBlockParam> blocks = lastUser.content().asBlockParams();
        assertThat(countCacheEditsBlocks(blocks)).as("同 tool_use_id 不重复删除（seenDeleteRefs 跨块去重）").isEqualTo(1);
    }

    @Test
    @DisplayName("门控: cached-MC 门关 → 不 consume、不注入（零行为变化）")
    void gateOff_noConsumeNoInject() {
        // setCachedMicrocompactEnabled(false) 由 setUp 保证
        List<ChatMessageDto> history = buildInjectionHistory();
        MicroCompactor.resetMicrocompactState();
        // 通过完整路径尝试入队（门关 → cached 路径不触发 → 块不入队）
        new MicroCompactor().microcompactMessages(history, "repl_main_thread");

        MessageCreateParams params = buildParams(history);
        assertThat(MicroCompactor.consumePendingCacheEditsBlock())
            .as("门关 → pendingCacheEditsBlock 未入队，consume 仍为 null").isNull();
        assertThat(MicroCompactor.getPinnedCacheEdits()).isEmpty();

        MessageParam lastUser = params.messages().get(lastUserMessageIndex(params));
        assertThat(countCacheEditsBlocks(lastUser.content().asBlockParams()))
            .as("门关 → 不注入 cache_edits").isZero();
    }

    @Test
    @DisplayName("cache_reference: marker 之前 user 消息 tool_result 块附 cache_reference=tool_use_id；marker 所在消息不附（CC claude.ts:3164-3208 strict before）")
    void cacheReference_addedToToolResultsStrictlyBeforeMarker() {
        MicroCompactor.setCachedMicrocompactEnabled(true); // CC useCachedMC 门开（:3108 早期 return 之前）
        List<ChatMessageDto> history = buildInjectionHistory(); // user + asst(t1) + tool(t1) + asst(t2) + tool(t2)
        MessageCreateParams params = buildParamsWithCaching(history); // enablePromptCaching=true → 门控确定
        List<MessageParam> msgs = params.messages();

        // 前置：末条消息（tool_result t2，user）承载 cache_control marker（skipCacheWrite=null → 末条，claude.ts:3089-3091）
        MessageParam last = msgs.get(msgs.size() - 1);
        assertThat(hasCacheControlMarker(last)).as("前置：末条消息 = marker 消息（claude.ts:3089-3091）").isTrue();

        // marker 之前（i < lastCCMsg）的 tool_result(t1) user 消息 → 序列化 JSON 承载 cache_reference=t1
        MessageParam before = msgs.get(2); // tool(t1) → user 消息，tool_result 块
        assertThat(before.role()).as("前置：index 2 = user 消息").isEqualTo(MessageParam.Role.USER);
        String body = serialize(before);
        assertThat(body).as("marker 前 user 消息 tool_result 附 cache_reference=t1（CC claude.ts:3201-3203 Object.assign）")
            .contains("\"tool_use_id\":\"t1\"").contains("\"cache_reference\":\"t1\"");

        // marker 所在消息（i == lastCCMsg）不附 cache_reference（严格 before，CC :3187）
        assertThat(serialize(last)).as("marker 所在消息不附 cache_reference（严格 before，CC :3187）")
            .doesNotContain("cache_reference");
    }

    // ─────────── helpers ───────────

    /** 走完整 cached-MC 路径入队一个 pendingCacheEditsBlock（门已开）。 */
    private static void enqueueCacheEditsBlock(List<ChatMessageDto> history) {
        // 13 个可压缩工具 → active(13) > triggerThreshold(10) → cached 路径删除 → 入队 block（8 个删除）
        List<ChatMessageDto> trigger = buildThirteenToolMessages();
        new MicroCompactor().microcompactMessages(trigger, "repl_main_thread");
        CacheEditsBlock queued = MicroCompactor.consumePendingCacheEditsBlock();
        assertThat(queued).as("前置：cached 路径触发删除 → pendingCacheEditsBlock 已入队").isNotNull();
        // 重新触发入队，供 buildMessageParams 消费
        new MicroCompactor().microcompactMessages(trigger, "repl_main_thread");
    }

    private static MessageCreateParams buildParams(List<ChatMessageDto> history) {
        return AnthropicSdkProvider.buildMessageParams(
            MODEL, null, history, null, null, null, null, null, null);
    }

    /** 显式 enablePromptCaching=true（11 参重载）· 使 caching 门控确定，不受 env DISABLE_PROMPT_CACHING 影响。 */
    private static MessageCreateParams buildParamsWithCaching(List<ChatMessageDto> history) {
        return AnthropicSdkProvider.buildMessageParams(
            MODEL, null, history, null, null, null, null, null, null, true, false);
    }

    /** 消息 content 任一 block 承载 cache_control marker（CC claude.ts:3168-3178 'cache_control' in block 等价）。 */
    private static boolean hasCacheControlMarker(MessageParam msg) {
        if (!msg.content().isBlockParams()) {
            return false;
        }
        for (ContentBlockParam block : msg.content().asBlockParams()) {
            if (block != null && block.cacheControl() != null && block.cacheControl().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** 注入测试历史：末条 user 消息承载 tool_result(t2)。 */
    private static List<ChatMessageDto> buildInjectionHistory() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(userMsg("question"));
        list.add(assistantWithToolCall("a1", "Bash", "t1"));
        list.add(toolMsg("tool-1", "result-1", "t1"));
        list.add(assistantWithToolCall("a2", "Bash", "t2"));
        list.add(toolMsg("tool-2", "result-2", "t2"));
        return list;
    }

    /** 13 对 assistant(tool_use) + tool(tool_result) 消息 · 与 CachedMcBoundaryWiringCcTest 同构。 */
    private static List<ChatMessageDto> buildThirteenToolMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            list.add(assistantWithToolCall("asst-" + i, "Bash", "t" + i));
            list.add(toolMsg("tool-" + i, "result content", "t" + i));
        }
        return list;
    }

    private static int lastUserMessageIndex(MessageCreateParams params) {
        List<MessageParam> msgs = params.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role() == MessageParam.Role.USER) {
                return i;
            }
        }
        throw new IllegalStateException("无 user 消息");
    }

    private static boolean isCacheEditsBlock(ContentBlockParam block) {
        if (!block._json().isPresent()) {
            return false;
        }
        JsonValue json = block._json().get();
        JsonNode node = json.convert(JsonNode.class);
        return node != null && node.isObject() && "cache_edits".equals(node.path("type").asText());
    }

    private static int countCacheEditsBlocks(List<ContentBlockParam> blocks) {
        int count = 0;
        for (ContentBlockParam b : blocks) {
            if (isCacheEditsBlock(b)) {
                count++;
            }
        }
        return count;
    }

    private static String serialize(MessageParam param) {
        try {
            // MessageParam 经 SDK ObjectMappers 序列化（_content/_role 字段通道）→ 请求体 messages[i] JSON
            return ObjectMappers.jsonMapper().writeValueAsString(param);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    private static ChatMessageDto assistantWithToolCall(String id, String name, String toolCallId) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", "thinking", null,
            List.of(new ToolCallDto(toolCallId, name, "{}", null, false)),
            com.nexusai.model.session.dto.FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto toolMsg(String id, String content, String toolCallId) {
        return new ChatMessageDto(
            id, null, Role.tool, "tool", content, null,
            List.of(), com.nexusai.model.session.dto.FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            toolCallId, null, null, List.of(), List.of());
    }
}
