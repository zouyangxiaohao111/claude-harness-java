package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * R32-b9-fix · Phase 8 · 多 turn 连续 tool-result imagePasteId 不重复 (Fix A 关键验证).
 *
 * <p><b>WHY (意图验证)</b>: b9 reviewer P1-1 严重功能缺陷 — {@code computeNextImagePasteId}
 * 修复前只扫 {@code Role.user},导致连续工具结果中 imagePasteId 会从 1 重新开始。
 * 真实场景:前端用 imagePasteId 渲染 image 引用(对齐 CC
 * {@code Open-ClaudeCode/src/components/Message.tsx:172-186}),若 ID 重复/冲突,
 * image 显示错乱(用户不可见但前端报告渲染错误)。
 *
 * <p>Fix A: computeNextImagePasteId 改为扫所有 Role(user + tool + assistant)。
 * 本测试模拟"用户 + 多次工具结果(image + contentBlocks)"的真实链路,
 * 验证 ID 跨 turn 全局单调递增。
 *
 * <p>本测试是任务验收关键项:
 * {@code mvn test -Dtest='R32B9_ContinuousToolResultImagePasteIdTest'} 必须 PASS。
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>Turn 1: user 消息带 2 个 image → ID = 1, 2</li>
 *   <li>Turn 2: tool result 注入 1 个 image → 下一个 ID = 3(非 1,关键修复点)</li>
 *   <li>Turn 3: assistant + tool result 各 1 个 image → ID = 4, 5</li>
 *   <li>混合 user/tool/assistant 跨 turn:ID 全局递增不重复</li>
 * </ul>
 *
 * @see com.nexusai.application.agent.LlmAgentLoop#computeNextImagePasteId
 */
class R32B9_ContinuousToolResultImagePasteIdTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────── reflection helpers ───────────

    private static int invokeComputeNextImagePasteId(List<ChatMessageDto> messages) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("computeNextImagePasteId", List.class);
            m.setAccessible(true);
            return (int) m.invoke(null, messages);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> invokeGenerateImagePasteIds(int baseId, int imageCount) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("generateImagePasteIds", int.class, int.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) m.invoke(null, baseId, imageCount);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeCountImageBlocks(List<JsonNode> blocks) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("countImageBlocks", List.class);
            m.setAccessible(true);
            return (int) m.invoke(null, blocks);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─────────── 关键 Fix A 验证 ───────────

    @Test
    @DisplayName("[Fix A 关键验证] 连续 tool-result ID 不从 1 重新开始")
    void continuousToolResultIdsDoNotReset() {
        // 模拟真实链路 ID 累计:user (1,2) → tool-result (3) → assistant → tool-result (4)
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsgWithIds(List.of("1", "2")));    // user 带 2 个 image → ID 1, 2
        history.add(toolMsgWithIds(List.of("3")));          // tool result → ID 3
        history.add(assistantMsg());                         // assistant
        history.add(toolMsgWithIds(List.of("4")));          // tool result → ID 4

        // 下一个 ID 应 = 5(全局 max + 1),不是 1(修复前 bug 行为)
        int nextId = invokeComputeNextImagePasteId(history);
        assertThat(nextId)
            .as("Fix A: 连续 tool-result ID 必须全局递增,不能从 1 重新开始")
            .isEqualTo(5);
    }

    @Test
    @DisplayName("[Fix A] user + tool + assistant 混合:nextId = max+1")
    void mixedRoleMessagesNextId() {
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsgWithIds(List.of("1", "3")));
        history.add(toolMsgWithIds(List.of("5", "7")));
        history.add(assistantMsgWithIds(List.of("9")));
        history.add(userMsgWithIds(List.of("2")));  // 顺序乱,但应取 max=9
        history.add(toolMsgWithIds(List.of("10", "11")));
        assertThat(invokeComputeNextImagePasteId(history)).isEqualTo(12);
    }

    @Test
    @DisplayName("[Fix A] 模拟真实 allow + ask 路径连续注入:imagePasteId 全局不重复")
    void realisticMultiTurnAllowAskSequence() {
        // Turn 1: user 提问 (无 image)
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsgNoImages());

        // Turn 1 reply: assistant 带 tool_calls → tool result(denied, ask 路径注入 contentBlocks)
        history.add(assistantMsgWithToolCalls());
        history.add(toolMsgWithIds(List.of("1", "2")));  // ask denied: 2 个 image → ID 1, 2

        // Turn 2: assistant 带 tool_calls → tool result(allowed, acceptFeedback + image)
        history.add(assistantMsgWithToolCalls());
        history.add(toolMsgWithIds(List.of("3")));  // allowed: 1 个 image → ID 3

        // 期望下一个 ID = 4
        assertThat(invokeComputeNextImagePasteId(history)).isEqualTo(4);

        // 模拟下一次 allow 路径:3 个 image → ID 4, 5, 6(不是 1, 2, 3)
        List<String> ids = invokeGenerateImagePasteIds(4, 3);
        assertThat(ids).containsExactly("4", "5", "6");
    }

    @Test
    @DisplayName("[Fix A] 历史只含 tool 消息 (无 user):nextId 仍正确累计")
    void toolOnlyHistoryNextId() {
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(toolMsgWithIds(List.of("100", "200", "50")));
        history.add(toolMsgWithIds(List.of("150")));
        assertThat(invokeComputeNextImagePasteId(history)).isEqualTo(201);
    }

    @Test
    @DisplayName("修复前(buggy 行为): 只看 user 时 ID 重复 — 已被覆盖,确保不再回退")
    void regressionGuardUserOnlyScanReverted() {
        // 这条断言模拟"bug 行为":仅看 user 会得到 nextId=1(错误),实际修复后 nextId=201
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsgNoImages());                      // user (无 image)
        history.add(toolMsgWithIds(List.of("200")));        // tool 带 ID=200
        // 修复后:扫所有 Role → max=200 → nextId=201
        assertThat(invokeComputeNextImagePasteId(history))
            .as("回归保护:不再退回到 '仅 Role.user' 扫描")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("countImageBlocks 配合 computeNextImagePasteId:连续注入 ID 不漏不缺")
    void fullPipelineIntegration() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{}}"));
        blocks.add(JSON.readTree("{\"type\":\"text\",\"text\":\"附注\"}"));
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/c.png\"}}"));

        // imageCount = 2(跳过 text)
        int imageCount = invokeCountImageBlocks(blocks);
        assertThat(imageCount).isEqualTo(2);

        // 历史已有 ID = 1, 2 → nextId = 3, 注入 [3, 4]
        List<ChatMessageDto> history = new ArrayList<>();
        history.add(userMsgWithIds(List.of("1", "2")));
        history.add(toolMsgWithIds(List.of("3", "4")));   // 关键:tool 也累计

        int nextId = invokeComputeNextImagePasteId(history);
        assertThat(nextId).isEqualTo(5);
        assertThat(invokeGenerateImagePasteIds(nextId, imageCount)).containsExactly("5", "6");
    }

    // ─────────── helpers ───────────

    private static ChatMessageDto userMsgWithIds(List<String> ids) {
        return new ChatMessageDto("u", null, Role.user, "user", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), ids);
    }

    private static ChatMessageDto toolMsgWithIds(List<String> ids) {
        return new ChatMessageDto("t", null, Role.tool, "tool", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "call-1", null,
            null, List.of(), ids);
    }

    private static ChatMessageDto assistantMsgWithIds(List<String> ids) {
        return new ChatMessageDto("a", null, Role.assistant, "asst", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), ids);
    }

    private static ChatMessageDto assistantMsg() {
        return new ChatMessageDto("a", null, Role.assistant, "asst", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), List.of());
    }

    private static ChatMessageDto userMsgNoImages() {
        return new ChatMessageDto("u", null, Role.user, "user", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), List.of());
    }

    private static ChatMessageDto assistantMsgWithToolCalls() {
        // 为简洁,toolCalls 用空 list(只要 role=assistant 即可)
        return new ChatMessageDto("a", null, Role.assistant, "asst", "x",
            null, List.of(), null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), List.of());
    }
}