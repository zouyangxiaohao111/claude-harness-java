package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.command.ReleaseNotesCommand;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandRegistrationConfigCInfoSettings 接线测试 · 组 C：信息/设置 命令（release-notes /
 * security-review / privacy-settings / think-back）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>security-review（prompt）</b>——注册为 type='prompt'，经 SkillRegistry.getModelInvocableCommands
 *       模型可调用；promptFn 生成 CC 契约 prompt（安全工程师指令 + 5 类漏洞 + 17 条 hard exclusions），
 *       且剥离 frontmatter（不含 allowed-tools:，对齐 CC parseFrontmatter）；allowedTools 10 项。
 *       默认门控开启（CC 无 gate）。</li>
 *   <li><b>release-notes（local）</b>——注册为 type='local' 元数据（web GET /api/command 可见），
 *       handler 路由 /release-notes → 执行；ReleaseNotesCommand 读 CHANGELOG.md → "Version X:\n· note"，
 *       缺失 → changelog 链接（对齐 CC release-notes.ts:19-50 回落链）。</li>
 *   <li><b>privacy-settings（local-jsx）</b>——注册为 type='local-jsx'，门控 isConsumerSubscriber 默认关
 *       （web 无 claude.ai 订阅模型，对齐 CC privacy-settings/index.ts:8-10）；handler 路由 /privacy-settings。</li>
 *   <li><b>think-back（local-jsx）</b>——注册为 type='local-jsx'，门控 statsig tengu_thinkback 默认关
 *       （Java 无 statsig，对齐 CC thinkback/index.ts:8-10）；handler 路由 /think-back。</li>
 *   <li><b>模型可调用过滤</b>——getModelInvocableCommands 含 prompt 命令（security-review）、
 *       排除 local/local-jsx 命令（对齐 CC commands.ts:568 type==='prompt' 过滤）。</li>
 * </ol>
 */
class CommandRegistrationConfigCInfoSettingsTest {

    private final CommandRegistrationConfigCInfoSettings config = new CommandRegistrationConfigCInfoSettings();

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @AfterEach
    void clearRegistryAfter() {
        BundledSkills.clear();
    }

