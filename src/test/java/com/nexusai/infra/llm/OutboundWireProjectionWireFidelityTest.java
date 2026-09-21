package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.ChatCompletionMessageToolCall;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.test.support.OutboundRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[H2] 线级投影必须与 wire「逐字同构」</b>：{@code ToolCallDto.arguments} 的 {@code null} 与
 * {@code ""} <b>不得折叠</b>。
 *
 * <h2>被修的缺陷（复验抓到的真缺口 · 先自己读码复验）</h2>
 * <p>{@code OpenAiSdkProvider.toSdkMessage} 的 assistant 分支写的是
 * <pre>{@code .arguments(tc.arguments() == null ? "{}" : tc.arguments())}</pre>
 * ⇒ wire 上 {@code null} 落 <b>{@code "{}"}</b>、{@code ""} 原样落 <b>{@code ""}</b> —— <b>两者字节不同</b>。
 * 而旧投影把 {@code null} 与 {@code ""} <b>都</b>映射为 {@code ""}：
 * <ul>
 *   <li>{@code null ↔ ""} 跨 run 变化时：<b>wire 字节变了而探针指纹不变</b> = <b>假阴性</b>
 *       （真实漂移被判绿，前缀缓存排查会指向错误结论）—— 见 {@link #argumentsNull_vs_emptyString_differOnWire()}；</li>
 *   <li>{@code null ↔ "{}"} 跨 run 变化时：wire 字节<b>不变</b>而投影不同 = 假阳性（漂移误报）
 *       —— 见 {@link #argumentsNull_vs_wireFoldString_sameOnWire()}。</li>
 * </ul>
 *
 * <h2>⭐ 本类如何验证「意图」而不只是「行为」（规则 9）</h2>
 * <p>判据的唯一价值是「与 wire 同构」，故本类不自己复述 wire 规则，而是<b>直接调用生产转换点</b>
 * {@code OpenAiSdkProvider.toSdkMessage}（同包可见），断言 <b>SDK 对象上真正要被序列化的那个字段</b>
 * （{@code ChatCompletionMessageToolCall.Function.arguments()}）：
 * <ol>
 *   <li>wire 相同 ⇒ 投影与指纹必须相同（挡假阳性）；</li>
 *   <li>wire 不同 ⇒ 投影与指纹必须不同（挡假阴性 —— 本批缺口）；</li>
 *   <li>三种互异的 wire 取值 ⇒ 三个指纹两两互异（挡住「干脆不看 arguments」这种更粗暴的变异）。</li>
 * </ol>
 *
 * <h2>登记：同一类（折叠）的其余点位</h2>
 * <p>逐字段比对 {@code toSdkMessage} / {@code toOpenAiSdkTool} 后，同类折叠只有<b>三处</b>：
 * {@code arguments}（本类 ①②③）与工具的顶层 {@code type}
 * （{@link #toolTopLevelType_absentVsExplicitNull_pairwiseDistinguishable()}），
 * 以及<b>⑥ 段</b>新钉的「按 role 分派」（旧实现对所有 role 恒带全部字段 = 对 user/assistant
 * 而言是 wire <b>超集</b> ⇒ 假阳性；本批已改为逐 role 只带 wire 真读的字段）。
 *
 * <p>⚠ 仍属<b>有意占位</b>（非超集，是「wire 上整条不存在」的忠实投影）：system 角色 →
 * {@code FILTERED_SYSTEM}；被 {@code toOpenAiSdkTool} 丢弃的畸形 tool → {@code DROPPED_TOOL}。
 * 二者保留「条数/顺序对齐」，不参与字段级判据。
 *
 * <p>纯 JUnit：⛔ 无 Spring / 无 {@code @SpringBootTest} / 无真 API / 无真 DB
 * （{@code projectMessage} / {@code toSdkMessage} / {@code fingerprintHead} 均为纯函数）。
 */
@DisplayName("[H2] 线级投影 ↔ wire 同构：tool call arguments 的 null 与空串不得折叠")
class OutboundWireProjectionWireFidelityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-20T10:00:00+08:00");

    /** 出站 system 串（本类不判它，传 null 更省事：指纹里落 "-"）。 */
    private static final String SYS = null;

    // ═══════════ ① null 与 wire 折叠值 "{}"：wire 相同 ⇒ 投影/指纹必须相同 ═══════════

    @Test
    @DisplayName("arguments=null 与 arguments=\"{}\"：wire 逐字相同 ⇒ 投影与头部指纹必须相同")
    void argumentsNull_vs_wireFoldString_sameOnWire() {
        ChatMessageDto nullArgs = assistant(null);
        ChatMessageDto foldValue = assistant(OutboundWireProjection.WIRE_NULL_ARGUMENTS);

        assertThat(wireArguments(nullArgs))
            .as("wire 事实（前置条件，直接读生产转换点的 SDK 对象）：null 被 toSdkMessage 折叠为 \"{}\"")
            .isEqualTo("{}");
        assertThat(wireArguments(foldValue))
            .as("wire 事实（前置条件）：显式的 \"{}\" 原样上 wire ⇒ 与 null 的 wire 逐字相同")
            .isEqualTo(wireArguments(nullArgs));

        assertThat(OutboundWireProjection.projectMessage(nullArgs))
            .as("wire 相同 ⇒ 投影必须相同（旧实现把 null 投成空串 ⇒ 这里会假红：wire 没变却报漂移）")
            .isEqualTo(OutboundWireProjection.projectMessage(foldValue));
        assertThat(msg0Fingerprint(nullArgs))
            .as("头部探针指纹同理：wire 相同 ⇒ 指纹必须相同")
            .isEqualTo(msg0Fingerprint(foldValue));
    }

    // ═══════════ ② null 与 ""：wire 【不同】 ⇒ 投影/指纹必须【不同】（本批缺口） ═══════════

    @Test
    @DisplayName("arguments=null 与 arguments=\"\"：wire 【不同】 ⇒ 投影与头部指纹必须【不同】（假阴性缺口）")
    void argumentsNull_vs_emptyString_differOnWire() {
        ChatMessageDto nullArgs = assistant(null);
        ChatMessageDto emptyArgs = assistant("");

        assertThat(wireArguments(nullArgs))
            .as("wire 事实（前置条件）：null ⇒ \"{}\"")
            .isEqualTo("{}");
        assertThat(wireArguments(emptyArgs))
            .as("wire 事实（前置条件）：空串原样 ⇒ \"\"（与 \"{}\" 是【不同】的字节）")
            .isEmpty();

        assertThat(OutboundWireProjection.projectMessage(nullArgs))
            .as("★这正是缺口：wire 字节变了而指纹不变 = 假阴性（真实漂移被判绿）—— 投影必须分辨")
            .isNotEqualTo(OutboundWireProjection.projectMessage(emptyArgs));
        assertThat(msg0Fingerprint(nullArgs))
            .as("★头部探针必须同步分辨，否则探针把 null↔\"\" 的真实漂移判成绿")
            .isNotEqualTo(msg0Fingerprint(emptyArgs));
    }

    // ═══════════ ③ 保有分辨力：三种互异的 wire 取值 ⇒ 三个指纹两两互异 ═══════════

    @Test
    @DisplayName("arguments=\"\" / \"{}\" / \"{\\\"x\\\":1}\"：wire 三者互异 ⇒ 指纹两两互异")
    void threeDistinctWireArguments_threeDistinctFingerprints() {
        String empty = msg0Fingerprint(assistant(""));
        String foldValue = msg0Fingerprint(assistant("{}"));
        String payload = msg0Fingerprint(assistant("{\"x\":1}"));

        assertThat(empty).as("空串 vs \"{}\" 必须可分辨").isNotEqualTo(foldValue);
        assertThat(foldValue).as("\"{}\" vs 真载荷 必须可分辨（挡住「投影干脆不看 arguments」的变异）")
            .isNotEqualTo(payload);
        assertThat(empty).as("空串 vs 真载荷 必须可分辨").isNotEqualTo(payload);
    }

    // ═══════════ ④ 测试基建是否随投影一并分辨（⛔ 无第二实现可漂移） ═══════════

    @Test
    @DisplayName("测试基建 OutboundRequest 的判据随生产投影一并分辨（该路径只是转调，无独立实现）")
    void testSupportPath_discriminatesWithProductionProjection() {
        OutboundRequest nullArgs = new OutboundRequest(null, List.of(assistant(null)), null);
        OutboundRequest emptyArgs = new OutboundRequest(null, List.of(assistant("")), null);

        assertThat(nullArgs.wireMessages())
            .as("OutboundRequest 是纯转调（见其 javadoc「单一实现」）⇒ 投影修好后测试判据自动获得分辨力")
            .isNotEqualTo(emptyArgs.wireMessages());
    }

    // ═══════════ ⑤ 同类的第二处折叠：tools 顶层 type 的「缺失 vs 显式 null」 ═══════════

    @Test
    @DisplayName("tools 顶层 type：字段缺失 ⇒ \"function\"；显式 null ⇒ 原样落 null（⛔ 不得折叠成同一状态）")
    void toolTopLevelType_absentVsExplicitNull_pairwiseDistinguishable() {
        String absent = OutboundWireProjection.projectToolNode(toolWrapper(false), true);
        String explicitNull = OutboundWireProjection.projectToolNode(toolWrapper(true), true);

        assertThat(absent)
            .as("toOpenAiSdkTool 的 `has(\"type\") ? fromJsonNode(...) : from(\"function\")`：字段缺失才落 function")
            .contains("\"type\":\"function\"");
        assertThat(explicitNull)
            .as("字段存在时 wire 原样透传该节点 ⇒ 显式 null 必须投影为 null，⛔ 不得与「缺失」折叠（同类假阴性）")
            .contains("\"type\":null");
        assertThat(explicitNull)
            .as("两个不同的 wire 状态不得投影成同一串")
            .isNotEqualTo(absent);
    }

    // ═══════════ ⑥ 按 role 分派：投影字段集必须 = wire 对该 role 真读的字段集 ═══════════
    //
    //   ⭐ 本段治的病与 ①②③ 同类（投影 ≠ wire），但方向相反：①②③ 治「投影比 wire 窄 / 折叠过头」
    //   （假阴性），本段治「投影比 wire 宽」（假阳性 —— 投影变而 wire 没变）。
    //   ⛔ 判据不看「投影串里有什么」，而是拿【真 wire】（SDK 序列化）做分辨力对照（见 ⑦）。

    @Test
    @DisplayName("等价重建：四个 role 各造两份全新 DTO（新 id/时间戳）⇒ 投影逐字相同（挡假阳性）")
    void equivalentRebuild_allRoles_projectionIdentical() {
        List<JsonNode> blocks = List.of(textBlock("块文字"));
        for (Role role : Role.values()) {
            ChatMessageDto a = dtoOf(role, "正文", role == Role.tool ? "tc-1" : null,
                role == Role.tool ? "反馈" : null, blocks, role == Role.assistant ? toolCalls("tc-1", "Bash", "{}") : List.of());
            ChatMessageDto b = dtoOf(role, "正文", role == Role.tool ? "tc-1" : null,
                role == Role.tool ? "反馈" : null, blocks, role == Role.assistant ? toolCalls("tc-1", "Bash", "{}") : List.of());
            assertThat(b.id()).as("前置：两份 DTO 必须是不同实例、不同 id").isNotEqualTo(a.id());
            assertThat(OutboundWireProjection.projectMessage(b))
                .as("role=%s：只有客户端字段（id/createdAt）不同 ⇒ 线级投影必须逐字相同", role)
                .isEqualTo(OutboundWireProjection.projectMessage(a));
        }
    }

    @Test
    @DisplayName("user：改 acceptFeedback / toolCallId / toolCalls ⇒ 指纹不动（wire 的 user 分支从不读它们）")
    void user_nonWireFieldsDoNotMoveFingerprint() {
        ChatMessageDto base = dtoOf(Role.user, "问题", null, null, List.of(), List.of());
        ChatMessageDto withFeedback = dtoOf(Role.user, "问题", "tc-9", "接受反馈", List.of(), List.of());

        assertThat(msg0Fingerprint(withFeedback))
            .as("user 的 acceptFeedback/toolCallId/toolCalls 不进 wire ⇒ 改了不得改变指纹（否则假阳性）")
            .isEqualTo(msg0Fingerprint(base));
    }

    @Test
    @DisplayName("user + 可渲染 contentBlocks：改标量 content ⇒ 指纹不动（wire 丢弃它）；改块文字 ⇒ 指纹必变")
    void user_renderableBlocks_dropScalarContent() {
        List<JsonNode> blocks = List.of(textBlock("块甲"));
        ChatMessageDto keep = dtoOf(Role.user, "会被丢弃的正文 A", null, null, blocks, List.of());
        ChatMessageDto dropped = dtoOf(Role.user, "会被丢弃的正文 B", null, null, blocks, List.of());
        ChatMessageDto blockChanged = dtoOf(Role.user, "会被丢弃的正文 A", null, null, List.of(textBlock("块乙")), List.of());

        assertThat(msg0Fingerprint(dropped))
            .as("contentBlocks 能渲染成 parts 时 wire 走 contentOfArrayOfContentParts ⇒ 标量 content 被丢弃，改它不得改指纹")
            .isEqualTo(msg0Fingerprint(keep));
        assertThat(msg0Fingerprint(blockChanged))
            .as("块文字真的上 wire ⇒ 改它必须改指纹")
            .isNotEqualTo(msg0Fingerprint(keep));
    }

    @Test
    @DisplayName("user + 不可渲染 contentBlocks（document）：改块内容 ⇒ 指纹不动（wire 回落标量 content）")
    void user_nonRenderableBlocks_doNotMoveFingerprint() {
        List<JsonNode> pdfA = List.of(documentBlock("QUJDRA=="));
        List<JsonNode> pdfB = List.of(documentBlock("RUZHSA=="));
        ChatMessageDto a = dtoOf(Role.user, "正文", null, null, pdfA, List.of());
        ChatMessageDto b = dtoOf(Role.user, "正文", null, null, pdfB, List.of());

        assertThat(msg0Fingerprint(b))
            .as("document 块被 toSdkUserContentPart 丢弃、wire 回落标量 content ⇒ 改块数据不得改指纹")
            .isEqualTo(msg0Fingerprint(a));
    }

    @Test
    @DisplayName("assistant：改 acceptFeedback / toolCallId / contentBlocks ⇒ 指纹不动；改 content ⇒ 必变")
    void assistant_nonWireFieldsDoNotMoveFingerprint() {
        ChatMessageDto base = dtoOf(Role.assistant, "回答", null, null, List.of(),
            toolCalls("tc-1", "Bash", "{}"));
        ChatMessageDto nonWireFlipped = dtoOf(Role.assistant, "回答", "tc-zzz", "反馈", List.of(textBlock("块")),
            toolCalls("tc-1", "Bash", "{}"));
        ChatMessageDto contentFlipped = dtoOf(Role.assistant, "回答2", null, null, List.of(),
            toolCalls("tc-1", "Bash", "{}"));

        assertThat(msg0Fingerprint(nonWireFlipped))
            .as("assistant 分支从不读 acceptFeedback/toolCallId/contentBlocks ⇒ 改了不得改变指纹")
            .isEqualTo(msg0Fingerprint(base));
        assertThat(msg0Fingerprint(contentFlipped))
            .as("content 是 assistant 的 wire 字段 ⇒ 改它必须改指纹")
            .isNotEqualTo(msg0Fingerprint(base));
    }

    @Test
    @DisplayName("tool：改 toolCalls ⇒ 指纹不动；改 toolCallId / acceptFeedback ⇒ 必变（二者真上 wire）")
    void tool_roleFieldSet() {
        ChatMessageDto base = dtoOf(Role.tool, "工具输出", "tc-1", null, List.of(), toolCalls("x", "Other", "{}"));
        ChatMessageDto toolCallsFlipped = dtoOf(Role.tool, "工具输出", "tc-1", null, List.of(),
            toolCalls("y", "Bash", "{\"a\":1}"));
        ChatMessageDto idFlipped = dtoOf(Role.tool, "工具输出", "tc-2", null, List.of(), List.of());
        ChatMessageDto feedbackFlipped = dtoOf(Role.tool, "工具输出", "tc-1", "反馈", List.of(), List.of());

        assertThat(msg0Fingerprint(toolCallsFlipped))
            .as("tool 分支不读 toolCalls ⇒ 改了不得改变指纹")
            .isEqualTo(msg0Fingerprint(base));
        assertThat(msg0Fingerprint(idFlipped))
            .as("tool_call_id 是 tool 的 wire 字段 ⇒ 改它必须改指纹")
            .isNotEqualTo(msg0Fingerprint(base));
        assertThat(msg0Fingerprint(feedbackFlipped))
            .as("acceptFeedback 在 tool 分支真上 wire（独立 text part）⇒ 改它必须改指纹")
            .isNotEqualTo(msg0Fingerprint(base));
    }

    @Test
    @DisplayName("system：字段全被丢弃 ⇒ 任何改动都不改指纹（占位保条数对齐）")
    void systemRole_isFilteredPlaceholder() {
        ChatMessageDto a = dtoOf(Role.system, "A", null, null, List.of(), List.of());
        ChatMessageDto b = dtoOf(Role.system, "B", "tc-1", "反馈", List.of(textBlock("块")),
            toolCalls("tc-1", "Bash", "{}"));

        assertThat(OutboundWireProjection.projectMessage(b))
            .as("system 出站被 toSdkMessage 过滤 ⇒ 占位恒等")
            .isEqualTo(OutboundWireProjection.FILTERED_SYSTEM)
            .isEqualTo(OutboundWireProjection.projectMessage(a));
    }

    // ═══════════ ⑦ 分辨力对照真 wire：投影 ⟺ SDK 出站 JSON（不是「我以为的 wire」） ═══════════

    @Test
    @DisplayName("⭐ 逐形态：投影的分辨力必须 ⟺ 真 wire（SDK 序列化）的分辨力")
    void projectionDiscriminationMatchesSdkWire() {
        List<JsonNode> textA = List.of(textBlock("块甲"));

        // ① user：标量 content（真上 wire）
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "甲", null, null, List.of(), List.of())),
            List.of(dtoOf(Role.user, "乙", null, null, List.of(), List.of())), "user 标量 content");
        // ② user：可渲染块时标量 content 被丢弃
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "正文A", null, null, textA, List.of())),
            List.of(dtoOf(Role.user, "正文B", null, null, textA, List.of())), "user 可渲染块 → content 被丢弃");
        // ③ user：document 块（wire 丢弃该块）
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "正文", null, null, List.of(documentBlock("QUJDRA==")), List.of())),
            List.of(dtoOf(Role.user, "正文", null, null, List.of(documentBlock("RUZHSA==")), List.of())),
            "user document 块被丢弃");
        // ④ user：acceptFeedback 不进 wire
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "正文", null, null, List.of(), List.of())),
            List.of(dtoOf(Role.user, "正文", "tc-9", "反馈", List.of(), List.of())), "user 非 wire 字段");
        // ⑤ assistant：tool_calls.arguments
        assertSameDiscrimination(
            List.of(dtoOf(Role.assistant, "回答", null, null, List.of(), toolCalls("tc-1", "Bash", "{}"))),
            List.of(dtoOf(Role.assistant, "回答", null, null, List.of(), toolCalls("tc-1", "Bash", "{\"x\":1}"))),
            "assistant tool_calls.arguments");
        // ⑥ assistant：空白 id/name 的 tool call 被丢弃（两侧都该判「无变化」）
        assertSameDiscrimination(
            List.of(dtoOf(Role.assistant, "回答", null, null, List.of(), toolCalls("tc-1", "Bash", "{}"))),
            List.of(dtoOf(Role.assistant, "回答", null, null, List.of(),
                List.of(new ToolCallDto("  ", "Bash", "{}", null, null)))), "assistant 空白 id 的 tool call 被丢弃");
        // ⑦ tool：acceptFeedback 上 wire（owner assistant 前置以满足配对修复）
        assertSameDiscrimination(
            toolPair("tc-1", "工具输出", null, List.of()),
            toolPair("tc-1", "工具输出", "反馈", List.of()), "tool acceptFeedback");
        // ⑧ tool：text 块上 wire、非 text 块被跳
        assertSameDiscrimination(
            toolPair("tc-1", "工具输出", null, List.of(documentBlock("QUJDRA=="))),
            toolPair("tc-1", "工具输出", null, List.of(documentBlock("RUZHSA=="))), "tool 非 text 块被跳");
        // ⑨ tool：toolCalls 不进 wire
        assertSameDiscrimination(
            toolPair("tc-1", "工具输出", null, List.of()),
            List.of(toolOwner("tc-1"),
                dtoOf(Role.tool, "工具输出", "tc-1", null, List.of(), toolCalls("y", "Other", "{}"))),
            "tool toolCalls");
        // ⑩ user：contentBlocks 的【原始 JSON 形状】不进 wire，wire 只看渲染出的 part
        //    （resolveImageUrl 两分支：顶层 url / source.url → 同一个 image_url ⇒ wire 逐字相同）
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "正文", null, null,
                List.of(imageBlockFromSourceUrl("http://e/x.png")), List.of())),
            List.of(dtoOf(Role.user, "正文", null, null,
                List.of(imageBlockTopLevelUrl("http://e/x.png")), List.of())),
            "user image 块两种写法 → 同一 image_url");
        // ⑪ user：块里的非 wire 键（如 cache_control）不进 wire
        assertSameDiscrimination(
            List.of(dtoOf(Role.user, "正文", null, null, List.of(textBlock("甲")), List.of())),
            List.of(dtoOf(Role.user, "正文", null, null,
                List.of(textBlockWithExtraKey("甲", "cache_control")), List.of())),
            "user text 块的非 wire 键被忽略");
        // ⑫ tool：text 块的非 wire 键同理
        assertSameDiscrimination(
            toolPair("tc-1", "工具输出", null, List.of(textBlock("甲"))),
            toolPair("tc-1", "工具输出", null, List.of(textBlockWithExtraKey("甲", "cache_control"))),
            "tool text 块的非 wire 键被忽略");
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /**
     * wire 侧事实：{@code ChatCompletionMessageToolCall.Function.arguments()}
     * —— 即 {@code toSdkMessage} 产出、随后被 SDK 序列化进请求体的那个字段。
     * <p>⛔ 不复述 wire 规则：直接读生产转换点的产物（同包可见），
     * 故本类的判据锚在 wire 上而不是锚在「我以为的 wire」上。
     */
    private static String wireArguments(ChatMessageDto m) {
        List<ChatCompletionMessageToolCall> tcs =
            OpenAiSdkProvider.toSdkMessage(m).asAssistant().toolCalls().orElseThrow();
        assertThat(tcs).as("前置：toSdkMessage 必须真的产出 tool_call（否则本用例不可观测）").hasSize(1);
        return tcs.get(0).function().arguments();
    }

    /** 头部探针在 {@code messages[0]} 上的指纹（= 真实探针的 hash 输入，不是复制的一份口径）。 */
    private static String msg0Fingerprint(ChatMessageDto m) {
        return OpenAiSdkProvider.fingerprintHead(SYS, List.of(m), 1, null, true).msg0();
    }

    /**
     * assistant 消息 · 单条 tool call（{@code id}/{@code name} 非空 ⇒ 必进 wire，不受
     * {@code ToolResultPairingRepair} 剥离影响）。
     *
     * @param toolArguments {@code ToolCallDto.arguments} 的取值（含 {@code null} 这一支）
     */
    private static ChatMessageDto assistant(String toolArguments) {
        return new ChatMessageDto(
            "m-asst", "sess-x", Role.assistant, "a",
            "回答", null,
            List.of(new ToolCallDto("tc-1", "Bash", toolArguments, null, null)),
            null, null, null,
            "刚刚", T, null, null,
            null, List.of(), List.of(), null, false);
    }

    /**
     * 与 {@code ToolRegistry.toOpenAiToolsArray} 同形的 wrapper（key 序与投影无关：投影自建节点）。
     *
     * @param explicitNullType {@code true} = 顶层 {@code "type":null}（字段存在）；{@code false} = 字段缺失
     */
    private static ObjectNode toolWrapper(boolean explicitNullType) {
        ObjectNode wrapper = JSON.createObjectNode();
        if (explicitNullType) {
            wrapper.putNull("type");
        }
        ObjectNode fn = wrapper.putObject("function");
        fn.put("name", "Bash");
        fn.put("description", "d1");
        fn.putObject("parameters").put("type", "object");
        return wrapper;
    }

    // ─────────── ⑥⑦ 段脚手架：逐 role 消息 + 真 wire 分辨力对照 ───────────

    /** 逐 role 消息（19 参构造，末参 isMeta 后置字段按既有约定传默认值）。 */
    private static ChatMessageDto dtoOf(Role role, String content, String toolCallId, String acceptFeedback,
                                       List<?> contentBlocks, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), "sess-x", role, "a",
            content, null, toolCalls, null, null, null,
            "刚刚", T, toolCallId, null,
            acceptFeedback, contentBlocks, List.of(), null,
            false);
    }

    private static List<ToolCallDto> toolCalls(String id, String name, String arguments) {
        return List.of(new ToolCallDto(id, name, arguments, null, null));
    }

    private static JsonNode textBlock(String text) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    /** 真实形态的 document 块（{@code R32B9_OpenAiSdkProviderMultiModalTest:180} 同形）。 */
    private static JsonNode documentBlock(String base64) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "document");
        ObjectNode source = b.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", base64);
        return b;
    }

    /** image 块写法①（CC 风格）：{@code source.url}。 */
    private static JsonNode imageBlockFromSourceUrl(String url) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "image");
        b.putObject("source").put("type", "url").put("url", url);
        return b;
    }

    /** image 块写法②（旧/直连风格）：顶层 {@code url} —— {@code resolveImageUrl} 第一分支，与①同解。 */
    private static JsonNode imageBlockTopLevelUrl(String url) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "image");
        b.put("url", url);
        return b;
    }

    /** text 块 + 一个 wire 从不读的键（如 {@code cache_control}）⇒ wire 字节与纯 text 块逐字相同。 */
    private static JsonNode textBlockWithExtraKey(String text, String extraKey) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "text");
        b.put("text", text);
        b.putObject(extraKey).put("type", "ephemeral");
        return b;
    }

    /** owning assistant（tool 消息协议上必须应答前置 tool_call，否则配对修复会剥离孤儿结果）。 */
    private static ChatMessageDto toolOwner(String toolCallId) {
        return dtoOf(Role.assistant, "", null, null, List.of(), toolCalls(toolCallId, "Bash", "{}"));
    }

    /** {@code [owning assistant, tool 结果]} 配对列表（真 wire 与投影两侧都经同一次配对修复）。 */
    private static List<ChatMessageDto> toolPair(String toolCallId, String content, String feedback,
                                                 List<?> blocks) {
        return List.of(toolOwner(toolCallId),
            dtoOf(Role.tool, content, toolCallId, feedback, blocks, List.of()));
    }

    /**
     * <b>真 wire 串</b>：{@code toSdkMessage} 的产物经 SDK 自己的 ObjectMapper 序列化 ——
     * 这是「DeepSeek 实际收到的 JSON」的最忠实可获得物（SDK 内部序列化规则不再由我复述）。
     */
    private static String sdkWireJson(List<ChatMessageDto> msgs) {
        try {
            return com.openai.core.ObjectMappers.jsonMapper()
                .writeValueAsString(OpenAiSdkProvider.buildSdkMessages(msgs));
        } catch (Exception e) {
            throw new AssertionError("SDK 出站序列化失败: " + e, e);
        }
    }

    /**
     * ⭐ 分辨力对照：<b>投影判「相同/不同」必须与真 wire（SDK JSON）判的一致</b>。
     * <p>这条比「断言某个字段在投影里」强得多：它不依赖我对 wire 规则的复述 ——
     * 投影若多带一个 wire 不读的字段，真 wire 判「相同」而投影判「不同」⇒ 立即红。
     */
    private static void assertSameDiscrimination(List<ChatMessageDto> a, List<ChatMessageDto> b, String what) {
        String wireA = sdkWireJson(a);
        String wireB = sdkWireJson(b);
        boolean projSame = OutboundWireProjection.projectMessages(a)
            .equals(OutboundWireProjection.projectMessages(b));
        assertThat(projSame)
            .as("⭐ 投影分辨力必须 ⟺ 真 wire 分辨力 · 形态=%s\nwireA=%s\nwireB=%s\nprojA=%s\nprojB=%s",
                what, wireA, wireB,
                OutboundWireProjection.projectMessages(a), OutboundWireProjection.projectMessages(b))
            .isEqualTo(wireA.equals(wireB));
    }
}
