package com.nexusai.application.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LanguageResolver 解析语义测试(规则九:测意图)。
 *
 * <p>WHY(意图):settings.language 存语言<b>显示名</b>或 {@code auto}。解析结果直接拼进
 * {@code "Always respond in {结果}"}(SystemPromptSections languageCompute)与 title prompt——
 * 故测试锁定:null/blank 不注入(SP-08 现状)、显式语言原样透传、auto 按时区映射、未命中回落中文。
 */
@DisplayName("LanguageResolver: language 值 → 注入用语言名(auto 按时区/显式透传/null 不注入)")
class LanguageResolverTest {

    @Test
    @DisplayName("null / blank → null(不注入 Language 段,SP-08 现状)")
    void resolve_nullOrBlankReturnsNull() {
        assertThat(LanguageResolver.resolve(null)).isNull();
        assertThat(LanguageResolver.resolve("")).isNull();
        assertThat(LanguageResolver.resolve("   ")).isNull();
    }

    @Test
    @DisplayName("显式语言名原样透传(trim):中文/English/日本語 → 同名(注入 respond in {名})")
    void resolve_explicitLanguagePassesThrough() {
        assertThat(LanguageResolver.resolve("中文")).isEqualTo("中文");
        assertThat(LanguageResolver.resolve("English")).isEqualTo("English");
        assertThat(LanguageResolver.resolve("日本語")).isEqualTo("日本語");
        // trim:前端可能带空格
        assertThat(LanguageResolver.resolve("  中文  ")).isEqualTo("中文");
    }

    @Test
    @DisplayName("auto 按指定时区解析:上海→中文 / 东京→日本語 / 纽约→English / 巴黎→Français")
    void resolve_autoUsesZoneMapping() {
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Asia/Shanghai"))).isEqualTo("中文");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Asia/Tokyo"))).isEqualTo("日本語");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Asia/Seoul"))).isEqualTo("한국어");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("America/New_York"))).isEqualTo("English");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Europe/Paris"))).isEqualTo("Français");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Europe/Berlin"))).isEqualTo("Deutsch");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Asia/Kolkata"))).isEqualTo("हिन्दी");
    }

    @Test
    @DisplayName("auto 未命中时区 → 回落中文(用户产品主中文,不抛)")
    void resolve_autoUnknownZoneFallsBackZh() {
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Pacific/Kiritimati"))).isEqualTo("中文");
        assertThat(LanguageResolver.resolve("auto", ZoneId.of("Antarctica/Troll"))).isEqualTo("中文");
    }

    @Test
    @DisplayName("auto + null zone → 不抛,回落本机时区解析(不 NPE)")
    void resolve_autoNullZoneFallsBackSystem() {
        String lang = LanguageResolver.resolve("auto", null);
        assertThat(lang).isNotBlank(); // 本机时区必有结果(命中映射或回落中文)
    }

    @Test
    @DisplayName("auto 大小写不敏感(auto/AUTO/Auto 均识别)")
    void resolve_autoCaseInsensitive() {
        assertThat(LanguageResolver.resolve("AUTO", ZoneId.of("Asia/Shanghai"))).isEqualTo("中文");
        assertThat(LanguageResolver.resolve("Auto", ZoneId.of("Asia/Shanghai"))).isEqualTo("中文");
    }
}
