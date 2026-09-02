package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S10] SafeToolWhitelist 测试 · 对齐 CC classifierDecision.ts:56-94
 * SAFE_YOLO_ALLOWLISTED_TOOLS（26 项 = 22 固定 + 4 条件）。
 *
 * <p>Java 最终 21 项 = 21 固定（CC 'classify_result' N/A：Java 分类器非 Tool）+ 0 条件
 * （Workflow/TerminalCapture/OverflowTest/VerifyPlanExecution 全 N/A：Java 无对应工具），
 * 逐项 N/A 依据见 {@link SafeToolWhitelist} 类 javadoc。
 */
@DisplayName("[S10] SafeToolWhitelist allowlist（CC 26 项对齐）")
class SafeToolWhitelistTest {

    private final SafeToolWhitelist whitelist = new SafeToolWhitelist();

    @Test
    @DisplayName("CC 22 固定项中 Java 存在的 21 项全部 isSafe 命中")
    void containsAllCcFixedItemsPresentInJava() {
        Set<String> expected = Set.of(
            // Read-only file operations（B2 后主名对齐 CC 大小写）
            "Read",                  // CC 'Read'
            "Grep",                  // CC 'Grep'
            "Glob",                  // CC 'Glob'
            "LSP",                   // CC 'LSP'
            "ToolSearch",            // CC 'ToolSearch'
            "ListMcpResourcesTool",  // CC 'ListMcpResources'
            "ReadMcpResourceTool",   // CC 'ReadMcpResourceTool'
            // Task management (metadata only)
            "TodoWrite",
            "TaskCreate", "TaskGet", "TaskUpdate",
            "TaskList", "TaskStop", "TaskOutput",
            // Plan mode / UI
            "AskUserQuestion", "EnterPlanMode", "ExitPlanMode",
            // Swarm coordination
            "TeamCreate", "TeamDelete", "SendMessage",
            // Misc safe
            "Sleep"                  // CC 'Sleep'
        );

        // [S06 ⊕-12] SafeToolWhitelist.getAll() 已删除（CC 无等价导出，classifierDecision.ts:96-98
        //   仅 isAutoModeAllowlistedTool 谓词）—— 以 isSafe 谓词断言 21 项全部命中。
        assertThat(expected)
            .as("Java 21 项 = CC 22 固定 − classify_result(N/A)，全部 isSafe 命中")
            .allSatisfy(name -> assertThat(whitelist.isSafe(name)).isTrue());
        // 负向控制：白名单外工具（如 Bash）不命中，防止 isSafe 恒 true 假绿
        assertThat(whitelist.isSafe("Bash")).isFalse();
    }

    @Test
    @DisplayName("Skill 免检已移除（CC 无 skill 免检概念，T07 R5）")
    void skillRemoved() {
        assertThat(whitelist.isSafe("Skill"))
            .as("旧 Java 独有 Skill 免检必须移除（CC classifierDecision.ts 无此概念）")
            .isFalse();
    }

    @Test
    @DisplayName("写/编辑/执行类工具不在 allowlist（CC :53-55 注释：Write/Edit 走 acceptEdits fast-path）")
    void writeAndExecToolsNotListed() {
        assertThat(whitelist.isSafe("Bash")).isFalse();
        assertThat(whitelist.isSafe("PowerShell")).isFalse();
        assertThat(whitelist.isSafe("write_file")).isFalse();
        assertThat(whitelist.isSafe("edit_file")).isFalse();
        assertThat(whitelist.isSafe("NotebookEdit")).isFalse();
        assertThat(whitelist.isSafe("WebFetch")).isFalse();
        assertThat(whitelist.isSafe("WebSearch")).isFalse();
    }

    @Test
    @DisplayName("边界：null/空/未知工具 → false（CC isAutoModeAllowlistedTool）")
    void nullAndUnknownNotListed() {
        assertThat(whitelist.isSafe(null)).isFalse();
        assertThat(whitelist.isSafe("")).isFalse();
        assertThat(whitelist.isSafe("NonExistentTool")).isFalse();
        assertThat(whitelist.isSafe("read_file ")).as("带空白不匹配").isFalse();
    }

    @Test
    @DisplayName("N/A 项登记（Java 无对应工具）：Workflow/TerminalCapture/OverflowTest/VerifyPlanExecution/classify_result")
    void naItemsNotListed() {
        // CC 4 条件项（feature/ant 门控）—— Java 无工具实现 → N/A（依据见类 javadoc）
        assertThat(whitelist.isSafe("Workflow")).isFalse();
        assertThat(whitelist.isSafe("TerminalCapture")).isFalse();
        assertThat(whitelist.isSafe("OverflowTestTool")).isFalse();
        assertThat(whitelist.isSafe("VerifyPlanExecution")).isFalse();
        // CC 固定项 classify_result（YOLO_CLASSIFIER_TOOL_NAME）—— Java 分类器非 Tool → N/A
        assertThat(whitelist.isSafe("classify_result")).isFalse();
    }
}
