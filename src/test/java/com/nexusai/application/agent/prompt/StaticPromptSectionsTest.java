package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7 静态 section 生成器测试 · 对齐 CC prompts.ts:175-442（非 ant 变体）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC 的 7 个静态 section 是 getSystemPrompt 返回数组
 * boundary 之前的固定内容（I-11 集序 + V-比对）。测试钉死：
 * <ul>
 *   <li>intro 依 outputStyleConfig 三元换措辞 + CYBER_RISK 全文本注入（:175-186）</li>
 *   <li>doingTasks 含安全子弹 + ISSUES_EXPLAINER（DEL-SP-22 独立 Security 段的替代）</li>
 *   <li>usingYourTools 含 Glob/Grep 子弹（非 embedded）且 taskToolName 可 null（:269-314）</li>
 *   <li>prependBullets 单/双空格前缀（:167-173）</li>
 * </ul>
 */
class StaticPromptSectionsTest {

    // ── prependBullets（prompts.ts:167-173）──

    @Test
    @DisplayName("prependBullets：顶层字符串单空格 ' - '，嵌套数组双空格 '  - '（CC:167-173）")
    void prependBullets_singleVsDoubleSpace() {
        List<String> lines = StaticPromptSections.prependBullets(List.of(
            "top level",
            List.of("sub one", "sub two")
        ));

        assertThat(lines).containsExactly(
            " - top level",
            "  - sub one",
            "  - sub two");
    }

    // ── intro（prompts.ts:175-186）──

    @Test
    @DisplayName("simpleIntroSection：outputStyleConfig=null → 'with software engineering tasks.' 逐字 V-比对（:175-186）")
    void intro_nullOutputStyle_engineeringFraming() {
        String intro = StaticPromptSections.simpleIntroSection(null);

        // V-比对：与 CC 模板字节级相等（前导 \\n + 三元措辞 + CYBER_RISK + URL 行）
        String ccExpected = "\nYou are an interactive agent that helps users with software engineering tasks. Use the instructions below and the tools available to you to assist the user.\n\n"
            + StaticPromptSections.CYBER_RISK_INSTRUCTION
            + "\nIMPORTANT: You must NEVER generate or guess URLs for the user unless you are confident that the URLs are for helping the user with programming. You may use URLs provided by the user in their messages or local files.";
        assertThat(intro).as("CC 模板字节级相等").isEqualTo(ccExpected);
        assertThat(intro).as("CC 模板以前导 \\n 开头").startsWith("\nYou are an interactive agent");
        assertThat(intro).as("无 Output Style → 软件工程措辞").contains("with software engineering tasks.");
        assertThat(intro).as("CYBER_RISK 全文本注入（:180）").contains(StaticPromptSections.CYBER_RISK_INSTRUCTION);
    }

    @Test
    @DisplayName("simpleIntroSection：outputStyleConfig 非 null → 'according to your \"Output Style\" below'（:181 配置分支）")
    void intro_withOutputStyle_styleFraming() {
        OutputStyleConfig config = OutputStyleConfig.of("Explanatory", "Explain everything.");

        String intro = StaticPromptSections.simpleIntroSection(config);

        assertThat(intro).contains("according to your \"Output Style\" below, which describes how you should respond to user queries.");
        assertThat(intro).doesNotContain("with software engineering tasks.");
    }

    // ── system（prompts.ts:186-199）──

    @Test
    @DisplayName("simpleSystemSection：'# System' + 6 子弹含 hooks 段（:138-144 + :186-199）")
    void systemSection_headerAndBullets() {
        String system = StaticPromptSections.simpleSystemSection();

        assertThat(system).startsWith("# System\n");
        assertThat(system).contains("Github-flavored markdown");
        assertThat(system).contains("Users may configure 'hooks'");
        assertThat(system).contains("The system will automatically compress prior messages");
        assertThat(system).contains("<system-reminder>");
    }

    // ── doingTasks（prompts.ts:199-253，非 ant）──

