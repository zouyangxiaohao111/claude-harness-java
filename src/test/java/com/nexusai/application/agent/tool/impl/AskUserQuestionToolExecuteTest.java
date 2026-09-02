package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AskUserQuestionTool.execute answers 缺省语义（CC call answers={} 对齐）。
 *
 * <p>WHY（规则九 · 验证意图）：CC {@code AskUserQuestionTool.tsx:209-213} 的 {@code call}
 * 声明 {@code answers = {}} 缺省值，answers 缺失时静默回退空对象、恒返回
 * {@code data:{questions, answers:{}}}，全程无 fail-loud/error 分支；其
 * {@code mapToolResultToToolResultBlockParam}（:224-244）对空 answers 也产出空答案文案
 * （"User has answered your questions: . You can now continue..."）而非报错。
 *
 * <p>本测试锁定 Java 端同一语义：answers 缺失/非法 → 静默回退空 answers 且不报错
 * （isError==false），而非旧实现的 fail-loud 明确失败。若有人恢复 fail-loud 守卫，
 * 用例 1 会转红——这体现"缺失 answers 是合法输入（CC optional，非必填）"这一契约为何重要。
 */
@DisplayName("AskUserQuestionTool.execute answers 缺省语义（CC call answers={} 对齐）")
class AskUserQuestionToolExecuteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode questionsInput() {
        ObjectNode input = JSON.createObjectNode();
        ObjectNode q = input.putArray("questions").addObject();
        q.put("question", "Which library should we use?");
        q.put("header", "Library");
        ObjectNode opt = q.putArray("options").addObject();
        opt.put("label", "date-fns");
        opt.put("description", "Modern date library");
        return input;
    }

    @Test
    @DisplayName("answers 缺失 → 静默回退空 answers（isError==false，对齐 CC answers={}）")
    void missingAnswersFallsBackToEmptyObject() {
        AskUserQuestionTool tool = new AskUserQuestionTool();
        AgentToolResult<?> result = tool.execute(
            new ToolUseBlock("ask-1", "AskUserQuestion", questionsInput()));

        // CC call :209-213 恒返回 data:{questions, answers:{}}，无 error 分支。
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("answers 缺失静默回退不产生 error（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();

        Map<String, Object> out = ToolResult.presentationMeta((ToolResult<?>) result);
        assertThat(out).containsKeys("questions", "answers");

        // questions 原样回传（CC data.questions）。
        assertThat(out.get("questions")).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) out.get("questions")).isArray()).isTrue();
        assertThat(((JsonNode) out.get("questions")).size()).isEqualTo(1);

        // answers 回退为非空 ObjectNode 且 isEmpty（CC answers={} 缺省，不伪造答案）。
        Object answers = out.get("answers");
        assertThat(answers).as("answers 必须回退为空对象而非 null").isNotNull();
        assertThat(answers).isInstanceOf(JsonNode.class);
        JsonNode answersNode = (JsonNode) answers;
        assertThat(answersNode.isObject()).as("answers 必须是对象（CC z.record）").isTrue();
        assertThat(answersNode.isEmpty()).as("缺省 answers 应为空对象（CC answers={}）").isTrue();
    }

    @Test
    @DisplayName("answers 非空 → 原样回传 answers 值（回归：不误吞真实答案）")
    void presentAnswersEchoedBack() {
        ObjectNode input = questionsInput();
        ObjectNode answers = input.putObject("answers");
        answers.put("Which library should we use?", "date-fns");

        AskUserQuestionTool tool = new AskUserQuestionTool();
        AgentToolResult<?> result = tool.execute(
            new ToolUseBlock("ask-2", "AskUserQuestion", input));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("answers 非空成功路径 data 非错误消息")
            .isFalse();

        JsonNode answersNode = (JsonNode) ToolResult.presentationMeta((ToolResult<?>) result).get("answers");
        assertThat(answersNode.isObject()).isTrue();
        assertThat(answersNode.size()).isEqualTo(1);
        assertThat(answersNode.get("Which library should we use?").asText()).isEqualTo("date-fns");
    }
}
