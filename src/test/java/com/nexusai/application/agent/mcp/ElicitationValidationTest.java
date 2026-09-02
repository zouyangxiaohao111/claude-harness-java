package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.llm.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [R2-03 ✗-1] ElicitationValidation 移植测试（对齐 CC utils/mcp/elicitationValidation.ts 全链 :15-336）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 前端弹窗（待实现.md T-02）需要与 CC 一致的 elicitation
 * 输入校验语义（zod 等价消息文本、枚举/区间/格式门、NL 日期 LLM 解析回落）。本测试锁定
 * <b>真实校验语义</b>（非存在性断言）：消息字面量照抄 CC、number 区间 .0 格式、boolean
 * coerce、date-time 非 ISO 输入走 DateTimeParser 慢路径（X-1 0 消费方消除的 E3 证据）。
 */
class ElicitationValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ═══════════ 可配置响应的 mock LLM 查询（同 DateTimeParserCcContractTest StubQuery 模式）═══════════

    private static final class StubQuery implements DateTimeParser.DateTimeModelQuery {
        final AtomicInteger calls = new AtomicInteger();
        private final String response;
        StubQuery(String response) { this.response = response; }
        @Override
        public String query(String systemPrompt, String userPrompt, LlmProvider.ChatRequestOptions options) {
            calls.incrementAndGet();
            return response;
        }
    }

    private static ElicitationValidation validation(String llmResponse) {
        DateTimeParser parser = new DateTimeParser(new StubQuery(llmResponse));
        return new ElicitationValidation(parser);
    }

    private static JsonNode schema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ═══════════ 枚举（CC :43-47/:104-133）═══════════

    @Test
    @DisplayName("enum schema: 命中 → valid + value；未命中 → invalid（error 含 CC 枚举消息）")
    void enumSchema_matchesAndMisses() {
        JsonNode s = schema("{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\"]}");
        ElicitationValidation v = new ElicitationValidation();

        ElicitationValidation.ValidationResult hit = v.validateElicitationInput("medium", s);
        assertThat(hit.isValid()).isTrue();
        assertThat(hit.value()).isNotNull();
        assertThat(hit.value().asText()).isEqualTo("medium");

        ElicitationValidation.ValidationResult miss = v.validateElicitationInput("ultra", s);
        assertThat(miss.isValid()).isFalse();
        assertThat(miss.error()).isNotBlank();
    }

    @Test
    @DisplayName("oneOf schema: 枚举值取 const、标签取 title（CC :104-125）")
    void oneOfSchema_constTitleSemantics() {
        JsonNode s = schema(
            "{\"type\":\"string\",\"oneOf\":[{\"const\":\"a\",\"title\":\"Alpha\"},{\"const\":\"b\",\"title\":\"Beta\"}]}");
        assertThat(ElicitationValidation.getEnumValues(s)).containsExactly("a", "b");
        assertThat(ElicitationValidation.getEnumLabels(s)).containsExactly("Alpha", "Beta");
        assertThat(ElicitationValidation.getEnumLabel(s, "a")).isEqualTo("Alpha");
        assertThat(ElicitationValidation.getEnumLabel(s, "zzz")).as("未知值 → 原值（CC :130-133）").isEqualTo("zzz");
    }

    @Test
    @DisplayName("空 enum → 恒失败（CC z.never 等价，:138-140）")
    void emptyEnum_neverFails() {
        JsonNode s = schema("{\"type\":\"string\",\"enum\":[]}");
        ElicitationValidation.ValidationResult r =
            new ElicitationValidation().validateElicitationInput("x", s);
        assertThat(r.isValid()).isFalse();
    }

    @Test
    @DisplayName("multi-select enum: getMultiSelectValues/Labels/Label（CC :67-99）")
    void multiSelectEnum_helpers() {
        JsonNode legacy = schema("{\"type\":\"array\",\"items\":{\"enum\":[\"x\",\"y\"]}}");
        JsonNode anyOf = schema(
            "{\"type\":\"array\",\"items\":{\"anyOf\":[{\"const\":\"a\",\"title\":\"A\"},{\"const\":\"b\",\"title\":\"B\"}]}}");
        assertThat(ElicitationValidation.isMultiSelectEnumSchema(legacy)).isTrue();
        assertThat(ElicitationValidation.isMultiSelectEnumSchema(anyOf)).isTrue();
        assertThat(ElicitationValidation.isMultiSelectEnumSchema(schema("{\"type\":\"string\"}"))).isFalse();
        assertThat(ElicitationValidation.getMultiSelectValues(anyOf)).containsExactly("a", "b");
        assertThat(ElicitationValidation.getMultiSelectLabels(anyOf)).containsExactly("A", "B");
        assertThat(ElicitationValidation.getMultiSelectLabel(anyOf, "b")).isEqualTo("B");
        assertThat(ElicitationValidation.getMultiSelectValues(legacy)).containsExactly("x", "y");
    }

    // ═══════════ 字符串 min/max（CC :145-154 消息字面量）═══════════

    @Test
    @DisplayName("minLength/maxLength: 消息字面量照抄 CC（Must be at least/most N character(s)）")
    void stringLength_messages() {
        JsonNode s = schema("{\"type\":\"string\",\"minLength\":3,\"maxLength\":5}");
        ElicitationValidation.ValidationResult short_ =
            new ElicitationValidation().validateElicitationInput("ab", s);
        assertThat(short_.isValid()).isFalse();
        assertThat(short_.error()).isEqualTo("Must be at least 3 characters");

        ElicitationValidation.ValidationResult long_ =
            new ElicitationValidation().validateElicitationInput("abcdef", s);
        assertThat(long_.error()).isEqualTo("Must be at most 5 characters");

        ElicitationValidation.ValidationResult ok =
            new ElicitationValidation().validateElicitationInput("abc", s);
        assertThat(ok.isValid()).isTrue();
    }

    // ═══════════ 格式（CC :155-181 消息字面量）═══════════

    @Test
    @DisplayName("email/uri 格式: 消息字面量 + 命中/未命中（CC :156-165）")
    void formatEmailUri() {
        ElicitationValidation v = new ElicitationValidation();
        JsonNode email = schema("{\"type\":\"string\",\"format\":\"email\"}");
        assertThat(v.validateElicitationInput("user@example.com", email).isValid()).isTrue();
        ElicitationValidation.ValidationResult badEmail = v.validateElicitationInput("not-an-email", email);
        assertThat(badEmail.isValid()).isFalse();
        assertThat(badEmail.error()).isEqualTo("Must be a valid email address, e.g. user@example.com");

        JsonNode uri = schema("{\"type\":\"string\",\"format\":\"uri\"}");
        assertThat(v.validateElicitationInput("https://example.com", uri).isValid()).isTrue();
        ElicitationValidation.ValidationResult badUri = v.validateElicitationInput("not a url", uri);
        assertThat(badUri.isValid()).isFalse();
        assertThat(badUri.error()).isEqualTo("Must be a valid URI, e.g. https://example.com");
    }

    @Test
    @DisplayName("date/date-time 格式: 消息字面量 + ISO 命中/未命中（CC :166-177）")
    void formatDateDateTime() {
        ElicitationValidation v = new ElicitationValidation();
        JsonNode date = schema("{\"type\":\"string\",\"format\":\"date\"}");
        assertThat(v.validateElicitationInput("2024-03-15", date).isValid()).isTrue();
        ElicitationValidation.ValidationResult badDate = v.validateElicitationInput("2024/03/15", date);
        assertThat(badDate.isValid()).isFalse();
        assertThat(badDate.error()).isEqualTo("Must be a valid date, e.g. 2024-03-15, today, next Monday");

        JsonNode dateTime = schema("{\"type\":\"string\",\"format\":\"date-time\"}");
        assertThat(v.validateElicitationInput("2024-03-15T14:30:00Z", dateTime).isValid()).isTrue();
        ElicitationValidation.ValidationResult badDt =
            v.validateElicitationInput("2024-03-15 14:30", dateTime);
        assertThat(badDt.isValid()).isFalse();
        assertThat(badDt.error())
            .isEqualTo("Must be a valid date-time, e.g. 2024-03-15T14:30:00Z, tomorrow at 3pm");
    }

    // ═══════════ number/integer 区间（CC :184-216 rangeMsg + .0 语义）═══════════

    @Test
    @DisplayName("number 区间: rangeMsg 含 .0（CC formatNum :187-188）；integer 用整数格式")
    void numberRange_messages() {
        ElicitationValidation v = new ElicitationValidation();
        // number 且 minimum/maximum 为整数 → formatNum 输出 "0.0"（CC :188）
        JsonNode num = schema("{\"type\":\"number\",\"minimum\":0,\"maximum\":100}");
        ElicitationValidation.ValidationResult badNum = v.validateElicitationInput("150", num);
        assertThat(badNum.isValid()).isFalse();
        assertThat(badNum.error())
            .as("CC rangeMsg: Must be a number between 0.0 and 100.0（formatNum 整数 number → .0）")
            .isEqualTo("Must be a number between 0.0 and 100.0");

        ElicitationValidation.ValidationResult okNum = v.validateElicitationInput("42", num);
        assertThat(okNum.isValid()).isTrue();
        assertThat(okNum.value()).isNotNull();
        assertThat(okNum.value().asDouble()).isEqualTo(42.0);

        // integer → formatNum 恒整数格式（无 .0）
        JsonNode intg = schema("{\"type\":\"integer\",\"minimum\":0,\"maximum\":100}");
        ElicitationValidation.ValidationResult badInt = v.validateElicitationInput("42.5", intg);
        assertThat(badInt.isValid()).isFalse();
        assertThat(badInt.error()).isEqualTo("Must be an integer between 0 and 100");

        // 非数字输入 → coerce 失败（rangeMsg）
        ElicitationValidation.ValidationResult notNum = v.validateElicitationInput("abc", num);
        assertThat(notNum.isValid()).isFalse();
        assertThat(notNum.error()).isEqualTo("Must be a number between 0.0 and 100.0");
    }

    @Test
    @DisplayName("number 单边 minimum/maximum → CC rangeMsg（Must be a number >= N / <= N）")
    void numberRange_singleSided() {
        ElicitationValidation v = new ElicitationValidation();
        JsonNode minOnly = schema("{\"type\":\"number\",\"minimum\":10}");
        assertThat(v.validateElicitationInput("5", minOnly).error())
            .isEqualTo("Must be a number >= 10.0");
        JsonNode maxOnly = schema("{\"type\":\"integer\",\"maximum\":3}");
        assertThat(v.validateElicitationInput("4", maxOnly).error())
            .isEqualTo("Must be an integer <= 3");
    }

    // ═══════════ boolean coerce（CC :218-219 z.coerce.boolean，zod v4 语义）═══════════

    @Test
    @DisplayName("boolean coerce: 'true'→true / 'false'→false 通过，非布尔字符串失败（zod v4 字符串映射）")
    void booleanCoerce() {
        ElicitationValidation v = new ElicitationValidation();
        JsonNode s = schema("{\"type\":\"boolean\"}");
        ElicitationValidation.ValidationResult t = v.validateElicitationInput("true", s);
        assertThat(t.isValid()).isTrue();
        assertThat(t.value()).isNotNull();
        assertThat(t.value().asBoolean()).isTrue();
        ElicitationValidation.ValidationResult f = v.validateElicitationInput("false", s);
        assertThat(f.isValid()).isTrue();
        assertThat(f.value().asBoolean()).isFalse();
        ElicitationValidation.ValidationResult garbage = v.validateElicitationInput("garbage", s);
        assertThat(garbage.isValid()).isFalse();
    }

    // ═══════════ 非法 schema（CC :222 throw）═══════════

    @Test
    @DisplayName("非法 schema 类型 → throw（CC :222 Unsupported schema 消息）")
    void unsupportedSchema_throws() {
        JsonNode s = schema("{\"type\":\"object\"}");
        assertThatThrownBy(() -> new ElicitationValidation().validateElicitationInput("x", s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported schema:");
    }

    // ═══════════ isDateTimeSchema / looksLikeISO8601 门（CC :293-301 + dateTimeParser.ts:117-121）═══════════

    @Test
    @DisplayName("isDateTimeSchema: 仅 string + format date/date-time（CC :293-301）")
    void isDateTimeSchema_gate() {
        assertThat(ElicitationValidation.isDateTimeSchema(schema("{\"type\":\"string\",\"format\":\"date\"}"))).isTrue();
        assertThat(ElicitationValidation.isDateTimeSchema(schema("{\"type\":\"string\",\"format\":\"date-time\"}"))).isTrue();
        assertThat(ElicitationValidation.isDateTimeSchema(schema("{\"type\":\"string\",\"format\":\"email\"}"))).isFalse();
        assertThat(ElicitationValidation.isDateTimeSchema(schema("{\"type\":\"number\"}"))).isFalse();
    }

    // ═══════════ validateElicitationInputAsync 消费路径（CC :307-336 + dateTimeParser 接线）═══════════

    @Test
    @DisplayName("date-time schema + 非 ISO 输入 → DateTimeParser 慢路径：LLM 返回 ISO → 复验通过")
    void async_nonIso_llmParsesAndRevalidates() {
        ElicitationValidation v = validation("2024-03-15T14:30:00Z");
        JsonNode dateTime = schema("{\"type\":\"string\",\"format\":\"date-time\"}");

        ElicitationValidation.ValidationResult r =
            v.validateElicitationInputAsync("tomorrow at 3pm", dateTime);

        assertThat(r.isValid()).isTrue();
        assertThat(r.value().asText()).isEqualTo("2024-03-15T14:30:00Z");
    }

    @Test
    @DisplayName("LLM 返回 INVALID → 回落 syncResult（CC :334-335，失败不升级为成功）")
    void async_llmInvalid_fallsBackToSyncResult() {
        ElicitationValidation v = validation("INVALID");
        JsonNode dateTime = schema("{\"type\":\"string\",\"format\":\"date-time\"}");

        ElicitationValidation.ValidationResult r =
            v.validateElicitationInputAsync("tomorrow at 3pm", dateTime);

        assertThat(r.isValid()).isFalse();
        assertThat(r.error()).isEqualTo("Must be a valid date-time, e.g. 2024-03-15T14:30:00Z, tomorrow at 3pm");
    }

    @Test
    @DisplayName("ISO 输入不触发 LLM（looksLikeISO8601 命中 → sync 校验直接决定）")
    void async_isoInput_noLlm() {
        DateTimeParser.DateTimeModelQuery counting = new StubQuery("2024-03-15T14:30:00Z");
        ElicitationValidation v = new ElicitationValidation(new DateTimeParser(counting));
        JsonNode dateTime = schema("{\"type\":\"string\",\"format\":\"date-time\"}");

        ElicitationValidation.ValidationResult ok =
            v.validateElicitationInputAsync("2024-03-15T14:30:00Z", dateTime);
        assertThat(ok.isValid()).isTrue();
        assertThat(((StubQuery) counting).calls.get()).as("ISO 输入不走 LLM 慢路径").isZero();
    }

    @Test
    @DisplayName("sync 校验已通过 → 不触发 LLM（CC :312-314 短路）")
    void async_syncValid_noLlm() {
        DateTimeParser.DateTimeModelQuery counting = new StubQuery("2024-03-15T14:30:00Z");
        ElicitationValidation v = new ElicitationValidation(new DateTimeParser(counting));
        JsonNode num = schema("{\"type\":\"integer\",\"minimum\":0}");

        ElicitationValidation.ValidationResult r = v.validateElicitationInputAsync("42", num);
        assertThat(r.isValid()).isTrue();
        assertThat(((StubQuery) counting).calls.get()).isZero();
    }

    @Test
    @DisplayName("非日期 schema 校验失败 → 不触发 LLM（isDateTimeSchema 门 :317）")
    void async_nonDateTimeSchema_noLlm() {
        DateTimeParser.DateTimeModelQuery counting = new StubQuery("2024-03-15T14:30:00Z");
        ElicitationValidation v = new ElicitationValidation(new DateTimeParser(counting));
        JsonNode email = schema("{\"type\":\"string\",\"format\":\"email\"}");

        ElicitationValidation.ValidationResult r = v.validateElicitationInputAsync("not-an-email", email);
        assertThat(r.isValid()).isFalse();
        assertThat(((StubQuery) counting).calls.get()).isZero();
    }

    // ═══════════ getFormatHint（CC :258-288）═══════════

    @Test
    @DisplayName("getFormatHint: string format → 'description, e.g. example'；number → 区间提示")
    void formatHint() {
        assertThat(ElicitationValidation.getFormatHint(schema("{\"type\":\"string\",\"format\":\"email\"}")))
            .isEqualTo("email address, e.g. user@example.com");
        assertThat(ElicitationValidation.getFormatHint(schema("{\"type\":\"string\"}")))
            .isNull();
        assertThat(ElicitationValidation.getFormatHint(schema("{\"type\":\"number\",\"minimum\":1,\"maximum\":5}")))
            .isEqualTo("(number between 1.0 and 5.0)");
        assertThat(ElicitationValidation.getFormatHint(schema("{\"type\":\"integer\"}")))
            .isEqualTo("(integer, e.g. 42)");
        assertThat(ElicitationValidation.getFormatHint(schema("{\"type\":\"boolean\"}"))).isNull();
    }
}