    private Map<String, Command> byName() {
        return BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity(), (a, b) -> a));
    }

    @Test
    @DisplayName("security-review 注册为 prompt 型 + promptFn 生成 CC 契约且剥离 frontmatter")
    void securityReviewPromptRegistered() {
        config.commandBundledRegistrationC();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKey("security-review");
        Command sr = cmds.get("security-review");
        assertThat(sr.getType()).isEqualTo("prompt");
        // 无 gate → 恒启用（CC types/command.ts:214-215 isEnabled?.() ?? true）
        assertThat(sr.isCommandEnabled()).isTrue();

        // promptFn → 安全工程师指令（CC security-review.ts:11），剥离 frontmatter（不泄露 allowed-tools）
        String prompt = renderPromptFn(sr, "");
        assertThat(prompt).contains("You are a senior security engineer")
            .contains("SECURITY CATEGORIES TO EXAMINE")
            .contains("FALSE POSITIVE FILTERING")
            .doesNotContain("allowed-tools:");

        // allowedTools 10 项（git 5 + Read/Glob/Grep/LS/Task）
        assertThat(sr.getAllowedTools()).contains("Bash(git diff:*)", "Bash(git status:*)",
            "Bash(git log:*)", "Bash(git show:*)", "Bash(git remote show:*)",
            "Read", "Glob", "Grep", "LS", "Task");

        // progressMessage 对齐 CC security-review.ts:202
        assertThat(sr.getProgressMessage()).isEqualTo("analyzing code changes for security risks");
    }

    @Test
    @DisplayName("local/local-jsx 命令元数据注册为正确 type + 门控默认关（对齐 CC index.ts）")
    void localCommandMetadataRegisteredWithCorrectTypeAndGate() {
        config.commandBundledRegistrationC();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys("release-notes", "privacy-settings", "think-back");

        assertThat(cmds.get("release-notes").getType()).isEqualTo("local");
        assertThat(cmds.get("release-notes").isCommandEnabled()).isTrue();

        assertThat(cmds.get("privacy-settings").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("privacy-settings").isCommandEnabled()).isFalse();

        assertThat(cmds.get("think-back").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("think-back").isCommandEnabled()).isFalse();
    }

    @Test
    @DisplayName("模型可调用过滤：含 prompt security-review、排除 local/local-jsx（对齐 CC commands.ts:568）")
    void modelInvocableIncludesPromptExcludesLocal() {
        config.commandBundledRegistrationC();

        SkillRegistry registry = new SkillRegistry("");
        registry.refresh();

        List<String> invocable = registry.getModelInvocableCommands().stream()
            .map(Command::getName).toList();
        assertThat(invocable).contains("security-review");
        assertThat(invocable).doesNotContain("release-notes", "privacy-settings", "think-back");

        // getAllCommands（web GET /api/command 数据源）含无 gate 的 local 命令
        List<String> all = registry.getAllCommands().stream().map(Command::getName).toList();
        assertThat(all).contains("security-review", "release-notes");
        // 门控关的 local-jsx → 从 getAllCommands 过滤（对齐 CC commands.ts:484 isCommandEnabled）
        assertThat(all).doesNotContain("privacy-settings", "think-back");
    }

    @Test
    @DisplayName("local 命令执行 handler 注册进 UserInputDispatcher：/name 触发后端执行")
    void localSlashHandlersRegisteredAndDispatchable() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandLocalSlashRegistrationC(dispatcher, null);

        // /release-notes → result handler（[Fix-P1] type=local 迁移 registerSlashCommandResult →
        //   dispatchResult 回传 text；拦截器 local 分支组装 <local-command-stdout> 用户可见）
        UserInputDispatcher.LocalCommandResult rn = dispatcher.dispatchResult("/release-notes");
        assertThat(rn).isNotNull();
        assertThat(rn.kind()).as("/release-notes text 结果回传").isEqualTo("text");
        // /privacy-settings、/think-back → void handler（local-jsx 未迁移，仍走 dispatch）
        assertThat(dispatcher.dispatch("/privacy-settings").routedTo()).isEqualTo("privacy-settings");
        assertThat(dispatcher.dispatch("/think-back").routedTo()).isEqualTo("think-back");
    }

    @Test
    @DisplayName("ReleaseNotesCommand 解析 markdown 表 + 回落链（对齐 CC release-notes.ts:19-50）")
    void releaseNotesParsesTableAndFallsBackToLink() throws Exception {
        // 1. 表格式解析（本项目 CHANGELOG.md | Date | Version | Change |）
        String table = "# CHANGELOG\n\n"
            + "| Date | Version | Change |\n"
            + "|------|---------|--------|\n"
            + "| 2026-08-30 | 1.2.0 | first feature |\n"
            + "| 2026-08-29 | 1.1.0 | second feature |\n";
        ReleaseNotesCommand cmd = new ReleaseNotesCommand();
        Map<String, List<String>> parsed = cmd.parseChangelog(table);
        assertThat(parsed).containsKeys("1.2.0", "1.1.0");
        assertThat(parsed.get("1.2.0")).containsExactly("first feature");

        // 2. getAllReleaseNotes → old → new（CC utils/releaseNotes.ts:249-276 升序）
        List<Map.Entry<String, List<String>>> all = cmd.getAllReleaseNotes(table);
        assertThat(all.get(0).getKey()).isEqualTo("1.1.0");
        assertThat(all.get(1).getKey()).isEqualTo("1.2.0");

        // 3. call() 读临时 CHANGELOG.md → "Version X:\n· note"（对齐 CC formatReleaseNotes）
        Path changelog = tempDir.resolve("CHANGELOG.md");
        Files.writeString(changelog, table, StandardCharsets.UTF_8);
        ReleaseNotesCommand.CommandResult result = cmd.call(changelog.toString());
        assertThat(result.value()).contains("Version 1.1.0:").contains("· second feature")
            .contains("Version 1.2.0:").contains("· first feature");

        // 4. 缺失文件 → changelog 链接（CC release-notes.ts:45-49）
        ReleaseNotesCommand.CommandResult fallback = cmd.call(tempDir.resolve("missing.md").toString());
        assertThat(fallback.value()).isEqualTo("See the full changelog at: " + ReleaseNotesCommand.CHANGELOG_URL);
    }

    /** 调 Command.promptFn → 文本内容（text 块 join）。 */
    private static String renderPromptFn(Command cmd, String args) {
        if (cmd.getPromptFn() == null) {
            return null;
        }
        return cmd.getPromptFn().apply(args, new PromptFnContext(null, List.of(), null)).stream()
            .filter(b -> b instanceof ContentBlockParam.TextBlockParam)
            .map(b -> ((ContentBlockParam.TextBlockParam) b).text())
            .collect(Collectors.joining("\n\n"));
    }
}
