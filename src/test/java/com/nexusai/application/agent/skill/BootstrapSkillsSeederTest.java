package com.nexusai.application.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BootstrapSkillsSeeder 引导语义测试(规则九:测意图)。
 *
 * <p>WHY(意图):内置技能在<code>skills 目录为空/不存在</code>时首次复制(空才引导,不覆盖用户已有
 * 技能)。测试锁定:①空 → 从 classpath skills 复制(≥1 技能且含 SKILL.md);②非空 → 跳过(0 复制,
 * 尊重用户自装);③复制结果可被技能加载器识别(SKILL.md 在技能根)。
 */
@DisplayName("BootstrapSkillsSeeder: skills 空时从内置 resource 引导复制(非空跳过)")
class BootstrapSkillsSeederTest {

    @TempDir
    Path tempDir;

    Path cfgHome;
    BootstrapSkillsSeeder seeder;

    @BeforeEach
    void setUp() throws Exception {
        cfgHome = Files.createDirectories(tempDir.resolve("cfg-home"));
        NexusaiPaths.setConfigHomeDirOverride(cfgHome.toString());
        seeder = new BootstrapSkillsSeeder();
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @Test
    @DisplayName("skills 目录不存在(全新 config home)→ 从 classpath 复制内置技能(≥1 技能,技能根含 SKILL.md)")
    void seedIfEmpty_absentCopiesFromClasspath() throws Exception {
        int n = seeder.seedIfEmpty();

        assertThat(n).isGreaterThan(0);
        Path skillsRoot = cfgHome.resolve("skills");
        assertThat(skillsRoot).exists().isDirectory();
        try (var s = Files.list(skillsRoot)) {
            assertThat(s.findAny()).isPresent(); // 至少一个技能目录
        }
        // 抽查:存在一个含 SKILL.md 的技能目录(可被技能加载器识别)
        try (var s = Files.list(skillsRoot)) {
            assertThat(s.map(d -> d.resolve("SKILL.md")).filter(Files::exists).findAny()).isPresent();
        }
    }

    @Test
    @DisplayName("skills 目录已有用户内容(非空)→ 跳过复制(0,不覆盖用户技能)")
    void seedIfEmpty_nonEmptySkips() throws Exception {
        Path skillsRoot = Files.createDirectories(cfgHome.resolve("skills"));
        Files.createDirectories(skillsRoot.resolve("my-custom-skill"));
        Files.writeString(skillsRoot.resolve("my-custom-skill").resolve("SKILL.md"), "# my skill\n");

        int n = seeder.seedIfEmpty();

        assertThat(n).isZero(); // 非空不复制
        // 用户技能保留,未被打包技能侵入(目录仍只有用户的)
        try (var s = Files.list(skillsRoot)) {
            assertThat(s).noneMatch(d -> d.getFileName().toString().startsWith("design-")
                || d.getFileName().toString().equals("canvas-design"));
        }
    }

    @Test
    @DisplayName("幂等:空时首次复制后再次调用仍为 0(已有内置技能=非空 → 跳过)")
    void seedIfEmpty_idempotent() throws Exception {
        int first = seeder.seedIfEmpty();
        assertThat(first).isGreaterThan(0);
        int second = seeder.seedIfEmpty();
        assertThat(second).isZero(); // 已复制(非空)→ 不再复制
    }
}
