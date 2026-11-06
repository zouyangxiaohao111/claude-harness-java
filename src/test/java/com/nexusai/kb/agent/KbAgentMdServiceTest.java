package com.nexusai.kb.agent;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智能体 MD 账本 · 渲染 / 解析 / 路径安全。
 *
 * <p><b>WHY 这些断言</b>：
 * <ul>
 *   <li><b>id 被拼进路径</b> ⇒ 必须挡住 {@code ../}（路径穿越，能写到 agents 目录之外）</li>
 *   <li><b>空值写进 frontmatter</b> ⇒ {@code tools: ""} 会被解析成「空工具集」而不是「全部」，静默改变权限</li>
 *   <li><b>多行 description</b> ⇒ 不压成单行会打断 frontmatter，整个智能体加载失败</li>
 * </ul>
 */
@DisplayName("[W10] 智能体 MD 账本")
class KbAgentMdServiceTest {

    @TempDir
    Path tmp;

    private KbAgentMdService service;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
        service = new KbAgentMdService();
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ── ⭐ 路径安全 ─────────────────────────────────────────

    @Test
    @DisplayName("⭐ 拒绝路径穿越的 id")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> service.mdPath("../evil"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.mdPath("a/b"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.mdPath("a\\b"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.mdPath(""))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("合法 id 通过（字母数字下划线连字符）")
    void acceptsSafeIds() {
        assertThat(service.mdPath("agri-assistant").toString()).endsWith("agri-assistant.md");
        assertThat(service.mdPath("a_b-1").toString()).endsWith("a_b-1.md");
    }

    @Test
    @DisplayName("⭐ 中文 id 合法（现有 agents 里就有「农污助手」）且映射到稳定文件名")
    void chineseIdAllowed() {
        String f1 = KbAgentMdService.fileNameFor("农污助手");
        String f2 = KbAgentMdService.fileNameFor("农污助手");
        assertThat(f1).as("同一 id 必须映射到同一文件（确定性）").isEqualTo(f2);
        assertThat(f1).endsWith(".md");
        assertThat(KbAgentMdService.fileNameFor("农污诊断"))
            .as("不同 id 不能撞车")
            .isNotEqualTo(f1);
    }

    @Test
    @DisplayName("⭐ 中文 id 端到端：写入后能被 listMdIds 读回（不能靠文件名还原）")
    void chineseIdRoundTrip() {
        service.write("农污助手", new KbAgentMdService.MdContent(
            null, "农污问答", "Read", null, null, null, "正文"));
        assertThat(service.listMdIds()).containsExactly("农污助手");
        assertThat(service.readView("农污助手").name()).isEqualTo("农污助手");
    }

    @Test
    @DisplayName("⭐ 缺 description ⇒ 400（缺它智能体不会被加载）")
    void missingDescriptionRejected() {
        assertThatThrownBy(() -> service.write("a", new KbAgentMdService.MdContent(
            "A", null, null, null, null, null, "正文")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("description");
    }

    // ── 渲染 / 解析往返 ────────────────────────────────────

    @Test
    @DisplayName("写入后能读回全部字段")
    void writeThenRead() {
        service.write("agri", new KbAgentMdService.MdContent(
            "农污助手", "农村污水治理问答", "Read, Glob, Grep",
            "deepseek/deepseek-flash", "deepseek/deepseek-v4-pro", "cyan", "你是农污专家。"));

        KbAgentMdService.MdView v = service.readView("agri");
        assertThat(v.name()).as("name 恒等于 id（= agentType）").isEqualTo("agri");
        assertThat(v.description()).isEqualTo("农村污水治理问答");
        assertThat(v.tools()).isEqualTo("Read, Glob, Grep");
        assertThat(v.model()).isEqualTo("deepseek/deepseek-flash");
        assertThat(v.mainModel()).isEqualTo("deepseek/deepseek-v4-pro");
        assertThat(v.color()).isEqualTo("cyan");
        assertThat(v.body()).isEqualTo("你是农污专家。");
    }

    @Test
    @DisplayName("⭐ 空值不写入 frontmatter（否则 tools:\"\" 会被当成空工具集）")
    void blankValuesOmitted() throws Exception {
        service.write("a", new KbAgentMdService.MdContent(
            null, "描述", null, null, null, null, "提示词"));

        String raw = Files.readString(service.mdPath("a"), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("tools:");
        assertThat(raw).doesNotContain("model:");
        assertThat(raw).doesNotContain("main-model:");
        // ⭐ name 恒等于 id（它就是 agentType，不是另一个显示名）
        assertThat(raw).contains("name: a");
    }

    @Test
    @DisplayName("⭐ 多行 description 压成单行（否则打断 frontmatter）")
    void multilineDescriptionFlattened() throws Exception {
        service.write("a", new KbAgentMdService.MdContent(
            "A", "第一行\n第二行", null, null, null, null, "正文"));

        String raw = Files.readString(service.mdPath("a"), StandardCharsets.UTF_8);
        assertThat(raw).contains("description: 第一行 第二行");
        // frontmatter 必须能闭合
        assertThat(service.readView("a").body()).isEqualTo("正文");
    }

    @Test
    @DisplayName("正文不能为空")
    void blankBodyRejected() {
        assertThatThrownBy(() -> service.write("a", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "   ")))
            .isInstanceOf(ValidationException.class);
    }

    // ── 列表 / 删除 / 复制 ─────────────────────────────────

    @Test
    @DisplayName("列表按文件名排序，忽略非 md")
    void listIds() throws Exception {
        Files.createDirectories(service.agentsDir());
        service.write("b", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "x"));
        service.write("a", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "x"));
        Files.writeString(service.agentsDir().resolve("notes.txt"), "ignore me");

        assertThat(service.listMdIds()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("删除幂等")
    void deleteIdempotent() {
        service.write("a", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "x"));
        assertThat(service.delete("a")).isTrue();
        assertThat(service.delete("a")).isFalse();
    }

    @Test
    @DisplayName("复制：目标已存在 ⇒ 409")
    void copyConflict() {
        service.write("a", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "x"));
        service.write("b", new KbAgentMdService.MdContent(
            "A", "描述", null, null, null, null, "y"));
        assertThatThrownBy(() -> service.copy("a", "b", null))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("复制：内容完整带过去")
    void copyPreservesContent() {
        service.write("a", new KbAgentMdService.MdContent(
            "A", "描述", "Read", "m/sub", "m/main", "blue", "正文"));
        service.copy("a", "a-copy", null);

        KbAgentMdService.MdView v = service.readView("a-copy");
        assertThat(v.name()).as("复制后 name 跟着新 id 走").isEqualTo("a-copy");
        assertThat(v.tools()).isEqualTo("Read");
        assertThat(v.model()).isEqualTo("m/sub");
        assertThat(v.mainModel()).isEqualTo("m/main");
        assertThat(v.body()).isEqualTo("正文");
    }

    @Test
    @DisplayName("读不存在的 ⇒ 404")
    void readMissing() {
        assertThatThrownBy(() -> service.readView("nope"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("无 frontmatter 的 MD ⇒ 正文保留，字段为 null")
    void plainMarkdownWithoutFrontmatter() throws Exception {
        Files.createDirectories(service.agentsDir());
        Files.writeString(service.agentsDir().resolve("plain.md"), "只有正文");

        KbAgentMdService.MdView v = service.readView("plain");
        assertThat(v.body()).isEqualTo("只有正文");
        assertThat(v.name()).isNull();
    }

    @Test
    @DisplayName("兼容驼峰 mainModel")
    void camelCaseMainModel() throws Exception {
        Files.createDirectories(service.agentsDir());
        Files.writeString(service.agentsDir().resolve("c.md"),
            "---\nname: C\nmainModel: x/y\n---\n\n正文");

        assertThat(service.readView("c").mainModel()).isEqualTo("x/y");
    }
}
