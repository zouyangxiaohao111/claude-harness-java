package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BD-18 debug skill 忠实 tail 逻辑锁定（RED→GREEN 行为验证）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>CC debug.ts:25-99 getPromptForCommand 真实 tail 逻辑</b>——enableDebugLogging →
 *       getDebugLogPath → stat+tail read（切最后 20 行）→ 拼装 prompt（log size + tail +
 *       just-enabled 段 + args + settings 路径 + instructions）。Java DebugSkillRegistrar 逻辑忠实，
 *       本测试锁定三段关键语义：tail 读（非空串 → 真实尾块）、ENOENT → fallback、其他 IOException → fallback，
 *       防退回空桩（旧 Bootstrapper 注入 tailReader=空串 恒走 "No debug log exists yet" 分支）。</li>
 *   <li><b>justEnabled 段条件渲染</b>——wasAlreadyLogging=false → 渲染 "Debug Logging Just Enabled"，
 *       true → 不渲染（CC debug.ts:59-67）。断言锁定该状态机。</li>
 * </ol>
 */
class DebugSkillRegistrarTest {

    @Test
    @DisplayName("getPromptForCommand 真实 tail：log size + 最后 20 行 + just-enabled + settings 路径 + instructions")
    void promptContainsRealTail() {
        DebugSkillRegistrar registrar = new DebugSkillRegistrar(
            () -> false,                              // isAnt
            () -> "/tmp/debug.log",
            () -> false,                              // wasAlreadyLogging=false → just-enabled 段渲染
            (path, offset, size) -> "line1\nline2\nline3\n",   // 真实 tailReader
            path -> new DebugSkillRegistrar.FileStat(1000),     // 真实 statReader
            bytes -> bytes + " B",                              // fileSizeFormatter
            source -> "/settings/" + source + ".json");         // settingsPathProvider

        List<PromptBlock> blocks = registrar.getPromptForCommand("tool X 失败");
        String prompt = blocks.get(0).text();

        assertThat(prompt)
            .as("CC debug.ts:69-99 prompt 结构")
            .contains("# Debug Skill")
            .contains("Log size: 1000 B")
            .contains("### Last 20 lines")
            .contains("line1")
            .contains("line2")
            .contains("line3")
            .as("CC debug.ts:59-67 just-enabled 段（wasAlreadyLogging=false）")
            .contains("## Debug Logging Just Enabled")
            .contains("/tmp/debug.log")
            .contains("## Issue Description")
            .contains("tool X 失败")
            .as("CC debug.ts:85-90 settings 三路径")
            .contains("* user - /settings/userSettings.json")
            .contains("* project - /settings/projectSettings.json")
            .contains("* local - /settings/localSettings.json")
            .contains("## Instructions");
    }

    @Test
    @DisplayName("ENOENT（NoSuchFileException）→ 'No debug log exists yet — logging was just enabled.'")
    void enoentFallsBackToNoLogYet() {
        DebugSkillRegistrar registrar = new DebugSkillRegistrar(
            () -> false, () -> "/tmp/debug.log", () -> false,
            (p, o, s) -> "x",
            path -> { throw new NoSuchFileException(path); },
            b -> "" + b, source -> source);

        List<PromptBlock> blocks = registrar.getPromptForCommand(null);

        assertThat(blocks.get(0).text())
            .as("CC debug.ts:54-55 isENOENT → 'No debug log exists yet'")
            .contains("No debug log exists yet — logging was just enabled.");
    }

    @Test
    @DisplayName("其他 IOException → 'Failed to read last 20 lines...' 且 wasAlreadyLogging=true 不渲染 just-enabled")
    void otherIoErrorFallsBackWithMessage() {
        DebugSkillRegistrar registrar = new DebugSkillRegistrar(
            () -> false, () -> "/tmp/debug.log", () -> true,   // wasAlreadyLogging=true
            (p, o, s) -> "x",
            path -> { throw new java.io.IOException("boom"); },
            b -> "" + b, source -> source);

        List<PromptBlock> blocks = registrar.getPromptForCommand(null);

        assertThat(blocks.get(0).text())
            .as("CC debug.ts:56 isENOENT=false → 'Failed to read last 20 lines'")
            .contains("Failed to read last 20 lines of debug log: boom")
            .as("CC debug.ts:59-67 wasAlreadyLogging=true → just-enabled 段不渲染")
            .doesNotContain("## Debug Logging Just Enabled");
    }

    @Test
    @DisplayName("register() 产出 debug skill：disableModelInvocation=true + allowedTools=[Read,Grep,Glob] + argumentHint")
    void registerProducesDebugDefinition() {
        DebugSkillRegistrar registrar = new DebugSkillRegistrar(
            () -> false, () -> "/tmp/debug.log", () -> false,
            (p, o, s) -> "x", path -> new DebugSkillRegistrar.FileStat(0),
            b -> "" + b, source -> source);

        BundledSkillDefinition def = registrar.register();

        assertThat(def.name()).isEqualTo("debug");
        assertThat(def.disableModelInvocation())
            .as("CC debug.ts:23 disableModelInvocation=true（用户显式调用，免占 context）")
            .isTrue();
        assertThat(def.userInvocable()).as("CC debug.ts:24 userInvocable=true").isTrue();
        assertThat(def.allowedTools()).as("CC debug.ts:19 allowedTools=[Read,Grep,Glob]")
            .containsExactly("Read", "Grep", "Glob");
        assertThat(def.argumentHint()).as("CC debug.ts:20 argumentHint='[issue description]'")
            .isEqualTo("[issue description]");
    }
}
