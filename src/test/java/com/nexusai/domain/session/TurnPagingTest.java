package com.nexusai.domain.session;

import com.nexusai.domain.session.TurnPaging.TurnAnchor;
import com.nexusai.domain.session.TurnPaging.TurnWindow;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.entity.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [turn-paging] {@link TurnPaging} 纯单测（不启 Spring、不碰 DB）。
 *
 * <p><b>WHY（CLAUDE.md 规则九：测意图而非行为）</b>：用户裁定「按轮分页」要解决的是
 * <b>「一个巨型轮占满整页 ⇒ 首屏连一轮都显示不全」</b>。所以本组用例钉死的不是「方法返回了某几个数」，
 * 而是四件会直接毁掉该体验的事：
 * <ul>
 *   <li><b>轮边界正确</b>：窗口首元素必须是<b>用户提问行本身</b>（含起点），窗口外不得混入更早的轮
 *       —— 否则前端渲染出一段没有提问的「半截轮」；</li>
 *   <li><b>非用户提问行不得开新轮</b>：cron / 系统提醒 / compact 摘要 / 子代理回执若被当成轮起点，
 *       长过程里会凭空多出若干个 {@code N nexus} 头（用户已有裁定：只按用户消息算轮次）；</li>
 *   <li><b>巨型轮不截断</b>：一轮 200 条 tool 消息必须整轮返回 —— 这正是分页口径从「条数」改「轮」的
 *       唯一原因；</li>
 *   <li><b>hasMore 三态</b>：还有更早 / 恰好到底 / 无轮 —— 判错会让前端要么永远翻不到顶，要么提前停止。</li>
 * </ul>
 *
 * <p><b>行区间模拟</b>：生产由 {@link MessageService#listMessagesByTurns} 把窗口换算成 seq 区间后
 * <b>下推 SQL</b>（{@code seq >= from AND seq < to}，<b>无 LIMIT</b>）。本测试用
 * {@link #idsInWindow} 复刻该 SQL 的区间语义（含「NULL seq 不入选」），从而在本层断言
 * 「取到的行集」是否完整。
 */
@DisplayName("[turn-paging] TurnPaging：轮锚点识别 + 窗口定位 + seq 行区间")
class TurnPagingTest {

    // ════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════

    private static MessageRecord row(String id, Long seq, String role) {
        return row(id, seq, role, null, null, null, null);
    }

    private static MessageRecord row(String id, Long seq, String role, String author, String subtype,
                                     Boolean isMeta, Boolean isCompactSummary) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setSeq(seq);
        m.setRole(role);
        m.setAuthor(author);
        m.setSubtype(subtype);
        m.setIsMeta(isMeta);
        m.setIsCompactSummary(isCompactSummary);
        return m;
    }

    /** 3 个用户轮（u1/u2/u3）盘在时间线上，夹杂 assistant / tool / 后台行（cron、compact boundary）。 */
    private static List<MessageRecord> session3Turns() {
        return new ArrayList<>(List.of(
            row("msg-u1", 10L, "user"),
            row("msg-a1", 11L, "assistant"),
            row("msg-t1", 12L, "tool"),
            row("msg-cron", 13L, "user", null, "cron", null, null),
            row("msg-u2", 20L, "user"),
            row("msg-a2", 21L, "assistant"),
            row("msg-bnd", 22L, "user", null, "compact_boundary", null, null),
            row("msg-u3", 30L, "user"),
            row("msg-a3", 31L, "assistant")));
    }

    /**
     * 测试侧模拟生产 SQL 的行区间：{@code seq >= from AND seq < to}（无 LIMIT）。
     * NULL seq 行在 SQL 比较下不满足条件 ⇒ 不入选（与生产「带区间的页永远取不到 NULL seq 行」一致）。
     */
    private static List<String> idsInWindow(List<MessageRecord> rows, TurnWindow win) {
        List<String> out = new ArrayList<>();
        for (MessageRecord r : rows) {
            Long seq = r.getSeq();
            if (seq == null) {
                continue;
            }
            if (win.seqFromInclusive() != null && seq < win.seqFromInclusive()) {
                continue;
            }
            if (win.seqToExclusive() != null && seq >= win.seqToExclusive()) {
                continue;
            }
            out.add(r.getId());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 最近 2 轮（3 个用户轮 + 夹杂 tool/boundary）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("最近 2 轮：窗口=[u2,u3]，含起点用户消息、不含更早的 u1 轮（含其后的 tool/cron 行）")
    void latestTwoTurns_boundsAreUserMessages() {
        List<MessageRecord> rows = session3Turns();
        List<TurnAnchor> anchors = TurnPaging.anchorsOf(rows);

        assertThat(anchors).extracting(TurnAnchor::id).containsExactly("msg-u1", "msg-u2", "msg-u3");

        TurnWindow win = TurnPaging.resolveWindow(anchors, null, 2);

        assertThat(win.startIndex()).isEqualTo(1);
        assertThat(win.endIndex()).isEqualTo(3);
        assertThat(win.hasMore()).isTrue();
        assertThat(win.firstTurnId()).isEqualTo("msg-u2");
        assertThat(win.lastTurnId()).isEqualTo("msg-u3");
        // 下界 = u2 自己的 seq（含起点）；窗口到会话末尾 ⇒ 无上界
        assertThat(win.seqFromInclusive()).isEqualTo(20L);
        assertThat(win.seqToExclusive()).isNull();

        // 取到的行：u2 起全部（含轮内的 assistant / boundary），u1 轮与 msg-cron 一律不在
        assertThat(idsInWindow(rows, win))
            .containsExactly("msg-u2", "msg-a2", "msg-bnd", "msg-u3", "msg-a3");
        assertThat(idsInWindow(rows, win)).doesNotContain("msg-u1", "msg-a1", "msg-t1", "msg-cron");
    }

    // ════════════════════════════════════════════════════════════════════
    // ② beforeTurnId 指向中间轮 → 返回它之前的 2 轮（不含该轮）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("游标指向中间轮 u3：返回它之前的 2 轮 [0,2)=u1,u2，上界切在 u3 的 seq（u3 本身不含）")
    void cursorAtMiddleTurn_returnsTurnsBefore() {
        List<MessageRecord> rows = session3Turns();
        List<TurnAnchor> anchors = TurnPaging.anchorsOf(rows);

        TurnWindow win = TurnPaging.resolveWindow(anchors, "msg-u3", 2);

        assertThat(win.startIndex()).isZero();
        assertThat(win.endIndex()).isEqualTo(2);
        assertThat(win.hasMore()).isFalse();          // 窗口已覆盖会话首轮 ⇒ 前面没有更早的提问
        assertThat(win.firstTurnId()).isEqualTo("msg-u1");
        assertThat(win.lastTurnId()).isEqualTo("msg-u2");
        assertThat(win.seqFromInclusive()).isNull();   // 覆盖到首轮 ⇒ 无下界（首轮之前的无主行随本页返回）
        assertThat(win.seqToExclusive()).isEqualTo(30L); // 切在 u3 自己的 seq 上（u3 属于下一页）

        assertThat(idsInWindow(rows, win))
            .containsExactly("msg-u1", "msg-a1", "msg-t1", "msg-cron", "msg-u2", "msg-a2", "msg-bnd");
        assertThat(idsInWindow(rows, win)).doesNotContain("msg-u3", "msg-a3");
    }

    @Test
    @DisplayName("游标指向第 4 轮时「前面还有更早」：窗口=[2,3)=u3，hasMore=true（u1/u2 还在更早）")
    void cursorBeyondFourthTurn_hasMoreTrue() {
        List<MessageRecord> rows = session3Turns();
        rows.add(row("msg-u4", 40L, "user"));
        List<TurnAnchor> anchors = TurnPaging.anchorsOf(rows);

        TurnWindow win = TurnPaging.resolveWindow(anchors, "msg-u4", 2);

        assertThat(win.startIndex()).isEqualTo(1);
        assertThat(win.endIndex()).isEqualTo(3);
        assertThat(win.hasMore()).isTrue();
        assertThat(win.firstTurnId()).isEqualTo("msg-u2");
        assertThat(win.lastTurnId()).isEqualTo("msg-u3");
        assertThat(win.seqToExclusive()).isEqualTo(40L); // 上界 = u4（不含）
        assertThat(idsInWindow(rows, win))
            .containsExactly("msg-u2", "msg-a2", "msg-bnd", "msg-u3", "msg-a3");
    }

    @Test
    @DisplayName("游标就是首轮 → 空窗口（前面没有任何轮），不得返回首轮自身")
    void cursorAtFirstTurn_returnsEmpty() {
        List<TurnAnchor> anchors = TurnPaging.anchorsOf(session3Turns());

        TurnWindow win = TurnPaging.resolveWindow(anchors, "msg-u1", 2);

        assertThat(win.isEmpty()).isTrue();
        assertThat(win.hasMore()).isFalse();
        assertThat(win.firstTurnId()).isNull();
        assertThat(win.lastTurnId()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 巨型轮：一轮 200 条 tool 消息 → 整轮返回，不截断
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("巨型轮（u2 后跟 200 条 tool + u3）：整轮 202 条全部落在区间内，一条不丢")
    void giantTurn_isReturnedWhole() {
        // WHY（用户裁定的原始缺陷）：条数分页下「150 条/页」会把一个巨型轮切成两页，首屏连一轮都拼不出来。
        //   轮分页的唯一价值就是「一轮再大也整轮给」⇒ 区间必须完整覆盖 200 条 tool 行（生产 SQL 无 LIMIT）。
        List<MessageRecord> rows = new ArrayList<>();
        rows.add(row("msg-u1", 1L, "user"));
        rows.add(row("msg-u2", 100L, "user"));
        for (int i = 0; i < 200; i++) {
            rows.add(row("msg-tool-" + i, 101L + i, "tool"));
        }
        rows.add(row("msg-u3", 400L, "user"));

        List<TurnAnchor> anchors = TurnPaging.anchorsOf(rows);
        TurnWindow win = TurnPaging.resolveWindow(anchors, null, 2);

        assertThat(win.firstTurnId()).isEqualTo("msg-u2");
        assertThat(win.seqFromInclusive()).isEqualTo(100L);

        List<String> got = idsInWindow(rows, win);
        assertThat(got).hasSize(202);                       // u2 + 200 tool + u3
        assertThat(got.get(0)).isEqualTo("msg-u2");         // 含起点用户消息
        assertThat(got).contains("msg-tool-0", "msg-tool-199"); // 两端都在，未被截断
        assertThat(got.get(got.size() - 1)).isEqualTo("msg-u3");
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ hasMore 三态
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hasMore 三态：还有更早=true / 恰好到底=false / 无轮=空窗口 false")
    void hasMore_threeStates() {
        List<TurnAnchor> three = TurnPaging.anchorsOf(session3Turns());

        // (a) 还有更早：3 轮取 2 → 窗口前仍有 u1
        assertThat(TurnPaging.resolveWindow(three, null, 2).hasMore()).isTrue();
        // (b) 恰好到底：3 轮全取
        assertThat(TurnPaging.resolveWindow(three, null, 3).hasMore()).isFalse();
        // (c) 无轮：会话里没有任何用户提问（只有 assistant / 后台行）
        List<MessageRecord> noTurnRows = List.of(
            row("msg-a1", 1L, "assistant"),
            row("msg-cron", 2L, "user", null, "cron", null, null));
        assertThat(TurnPaging.anchorsOf(noTurnRows)).isEmpty();
        TurnWindow none = TurnPaging.resolveWindow(TurnPaging.anchorsOf(noTurnRows), null, 2);
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.hasMore()).isFalse();
        assertThat(none.firstTurnId()).isNull();
        assertThat(none.lastTurnId()).isNull();
    }

    @Test
    @DisplayName("turns 归一化：缺省 2、上限 10（超过按 10）、非正回落 2")
    void normalizeTurns_capsAtTen() {
        assertThat(TurnPaging.normalizeTurns(0)).isEqualTo(2);
        assertThat(TurnPaging.normalizeTurns(-5)).isEqualTo(2);
        assertThat(TurnPaging.normalizeTurns(5)).isEqualTo(5);
        assertThat(TurnPaging.normalizeTurns(10)).isEqualTo(10);
        assertThat(TurnPaging.normalizeTurns(11)).isEqualTo(10);
        assertThat(TurnPaging.normalizeTurns(999)).isEqualTo(10);
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑤ 非用户提问行不得当轮起点（与前端 isUserTurnRow 同口径）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非用户提问行（cron/系统/attachment/meta/摘要/各类 boundary/子代理回执）一律不开新轮")
    void nonUserRows_neverBecomeTurnAnchors() {
        List<MessageRecord> rows = new ArrayList<>(List.of(
            row("msg-u1", 1L, "user"),
            // role=user 但各种「不是提问」的形态
            row("msg-cron", 2L, "user", null, "cron", null, null),
            row("msg-scheduled", 3L, "user", null, "scheduled_task_fire", null, null),
            row("msg-queue", 4L, "user", null, "queue", null, null),
            row("msg-away", 5L, "user", null, "away_summary", null, null),
            row("msg-tasknotif", 6L, "user", null, "task_notification", null, null),
            row("msg-taskstarted", 7L, "user", null, "task_started", null, null),
            row("msg-taskprogress", 8L, "user", null, "task_progress", null, null),
            row("msg-bnd", 9L, "user", null, "compact_boundary", null, null),
            row("msg-mbnd", 10L, "user", null, "microcompact_boundary", null, null),
            row("msg-snipbnd", 11L, "user", null, "snip_boundary", null, null),
            row("msg-snipmark", 12L, "user", null, "snip_marker", null, null),
            row("msg-sr", 13L, "user", null, "subagent_result", null, null),
            row("msg-srcpt", 14L, "user", null, "subagent_receipt", null, null),
            row("msg-sstart", 15L, "user", null, "subagent_start", null, null),
            row("msg-sstop", 16L, "user", null, "subagent_stop", null, null),
            row("msg-tus", 17L, "user", null, "tool_use_summary", null, null),
            row("msg-shs", 18L, "user", null, "stop_hook_summary", null, null),
            row("msg-sysauth", 19L, "user", "system", null, null, null),
            row("msg-attach", 20L, "user", "attachment", null, null, null),
            row("msg-meta", 21L, "user", null, null, true, null),
            row("msg-summary", 22L, "user", null, null, null, true),
            // 非 user 角色
            row("msg-a1", 23L, "assistant"),
            row("msg-t1", 24L, "tool"),
            row("msg-s1", 25L, "system"),
            // 第二个真提问
            row("msg-u2", 26L, "user")));

        assertThat(TurnPaging.anchorsOf(rows)).extracting(TurnAnchor::id)
            .containsExactly("msg-u1", "msg-u2");
        // 逐条钉死：任何一类被放行（例如漏掉 cron）都会在这里红
        assertThat(TurnPaging.isUserTurnRow("user", null, "cron", null, null)).isFalse();
        assertThat(TurnPaging.isUserTurnRow("user", null, null, true, null)).isFalse();
        assertThat(TurnPaging.isUserTurnRow("user", "attachment", null, null, null)).isFalse();
        assertThat(TurnPaging.isUserTurnRow("user", "system", null, null, null)).isFalse();
        assertThat(TurnPaging.isUserTurnRow("user", null, null, null, true)).isFalse();
        assertThat(TurnPaging.isUserTurnRow("assistant", null, null, null, null)).isFalse();
        // 真提问（isMeta/isCompactSummary 为 NULL 的旧行也必须放行）
        assertThat(TurnPaging.isUserTurnRow("user", null, null, null, null)).isTrue();
        assertThat(TurnPaging.isUserTurnRow("user", null, null, false, false)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑥ 游标非法 / seq 为 NULL
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("beforeTurnId 不属于锚点集合 → ValidationException（400），绝不静默返回空页")
    void unknownCursor_throwsValidation() {
        List<TurnAnchor> anchors = TurnPaging.anchorsOf(session3Turns());

        assertThatThrownBy(() -> TurnPaging.resolveWindow(anchors, "msg-ghost", 2))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("msg-ghost");
        // 非轮起点（存在但是 assistant 行）同样 400：锚点集合里没有它
        assertThatThrownBy(() -> TurnPaging.resolveWindow(anchors, "msg-a2", 2))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("msg-a2");
    }

    @Test
    @DisplayName("用户提问行 seq 为 NULL（位置键未落）→ 不作锚点，且被计数以便留痕")
    void nullSeqUserRow_isNotAnchor_butCounted() {
        List<MessageRecord> rows = new ArrayList<>(session3Turns());
        rows.add(row("msg-u-nullseq", null, "user"));

        assertThat(TurnPaging.anchorsOf(rows)).extracting(TurnAnchor::id)
            .containsExactly("msg-u1", "msg-u2", "msg-u3");
        assertThat(TurnPaging.countNullSeqTurnRows(rows)).isEqualTo(1);
        // 非用户行的 NULL seq 不计入（那些行走 warnNullSeqIfAny 的另一条兜底，不是轮锚点问题）
        assertThat(TurnPaging.countNullSeqTurnRows(List.of(row("msg-a-nullseq", null, "assistant")))).isZero();
    }
}
