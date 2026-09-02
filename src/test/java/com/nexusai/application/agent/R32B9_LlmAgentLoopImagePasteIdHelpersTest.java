package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9 + R32-b9-fix · LlmAgentLoop 内 imagePasteId 助手函数 + toolResultMessage 4-参重载.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:252-262} getNextImagePasteId +
 * {@code toolExecution.ts:1410-1467} addToolResult 的 image block 计数与 sequence 生成逻辑.
 * 这三个静态助手 + toolResultMessage 重载都是 LlmAgentLoop 私有实现(避免 Spring DI 风险),
 * 测试通过反射访问. 验证:
 * <ul>
 *   <li>{@code countImageBlocks}: 统计 contentBlocks 中 type=image 块的数量(0/1/混合正确)</li>
 *   <li>{@code generateImagePasteIds}: 起始 ID + 个数 → 严格递增 [baseId, baseId+1, ...]</li>
 *   <li>{@code computeNextImagePasteId} [fix P1-1]: 扫所有 role 累计 maxId+1(不再仅 Role.user;
 *       tool/assistant 含 imagePasteIds 同样参与)</li>
 *   <li>{@code computeNextImagePasteId}: 跳过 null/畸形 ID(非数字 ID 不计入)</li>
 *   <li>{@code toolResultMessage} 4-参重载 [fix P2-2 验证]: acceptFeedback 不再字符串拼接到
 *       content (Fix E),而是独立字段透传 + contentBlocks + imagePasteIds</li>
 * </ul>
 *
 * <p>WHY 反射访问: 这些是 LlmAgentLoop 私有静态 helper (package-private 升级会污染 public API),
 * 通过反射保持封装性 (CLAUDE.md 规则 11 代码库既有规范 - 不为单元测试暴露生产 API).
 *
 * @see com.nexusai.application.agent.LlmAgentLoop
 */
