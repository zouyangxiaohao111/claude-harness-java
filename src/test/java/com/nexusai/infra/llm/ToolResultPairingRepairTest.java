package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1] 发送边界 tool_use / tool_result 配对修复单测 · CC original: {@code ensureToolResultPairing}
 * （{@code claude-code-best/src/utils/messages.ts:5594-5951}）。
 *
 * <p><b>WHY（测试验证意图 · CLAUDE.md 规则九）</b>：fork 链路（SM 提取 / extract_memories / auto_dream /
 * partial compact）反复 400 —
 * {@code An assistant message with 'tool_calls' must be followed by tool messages responding to each
 * 'tool_call_id'}。两类断头：① 上下文尾部是刚采样的 assistant(tool_calls)、其工具结果尚未产生
 * （工具在 {@code handleToolCallsTurn} 才跑）；② fork 自执行工具的结果消息 {@code toolCallId} 恒 null
 * → provider 序列化侧丢弃。本测试锁定「发送前补齐配对」而非「过滤删除」——因为 fork 的尾部悬挂
 * tool_use 是<b>正常中间态</b>（tools 随后才跑），删掉会丢真实上下文。
 *
 * <p>覆盖 CC 的五个边界：① tool_use 无结果 ② tool 结果无前置 tool_use ③ 一次多个悬挂
 * ④ 连续多条 assistant ⑤ 空内容 / 已配对不动。
 *
 * <p><b>变异自证</b>：去掉调用点修复（provider 三处 {@code ensureToolResultPairing} 调用回退为直传）
 * → {@link ToolResultPairingSendBoundaryTest} 的真机断言红（assistant.tool_calls 无对应 tool 消息）；
 * 本类中「已配对 → {@code isSameAs} 引用同一」与「悬挂 → 合成结果」亦红。
 */
class ToolResultPairingRepairTest {

