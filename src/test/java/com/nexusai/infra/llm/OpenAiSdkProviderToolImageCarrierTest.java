package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[CU 修复] tool 结果里的 image 块（computer-use 截图）在 OpenAI 兼容通道不得被丢弃</b>。
 *
 * <h2>被修的缺陷（事实先于结论）</h2>
 * <p>openai-java 0.25.0 的 {@code ChatCompletionToolMessageParam.content} 数组<b>只收</b>
 * {@code ChatCompletionContentPartText}（javap 实证：形参被钉死为
 * {@code List<ChatCompletionContentPartText>}）⇒ tool 结果里的 image 块在 OpenAI 兼容通道
 * <b>结构上无处安放</b> ⇒ 旧实现「跳过」⇒ <b>CU 截图模型根本看不见</b>（Anthropic 通道不受影响：
 * 它把 image 内嵌在 {@code tool_result.content} 里）。
 *
 * <h2>修法（等价替代通道 · 非「把图塞进文本」）</h2>
 * <p>tool 消息本体形状<b>逐字不变</b>（文本照旧），image 块改由<b>紧随整段连续 tool 消息之后的
 * 一条独立 {@code role=user} 消息</b>承载（{@code content:[{type:image_url, image_url:{url:data:...}}]}）
 * —— 这是 OpenAI 兼容协议里送图给模型的<b>正规形态</b>（vision 输入），不是把二进制糊进字符串。
 *
 * <h2>⭐ 本类如何验证「意图」而不只是「行为」（规则 9）</h2>
 * <p>判据全部锚在<b>真 wire</b>（SDK 自己的 ObjectMapper 序列化出来的 JSON）上，而不是锚在
 * 「我以为的 wire」上：
 * <ol>
 *   <li><b>图没丢</b>：base64 数据必须出现在 OpenAI 兼容 wire 里（改动前整串 wire 里没有它）；</li>
 *   <li><b>形态是结构化的 image_url part</b>（⛔ 不是文本里的 base64 串）；</li>
 *   <li><b>纯文本 tool_result 的 wire 逐字未变</b>（回归闸：普通工具结果的字节与计费口径不受影响）；</li>
 *   <li><b>Anthropic 通道的 tool_result 形状未变</b>（另一条通道不受本修复影响）；</li>
 *   <li><b>承载消息不插在 tool 消息中间</b>（否则「assistant(tool_calls)→tool 紧邻」被破坏 ⇒ 端点 400）。</li>
 * </ol>
 *
 * <p>纯 JUnit：⛔ 无 Spring / 无 {@code @SpringBootTest} / 无真 API / 无真 DB。
 */
