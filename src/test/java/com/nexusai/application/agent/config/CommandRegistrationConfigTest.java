package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandRegistrationConfig 接线测试 · 未注册命令类暴露给 web 的注册面验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>prompt 命令进 BundledSkills</b>——commit / commit-push-pr / statusline / review 注册为
 *       type='prompt'，经 SkillRegistry.getModelInvocableCommands 模型可调用 + getAllCommands web 可见；
 *       promptFn 生成 CC 契约 prompt（"## Context" / "expert code reviewer" / "statusline-setup"）。</li>
 *   <li><b>local 命令元数据进 BundledSkills + handler 进 UserInputDispatcher</b>——advisor / cost / files /
 *       heapdump / keybindings / rename / brief / ultrareview 注册为 type='local'/'local-jsx' 元数据
 *       （web GET /api/command 可见），同时 UserInputDispatcher.dispatch("/name") 触发执行 handler。</li>
 *   <li><b>模型可调用过滤</b>——getModelInvocableCommands 含 prompt 命令、排除 local 命令
 *       （对齐 CC commands.ts:568 type==='prompt' 过滤）。</li>
 *   <li><b>门控</b>——brief isCommandEnabled=false（KAIROS gate 默认关，CC brief.ts:29-31）。</li>
 * </ol>
 */
class CommandRegistrationConfigTest {

    private final CommandRegistrationConfig config = new CommandRegistrationConfig();

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
    @DisplayName("prompt 命令注册为 BundledSkills type='prompt' + promptFn 生成 CC 契约内容")
    void promptCommandsRegisteredWithPromptFn() {
        config.commandBundledRegistration();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys("commit", "commit-push-pr", "statusline", "review");
        assertThat(cmds.get("commit").getType()).isEqualTo("prompt");
        assertThat(cmds.get("commit-push-pr").getType()).isEqualTo("prompt");
        assertThat(cmds.get("statusline").getType()).isEqualTo("prompt");
        assertThat(cmds.get("review").getType()).isEqualTo("prompt");

        // commit promptFn → "## Context" + Git Safety Protocol（CC commit.ts:20-54）
        String commitPrompt = renderPromptFn(cmds.get("commit"), "");
        assertThat(commitPrompt).contains("## Context").contains("## Git Safety Protocol");

        // review promptFn → LOCAL_REVIEW_PROMPT（CC review.ts:9-31）
        String reviewPrompt = renderPromptFn(cmds.get("review"), "123");
        assertThat(reviewPrompt).contains("expert code reviewer").contains("PR number: 123");

        // statusline promptFn → Agent + subagent_type "statusline-setup"（CC statusline.tsx:15-20）
        String statuslinePrompt = renderPromptFn(cmds.get("statusline"), "show git branch");
        assertThat(statuslinePrompt).contains("subagent_type \"statusline-setup\"")
            .contains("show git branch");

        // commit-push-pr promptFn → "## Additional instructions from user"（CC commit-push-pr.ts:127-131）
        String cppPrompt = renderPromptFn(cmds.get("commit-push-pr"), "add OAuth");
        assertThat(cppPrompt).contains("## Additional instructions from user").contains("add OAuth");
    }

    @Test
    @DisplayName("local 命令元数据注册为 type='local'/'local-jsx' + brief 门控默认关")
    void localCommandMetadataRegisteredWithCorrectTypeAndGate() {
        config.commandBundledRegistration();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys("advisor", "cost", "files", "heapdump",
            "keybindings", "rename", "brief", "ultrareview");
        assertThat(cmds.get("advisor").getType()).isEqualTo("local");
        assertThat(cmds.get("cost").getType()).isEqualTo("local");
        assertThat(cmds.get("files").getType()).isEqualTo("local");
        assertThat(cmds.get("heapdump").getType()).isEqualTo("local");
        assertThat(cmds.get("keybindings").getType()).isEqualTo("local");
        assertThat(cmds.get("rename").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("brief").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("ultrareview").getType()).isEqualTo("local-jsx");

        // heapdump isHidden=true（CC heapdump/index.ts:6）
        assertThat(cmds.get("heapdump").getIsHidden()).isTrue();

        // brief 门控默认 false（KAIROS feature + enable_slash_command 默认 false，CC brief.ts:29-31/51-56）
        assertThat(cmds.get("brief").isCommandEnabled()).isFalse();

        // 无 gate 命令恒启用（CC types/command.ts:214-215 isEnabled?.() ?? true）
        assertThat(cmds.get("advisor").isCommandEnabled()).isTrue();
        assertThat(cmds.get("cost").isCommandEnabled()).isTrue();
        assertThat(cmds.get("rename").isCommandEnabled()).isTrue();
    }

    @Test
    @DisplayName("模型可调用过滤：含 prompt 命令、排除 local 命令（对齐 CC commands.ts:568）")
    void modelInvocableIncludesPromptExcludesLocal() {
        config.commandBundledRegistration();

        SkillRegistry registry = new SkillRegistry("");
        registry.refresh(); // 清缓存，确保 fresh 视图

        List<String> invocable = registry.getModelInvocableCommands().stream()
            .map(Command::getName).toList();
        assertThat(invocable).contains("commit", "commit-push-pr", "statusline", "review");
        assertThat(invocable).doesNotContain("advisor", "cost", "rename", "brief", "ultrareview");

        // getAllCommands（web GET /api/command 数据源）含 local 命令（无 gate 的）
        List<String> all = registry.getAllCommands().stream().map(Command::getName).toList();
        assertThat(all).contains("commit", "advisor", "cost", "rename");
        // brief 门控关 → 从 getAllCommands 过滤（对齐 CC commands.ts:484 isCommandEnabled）
        assertThat(all).doesNotContain("brief");
    }

    @Test
    @DisplayName("local 命令执行 handler 注册进 UserInputDispatcher：/name 触发后端执行")
    void localSlashHandlersRegisteredAndDispatchable() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandLocalSlashRegistration(dispatcher, null, null);

        // /cost → result handler（CC cost.ts:6-24 call）· [Fix-P1] type=local 迁移 registerSlashCommandResult
        //   （拦截器 local 分支经 dispatchResult 回传 text → <local-command-stdout> 可见）
        UserInputDispatcher.LocalCommandResult cost = dispatcher.dispatchResult("/cost");
        assertThat(cost).isNotNull();
        assertThat(cost.kind()).as("/cost text 结果回传（对齐 CC local text 分支）").isEqualTo("text");

        // /rename <name> → void handler（CC rename.ts:21-87 call，local-jsx 未迁移，仍走 dispatch）
        UserInputDispatcher.RoutingResult rename = dispatcher.dispatch("/rename fix login bug");
        assertThat(rename.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(rename.routedTo()).isEqualTo("rename");

        // /advisor → result handler（CC advisor.ts:16-94 call）
        UserInputDispatcher.LocalCommandResult advisor = dispatcher.dispatchResult("/advisor opus");
        assertThat(advisor).isNotNull();
        assertThat(advisor.kind()).as("/advisor text 结果回传").isEqualTo("text");

        // 未注册的 /nope → dispatchResult 无 handler → null（拦截器 fail loud）；dispatch() 回落通用 handler
        assertThat(dispatcher.dispatchResult("/nope")).isNull();
        UserInputDispatcher.RoutingResult nope = dispatcher.dispatch("/nope");
        assertThat(nope.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(nope.routedTo()).isEqualTo("command-router");
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
