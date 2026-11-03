package com.nexusai.domain.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.market.dto.ExternalPluginDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [skill-market v4 2026-11-01] 市场条目的<b>统计字段</b>与<b>开发者字段</b>解析。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：用户两条硬要求 ——
 * <b>①「统计数字要真实的」</b>、<b>②「补开发者概念，没有的默认数字公司开发组」</b>。
 * 这两个要求各自有一条<b>极易写错、且错了会静默误导用户</b>的边界：
 *
 * <ol>
 *   <li><b>统计缺失 ≠ 0</b>。若把「市场没这项统计」当成 0，界面会显示「0 次使用」——
 *       用户会以为这技能没人用，实际是没数据。本用例锁死：缺失 → {@code null}。</li>
 *   <li><b>缺开发者要落到默认作者名</b>。作者名参与「按 author.name 分组」
 *       （开发者弹窗列出该开发者全部技能）；后端不兜底的话，空 author 与显式
 *       「数字公司开发组」会裂成两组。</li>
 *   <li><b>统计必须是原始整数</b>，不能是「615.64w」这种展示串 —— 后者排序/聚合全废。</li>
 * </ol>
 *
 * <p>变异自证：把 {@code longOrNull} 的 null 分支改成返回 0L → 用例 1 红；
 * 把 {@code authorName} 的缺省兜底去掉 → 用例 2 红；
 * 把 {@code mergeStats} 去掉 → 用例 3 红。
 */
class ExternalMarketServiceStatsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 被测服务的私有静态方法（纯函数，无需构造依赖 —— 用反射直调，避免为测试造 HTTP 桩）。 */
    private static Object invokeStatic(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = ExternalMarketService.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    // ── 1. 统计：缺失 ≠ 0 ───────────────────────────────────────────────

    @Test
    @DisplayName("统计缺失 → null（不是 0）：界面上「没有数据」与「0 次使用」必须能区分")
    void missingStatsIsNullNotZero() throws Exception {
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, (Object) null)).isNull();
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.nullNode())).isNull();
        // 空对象里取不到键 → JsonNode 为 null
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class},
            JSON.readTree("{}").get("useCount"))).isNull();
    }

    @Test
    @DisplayName("统计为 0 时返回 0（真实 0 不能被当成「没数据」吞掉）")
    void zeroStatsIsZero() throws Exception {
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.readTree("0"))).isEqualTo(0L);
    }

    @Test
    @DisplayName("统计下发原始整数：6156400 → 6156400（⛔ 不是「615.64w」展示串）")
    void statsStayRawIntegers() throws Exception {
        Object v = invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.readTree("6156400"));
        assertThat(v).isEqualTo(6_156_400L);
    }

    @Test
    @DisplayName("统计兼容字符串数字（部分静态站生成器写成 \"123\"）；非法值 → null 而非抛异常")
    void statsAcceptsNumericStringAndRejectsGarbage() throws Exception {
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.readTree("\"232200\"")))
            .isEqualTo(232_200L);
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.readTree("\"abc\""))).isNull();
        // 负数视为非法（统计不可能是负数）
        assertThat(invokeStatic("longOrNull", new Class<?>[]{JsonNode.class}, JSON.readTree("-5"))).isNull();
    }

    // ── 2. 开发者：缺省落到默认作者名 ──────────────────────────────────

    @Test
    @DisplayName("marketplace 没写 author → 默认「数字公司开发组」（用户拍板；保证按作者分组不裂开）")
    void missingAuthorFallsBackToDefault() throws Exception {
        assertThat(invokeStatic("authorName", new Class<?>[]{JsonNode.class}, (Object) null))
            .isEqualTo(ExternalPluginDto.DEFAULT_AUTHOR_NAME);
        assertThat(ExternalPluginDto.DEFAULT_AUTHOR_NAME).isEqualTo("数字公司开发组");
    }

    @Test
    @DisplayName("author 两种写法都认：字符串 \"Acme\" 与对象 {\"name\":\"Acme\"}")
    void authorAcceptsStringAndObject() throws Exception {
        assertThat(invokeStatic("authorName", new Class<?>[]{JsonNode.class}, JSON.readTree("\"Acme\"")))
            .isEqualTo("Acme");
        assertThat(invokeStatic("authorName", new Class<?>[]{JsonNode.class}, JSON.readTree("{\"name\":\"Acme\"}")))
            .isEqualTo("Acme");
    }

    @Test
    @DisplayName("author 对象里的 email/verified/avatarUrl 被取出；非对象形态一律缺省")
    void authorObjectFieldsParsed() throws Exception {
        JsonNode author = JSON.readTree(
            "{\"name\":\"Anthropic\",\"email\":\"support@anthropic.com\",\"verified\":true,"
                + "\"avatarUrl\":\"https://x/a.png\"}");
        assertThat(invokeStatic("authorEmail", new Class<?>[]{JsonNode.class}, author))
            .isEqualTo("support@anthropic.com");
        assertThat(invokeStatic("authorVerified", new Class<?>[]{JsonNode.class}, author)).isEqualTo(Boolean.TRUE);
        assertThat(invokeStatic("authorAvatarUrl", new Class<?>[]{JsonNode.class}, author))
            .isEqualTo("https://x/a.png");

        // 缺省：verified 必须是 false（不是 null —— 前端据 true 渲染认证图标）
        assertThat(invokeStatic("authorVerified", new Class<?>[]{JsonNode.class}, (Object) null))
            .isEqualTo(Boolean.FALSE);
        assertThat(invokeStatic("authorEmail", new Class<?>[]{JsonNode.class}, (Object) null)).isNull();
    }

    // ── 3. stats.json 合并 ─────────────────────────────────────────────

    @Test
    @DisplayName("stats.json 按插件名合并进列表；没统计的条目保持 null（不被污染成 0）")
    void mergeStatsByName() throws Exception {
        ExternalPluginDto docx = plugin("docx", null, null);
        ExternalPluginDto pdf = plugin("pdfkit", null, null);
        JsonNode stats = JSON.readTree(
            "{\"docx\":{\"use\":6156400,\"download\":232200}}");

        @SuppressWarnings("unchecked")
        List<ExternalPluginDto> merged = (List<ExternalPluginDto>) invokeStatic(
            "mergeStats", new Class<?>[]{List.class, JsonNode.class}, List.of(docx, pdf), stats);

        assertThat(merged).hasSize(2);
        ExternalPluginDto m0 = merged.get(0);
        assertThat(m0.name()).isEqualTo("docx");
        assertThat(m0.useCount()).isEqualTo(6_156_400L);
        assertThat(m0.downloadCount()).isEqualTo(232_200L);
        // 没有统计的条目：保持 null
        assertThat(merged.get(1).useCount()).isNull();
        assertThat(merged.get(1).downloadCount()).isNull();
    }

    @Test
    @DisplayName("stats.json 用 useCount/downloadCount 命名也认（两种习惯兼容）")
    void mergeStatsAcceptsAlternateKeys() throws Exception {
        JsonNode stats = JSON.readTree("{\"docx\":{\"useCount\":7,\"downloadCount\":9}}");
        @SuppressWarnings("unchecked")
        List<ExternalPluginDto> merged = (List<ExternalPluginDto>) invokeStatic(
            "mergeStats", new Class<?>[]{List.class, JsonNode.class},
            List.of(plugin("docx", null, null)), stats);
        assertThat(merged.get(0).useCount()).isEqualTo(7L);
        assertThat(merged.get(0).downloadCount()).isEqualTo(9L);
    }

    @Test
    @DisplayName("stats.json 为空/结构不对 → 原样返回（统计缺失不能阻断市场列表）")
    void mergeStatsToleratesEmptyOrBadInput() throws Exception {
        List<ExternalPluginDto> list = List.of(plugin("docx", null, null));
        @SuppressWarnings("unchecked")
        List<ExternalPluginDto> r1 = (List<ExternalPluginDto>) invokeStatic(
            "mergeStats", new Class<?>[]{List.class, JsonNode.class}, list, JSON.readTree("{}"));
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).useCount()).isNull();
        // stats 为 null（拉取失败）→ 原样
        @SuppressWarnings("unchecked")
        List<ExternalPluginDto> r2 = (List<ExternalPluginDto>) invokeStatic(
            "mergeStats", new Class<?>[]{List.class, JsonNode.class}, list, (Object) null);
        assertThat(r2).hasSize(1);
    }

    @Test
    @DisplayName("stats.json 的 URL 由 marketplace.json 同目录推导")
    void siblingStatsUrlDerivation() throws Exception {
        assertThat(invokeStatic("siblingStatsUrl", new Class<?>[]{String.class},
            "http://h/nexusai/marketplace/marketplace.json"))
            .isEqualTo("http://h/nexusai/marketplace/stats.json");
        // 无目录部分 → 推不出来
        assertThat(invokeStatic("siblingStatsUrl", new Class<?>[]{String.class}, "marketplace.json")).isNull();
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static ExternalPluginDto plugin(String name, Long use, Long download) {
        return new ExternalPluginDto(
            name, name, "desc", "1.0.0", "skill", List.of(), null,
            use, download, ExternalPluginDto.DEFAULT_AUTHOR_NAME, false, null, null);
    }
}
