package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.compact.BoundaryReader;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.PivotCandidateDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [dialog-ops-pivot] MessageService.listPivotCandidates 单测 · 候选 = 当前上下文可见的用户消息。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>：修复「弹窗候选只吃聊天尾页 50 条窗口」回归（v0.1.7 起，
 * window-paging 559223a4 漏改弹窗），并消除压缩后「已吸收旧消息混入候选」的错误。变异点：
 * <ul>
 *   <li>候选被 50 条窗口截断 / 最早消息不在列 → 红（T1，回归核心）</li>
 *   <li>子集喂 BoundaryReader ≠ 整表喂（边界行缺失 / 保留段头尾未补读 / 顺序错）→ 红（T3/T5）</li>
 *   <li>被压缩吸收的旧 user 行混入候选 → 红（T2）</li>
 *   <li>isMeta / isCompactSummary / isVisibleInTranscriptOnly 未排除 → 红（T2/T4）</li>
 *   <li>snip removedUuids 行仍在列 → 红（T4）</li>
 *   <li>removedAfter 口径错（不含自身 / meta 行未排除 / 非 seq 后缀）→ 红（T1/T6）</li>
 * </ul>
 *
 * <p><b>mock 分流约定</b>：listPivotCandidates 内 selectListByQuery 的调用序 =
 * ①主读 → ②补读（仅当 preservedSegment 头尾有 id 不在主读结果中）→ ③计数。
 * 本测试按调用序 thenAnswer 分流；实现改动调用顺序会使本测试立刻转红（提醒同步，属预期耦合）。
 */
@DisplayName("[dialog-ops-pivot] MessageService.listPivotCandidates（当前上下文可见用户消息）")
class MessageServicePivotCandidatesTest {

