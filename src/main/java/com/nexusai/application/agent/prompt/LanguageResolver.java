package com.nexusai.application.agent.prompt;

import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话/回复语言解析 · 供 SP-08 系统提示 Language 段 + title 生成共用。
 *
 * <p><b>值语义</b>(settings.language):存<b>语言显示名</b>(中文/English/日本語…)或特殊值
 * {@code "auto"}。<b>注入拼法</b> {@code "Always respond in {语言名}"}(SystemPromptSections
 * languageCompute,对齐 CC getLanguageSection prompts.ts:154-162),故显示名最自然——存 "zh" 会
 * 产生 "respond in zh" 而非人类语言名。
 *
 * <p><b>auto 解析</b>(用户 2026-09-04 拍板):按<b>本机时区</b>推断语言(本地单机:后端
 * {@code ZoneId.systemDefault()} = 用户机器时区)。主流时区映射见 {@link #ZONE_LANG}(覆盖 14
 * 主流语言);未命中 → 回落中文(用户产品主中文;本机 Asia/Shanghai 已命中)。null/blank → null
 * (沿用 SP-08 "未配置不注入 Language 段" 现状)。
 */
public final class LanguageResolver {

    private static final Logger log = LoggerFactory.getLogger(LanguageResolver.class);

    /** 特殊值:用户选"自动",解析时按本机时区推断。 */
    public static final String AUTO = "auto";

    /** 解析入口(用本机时区):settings.language 原始值 → 可注入的最终语言显示名。 */
    public static String resolve(String raw) {
        return resolve(raw, ZoneId.systemDefault());
    }

    /** 解析入口(指定时区 · 测试固定 auto 时区用):"auto" → 按该时区推断;null/blank → null;已设 → 原样。 */
    public static String resolve(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return null; // 未配置 → 不注入(SP-08 现状)
        }
        String trimmed = raw.trim();
        if (AUTO.equalsIgnoreCase(trimmed)) {
            return resolveFromZone(zone != null ? zone : ZoneId.systemDefault());
        }
        return trimmed; // 已是语言显示名
    }

    /** auto → 按时区推断。时区 id 精确命中表 → 语言名;未命中 → 中文(fallback)。 */
    private static String resolveFromZone(ZoneId zone) {
        String id = zone != null ? zone.getId() : "";
        // 先精确命中(城市级 zone,同国多城可并存)
        String exact = ZONE_LANG.get(id);
        if (exact != null) {
            return exact;
        }
        // 区域级兜底:取 zone 的 region(如 Europe/Paris → region=Paris),查 REGION_LANG
        int slash = id.indexOf('/');
        String region = slash >= 0 ? id.substring(slash + 1) : id;
        String byRegion = REGION_LANG.get(region);
        if (byRegion != null) {
            return byRegion;
        }
        if (log.isDebugEnabled()) {
            log.debug("[LanguageResolver] auto 时区 {} 未命中映射,回落中文", id);
        }
        return "中文";
    }

    /** 精确 zone id → 语言名(主流覆盖,同语言多城市枚举)。 */
    private static final Map<String, String> ZONE_LANG = Map.ofEntries(
        // 中文
        Map.entry("Asia/Shanghai", "中文"), Map.entry("Asia/Hong_Kong", "中文"),
        Map.entry("Asia/Taipei", "中文"), Map.entry("Asia/Macau", "中文"),
        Map.entry("Asia/Chongqing", "中文"), Map.entry("Asia/Urumqi", "中文"),
        // English(美/加核心城市 + 英/澳/新/纽)
        Map.entry("America/New_York", "English"), Map.entry("America/Chicago", "English"),
        Map.entry("America/Los_Angeles", "English"), Map.entry("America/Denver", "English"),
        Map.entry("America/Phoenix", "English"), Map.entry("America/Anchorage", "English"),
        Map.entry("America/Toronto", "English"), Map.entry("America/Vancouver", "English"),
        Map.entry("Europe/London", "English"), Map.entry("Australia/Sydney", "English"),
        Map.entry("Australia/Melbourne", "English"), Map.entry("Asia/Singapore", "English"),
        Map.entry("Pacific/Auckland", "English"), Map.entry("America/Indianapolis", "English"),
        // 日本語
        Map.entry("Asia/Tokyo", "日本語"),
        // 한국어
        Map.entry("Asia/Seoul", "한국어"),
        // Deutsch
        Map.entry("Europe/Berlin", "Deutsch"), Map.entry("Europe/Vienna", "Deutsch"),
        Map.entry("Europe/Zurich", "Deutsch"),
        // Français
        Map.entry("Europe/Paris", "Français"), Map.entry("Europe/Brussels", "Français"),
        Map.entry("Europe/Luxembourg", "Français"),
        // Español
        Map.entry("Europe/Madrid", "Español"), Map.entry("America/Mexico_City", "Español"),
        Map.entry("America/Argentina/Buenos_Aires", "Español"),
        Map.entry("America/Lima", "Español"), Map.entry("America/Bogota", "Español"),
        Map.entry("America/Santiago", "Español"),
        // Português
        Map.entry("Europe/Lisbon", "Português"), Map.entry("America/Sao_Paulo", "Português"),
        // Русский
        Map.entry("Europe/Moscow", "Русский"),
        // Italiano
        Map.entry("Europe/Rome", "Italiano"),
        // العربية
        Map.entry("Asia/Riyadh", "العربية"), Map.entry("Asia/Dubai", "العربية"),
        Map.entry("Africa/Cairo", "العربية"), Map.entry("Africa/Casablanca", "العربية"),
        // हिन्दी
        Map.entry("Asia/Kolkata", "हिन्दी"),
        // Nederlands
        Map.entry("Europe/Amsterdam", "Nederlands"),
        // Türkçe
        Map.entry("Europe/Istanbul", "Türkçe"));

    /** 非精确城市级兜底:按 region(zone 斜杠后段)映射(覆盖未枚举城市如 Europe/Helsinki→en 等非主流回落)。 */
    private static final Map<String, String> REGION_LANG = Map.ofEntries(
        Map.entry("Tokyo", "日本語"), Map.entry("Seoul", "한국어"), Map.entry("Kolkata", "हिन्दी"),
        Map.entry("New_York", "English"), Map.entry("London", "English"), Map.entry("Sydney", "English"),
        Map.entry("Berlin", "Deutsch"), Map.entry("Paris", "Français"), Map.entry("Madrid", "Español"),
        Map.entry("Moscow", "Русский"), Map.entry("Rome", "Italiano"), Map.entry("Riyadh", "العربية"));

    /** 支持的语言显示名全集(前端弹窗选项 + auto 文案用 · 后端兜底枚举)。 */
    public static final java.util.List<String> SUPPORTED_LANGUAGES = java.util.List.of(
        "中文", "English", "日本語", "한국어", "Deutsch", "Français", "Español",
        "Português", "Русский", "Italiano", "العربية", "हिन्दी", "Nederlands", "Türkçe");

    private LanguageResolver() {}
}
