package com.nexusai.application.agent.skill;

import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-7 7 个 bundled Registrar prompt 文案还原锁定（RED→GREEN）· 对齐 CC bundled/*.ts 完整文案。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>prompt 文案是 CC 行为的一部分</b>——P2-7 之前 Java 把 loop 的 interval 解析做成 Java 侧
 *       parseArgs/buildCronExpression 输出 3 行摘要（CC loop.ts buildPrompt 是静态指令、解析交给模型）、
 *       remember/simplify/skillify/stuck/update-config/schedule 大幅删节关键段。断言关键段存在即锁定
 *       "还原 CC 完整文案"意图；若某 Registrar 退回简化文案，对应断言必红。</li>
 *   <li><b>死代码回归守卫</b>——loop buildPrompt 输出不得含旧 "Interval: .../Cron:" 摘要行（Java 自造），
 *       证明 parseArgs/buildCronExpression 死代码删除后无残留行为。</li>
 * </ol>
 */
class RegistrarPromptCopyTest {

    // ── loop（CC loop.ts:25-72 静态指令 prompt）──

    @Test
    @DisplayName("loop buildPrompt 还原 CC 完整文案：## Parsing / ## Interval → cron 在，旧 Interval:/Cron: 摘要行不在")
    void loopBuildPromptContainsFullCcSections() {
        String prompt = LoopSkillRegistrar.buildPrompt("5m /babysit-prs");

        assertThat(prompt)
            .as("CC loop.ts:30 ## Parsing (in priority order) 段")
            .contains("## Parsing (in priority order)")
            .as("CC loop.ts:46 ## Interval → cron 段")
            .contains("## Interval → cron")
            .as("CC loop.ts:60 ## Action 段")
            .contains("## Action")
            .as("CC loop.ts:62 CronCreate 插值（ScheduleCronTool/prompt.ts:64）")
            .contains("Call CronCreate with")
            .as("CC loop.ts:66 7-day auto-expire + CronDelete 插值（prompt.ts:8/:65）")
            .contains("auto-expire after 7 days")
            .contains("cancel sooner with CronDelete")
            .as("CC loop.ts:71 ## Input + ${args}")
            .contains("## Input")
            .contains("5m /babysit-prs")
            .as("Java 自造 parseArgs 摘要行必须消失（P2-7 deleteList parseArgs/buildCronExpression）")
            .doesNotContain("Interval: 5m")
            .doesNotContain("Cron: */5");
    }

    @Test
    @DisplayName("loop USAGE_MESSAGE 逐字对齐 CC loop.ts:11-23（5 个示例，含 defaults 与 every 20m 两行）")
    void loopUsageMessageHasAllFiveExamples() {
        assertThat(LoopSkillRegistrar.USAGE_MESSAGE)
            .contains("  /loop 5m /babysit-prs")
            .contains("  /loop 30m check the deploy")
            .contains("  /loop 1h /standup 1")
            .contains("  /loop check the deploy          (defaults to 10m)")
            .contains("  /loop check the deploy every 20m");
    }

    // ── remember（CC remember.ts:9-62）──

    @Test
    @DisplayName("remember SKILL_PROMPT 还原 CC 完整文案：destination 表格 + Important distinctions + Success criteria + Rules 4 条")
    void rememberSkillPromptContainsFullCcSections() {
        assertThat(RememberSkillRegistrar.SKILL_PROMPT)
            .as("CC remember.ts:24-29 destination 表格（Java 旧版 bullet 替代）")
            .contains("| Destination |")
            .contains("| **Team memory** |")
            .as("CC remember.ts:31-34 Important distinctions")
            .contains("**Important distinctions:**")
            .as("CC remember.ts:19/:36/:44/:55 每步 Success criteria")
            .contains("**Success criteria**")
            .as("CC remember.ts:57-61 Rules 4 条")
            .contains("## Rules")
            .contains("- Do NOT create new files unless the target doesn't exist yet");
    }

