package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ultracode + cron-list/cron-delete bundled skill 注册对齐测试 · 对齐 CC
 * skills/bundled/ultracode.ts + cronManage.ts（bundled/index.ts:39-41）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>ultracode 无条件注册</b>——CC registerUltracodeSkill 无 isEnabled gate、无 feature 门控
 *       （ultracode.ts:219-235），纯知识注入 skill；Java isEnabled=null → 默认启用。若误加 isEnabled
 *       桩或 feature 门控，此断言必红。</li>
 *   <li><b>args append 语义</b>——CC {@code if (args) prompt += `\n## User input\n\n${args}\n`}
 *       （ultracode.ts:228-232）：truthy 判定不 trim（空白串仍追加）。防「误 trim 或空串跳过」。</li>
 *   <li><b>cron-list/delete 注册但 enabled 惰性门控</b>——CC cronManage.ts:15/:35 isEnabled:
 *       isKairosCronEnabled（与 loop 同源）。skill 始终注册，agentTriggers=false 时 isCommandEnabled()
 *       =false（对齐 CC「registered unconditionally; the skill's own isEnabled callback decides
 *       visibility」）。防「把 isEnabled 当注册门控误删注册」。</li>
 *   <li><b>cron-delete args 分支</b>——空 args → usage；非空 → CronDeleteTool 指令（cronManage.ts:36-52）。</li>
 * </ol>
 */
class BundledSkillsUltracodeCronTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    private static Command command(String name) {
        return BundledSkills.getAll().stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("skill '" + name + "' 未注册"));
    }

    private static String promptText(Command command, String args) {
        List<ContentBlockParam> blocks = command.getPromptFn().apply(
            args, PromptFnContext.of("", List.of(), null));
        return blocks.stream()
            .map(b -> ((ContentBlockParam.TextBlockParam) b).text())
            .collect(Collectors.joining());
    }

    @Test
    @DisplayName("DEFAULTS 全 flag true → ultracode/cron-list/cron-delete 均注册（对齐 CC bundled/index.ts:39-41）")
    void defaultsRegisterThreeSkills() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName)
            .collect(Collectors.toList());
        assertThat(names).contains("ultracode", "cron-list", "cron-delete");
        // 注册序：ultracode → cron-list → cron-delete 在 loop 之前（bundled/index.ts:39-41 在 :54 loop 之前）
        assertThat(names.indexOf("ultracode")).isLessThan(names.indexOf("loop"));
        assertThat(names.indexOf("cron-list")).isLessThan(names.indexOf("loop"));
        assertThat(names.indexOf("cron-delete")).isLessThan(names.indexOf("loop"));
    }

    @Test
    @DisplayName("ultracode：无条件注册 + userInvocable=true + 无 isEnabled gate（默认启用）")
    void ultracodeUnconditionalAndEnabled() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        Command ultracode = command("ultracode");
        assertThat(ultracode.getUserInvocable()).isTrue();
        assertThat(ultracode.getIsEnabled())
            .as("CC ultracode.ts:219-235 无 isEnabled → undefined → 默认启用")
            .isNull();
        assertThat(ultracode.isCommandEnabled()).isTrue();
        assertThat(ultracode.getArgumentHint()).isNull();
    }

    @Test
    @DisplayName("ultracode：无 args → playbook 全文；有 args → append '\\n## User input\\n\\n{args}\\n'（CC ultracode.ts:228-232）")
    void ultracodePromptAppendsUserInput() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        Command ultracode = command("ultracode");
        String noArgs = promptText(ultracode, "");
        assertThat(noArgs).startsWith("# /ultracode — Workflow Orchestration Playbook");
        assertThat(noArgs).doesNotContain("## User input");

        String withArgs = promptText(ultracode, "audit the codebase");
        assertThat(withArgs).isEqualTo(noArgs + "\n## User input\n\naudit the codebase\n");

        // CC if (args) truthy 判定不 trim：空白串仍追加
        String whitespace = promptText(ultracode, "  ");
        assertThat(whitespace).isEqualTo(noArgs + "\n## User input\n\n  \n");
    }

    @Test
    @DisplayName("ultracode：playbook 转义精确（${...} 字面 + \\\" 反斜杠引号 + ```js 围栏 + Resume 结尾）")
    void ultracodePromptPreservesTsEscapes() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        String prompt = promptText(command("ultracode"), "");
        // CC 模板字符串求值后：\` → `、\${ → ${、\\" → \"——Java text block 必须渲染同一运行时串
        // （不信 CC 注释，逐字对照 ultracode.ts 运行时行为）
        assertThat(prompt).contains("`script`");
        assertThat(prompt).contains("`{scriptPath: \"<path>\"}`");
        // args JSON-encoded 示例（ultracode.ts:73）：运行时含 \" 反斜杠引号（非纯 " 引号）
        assertThat(prompt).contains("`args: \"[\\\"a.ts\\\", ...]\"`");
        // ${} 插值字面（ultracode.ts:129 \${d.key} → ${d.key}）
        assertThat(prompt).contains("{label: `review:${d.key}`, phase: 'Review', schema: FINDINGS_SCHEMA}");
        // ```js 代码围栏
        assertThat(prompt).contains("```js");
        // 结尾 Resume 段（ultracode.ts:216）
        assertThat(prompt).endsWith("hand-author a continuation script.\n");
    }

    @Test
    @DisplayName("cron-list：注册 + userInvocable=true + agentTriggers=true 时 enabled（isKairosCronEnabled 门控）")
    void cronListRegisteredAndGated() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        Command cronList = command("cron-list");
        assertThat(cronList.getUserInvocable()).isTrue();
        assertThat(cronList.getIsEnabled()).isNotNull();
        assertThat(cronList.isCommandEnabled()).isTrue();
        assertThat(promptText(cronList, ""))
            .isEqualTo("Call CronList to list all scheduled cron jobs. Display the results in a table "
                + "with columns: ID, Schedule, Prompt, Recurring, Durable. If no jobs exist, say \"No scheduled tasks.\"");
    }

    @Test
    @DisplayName("cron-list：agentTriggers=false → 仍注册但 isCommandEnabled()=false（与 loop 同源门控；loop 本身不注册）")
    void cronListDisabledWhenAgentTriggersOff() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, new BundledSkillFeatureFlags(false, true, true, true));
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName)
            .collect(Collectors.toList());
        // cron-list/delete 恒注册（isEnabled 仅控制 enabled，不控制注册）——对齐 CC cronManage.ts
        // 「registered unconditionally; isEnabled decides visibility」
        assertThat(names).contains("cron-list", "cron-delete");
        assertThat(command("cron-list").isCommandEnabled()).isFalse();
        // 对照：loop 受注册级 feature('AGENT_TRIGGERS') 门控（bundled/index.ts:47）——agentTriggers=false 时不注册
        assertThat(names).doesNotContain("loop");
    }

    @Test
    @DisplayName("cron-delete：空 args → usage；有 args → CronDeleteTool 指令（CC cronManage.ts:36-52）")
    void cronDeletePromptUsageAndCall() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, BundledSkillFeatureFlags.DEFAULTS);
        bootstrapper.run(null);

        Command cronDelete = command("cron-delete");
        assertThat(cronDelete.getUserInvocable()).isTrue();
        assertThat(cronDelete.getArgumentHint()).isEqualTo("<job-id>");
        assertThat(cronDelete.isCommandEnabled()).isTrue();

        assertThat(promptText(cronDelete, ""))
            .isEqualTo("Usage: /cron-delete <job-id>\n\n"
                + "Provide the job ID to cancel. Use /cron-list to see active jobs and their IDs.");
        // CC id = args.trim()（cronManage.ts:37）——前后空白剥离
        assertThat(promptText(cronDelete, "  42  "))
            .isEqualTo("Call CronDelete with id \"42\" to cancel that scheduled job. Confirm the result to the user.");
    }

    @Test
    @DisplayName("cron-delete：agentTriggers=false → 仍注册但 isCommandEnabled()=false")
    void cronDeleteDisabledWhenAgentTriggersOff() {
        BundledSkillsBootstrapper bootstrapper = new BundledSkillsBootstrapper(
            () -> false, () -> true, new BundledSkillFeatureFlags(false, true, true, true));
        bootstrapper.run(null);

        assertThat(command("cron-delete").isCommandEnabled()).isFalse();
    }
}
