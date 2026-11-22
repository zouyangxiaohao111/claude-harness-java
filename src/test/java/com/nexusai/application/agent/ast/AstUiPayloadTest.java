package com.nexusai.application.agent.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AstUiPayload 的载荷形状与序列化闸口。
 *
 * <p>WHY：前端只认 {@code kind} + 固定字段；序列化闸口必须只对 AstGrep/AstEdit 的结构化结果生效
 * （否则会把其它工具的 Map 误当 UI 载荷，模型面/UI 面串味）。
 */
@DisplayName("AstUiPayloadTest · kind/截断/序列化闸口")
class AstUiPayloadTest {

    private static AstMatch match(String file, int line, String text, String meta) {
        return new AstMatch(file, text, "Java", line, 3, line, 3 + text.length(),
            line * 10, line * 10 + text.length(),
            meta == null ? Map.of() : Map.of("A", meta), null);
    }

    private static AstGrepRunner.Page page(List<AstMatch> matches) {
        return new AstGrepRunner.Page(matches, matches.size(), false, 4, List.of(), false);
    }

    @Test
    @DisplayName("grep 载荷：kind/pattern/scope/字段齐全，同文件分组、行列原样")
    @SuppressWarnings("unchecked")
    void grepPayloadShape() {
        Map<String, Object> payload = AstUiPayload.grep("foo($A)", null, "src",
            page(List.of(match("src/A.java", 5, "foo(1)", "1"), match("src/A.java", 7, "foo(2)", "2"))), 0);

        assertThat(payload).containsEntry("kind", "ast_grep")
            .containsEntry("pattern", "foo($A)")
            .containsEntry("scope", "src")
            .containsEntry("filesSearched", 4)
            .containsEntry("totalMatches", 2)
            .containsEntry("truncated", false)
            .containsEntry("more", 0);
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0)).containsEntry("path", "src/A.java").containsEntry("language", "Java");
        List<Map<String, Object>> matches = (List<Map<String, Object>>) files.get(0).get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0)).containsEntry("line", 5).containsEntry("column", 3);
        assertThat((Map<String, String>) matches.get(0).get("meta")).containsEntry("A", "1");
    }

    @Test
    @DisplayName("超过 30 条匹配 → truncated=true 且 more 为省略条数；长文本截到 200 字符")
    @SuppressWarnings("unchecked")
    void grepTruncates() {
        List<AstMatch> matches = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            matches.add(match("a.js", i, "x".repeat(300), null));
        }
        Map<String, Object> payload = AstUiPayload.grep("p", null, ".", page(matches), 0);
        assertThat(payload).containsEntry("truncated", true).containsEntry("more", 5);
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        List<Map<String, Object>> list = (List<Map<String, Object>>) files.get(0).get("matches");
        assertThat(list).hasSize(30);
        assertThat((String) list.get(0).get("text")).hasSize(201).endsWith("…");
    }

    @Test
    @DisplayName("edit 载荷：applied/ops/paths/totalReplacements/filesTouched")
    @SuppressWarnings("unchecked")
    void editPayloadShape() {
        AstEditPlan plan = new AstEditPlan("a.js", List.of(
            new AstEditPlan.Change(0, 6, "foo(1)", "bar(1)", 1, 1, 1, 7)));
        Map<String, Object> payload = AstUiPayload.edit(false,
            List.of(new LinkedHashMap<>(Map.of("pat", "foo($A)", "out", "bar($A)"))),
            List.of("."), List.of(plan), false);

        assertThat(payload).containsEntry("kind", "ast_edit")
            .containsEntry("applied", false)
            .containsEntry("totalReplacements", 1)
            .containsEntry("filesTouched", 1)
            .containsEntry("truncated", false);
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        assertThat(files.get(0)).containsEntry("path", "a.js");
        List<Map<String, Object>> changes = (List<Map<String, Object>>) files.get(0).get("changes");
        assertThat(changes.get(0)).containsEntry("before", "foo(1)").containsEntry("after", "bar(1)");
    }

    @Test
    @DisplayName("serialize：只认 AstGrep/AstEdit + Map + kind；其它一律 null，且剔除 summary")
    void serializeGate() {
        Map<String, Object> payload = AstUiPayload.grep("p", null, ".",
            page(List.of(match("a.js", 1, "foo(1)", null))), 0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", "model facing text");
        data.putAll(payload);

        String json = AstUiPayload.serialize("AstGrep", data);
        assertThat(json).contains("\"kind\":\"ast_grep\"").doesNotContain("model facing text");
        assertThat(AstUiPayload.serialize("ast_grep", data)).isNotNull();
        assertThat(AstUiPayload.serialize("Grep", data)).isNull();
        assertThat(AstUiPayload.serialize("AstEdit", data)).as("kind 不符").isNull();
        assertThat(AstUiPayload.serialize("AstGrep", "text")).isNull();
        assertThat(AstUiPayload.serialize(null, data)).isNull();
    }
}
