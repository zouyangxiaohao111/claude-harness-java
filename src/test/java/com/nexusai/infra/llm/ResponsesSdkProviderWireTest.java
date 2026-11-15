package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.openai.models.responses.ResponseInputItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses 协议 <b>wire 形状</b>断言（T1–T8）· 不调真模型。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：
 * <ul>
 *   <li>T1/T2：图片在 Responses 是 {@code input_image} 且 {@code image_url} 为<b>裸字符串</b>、
 *       {@code detail} 必填 —— 与 Completions 的 {@code {url:...}} 对象形态相反，写错只能靠运行时 400 发现。</li>
 *   <li>T3/T4/T5：工具结果带图三态 —— 有图必须走 item 数组（Responses 独有能力）；
 *       纯文本退化字符串；模型不支持图时必须给非空占位，空数组会 400。</li>
 *   <li>T6：图与 {@code call_id} 同属一个 item，防"图片归属脱钩"。</li>
 *   <li>T7：system 走顶层 {@code instructions}，不在 {@code input[]}。</li>
 *   <li>T8：工具定义扁平，不套 {@code function} 外层。</li>
 * </ul>
 */
@DisplayName("[Responses] wire 形状 T1–T8")
class ResponsesSdkProviderWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String IMG_B64 =
        "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}}";
    private static final String IMG_URL =
        "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x/y.png\"}}";

    private static JsonNode img(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    /** T1 + T2：用户消息图片 → input_image；image_url 裸串；detail 必填。 */
    @Test
    void userImageIsBareStringWithDetail() throws Exception {
        ResponseInputItem item = ResponsesSdkProvider.buildUserItem(
            List.of(img(IMG_B64)), null, "auto", true);
        String json = JSON.writeValueAsString(item);

        assertThat(json).contains("\"input_image\"");
        assertThat(json).contains("\"image_url\":\"data:image/png;base64,QUJD\"");
        assertThat(json).doesNotContain("\"image_url\":{");
        assertThat(json).contains("\"detail\":\"auto\"");
    }

    /** T3：工具结果 文本+图 → output 为 item 数组（input_text + input_image）。 */
    @Test
    void toolResultWithTextAndImageUsesItemList() throws Exception {
        ResponseInputItem.FunctionCallOutput out =
            ResponsesSdkProvider.buildToolOutput(tool("file-body", img(IMG_B64)), "auto", true);
        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("\"input_text\"").contains("\"input_image\"");
        assertThat(json).contains("\"output\":[");
    }

    /** T4：工具结果纯文本 → output 退化为字符串，不带图。 */
    @Test
    void toolResultPlainTextIsString() throws Exception {
        ResponseInputItem.FunctionCallOutput out =
            ResponsesSdkProvider.buildToolOutput(tool("file-body", null), "auto", true);
        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("\"output\":\"file-body\"");
        assertThat(json).doesNotContain("\"input_image\"");
    }

    /** T5：只有图 + 模型不支持 → 非空占位字符串（空数组会 400）。 */
    @Test
    void toolResultImageOnlyWithUnsupportedModelFallsBackToPlaceholder() throws Exception {
        ResponseInputItem.FunctionCallOutput out =
            ResponsesSdkProvider.buildToolOutput(tool("", img(IMG_B64)), "auto", false);
        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("see attached image");
        assertThat(json).doesNotContain("\"input_image\"");
        assertThat(json).doesNotContain("\"output\":\"\"");
    }

    /** T6：图与 call_id 同属一个 item（归属不脱钩）。 */
    @Test
    void toolResultKeepsCallId() throws Exception {
        ResponseInputItem.FunctionCallOutput out =
            ResponsesSdkProvider.buildToolOutput(tool("x", img(IMG_URL)), "high", true);
        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("\"call_id\":\"call_1\"");
        assertThat(json).contains("\"detail\":\"high\"");
    }

    /** T7：systemPromptBlocks → instructions（多块空行 join，cacheScope 忽略）。 */
    @Test
    void systemPromptJoinsIntoInstructions() {
        String joined = ResponsesSdkProvider.joinSystem(List.of(
            new SystemPromptBlock("SYS-A", CacheScope.NULL),
            new SystemPromptBlock("SYS-B", CacheScope.NULL)));

        assertThat(joined).isEqualTo("SYS-A\n\nSYS-B");
    }

    /** T8：工具定义扁平 —— 有 name/parameters/strict，无 function 外层。 */
    @Test
    void toolsAreFlatWithoutFunctionWrapper() throws Exception {
        ArrayNode tools = JSON.createArrayNode();
        ObjectNode t = JSON.createObjectNode();
        t.put("type", "function");
        ObjectNode fn = JSON.createObjectNode();
        fn.put("name", "read_file");
        fn.put("description", "d");
        fn.set("parameters", JSON.readTree("{\"type\":\"object\",\"properties\":{}}"));
        t.set("function", fn);
        tools.add(t);

        String json = JSON.writeValueAsString(ResponsesSdkProvider.buildTools(tools));

        assertThat(json).contains("\"name\":\"read_file\"");
        assertThat(json).doesNotContain("\"function\":{");
    }

    /** 17 参兼容构造器造 tool 消息（toolCallId=call_1）。 */
    private static ChatMessageDto tool(String content, JsonNode image) {
        return new ChatMessageDto("id", "sess", Role.tool, "tool", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            "call_1", null, null, image == null ? List.of() : List.of(image), List.of());
    }
}