    @Test
    @DisplayName("simpleDoingTasksSection：安全子弹 + ISSUES_EXPLAINER + 嵌套双空格子子弹（DEL-SP-22 安全段替代）")
    void doingTasks_safetyBulletAndIssuesExplainer() {
        String doingTasks = StaticPromptSections.simpleDoingTasksSection();

        assertThat(doingTasks).startsWith("# Doing tasks\n");
        assertThat(doingTasks).as("安全子弹（DEL-SP-22：无独立 # Security 段，安全内容是 doingTasks 一项）")
            .contains("Be careful not to introduce security vulnerabilities such as command injection, XSS, SQL injection");
        assertThat(doingTasks).as("ISSUES_EXPLAINER 构建期宏真值（捆绑产物 cli.js 解析）")
            .contains("To give feedback, users should report the issue at https://github.com/anthropics/claude-code/issues");
        assertThat(doingTasks).as("codeStyleSubitems 嵌套数组 → 双空格前缀（CC prependBullets）")
            .contains("  - Don't add features, refactor code, or make \"improvements\" beyond what was asked.");
        assertThat(doingTasks).as("非 ant：无 ant 专属 comment-writing 子弹")
            .doesNotContain("Default to writing no comments.");
    }

    // ── actions（prompts.ts:255-267）──

    @Test
    @DisplayName("actionsSection：'# Executing actions with care' 单大段含示例列表（:255-267）")
    void actionsSection_largeParagraph() {
        String actions = StaticPromptSections.actionsSection();

        assertThat(actions).startsWith("# Executing actions with care\n");
        assertThat(actions).contains("Carefully consider the reversibility and blast radius of actions.");
        assertThat(actions).contains("- Destructive operations: deleting files/branches");
        assertThat(actions).contains("measure twice, cut once.");
    }

    // ── usingYourTools（prompts.ts:269-314，非 REPL / 非 embedded）──

    @Test
    @DisplayName("usingYourToolsSection：TaskCreate+TodoWrite 都启用 → 取 TaskCreate 作 taskToolName（:270-272 find 序）")
    void usingYourTools_taskCreatePreferred() {
        String section = StaticPromptSections.usingYourToolsSection(
            Set.of(ToolNameConstants.TASK_CREATE_TOOL_NAME, ToolNameConstants.TODO_WRITE_TOOL_NAME));

        assertThat(section).contains("Break down and manage your work with the " + ToolNameConstants.TASK_CREATE_TOOL_NAME + " tool.");
        assertThat(section).as("非 embedded → Glob/Grep 子弹保留（:291-296）")
            .contains("To search for files use " + ToolNameConstants.GLOB_TOOL_NAME + " instead of find or ls")
            .contains("To search the content of files, use " + ToolNameConstants.GREP_TOOL_NAME + " instead of grep or rg");
        assertThat(section).as("嵌套 providedToolSubitems 双空格").contains("  - To read files use " + ToolNameConstants.FILE_READ_TOOL_NAME + " instead of cat, head, tail, or sed");
    }

    @Test
    @DisplayName("usingYourToolsSection：仅 TodoWrite → taskToolName=TodoWrite（find 语义，非空集首个命中）")
    void usingYourTools_todoWriteFallback() {
        String section = StaticPromptSections.usingYourToolsSection(Set.of(ToolNameConstants.TODO_WRITE_TOOL_NAME));

        assertThat(section).contains("Break down and manage your work with the " + ToolNameConstants.TODO_WRITE_TOOL_NAME + " tool.");
    }

    @Test
    @DisplayName("usingYourToolsSection：无 TaskCreate/TodoWrite → taskToolName 为 null，任务子弹省略（:310-312 null 被 filter）")
    void usingYourTools_noTaskTool_bulletOmitted() {
        String section = StaticPromptSections.usingYourToolsSection(Set.of("Read"));

        assertThat(section).doesNotContain("Break down and manage your work with");
        assertThat(section).contains("You can call multiple tools in a single response.");
    }

    // ── toneAndStyle（prompts.ts:430-442，非 ant）──

    @Test
    @DisplayName("simpleToneAndStyleSection：外部变体含 'short and concise' 子弹（:437-439 非 ant 分支）")
    void toneAndStyle_containsShortAndConcise() {
        String tone = StaticPromptSections.simpleToneAndStyleSection();

        assertThat(tone).startsWith("# Tone and style\n");
        assertThat(tone).contains("Your responses should be short and concise.");
        assertThat(tone).contains("file_path:line_number");
        assertThat(tone).contains("owner/repo#123");
        assertThat(tone).contains("Do not use a colon before tool calls.");
    }

    // ── outputEfficiency（prompts.ts:403-430，非 ant）──

