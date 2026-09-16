package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G2] 子代理压缩产物落进**它自己的** transcript + 修「找链尾」· 端到端证据。
 *
 * <h2>WHY 本测试存在（验证意图，非行为）</h2>
 * <p>G1 之后子代理**会**压缩，但压缩产物（compact_boundary + summary）此前**哪儿都不留痕**：
 * 子代理只武装 {@code appendListener}（只覆盖 {@code appendMessage} 通道），压缩产物走
 * {@code AgentState.persistCompactedMessages} 独立通道 ⇒ 无人落盘 ⇒
 * {@code LlmAgentLoop.persistCompactedMessages} 恒走 fail-loud「无落库通道」ERROR，
 * 且下轮 resume 从转录读回**压缩前全量** ⇒ 压缩白做。
 *
 * <h2>本测试驱动的真实代码路径（⛔ 不是复刻实现）</h2>
 * <ol>
 *   <li>写入侧：{@link SubagentExecutor#compactTranscriptPersistListener}（生产 listener 本体）
 *       → {@link SubagentExecutor#recordMessageTranscript}（appendListener 同款落盘助手）
 *       → {@code AgentTranscript.recordSidechainTranscript}</li>
 *   <li>读取侧：{@link AgentTranscript#getAgentTranscript}（resume 走的就是它）</li>
 *   <li>boundary 夹具：{@link CompactBoundaryMessage#createCompactBoundaryMessage}（生产工厂）</li>
 * </ol>
 *
 * <h2>⛔ 为什么断言必须落在「读回的 leaf 是压缩后那条」</h2>
 * <p>光把 boundary 写进文件**不够**：boundary 的 {@code parentUuid=null}
 * （CC sessionStorage.ts:1391-1407 "correct: truncates --continue chain at compact boundary"）
 * ⇒ 文件里**有两条链头**。此时若 leaf 判据仍是「文件序首条」（旧 {@code findFirst()}），
 * 会挑中**压缩前**那条旧链尾 ⇒ resume 重建出压缩前全量 ⇒ 压缩照样白做。
 * 故本测试同时锁死两侧：<b>写得进</b>（断言 2/3）且<b>读得对</b>（断言 1）。
 */
@DisplayName("[G2] 子代理压缩产物落自己的 transcript（写入侧 + 读回 leaf 双证）")
class SubagentCompactTranscriptPersistG2Test {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "sess-g2-0001";
    private static final String AGENT_ID = "a0123456789abcdef";

    // ────────────────────────────────────────────────────────────────────────
    // 断言 1（本批主证据）: 压缩产物经生产 listener 落盘后，resume 读回的是**压缩后**链
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("压缩产物落盘后，getAgentTranscript 读回的链头是 compact_boundary（压缩后链），非压缩前旧链")
    void compactPersist_thenResume_readsPostCompactChain() {
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(5);
        // ── GIVEN: 压缩前的旧链已落转录（走生产写入助手 ⇒ 带 timestamp，与运行期同形）──
        List<ChatMessageDto> preCompact = List.of(
            msg("pre-1", Role.user, "压缩前用户消息", base),
            msg("pre-2", Role.assistant, "压缩前助手回复", base.plusSeconds(1)));
        for (ChatMessageDto m : preCompact) {
            SubagentExecutor.recordMessageTranscript(tmpDir, SESSION_ID, AGENT_ID, m, null);
        }
        // 已落转录的 id 预置（生产同款：appendListener 逐条登记 + 初始批预置）
        Set<String> recordedIds = ConcurrentHashMap.newKeySet();
        for (ChatMessageDto m : preCompact) {
            recordedIds.add(m.id());
        }
        AtomicReference<String> chainTail = new AtomicReference<>("pre-2");

        // ── WHEN: 生产 listener 收到 postCompact（boundary + summary）并落盘 ──
        ChatMessageDto boundary = CompactBoundaryMessage
            .createCompactBoundaryMessage("auto", 180_000, "pre-2", null, 2)
            .toChatMessageDto();
        ChatMessageDto summary = msg("post-summary", Role.user, "<summary>压缩摘要</summary>",
            OffsetDateTime.now().plusSeconds(2)).withIsCompactSummary(true);
        List<ChatMessageDto> postCompact = List.of(boundary, summary);

        var listener = SubagentExecutor.compactTranscriptPersistListener(
            tmpDir, SESSION_ID, AGENT_ID, recordedIds, chainTail);
        List<ChatMessageDto> returned = listener.apply(postCompact);

        // ── THEN 0: UnaryOperator 语义 —— 返回入参列表（内存据此替换为压缩后视图）──
        assertThat(returned).as("listener 必须返回入参列表（AgentState.persistCompactedMessages 契约）")
            .isSameAs(postCompact);

        // ── THEN 1（主断言）: resume 读回的 leaf 是**压缩后**那条 ──
        Optional<AgentTranscript.AgentTranscriptResult> read =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_ID);
        assertThat(read).as("转录必须可读回").isPresent();
        List<com.nexusai.application.agent.subagent.AgentMessage> chain = read.get().messages();
        assertThat(chain).as("压缩后链 = [boundary, summary]").isNotEmpty();
        assertThat(chain.get(0).content())
            .as("⛔ 链头必须是 compact_boundary（压缩后链）；若读回压缩前那条 ⇒ 压缩白做（本批要修的缺陷）")
            .isEqualTo(CompactBoundaryMessage.CONTENT_COMPACTED);
        assertThat(chain).as("压缩前的旧链必须被 boundary 截断（parentUuid=null），不得混入")
            .noneMatch(m -> "压缩前用户消息".equals(m.content()))
            .noneMatch(m -> "压缩前助手回复".equals(m.content()));
        assertThat(chain.get(chain.size() - 1).content())
            .as("链尾 = 摘要消息").isEqualTo("<summary>压缩摘要</summary>");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 断言 2: boundary 行的 parentUuid 必须是**显式 null**（新链头 = 截断旧链的关键）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("落到转录的 boundary 行: parentUuid 为显式 null + subtype/timestamp/compactMetadata/logicalParentUuid 齐备")
    void boundaryLine_hasNullParentUuidAndCompactFields() throws Exception {
        Set<String> recordedIds = ConcurrentHashMap.newKeySet();
        AtomicReference<String> chainTail = new AtomicReference<>("prev-uuid");
        ChatMessageDto boundary = CompactBoundaryMessage
            .createCompactBoundaryMessage("auto", 123_456, "prev-uuid", null, 7)
            .toChatMessageDto();

        SubagentExecutor.compactTranscriptPersistListener(
            tmpDir, SESSION_ID, AGENT_ID, recordedIds, chainTail).apply(List.of(boundary));

        JsonNode node = readOnlyLine();
        assertThat(node.hasNonNull("parentUuid"))
            .as("⛔ boundary 的 parentUuid 必须是显式 null（链头）—— 缺该键时 AgentTranscript."
                + "enrichTranscriptMessage 会把上一消息 uuid 回填进来 ⇒ 压缩后不成新链 ⇒ 读回压缩前全量")
            .isFalse();
        assertThat(node.get("parentUuid").isNull()).as("key 存在且值为 null").isTrue();
        assertThat(node.path("subtype").asText())
            .as("subtype 缺失 ⇒ 读侧认不出 boundary（messages.ts:4608）").isEqualTo("compact_boundary");
        assertThat(node.hasNonNull("timestamp")).as("timestamp 缺失 ⇒ 无法判链头先后").isTrue();
        assertThat(node.hasNonNull("compactMetadata")).as("compactMetadata 齐备（messages.ts:4540-4546）").isTrue();
        assertThat(node.path("logicalParentUuid").asText())
            .as("logicalParentUuid = 压缩前最后消息 uuid（messages.ts:4551-4553）").isEqualTo("prev-uuid");
        // 写完 boundary 后链尾必须移到 boundary（后续消息挂它）
        assertThat(chainTail.get()).as("boundary 写完后成为链尾").isEqualTo(boundary.id());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 断言 3: 去重 —— 转录已有的消息（含初始批）不得重复写入
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("去重: recordedIds 中已有的消息不重复落盘（初始批 map uuid 与 DTO id 两个来源都挡住）")
    void alreadyRecordedMessages_areNotWrittenTwice() {
        ChatMessageDto kept = msg("kept-1", Role.assistant, "已在转录里的消息", OffsetDateTime.now());
        Set<String> recordedIds = ConcurrentHashMap.newKeySet();
        recordedIds.add(kept.id());       // DTO id 来源（附录：convertToChatMessageDto 会新建 id）
        recordedIds.add("map-uuid-initial"); // 初始批 map uuid 来源（生产预置的第二路）

        ChatMessageDto fresh = msg("fresh-1", Role.user, "尚未落盘的消息", OffsetDateTime.now());

        SubagentExecutor.compactTranscriptPersistListener(
            tmpDir, SESSION_ID, AGENT_ID, recordedIds, new AtomicReference<>())
            .apply(List.of(kept, fresh));

        List<String> contents = readContents();
        assertThat(contents).as("只写「转录尚未有的」那条").containsExactly("尚未落盘的消息");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 断言 4: legacy 转录（无 timestamp）零回归 —— 仍取文件序首条 leaf
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy 转录（无 timestamp）: leaf 语义与改动前逐位等价（文件序首条），resume 不停摆")
    void legacyTranscript_withoutTimestamps_keepsFirstLeafSemantics() throws Exception {
        // 单链：a → b → c（旧格式，无 timestamp 键）
        writeRawLines(List.of(
            rawEntry("legacy-a", "第一条", null),
            rawEntry("legacy-b", "第二条", "legacy-a"),
            rawEntry("legacy-c", "第三条", "legacy-b")));

        Optional<AgentTranscript.AgentTranscriptResult> read =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_ID);

        assertThat(read).as("legacy 转录必须仍可读回（⛔ 不能因新增 timestamp 判据而整体返回 empty）")
            .isPresent();
        assertThat(read.get().messages()).extracting(
                com.nexusai.application.agent.subagent.AgentMessage::content)
            .as("无 timestamp 时保持旧 findFirst 语义 ⇒ 单链完整重建（链头→链尾）")
            .containsExactly("第一条", "第二条", "第三条");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 断言 5: 写入侧字段契约（chatMessageToMap）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatMessageToMap: boundary 写 subtype/timestamp/compactMetadata/logicalParentUuid；摘要写 isCompactSummary")
    void chatMessageToMap_writesCompactFields() {
        ChatMessageDto boundary = CompactBoundaryMessage
            .createCompactBoundaryMessage("manual", 999, "lp-uuid", null, 3)
            .toChatMessageDto();
        Map<String, Object> boundaryMap = SubagentExecutor.chatMessageToMap(boundary);
        assertThat(boundaryMap).containsKeys("subtype", "timestamp", "compactMetadata", "logicalParentUuid");
        assertThat(boundaryMap.get("subtype")).isEqualTo("compact_boundary");

        ChatMessageDto summary = msg("s1", Role.user, "摘要", OffsetDateTime.now()).withIsCompactSummary(true);
        assertThat(SubagentExecutor.chatMessageToMap(summary))
            .as("摘要消息标记 isCompactSummary（messages.ts:465/480）").containsEntry("isCompactSummary", true);

        ChatMessageDto plain = msg("p1", Role.user, "普通消息", OffsetDateTime.now());
        Map<String, Object> plainMap = SubagentExecutor.chatMessageToMap(plain);
        assertThat(plainMap).as("非压缩消息不落 subtype（读侧缺省 = CC undefined 同义）")
            .doesNotContainKey("subtype");
        assertThat(plainMap).as("普通消息也要有 timestamp（leaf 判先后依赖它）").containsKey("timestamp");
    }

    // ──── R4 补闭 · ① 武装动作（行为级）: 武装后 state 真的带上 listener，且产物落自己的转录 ────

    @Test
    @DisplayName("[R4] armCompactTranscriptPersist 武装后 state.isCompactPersistArmed() 为真；经 AgentState.persistCompactedMessages 收到 postCompact 时产物落子代理自己的转录")
    void armingSeam_armsListenerOnState_andProductsLandInOwnTranscript() {
        // GIVEN: 子代理 state（真类）+ 未武装
        AgentState state = new AgentState("sys", SESSION_ID, UUID.randomUUID());
        assertThat(state.isCompactPersistArmed())
            .as("前置：未武装时判据必须为假（否则本用例无鉴别力）").isFalse();

        Set<String> recordedIds = ConcurrentHashMap.newKeySet();
        AtomicReference<String> chainTail = new AtomicReference<>("pre-1");

        // WHEN: 武装（生产接线点调用的就是这个方法）
        SubagentExecutor.armCompactTranscriptPersist(
            state, tmpDir, SESSION_ID, AGENT_ID, recordedIds, chainTail);

        // THEN 1: 武装动作真的落到 state 上（= LlmAgentLoop.persistCompactedMessages 的 fail-loud 判据）
        assertThat(state.isCompactPersistArmed())
            .as("⛔ 未武装 ⇒ LlmAgentLoop.persistCompactedMessages 走 fail-loud「无落库通道」ERROR，"
                + "且压缩产物哪儿都不留痕 ⇒ 压缩白做（本批要修的缺陷）")
            .isTrue();

        // THEN 2: 经**真实生产派发面** AgentState.persistCompactedMessages 投递 postCompact
        ChatMessageDto boundary = CompactBoundaryMessage
            .createCompactBoundaryMessage("auto", 100, "pre-1", null, 1).toChatMessageDto();
        ChatMessageDto summary = msg("s-post", Role.user, "<summary>摘要</summary>",
            OffsetDateTime.now().plusSeconds(2)).withIsCompactSummary(true);
        List<ChatMessageDto> postCompact = List.of(boundary, summary);
        List<ChatMessageDto> dispatched = state.persistCompactedMessages(postCompact);

        assertThat(dispatched).as("UnaryOperator 契约：返回入参列表（内存据此替换为压缩后视图）")
            .isSameAs(postCompact);

        // THEN 3: 产物真的落进**子代理自己的**转录，且 resume 读回压缩后链
        Optional<AgentTranscript.AgentTranscriptResult> read =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_ID);
        assertThat(read).as("压缩产物必须落进子代理自己的 transcript").isPresent();
        assertThat(read.get().messages().get(0).content())
            .as("链头 = compact_boundary（压缩后链）").isEqualTo(CompactBoundaryMessage.CONTENT_COMPACTED);
    }

    // ──── R4 补闭 · ② 接线（源级守卫）: 武装点仍是被调用的直接语句，且未被禁用 ────

    /**
     * ⚠️ <b>源级守卫</b>（本仓对 {@code SubagentExecutor} 接线层的既有标准，
     * 先例 {@code SubagentIdentityProducerTest.executeStreaming_stampsIdentityFromSingleDerivationPoint}）。
     *
     * <p><b>守护范围（明确限定）</b>：
     * <ol>
     *   <li>全文件**只有一处** {@code armCompactTranscriptPersist(state, ...)} 调用（武装点唯一，不新增旁路）；</li>
     *   <li>该调用是 {@code runSubagentQueryLoop} 的 {@code try} 体的**直接语句**（括号深度恰为 try 体深度）
     *       ⇒ 被 {@code if (false) { ... }} 之类**包裹即红**（这是本用例存在的直接理由：R4 的接线层变异）；</li>
     *   <li>实参来自 {@code sessionDir / sessionId / agentIdHex / recordedIds / currentParentUuid}
     *       这 5 个就地绑定量（不得硬编码或另起来源）。</li>
     * </ol>
     * <p><b>⛔ 它不守什么</b>：不守这些实参的**运行时取值**正确性，也不守 listener 的落盘行为
     * （前者由 {@code runSubagentQueryLoop} 的绑定关系结构性保证，后者由本类其余 6 个用例行为级覆盖）。
     * <p><b>为什么只能源级</b>：{@code runSubagentQueryLoop} 有 ~19 步重依赖（会话目录 / transcript /
     * MCP / 任务登记 / 摘要服务 / LLM 循环），单测到不了该点（本仓既有记载
     * {@code SubagentExecutorInvokedSkillCleanupTest:22}）—— 故「武装动作」下沉到
     * {@link SubagentExecutor#armCompactTranscriptPersist} 做行为级断言，把「有没有被调用」压到本守卫。
     */
    @Test
    @DisplayName("[R4] 接线源级守卫：runSubagentQueryLoop 仍直接调用 armCompactTranscriptPersist（未被删/未被包裹禁用）")
    void armingCallSite_isDirectStatementInRunSubagentQueryLoop() throws Exception {
        Path src = Path.of("src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java");
        // ⛔ 必须剥注释与字符串：注释里的一行假文本/日志占位符 {} 都能让守卫假绿或错判
        //    （先例 SubagentIdentityProducerTest.stripComments 的「实测到的假绿教训」）。
        String text = stripCommentsAndStrings(Files.readString(src, StandardCharsets.UTF_8));

        // ① 武装点唯一（调用形如 armCompactTranscriptPersist(state, ...)，与声明区分）
        int callSites = text.split("armCompactTranscriptPersist\\(state,", -1).length - 1;
        assertThat(callSites)
            .as("武装点必须唯一（新增第二个武装点 = 绕过本批落盘语义，须显式评审）").isEqualTo(1);
        assertThat(text).as("武装 seam 本体必须存在")
            .contains("static void armCompactTranscriptPersist(");

        int call = text.indexOf("armCompactTranscriptPersist(state,");

        // ② 结构性：用**括号栈**定位包裹该调用的块，并断言它是 try 体
        //    ⇒ 若被 if (false) { … } 包裹，最近未闭合 { 变成 if 块的 { ⇒ 本断言即红（R4 接线层变异）。
        java.util.Deque<Integer> braces = new java.util.ArrayDeque<>();
        for (int i = 0; i < call; i++) {
            char c = text.charAt(i);
            if (c == '{') {
                braces.push(i);
            } else if (c == '}') {
                assertThat(braces).as("括号必须平衡（源级守卫前提）").isNotEmpty();
                braces.pop();
            }
        }
        assertThat(braces).as("调用点必须位于某个块内（方法体/try 体）").isNotEmpty();
        int enclosingBrace = braces.peek();
        String introducer = text.substring(0, enclosingBrace);
        introducer = introducer.substring(introducer.lastIndexOf('}') + 1).strip();
        assertThat(introducer)
            .as("⛔ 武装调用必须直接位于 try 体内（被 if (false) { … } 之类包裹即红 —— R4 接线层变异）"
                + "，实际包裹块引导词 = 「" + introducer + "」")
            .endsWith("try");

        // ③ 调用点必须是**独立语句的起始位置**（非「单语句体」形态）。
        //    WHY 必需（实测到的绕过）：`if (false) stmt;`（**不带花括号**）不产生新的 `{`
        //    ⇒ 上面的括号栈判据仍看到 try 体 ⇒ 假绿。而 if/for/while/switch 的单语句体
        //    必然紧跟在 `)` 之后 ⇒ 判「前一个非空白字符不得是 `)`」即可堵住该形态
        //    （且对条件写法不敏感：`if (skip)` / `if (1 == 2)` 同样以 `)` 结尾）。
        //    合法形态的前一个非空白字符 ∈ {`{`（块首）, `;` / `}`（上一条语句之后）}。
        int back = call - 1;
        while (back >= 0 && Character.isWhitespace(text.charAt(back))) {
            back--;
        }
        char prevNonBlank = text.charAt(back);
        assertThat(prevNonBlank == '{' || prevNonBlank == ';' || prevNonBlank == '}')
            .as("⛔ 武装调用必须是**独立语句**（前一个非空白字符 ∈ { '{' , ';' , '}' }）；"
                + "实际 = 「" + prevNonBlank + "」 —— 为 ')' 即说明它被当成了 if/for/while/switch 的"
                + "单语句体（`if (false) arm…;` 这类**不带花括号**的禁用写法，实测可绕过括号栈判据）")
            .isTrue();

        // ④ 调用点必须是以 `;` 收尾的完整语句（与 ③ 互补：③ 守「从哪开始」，④ 守「到哪结束」）
        int semi = text.indexOf(';', call);
        assertThat(semi).as("武装调用必须以 `;` 收尾（独立语句）").isGreaterThan(call);
        String stmt = text.substring(call, semi);
        assertThat(stmt)
            .as("武装实参必须是就地绑定量，不得硬编码或另起来源")
            .contains("state,").contains("sessionDir").contains("agentIdHex")
            .contains("recordedIds").contains("currentParentUuid");
        assertThat(stmt)
            .as("回归护栏：不得退回 null 武装（等于不落盘）")
            .doesNotContain("setCompactPersistListener(null")
            .doesNotContain("armCompactTranscriptPersist(null");
    }

    /** 去注释与字符串字面量后再做源级断言（先例：SubagentIdentityProducerTest.stripComments 的假绿教训）。 */
    private static String stripCommentsAndStrings(String text) {
        // 字符串必须在注释之前剥离（注释符可能落在字符串里），但注释里也可能含引号 ——
        // 故：先剥块注释/行注释，再剥字符串（顺序与先例一致，字符串内出现 // 的概率极低且不影响括号计数）。
        String noComments = text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
        return noComments.replaceAll("(?s)\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    // ───────────────────────────────────────── fixtures ─────────────────────────────────────────

    private static ChatMessageDto msg(String id, Role role, String content, OffsetDateTime at) {
        return new ChatMessageDto(
            id, SESSION_ID, role, role.name().toLowerCase(), content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", at,
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }

    private Path transcriptPath() {
        return AgentTranscript.getTranscriptPath(tmpDir, SESSION_ID, AGENT_ID);
    }

    /** 读回转录文件里**唯一**一行并解析（单条落盘场景）。 */
    private JsonNode readOnlyLine() throws Exception {
        List<String> lines = Files.readAllLines(transcriptPath());
        assertThat(lines).as("应恰好落一条").hasSize(1);
        return MAPPER.readTree(lines.get(0));
    }

    private List<String> readContents() {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(transcriptPath())) {
                if (!line.isBlank()) {
                    out.add(MAPPER.readTree(line).path("content").asText());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("读转录失败: " + e.getMessage(), e);
        }
        return out;
    }

    /** legacy 转录行（无 timestamp 键）· 对齐改动前写入形态。 */
    private Map<String, Object> rawEntry(String uuid, String content, String parentUuid) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", content);
        m.put("agentId", AGENT_ID);
        m.put("isSidechain", true);
        m.put("uuid", uuid);
        if (parentUuid != null) {
            m.put("parentUuid", parentUuid);
        }
        return m;
    }

    private void writeRawLines(List<Map<String, Object>> maps) throws Exception {
        Path path = transcriptPath();
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : maps) {
            sb.append(MAPPER.writeValueAsString(m)).append("\n");
        }
        Files.writeString(path, sb.toString());
    }

    @SuppressWarnings("unused")
    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