    @Test
    @DisplayName("remember DESCRIPTION 无 Java 追加句 'and identifies cleanup opportunities.'（CC remember.ts:66-67）")
    void rememberDescriptionHasNoJavaAppendage() {
        assertThat(RememberSkillRegistrar.DESCRIPTION)
            .as("P2-7 deleteList：Java 自增尾部句必须删除")
            .doesNotContain("and identifies cleanup opportunities.");
    }

    // ── simplify（CC simplify.ts:4-53）──

    @Test
    @DisplayName("simplify SIMPLIFY_PROMPT 还原 CC 完整文案：Agent 2/3 各项详细说明 + Phase 3 do not argue")
    void simplifyPromptContainsFullCcDetails() {
        assertThat(SimplifySkillRegistrar.SIMPLIFY_PROMPT)
            .as("CC simplify.ts:29 Parameter sprawl 详细说明")
            .contains("**Parameter sprawl**: adding new parameters")
            .as("CC simplify.ts:44 Recurring no-op updates 完整（change-detection guard）")
            .contains("change-detection guard")
            .as("CC simplify.ts:45 Unnecessary existence checks（TOCTOU）")
            .contains("**Unnecessary existence checks**: pre-checking file/resource existence")
            .as("CC simplify.ts:50 do not argue with the finding")
            .contains("do not argue with the finding, just skip it")
            .as("CC simplify.ts:52 结尾 summary 句")
            .contains("briefly summarize what was fixed");
    }

    // ── skillify（CC skillify.ts:22-156）──

    @Test
    @DisplayName("skillify getPromptForCommand 还原 CC 完整文案：Per-step annotations + Frontmatter rules + Round 2 fork/inline")
    void skillifyPromptContainsFullCcSections() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        // [拍板#9 part2] 构造器签名升级：sessionId→"mem"（session memory）+ messages→空（无用户消息）
        new SkillifySkillRegistrar(() -> "ant", sessionId -> "mem", messages -> List.of(),
            def -> holder[0] = def).register();

        List<PromptBlock> blocks = holder[0].getPromptForCommand().apply(
            "refactor X", PromptFnContext.of("", List.of(), "sess"));
        String prompt = blocks.get(0).text();