    @Test
    @DisplayName("outputEfficiencySection：非 ant 变体 '# Output efficiency'（:426-430）")
    void outputEfficiency_nonAntText() {
        String eff = StaticPromptSections.outputEfficiencySection();

        assertThat(eff).startsWith("# Output efficiency\n");
        assertThat(eff).contains("IMPORTANT: Go straight to the point.");
        assertThat(eff).doesNotContain("# Communicating with the user");
    }

    // ── AST 工具子弹：两名称各自独立门控（只提实际可见的那个）──

    @Test
    @DisplayName("usingYourToolsSection：AstGrep+AstEdit 都可见 → 两者都出现，且写清分工与 apply 两阶段")
    void usingYourTools_astBothVisible() {
        String section = StaticPromptSections.usingYourToolsSection(
            Set.of(ToolNameConstants.AST_GREP_TOOL_NAME, ToolNameConstants.AST_EDIT_TOOL_NAME));

        assertThat(section).contains(ToolNameConstants.AST_GREP_TOOL_NAME);
        assertThat(section).contains(ToolNameConstants.AST_EDIT_TOOL_NAME);
        assertThat(section).as("代码搜索优先 AstGrep / 代码修改优先 AstEdit（含单点）")
            .contains("Prefer " + ToolNameConstants.AST_GREP_TOOL_NAME)
            .contains("Prefer " + ToolNameConstants.AST_EDIT_TOOL_NAME)
            .contains("code searches")
            .contains("code changes")
            .contains("including a single location");
        assertThat(section).as("非程序内容回退 Grep/Edit、不可表达可回退")
            .contains(ToolNameConstants.GREP_TOOL_NAME + " for non-programming content")
            .contains(ToolNameConstants.FILE_EDIT_TOOL_NAME + " for non-programming content")
            .contains("cannot express");
        assertThat(section).as("旧取向（仅多处/多文件才用）已清除").doesNotContain("many matched sites");
        assertThat(section).as("AstEdit 两阶段：先预览再 apply").contains("apply: true");
        assertThat(section).as("CC 原有 Edit/Grep 子弹不因 AST 子弹被削")
            .contains("To edit files use " + ToolNameConstants.FILE_EDIT_TOOL_NAME)
            .contains("To search the content of files, use " + ToolNameConstants.GREP_TOOL_NAME);
    }

    @Test
    @DisplayName("usingYourToolsSection：仅 AstGrep 可见 → 只提 AstGrep，不提 AstEdit")
    void usingYourTools_onlyAstGrep() {
        String section = StaticPromptSections.usingYourToolsSection(
            Set.of(ToolNameConstants.FILE_EDIT_TOOL_NAME, ToolNameConstants.AST_GREP_TOOL_NAME));

        assertThat(section).contains(ToolNameConstants.AST_GREP_TOOL_NAME);
        assertThat(section).as("正向：代码搜索优先 AstGrep").contains("Prefer " + ToolNameConstants.AST_GREP_TOOL_NAME);
        assertThat(section).doesNotContain(ToolNameConstants.AST_EDIT_TOOL_NAME);
    }

    @Test
    @DisplayName("usingYourToolsSection：仅 AstEdit 可见 → 只提 AstEdit，不提 AstGrep")
    void usingYourTools_onlyAstEdit() {
        String section = StaticPromptSections.usingYourToolsSection(
            Set.of(ToolNameConstants.FILE_EDIT_TOOL_NAME, ToolNameConstants.AST_EDIT_TOOL_NAME));

        assertThat(section).contains(ToolNameConstants.AST_EDIT_TOOL_NAME);
        assertThat(section).as("正向：代码修改优先 AstEdit").contains("Prefer " + ToolNameConstants.AST_EDIT_TOOL_NAME);
        assertThat(section).doesNotContain(ToolNameConstants.AST_GREP_TOOL_NAME);
    }

    @Test
    @DisplayName("usingYourToolsSection：两者都不可见 → 两个名称都不出现")
    void usingYourTools_noAstTools() {
        String section = StaticPromptSections.usingYourToolsSection(
            Set.of(ToolNameConstants.FILE_EDIT_TOOL_NAME, ToolNameConstants.GREP_TOOL_NAME));

        assertThat(section).doesNotContain(ToolNameConstants.AST_GREP_TOOL_NAME);
        assertThat(section).doesNotContain(ToolNameConstants.AST_EDIT_TOOL_NAME);
    }
}
