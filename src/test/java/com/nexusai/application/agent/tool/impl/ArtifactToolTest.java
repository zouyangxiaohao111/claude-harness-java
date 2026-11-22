package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArtifactTool（本地版）意图测试 · 对齐 exe 2.1.328 ArtifactTool 的语义，发布目标改为本地。
 *
 * <p>实验性质：工具暂不接 Spring（无 @Component），测试直接 new + 注入临时根。
 */
@DisplayName("Artifact · 本地发布（created/updated/类型/路径越狱）")
class ArtifactToolTest {

    @TempDir
    Path tmp;

    private static ToolUseBlock call(String id, ObjectNode input) {
        return new ToolUseBlock(id, ArtifactTool.NAME, input);
    }

    private static ObjectNode input(String path, String content, String type, String slug) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        if (path != null) n.put("path", path);
        n.put("content", content);
        if (type != null) n.put("type", type);
        if (slug != null) n.put("slug", slug);
        return n;
    }

    private static String text(Object result) {
        return ToolResult.renderToolResultPayloadText((ToolResult<?>) result);
    }

    @Test
    @DisplayName("首次发布 → created + 文件落盘 + 内容一致")
    void firstPublish_created() throws Exception {
        ArtifactTool tool = new ArtifactTool(tmp);

        String out = text(tool.execute(call("c1", input("report.html", "<h1>Hi</h1>", "page", null))));

        assertThat(out).contains("status: created").contains("artifact_type: page");
        Path f = tmp.resolve("report.html");
        assertThat(Files.exists(f)).isTrue();
        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo("<h1>Hi</h1>");
    }

    @Test
    @DisplayName("再次发布同一路径 → updated")
    void republish_updated() {
        ArtifactTool tool = new ArtifactTool(tmp);
        tool.execute(call("c1", input("deck/index.html", "<h1>A</h1>", "slide_deck", "deck")));

        String out = text(tool.execute(call("c2", input("deck/index.html", "<h1>B</h1>", "slide_deck", "deck"))));

        assertThat(out).contains("status: updated");
    }

    @Test
    @DisplayName("无 path 有 slug → 默认 <slug>/index.html；类型保留")
    void slugDefaultsPath() throws Exception {
        ArtifactTool tool = new ArtifactTool(tmp);

        String out = text(tool.execute(call("c1", input(null, "<html></html>", "data_dashboard", "sales"))));

        assertThat(out).contains("slug: sales").contains("artifact_type: data_dashboard");
        assertThat(Files.exists(tmp.resolve("sales/index.html"))).isTrue();
    }

    @Test
    @DisplayName("未知类型 → 错误（不落盘）")
    void unknownType_rejected() {
        ArtifactTool tool = new ArtifactTool(tmp);

        String out = text(tool.execute(call("c1", input("x.html", "<p/>", "poster", null))));

        assertThat(out).contains("Unknown artifact type").contains("poster");
        assertThat(Files.exists(tmp.resolve("x.html"))).isFalse();
    }

    @Test
    @DisplayName("路径越狱 → 错误（不落盘）")
    void pathEscape_rejected() {
        ArtifactTool tool = new ArtifactTool(tmp);

        String out = text(tool.execute(call("c1", input("../escape.html", "<p/>", "page", null))));

        assertThat(out).contains("Path escapes");
        assertThat(Files.exists(tmp.getParent().resolve("escape.html"))).isFalse();
    }

    @Test
    @DisplayName("已接线 Spring（用户 2026-11-19 裁定：直接接线）")
    void springManaged() {
        assertThat(ArtifactTool.class.getAnnotation(org.springframework.stereotype.Component.class))
            .as("ArtifactTool 是 @Component，自动进工具池")
            .isNotNull();
    }
}
