package com.nexusai.kb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 端点契约 · W2-8。
 *
 * <p><b>WHY 用反射而不是 {@code @SpringBootTest}</b>：
 * ⛔ 本仓的 {@code @SpringBootTest} 会迁移<b>用户真库</b>（见 CLAUDE.md 铁律），一律不跑。
 * ⇒ 端点契约只能靠「结构断言」：路径、方法、DTO 字段名。
 * 这些恰好是最容易漏/最容易被改坏的部分（源侧是 snake_case）。
 */
@DisplayName("[W2] Wiki 端点契约")
class WikiControllerContractTest {

    /** 源侧 22 个端点（path 前缀去掉 /api/v1，用本仓的 /knowledge-bases 形态）。 */
    private static final List<String> EXPECTED_PATHS = List.of(
        "/api/v1/knowledge-bases/{kb}/wiki/pages",
        "/api/v1/knowledge-bases/{kb}/wiki/pages",
        "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}",
        "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}",
        "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}",
        "/api/v1/knowledge-bases/{kb}/wiki/folders",
        "/api/v1/knowledge-bases/{kb}/wiki/folders",
        "/api/v1/knowledge-bases/{kb}/wiki/folders/{fid}",
        "/api/v1/knowledge-bases/{kb}/wiki/folders/{fid}",
        "/api/v1/knowledge-bases/{kb}/wiki/index",
        "/api/v1/knowledge-bases/{kb}/wiki/search",
        "/api/v1/wiki-search",
        "/api/v1/knowledge-bases/{kb}/wiki/graph",
        "/api/v1/knowledge-bases/{kb}/wiki/stats",
        "/api/v1/knowledge-bases/{kb}/wiki/lint",
        "/api/v1/knowledge-bases/{kb}/wiki/auto-fix",
        "/api/v1/knowledge-bases/{kb}/wiki/issues",
        "/api/v1/knowledge-bases/{kb}/wiki/issues/{iid}/status",
        "/api/v1/knowledge-bases/{kb}/wiki/rebuild-links",
        "/api/v1/knowledge-bases/{kb}/wiki/revisions/{type}/{name}",
        "/api/v1/knowledge-bases/{kb}/wiki/revert",
        "/api/v1/knowledge-bases/{kb}/wiki/move-page");

    /**
     * 本仓<b>额外</b>的端点（源侧没有，为补能力缺口而加）。
     *
     * <p>⭐ 为什么要显式登记而不是简单放宽成「≥ 22」：
     * 计数断言的价值是「多一个或少一个都会红」—— 放宽成 ≥ 就再也发现不了误加。
     * 这里把「有意的额外端点」写成白名单，任何<b>意外的</b>多/少仍然会失败。
     *
     * <p>目前只有 1 个：{@code wiki/rebuild} —— Wiki 原本只在文档入库后自动生成，
     * 上传时没配模型、或那一轮生成失败，之后配好模型也没有补生成入口
     * （只能整篇重新解析，重跑分块与索引、块 ID 会变）。该端点对齐
     * {@code graph/rebuild} 提供手动补生成。
     */
    private static final List<String> OUR_EXTRA_PATHS = List.of(
        "/api/v1/knowledge-bases/{kb}/wiki/rebuild");

    /** 收集某个 mapping 注解上的全部路径。 */
    private static Set<String> pathsOf(Method m) {
        Set<String> out = new TreeSet<>();
        for (var ann : m.getAnnotations()) {
            String name = ann.annotationType().getSimpleName();
            if (!name.endsWith("Mapping")) {
                continue;
            }
            try {
                var value = ann.annotationType().getMethod("value").invoke(ann);
                if (value instanceof String[] arr) {
                    out.addAll(Arrays.asList(arr));
                }
            } catch (ReflectiveOperationException ignored) {
                // 无 value 属性（如 @PostMapping 用了 path）—— 本仓统一用 value
            }
        }
        return out;
    }

    /** 所有带 mapping 注解的方法。 */
    private static List<Method> mappings() {
        return Arrays.stream(WikiController.class.getDeclaredMethods())
            .filter(m -> pathsOf(m).size() > 0)
            .toList();
    }

    @Test
    @DisplayName("⭐⭐ 22 个端点全部存在（含源侧兼容路径 + 本仓惯例路径）")
    void allTwentyTwoEndpoints() {
        // ⚠️ 必须按「方法 + 路径」计数，不能只收集路径 ——
        //    GET/POST /wiki/pages 是同一路径的两个端点，用 Set 会被去重成 1 个。
        List<String> conventions = new java.util.ArrayList<>();
        for (Method m : mappings()) {
            for (String p : pathsOf(m)) {
                if (p.contains("/knowledge-bases/") || p.equals("/api/v1/wiki-search")) {
                    conventions.add(m.getName() + " " + p);
                }
            }
        }

        assertThat(conventions)
            .as("⭐⭐ 源侧 22 个端点一个都不能少（本仓额外端点显式登记在 OUR_EXTRA_PATHS）；"
                + "意外的多/少都必须红，所以计数仍然精确比对")
            .hasSize(EXPECTED_PATHS.size() + OUR_EXTRA_PATHS.size());

        // 每条期望路径都至少被一个方法覆盖
        for (String expected : EXPECTED_PATHS) {
            assertThat(conventions.stream().anyMatch(c -> c.endsWith(" " + expected)))
                .as("缺少端点: %s", expected)
                .isTrue();
        }

        // 每个「本仓额外端点」也确实存在（白名单不能是空头承诺）
        for (String extra : OUR_EXTRA_PATHS) {
            assertThat(conventions.stream().anyMatch(c -> c.endsWith(" " + extra)))
                .as("白名单登记了但实际不存在: %s", extra)
                .isTrue();
        }
    }

