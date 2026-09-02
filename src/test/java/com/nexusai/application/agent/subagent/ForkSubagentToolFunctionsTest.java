package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session E · P1-3 (fork 工具函数 3 件套) · 对齐 CC Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:107-210.
 *
 * <p><b>WHY (意图验证 · CLAUDE.md 规则九)</b>: fork subagent 的核心契约是
 * <b>prompt cache 共享</b>——所有 fork child 必须产生 byte-identical API 请求前缀,
 * 只有最后一条 user message 的 directive text 不同. 因此:
 *
 * <ol>
 *   <li><b>buildForkedMessages</b> 必须在 user message 内为 assistant 的每一个 tool_use 块
 *       产生一个相同 placeholder 的 tool_result, 且每个 tool_use_id 必须一一对应
 *       assistantMessage 里的 tool_use.id——否则 LLM 端拒绝 tool_use/tool_result 配对</li>
 *   <li><b>buildChildMessage</b> 必须把 10 条不可协商规则 + Output format 段都装进
 *       {@code <fork-boilerplate>...</fork-boilerplate>}, 末尾追加
 *       {@code Your directive: <directive>}——否则 fork 子 agent 不知道自己是什么角色</li>
 *   <li><b>buildWorktreeNotice</b> 必须显式告知子 agent 它在 isolated git worktree,
 *       路径要从 parent cwd 翻译到 worktree cwd——否则子 agent 会改父 agent 的文件</li>
 * </ol>
 *
 * <p><b>测试设计原则</b>: 每条契约一旦被破坏, 对应测试必须失败 (例: 把 placeholder
 * 改成 "started" 而不是 "Fork started — processing in background" → cache 共享失效
 * 但仍能跑通, 必须断言精确字符串).
 */
