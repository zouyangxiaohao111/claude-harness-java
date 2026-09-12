package com.nexusai.domain.session;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SM/compact 对齐 CC] {@code MessageService.appendPostCompactMessages} = compact 结果 <b>append-only</b> 落库。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC transcript 是 append-only
 * （sessionStorage.ts {@code recordTranscript}/{@code insertMessageChain} 只追加；compact 只追加 boundary +
 * summary，加载/请求侧按最后一个 boundary 剪枝）。旧实现 {@code replaceSessionMessages}「删全表 + 按新时间基
 * 重插」会让同一 run 内 compact <b>之后</b>追加的消息 created_at 早于重插的 boundary → 下轮
 * {@code listRawForTranscript}（created_at ASC）顺序倒挂 → boundary 切片整段丢消息（HIGH bug）。
 * 本测试锁死 append-only 三条不变量：
 * <ol>
 *   <li><b>绝不删除</b>任何旧行（DELETE 零调用）——删了就丢轨迹、且会带走 boundary 之前的历史；</li>
 *   <li><b>已存在 id</b>（messagesToKeep）→ <b>零改写</b>（不 UPDATE、不 INSERT）：对齐 CC
 *       {@code recordTranscript} 按 uuid dedup 跳过（sessionStorage.ts:1457-1468，CC 注释自述 kept 行
 *       parentUuid 保持 pre-compact 原值 "can't rewrite"）。kept 行留在 boundary 之前，靠<b>读侧</b>
 *       {@code BoundaryReader.applyPreservedSegmentRelink} 按 preservedSegment 重挂回模型视图；</li>
 *   <li><b>新 id</b>（boundary / summary）→ INSERT 新行，seq 单调，返回顺序 = 入参顺序。</li>
 * </ol>
 *
 * <p><b>[D4 解法1-精简 · 2026-09-12]</b>：改造前写侧对已存在 id 发 {@code UPDATE seq} 把 kept 搬到
 * boundary 之后（"写侧重挂"）。该分支<b>已删除</b> —— 搬迁与「真 append-only」相悖、会抹掉位置的历史，
 * 且与 CC 的写法相反（CC 磁盘上原地不动）。
 *
 * <p><b>[SM/compact 对齐 CC · seq]</b>：V70 引入单调 seq 作位置键（雪花 ID，全局单调 long），根治
 * 「created_at 既是时间又是位置」——kept 在 DB 里 seq/created_at 双双保持原值（写侧零改写），
 * 位置还原完全交给读侧投影。
 *
 * <p><b>[SM/compact 对齐 CC · seq 块]</b>：整块 seq 由 {@code nextSeqBlock} <b>一次 CAS 原子占位</b>
 * （块内按数组序发号），保证并发实时落库（{@code ChatService.persistAppendedMessage}）的号不可能插进
 * compact 块内部 —— 详见 {@link MessageServiceSeqBlockTest} 与下方并发用例。kept 段不取号
 * （零改写）→ 号段只覆盖真正写入的新行，块内无空洞。
 *
 * <p><b>RED 条件</b>：改回删 + 重插 → {@code never()).deleteByQuery} 红；kept 段又发写入（UPDATE 或
 * INSERT）→ {@code never()).update} / insert 次数断言红；kept 段被塞进 seq 块 → 新行 seq 连续性断言红
 * （boundary/summary 不再是相邻号）；返回顺序被重排 → id 序列断言红；
 * 整块改回逐个 {@code nextSeq} → 并发用例的块内连续性断言红；两个 boundary id 复用同一个 → INSERT
 * 次数断言红。
 */
@DisplayName("[SM/compact 对齐 CC] appendPostCompactMessages = append-only 落库（不删旧行）")
class MessageServiceAppendPostCompactTest {

    private static final String SESSION = "sess-append01";
    /** boundary 行 id 形态 = 雪花数字串（生产由 {@code CompactBoundaryMessage.newBoundaryId()} 取号；
     *  判别依据恒为 subtype，id 不承载语义，此处仅作本文件 fixture 的固定值）。 */
    private static final String BOUNDARY_ID = "1800000000000000001";
    /** 第二条 boundary 的 id（生产 partial-from：新 boundary 与 kept 段旧 boundary 各自
     *  {@code CompactBoundaryMessage.newBoundaryId()} 取雪花 → <b>两个不同 id</b>；此处不得复用 BOUNDARY_ID）。 */
    private static final String BOUNDARY_ID_ALT = "1800000000000000002";
    private static final String SUMMARY_ID = "summary-json-1";
    private static final String KEEP_ID = "msg-keep-1";

