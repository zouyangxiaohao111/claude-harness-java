package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bundled 注册集无 fake 测试（P0-7）· 对齐 CC bundled/index.ts:24-34（10 always-on）+:39-77（7 feature-gated）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>6 个 fake（commit/review-pr/plan/fix/explain/refactor）是 Java 自造占位</b>（旧
 *       BundledSkills 内置注册，CC 全集 grep 0 命中）——从未 setContent，经 SkillToolImpl 调用必然
 *       报 'Skill has no content'，同时污染 SkillRegistry/SkillCatalog 模型技能目录（R2）。删除即消除
 *       占位污染，bundled 注册集对齐 CC 真实全集。</li>
 *   <li><b>防过度删除</b>——断言 11 个真实 skill（batch/claude-api/debug/loop/remember/schedule/
 *       simplify/skillify/stuck/update-config/verify）仍在，若实施误删真实注册此断言必红。</li>
 * </ol>
 */
class BundledSkillsBootstrapperNoFakesTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("bundled 注册集不含 6 个 fake，且 11 个真实 skill 仍在")
    void bundledRegistryHasNoFakeSkills() {
        // P2-6：remember 走真实 ant 早返（CC remember.ts:5-7），本测试验证注册集完整性（防过度删除），
        // 故注入 isAntSupplier=() -> true 使 remember 经 ant 门控注册（不再依赖 USER_TYPE env / 硬编码 true）。
        new BundledSkillsBootstrapper(() -> false, () -> true).run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toList());

        // 6 fake 必须不在（RED 阶段存在 → 此断言失败；删除后 GREEN）
        assertThat(names)
            .doesNotContain("commit", "review-pr", "plan", "fix", "explain", "refactor");
        // 11 真实 skill 必须仍在（防过度删除）
        assertThat(names)
            .contains("batch", "claude-api", "debug", "loop", "remember", "schedule",
                "simplify", "skillify", "stuck", "update-config", "verify");
    }
}