@DisplayName("Session E · P1-3 · fork 工具函数 3 件套 (buildForkedMessages/buildChildMessage/buildWorktreeNotice)")
class ForkSubagentToolFunctionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Test 1: buildForkedMessages ─────────────────────────────────────────

    @Test
    @DisplayName("buildForkedMessages: 缓存共享 — tool_result.tool_use_id 必须对应 assistantMessage 的 tool_use.id")
    void buildForkedMessages_sharesToolUseIdWithAssistant() {
        // GIVEN: 一个含 2 个 tool_use 块的 assistantMessage
        JsonNode input1 = JSON.createObjectNode().put("cmd", "ls");
        JsonNode input2 = JSON.createObjectNode().put("path", "/tmp");
        ForkSubagentMessages.BetaToolUseBlock toolUse1 =
            new ForkSubagentMessages.BetaToolUseBlock("toolu_test_01", "Bash", input1);
        ForkSubagentMessages.BetaToolUseBlock toolUse2 =
            new ForkSubagentMessages.BetaToolUseBlock("toolu_test_02", "Read", input2);
        ForkSubagentMessages.AssistantMessage assistant = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid-original",
            List.of(
                new ForkSubagentMessages.BetaTextBlock("thinking about tools"),
                toolUse1,
                toolUse2,
                new ForkSubagentMessages.BetaTextBlock("after tools")
            )
        );

        // WHEN: buildForkedMessages(directive, assistant)
        List<ForkSubagentMessages.Message> messages = ForkSubagentMessages.buildForkedMessages(
            "do the thing", assistant);

        // THEN: 返回 2 条 — assistant (cloned with new uuid) + user (tool_results + directive)
        assertThat(messages).hasSize(2);

        ForkSubagentMessages.Message firstMsg = messages.get(0);
        assertThat(firstMsg).isInstanceOf(ForkSubagentMessages.AssistantMessage.class);
        ForkSubagentMessages.AssistantMessage clonedAssistant = (ForkSubagentMessages.AssistantMessage) firstMsg;
        // WHY: 必须新 uuid —— 防 fork child 复用父 message 触发 transcript 渲染冲突
        assertThat(clonedAssistant.uuid())
            .isNotEqualTo(assistant.uuid())
            .isNotBlank();
        // WHY: 必须保留全部 content blocks (thinking + tool_use1 + tool_use2 + text)
        // 不能过滤, 否则 cache prefix 失效
        assertThat(clonedAssistant.content()).hasSize(4);

        ForkSubagentMessages.Message secondMsg = messages.get(1);
        assertThat(secondMsg).isInstanceOf(ForkSubagentMessages.UserMessage.class);
        ForkSubagentMessages.UserMessage userMsg = (ForkSubagentMessages.UserMessage) secondMsg;
        List<ForkSubagentMessages.ContentBlock> userBlocks = userMsg.content();

        // WHY: tool_result 数量 = tool_use 数量 (2 个), 顺序一一对应
        List<ForkSubagentMessages.BetaToolResultBlock> toolResults = userBlocks.stream()
            .filter(b -> b instanceof ForkSubagentMessages.BetaToolResultBlock)
            .map(b -> (ForkSubagentMessages.BetaToolResultBlock) b)
            .toList();
        assertThat(toolResults).hasSize(2);
        assertThat(toolResults.get(0).toolUseId()).isEqualTo("toolu_test_01");
        assertThat(toolResults.get(1).toolUseId()).isEqualTo("toolu_test_02");

        // WHY: 所有 tool_result placeholder 必须 byte-identical (CC FORK_PLACEHOLDER_RESULT 常量)
        // 这就是"缓存共享"的核心 — prefix 必须 100% 一致
        for (ForkSubagentMessages.BetaToolResultBlock tr : toolResults) {
            assertThat(tr.content()).hasSize(1);
            assertThat(tr.content().get(0).text()).isEqualTo("Fork started — processing in background");
        }

        // WHY: 最后一条 text block = buildChildMessage(directive) 输出
        ForkSubagentMessages.BetaTextBlock directiveBlock = (ForkSubagentMessages.BetaTextBlock)
            userBlocks.get(userBlocks.size() - 1);
        assertThat(directiveBlock.text()).contains("do the thing");
    }

    @Test
    @DisplayName("buildForkedMessages: 无 tool_use 块 → 仅返回 user message (含 directive)")
    void buildForkedMessages_noToolUseBlocks_returnsOnlyUserMessage() {
        // GIVEN: 只有 text block 的 assistantMessage (没有 tool_use)
        ForkSubagentMessages.AssistantMessage assistant = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid-text-only",
            List.of(new ForkSubagentMessages.BetaTextBlock("just thinking"))
        );

        // WHEN
        List<ForkSubagentMessages.Message> messages = ForkSubagentMessages.buildForkedMessages(
            "do something", assistant);

        // THEN: 仅返回 1 条 user message (CC forkSubagent.ts:127-139)
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(ForkSubagentMessages.UserMessage.class);
        ForkSubagentMessages.UserMessage userMsg = (ForkSubagentMessages.UserMessage) messages.get(0);
        assertThat(userMsg.content()).hasSize(1);
        ForkSubagentMessages.BetaTextBlock textBlock = (ForkSubagentMessages.BetaTextBlock) userMsg.content().get(0);
        assertThat(textBlock.text()).contains("do something");
        assertThat(textBlock.text()).contains("fork-boilerplate");
    }

    // ── Test 2: buildChildMessage ──────────────────────────────────────────

    @Test
    @DisplayName("buildChildMessage: 模板完整性 — 包含 <fork-boilerplate> 标签 + STOP + directive + Output format + 10 条 RULES")
    void buildChildMessage_includesForkBoilerplate() {
        // GIVEN
        String directive = "Refactor the auth module";

        // WHEN
        String out = ForkChildBoilerplate.buildChildMessage(directive);

        // THEN: <fork-boilerplate> 开闭标签 (CC forkSubagent.ts:172, 195)
        assertThat(out).startsWith("<fork-boilerplate>");
        assertThat(out).contains("</fork-boilerplate>");

        // WHY: 顶部必须 "STOP. READ THIS FIRST." — 强制 LLM 先读规则再行动
        assertThat(out).contains("STOP. READ THIS FIRST.");

        // WHY: "You are a forked worker process. You are NOT the main agent."
        // — fork 子 agent 必须知道自己是被 fork 的 worker, 不是主 agent
        assertThat(out).contains("You are a forked worker process");
        assertThat(out).contains("You are NOT the main agent");

        // WHY: 必须包含 "Output format" 段 + 5 个 plain text label (CC forkSubagent.ts:189-194)
        assertThat(out).contains("Output format (plain text labels, not markdown headers)");
        assertThat(out).contains("Scope:");
        assertThat(out).contains("Result:");
        assertThat(out).contains("Key files:");
        assertThat(out).contains("Files changed:");
        assertThat(out).contains("Issues:");

        // WHY: 10 条 RULES 必须全部出现 — 防递归 fork / 防闲谈 / 强制 tool-first
        // CC forkSubagent.ts:178-187 — 规则 1-10 的关键词
        assertThat(out).contains("RULES (non-negotiable)");
        assertThat(out).contains("Do NOT spawn sub-agents");
        assertThat(out).contains("Do NOT converse, ask questions");
        assertThat(out).contains("Do NOT editorialize");
        assertThat(out).contains("commit your changes before reporting");
        assertThat(out).contains("Do NOT emit text between tool calls");
        assertThat(out).contains("Stay strictly within your directive");
        assertThat(out).contains("Keep your report under 500 words");
        assertThat(out).contains("Your response MUST begin with \"Scope:\"");
        assertThat(out).contains("REPORT structured facts, then stop");

        // WHY: 末尾必须是 "Your directive: <directive>" — CC constants/xml.ts:66
        // FORK_DIRECTIVE_PREFIX = 'Your directive: ' — 用户指令追加在末尾供 fork 子 agent 执行
        assertThat(out).endsWith("Your directive: " + directive);
    }

    // ── Test 3: buildWorktreeNotice ─────────────────────────────────────────

    @Test
    @DisplayName("buildWorktreeNotice: 路径翻译提示 — 包含 parentCwd + worktreeCwd + isolated git worktree")
    void buildWorktreeNotice_translatesPathsToChildCwd() {
        // GIVEN
        String parentCwd = "/Users/parent/proj";
        String worktreeCwd = "/Users/parent/proj.worktrees/feat-x";

        // WHEN
        String out = ForkWorktreePaths.buildWorktreeNotice(parentCwd, worktreeCwd);

        // THEN: parentCwd + worktreeCwd 都出现 (CC forkSubagent.ts:209)
        assertThat(out).contains(parentCwd);
        assertThat(out).contains(worktreeCwd);

        // WHY: 必须显式告知 isolated git worktree — 子 agent 不应改父 agent 的文件
        assertThat(out).contains("isolated git worktree");

        // WHY: 必须显式告知 "translate them to your worktree root" —
        // 父对话里出现的路径是 parent cwd 相对路径, 必须翻译
        assertThat(out).contains("translate them to your worktree root");

        // WHY: 必须告知 "Re-read files before editing" — 父可能已修改文件,
        // 子 agent 不能信任继承上下文里的文件内容
        assertThat(out).contains("Re-read files before editing");

        // WHY: 必须告知 "Your changes stay in this worktree" — 防止误改父文件
        assertThat(out).contains("Your changes stay in this worktree");
    }
}