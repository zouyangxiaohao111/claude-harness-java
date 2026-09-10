package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [skill-listing-cc-align 2026-09-10 收尾轮] <b>compact 管线不得 reset skill_listing 去重态</b> 的静态守卫。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 + CC compact.ts:548-553）</b>：CC 明令压缩后<b>不</b> reset
 * {@code sentSkillNames}（post-compact 重注入 ~4K token/事件、收益边际）。nexusai 对应
 * {@link SkillListingSentRegistry} 的 {@code reset() / resetSentAllSessions()}。行为级守卫见
 * {@code SkillListingSentRegistryTest#compactRealCleanup_doesNotResetRegistry}（真实驱动
 * {@code PostCompactCleanup.runPostCompactCleanup()}）。本测试再补一层：扫 {@code ...agent.compact} 包
 * 全部源码，禁止出现对注册表 reset 语义方法的调用 —— 覆盖「reset 被加到 compact 包其它类」的变异。
 * 在 compact 包任意文件加一行 {@code SkillListingSentRegistry.reset()} → 本测试 RED。
 */
@DisplayName("[skill-listing-cc-align] compact 包不得 reset skill_listing 注册表（源码守卫）")
class SkillListingCompactNoResetGuardTest {

    /** 相对 backend 模块根（surefire 工作目录 = 模块 basedir，与 ReactiveCompactorCcContractTest 同款）。 */
    private static final Path COMPACT_PKG = Path.of("src/main/java/com/nexusai/application/agent/compact");

    /** CC compact.ts:548-553 禁止的「清空去重态 → 重注入」动作（方法名带左括号以避开 javadoc 文字引用）。 */
    private static final List<String> FORBIDDEN_CALLS = List.of(
        "SkillListingSentRegistry.reset(",
        "SkillListingSentRegistry.resetSentAllSessions(");

    @Test
    @DisplayName("compact 包源码不得调用 SkillListingSentRegistry.reset/resetSentAllSessions")
    void compactPackage_neverResetsSkillListingRegistry() throws IOException {
        assertThat(Files.isDirectory(COMPACT_PKG))
            .as("compact 包源码目录必须存在（相对 backend 模块根）: %s", COMPACT_PKG.toAbsolutePath())
            .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(COMPACT_PKG)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (String forbidden : FORBIDDEN_CALLS) {
                    if (src.contains(forbidden)) {
                        offenders.add(f.getFileName() + " → " + forbidden);
                    }
                }
            }
        }
        assertThat(offenders)
            .as("压缩后清理不得 reset skill_listing 去重态（CC compact.ts:548-553 明令不 reset）")
            .isEmpty();
    }
}
