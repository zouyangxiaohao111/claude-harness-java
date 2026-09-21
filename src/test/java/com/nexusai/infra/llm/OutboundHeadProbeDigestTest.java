package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.test.support.OutboundRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[F1 修复] 出站头部探针的 hash 输入口径</b>：必须等于<b>线级投影</b>，不是 DTO 的 {@code toString}。
 *
 * <h2>被修的缺陷（独立验证 #1 判 REFUTED 的那条）</h2>
 * <p>原探针 {@code OpenAiSdkProvider.logHeadProbeOutbound} 对 {@code messages[i]} 直接
 * {@code PromptCacheBreakDetection.probeDigest(ChatMessageDto)} ⇒ {@code stableString} 对非
 * Map/List 走 {@code data.toString()}。而 {@code ChatMessageDto} 是<b>未覆写 {@code toString} 的
 * record</b> ⇒ {@code id / createdAt / time / sessionId / author} 全部进 hash。
 * {@code messages[0]} 又是每轮用 {@code UUID.randomUUID()} + {@code OffsetDateTime.now()} 新造的
 * userContext 元消息（{@code AgentLoopContext.metaUserMessage} / {@code prependUserContext} +
 * {@code result.add(0, ...)}）⇒ <b>即使 wire 字节完全不变，msg0 hash 也每次请求都变</b>。
 * 结果是探针把判据指向最关心的那一格、且方向是错的（假阳性），
 * 而派单书步骤 1 的「否证形态」（三项跨 run 全同）<b>结构上永不可达</b>。
 *
 * <h2>⭐ 本类如何验证「意图」而不只是「行为」（规则 9）</h2>
 * <p>判据的唯一价值是<b>可分辨</b>：稳定 ⇒ 判绿；真漂移 ⇒ 判红。故本类必须有<b>两侧</b>：
 * <ol>
 *   <li><b>挡假阳性</b>：等价重建（每次全新 DTO + 新 id/时间戳）⇒ 三项指纹逐字相同；</li>
 *   <li><b>保有分辨力</b>：system / tools / {@code messages[0]} / {@code messages[2]} 各自改
 *       <b>1 个 wire 字节</b> ⇒ <b>只有</b>对应项翻转。</li>
 * </ol>
 * <p>只有 (1) 会退化成「什么都不比较」（全恒定的判据也满足 (1)）；只有 (2) 会保留原缺陷。
 * 两侧同时成立才证明口径正确。
 *
 * <p><b>副作用式证据</b>：{@link #rawToStringDiffers_butProjectionIdentical()} 直接对照
 * 「DTO toString 变了」与「线级投影没变」，把「旧口径为何不可用」钉成可执行的断言
 * （⛔ 不是注释里的说法）。
 *
 * <p>纯 JUnit：⛔ 无 Spring / 无 {@code @SpringBootTest} / 无真 API / 无真 DB
 * （{@code fingerprintHead} 是纯函数，不碰任何 IO）。
 */
@DisplayName("[F1] 出站头部探针口径 = 线级投影（挡假阳性 + 保有分辨力）")
class OutboundHeadProbeDigestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-20T10:00:00+08:00");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-09-20T10:00:07+08:00");

    private static final String SYS = "SYS-A\n\nPrimary working directory: /w";

    // ══════════════════ 正向 ① · 等价重建 ⇒ 三项指纹全同（挡假阳性） ══════════════════

    @Test
    @DisplayName("等价重建：每次全新 DTO + 新 id/时间戳 ⇒ sys/tools/msg0/msg1/msg2 指纹逐字相同")
    void equivalentRebuild_fingerprintsIdentical() {
        OpenAiSdkProvider.HeadProbeFingerprint a = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint b = fp(List.of(
            meta("CTX-A", T2), user("m1", "问题一", T2), assistant("m2", "回答一", "{}", T2)));

        assertThat(b.system()).as("system 串跨等价重建必须逐字相同").isEqualTo(a.system());
        assertThat(b.tools()).as("tools 线级投影跨等价重建必须逐字相同").isEqualTo(a.tools());
        assertThat(b.msg0()).as("msg0（每轮新造的 userContext 元消息）指纹必须跨等价重建逐字相同").isEqualTo(a.msg0());
        assertThat(b.msg1()).as("msg1 指纹必须跨等价重建逐字相同").isEqualTo(a.msg1());
        assertThat(b.msg2()).as("msg2 指纹必须跨等价重建逐字相同").isEqualTo(a.msg2());
        assertThat(a.msg0()).as("前置：msg0 必须真被 hash 到（不是 '-' 占位）").startsWith("h=");
        assertThat(a.sentCount()).as("条数原样透传").isEqualTo(3).isEqualTo(b.sentCount());
    }

    @Test
    @DisplayName("副作用式证据：同一逻辑请求 —— DTO 的 toString 变了，线级投影没变（旧口径为何不可用）")
    void rawToStringDiffers_butProjectionIdentical() {
        ChatMessageDto a = meta("CTX-A", T1);
        ChatMessageDto b = meta("CTX-A", T2);

        assertThat(b.toString())
            .as("ChatMessageDto 未覆写 toString ⇒ id/createdAt/time 进 debug 串（这正是旧探针 hash 的输入）")
            .isNotEqualTo(a.toString());
        assertThat(OutboundWireProjection.projectMessage(b))
            .as("线级投影只取上 wire 的字段 ⇒ 必须相同（id/createdAt/time/author 不进 wire）")
            .isEqualTo(OutboundWireProjection.projectMessage(a));
    }

    @Test
    @DisplayName("客户端专有字段全换（id/时间戳/isMeta）⇒ 三项指纹仍全同")
    void clientOnlyFieldFlip_nothingFlips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        // 只改不进 wire 的客户端字段：id（UUID）/ createdAt / time
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(List.of(
            meta(UUID.randomUUID().toString(), "CTX-A", T2),
            user(UUID.randomUUID().toString(), "问题一", T2),
            assistant(UUID.randomUUID().toString(), "回答一", "{}", T2)));

        assertThat(after).as("只改不进 wire 的字段 ⇒ 三项指纹必须一字不变").isEqualTo(base);
    }

    // ══════════════════ 正向 ② · 分辨力：改 1 个 wire 字节 ⇒ 只有对应项翻转 ══════════════════

    @Test
    @DisplayName("system 改 1 字节 ⇒ 只有 sys 翻转，tools/msg0/msg1/msg2 不动")
    void systemByteFlip_onlySystemFlips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(
            "SYS-B\n\nPrimary working directory: /w", messages(), tools("d1", false), true);

        assertThat(after.system()).isNotEqualTo(base.system());
        assertThat(after.tools()).isEqualTo(base.tools());
        assertThat(after.msg0()).isEqualTo(base.msg0());
        assertThat(after.msg1()).isEqualTo(base.msg1());
        assertThat(after.msg2()).isEqualTo(base.msg2());
    }

    @Test
    @DisplayName("tools 描述改 1 字节 ⇒ 只有 tools 翻转，sys/msg0/msg1/msg2 不动")
    void toolsByteFlip_onlyToolsFlips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(SYS, messages(), tools("d2", false), true);

        assertThat(after.tools()).isNotEqualTo(base.tools());
        assertThat(after.system()).isEqualTo(base.system());
        assertThat(after.msg0()).isEqualTo(base.msg0());
        assertThat(after.msg1()).isEqualTo(base.msg1());
        assertThat(after.msg2()).isEqualTo(base.msg2());
    }

    @Test
    @DisplayName("messages[0] 正文改 1 字节 ⇒ 只有 msg0 翻转（判据指向最关心的那一格，方向正确）")
    void msg0ByteFlip_onlyMsg0Flips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(List.of(
            meta("CTX-B", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));

        assertThat(after.msg0()).isNotEqualTo(base.msg0());
        assertThat(after.msg1()).isEqualTo(base.msg1());
        assertThat(after.msg2()).isEqualTo(base.msg2());
        assertThat(after.system()).isEqualTo(base.system());
        assertThat(after.tools()).isEqualTo(base.tools());
    }

    @Test
    @DisplayName("messages[2] 的 toolCalls.arguments 改 1 字节 ⇒ 只有 msg2 翻转（证明 toolCalls 真的进了口径）")
    void msg2ToolCallArgumentsFlip_onlyMsg2Flips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(List.of(
            meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{\"x\":1}", T1)));

        assertThat(after.msg2()).as("toolCalls(id,name,arguments) 是 wire 字段 ⇒ 必须翻转").isNotEqualTo(base.msg2());
        assertThat(after.msg0()).isEqualTo(base.msg0());
        assertThat(after.msg1()).isEqualTo(base.msg1());
    }

    @Test
    @DisplayName("messages[1] 的 toolCallId 改 1 字节 ⇒ 只有 msg1 翻转（证明 toolCallId 真的进了口径）")
    void msg1ToolCallIdFlip_onlyMsg1Flips() {
        OpenAiSdkProvider.HeadProbeFingerprint base = fp(List.of(
            meta("CTX-A", T1), toolResult("m1", "tr-1", T1), assistant("m2", "回答一", "{}", T1)));
        OpenAiSdkProvider.HeadProbeFingerprint after = fp(List.of(
            meta("CTX-A", T1), toolResult("m1", "tr-2", T1), assistant("m2", "回答一", "{}", T1)));

        assertThat(after.msg1()).isNotEqualTo(base.msg1());
        assertThat(after.msg0()).isEqualTo(base.msg0());
        assertThat(after.msg2()).isEqualTo(base.msg2());
    }

    // ═══════════ 第二个恒假阳性来源（本批一并修）：tools 源 JSON 里的非 wire 字段 ═══════════

    @Test
    @DisplayName("tools 的 defer_loading 翻转 ⇒ 指纹必须【不】动（该字段从不被 toOpenAiSdkTool 读取 ⇒ 不进 wire）")
    void toolsNonWireFieldFlip_nothingFlips() {
        OpenAiSdkProvider.HeadProbeFingerprint off = fp(SYS, messages(), tools("d1", false), true);
        OpenAiSdkProvider.HeadProbeFingerprint on = fp(SYS, messages(), tools("d1", true), true);

        assertThat(on.tools())
            .as("defer_loading 不在 toOpenAiSdkTool 的字段集里（⛔ 不是 wire）⇒ 翻转不得改变指纹，"
                + "否则又会把静态 wire 判成漂移")
            .isEqualTo(off.tools());
    }

    @Test
    @DisplayName("tools 的 strict 门控：关 = 省略（不上 wire），开 = 计入 ⇒ 门控翻转时指纹随之翻转")
    void strictModelGate_isPartOfWireFieldSet() {
        ArrayNode withStrict = tools("d1", false);
        ((ObjectNode) withStrict.get(0).get("function")).put("strict", true);

        OpenAiSdkProvider.HeadProbeFingerprint gateOff = fp(SYS, messages(), withStrict, false);
        OpenAiSdkProvider.HeadProbeFingerprint gateOn = fp(SYS, messages(), withStrict, true);

        assertThat(gateOn.tools())
            .as("门控通过时 strict 上 wire ⇒ 必须与门控关闭时不同")
            .isNotEqualTo(gateOff.tools());
    }

    @Test
    @DisplayName("被 toOpenAiSdkTool 丢弃的畸形 tool（缺 function）⇒ 投影为占位符，仍可分辨")
    void droppedTool_markerAndOrderPreserved() {
        ArrayNode malformed = JSON.createArrayNode();
        malformed.addObject().put("type", "function");   // 缺 function ⇒ toOpenAiSdkTool 返回 null

        assertThat(OutboundWireProjection.projectToolNode(malformed.get(0), true))
            .isEqualTo(OutboundWireProjection.DROPPED_TOOL);
        assertThat(OutboundWireProjection.projectTools(tools("d1", false), true))
            .isNotEqualTo(OutboundWireProjection.projectTools(malformed, true));
    }

    // ═══════════ 单一实现：测试基建与生产探针不得各写一套口径 ═══════════

    @Test
    @DisplayName("测试基建 OutboundRequest 转调生产投影（两处口径同源，不会再分叉）")
    void testSupportDelegatesToProductionProjection() {
        List<ChatMessageDto> msgs = messages();
        ArrayNode t = tools("d1", false);
        OutboundRequest req = new OutboundRequest(null, msgs, t);

        assertThat(req.toolsText()).isEqualTo(OutboundWireProjection.projectTools(t, true));
        assertThat(req.wireMessages()).isEqualTo(OutboundWireProjection.projectMessages(msgs));
    }

    @Test
    @DisplayName("缺消息 / 缺 tools ⇒ 占位 '-'（⛔ 不抛异常，探针在边界请求上不得静默失效）")
    void missingPieces_areDashPlaceholders() {
        OpenAiSdkProvider.HeadProbeFingerprint p =
            OpenAiSdkProvider.fingerprintHead(null, List.of(), 0, null, false);

        assertThat(p.system()).isEqualTo("-");
        assertThat(p.tools()).isEqualTo("-");
        assertThat(p.msg0()).isEqualTo("-");
        assertThat(p.msg1()).isEqualTo("-");
        assertThat(p.msg2()).isEqualTo("-");
        assertThat(p.sentCount()).isZero();
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /** 生产同形请求：默认 system 串 + 单工具数组 + 给定消息。 */
    private static OpenAiSdkProvider.HeadProbeFingerprint fp(List<ChatMessageDto> messages) {
        return fp(SYS, messages, tools("d1", false), true);
    }

    private static OpenAiSdkProvider.HeadProbeFingerprint fp(String system, List<ChatMessageDto> messages,
                                                             ArrayNode tools, boolean strictModelGate) {
        return OpenAiSdkProvider.fingerprintHead(system, messages, messages.size(), tools, strictModelGate);
    }

    private static List<ChatMessageDto> messages() {
        return List.of(meta("CTX-A", T1), user("m1", "问题一", T1), assistant("m2", "回答一", "{}", T1));
    }

    /**
     * userContext 元消息 · 与生产 {@code AgentLoopContext.metaUserMessage} 同形
     * （19 参构造器 + 末参 isMeta=true + 每次新 UUID/时间戳）。
     */
    private static ChatMessageDto meta(String content, OffsetDateTime createdAt) {
        return meta(UUID.randomUUID().toString(), content, createdAt);
    }

    private static ChatMessageDto meta(String id, String content, OffsetDateTime createdAt) {
        return new ChatMessageDto(
            id, null, Role.user, "system",
            content, null, List.of(), null, null, null,
            "刚刚", createdAt, null, null,
            null, List.of(), List.of(), null, true);
    }

    private static ChatMessageDto user(String id, String content, OffsetDateTime createdAt) {
        return new ChatMessageDto(
            id, "sess-x", Role.user, "u",
            content, null, List.of(), null, null, null,
            "刚刚", createdAt, null, null,
            null, List.of(), List.of(), null, false);
    }

    private static ChatMessageDto assistant(String id, String content, String toolArgs, OffsetDateTime createdAt) {
        return new ChatMessageDto(
            id, "sess-x", Role.assistant, "a",
            content, null,
            List.of(new ToolCallDto("tc-1", "Bash", toolArgs, null, null)),
            null, null, null,
            "刚刚", createdAt, null, null,
            null, List.of(), List.of(), null, false);
    }

    private static ChatMessageDto toolResult(String id, String toolCallId, OffsetDateTime createdAt) {
        return new ChatMessageDto(
            id, "sess-x", Role.tool, "a",
            "工具输出", null, List.of(), null, null, null,
            "刚刚", createdAt, toolCallId, null,
            null, List.of(), List.of(), null, false);
    }

    /** 与 {@code ToolRegistry.toOpenAiToolsArray} 同形的 tools 数组（wrapper 顶层可含 defer_loading）。 */
    private static ArrayNode tools(String description, boolean deferLoading) {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode wrapper = arr.addObject();
        wrapper.put("type", "function");
        if (deferLoading) {
            wrapper.put("defer_loading", true);
        }
        ObjectNode fn = wrapper.putObject("function");
        fn.put("name", "Bash");
        fn.put("description", description);
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        return arr;
    }
}
