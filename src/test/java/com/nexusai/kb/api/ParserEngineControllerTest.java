package com.nexusai.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.app.parser.KbImageParser;
import com.nexusai.kb.app.parser.KbOfficeParser;
import com.nexusai.kb.app.parser.KbPaddleOcrVlParser;
import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.app.parser.KbPdfParser;
import com.nexusai.kb.app.parser.KbTextParser;
import com.nexusai.kb.app.parser.ParserEngineInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/system/parser-engines} 端点 · 可用性如实透出。
 *
 * <p><b>WHY</b>：前端「新建知识库 → 解析引擎」按 {@code Available}/{@code UnavailableReason}
 * 置灰并显示原因。若端点把不可用的引擎报成可用，用户选了之后解析必然失败，且前端没有任何提示 ——
 * 正是「假选项」这一类静默缺陷。故这里用<b>真实解析器实例</b>（不走 Spring、不 mock 掉可用性来源）
 * 从端点一路断言到序列化后的键名。
 *
 * <p><b>判据来源</b>：{@link KbPaddleOcrVlParser#isAvailable()} 读的配置键是
 * {@code nexusai.knowledge.ocr.paddleocr-vl.endpoint}；空 ⇒ 不可用，非空 ⇒ 可用。
 * 断言用真实配置键而非某个常量副本，避免「文案改了测试还绿」。
 */
@DisplayName("[KB] 解析引擎端点 · 可用性")
class ParserEngineControllerTest {

    /** 与解析器实现同源的配置键（⛔ 不要在测试里另立一份不同的键名）。 */
    private static final String ENDPOINT_PROPERTY = "nexusai.knowledge.ocr.paddleocr-vl.endpoint";

    private static final String PADDLE = "KbPaddleOcrVlParser";

    /**
     * 造真实的 PaddleOCR-VL 解析器。
     *
     * <p>它的 {@code (endpoint, apiKey)} 构造器是包内可见（生产走 Spring 字段注入），
     * 测试在 {@code com.nexusai.kb.api} 包下，故用反射调用它 —— 比反射改私有字段更稳，
     * 也与 Spring 注入后的状态等价。
     */
    private static KbPaddleOcrVlParser paddle(String endpoint) throws ReflectiveOperationException {
        Constructor<KbPaddleOcrVlParser> ctor =
            KbPaddleOcrVlParser.class.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(endpoint, "");
    }

    private static ParserEngineController controller(KbPaddleOcrVlParser paddle) {
        // 真实解析器集合：pdf / paddleocr_vl / image / office / text
        KbParserRegistry registry = new KbParserRegistry(List.of(
            new KbPdfParser(null), paddle, new KbImageParser(null),
            new KbOfficeParser(), new KbTextParser()));
        return new ParserEngineController(registry);
    }

    @SuppressWarnings("unchecked")
    private static List<ParserEngineInfo> data(Map<String, Object> payload) {
        return (List<ParserEngineInfo>) payload.get("data");
    }

    private static ParserEngineInfo engine(Map<String, Object> payload, String name) {
        return data(payload).stream()
            .filter(e -> e.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("响应里没有引擎 " + name));
    }

    @Test
    @DisplayName("① endpoint 未配置 ⇒ Available=false，Reason 写人话并含配置键")
    void unconfiguredPaddleIsReportedUnavailable() throws Exception {
        Map<String, Object> payload = controller(paddle("")).list();

        ParserEngineInfo paddle = engine(payload, PADDLE);
        assertThat(paddle.available()).isFalse();
        assertThat(paddle.unavailableReason())
            .as("原因必须是给用户看的人话，且指出缺哪个配置")
            .contains("PaddleOCR-VL")
            .contains("未配置")
            .contains(ENDPOINT_PROPERTY);

        // 其它引擎不受影响（避免「一条不可用把整份清单拖黑」）
        assertThat(data(payload))
            .filteredOn(e -> !e.name().equals(PADDLE))
            .allSatisfy(e -> {
                assertThat(e.available()).isTrue();
                assertThat(e.unavailableReason()).isEmpty();
            });
    }

    @Test
    @DisplayName("② endpoint 有值 ⇒ Available=true，Reason 为空")
    void configuredPaddleIsReportedAvailable() throws Exception {
        Map<String, Object> payload = controller(paddle("http://127.0.0.1:8080")).list();

        ParserEngineInfo paddle = engine(payload, PADDLE);
        assertThat(paddle.available()).isTrue();
        assertThat(paddle.unavailableReason()).isEmpty();
    }

    @Test
    @DisplayName("③ 未实现 SelfDescribingAvailability 的解析器 ⇒ Available=true、Reason 为空")
    void parserWithoutSelfDescribingAvailabilityIsAvailable() throws Exception {
        Map<String, Object> payload = controller(paddle("")).list();

        // KbTextParser / KbOfficeParser 未实现该接口 ⇒ 按既有语义视为可用
        assertThat(data(payload))
            .filteredOn(e -> e.name().equals("KbTextParser") || e.name().equals("KbOfficeParser"))
            .hasSize(2)
            .allSatisfy(e -> {
                assertThat(e.available()).isTrue();
                assertThat(e.unavailableReason()).isEmpty();
            });
    }

    @Test
    @DisplayName("④ 响应结构不变：success + data，元素键名仍是大写")
    void responseShapeAndUppercaseKeysArePreserved() throws Exception {
        Map<String, Object> payload = controller(paddle("")).list();

        assertThat(payload).containsEntry("success", true);
        assertThat(data(payload)).isNotEmpty();

        JsonNode array = new ObjectMapper().valueToTree(data(payload));
        JsonNode first = array.get(0);
        assertThat(first.fieldNames()).toIterable()
            .containsExactlyInAnyOrder(
                "Name", "Description", "FileTypes", "Available", "UnavailableReason");
        // 大写键必须逐字存在（前端按大写读；camelCase 会静默取到 undefined）
        assertThat(first.has("Name")).isTrue();
        assertThat(first.has("FileTypes")).isTrue();
        assertThat(first.has("Available")).isTrue();
        assertThat(first.has("UnavailableReason")).isTrue();
        assertThat(first.has("name")).isFalse();

        // 不可用引擎在序列化后的报文里同样是 false + 非空原因
        JsonNode paddleNode = null;
        for (JsonNode node : array) {
            if (PADDLE.equals(node.path("Name").asText())) {
                paddleNode = node;
                break;
            }
        }
        assertThat(paddleNode).as("响应里必须有 " + PADDLE).isNotNull();
        assertThat(paddleNode.path("Available").asBoolean()).isFalse();
        assertThat(paddleNode.path("UnavailableReason").asText()).contains("未配置");
    }
}