class R32B9_LlmAgentLoopImagePasteIdHelpersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────── reflection helpers ───────────

    private static Method method(String name, Class<?>... params) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("LlmAgentLoop helper not found: " + name, e);
        }
    }

    private static int invokeCountImageBlocks(List<JsonNode> blocks) {
        try {
            return (int) method("countImageBlocks", List.class).invoke(null, blocks);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> invokeGenerateImagePasteIds(int baseId, int imageCount) {
        try {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method("generateImagePasteIds", int.class, int.class)
                .invoke(null, baseId, imageCount);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeComputeNextImagePasteId(List<ChatMessageDto> messages) {
        try {
            return (int) method("computeNextImagePasteId", List.class).invoke(null, messages);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * [R32-b9-fix] P2-2 helper 测试修订:实际调用 4 参 toolResultMessage (不再是 3 参 1 参)。
     * WHY: 原 helper 测试只调 1 参 toolResultMessage,4 参工厂路径的 acceptFeedback/contentBlocks/
     * imagePasteIds 注入行为从未被覆盖。本测试用反射调 4 参重载,验证 Fix E 后的结构化注入。
     */
    private static ChatMessageDto invokeToolResultMessage4(ToolResult result,
                                                           String acceptFeedback,
                                                           List<JsonNode> contentBlocks,
                                                           List<String> imagePasteIds) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod(
                "toolResultMessage", ToolResult.class, String.class, List.class, List.class);
            m.setAccessible(true);
            return (ChatMessageDto) m.invoke(null, result, acceptFeedback, contentBlocks, imagePasteIds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─────────── countImageBlocks ───────────

    @Test
    @DisplayName("countImageBlocks: 空 list → 0")
    void countBlocksEmpty() {
        assertThat(invokeCountImageBlocks(null)).isZero();
        assertThat(invokeCountImageBlocks(new ArrayList<>())).isZero();
    }

    @Test
    @DisplayName("countImageBlocks: 1 image + 1 text → 1 (仅数 image)")
    void countBlocksMixed() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{}}"));
        blocks.add(JSON.readTree("{\"type\":\"text\",\"text\":\"hello\"}"));
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/y.png\"}}"));
        assertThat(invokeCountImageBlocks(blocks)).isEqualTo(2);
    }

    // ─────────── generateImagePasteIds ───────────

    @Test
    @DisplayName("generateImagePasteIds: 起始 5 + 数量 3 → [5,6,7] 严格递增")
    void generateIdsMonotonic() {
        assertThat(invokeGenerateImagePasteIds(5, 3)).containsExactly("5", "6", "7");
    }

    @Test
    @DisplayName("generateImagePasteIds: 0 个 → 空 list (防御:不抛异常)")
    void generateIdsZero() {
        assertThat(invokeGenerateImagePasteIds(10, 0)).isEmpty();
    }

    // ─────────── computeNextImagePasteId [R32-b9-fix:扫描所有 Role] ───────────

    @Test
    @DisplayName("computeNextImagePasteId: 无 message → 1 (从 1 开始)")
    void computeStartAtOne() {
        assertThat(invokeComputeNextImagePasteId(new ArrayList<>())).isEqualTo(1);
    }

    @Test
    @DisplayName("[fix P1-1] computeNextImagePasteId: 跨 user + tool + assistant 累计 maxId+1")
    void computeAccumulatesAcrossAllRoles() {
        // [fix P1-1] 修复前: 只扫 Role.user → toolMsg["100"] / assistantMsg["200"] 都被跳过,
        // max=5, 返回 6。修复后: 扫所有 Role → max=200, 返回 201。
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(userMsg(List.of("1", "3")));
        msgs.add(toolMsg());
        msgs.add(assistantMsg());
        msgs.add(userMsg(List.of("5", "2")));  // 重复 / 倒序
        assertThat(invokeComputeNextImagePasteId(msgs)).isEqualTo(201);
    }

    @Test
    @DisplayName("[fix P1-1] computeNextImagePasteId: tool/assistant 独立携带 imagePasteIds 也累计")
    void computeIncludesToolAndAssistantIds() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(toolMsg());                            // ["100"]
        msgs.add(assistantMsg());                       // ["200"]
        msgs.add(toolMsgWithIds(List.of("500")));       // ["500"]
        assertThat(invokeComputeNextImagePasteId(msgs)).isEqualTo(501);
    }

    @Test
    @DisplayName("computeNextImagePasteId: 跳过 null/畸形 ID")
    void computeSkips() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(userMsg(null));                // null list 跳过
        msgs.add(userMsg(List.of("abc", "9"))); // 含畸形 ID → 只算 9
        msgs.add(toolMsgWithIds(List.of("xyz", "42")));  // 畸形 + 42
        assertThat(invokeComputeNextImagePasteId(msgs)).isEqualTo(43);
    }

    // ─────────── toolResultMessage 4-参重载 [R32-b9-fix:验证结构化注入] ───────────

    @Test
    @DisplayName("[fix P2-2 + fix E] toolResultMessage 4 参: acceptFeedback 结构化注入,不再字符串拼接")
    void toolResultMessage4ArgStructuredInjection() throws Exception {
        ToolResult<String> result = new ToolResult<>("tool executed", null, null, null);
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/y.png\"}}"));
        List<String> ids = List.of("3", "4");

        ChatMessageDto msg = invokeToolResultMessage4(result, "用户反馈: 重写", blocks, ids);

        // 角色/工具调用 ID
        assertThat(msg.role()).isEqualTo(Role.tool);
        assertThat(msg.toolCallId()).isNull();

        // [fix E] content 不再拼接 acceptFeedback (结构化字段独立)
        assertThat(msg.content()).isEqualTo("tool executed");
        // acceptFeedback 独立保留
        assertThat(msg.acceptFeedback()).isEqualTo("用户反馈: 重写");
        // contentBlocks 透传
        assertThat(msg.contentBlocks()).hasSize(1);
        // imagePasteIds 透传
        assertThat(msg.imagePasteIds()).containsExactly("3", "4");
    }

    @Test
    @DisplayName("[fix E] toolResultMessage 4 参: 空 acceptFeedback/blocks → null/空 list (结构化字段保持 null-safe)")
    void toolResultMessage4ArgEmptyArgs() {
        ToolResult<String> result = new ToolResult<>("result only", null, null, null);
        ChatMessageDto msg = invokeToolResultMessage4(result, null, null, null);

        assertThat(msg.content()).isEqualTo("result only");
        assertThat(msg.acceptFeedback()).isNull();
        assertThat(msg.contentBlocks()).isNull();
        assertThat(msg.imagePasteIds()).isNull();
    }

    // ─────────── helpers ───────────

    private static ChatMessageDto userMsg(List<String> ids) {
        return new ChatMessageDto("u", null, Role.user, "user", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), ids == null ? List.of() : ids);
    }

    private static ChatMessageDto toolMsg() {
        return new ChatMessageDto("t", null, Role.tool, "tool", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "c1", null,
            null, List.of(), List.of("100"));
    }

    private static ChatMessageDto toolMsgWithIds(List<String> ids) {
        return new ChatMessageDto("t", null, Role.tool, "tool", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "c1", null,
            null, List.of(), ids == null ? List.of() : ids);
    }

    private static ChatMessageDto assistantMsg() {
        return new ChatMessageDto("a", null, Role.assistant, "asst", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), List.of("200"));
    }
}