        assertThat(prompt)
            .as("CC skillify.ts:130 Per-step annotations")
            .contains("**Per-step annotations**:")
            .as("CC skillify.ts:142 Frontmatter rules")
            .contains("**Frontmatter rules:**")
            .as("CC skillify.ts:66 Round2 fork vs inline")
            .contains("forked (as a sub-agent with its own context)")
            .as("CC skillify.ts:82 IMPORTANT 纠正关注")
            .contains("Pay special attention to places where the user corrected you")
            .as("CC skillify.ts:85 Round4 trigger 示例")
            .contains("cherry-pick to release")
            .as("userDescriptionBlock 插值保留")
            .contains("The user described this process as: \"refactor X\"");
    }

    // ── stuck（CC stuck.ts:6-59）──

    @Test
    @DisplayName("stuck STUCK_PROMPT 还原 CC 完整文案：two-message 结构 + thread_ts + pgrep -lP + sample <pid> 3")
    void stuckPromptContainsFullCcSections() {
        assertThat(StuckSkillRegistrar.STUCK_PROMPT)
            .as("CC stuck.ts:46 two-message structure")
            .contains("two-message structure")
            .as("CC stuck.ts:49 thread reply 传 thread_ts")
            .contains("`ts` as `thread_ts`")
            .as("CC stuck.ts:31 pgrep -lP")
            .contains("pgrep -lP")
            .as("CC stuck.ts:37 sample <pid> 3")
            .contains("`sample <pid> 3`")
            .as("CC stuck.ts:15 Sample twice 1-2s 确认")
            .contains("Sample twice, 1-2s apart")
            .as("CC stuck.ts:56-58 Notes 2 条")
            .contains("## Notes")
            .contains("If the user gave an argument (e.g., a specific PID or symptom), focus there first.");
    }

    // ── update-config（CC updateConfig.ts:15-104 / :110-267 / :269-305 / :307-443）──

    @Test
    @DisplayName("update-config SETTINGS_EXAMPLES_DOCS 还原：Permission Rule Syntax + Attribution + Other Settings + Plugins 语法")
    void updateConfigSettingsDocsContainsFullCcSections() {
        assertThat(UpdateConfigSkillRegistrar.SETTINGS_EXAMPLES_DOCS)
            .as("CC updateConfig.ts:42-45 Permission Rule Syntax 3 条")
            .contains("**Permission Rule Syntax:**")
            .contains("Exact match: `\"Bash(npm run test)\"`")
            .as("CC updateConfig.ts:66-75 Attribution (Commits & PRs)")
            .contains("### Attribution (Commits & PRs)")
            .as("CC updateConfig.ts:94 Plugins 语法行")
            .contains("Plugin syntax: `plugin-name@source`")
            .as("CC updateConfig.ts:96-103 Other Settings 7 项")
            .contains("### Other Settings")
            .contains("`cleanupPeriodDays`")
            .contains("`syntaxHighlightingDisabled`");
    }

    @Test
    @DisplayName("update-config HOOKS_DOCS 还原：Hook Input stdin JSON + Hook JSON Output + Common Patterns")
    void updateConfigHooksDocsContainsFullCcSections() {
        assertThat(UpdateConfigSkillRegistrar.HOOKS_DOCS)
            .as("CC updateConfig.ts:144 Stop 完整 purpose（clear/resume/compact）")
            .contains("Run when Claude stops (including clear, resume, compact)")
            .as("CC updateConfig.ts:150 Common tool matchers")
            .contains("**Common tool matchers:**")
            .as("CC updateConfig.ts:163/:169 prompt/agent hook 适用事件")
            .contains("Only available for tool events: PreToolUse, PostToolUse, PermissionRequest.")
            .as("CC updateConfig.ts:171-179 Hook Input (stdin JSON)")
            .contains("### Hook Input (stdin JSON)")
            .as("CC updateConfig.ts:181-211 Hook JSON Output")
            .contains("### Hook JSON Output")
            .as("CC updateConfig.ts:213-266 Common Patterns 4 例")
            .contains("### Common Patterns")
            .contains("**Run tests after code changes:**");
    }

    @Test
    @DisplayName("update-config HOOK_VERIFICATION_FLOW 还原 7 步完整：pipe-test 命令 + jq -e + sentinel 证明法")
    void updateConfigVerificationFlowContainsFullCcSteps() {
        assertThat(UpdateConfigSkillRegistrar.HOOK_VERIFICATION_FLOW)
            .as("CC updateConfig.ts:275-279 Step2 4 子要点")
            .contains("Stays RAW for now")
            .as("CC updateConfig.ts:281-286 Step3 pipe-test 具体命令")
            .contains("echo '{\"tool_name\":\"Edit\"")
            .as("CC updateConfig.ts:292-294 Step5 jq -e 校验命令")
            .contains("jq -e '.hooks.<event>[]")
            .as("CC updateConfig.ts:296-302 Step6 sentinel 文件 + watcher caveat")
            .contains("echo \"$(date) hook fired\" >> /tmp/claude-hook-check.txt")
            .contains("it only watches directories that had a settings file when this session started")
            .as("CC updateConfig.ts:304 Step7 silent success")
            .contains("silent success is invisible by design");
    }

    @Test
    @DisplayName("update-config UPDATE_CONFIG_PROMPT 还原：Merging Arrays WRONG/RIGHT + Example Workflows + Troubleshooting 6 条")
    void updateConfigPromptContainsFullCcSections() {
        assertThat(UpdateConfigSkillRegistrar.UPDATE_CONFIG_PROMPT)
            .as("CC updateConfig.ts:356-376 Merging Arrays WRONG/RIGHT 示例")
            .contains("**WRONG** (replaces existing permissions):")
            .contains("**RIGHT** (preserves existing + adds new):")
            .as("CC updateConfig.ts:384-425 Example Workflows（Adding a Hook/Permissions/Environment Variables）")
            .contains("## Example Workflows")
            .contains("### Adding a Hook")
            .contains("### Adding Permissions")
            .contains("### Environment Variables")
            .as("CC updateConfig.ts:427-432 Common Mistakes")
            .contains("## Common Mistakes to Avoid")
            .as("CC updateConfig.ts:434-442 Troubleshooting Hooks 完整 6 条")
            .contains("## Troubleshooting Hooks")
            .contains("**Use --debug** - Run `claude --debug` to see hook execution logs");
    }

    // ── schedule（CC scheduleRemoteAgents.ts:135-322）──

    @Test
    @DisplayName("schedule buildPrompt 还原 CC 完整文案：Create body shape + Cron Expression Examples + Important Notes + jsonStringify firstStep")
    void scheduleBuildPromptContainsFullCcSections() {
        String prompt = ScheduleRemoteAgentsSkillRegistrar.buildPrompt(
            "check the deploy", List.of(), "https://github.com/org/repo",
            "No connected MCP connectors found. The user may need to connect servers at https://claude.ai/settings/connectors",
            "Available environments:\n- prod (id: e1, kind: cloud)",
            new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource("prod", "e1", "cloud"),
            "Asia/Shanghai", false);

        assertThat(prompt)
            .as("CC scheduleRemoteAgents.ts:195 ## Create body shape + gitRepoUrl 插值")
            .contains("## Create body shape")
            .contains("\"git_repository\": {\"url\": \"https://github.com/org/repo\"}")
            .as("CC :226 生成 uuid 指令")
            .contains("Generate a fresh lowercase UUID")
            .as("CC :267-277 Cron Expression Examples + userTimezone 插值")
            .contains("### Cron Expression Examples")
            .contains("The user's local timezone is **Asia/Shanghai**")
            .contains("Minimum interval is 1 hour. `*/30 * * * *` will be rejected.")
            .as("CC :279-310 Workflow CREATE 7 步 / UPDATE / LIST / RUN NOW")
            .contains("## Workflow")
            .contains("### UPDATE a trigger:")
            .contains("### RUN NOW:")
            .as("CC :312-320 ## Important Notes")
            .contains("## Important Notes")
            .contains("The prompt is the most important part")
            .as("CC :321 User Request 尾部")
            .contains("## User Request")
            .contains("The user said: \"check the deploy\"");
    }

    @Test
    @DisplayName("schedule firstStep 对 initialQuestion 做 jsonStringify（CC :170）— 无 args 时 question 字段为 JSON 字符串字面量")
    void scheduleFirstStepJsonStringifiesInitialQuestion() {
        String prompt = ScheduleRemoteAgentsSkillRegistrar.buildPrompt(
            "", List.of(), null, "connectors-info", "environments-info", null, "UTC", false);

        assertThat(prompt)
            .as("CC scheduleRemoteAgents.ts:170 jsonStringify(initialQuestion)：question 值被 JSON 双引号包裹")
            .contains("\"What would you like to do with scheduled remote agents?\"")
            .as("firstStep 提及 AskUserQuestion 首动作")
            .contains("Your FIRST action must be a single AskUserQuestion tool call");
    }

    @Test
    @DisplayName("schedule needsGitHubAccessReminder=true 时出现 GitHub App 提醒句（CC :320）")
    void scheduleGitHubReminderBranchRenders() {
        String prompt = ScheduleRemoteAgentsSkillRegistrar.buildPrompt(
            "", List.of(), null, "connectors-info", "environments-info", null, "UTC", true);

        assertThat(prompt)
            .as("CC scheduleRemoteAgents.ts:320 tengu_cobalt_lantern=false 分支文案")
            .contains("they need the Claude GitHub App installed on the repo");

        String withoutReminder = ScheduleRemoteAgentsSkillRegistrar.buildPrompt(
            "", List.of(), null, "connectors-info", "environments-info", null, "UTC", false);
        assertThat(withoutReminder)
            .as("needsGitHubAccessReminder=false 时提醒句不渲染")
            .doesNotContain("they need the Claude GitHub App installed on the repo");
    }
}
