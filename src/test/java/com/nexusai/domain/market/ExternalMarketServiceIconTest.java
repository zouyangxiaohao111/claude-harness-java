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
 * [2026-11-23 市场图标] 外部市场条目 icon 字段的解析与统计合并保留。
 *
 * <p><b>WHY</b>：图标最容易在两个地方静默丢失 ——
 * ① 解析时没显式取 {@code icon} 键（Jackson 不看就丢，界面永远首字母色块）；
 * ② {@code mergeStats} 逐字段重建 DTO 时漏传 icon（有 stats.json 的市场条目会集体没图标）。
 * 本用例锁死这两条；前端另有 onError 回落用例。
 */
class ExternalMarketServiceIconTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ExternalPluginDto plugin(String name, String icon) {
        return new ExternalPluginDto(
            name, name, "desc", "1.0.0", "sha256:" + name, "skill", List.of(), null, null,
            null, null, ExternalPluginDto.DEFAULT_AUTHOR_NAME, false, null, null, null, icon);
    }

    @Test
    @DisplayName("marketplace.json 的 icon 键能被 text() 读出；缺失为 null")
    void iconKeyReadable() throws Exception {
        JsonNode node = JSON.readTree("{\"icon\":\"http://m/icons/a.svg\"}");
        assertThat(text(node.get("icon"))).isEqualTo("http://m/icons/a.svg");
        assertThat(text(JSON.readTree("{}").get("icon"))).isNull();
    }

    @Test
    @DisplayName("mergeStats 重建 DTO 时保留 icon（有统计的条目不能集体掉图标）")
    void mergeStatsKeepsIcon() throws Exception {
        List<ExternalPluginDto> out = mergeStats(
            List.of(plugin("a", "http://m/icons/a.svg"), plugin("b", null)),
            JSON.readTree("{\"a\":{\"use\":3},\"b\":{\"download\":5}}"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).icon()).isEqualTo("http://m/icons/a.svg");
        assertThat(out.get(1).icon()).isNull();
    }

    @Test
    @DisplayName("stats.json 里没有该条目时原对象原样保留（icon 不丢）")
    void mergeStatsWithoutEntryKeepsIcon() throws Exception {
        List<ExternalPluginDto> out = mergeStats(
            List.of(plugin("a", "http://m/icons/a.svg")),
            JSON.readTree("{\"other\":{\"use\":1}}"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).icon()).isEqualTo("http://m/icons/a.svg");
    }

    // ── 反射调私有静态纯函数 ────────────────────────────────────────────

    private static String text(JsonNode node) throws Exception {
        Method m = ExternalMarketService.class.getDeclaredMethod("text", JsonNode.class);
        m.setAccessible(true);
        return (String) m.invoke(null, node);
    }

    @SuppressWarnings("unchecked")
    private static List<ExternalPluginDto> mergeStats(List<ExternalPluginDto> in, JsonNode stats) throws Exception {
        Method m = ExternalMarketService.class.getDeclaredMethod("mergeStats", List.class, JsonNode.class);
        m.setAccessible(true);
        return (List<ExternalPluginDto>) m.invoke(null, in, stats);
    }
}
