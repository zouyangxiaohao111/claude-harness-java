package com.nexusai.application.agent.bundled;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置专家目录（classpath 272 个）的解析与过滤。
 *
 * <h2>WHY</h2>
 * <p>目录是「按需安装」的唯一数据源：列表里少了/多了一条，用户就装不到或装错。
 * 这里钉住 272 总数、slug/name 唯一、必填字段非空、分类过滤与搜索命中，
 * 以及「目录条目能被安装器按 slug 取到」这一条链路。
 */
@DisplayName("[bundled-experts] 内置专家目录")
class BundledExpertsCatalogTest {

    private final BundledExpertsCatalog catalog = new BundledExpertsCatalog();

    @Test
    @DisplayName("272 个专家，slug/name 唯一，必填字段非空")
    void loadsAllBundledExperts() {
        List<BundledExpert> all = catalog.all();

        assertThat(all).hasSize(272);
        assertThat(all.stream().map(BundledExpert::slug).collect(Collectors.toSet())).hasSize(272);
        assertThat(all.stream().map(BundledExpert::name).collect(Collectors.toSet())).hasSize(272);
        assertThat(all.stream().map(BundledExpert::division).collect(Collectors.toSet())).hasSize(19);

        for (BundledExpert e : all) {
            assertThat(e.name()).as("%s name", e.slug()).isNotBlank();
            assertThat(e.description()).as("%s description", e.slug()).isNotBlank();
            assertThat(e.content()).as("%s content", e.slug()).contains("---");
        }

        assertThat(all.get(0).division().compareTo(all.get(all.size() - 1).division())).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("按 division 过滤 + 关键字搜索（名称/描述/slug）")
    void filtersByDivisionAndQuery() {
        assertThat(catalog.list("engineering", null)).hasSize(42);
        assertThat(catalog.list("engineering", null))
            .allMatch(e -> e.division().equals("engineering"));

        List<BundledExpert> byName = catalog.list(null, "后端架构师");
        assertThat(byName).extracting(BundledExpert::slug).contains("engineering-backend-architect");

        List<BundledExpert> byDesc = catalog.list(null, "威胁建模");
        assertThat(byDesc).isNotEmpty();

        assertThat(catalog.list("engineering", "完全不存在的关键字XYZ")).isEmpty();
    }

    @Test
    @DisplayName("按 slug 精确查找")
    void findsBySlug() {
        assertThat(catalog.findBySlug("engineering-backend-architect"))
            .isPresent()
            .get()
            .extracting(BundledExpert::name)
            .isEqualTo("后端架构师");

        assertThat(catalog.findBySlug("no-such-expert")).isEmpty();
        assertThat(catalog.findBySlug("  ")).isEmpty();
    }
}