    private MessageService service;
    private MessageMapper messageMapper;
    /** [F4] 提升为字段：供「会话不存在」用例在本用例内覆写 stub（默认 setUp 里 stub 为存在）。 */
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord()); // session 存在（校验通过）
    }

    // ---- helpers（构造 DB 行）----

    private static MessageRecord rec(String id, long seq, String role, String subtype, String content) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole(role);
        m.setSubtype(subtype);
        m.setContent(content);
        m.setSeq(seq);
        m.setCreatedAt("2026-01-01T00:00:00Z");
        return m;
    }

    private static MessageRecord user(String id, long seq, String content) {
        return rec(id, seq, "user", null, content);
    }

    private static MessageRecord assistant(String id, long seq) {
        return rec(id, seq, "assistant", null, "回复-" + id);
    }

    /** compact 摘要行：role=user + isCompactSummary + isVisibleInTranscriptOnly（CompactConversation.java:1141-1149 形态）。 */
    private static MessageRecord summary(String id, long seq) {
        MessageRecord m = rec(id, seq, "user", "summary", "摘要");
        m.setIsCompactSummary(true);
        m.setIsVisibleInTranscriptOnly(true);
        return m;
    }

    /** compact_boundary 行（带 preservedSegment；anchor/head/tail 与 PartialCompactConversation 落库形态一致）。 */
    private static MessageRecord boundarySeg(String id, long seq, String head, String anchor, String tail) {
        MessageRecord m = rec(id, seq, "system", "compact_boundary", "");
        m.setCompactMetadata("{\"preservedSegment\":{\"headUuid\":\"" + head
            + "\",\"anchorUuid\":\"" + anchor + "\",\"tailUuid\":\"" + tail + "\"}}");
        return m;
    }

    private static MessageRecord snipBoundary(String id, long seq, String removedId) {
        MessageRecord m = rec(id, seq, "system", "snip_boundary", "");
        m.setSnipMetadata("{\"removedUuids\":[\"" + removedId + "\"]}");
        return m;
    }

    /** 按调用序分流：主读 → （补读，extraRead != null 时）→ 计数；[F5] 越界响亮——多出来的查询调用
     *  不得静默拿空表（否则「实现新增了一次查询」的回归会让本测试照绿）。 */
    private void stubReads(List<MessageRecord> mainRead, List<MessageRecord> extraRead, List<MessageRecord> countRows) {
        List<List<MessageRecord>> calls = new ArrayList<>();
        calls.add(mainRead);
        if (extraRead != null) {
            calls.add(extraRead);
        }
        calls.add(countRows);
        AtomicInteger i = new AtomicInteger();
        // ⚠️ 必须 doAnswer().when() 而非 when().thenAnswer()：T5 在同一用例内对同一 mock 多次 re-stub，
        //   后者在 stub 那一刻会真实调用旧 answer（F5 越界抛错会误炸在 stub 行而不是被测调用处）。
        doAnswer(inv -> {
            int idx = i.getAndIncrement();
            if (idx >= calls.size()) {
                throw new AssertionError("selectListByQuery 第 " + (idx + 1) + " 次调用超出预期——实现新增了查询？请同步本测试");
            }
            return calls.get(idx);
        }).when(messageMapper).selectListByQuery(any());
    }

    private static List<String> ids(List<PivotCandidateDto> out) {
        return out.stream().map(PivotCandidateDto::id).toList();
    }

    // ---- T1 回归核心 ----

    @Test
    @DisplayName("T1 无压缩长会话：60 条用户消息全在列（不被 50 条窗口截断），计数含自身")
    void noCompact_allUserMessagesListed() {
        List<MessageRecord> users = new ArrayList<>();
        List<MessageRecord> all = new ArrayList<>();
        for (int n = 1; n <= 60; n++) {
            users.add(user("u" + n, 2L * n, "内容" + n));
            all.add(user("u" + n, 2L * n, "内容" + n));
            all.add(assistant("a" + n, 2L * n + 1));
        }
        stubReads(users, null, all);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(out).hasSize(60);
        assertThat(out.get(0).id()).isEqualTo("u1"); // 最早一条在列 = 回归核心断言
        assertThat(out.get(59).id()).isEqualTo("u60");
        // 计数口径（非 meta、含自身）：u1(seq2) 之后 = 全部 120 行；u60(seq120) 之后 = u60+a60 = 2
        assertThat(out.get(0).removedAfter()).isEqualTo(120);
        assertThat(out.get(59).removedAfter()).isEqualTo(2);
        // [F4] 出站其他字段：previewSource = 原正文（未超窗口不截断）、createdAt 非空
        assertThat(out.get(0).previewSource()).isEqualTo("内容1");
        assertThat(out.get(0).createdAt()).isNotNull();
    }

    // ---- T2 压缩 from：吸收排除 + 保留段重挂顺序 ----

    @Test
    @DisplayName("T2 压缩（from）：被吸收旧消息不在列、保留段重挂顺序正确、摘要不在列")
    void compactFrom_excludesAbsorbed_relinkOrder() {
        // 表（seq 升序）：u1(1),a1(2),u2(3),a2(4),u3(5),a3(6),u4(7),a4(8),b(9),s(10),u5(11),a5(12)
        // 压缩 from（pivot=u3）：kept=[u1,a1,u2]（seq 原值 1..3，物理在 boundary 之前）；u3/a3/u4/a4 被吸收但保留在表
        List<MessageRecord> mainRead = List.of(
            user("u1", 1, "一"), user("u2", 3, "二"), user("u3", 5, "三"), user("u4", 7, "四"),
            boundarySeg("b", 9, "u1", "b", "u2"), summary("s", 10), user("u5", 11, "五"));
        List<MessageRecord> allRows = List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"), assistant("a2", 4),
            user("u3", 5, "三"), assistant("a3", 6), user("u4", 7, "四"), assistant("a4", 8),
            boundarySeg("b", 9, "u1", "b", "u2"), summary("s", 10), user("u5", 11, "五"), assistant("a5", 12));
        stubReads(mainRead, null, allRows); // head/tail 均在主读结果内 → 无补读

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(ids(out)).containsExactly("u1", "u2", "u5");
    }

    // ---- T3 压缩 up_to：保留段尾为 assistant → 必须补读 ----

    @Test
    @DisplayName("T3 压缩（up_to）：保留段尾是 assistant 行——不补读则重挂放弃、候选丢失")
    void compactUpTo_relinkNeedsSupplementRead() {
        // 表（seq 升序）：u1(1),a1(2),u2(3),a2(4),u3(5),a3(6),b(7),s(8)
        // 压缩 up_to（pivot=u2）：kept=[u3,a3]（seq 5,6 原位）；anchor = summary id（s）
        List<MessageRecord> mainRead = List.of(
            user("u1", 1, "一"), user("u2", 3, "二"), user("u3", 5, "三"),
            boundarySeg("b", 7, "u3", "s", "a3"), summary("s", 8));
        List<MessageRecord> extraRead = List.of(assistant("a3", 6)); // tail=a3 不在主读 → 补读
        List<MessageRecord> allRows = List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"), assistant("a2", 4),
            user("u3", 5, "三"), assistant("a3", 6), boundarySeg("b", 7, "u3", "s", "a3"), summary("s", 8));
        stubReads(mainRead, extraRead, allRows);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(ids(out)).containsExactly("u3");
    }

    // ---- T4 snip ----

    @Test
    @DisplayName("T4 snip：removedUuids 中的消息不在列")
    void snippedMessagesExcluded() {
        List<MessageRecord> rows = List.of(
            user("u1", 1, "一"), user("u2", 2, "二"), user("u3", 3, "三"),
            snipBoundary("sb", 4, "u2"), user("u4", 5, "四"));
        stubReads(rows, null, rows);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(ids(out)).containsExactly("u1", "u3", "u4");
    }

    // ---- T5 等价性（本设计的核心风险，必须钉住）----

    @Test
    @DisplayName("T5 等价性：子集喂与整表喂 BoundaryReader 的候选逐条一致（矩阵）")
    void subsetFeed_equalsFullFeed() {
        // 场景 A：无边界
        assertEquivalence(List.of(user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二")));
        // 场景 B：from + seg（head/tail 均为 user）
        assertEquivalence(List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"), assistant("a2", 4),
            user("u3", 5, "三"), assistant("a3", 6), user("u4", 7, "四"), assistant("a4", 8),
            boundarySeg("b", 9, "u1", "b", "u2"), summary("s", 10), user("u5", 11, "五"), assistant("a5", 12)));
        // 场景 C：up_to + seg（tail 为 assistant → 触发补读路径）
        assertEquivalence(List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"), assistant("a2", 4),
            user("u3", 5, "三"), assistant("a3", 6), boundarySeg("b", 7, "u3", "s", "a3"), summary("s", 8)));
        // 场景 D：多边界（旧 boundary 无 seg + 新 boundary 有 seg）
        assertEquivalence(List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"),
            boundarySeg("b0", 4, "u1", "b0", "u1"), summary("s0", 5),
            user("u3", 6, "三"), user("u4", 7, "四"),
            boundarySeg("b1", 8, "u3", "b1", "u3"), summary("s1", 9), user("u5", 10, "五")));
        // 场景 E：snip（无 compact 边界）
        assertEquivalence(List.of(
            user("u1", 1, "一"), user("u2", 2, "二"), snipBoundary("sb", 3, "u2"), user("u3", 4, "三")));
    }

    /**
     * 等价性对照：expected = 整表行喂 BoundaryReader 后过滤；actual = 服务方法
     * （主读/补读 SQL 由测试按同构条件从整表裁出，mock 返回）。
     */
    private void assertEquivalence(List<MessageRecord> full) {
        // expected：整表喂（判定入口与实现同源；构造复用服务侧 package-private 助手，消掉构造变量）
        List<ChatMessageDto> allDtos = full.stream().map(service::lightDtoForPivot).toList();
        List<String> expected = BoundaryReader.getMessagesAfterCompactBoundary(allDtos, false).stream()
            .filter(m -> m.role() == Role.user && !m.isMeta() && !m.isCompactSummary() && !m.isVisibleInTranscriptOnly())
            .map(ChatMessageDto::id).toList();

        // actual：按主读条件（role=user OR subtype in 边界）裁子集 + 补读保留段头尾
        List<MessageRecord> mainRead = new ArrayList<>();
        for (MessageRecord r : full) {
            if ("user".equals(r.getRole()) || "compact_boundary".equals(r.getSubtype())
                || "snip_boundary".equals(r.getSubtype())) {
                mainRead.add(r);
            }
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        for (MessageRecord r : mainRead) {
            present.add(r.getId());
        }
        List<MessageRecord> extraRead = new ArrayList<>();
        for (int i = mainRead.size() - 1; i >= 0; i--) {
            MessageRecord b = mainRead.get(i);
            if ("compact_boundary".equals(b.getSubtype())) {
                if (b.getCompactMetadata() != null) {
                    for (MessageRecord r : full) {
                        String id = r.getId();
                        boolean isHeadOrTail = b.getCompactMetadata().contains("\"" + id + "\"");
                        if (isHeadOrTail && !present.contains(id)) {
                            extraRead.add(r);
                        }
                    }
                }
                break;
            }
        }
        stubReads(mainRead, extraRead.isEmpty() ? null : extraRead, full);

        List<String> actual = ids(service.listPivotCandidates("sess-1"));

        assertThat(actual).as("子集喂 ≠ 整表喂（等价性假设被破坏）").isEqualTo(expected);
    }

    // ---- T6 计数口径 ----

    @Test
    @DisplayName("T6 removedAfter：含自身、非 meta 口径（meta 行不计入）")
    void removedAfterCaliber() {
        List<MessageRecord> users = List.of(user("u1", 1, "一"), user("u2", 2, "二"));
        MessageRecord meta = assistant("am", 3);
        meta.setIsMeta(true);
        List<MessageRecord> all = new ArrayList<>(users);
        all.add(meta);
        all.add(assistant("a2", 4));
        stubReads(users, null, all);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(out.get(0).removedAfter()).isEqualTo(3); // u1(seq1) 之后：u1,u2,a2（am 为 meta 不计）
        assertThat(out.get(1).removedAfter()).isEqualTo(2); // u2(seq2) 之后：u2,a2
    }

    // ---- T7 空会话 ----

    @Test
    @DisplayName("T7 空会话：空候选不抛异常（F6b 早退：仅 1 次主读，不查计数）")
    void emptySession_returnsEmpty() {
        stubReads(List.of(), null, List.of());

        assertThat(service.listPivotCandidates("sess-1")).isEmpty();
    }

    // ---- T8 SQL 层断言（F1：mock any() 看不到 QueryWrapper 内容，必须钉住真实下发的 SQL）----

    @Test
    @DisplayName("T8 SQL 层：主读谓词 = user + 两种边界 + seq 升序 NULLS LAST；补读 = 本会话 + id IN 头尾")
    void sqlLayer_mainPredicateOrderAndSupplementRead() {
        // 场景 = T3（up_to + seg、tail=assistant）→ 恰制造 3 次查询：主读 / 补读 / 计数。
        // ⚠️ toSQL() 不带表名（mybatis-flex 执行期才由 mapper entity 解析 FROM；
        //   先例 MessageServiceDeleteBySubtypeTest.java:73）⇒ 断言只能落在 WHERE / ORDER BY 面。
        List<MessageRecord> mainRead = List.of(
            user("u1", 1, "一"), user("u2", 3, "二"), user("u3", 5, "三"),
            boundarySeg("b", 7, "u3", "s", "a3"), summary("s", 8));
        List<MessageRecord> extraRead = List.of(assistant("a3", 6));
        List<MessageRecord> allRows = List.of(
            user("u1", 1, "一"), assistant("a1", 2), user("u2", 3, "二"), assistant("a2", 4),
            user("u3", 5, "三"), assistant("a3", 6), boundarySeg("b", 7, "u3", "s", "a3"), summary("s", 8));
        stubReads(mainRead, extraRead, allRows);

        service.listPivotCandidates("sess-1");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper, atLeastOnce()).selectListByQuery(captor.capture());
        List<QueryWrapper> calls = captor.getAllValues();
        assertThat(calls).as("主读 + 补读 + 计数 = 恰 3 次查询（零 N+1、零全量 toDto）").hasSize(3);

        String mainSql = String.valueOf(calls.get(0).toSQL());
        assertThat(mainSql).as("会话隔离条件").contains("session_id");
        assertThat(mainSql).as("user 行必入（漏掉 → 候选恒空）").contains("role = 'user'");
        assertThat(mainSql).as("compact 边界行必入（漏掉 → 压缩后已吸收旧消息混入候选）").contains("'compact_boundary'");
        assertThat(mainSql).as("snip 边界行必入（漏掉 → 被 snip 删除的消息复活成候选）").contains("'snip_boundary'");
        assertThat(mainSql).as("seq 升序 + NULLS LAST（等价性前提：喂 BoundaryReader 的顺序必须与整表一致）")
            .contains("seq ASC NULLS LAST");

        String supplementSql = String.valueOf(calls.get(1).toSQL());
        assertThat(supplementSql).as("补读仍限本会话").contains("session_id");
        assertThat(supplementSql).as("补读按 preservedSegment 头尾 id 精确取行（tail=a3 不在主读 → 必发此查询）")
            .contains("id IN");

        String countSql = String.valueOf(calls.get(2).toSQL());
        assertThat(countSql).as("计数只读 (seq, is_meta) 两个小列（不读 46 列行）")
            .contains("seq").contains("is_meta");
    }

    // ---- T9 候选 seq 为 NULL（F3：显式失败，不静默 removedAfter=0）----

    @Test
    @DisplayName("T9 候选 seq 为 NULL → IllegalStateException（静默 0 会让前端误显示「删除 0 条」）")
    void candidateWithNullSeq_throwsIllegalState() {
        MessageRecord dirty = user("u-null", 2, "脏行（位置键未落）");
        dirty.setSeq(null);
        List<MessageRecord> rows = List.of(user("u1", 1, "一"), dirty);
        stubReads(rows, null, rows); // visible 非空 → 计数查询发生 → 备量 [主读, 计数]

        assertThatThrownBy(() -> service.listPivotCandidates("sess-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("seq")
            .hasMessageContaining("sess-1")
            .hasMessageContaining("u-null");
    }

    // ---- T10 previewSource 截断（F4）----

    @Test
    @DisplayName("T10 previewSource：3000 字正文截到 2048（与前端 PREVIEW_SOURCE_CHARACTERS 同值单点约定）")
    void previewSource_truncatedAt2048() {
        String content = "x".repeat(3000);
        List<MessageRecord> rows = List.of(user("u1", 1, content));
        stubReads(rows, null, rows);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).previewSource()).hasSize(2048).isEqualTo(content.substring(0, 2048));
    }

    // ---- T11/T12 入参/会话校验（F4）----

    @Test
    @DisplayName("T11 空白 sessionId → NotFoundException（拒绝跨会话候选查询）")
    void blankSessionId_throwsNotFound() {
        assertThatThrownBy(() -> service.listPivotCandidates(" "))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("T12 会话不存在 → NotFoundException")
    void unknownSession_throwsNotFound() {
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listPivotCandidates("sess-404"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---- T13 user + isMeta 排除（I-3 载荷判据）----

    @Test
    @DisplayName("T13 user + isMeta=true 不在候选（mcp_instructions_delta 尾部注入的元消息不得被选成 pivot）")
    void metaUserMessage_excluded() {
        // WHY（载荷判据，终审 I-3）：其余用例都不覆盖「role=user && isMeta=true」——T6 的 meta 行是
        //   assistant（先被 role 过滤）、summary 行两 flag 互遮 ⇒ 若 lightDtoForPivot 把 isMeta 映射错
        //   （如恒 false），全部用例仍会绿。真实来源：AgentLoopContext.java:4810-4829 的
        //   mcp_instructions_delta producer 建的是 **user + isMeta=false**、本通道补正为 isMeta=true
        //   并已落库、追加在**尾部**（最后一条 boundary 之后 ⇒ 必在模型视图内）。映射错位会让
        //   「给模型看的 system-reminder 正文」以「你」的身份出现在候选里可被选成 pivot。
        MessageRecord um = user("um", 2, "元消息");
        um.setIsMeta(true);
        List<MessageRecord> rows = List.of(user("u1", 1, "一"), um, user("u2", 3, "二"));
        stubReads(rows, null, rows);

        List<PivotCandidateDto> out = service.listPivotCandidates("sess-1");

        assertThat(ids(out)).containsExactly("u1", "u2"); // um 不在列
    }
}
