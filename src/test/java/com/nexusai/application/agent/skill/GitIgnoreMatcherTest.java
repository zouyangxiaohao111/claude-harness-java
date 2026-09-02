package com.nexusai.application.agent.skill;

import com.nexusai.infra.util.GitIgnoreMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 gitignore-style 匹配器测试 · 对齐 CC {@code ignore} npm 包
 * （loadSkillsDir.ts:1012 {@code ignore().add(skill.paths)}）条件技能 paths 匹配语义。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）: 条件技能激活依赖 paths 精确匹配 ——
 * {@code src/**\/*.ts} 必须命中 {@code src/a/b.ts} 且不命中 {@code docs/b.ts}，否则
 * 条件技能会在错误的文件操作上激活 / 应激活时不激活（CC loadSkillsDir.ts:1029-1038）。
 */
@DisplayName("P1-2 GitIgnoreMatcher · 条件技能 paths gitignore 语义（CC ignore 包）")
class GitIgnoreMatcherTest {

    @Test
    @DisplayName("src/**/*.ts 命中深层 ts、不命中 js / 其他目录 · CC skill paths 典型形态")
    void globDoubleStar_tsPattern() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("src/**/*.ts"));
        assertThat(m.ignores("src/a/b.ts")).isTrue();
        assertThat(m.ignores("src/a.ts")).isTrue();   // **/ 匹配零个目录
        assertThat(m.ignores("src/a/b.js")).isFalse();
        assertThat(m.ignores("docs/b.ts")).isFalse(); // 锚定 src
    }

    @Test
    @DisplayName("basename 任意层级匹配：foo 命中 a/foo 与 a/foo/b")
    void basenameMatchesAnyLevel() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("foo"));
        assertThat(m.ignores("foo")).isTrue();
        assertThat(m.ignores("a/foo")).isTrue();
        assertThat(m.ignores("a/foo/b.txt")).isTrue();
        assertThat(m.ignores("a/bar")).isFalse();
    }

    @Test
    @DisplayName("/ 锚定：/foo 只命中根下 foo，不命中 a/foo")
    void leadingSlashAnchors() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("/foo"));
        assertThat(m.ignores("foo")).isTrue();
        assertThat(m.ignores("a/foo")).isFalse();
    }

    @Test
    @DisplayName("trailing / 目录：docs/ 命中 docs 本身及其下所有内容")
    void trailingSlashDirectory() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("docs/"));
        assertThat(m.ignores("docs/readme.md")).isTrue();
        assertThat(m.ignores("docs")).isTrue();
        assertThat(m.ignores("a/docs/readme.md")).isTrue();
        assertThat(m.ignores("readme.md")).isFalse();
    }

    @Test
    @DisplayName("! negation last-match-wins：src/** 排除后 !src/gen.ts 重新纳入")
    void negationLastMatchWins() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("src/**", "!src/gen.ts"));
        assertThat(m.ignores("src/a/b.ts")).isTrue();
        assertThat(m.ignores("src/gen.ts")).isFalse();
    }

    @Test
    @DisplayName("单一 * 不跨目录：src/*.ts 只命中 src 直属，不命中子目录")
    void singleStarNoCrossDir() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("src/*.ts"));
        assertThat(m.ignores("src/a.ts")).isTrue();
        assertThat(m.ignores("src/a/b.ts")).isFalse();
    }

    @Test
    @DisplayName("路径归一化：反斜杠 / ./ 前缀剥离 · Windows 相对路径可匹配")
    void normalizesPaths() {
        GitIgnoreMatcher m = new GitIgnoreMatcher(List.of("src/**/*.ts"));
        assertThat(m.ignores("src\\a\\b.ts")).isTrue();      // Windows 分隔符
        assertThat(m.ignores("./src/a/b.ts")).isTrue();      // ./ 前缀
    }
}