@DisplayName("[CU 修复] tool 结果 image 块：OpenAI 兼容通道经 role=user 承载消息送达模型")
class OpenAiSdkProviderToolImageCarrierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 真实形态的截图 base64（短但同构）。 */
    private static final String IMG_DATA = "AAECAwQFBgc=";
    private static final String IMG_URL = "data:image/jpeg;base64," + IMG_DATA;

    @Test
    @DisplayName("tool 结果含 image 块 ⇒ OpenAI 兼容 wire 里图没丢（image_url part · 结构化 · 非文本糊图）")
    void toolResultImage_openAiWire_carriesImageUrl() throws Exception {
        JsonNode wire = sdkWire(imageCase());

        // ① tool 消息本体：形状不变，且【不】被塞进 image part
        JsonNode toolMsg = wire.get(1);
        assertThat(toolMsg.get("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(toolMsg.toString())
            .as("SDK 0.25.0 的 tool content 数组收不了 image part ⇒ 图不进 tool 消息本体")
            .doesNotContain("image_url");

        // ② 承载消息：紧随 tool 段之后的一条 role=user 消息，带结构化 image_url part
        assertThat(wire.size()).as("assistant + tool + 承载消息 = 3 条").isEqualTo(3);
        JsonNode carrier = wire.get(2);
        assertThat(carrier.get("role").asText()).isEqualTo("user");
        JsonNode part = carrier.get("content").get(0);
        assertThat(part.get("type").asText())
            .as("必须是 OpenAI 兼容协议的 image_url part（⛔ 不是把 base64 糊进 text）")
            .isEqualTo("image_url");
        assertThat(part.get("image_url").get("url").asText())
            .as("media_type 与 base64 数据必须原样送达模型")
            .isEqualTo(IMG_URL);

        // ③ 关键断言：整条 wire 里真的带着截图数据（改动前 SDK 把它丢了 ⇒ 这里会红）
        assertThat(wire.toString())
            .as("★这就是缺陷本体：改动前 OpenAI 兼容 wire 里根本不存在这段 base64 ⇒ 模型看不见 CU 截图")
            .contains(IMG_DATA);
    }

    @Test
    @DisplayName("纯文本 tool 结果 ⇒ wire 与改动前逐字一致（零回归 · 无承载消息）")
    void plainTextToolResult_wireUnchanged() throws Exception {
        JsonNode wire = sdkWire(textCase());

        assertThat(wire.size()).as("纯文本 tool 结果不产生承载消息（仍 2 条）").isEqualTo(2);
        assertThat(wire.get(1).toString())
            .as("★wire 快照（改动前实测所得）：普通工具结果的字节必须逐字不变")
            .isEqualTo("{\"content\":\"工具输出\",\"role\":\"tool\",\"tool_call_id\":\"call_1\"}");
    }

    @Test
    @DisplayName("连续 tool 消息之后才冲刷承载消息 ⇒ assistant(tool_calls)→tool 的紧邻关系不被破坏")
    void carrier_placedAfterWholeToolRun() throws Exception {
        JsonNode wire = sdkWire(twoToolCase());

        assertThat(wire.size()).as("assistant + tool + tool + 承载消息 = 4 条").isEqualTo(4);
        assertThat(wire.get(1).get("role").asText()).isEqualTo("tool");
        assertThat(wire.get(1).get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(wire.get(2).get("role").asText())
            .as("⛔ 承载消息不得插在两条 tool 消息中间（端点要求 tool 紧跟 assistant.tool_calls）")
            .isEqualTo("tool");
        assertThat(wire.get(2).get("tool_call_id").asText()).isEqualTo("call_2");
        assertThat(wire.get(3).get("role").asText()).isEqualTo("user");
        assertThat(wire.get(3).get("content").size()).as("两段 tool 段的图合并到一条承载消息").isEqualTo(2);
    }

    @Test
    @DisplayName("Anthropic 通道的 tool_result 形状不变（image 仍内嵌在 tool_result.content 里）")
    void anthropicPath_toolResultShapeUnchanged() throws Exception {
        JsonNode wire = anthropicWire(imageCase());

        JsonNode toolResult = wire.get(1).get("content").get(0);
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        JsonNode img = toolResult.get("content").get(0);
        assertThat(img.get("type").asText()).isEqualTo("image");
        assertThat(img.get("source").get("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(img.get("source").get("data").asText()).isEqualTo(IMG_DATA);
    }

    @Test
    @DisplayName("线级投影分辨 tool 结果里的截图字节（截图每轮都变 ⇒ 头部探针不得判绿）")
    void projection_distinguishesToolImageBytes() {
        String a = OutboundWireProjection.projectMessage(toolMsg(IMG_DATA));
        String b = OutboundWireProjection.projectMessage(toolMsg("QkZHSElKS0w="));

        assertThat(a).isNotEqualTo(b);
        assertThat(a)
            .as("投影必须带上截图数据（否则「截图每次都不同」这一真实漂移在探针里恒判绿 = 假阴性）")
            .contains(IMG_URL);
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /** 生产 SDK wire：buildSdkMessages → SDK 自己的 ObjectMapper 序列化（= 端点收到的 JSON）。 */
    private static JsonNode sdkWire(List<ChatMessageDto> history) throws Exception {
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(OpenAiSdkProvider.buildSdkMessages(history)));
    }

    /** Anthropic 通道 wire：私有 buildSdkMessages 反射取（与生产同一实现）。 */
    private static JsonNode anthropicWire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(m.invoke(null, history)));
    }

    /** CU 截图形态：assistant 发 tool_call → tool 结果里只有一张图（payload 为空）。 */
    private static List<ChatMessageDto> imageCase() {
        return List.of(owner("call_1", "mcp__computer-use__screenshot"), toolMsg(IMG_DATA));
    }

    /** 纯文本 tool 结果形态。 */
    private static List<ChatMessageDto> textCase() {
        return List.of(owner("call_1", "mcp__computer-use__screenshot"),
            new ChatMessageDto(
                "tool-1", "sess-1", Role.tool, "tool", "工具输出",
                null, null, null, null, null, null, OffsetDateTime.now(), "call_1", null, null,
                List.of(), List.of()));
    }

    /** 一个 assistant 发两个 tool_call ⇒ 两条连续 tool 结果（各自带一张图）。 */
    private static List<ChatMessageDto> twoToolCase() {
        return List.of(
            new ChatMessageDto(
                "asst-1", "sess-1", Role.assistant, "assistant", "",
                null, List.of(
                    new ToolCallDto("call_1", "mcp__computer-use__screenshot", "{}", null, false),
                    new ToolCallDto("call_2", "mcp__computer-use__screenshot", "{}", null, false)),
                null, null, null, null, OffsetDateTime.now(), null, null, null,
                List.of(), List.of()),
            toolMsg("call_1", IMG_DATA),
            toolMsg("call_2", "QkZHSElKS0w="));
    }

    /** 发起 tool_call 的 assistant。 */
    private static ChatMessageDto owner(String toolCallId, String toolName) {
        return new ChatMessageDto(
            "asst-" + toolCallId, "sess-1", Role.assistant, "assistant", "",
            null, List.of(new ToolCallDto(toolCallId, toolName, "{}", null, false)),
            null, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of());
    }

    /** 带一张截图的 tool 结果（payload 空 · contentBlocks 只有 image 块 —— CU 截图真实形态）。 */
    private static ChatMessageDto toolMsg(String base64) {
        return toolMsg("call_1", base64);
    }

    private static ChatMessageDto toolMsg(String toolCallId, String base64) {
        List<JsonNode> blocks = new ArrayList<>();
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "image");
        ObjectNode source = b.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/jpeg");
        source.put("data", base64);
        blocks.add(b);
        return new ChatMessageDto(
            "tool-" + toolCallId, "sess-1", Role.tool, "tool", "",
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, null, null,
            blocks, List.of());
    }
}
