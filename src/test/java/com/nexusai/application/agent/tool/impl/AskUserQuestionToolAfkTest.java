package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AskUserQuestion 的 AFK 超时结果契约（对齐 CC exe 2.1.328）。
 *
 * <p>CC 事实（{@code b-afk-contract.md} §5 / 偏移 334217400）：
 * <ul>
 *   <li>超时提交 = {@code allow}，{@code updatedInput} 带 {@code afkTimeoutMs}；</li>
 *   <li>只提交已答（{@code answersToSubmit}），未答<b>不填默认值</b>，只计 skipped 遥测；</li>
 *   <li>模型文案 = {@code "No response after Ns — the user may be away from keyboard…"}；
 *       部分作答时追加 {@code "Before going idle the user had selected: …"}；</li>
 *   <li>{@code afkTimeoutMs} 只在 <b>输出</b> schema（输入 schema 没有该字段）。</li>
 * </ul>
 */
@DisplayName("AskUserQuestionTool · AFK 超时结果（只提交已答 / 文案 / schema）")
class AskUserQuestionToolAfkTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode twoQuestions() {
        ObjectNode input = JSON.createObjectNode();
        ObjectNode q1 = input.putArray("questions").addObject();
        q1.put("question", "Which library should we use?");
        q1.put("header", "Library");
        ObjectNode o1 = q1.putArray("options").addObject();
        o1.put("label", "date-fns");
        o1.put("description", "Modern date library");

        ObjectNode q2 = input.putArray("questions").addObject();
        q2.put("question", "Which runtime?");
        q2.put("header", "Runtime");
        ObjectNode o2 = q2.putArray("options").addObject();
        o2.put("label", "node");
        o2.put("description", "Node.js");
        return input;
    }

    @Test
    @DisplayName("全部未答 + 超时 ⇒ 文案「No response after …」，不出现默认答案、不出现已选列表")
    void timeoutWithoutAnswersHasNoDefaults() {
        ObjectNode input = twoQuestions();
        input.put("answers", JSON.createObjectNode());
        input.put("afkTimeoutMs", 60_000);

        AskUserQuestionTool tool = new AskUserQuestionTool();
        AgentToolResult<?> result = tool.execute(new ToolUseBlock("ask-afk-1", "AskUserQuestion", input));

        Map<String, Object> out = ToolResult.presentationMeta((ToolResult<?>) result);
        String summary = String.valueOf(out.get("summary"));
        assertThat(summary)
            .as("对齐 CC mapToolResultToToolResultBlockParam 的 AFK 分支")
            .startsWith("No response after 60s — the user may be away from keyboard.");
        assertThat(summary)
            .as("一题都没答 ⇒ 不追加已选列表")
            .doesNotContain("Before going idle");
        assertThat(summary)
            .as("未答不补默认值")
            .doesNotContain("date-fns")
            .doesNotContain("node");
    }

    @Test
    @DisplayName("部分作答 + 超时 ⇒ 保留已答、未答留空，文案含已选列表")
    void partialAnswersArePreserved() {
        ObjectNode input = twoQuestions();
        ObjectNode answers = input.putObject("answers");
        answers.put("Which library should we use?", "date-fns");
        input.put("afkTimeoutMs", 60_000);

        AgentToolResult<?> result = new AskUserQuestionTool()
            .execute(new ToolUseBlock("ask-afk-2", "AskUserQuestion", input));

        Map<String, Object> out = ToolResult.presentationMeta((ToolResult<?>) result);
        String summary = String.valueOf(out.get("summary"));
        assertThat(summary)
            .contains("Before going idle the user had selected: \"Which library should we use?\"=\"date-fns\".");
        assertThat(summary)
            .as("未作答的第二题不出现在已选列表里")
            .doesNotContain("Which runtime?");
    }

    @Test
    @DisplayName("结构化输出带 afkTimeoutMs（人类作答路径不带）")
    void structuredOutputCarriesAfkTimeoutOnlyOnTimeout() {
        ObjectNode timeoutInput = twoQuestions();
        timeoutInput.put("answers", JSON.createObjectNode());
        timeoutInput.put("afkTimeoutMs", 300_000);
        Map<String, Object> timeoutOut = ToolResult.presentationMeta((ToolResult<?>) new AskUserQuestionTool()
            .execute(new ToolUseBlock("ask-afk-3", "AskUserQuestion", timeoutInput)));
        assertThat(timeoutOut).containsEntry("afkTimeoutMs", 300_000);

        ObjectNode humanInput = twoQuestions();
        humanInput.putObject("answers").put("Which runtime?", "node");
        Map<String, Object> humanOut = ToolResult.presentationMeta((ToolResult<?>) new AskUserQuestionTool()
            .execute(new ToolUseBlock("ask-human-1", "AskUserQuestion", humanInput)));
        assertThat(humanOut).doesNotContainKey("afkTimeoutMs");
        assertThat(String.valueOf(humanOut.get("summary")))
            .as("人类作答文案保持改造前逐字不变")
            .startsWith("User has answered your questions:");
    }

    @Test
    @DisplayName("afkTimeoutMs 只在输出 schema，输入 schema 不含任何超时字段（对齐 CC）")
    void timeoutFieldIsOutputOnly() {
        AskUserQuestionTool tool = new AskUserQuestionTool();
        JsonNode outputProps = tool.outputSchema().path("properties");
        assertThat(outputProps.has("afkTimeoutMs")).isTrue();
        assertThat(outputProps.path("afkTimeoutMs").path("type").asText()).isEqualTo("integer");

        JsonNode inputProps = tool.inputSchema().path("properties");
        assertThat(inputProps.has("afkTimeoutMs"))
            .as("CC 输入 schema 没有超时字段 ⇒ 模型不能设置/关闭超时")
            .isFalse();
        assertThat(inputProps.has("timeout")).isFalse();
    }
}