    @Test
    @DisplayName("⭐ 每个端点都同时挂源侧路径（/knowledgebase/）以兼容迁移")
    void sourceCompatiblePaths() {
        for (Method m : mappings()) {
            Set<String> paths = pathsOf(m);
            boolean hasSourceForm = paths.stream().anyMatch(p -> p.startsWith("/api/v1/knowledgebase/"));
            boolean hasConventionForm = paths.stream().anyMatch(p -> p.startsWith("/api/v1/knowledge-bases/"));
            if (paths.contains("/api/v1/wiki-search")) {
                continue;   // 全库搜索只有一个路径
            }
            assertThat(hasSourceForm)
                .as("%s 缺少源侧兼容路径 /knowledgebase/ —— 外部调用方无法无感迁移", m.getName())
                .isTrue();
            assertThat(hasConventionForm).isTrue();
        }
    }

    @Test
    @DisplayName("⭐⭐ DTO 字段必须是 snake_case（源侧契约）")
    void dtoUsesSnakeCase() {
        // 逐个 DTO 检查 @JsonProperty 值
        assertSnakeCase(WikiController.WikiPageDto.class,
            "id", "slug", "title", "page_type", "status", "summary", "content",
            "folder_id", "wiki_path", "depth", "aliases", "source_refs", "chunk_refs",
            "out_links", "in_links", "version", "created_at", "updated_at");

        assertSnakeCase(WikiController.FolderDto.class,
            "id", "parent_id", "name", "path", "depth", "sort_order");

        assertSnakeCase(WikiController.LintIssueDto.class,
            "type", "severity", "page_slug", "target_slug", "description", "auto_fixable");

        assertSnakeCase(WikiController.LintResponse.class,
            "issues", "health_score", "summary", "error_count", "warning_count", "info_count");

        assertSnakeCase(WikiController.RevisionDto.class,
            "id", "slug", "version", "title", "page_type", "summary", "content",
            "edit_source", "edited_at");
    }

    private static void assertSnakeCase(Class<?> recordClass, String... expected) {
        var components = recordClass.getRecordComponents();
        Set<String> actual = new TreeSet<>();
        for (var c : components) {
            // ⚠️ @JsonProperty 在 record 上可能只落到「字段」而不是 RecordComponent
            //    （位置+目标不同）⇒ 两个位置都查一遍。
            var ann = c.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (ann == null) {
                try {
                    var field = recordClass.getDeclaredField(c.getName());
                    ann = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                } catch (NoSuchFieldException ignored) {
                    // 没有同名字段 ⇒ 保持 null
                }
            }
            assertThat(ann)
                .as("%s.%s 缺少 @JsonProperty —— Jackson 会输出 camelCase，破坏源侧契约",
                    recordClass.getSimpleName(), c.getName())
                .isNotNull();
            actual.add(ann.value());
            assertThat(ann.value())
                .as("@JsonProperty 值必须是 snake_case，实际是 %s", ann.value())
                .matches("^[a-z][a-z0-9_]*$");
        }
        assertThat(actual)
            .as("%s 字段不齐", recordClass.getSimpleName())
            .containsExactlyInAnyOrder(expected);
    }

    @Test
    @DisplayName("⭐ 请求体 DTO 也用 snake_case")
    void requestDtosSnakeCase() {
        assertSnakeCase(WikiController.WikiSearchRequest.class,
            "query", "knowledge_base_ids", "limit");
        assertSnakeCase(WikiController.RevertRequest.class, "slug", "version");
        assertSnakeCase(WikiController.MovePageRequest.class, "slug", "path");
    }

    @Test
    @DisplayName("⭐⭐ slug 端点必须用两段式 {type}/{name}（Spring 6 的 catch-all 不可靠）")
    void slugUsesTwoSegmentPath() {
        // ⭐⭐ 实测结论（3472 真机）：
        //   · {slug:.+} 在 PathPatternParser 下不跨段 ⇒ /pages/entity/acme 404
        //   · {*slug} catch-all 在本仓配置下同样 404
        //   ⇒ 唯一稳定形态 = {type}/{name}
        int checked = 0;
        for (Method m : mappings()) {
            for (String p : pathsOf(m)) {
                if (!p.contains("/pages/") && !p.contains("/revisions/")) {
                    continue;
                }
                if (!p.contains("{type}") && !p.contains("{slug") && !p.contains("{*")) {
                    continue;   // 不带路径变量的（如 POST /pages）
                }
                assertThat(p)
                    .as("⭐⭐ %s 的路径必须用 {type}/{name}；写成 {slug:.+} 或 {*slug} 会 404，"
                        + "而且只有带斜杠的 slug 才触发，极难排查", m.getName())
                    .contains("{type}/{name}");
                checked++;
            }
        }
        assertThat(checked).isPositive();
    }
}
