package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Date Time Parser · 对齐 CC utils/mcp/dateTimeParser.ts（121 行）。
 *
 * <p>L1 语义: 自然语言日期/时间 → ISO 8601（CC 用 Haiku 解析）。消费者
 * {@code utils/mcp/elicitationValidation.ts:317-318}（schema 为 date-time 且值非 ISO 时调用）
 * —— Java 侧 elicitation 消费链归 MCP-I-4/3（Q-16），跨批联动。
 *
 * <p>[impl-I-3 T4 / A16] LLM 慢路径接线：非快速路径且非 ISO → 侧信道小快模型
 * （{@link SkillImprovementHook#getSmallFastModel()}，F4 修正——不是 ModelConfigResolver
 * 的 resolveFastModelName Javadoc）等价 {@code queryModelWithoutStreaming} 执行器；
 * system/user prompt 照抄 CC L39-65。
 *
 * <p>[O1 受控偏差] CC {@code parseNaturalLanguageDateTime} <b>恒调 queryHaiku 无 fast path</b>；
 * Java 保留关键字快速路径短路 LLM。属性能优化 + 语义一致（快速路径覆盖 CC 能解析的最常见输入）。
 * 若用户要求严格同步复杂（恒调 LLM）则在后续批次移除 fast path。
 */
@Component
public class DateTimeParser {

    private static final Logger log = LoggerFactory.getLogger(DateTimeParser.class);

    /** CC DateTimeParseResult = {success:true,value} | {success:false,error}（dateTimeParser.ts:6-8）。 */
    public record DateTimeParseResult(boolean success, String value, String error) {
        public static DateTimeParseResult ok(String value) {
            return new DateTimeParseResult(true, value, null);
        }
        public static DateTimeParseResult err(String error) {
            return new DateTimeParseResult(false, null, error);
        }
    }

    /** CC format 参数：'date' | 'date-time'（dateTimeParser.ts:24）。 */
    public enum Format { DATE, DATE_TIME }

    /**
     * 侧信道 LLM 查询执行器 · Java 等价 CC {@code queryModelWithoutStreaming}
     * （claude.ts:709-723）；生产用 LlmProvider.chatWithOptions（参照
     * {@link SkillImprovementHook} 的 buildModelQuery 模式，模型名 = getSmallFastModel）。
     */
    @FunctionalInterface
    public interface DateTimeModelQuery {
        String query(String systemPrompt, String userPrompt, LlmProvider.ChatRequestOptions options);
    }

    private final DateTimeModelQuery modelQuery;

    /**
     * Spring 构造 · 生产 wiring：providerFactory + modelConfigResolver 按 getSmallFastModel()
     * 精确匹配真实 provider（对齐 SkillImprovementHook DEC-RV-19 模式）；解析失败 → warn+skip 返回 ""。
     */
    @Autowired
    public DateTimeParser(@Autowired(required = false) LlmProviderFactory providerFactory,
                          @Autowired(required = false) ModelConfigResolver modelConfigResolver) {
        this.modelQuery = buildModelQuery(providerFactory, modelConfigResolver);
    }

    /** 测试/注入构造 · mock DateTimeModelQuery 验证 LLM 语义。 */
    public DateTimeParser(DateTimeModelQuery modelQuery) {
        this.modelQuery = modelQuery;
    }

    /**
     * CC looksLikeISO8601（dateTimeParser.ts:117-121）：{@code /^\d{4}-\d{2}-\d{2}(T|$)/}。
     * 用于决定是否走 NL 解析。
     */
    public static boolean looksLikeISO8601(String input) {
        if (input == null) return false;
        return Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(T|$)").matcher(input.trim()).find();
    }

    /**
     * CC parseNaturalLanguageDateTime（dateTimeParser.ts:23-111）· Java 版：
     * 关键字快速路径（O1）→ ISO 直解 → LLM 慢路径。
     *
     * @param input  自然语言日期/时间（用户输入）
     * @param format 'date'（YYYY-MM-DD）或 'date-time'（全 ISO 8601 带时区）
     * @return ISO 8601 字符串或错误信息
     */
    public DateTimeParseResult parseNaturalLanguageDateTime(String input, Format format) {
        if (input == null || input.isBlank()) {
            return DateTimeParseResult.err("Unable to parse date/time from input");
        }
        String s = input.trim();
        // [O1] 关键字快速路径（CC 恒调 queryHaiku；Java 短路 LLM，受控偏差登记）
        String fast = fastPath(s);
        if (fast != null) {
            return DateTimeParseResult.ok(fast);
        }
        // ISO 直解（looksLikeISO8601 命中 → 原样规范化）
        if (looksLikeISO8601(s)) {
            return DateTimeParseResult.ok(s);
        }
        return parseViaLlm(s, format);
    }

    /** 关键字快速路径 · O1 受控偏差（CC 无此路径，语义一致覆盖最常见输入）。 */
    private String fastPath(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        return switch (lower) {
            case "now" -> toIso(now);
            case "tomorrow" -> toIso(now.plusDays(1));
            case "yesterday" -> toIso(now.minusDays(1));
            case "next hour" -> toIso(now.plusHours(1));
            case "next week" -> toIso(now.plusWeeks(1));
            default -> null;
        };
    }

    /** 本地时间 → 带本地时区偏移的 ISO 8601（CC formatDescription 的 timezone 段语义）。 */
    private static String toIso(LocalDateTime dt) {
        ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(dt);
        return dt.atOffset(offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** LLM 慢路径 · 对齐 CC dateTimeParser.ts:29-110。 */
    private DateTimeParseResult parseViaLlm(String input, Format format) {
        // 当前时间上下文（CC L29-36）
        Instant now = Instant.now();
        String currentDateTime = now.toString();
        ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(now);
        int tzTotal = offset.getTotalSeconds();
        int tzHours = Math.abs(tzTotal) / 3600;
        int tzMinutes = (Math.abs(tzTotal) % 3600) / 60;
        String tzSign = tzTotal >= 0 ? "+" : "-";
        String timezone = tzSign + String.format("%02d", tzHours) + ":" + String.format("%02d", tzMinutes);
        String dayOfWeek = java.time.LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        // system prompt（CC L39-48）
        String systemPrompt =
            "You are a date/time parser that converts natural language into ISO 8601 format.\n"
            + "You MUST respond with ONLY the ISO 8601 formatted string, with no explanation or additional text.\n"
            + "If the input is ambiguous, prefer future dates over past dates.\n"
            + "For times without dates, use today's date.\n"
            + "For dates without times, do not include a time component.\n"
            + "If the input is incomplete or you cannot confidently parse it into a valid date, respond with exactly \"INVALID\" (nothing else).\n"
            + "Examples of INVALID input: partial dates like \"2025-01-\", lone numbers like \"13\", gibberish.\n"
            + "Examples of valid natural language: \"tomorrow\", \"next Monday\", \"jan 1st 2025\", \"in 2 hours\", \"yesterday\".";

        // user prompt（CC L51-65）
        String formatDescription = format == Format.DATE
            ? "YYYY-MM-DD (date only, no time)"
            : "YYYY-MM-DDTHH:MM:SS" + timezone + " (full date-time with timezone)";
        String userPrompt =
            "Current context:\n"
            + "- Current date and time: " + currentDateTime + " (UTC)\n"
            + "- Local timezone: " + timezone + "\n"
            + "- Day of week: " + dayOfWeek + "\n\n"
            + "User input: \"" + input + "\"\n\n"
            + "Output format: " + formatDescription + "\n\n"
            + "Parse the user's input into ISO 8601 format. Return ONLY the formatted string, or \"INVALID\" if the input is incomplete or unparseable.";

        try {
            // CC L68-80: queryHaiku({systemPrompt, userPrompt, signal, options})
            // options: thinkingConfig disabled + temperature 0 + querySource 'mcp_datetime_parse'（CC L73）
            String response = modelQuery.query(systemPrompt, userPrompt, queryOptions());
            String parsedText = response == null ? "" : response.trim();

            // CC L86-91: 空 / INVALID → error
            if (parsedText.isEmpty() || "INVALID".equals(parsedText)) {
                return DateTimeParseResult.err("Unable to parse date/time from input");
            }
            // CC L93-99: 不以 \d{4} 开头 → error（年份开头 sanity check）
            if (!Pattern.compile("^\\d{4}").matcher(parsedText).find()) {
                return DateTimeParseResult.err("Unable to parse date/time from input");
            }
            return DateTimeParseResult.ok(parsedText);
        } catch (Exception e) {
            // CC L102-110: catch → logError + error（不暴露细节）
            log.warn("DateTimeParser LLM 解析失败: {}", e.getMessage());
            return DateTimeParseResult.err("Unable to parse date/time. Please enter in ISO 8601 format manually.");
        }
    }

    /** CC 查询选项（dateTimeParser.ts:72-79）· thinkingConfig disabled + temperature 0 + querySource 'mcp_datetime_parse'。 */
    private static LlmProvider.ChatRequestOptions queryOptions() {
        return new LlmProvider.ChatRequestOptions(
            List.of(), null, null,
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            0d,
            "mcp_datetime_parse",
            new AbortController(),
            null,
            null);
    }

    /**
     * 构造侧信道 LLM 查询函数 · 对齐 CC queryModelWithoutStreaming + SkillImprovementHook.buildModelQuery
     * （DEC-RV-19：模型名 → 全局 client，模型与 config 天然一致；Java 经 ModelConfigResolver 按
     * {@code getSmallFastModel()} 精确匹配 → 2 参 getProvider(config, providerType) 路由）。
     */
    static DateTimeModelQuery buildModelQuery(
            LlmProviderFactory providerFactory, ModelConfigResolver modelConfigResolver) {
        if (providerFactory == null) {
            return (systemPrompt, prompt, options) -> "";
        }
        AtomicReference<ProviderConfig> configRef = new AtomicReference<>();
        AtomicReference<LlmProvider> providerRef = new AtomicReference<>();
        return (systemPrompt, prompt, options) -> {
            if (configRef.get() == null) {
                if (modelConfigResolver == null) {
                    log.warn("DateTimeParser ModelConfigResolver 未注入, 跳过侧信道 LLM 查询 (warn+skip 不落 mock)");
                    return "";
                }
                ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(SkillImprovementHook.getSmallFastModel());
                if (resolved == null) {
                    return "";
                }
                configRef.set(resolved.config());
                providerRef.set(providerFactory.getProvider(resolved.config(), resolved.providerType()));
                log.info("DateTimeParser LLM 解析已接线真实 provider (baseUrl={}, type={})",
                    resolved.config().baseUrl(), resolved.providerType());
            }
            String content = providerRef.get().chatWithOptions(
                    configRef.get(), SkillImprovementHook.getSmallFastModel(), systemPrompt, prompt, options);
            return content == null ? "" : content;
        };
    }
}
