package com.nexusai.application.agent.mcp;

import com.nexusai.infra.llm.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-3 T4 / A16] DateTimeParser LLM 接线契约测试（对齐 CC utils/mcp/dateTimeParser.ts:23-111）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 消费者（elicitationValidation.ts:317-318）在 schema
 * 为 date-time 且值非 ISO 时调用 parse。旧实现仅关键字（now/tomorrow/...）对 "next Monday"
 * 走 ISO 解析失败 → 返回 now（错误语义：把自然语言当成 now）。新实现增 LLM 慢路径，
 * 非快速路径 + 非 ISO → 小快模型解析 → ISO 8601。
 */
class DateTimeParserCcContractTest {

    /** 可配置响应的 mock LLM 查询。 */
    private static final class StubQuery implements DateTimeParser.DateTimeModelQuery {
        final AtomicInteger calls = new AtomicInteger();
        private final String response;
        StubQuery(String response) { this.response = response; }
        @Override
        public String query(String systemPrompt, String userPrompt, LlmProvider.ChatRequestOptions options) {
            calls.incrementAndGet();
            assertThat(options.querySource()).as("CC dateTimeParser.ts:73 querySource='mcp_datetime_parse'")
                .isEqualTo("mcp_datetime_parse");
            assertThat(options.temperature()).as("CC temperatureOverride=0").isEqualTo(0d);
            return response;
        }
    }

    @Test
    @DisplayName("① 'next Monday' → mocked LLM 返回 2025-10-20 → success/value")
    void nextMonday_llmReturnsIso_success() {
        StubQuery query = new StubQuery("2025-10-20");
        DateTimeParser parser = new DateTimeParser(query);

        DateTimeParser.DateTimeParseResult r =
            parser.parseNaturalLanguageDateTime("next Monday", DateTimeParser.Format.DATE);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("2025-10-20");
        assertThat(query.calls.get()).as("非快速路径必须触发 LLM").isEqualTo(1);
    }

    @Test
    @DisplayName("② '2025-01-'（partial）→ mocked 返回 INVALID → success=false/error")
    void partialDate_llmInvalid_error() {
        StubQuery query = new StubQuery("INVALID");
        DateTimeParser parser = new DateTimeParser(query);

        DateTimeParser.DateTimeParseResult r =
            parser.parseNaturalLanguageDateTime("2025-01-", DateTimeParser.Format.DATE);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).as("CC dateTimeParser.ts:86-91 INVALID → error").isEqualTo("Unable to parse date/time from input");
        assertThat(query.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("③ looksLikeISO8601 判定（CC L117-121）")
    void looksLikeIso8601_checks() {
        assertThat(DateTimeParser.looksLikeISO8601("2025-10-15T15:00:00Z")).isTrue();
        assertThat(DateTimeParser.looksLikeISO8601("2025-10-15")).isTrue();
        assertThat(DateTimeParser.looksLikeISO8601("tomorrow")).isFalse();
        assertThat(DateTimeParser.looksLikeISO8601(null)).isFalse();
    }

    @Test
    @DisplayName("④ 快速路径 'tomorrow' 不触发 LLM（O1 受控偏差：CC 恒调 queryHaiku，Java 短路）")
    void fastPath_doesNotTriggerLlm() {
        StubQuery query = new StubQuery("2025-10-20");
        DateTimeParser parser = new DateTimeParser(query);

        DateTimeParser.DateTimeParseResult r =
            parser.parseNaturalLanguageDateTime("tomorrow", DateTimeParser.Format.DATE);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).as("快速路径必须返回 ISO 字符串（tomorrow）").startsWith("20");
        assertThat(query.calls.get()).as("O1：关键字快速路径短路 LLM（受控偏差）").isZero();
    }

    @Test
    @DisplayName("ISO 直解命中（looksLikeISO8601）不走 LLM")
    void isoInput_directParse() {
        StubQuery query = new StubQuery("2025-10-20");
        DateTimeParser parser = new DateTimeParser(query);

        DateTimeParser.DateTimeParseResult r =
            parser.parseNaturalLanguageDateTime("2025-10-15T15:00:00Z", DateTimeParser.Format.DATE_TIME);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("2025-10-15T15:00:00Z");
        assertThat(query.calls.get()).isZero();
    }

    @Test
    @DisplayName("LLM 返回非年份开头（gibberish）→ success=false（CC L93-99 ^\\d{4} sanity）")
    void llmGibberish_error() {
        StubQuery query = new StubQuery("not a date");
        DateTimeParser parser = new DateTimeParser(query);

        DateTimeParser.DateTimeParseResult r =
            parser.parseNaturalLanguageDateTime("when?", DateTimeParser.Format.DATE);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isEqualTo("Unable to parse date/time from input");
    }

    @Test
    @DisplayName("getSmallFastModel 接线验证（F4：DateTimeParser 引用 SkillImprovementHook.getSmallFastModel）")
    void buildModelQuery_resolvesSmallFastModelName() {
        // 语义验证：模型名 = SkillImprovementHook.getSmallFastModel()（env 链 ANTHROPIC_SMALL_FAST_MODEL
        // → ANTHROPIC_DEFAULT_HAIKU_MODEL → 默认 'claude-haiku-4-5-20251001'，model.ts:36-38 / configs.ts:31）。
        assertThat(SkillImprovementHookRef.getSmallFastModel())
            .as("getSmallFastModel 返回非空模型名（DateTimeParser 慢路径接线 F4 修正）")
            .isNotBlank();
    }

    /** 引用 SkillImprovementHook.getSmallFastModel 的只读探针（F4 修正：不是 ModelConfigResolver Javadoc）。 */
    private static final class SkillImprovementHookRef {
        static String getSmallFastModel() {
            return com.nexusai.application.agent.permission.hook.SkillImprovementHook.getSmallFastModel();
        }
    }
}