    private MessageService service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        // 本会话 DB 已有 1 行 = kept 段（messagesToKeep）；boundary/summary 均为新 id
        MessageRecord existing = new MessageRecord();
        existing.setId(KEEP_ID);
        existing.setSessionId(SESSION);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(existing));
        when(messageMapper.insert(any())).thenReturn(1);
        when(messageMapper.update(any())).thenReturn(1);
    }

    /** boundary（sessionId=null，对齐 CompactBoundaryMessage.toChatMessageDto）。 */
    private static ChatMessageDto boundary() {
        return boundary(BOUNDARY_ID);
    }

    /** boundary 指定 id（生产每个实例独立雪花 id → 两条 boundary 必为不同 id）。 */
    private static ChatMessageDto boundary(String id) {
        return new ChatMessageDto(
            id, null, Role.system, "system", "Conversation compacted", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, "compact_boundary");
    }

    /** 纯新行（id 不在 DB）· 供 seq 块并发用例批量造 boundary/summary 之外的普通行。 */
    private static ChatMessageDto newRow(String id) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, "assistant", "行-" + id, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null);
    }

    /** summary（sessionId=null + isCompactSummary=true，对齐 CompactConversation.buildCompactSummaryMessage）。 */
    private static ChatMessageDto summary() {
        return new ChatMessageDto(
            SUMMARY_ID, null, Role.user, "system", "摘要正文", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            null, "compact_summary");
    }

    /** kept 段（已在 DB，id 命中 existingIds）。 */
    private static ChatMessageDto keep() {
        return new ChatMessageDto(
            KEEP_ID, SESSION, Role.assistant, "assistant", "保留的尾段", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null);
    }

    @Test
    @DisplayName("不删任何行 + kept 零改写（不 UPDATE/不 INSERT）+ boundary/summary INSERT + 返回顺序 = 入参顺序")
    void appendOnly_dedupSkipKept_insertNew_neverDelete() {
        // GIVEN: CC buildPostCompactMessages 顺序 = boundary → summary → messagesToKeep
        List<ChatMessageDto> postCompact = List.of(boundary(), summary(), keep());

        // WHEN: compact 落库
        List<ChatMessageDto> out = service.appendPostCompactMessages(SESSION, postCompact);

        // THEN ① append-only 铁律：绝不删除任何行（replaceSessionMessages 的 DELETE 路径零调用）
        verify(messageMapper, never()).deleteByQuery(any());
        // 新行（boundary + summary）→ INSERT，且 id 保持入参 id
        ArgumentCaptor<MessageRecord> inserted = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(MessageRecord::getId)
            .as("新行 INSERT 且 id 保持（boundary/summary 为入参顺序）")
            .containsExactly(BOUNDARY_ID, SUMMARY_ID);
        assertThat(inserted.getAllValues()).extracting(MessageRecord::getSessionId)
            .as("compact DTO sessionId=null → 落库前落定为方法入参 sessionId（messages.session_id NOT NULL）")
            .containsOnly(SESSION);

        // THEN ② kept 段（已存在 id）→ 写侧零改写：既不 UPDATE 也不 INSERT（CC recordTranscript dedup 跳过）。
        //   这是 D4 解法1-精简 的核心不变量 —— RED：把 UPDATE seq 分支加回来 / 改成 INSERT 副本 → 本断言红。
        verify(messageMapper, never()).update(any());

        // THEN ③ seq 块只覆盖真正写入的新行：boundary < summary 且相邻（kept 不占号 → 块内无空洞）
        //   注：seq 已换雪花 long（非 1..N 递增），故只断言相对关系，不锁具体值。
        long boundarySeq = inserted.getAllValues().get(0).getSeq();
        long summarySeq = inserted.getAllValues().get(1).getSeq();
        assertThat(boundarySeq).as("boundary seq 为正（雪花号）").isPositive();
        assertThat(summarySeq).as("summary seq = boundary + 1（同一块、按数组序发号）")
            .isEqualTo(boundarySeq + 1);
        // 新行 created_at 仍由 nextCreatedAt 单调分配器取号（时间语义保留，供前端「X 分钟前」展示）
        assertThat(inserted.getAllValues()).extracting(MessageRecord::getCreatedAt).doesNotContainNull();

        // THEN ④ 返回 = 归一化列表（id 保持 + sessionId 落定），顺序 = 入参顺序（供 state.replaceMessages；
        //   内存数组仍是 boundary → summary → kept，即模型该看到的顺序 —— DB 位置由读侧重挂还原）
        assertThat(out).hasSize(3);
        assertThat(out).extracting(ChatMessageDto::id).containsExactly(BOUNDARY_ID, SUMMARY_ID, KEEP_ID);
        assertThat(out).extracting(ChatMessageDto::sessionId).containsOnly(SESSION);
    }

    @Test
    @DisplayName("[防御分支] 批内同 id 重复 → 第二次命中「已存在」被 dedup 跳过、不 PK 冲突、不 DELETE（生产已不走此路径）")
    void duplicateBoundaryId_dedupSkipped_noPkConflict() {
        // WHY（如实表述 · CLAUDE.md 规则十二 显式失败）：本用例建模的是<b>防御性</b>分支，不是生产场景。
        //   boundary id 现由 CompactBoundaryMessage.newBoundaryId() 每实例取雪花（唯一）→ 生产 partial-from
        //   不再产生「两条同 id boundary」。保留本用例是因为该分支仍必须存在：调用方误传同 id / 上游 id 复用
        //   时，它把「重复 id」退化到跳过而不是 PK 冲突崩库（改造前退化为 UPDATE 重挂位置）。
        // RED：去掉 knownIds 登记/判重（第二次同 id 仍走 INSERT）→ insert 次数 3（而非 2）→ 红。
        // GIVEN: 同一 boundary id 在批内出现两次
        List<ChatMessageDto> postCompact = List.of(boundary(), boundary(), summary());

        // WHEN
        List<ChatMessageDto> out = service.appendPostCompactMessages(SESSION, postCompact);

        // THEN: 第一次 boundary 为 INSERT（登记进 knownIds）→ 第二次同 id 命中「已存在」→ 零改写跳过，
        //       无 DELETE、无 PK 冲突；summary 为新 id → INSERT（共 2 次）
        verify(messageMapper, never()).deleteByQuery(any());
        verify(messageMapper, times(2)).insert(any());
        verify(messageMapper, never()).update(any());
        assertThat(out).hasSize(3);
        assertThat(out).extracting(ChatMessageDto::id).containsExactly(BOUNDARY_ID, BOUNDARY_ID, SUMMARY_ID);
    }

    @Test
    @DisplayName("[生产真实场景] 两个不同 id 的 boundary → 各自成行（两行 boundary）、无 UPDATE（append-only 不塌缩）")
    void distinctBoundaryIds_bothInserted_noUpdate() {
        // WHY（规则九）：生产 from 方向 partial-from 的批次里<b>确有两条 boundary</b> —— 新 boundary
        //   （buildPartialPostCompactMessages 头部）+ kept 段内的旧 boundary（BoundaryReader
        //   getMessagesAfterCompactBoundary 切片含边界本身 subList(boundaryIndex,..) → from 方向 keep
        //   保留旧 boundary），二者 id 由 newBoundaryId() 各自取雪花 → <b>必然不同</b>。
        //   append-only 的预期是「两条 boundary 各占一行（各占一个位置号）」，绝不能被 id 去重/塌缩成一行 ——
        //   塌缩会让旧 boundary 行被覆盖重挂 → 轨迹少一条边界、位置切片基准错位。
        //   前置条件：本用例 mock DB 只含 KEEP_ID → 两条 boundary 都按「新行」INSERT（共 3 次：2 boundary
        //   + 1 summary），0 次 UPDATE。（同一会话二次 compact 时旧 boundary 行已在 DB → 走 2a「重挂 UPDATE」
        //   分支，同样不会与其它行塌缩；该分支由 duplicateBoundaryId 用例覆盖。）
        // RED：把第二条 boundary 的 id 换成 BOUNDARY_ID（复用同 id）→ 第二次走 UPDATE →
        //      insert 次数 3→2、update 0→1 → 红。
        List<ChatMessageDto> postCompact = List.of(boundary(BOUNDARY_ID_ALT), boundary(), summary());

        List<ChatMessageDto> out = service.appendPostCompactMessages(SESSION, postCompact);

        verify(messageMapper, never()).deleteByQuery(any());
        // 两条 boundary（不同 id）+ 一条 summary = 3 次 INSERT、0 次 UPDATE（append-only：全部新增）
        ArgumentCaptor<MessageRecord> inserted = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(3)).insert(inserted.capture());
        verify(messageMapper, never()).update(any());
        assertThat(inserted.getAllValues()).extracting(MessageRecord::getId)
            .as("两条 boundary 各自成行（id 不复用 → 不塌缩），顺序 = 入参数组序")
            .containsExactly(BOUNDARY_ID_ALT, BOUNDARY_ID, SUMMARY_ID);
        assertThat(inserted.getAllValues()).extracting(MessageRecord::getSubtype)
            .as("两行 boundary + 一行 summary（append-only 两条 boundary 行并存）")
            .containsExactly("compact_boundary", "compact_boundary", "compact_summary");
        assertThat(out).extracting(ChatMessageDto::id)
            .containsExactly(BOUNDARY_ID_ALT, BOUNDARY_ID, SUMMARY_ID);
    }

    @Test
    @DisplayName("[生产真实形态·同会话二次 compact] 旧 boundary/kept 行已在 DB → 全部 dedup 跳过，只 INSERT 新 boundary+summary，仍不 DELETE")
    void secondCompactOnSameSession_existingRowsDedupSkipped_insertsOnlyNewRows() {
        // WHY（规则九 · 本用例与上一条的区别，两条都保留、各锁一条真实分支）：
        //   - distinctBoundaryIds_bothInserted_noUpdate = 「<b>新会话首次</b> compact」形态：mock DB 里
        //     没有任何 boundary 行 → 两条 boundary 都按新行 INSERT（2 INSERT + summary，0 UPDATE）。
        //   - 本用例 = 「<b>同一会话第二次</b> compact（partial from）」的真实形态：上一次 compact 落库的
        //     旧 boundary_A 行<b>仍在 DB</b>（knownIds 命中）→ 与 kept 行一样走「零改写 dedup 跳过」，
        //     只有新 boundary_B 与 summary 是 INSERT。批次顺序按生产 FROM 分支 =
        //     [新 boundary, ...kept(含旧 boundary), summary]（BoundaryReader 切片含边界本身
        //     subList(boundaryIndex,..)；CompactionResult FROM 分支 keep 在前、summary 在后）。
        //   [D4 解法1-精简] 改造前旧 boundary_A 与 kept 各行发一次 UPDATE seq（写侧重挂）；
        //     现在<b>写侧零改写</b> —— 它们留在原 seq 位置，模型视图由读侧按新 boundary 的
        //     preservedSegment 重挂还原（kept 区间 [head..tail] 覆盖它们）。
        // RED 条件：
        //   ① 把旧 boundary 从 mock DB 行集合里去掉（或把 boundary_A 的 id 改成未落库的新 id）→
        //      insert 2→3 → 红（破坏「已存在行必须 dedup 跳过、不得再插一行副本」的不变量）；
        //   ② 又对已存在行发写入（加回 UPDATE seq / 改成 INSERT 副本）→ never().update / insert 次数红；
        //   ③ 出现任何 DELETE（改回 replaceSessionMessages 删全表路径）→ never().deleteByQuery 红；
        //   ④ 块内发号不按数组序（如把 seqBlock[seqIdx++] 换成常量/乱序）→ 连续性断言红。
        MessageRecord keepRow = new MessageRecord();
        keepRow.setId(KEEP_ID);
        keepRow.setSessionId(SESSION);
        MessageRecord oldBoundaryRow = new MessageRecord();
        oldBoundaryRow.setId(BOUNDARY_ID);
        oldBoundaryRow.setSessionId(SESSION);
        // 本会话 DB 现有行 = kept 段 + 上一次 compact 落的旧 boundary_A
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(keepRow, oldBoundaryRow));

        List<ChatMessageDto> postCompact =
            List.of(boundary(BOUNDARY_ID_ALT), boundary(), keep(), summary());

        List<ChatMessageDto> out = service.appendPostCompactMessages(SESSION, postCompact);

        verify(messageMapper, never()).deleteByQuery(any());
        ArgumentCaptor<MessageRecord> inserted = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(inserted.capture());
        // 已存在的两条（旧 boundary_A + kept）一个字都不写 —— 写侧零改写（D4 解法1-精简核心不变量）
        verify(messageMapper, never()).update(any());

        assertThat(inserted.getAllValues()).extracting(MessageRecord::getId)
            .as("只有「新 boundary_B + summary」是新行（旧 boundary_A 与 kept 行已在 DB → 零改写跳过）")
            .containsExactly(BOUNDARY_ID_ALT, SUMMARY_ID);
        // 新行 seq 仍按数组序连续（KEEP 不取号 → 块内无空洞）
        long newBoundarySeq = inserted.getAllValues().get(0).getSeq();
        long newSummarySeq = inserted.getAllValues().get(1).getSeq();
        assertThat(newSummarySeq)
            .as("入参相邻的两个新行共用同一个 seq 块（结果 +1）；kept 不占号，故块内无空洞")
            .isEqualTo(newBoundarySeq + 1);
        assertThat(out).extracting(ChatMessageDto::id)
            .as("返回顺序 = 入参数组序（内存 state.replaceMessages 用之；DB 位置由读侧重挂还原）")
            .containsExactly(BOUNDARY_ID_ALT, BOUNDARY_ID, KEEP_ID, SUMMARY_ID);
    }

    @Test
    @DisplayName("[seq 块] seq 块只覆盖真正写入的新行（boundary/summary 相邻号）；kept 零改写不占号")
    void appendPostCompact_seqBlockCoversOnlyNewRows_noHole() {
        // WHY（规则九）：compact 新行（boundary/summary/附件/hook）的 seq 顺序即「下轮 boundary 切片后的
        //   模型上下文顺序」。若块内 seq 不连续（被并发实时落库插入），DB 序会变成 [boundary][并发行]
        //   [summary][...] → 切片后可能出现 tool_result 先于 tool_use（provider 配对校验失败）。
        //   本用例锁死「一个块、按数组序、连续发号」，并锁「kept 零改写后不消耗块内号段」（否则凭空
        //   制造 seq 空洞，块内连续性判据失效）。
        // RED：appendPostCompactMessages 改回循环内逐个 nextSeq（且存在并发取号）→ 见下方并发用例红；
        //      把 kept 也纳入取号 → summary 与 boundary 不再相邻 → 红；
        //      单纯改回逐个取号（无线程）时本用例仍绿 —— 并发不可打断由
        //      concurrentAppendCannotInterruptCompactSeqBlock 与 MessageServiceSeqBlockTest 钉。
        List<ChatMessageDto> postCompact = List.of(boundary(), summary(), keep());

        service.appendPostCompactMessages(SESSION, postCompact);

        ArgumentCaptor<MessageRecord> inserted = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(inserted.capture());
        verify(messageMapper, never()).update(any());

        long boundarySeq = inserted.getAllValues().get(0).getSeq();
        long summarySeq = inserted.getAllValues().get(1).getSeq();
        assertThat(summarySeq)
            .as("summary = boundary + 1（块内按数组序发号；kept 不取号 → 相邻）")
            .isEqualTo(boundarySeq + 1);
    }

    @Test
    @DisplayName("[seq 块·RED 核心] 并发实时落库取号下 compact 整块 seq 仍连续（无缺口）")
    void concurrentAppendCannotInterruptCompactSeqBlock() throws Exception {
        // WHY（规则九）：compact 落库（@Transactional）与实时落库（ChatService.persistAppendedMessage，
        //   持 ctx.lock）两把锁不同源；若整块 seq 逐个取号，并发 append 的号会插进块内 →
        //   下轮 DB(seq) 恢复序与内存视图错位 → tool_result 先于 tool_use（provider 配对校验失败）。
        // RED：appendPostCompactMessages 改回循环内逐个 nextSeq → 300 行里必出现 block[i] != block[i-1]+1 → 红。
        final int rows = 300;
        List<ChatMessageDto> postCompact = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            postCompact.add(newRow("msg-block-" + i));
        }
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread hammer = new Thread(() -> {
            while (!stop.get()) {
                service.nextSeq(SESSION); // 模拟并发实时落库取号（不触碰 mock mapper）
            }
        }, "append-hammer");
        hammer.setDaemon(true);
        hammer.start();
        try {
            quietMessageServiceDebug(() -> service.appendPostCompactMessages(SESSION, postCompact));
        } finally {
            stop.set(true);
            hammer.join(5_000);
        }

        ArgumentCaptor<MessageRecord> inserted = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(rows)).insert(inserted.capture());
        List<MessageRecord> records = inserted.getAllValues();
        for (int i = 1; i < records.size(); i++) {
            assertThat(records.get(i).getSeq())
                .as("第 %d 行的 seq 必须 = 前一行 + 1（块内连续；缺口 = 并发号插进 compact 块）", i)
                .isEqualTo(records.get(i - 1).getSeq() + 1);
        }
    }

    @Test
    @DisplayName("[V70] listRawForTranscript 读回 isCompactSummary/isVisibleInTranscriptOnly（TraceView compactSummaryAfter 依赖）")
    void listRawForTranscript_readsBackTranscriptFlags() {
        // WHY（CLAUDE.md 规则九）：compact 摘要正文挂载在 isCompactSummary=true 的 user 消息上
        //   （前端 TraceView.compactSummaryAfter 判据 = isCompactSummary===true）。V70 前该标志只在内存
        //   DTO 存在、DB 无列 → 重拉（GET /messages）读回恒 false → 轨迹视图摘要详情缺失。
        //   RED：toDto 硬编码 false → 断言红。
        MessageRecord summaryRec = new MessageRecord();
        summaryRec.setId(SUMMARY_ID);
        summaryRec.setSessionId(SESSION);
        summaryRec.setRole(Role.user.name());
        summaryRec.setContent("摘要正文");
        summaryRec.setCreatedAt(OffsetDateTime.now().toString());
        summaryRec.setSeq(1L);
        summaryRec.setIsCompactSummary(true);
        summaryRec.setIsVisibleInTranscriptOnly(true);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(summaryRec));

        List<ChatMessageDto> out = service.listRawForTranscript(SESSION);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).isCompactSummary())
            .as("is_compact_summary 列读回 true（TraceView.compactSummaryAfter: isCompactSummary===true）")
            .isTrue();
        assertThat(out.get(0).isVisibleInTranscriptOnly())
            .as("is_visible_in_transcript_only 列读回 true")
            .isTrue();
    }

    @Test
    @DisplayName("[V70] 存量旧行 is_compact_summary NULL → 读回 false（Boolean.TRUE.equals 容错）")
    void listRawForTranscript_nullFlags_readBackFalse() {
        MessageRecord legacy = new MessageRecord();
        legacy.setId("msg-legacy");
        legacy.setSessionId(SESSION);
        legacy.setRole(Role.user.name());
        legacy.setContent("旧行");
        legacy.setCreatedAt(OffsetDateTime.now().toString());
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(legacy));

        List<ChatMessageDto> out = service.listRawForTranscript(SESSION);

        assertThat(out.get(0).isCompactSummary()).isFalse();
        assertThat(out.get(0).isVisibleInTranscriptOnly()).isFalse();
    }

    /**
     * 并发用例期间把 {@code MessageService} 的日志抬到 WARN，用例后恢复。
     *
     * <p><b>WHY</b>：{@code src/test/resources/logback-test.xml} 给 {@code com.nexusai} 开了 DEBUG，
     * 而 hammer 线程以极高频率调 {@code nextSeq} → 每条一条 DEBUG 日志成为 I/O 瓶颈，主线程被日志写
     * 饿死（实测 300 行用例跑数分钟、日志涨到 GB 级），与断言正确性无关，纯噪声。
     * 只影响本用例窗口（结束后恢复为继承级别），不修改全局测试配置。
     */
    private static void quietMessageServiceDebug(Runnable body) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MessageService.class);
        ch.qos.logback.classic.Level prev = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            body.run();
        } finally {
            logger.setLevel(prev); // null = 恢复继承 logback-test.xml 的 com.nexusai=DEBUG
        }
    }
}
