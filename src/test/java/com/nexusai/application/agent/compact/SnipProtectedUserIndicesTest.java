package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [snip-protect-recent] {@link SnipCompactor#protectedUserIndices(List)} 判据测试 · 验证意图（CLAUDE.md 规则 9）。
 *
 * <p><b>WHY</b>：用户症状「snip 会压缩最后几轮用户对话 → 裁剪后 AI 不知道干嘛了」。snip 是
 * <b>模型驱动</b>的（模型点 {@code [id:]} 短 id），此前 {@code [id:]} tag 打给<b>所有</b>非 meta
 * user 消息<b>含最新一条</b>、且无任何 index 下限 —— 模型能把投影裁光。本判据是保护区的<b>唯一来源</b>，
 * 同时被 {@code SnipTool}（硬门拒绝）与 {@code AgentLoopContext}（不打 {@code [id:]} 标记）消费。
 *
 * <p>测试钉死的核心契约（<b>首尾双保护</b>，规范 D1 + D4）：
 * <ol>
 *   <li>保护集 = <b>{首条非 meta user} ∪ {最后 {@link SnipCompactor#KEEP_RECENT_USER_TURNS} 条非 meta user}</b>；
 *       <b>D4</b>：首条承载<b>原始任务陈述 + 约束</b>，硬门对 index 0 与 index n-1 一视同仁是缺口 ——
 *       首条被裁后模型处境与「裁完不知道干嘛」同构；</li>
 *   <li><b>必须去重</b>：短会话里首条可能就是最后两条之一（1 条 → 集合大小 1，2 条 → 2，<b>不是</b> 2/3）；</li>
 *   <li><b>isMeta 不计数</b> —— 判据必须与 {@code maybeAppendSnipIdTags} 的过滤条件
 *       （{@code role()==user && !isMeta}）语义一致，否则出现「打了标记但不可裁」/「没打标记却可裁」；</li>
 *   <li><b>代价已登记</b>（规范 §6.1②）：≤3 条非 meta user → 保护集 = <b>全部</b>，一条都裁不了；</li>
 *   <li>null / 空列表 / 无非 meta user → 空集（fail-safe，不抛）。</li>
 * </ol>
 */
class SnipProtectedUserIndicesTest {

    private static final String SESSION = "sess-snip-protect";

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static ChatMessageDto user(String id) {
        return msg(id, Role.user, false);
    }

    private static ChatMessageDto metaUser(String id) {
        return msg(id, Role.user, true);
    }

    private static ChatMessageDto assistant(String id) {
        return msg(id, Role.assistant, false);
    }

    private static ChatMessageDto msg(String id, Role role, boolean isMeta) {
        return new ChatMessageDto(
            id, SESSION, role,
            role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            "content-" + id, null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, isMeta, false);
    }

    /** n 轮 U/A 交替：非 meta 用户消息 index = 0,2,4,…,(2n-2)。 */
    private static List<ChatMessageDto> turns(int n) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(user("u" + i));
            list.add(assistant("a" + i));
        }
        return list;
    }

    // ────────────────────────────────────────────────────────────────────
    // 首尾双保护
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("首条非 meta 用户消息受保护（D4）")
    void firstNonMetaUserMessageIsProtected() {
        // 5 轮 U/A：非 meta user = 0,2,4,6,8 → 首条 0 + 最后两条 6,8
        assertThat(SnipCompactor.protectedUserIndices(turns(5)))
            .as("保护集 = {首条} ∪ {最后 2 条} = {0, 6, 8}")
            .containsExactlyInAnyOrder(0, 6, 8);
        assertThat(SnipCompactor.protectedUserIndices(turns(5)))
            .as("★ 首条（原始任务陈述 + 约束）必须在保护集内 —— 这正是 D4 要堵的缺口")
            .contains(0);
    }

    @Test
    @DisplayName("保护最后 2 条非 meta 用户消息（D1，尾部不回归）")
    void protectsLastTwoNonMetaUserMessages() {
        assertThat(SnipCompactor.protectedUserIndices(turns(5)))
            .as("尾部保护不因并入首条而丢失：6(U4) 与 8(U5) 仍在集合内")
            .contains(6, 8);
    }

    @Test
    @DisplayName("首尾重合时去重：1 条 → 大小 1，2 条 → 大小 2")
    void deduplicatesWhenFirstIsAlsoInTail() {
        assertThat(SnipCompactor.protectedUserIndices(turns(1)))
            .as("1 条非 meta user → 该条既是首条又是尾条 → 集合大小 1（去重，不是 2）")
            .containsExactly(0)
            .hasSize(1);

        assertThat(SnipCompactor.protectedUserIndices(turns(2)))
            .as("2 条非 meta user → 首条 0 与尾条 2 → 集合大小 2（去重，不是 3）")
            .containsExactlyInAnyOrder(0, 2)
            .hasSize(2);
    }

    @Test
    @DisplayName("非 meta 用户消息 ≤3 条时保护集 = 全部（D4 的已登记代价）")
    void allProtectedWhenThreeOrFewer() {
        assertThat(SnipCompactor.protectedUserIndices(turns(1)))
            .as("1 条 → 保护集 = {U1}")
            .containsExactly(0);
        assertThat(SnipCompactor.protectedUserIndices(turns(2)))
            .as("2 条 → 保护集 = {U1,U2}")
            .containsExactlyInAnyOrder(0, 2);
        assertThat(SnipCompactor.protectedUserIndices(turns(3)))
            .as("3 条 → 保护集 = {U1,U2,U3} = 全部，一条都裁不了（规范 §6.1②）")
            .containsExactlyInAnyOrder(0, 2, 4)
            .hasSize(3);
    }

    @Test
    @DisplayName("4 条时只剩第 2 条可裁 —— 首条并入不误伤中间轮")
    void fourTurnsLeaveOnlySecondSnipable() {
        // 4 轮：非 meta user = 0,2,4,6 → 保护 {0} ∪ {4,6} = {0,4,6} → 仅 index 2（U2）可裁
        assertThat(SnipCompactor.protectedUserIndices(turns(4)))
            .as("首条并入后仍必须留出中间轮：保护集 = {0,4,6}")
            .containsExactlyInAnyOrder(0, 4, 6);

        List<ChatMessageDto> msgs = turns(4);
        List<Integer> snipable = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i).role() == Role.user && !Boolean.TRUE.equals(msgs.get(i).isMeta())
                    && !SnipCompactor.protectedUserIndices(msgs).contains(i)) {
                snipable.add(i);
            }
        }
        assertThat(snipable)
            .as("★ 4 条时唯一可裁的是中间轮 U2（index 2）—— 证明首条并入没有把中间轮一起保护掉")
            .containsExactly(2);
    }

    @Test
    @DisplayName("isMeta 用户消息不计数（不占首条位、不占尾条位）")
    void isMetaUserMessagesAreNotCounted() {
        // 0=U1 1=A1 2=U2 3=A2 4=U3 5=A3 6=U4 7=A4 8=metaU 9=U5
        List<ChatMessageDto> msgs = new ArrayList<>(List.of(
            user("u1"), assistant("a1"), user("u2"), assistant("a2"),
            user("u3"), assistant("a3"), user("u4"), assistant("a4"),
            metaUser("meta"), user("u5")));

        assertThat(SnipCompactor.protectedUserIndices(msgs))
            .as("非 meta user index = 0,2,4,6,9 → 首条 0 + 尾两条 6,9 = {0,6,9}")
            .containsExactlyInAnyOrder(0, 6, 9);
        assertThat(SnipCompactor.protectedUserIndices(msgs))
            .as("isMeta user 消息不进入保护区（否则与 maybeAppendSnipIdTags 过滤条件不一致）")
            .doesNotContain(8);
    }

    @Test
    @DisplayName("首条若为 isMeta，则保护的是第一条【非 meta】用户消息")
    void isMetaFirstMessageDoesNotOccupyTheFirstSlot() {
        // 0=metaU 1=A1 2=U1 3=A2 4=U2 5=A2' … 非 meta user = 2,4,6
        List<ChatMessageDto> msgs = new ArrayList<>(List.of(
            metaUser("meta0"), assistant("a0"),
            user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"), assistant("a3")));

        assertThat(SnipCompactor.protectedUserIndices(msgs))
            .as("isMeta 不占首条位 → 首条非 meta user 是 index 2；尾部为 4,6 → {2,4,6}")
            .containsExactlyInAnyOrder(2, 4, 6);
    }

    // ────────────────────────────────────────────────────────────────────
    // 边界
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空或 null 列表返回空集")
    void emptyOrNullReturnsEmptySet() {
        assertThat(SnipCompactor.protectedUserIndices(null)).as("null → 空集（fail-safe 不抛）").isEmpty();
        assertThat(SnipCompactor.protectedUserIndices(List.of())).as("空列表 → 空集").isEmpty();
    }

    @Test
    @DisplayName("无非 meta 用户消息 → 空集")
    void noNonMetaUserReturnsEmptySet() {
        assertThat(SnipCompactor.protectedUserIndices(List.of(assistant("a0"))))
            .as("只有 assistant → 无首条可保护 → 空集")
            .isEmpty();
        assertThat(SnipCompactor.protectedUserIndices(List.of(metaUser("m0"), assistant("a0"))))
            .as("只有 isMeta user + assistant → 空集")
            .isEmpty();
    }

    @Test
    @DisplayName("列表含 null 元素时不抛，仍正确判定")
    void toleratesNullElements() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(null);
        msgs.add(user("u1"));
        msgs.add(null);

        assertThat(SnipCompactor.protectedUserIndices(msgs))
            .as("null 元素被跳过，不受影响（ctx.messages() 可能含 null）")
            .containsExactly(1);
    }

    // ────────────────────────────────────────────────────────────────────
    // 常量接线
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("首条保护由 KEEP_FIRST_USER_MESSAGE 常量接线（非硬编码）")
    void firstMessageProtectionIsWiredToTheConstant() {
        assertThat(SnipCompactor.KEEP_FIRST_USER_MESSAGE)
            .as("D4 首尾双保护开关（规范 §5.1）")
            .isTrue();
        assertThat(SnipCompactor.protectedUserIndices(turns(5)))
            .as("★ 常量若为 false，本用例必须变红 —— 证明常量是【活的】而非死码")
            .contains(0);
    }

    @Test
    @DisplayName("尾部保护条数由 KEEP_RECENT_USER_TURNS 单点定义")
    void tailProtectionCountComesFromSingleConstant() {
        assertThat(SnipCompactor.KEEP_RECENT_USER_TURNS)
            .as("保护条数 = 用户原话「最后两个用户对话」（规范 D1）")
            .isEqualTo(2);

        // 6 轮：非 meta user = 0,2,4,6,8,10 → 尾两条 8,10 + 首条 0 → {0,8,10}
        assertThat(SnipCompactor.protectedUserIndices(turns(6)))
            .as("尾部两侧各取 KEEP_RECENT_USER_TURNS 条（+ 首条），改常量 → 本用例必须变红")
            .containsExactlyInAnyOrder(0, 8, 10);
    }
}
