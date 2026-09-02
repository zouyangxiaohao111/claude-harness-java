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
 * CommandRegistrationConfigGroupAGitDir 接线测试 · 组A git/目录 5 命令注册面验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>prompt 命令进 BundledSkills</b>——pr-comments 注册为 type='prompt'，经 SkillRegistry
 *       getModelInvocableCommands 模型可调用 + getAllCommands web 可见；promptFn 生成 CC 契约 prompt
 *       （gh 拉取 PR 评论指令，对齐 pr_comments/index.ts:31-66）。</li>
 *   <li><b>local 命令元数据进 BundledSkills + handler 进 UserInputDispatcher</b>——branch / diff /
 *       rewind / add-dir 注册为 type='local'/'local-jsx' 元数据（web GET /api/command 可见），同时
 *       UserInputDispatcher.dispatch("/name") 触发执行 handler。</li>
 *   <li><b>type 正确</b>——branch=local-jsx（CC branch/index.ts:4）/ diff=local-jsx（diff/index.ts:3）/
 *       rewind=local（rewind/index.ts:10）/ add-dir=local-jsx（add-dir/index.ts:5）。</li>
 *   <li><b>别名</b>——branch alias 'fork'（CC branch/index.ts:8-9 FORK_SUBAGENT off）/ rewind alias
 *       'checkpoint'（rewind/index.ts:8）。</li>
 *   <li><b>门控</b>——5 命令 CC 均无 isEnabled gate → 恒启用（CC types/command.ts:214-215
 *       isEnabled?.() ?? true）。</li>
 * </ol>
 */
class CommandRegistrationConfigGroupAGitDirTest {

    private final CommandRegistrationConfigGroupAGitDir config = new CommandRegistrationConfigGroupAGitDir();

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
    @DisplayName("pr-comments 注册为 BundledSkills type='prompt' + promptFn 生成 CC gh 契约内容")
    void prCommentsRegisteredAsPrompt() {
        config.commandBundledRegistrationGroupA();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKey("pr-comments");
        Command cmd = cmds.get("pr-comments");
        assertThat(cmd.getType()).isEqualTo("prompt");
        // CC createMovedToPluginCommand:24 progressMessage='fetching PR comments'
        assertThat(cmd.getProgressMessage()).isEqualTo("fetching PR comments");
        // 无 isEnabled gate → 恒启用（CC types/command.ts:214-215）
        assertThat(cmd.isCommandEnabled()).isTrue();

        // promptFn → CC getPromptWhileMarketplaceIsPrivate（pr_comments/index.ts:31-66）
        String prompt = renderPromptFn(cmd, "pr 42");
        assertThat(prompt)
            .contains("gh pr view --json number,headRepository")
            .contains("gh api /repos/{owner}/{repo}/pulls/{number}/comments")
            .contains("Format the comments as:")
            .contains("No comments found.")
            .contains("Additional user input: pr 42");
    }

    @Test
    @DisplayName("local 命令元数据注册为正确 type + 别名 + 恒启用")
    void localCommandMetadataRegisteredWithCorrectTypeAndGate() {
        config.commandBundledRegistrationGroupA();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys("branch", "diff", "rewind", "add-dir");

        // branch：local-jsx + alias 'fork'（CC branch/index.ts:4/8-9 FORK_SUBAGENT off）
        assertThat(cmds.get("branch").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("branch").getAliases()).containsExactly("fork");
        assertThat(cmds.get("branch").getArgumentHint()).isEqualTo("[name]");

        // diff：local-jsx（CC diff/index.ts:3）
        assertThat(cmds.get("diff").getType()).isEqualTo("local-jsx");

        // rewind：local + alias 'checkpoint'（CC rewind/index.ts:8/10）
        assertThat(cmds.get("rewind").getType()).isEqualTo("local");
        assertThat(cmds.get("rewind").getAliases()).containsExactly("checkpoint");

        // add-dir：local-jsx + argumentHint '<path>'（CC add-dir/index.ts:4-5）
        assertThat(cmds.get("add-dir").getType()).isEqualTo("local-jsx");
        assertThat(cmds.get("add-dir").getArgumentHint()).isEqualTo("<path>");

        // 5 命令 CC 均无 isEnabled gate → 恒启用（CC types/command.ts:214-215）
        assertThat(cmds.get("branch").isCommandEnabled()).isTrue();
        assertThat(cmds.get("diff").isCommandEnabled()).isTrue();
        assertThat(cmds.get("rewind").isCommandEnabled()).isTrue();
        assertThat(cmds.get("add-dir").isCommandEnabled()).isTrue();
    }

    @Test
    @DisplayName("模型可调用过滤：含 prompt 命令 pr-comments、排除 local 命令（对齐 CC commands.ts:568）")
    void modelInvocableIncludesPromptExcludesLocal() {
        config.commandBundledRegistrationGroupA();

        SkillRegistry registry = new SkillRegistry("");
        registry.refresh(); // 清缓存，确保 fresh 视图

        List<String> invocable = registry.getModelInvocableCommands().stream()
            .map(Command::getName).toList();
        assertThat(invocable).contains("pr-comments");
        assertThat(invocable).doesNotContain("branch", "diff", "rewind", "add-dir");

        // getAllCommands（web GET /api/command 数据源）含 local 命令（无 gate 的）
        List<String> all = registry.getAllCommands().stream().map(Command::getName).toList();
        assertThat(all).contains("pr-comments", "branch", "diff", "rewind", "add-dir");
    }

    @Test
    @DisplayName("local 命令执行 handler 注册进 UserInputDispatcher：/name 触发后端执行")
    void localSlashHandlersRegisteredAndDispatchable() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandLocalSlashRegistrationGroupA(dispatcher, null, null);

        // /branch <title> → 命名 handler（CC branch.ts:222-296 call）
        UserInputDispatcher.RoutingResult branch = dispatcher.dispatch("/branch fix login bug");
        assertThat(branch.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(branch.routedTo()).isEqualTo("branch");

        // /diff → 命名 handler（CC diff.tsx:3-8 call）
        UserInputDispatcher.RoutingResult diff = dispatcher.dispatch("/diff");
        assertThat(diff.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(diff.routedTo()).isEqualTo("diff");

        // /rewind → result handler（[Fix-P1] type=local 迁移 registerSlashCommandResult；
        //   CC rewind.ts:11-12 恒 return {type:'skip'} → dispatchResult 回传 skip，无 stdout）
        UserInputDispatcher.LocalCommandResult rewind = dispatcher.dispatchResult("/rewind");
        assertThat(rewind).isNotNull();
        assertThat(rewind.kind()).as("/rewind 对齐 CC {type:'skip'}（openMessageSelector UI 属前端，受控差异）")
            .isEqualTo("skip");

        // /add-dir /tmp/newcode → 命名 handler（CC add-dir.tsx:65-125 call）
        UserInputDispatcher.RoutingResult addDir = dispatcher.dispatch("/add-dir /tmp/newcode");
        assertThat(addDir.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(addDir.routedTo()).isEqualTo("add-dir");

        // 未注册的 /nope → 回落通用 SLASH_COMMAND handler（向后兼容）
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
