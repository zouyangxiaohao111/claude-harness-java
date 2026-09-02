package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-G2 · AskUserQuestionTool 对齐 CC AskUserQuestionTool.tsx（组 6-2，TR-G3-⊕-13 + 补校验）聚焦测试。
 *
 * <p>WHY（规则九，验证意图）：
 * <ul>
 *   <li><b>multiSelect（⊕-13）</b>：CC 字段名 {@code multiSelect}（camelCase，AskUserQuestionTool.tsx:23）；
 *       旧 {@code multi_select}（snake_case）是 Java-only 漂移，前端/模型按 CC 契约调用会校验失败；</li>
 *   <li><b>strictObject + 嵌套必填（CC :62-67）</b>：顶层 {@code additionalProperties:false} 拒绝未知键；
 *       question 项 question/header/options 必填、option 项 label/description 必填——旧开放 schema
 *       （无嵌套必填）会让缺 header/options 的问题通过校验；</li>
 *   <li><b>UNIQUENESS_REFINE（CC :32-54）</b>：questions 文本互异 + 每题内 option label 互异——
 *       重复选项标签会致 UI 歧义（用户无法区分相同标签的选项）；</li>
 *   <li><b>能力对齐（CC :146-154）</b>：isReadOnly/isConcurrencySafe → true（提问不改状态、可并发）、
 *       toAutoClassifierInput 拼 questions 文本（安全分类器相关性）。</li>
 * </ul>
 */