    // ─────────── 构造助手 ───────────

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto system(String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.system, "system", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto assistant(String text, ToolCallDto... calls) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant", text,
            null, List.of(calls), null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, "tool", content,
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, null,
            null, null, null, null, false, false);
    }

    private static ToolCallDto call(String id) {
        return new ToolCallDto(id, "Bash", "{}", null, null);
    }

    private static List<Role> roles(List<ChatMessageDto> msgs) {
        List<Role> out = new ArrayList<>();
        for (ChatMessageDto m : msgs) {
            out.add(m.role());
        }
        return out;
    }

    private static String contentOf(List<ChatMessageDto> msgs, int idx) {
        return msgs.get(idx).content();
    }

    // ─────────── 边界① tool_use 无结果（尾部悬挂 · fork 主症状） ───────────

    @Test
    @DisplayName("边界①-1 尾部悬挂单个 tool_use → 补合成 tool 结果（is_error=true / isMeta / 占位文本 / id 对齐）")
    void danglingToolUse_tail_isRepairedWithSyntheticErrorResult() {
        ChatMessageDto asst = assistant("", call("toolu_A"));
        List<ChatMessageDto> in = List.of(user("hi"), asst);

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(3);
        assertThat(roles(out)).containsExactly(Role.user, Role.assistant, Role.tool);
        ChatMessageDto synthetic = out.get(2);
        assertThat(synthetic.toolCallId()).isEqualTo("toolu_A");
        assertThat(synthetic.content()).isEqualTo(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
        // CC original: syntheticBlocks = {type:'tool_result', tool_use_id, content: placeholder, is_error: true}
        // （messages.ts:5796-5801）—— 合成结果必须是「错误」语义，否则模型会把占位当真实工具输出
        assertThat(synthetic.isError()).isTrue();
        assertThat(synthetic.isMeta()).isTrue();
        assertThat(synthetic.assistantMessageId()).isEqualTo(asst.id());
        // 悬挂的 assistant 本体不得被改动（不删、不改）
        assertThat(out.get(1)).isSameAs(asst);
    }

    // ─────────── 边界③ 一次多个悬挂 ───────────

    @Test
    @DisplayName("边界③ 一次 3 个悬挂 → 逐个补齐，顺序 = assistant tool_calls 顺序")
    void multipleDanglingToolUses_synthesizedInToolCallOrder() {
        List<ChatMessageDto> in = List.of(
            user("go"),
            assistant("", call("toolu_A"), call("toolu_B"), call("toolu_C")));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(5);
        assertThat(out.get(2).toolCallId()).isEqualTo("toolu_A");
        assertThat(out.get(3).toolCallId()).isEqualTo("toolu_B");
        assertThat(out.get(4).toolCallId()).isEqualTo("toolu_C");
        assertThat(out).allSatisfy(m -> {
            if (m.role() == Role.tool) {
                assertThat(m.isError()).isTrue();
            }
        });
    }

    @Test
    @DisplayName("悬挂 + 其余已配对（混合）→ 合成结果前置，真实结果保留（CC :5836 [...synthetic, ...content]）")
    void partiallyPaired_missingPrependedBeforeRealResults() {
        List<ChatMessageDto> in = List.of(
            user("go"),
            assistant("", call("toolu_A"), call("toolu_B")),
            toolResult("toolu_B", "real-B"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(4);
        assertThat(out.get(2).toolCallId()).isEqualTo("toolu_A");
        assertThat(out.get(2).content()).isEqualTo(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
        assertThat(out.get(3)).isSameAs(in.get(2));
        assertThat(contentOf(out, 3)).isEqualTo("real-B");
    }

    // ─────────── 边界⑤ 已配对不动（幂等 · 前缀缓存） ───────────

    @Test
    @DisplayName("边界⑤-1 已完整配对 → 返回入参本体（同一引用 · 出站字节 100% 不变）")
    void fullyPaired_returnsSameListInstance_noByteChange() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A")),
            toolResult("toolu_A", "ok"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).isSameAs(in);
    }

    @Test
    @DisplayName("边界⑤-2 幂等：对已修复结果再跑一次 → 零改动（第二次 sameAs）")
    void repairIsIdempotent() {
        List<ChatMessageDto> in = List.of(user("hi"), assistant("", call("toolu_A")));

        List<ChatMessageDto> once = ToolResultPairingRepair.ensureToolResultPairing(in);
        List<ChatMessageDto> twice = ToolResultPairingRepair.ensureToolResultPairing(once);

        assertThat(once).hasSize(3);
        assertThat(twice).isSameAs(once);
    }

    @Test
    @DisplayName("边界⑤-3 system 消息对配对透明（CC normalize 已先过滤 system）→ 不误判悬挂、引用同一")
    void systemMessageIsTransparentToPairing() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A")),
            system("Switched to ... due to high demand"),
            toolResult("toolu_A", "ok"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        // 若不透明：assistant 的后继是 system → 误判悬挂（补假占位）+ 真实结果被当孤儿丢弃
        assertThat(out).isSameAs(in);
    }

    @Test
    @DisplayName("边界⑤-4 assistant 的 tool_call 无效（无 id）且 content 空白 → 剥空后补 [Tool use interrupted]")
    void emptiedAssistantContent_getsInterruptedPlaceholder() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", new ToolCallDto(null, "Bash", "{}", null, null)));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(2);
        assertThat(out.get(1).content()).isEqualTo(ToolResultPairingRepair.TOOL_USE_INTERRUPTED_PLACEHOLDER);
        assertThat(out.get(1).toolCalls()).isEmpty();
    }

    // ─────────── 边界② tool 结果无前置 tool_use（反向） ───────────

    @Test
    @DisplayName("边界②-1 孤立 tool 结果（前面是 user）→ 丢弃（CC 剥孤儿 tool_result 块等价）")
    void orphanToolResult_afterUser_isStripped() {
        List<ChatMessageDto> in = List.of(user("hi"), toolResult("toolu_ghost", "orphan"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.user);
    }

    @Test
    @DisplayName("边界②-2 孤立 tool 结果在 assistant 区段内（id 不匹配）→ 丢弃，同区段真实结果保留")
    void orphanWithinAssistantRegion_isStripped() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A")),
            toolResult("toolu_ghost", "orphan"),
            toolResult("toolu_A", "real-A"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(3);
        assertThat(out.get(2).toolCallId()).isEqualTo("toolu_A");
        assertThat(out.get(2).content()).isEqualTo("real-A");
    }

    @Test
    @DisplayName("边界②-3 首条即孤立 tool 结果 → 换成 user 占位文本（载荷仍以 user 开头 · CC :5642-5657）")
    void leadingOrphanToolResult_replacedByUserPlaceholder() {
        List<ChatMessageDto> in = List.of(toolResult("toolu_ghost", "orphan"), user("hi"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).role()).isEqualTo(Role.user);
        assertThat(out.get(0).content())
            .isEqualTo(ToolResultPairingRepair.ORPHANED_TOOL_RESULT_REMOVED_PLACEHOLDER);
        assertThat(out.get(1)).isSameAs(in.get(1));
    }

    // ─────────── 边界④ 连续多条 assistant ───────────

    @Test
    @DisplayName("边界④ 连续多条 assistant → 前一条补合成结果，两条 assistant 均不动（不合并 / 不删）")
    void consecutiveAssistants_areNeitherMergedNorDeleted() {
        ChatMessageDto asst1 = assistant("a", call("toolu_A"));
        ChatMessageDto asst2 = assistant("b");
        List<ChatMessageDto> in = List.of(asst1, asst2);

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(roles(out))
            .containsExactly(Role.assistant, Role.tool, Role.assistant);
        assertThat(out.get(0)).isSameAs(asst1);
        assertThat(out.get(2)).isSameAs(asst2);
        assertThat(out.get(1).toolCallId()).isEqualTo("toolu_A");
    }

    @Test
    @DisplayName("边界④-2 重复 tool_use id（跨消息）→ 保留首次出现，后续剥离（API tool_use ids must be unique）")
    void duplicateToolUseIds_keptFirstOnly() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("first", call("toolu_A")),
            toolResult("toolu_A", "r1"),
            assistant("second", call("toolu_A")),
            toolResult("toolu_A", "r2"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(4);
        assertThat(out.get(1).toolCalls()).hasSize(1);
        assertThat(out.get(2).content()).isEqualTo("r1");
        // 第二个 assistant 的重复 tool_use 被剥离 → 其 tool 结果随之成孤儿被丢弃
        assertThat(out.get(3).toolCalls()).isEmpty();
        assertThat(out.get(3).content()).isEqualTo("second");
        assertThat(out).noneSatisfy(m -> assertThat(m.content()).isEqualTo("r2"));
    }

    @Test
    @DisplayName("边界④-3 同一 tool_result 出现两次 → 只留首条")
    void duplicateToolResults_keptFirstOnly() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A")),
            toolResult("toolu_A", "first"),
            toolResult("toolu_A", "second"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        assertThat(out).hasSize(3);
        assertThat(out.get(2).content()).isEqualTo("first");
    }

    // ─────────── 全字段透传（record 重建不得静默丢字段） ───────────

    @Test
    @DisplayName("assistant 重建（去重路径）→ cwd / userMessageId / usage 等非配对字段全透传")
    void rebuiltAssistant_preservesAllOtherFields() {
        ChatMessageDto base = assistant("", call("toolu_A"), call("toolu_A"));
        ChatMessageDto withExtras = base
            .withCwd("D:/repo")
            .withContentBlocks(List.of());
        List<ChatMessageDto> in = List.of(user("hi"), withExtras);

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        ChatMessageDto rebuilt = out.get(1);
        assertThat(rebuilt).isNotSameAs(withExtras);
        assertThat(rebuilt.toolCalls()).hasSize(1);
        assertThat(rebuilt.cwd()).isEqualTo("D:/repo");
        assertThat(rebuilt.id()).isEqualTo(withExtras.id());
        assertThat(rebuilt.sessionId()).isEqualTo(withExtras.sessionId());
        assertThat(rebuilt.isMeta()).isEqualTo(withExtras.isMeta());
    }

    // ─────────── 无 id 结果：CC 无此输入形态 → 丢弃（不认领） ───────────

    @Test
    @DisplayName("无 id 结果 → 丢弃（不认领）；其对应悬挂 id 收合成错误占位")
    void nullIdResult_isDroppedAndMissingIdSynthesized() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A")),
            toolResult(null, "REAL OUTPUT"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        // WHY（对齐 CC）：CC 的 tool_result 恒带 tool_use_id（ToolResultBlockParam.messages 里
        // tool_result 必填 tool_use_id），不存在「无 id 结果」这种输入 → 也就没有任何认领逻辑。
        // 对不上任何 tool_use 的结果统一走 orphanedIds 剥离：
        //   CC original: messages.ts:5817-5834 `if (orphanedSet.has(trId)) return false`。
        // 其对应的悬挂 tool_use 由合成块补齐：
        //   CC original: messages.ts:5796-5801 syntheticBlocks = {tool_use_id, content: 占位, is_error: true}。
        assertThat(out).hasSize(3);
        assertThat(out.get(2).toolCallId()).isEqualTo("toolu_A");
        assertThat(out.get(2).content()).isEqualTo(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
        assertThat(out.get(2).isError()).isTrue();
        // 真实输出被丢弃（数据保真度下降，见 R5 blockers）：既不认领，也不以任何形式保留
        assertThat(out).noneSatisfy(m -> assertThat(m.content()).isEqualTo("REAL OUTPUT"));
    }

    @Test
    @DisplayName("2 悬挂 + 1 无 id 结果 → 无 id 结果丢弃，两个 id 各收合成占位（仍不认领）")
    void nullIdResult_alwaysDropped_missingIdsSynthesized() {
        List<ChatMessageDto> in = List.of(
            user("hi"),
            assistant("", call("toolu_A"), call("toolu_B")),
            toolResult(null, "REAL OUTPUT"));

        List<ChatMessageDto> out = ToolResultPairingRepair.ensureToolResultPairing(in);

        // WHY（对齐 CC）：无 id 结果与任何 tool_use 都对不上 → 一律剥离（不因「1:1 无歧义」而认领，
        // CC 无此扩展）。CC original: messages.ts:5817-5834（剥孤儿）+ :5796-5801（合成占位）。
        assertThat(out).hasSize(4);
        assertThat(out.get(2).toolCallId()).isEqualTo("toolu_A");
        assertThat(out.get(3).toolCallId()).isEqualTo("toolu_B");
        assertThat(out).allSatisfy(m -> assertThat(m.content()).isNotEqualTo("REAL OUTPUT"));
    }

    // ─────────── 空输入容错 ───────────

    @Test
    @DisplayName("null / 空列表原样返回（不抛）")
    void nullAndEmptyInput_passthrough() {
        assertThat(ToolResultPairingRepair.ensureToolResultPairing(null)).isNull();
        List<ChatMessageDto> empty = List.of();
        assertThat(ToolResultPairingRepair.ensureToolResultPairing(empty)).isSameAs(empty);
    }
}
