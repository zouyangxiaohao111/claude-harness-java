package com.nexusai.kb.agent.api;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 技能目录端点（{@code /api/v1/kb/skills}）· 批次 W10。
 *
 * <p><b>WHY 这些断言</b>：这几个端点的错误后果都是「用户看不见但已经发生」：
 * <ul>
 *   <li><b>路径穿越</b>：{@code files/content?path=../../x} 能读出整台机器的文件；</li>
 *   <li><b>删除跟随符号链接</b>：本机 {@code ~/.claude/skills/xxx} 是指向外部目录的链接，
 *       删了会**连带删掉用户全局技能**；</li>
 *   <li><b>技能名不校验</b>：名字会拼进目录名，{@code ../} 能写到技能根之外。</li>
 * </ul>
 */
@DisplayName("[W10] 技能目录端点")
class SkillCatalogControllerTest {

    @TempDir
    Path tmp;

    private SkillCatalogController controller;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
        controller = new SkillCatalogController();
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @Test
    @DisplayName("创建后能读到（frontmatter 的 name/description/version 都落盘）")
    void createThenRead() {
        var created = controller.create(
            new SkillCatalogController.CreateRequest("my-skill", "做某件事", "正文", "1.0.0"));

        assertThat(created.id()).isEqualTo("my-skill");
        assertThat(created.description()).isEqualTo("做某件事");
        assertThat(created.version()).isEqualTo("1.0.0");
        assertThat(created.content()).contains("正文").contains("description: 做某件事");

        assertThat(controller.catalog()).extracting(SkillCatalogController.CatalogItem::id)
            .containsExactly("my-skill");
    }

    @Test
    @DisplayName("⭐ 同名再建 ⇒ 409（不覆盖用户已有技能）")
    void duplicateCreateConflicts() {
        controller.create(new SkillCatalogController.CreateRequest("dup", null, "正文", null));
        assertThatThrownBy(() -> controller.create(
            new SkillCatalogController.CreateRequest("dup", "新描述", "新正文", null)))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("⭐ 技能名不能带路径分隔符或 ..")
    void unsafeNamesRejected() {
        for (String bad : List.of("../evil", "a/b", "a b", "", "  ")) {
            assertThatThrownBy(() -> controller.create(
                new SkillCatalogController.CreateRequest(bad, null, "正文", null)))
                .as("name=[%s]", bad)
                .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("⭐ 读文件不能越出技能目录")
    void fileContentCannotEscape() throws IOException {
        controller.create(new SkillCatalogController.CreateRequest("safe", null, "正文", null));
        Files.writeString(tmp.resolve("secret.txt"), "TOP-SECRET", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> controller.fileContent("safe", "../../secret.txt"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("技能目录内");
    }

    @Test
    @DisplayName("读技能内文件正常")
    void fileContentWorks() {
        controller.create(new SkillCatalogController.CreateRequest("own", null, "正文", null));
        var got = controller.fileContent("own", "SKILL.md");

        assertThat(got.path()).isEqualTo("SKILL.md");
        assertThat(got.content()).contains("name: own");
    }

    @Test
    @DisplayName("正文为空 ⇒ 400（空技能没有意义）")
    void blankInstructionsRejected() {
        assertThatThrownBy(() -> controller.create(
            new SkillCatalogController.CreateRequest("empty", "描述", "  ", null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("instructions");
    }

    @Test
    @DisplayName("⭐ 删除不跟随符号链接（否则会删掉外部真目录）")
    void deleteRefusesSymlink() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside-skill"));
        Files.writeString(outside.resolve("SKILL.md"), "---\nname: ext\n---\n", StandardCharsets.UTF_8);
        Path skills = Files.createDirectories(
            tmp.resolve("skills"));
        Path link = skills.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            return; // 无权限/不支持符号链接的环境跳过（不制造假红）
        }

        assertThatThrownBy(() -> controller.delete("linked"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("符号链接");
        assertThat(Files.isRegularFile(outside.resolve("SKILL.md")))
            .as("外部真目录必须完好").isTrue();
    }

    @Test
    @DisplayName("删除后目录消失")
    void deleteRemovesDir() {
        controller.create(new SkillCatalogController.CreateRequest("temp", null, "正文", null));
        assertThat(controller.delete("temp").deleted()).isTrue();
        assertThatThrownBy(() -> controller.delete("temp"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("⭐ install 如实返回 installed:false（本仓无沙箱，不假装成功）")
    void installIsHonest() {
        controller.create(new SkillCatalogController.CreateRequest("inst", null, "正文", null));
        var r = controller.install("inst");

        assertThat(r.installed()).isFalse();
        assertThat(r.note()).contains("沙箱");
    }

    @Test
    @DisplayName("目录只认含 SKILL.md 的子目录")
    void onlySkillMdDirsListed() throws IOException {
        Files.createDirectories(tmp.resolve("skills").resolve("not-a-skill"));
        assertThat(controller.catalog()).isEmpty();
    }
}
