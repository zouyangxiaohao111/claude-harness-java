package com.nexusai.infra.llm;

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
 * <p>逐字段比对 {@code toSdkMessage} / {@code toOpenAiSdkTool} 后，同类折叠只有<b>两处</b>：
 * {@code arguments}（本类 ①②③）与工具的顶层 {@code type}
 * （{@link #toolTopLevelType_absentVsExplicitNull_pairwiseDistinguishable()}）。
 * 其余「投影比 wire 宽」的差异（tool 消息缺 {@code toolCallId}、assistant 的空白
 * {@code id}/{@code name}、system 角色占位等）属<b>有意的超集</b>（类 javadoc「口径一」），
 * 方向只会更严，不产生假阴性，本批不动。
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
}