@DisplayName("AskUserQuestionTool IMP-G2 对齐（multiSelect + UNIQUENESS_REFINE + strict schema）")
class AskUserQuestionToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AskUserQuestionTool newTool() {
        return new AskUserQuestionTool();
    }

    private ObjectNode questionsInput() {
        ObjectNode input = JSON.createObjectNode();
        ObjectNode q = input.putArray("questions").addObject();
        q.put("question", "Which library should we use?");
        q.put("header", "Library");
        // Jackson putArray 同键会替换数组 —— 必须复用同一 ArrayNode 追加两个 option
        com.fasterxml.jackson.databind.node.ArrayNode options = q.putArray("options");
        ObjectNode opt = options.addObject();
        opt.put("label", "date-fns");
        opt.put("description", "Modern date library");
        ObjectNode opt2 = options.addObject();
        opt2.put("label", "dayjs");
        opt2.put("description", "Lightweight date library");
        return input;
    }

    @Test
    @DisplayName("inputSchema: 字段名 multiSelect（非 multi_select）+ 顶层 strictObject + 嵌套必填（⊕-13）")
    void inputSchema_usesCamelCaseMultiSelect_andStrictNesting() {
        // WHY: CC AskUserQuestionTool.tsx:23 multiSelect（camelCase）+ :62-67 strictObject——
        //      旧 multi_select（snake_case）漂移、顶层无 additionalProperties:false、无嵌套必填。
        JsonNode schema = newTool().inputSchema();

        // ⊕-13 字段名对齐：multiSelect 存在、multi_select 消失
        JsonNode qItems = schema.get("properties").get("questions").get("items");
        assertThat(qItems.get("properties").has("multiSelect"))
                .as("CC 字段名 multiSelect（camelCase）必须存在").isTrue();
        assertThat(qItems.get("properties").has("multi_select"))
                .as("旧 multi_select（snake_case）必须删除").isFalse();

        // strictObject：顶层拒绝未知键
        assertThat(schema.get("additionalProperties").asBoolean())
                .as("CC z.strictObject → 顶层 additionalProperties:false").isFalse();

        // 嵌套必填：question/header/options（CC z.object 非 optional 字段）
        assertThat(qItems.get("required").toString())
                .as("question/header/options 必填（CC z.object 必填）")
                .contains("question").contains("header").contains("options");
        JsonNode optItems = qItems.get("properties").get("options").get("items");
        assertThat(optItems.get("required").toString())
                .as("option label/description 必填（CC questionOptionSchema）")
                .contains("label").contains("description");
    }

    @Test
    @DisplayName("validateInput: questions 文本重复 → 拒绝（CC UNIQUENESS_REFINE :32-54）")
    void validateInput_rejectsDuplicateQuestionTexts() {
        // WHY: CC UNIQUENESS_REFINE.check —— questions 文本互异；重复问题文本致模型无法区分两个问题。
        AskUserQuestionTool tool = newTool();
        ObjectNode input = questionsInput();
        // 在现有 questions 数组追加一个 question 文本与第一个相同（Jackson putArray 会替换数组，须用 addObject 追加）
        com.fasterxml.jackson.databind.node.ArrayNode questions =
                (com.fasterxml.jackson.databind.node.ArrayNode) input.get("questions");
        ObjectNode dup = questions.addObject();
        dup.put("question", "Which library should we use?");
        dup.put("header", "Library 2");
        ObjectNode dOpt = dup.putArray("options").addObject();
        dOpt.put("label", "moment");
        dOpt.put("description", "Legacy date library");

        Tool.ValidationResult result = tool.validateInput(input, null);
        assertThat(result.ok()).isFalse();
        assertThat(result.message())
                .isEqualTo("Question texts must be unique, option labels must be unique within each question");
    }

    @Test
    @DisplayName("validateInput: 每题内 option label 重复 → 拒绝（CC UNIQUENESS_REFINE）")
    void validateInput_rejectsDuplicateOptionLabels() {
        // WHY: CC UNIQUENESS_REFINE —— 每题内 option label 互异；重复标签致 UI 选项歧义。
        AskUserQuestionTool tool = newTool();
        ObjectNode input = questionsInput();
        // 在现有 options 数组追加第三个 option，label 与第一个相同（putArray 会替换数组，须用 addObject 追加）
        com.fasterxml.jackson.databind.node.ArrayNode options =
                (com.fasterxml.jackson.databind.node.ArrayNode) input.get("questions").get(0).get("options");
        ObjectNode dupOpt = options.addObject();
        dupOpt.put("label", "date-fns");
        dupOpt.put("description", "Duplicate label");

        Tool.ValidationResult result = tool.validateInput(input, null);
        assertThat(result.ok()).isFalse();
    }

    @Test
    @DisplayName("validateInput: 唯一 questions/options → 通过（CC UNIQUENESS_REFINE pass）")
    void validateInput_passesWithUniqueQuestions() {
        AskUserQuestionTool tool = newTool();
        Tool.ValidationResult result = tool.validateInput(questionsInput(), null);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("isReadOnly/isConcurrencySafe → true（CC :146-151）")
    void readOnlyAndConcurrencySafe_true() {
        // WHY: CC AskUserQuestionTool.tsx:146-151 —— 提问工具不改状态（只读）且可并发执行。
        AskUserQuestionTool tool = newTool();
        assertThat(tool.isReadOnly(questionsInput())).isTrue();
        assertThat(tool.isConcurrencySafe(questionsInput())).isTrue();
    }

    @Test
    @DisplayName("toAutoClassifierInput: questions 文本 join ' | '（CC :152-154）")
    void toAutoClassifierInput_joinsQuestionTexts() {
        // WHY: CC :152-154 input.questions.map(q => q.question).join(' | ') —— 安全分类器输入。
        AskUserQuestionTool tool = newTool();
        ObjectNode input = JSON.createObjectNode();
        // Jackson putArray 同键会替换数组 —— 复用同一 ArrayNode 追加两个 question
        com.fasterxml.jackson.databind.node.ArrayNode questions = input.putArray("questions");
        ObjectNode q1 = questions.addObject();
        q1.put("question", "Which library?");
        ObjectNode q2 = questions.addObject();
        q2.put("question", "Which style?");
        assertThat(tool.toAutoClassifierInput(input)).isEqualTo("Which library? | Which style?");
    }
}
