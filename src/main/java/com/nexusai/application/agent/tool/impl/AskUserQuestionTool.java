package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AskUserQuestionPrompt;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AskUserQuestionTool — 对齐 CC {@code AskUserQuestionTool.tsx}（真实 UI 交互，非教学版伪造）。
 *
 * <p>CC 关键行为（自验源码，不信注释）:
 * <ul>
 *   <li>inputSchema {@code strictObject({questions, ...commonFields})}（:62-67）——
 *       commonFields 含 answers(:56)/annotations(:57)/metadata(:58)，由权限/交互层注入 input</li>
 *   <li>checkPermissions → behavior='ask'（:182-188），requiresUserInteraction=true（:155-157）——
 *       弹窗提问，bypass-immune</li>
 *   <li>call 仅回传 questions + answers（:209-223），全程无 'default to first option' 伪造逻辑</li>
 *   <li>outputSchema = {questions, answers, annotations}（:69-73）</li>
 *   <li>mapToolResultToToolResultBlockParam 拼 "User has answered your questions: ..."（:224-244）</li>
 * </ul>
 *
 * <p>answers 缺省：对齐 CC call {@code answers = {}}（:209-213），input 缺 answers → 静默回退
 * 空 answers 返回 {@code {questions, answers:{}}}（不伪造答案，不 fail-loud）。
 *
 * <p>IMP-G2（组 6-2，TR-G3-⊕-13 + 补校验）：
 * <ul>
 *   <li><b>multiSelect（⊕-13）</b>：输入字段 {@code multi_select}（snake_case）→
 *       {@code multiSelect}（CC camelCase，AskUserQuestionTool.tsx:23）；</li>
 *   <li><b>strictObject + 嵌套必填</b>（:62-67）：顶层 {@code additionalProperties:false}；
 *       question 项 question/header/options 必填，option 项 label/description 必填；</li>
 *   <li><b>UNIQUENESS_REFINE</b>（:32-54）：questions 文本互异 + 每题内 option label 互异，
 *       在 {@link #validateInput} 语义层实现（errorCode 1）；</li>
 *   <li><b>能力对齐</b>：isReadOnly()/isConcurrencySafe() → true（:146-151）、toAutoClassifierInput
 *       （:152-154）、searchHint（:111）、userFacingName ''（:132-134）、prompt() 接线
 *       ASK_USER_QUESTION_TOOL_PROMPT（:117-125）。</li>
 * </ul>
 */
@Component
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);

    public static final String NAME = "AskUserQuestion";

    /** UNIQUENESS_REFINE 失败 message · 对齐 CC AskUserQuestionTool.tsx:53。 */
    private static final String UNIQUENESS_MESSAGE =
        "Question texts must be unique, option labels must be unique within each question";

    /**
     * [IMP-G] G26③ question preview format 状态 · 对齐 CC bootstrap/state.ts:1120-1126
     * {@code getQuestionPreviewFormat(): 'markdown' | 'html' | undefined} /
     * {@code setQuestionPreviewFormat(format)}（main.tsx:835-843 由
     * {@code CLAUDE_CODE_QUESTION_PREVIEW_FORMAT} env / toolConfig 配置）。
     *
     * <p>Java Web 后端经 session 建立接线（ChatService.processUserMessage 会话建立处调用
     * {@link #applyQuestionPreviewFormatFromConfig()}，仅当 {@link #QUESTION_PREVIEW_FORMAT_ENV}
     * 配置值为 'markdown'|'html' 才 set，映射 CC main.tsx:835-837 合法值分支）；
     * null = 未 opt-in（SDK 消费方不渲染 preview 字段，省略 preview 指导，对齐 CC :119-123）。
     * 进程级 volatile（CC 为模块级 STATE；会话建立时覆盖）。
     */
    private static volatile String questionPreviewFormat;

    /** 读 preview format（null = 未 opt-in）· 对齐 CC getQuestionPreviewFormat。 */
    public static String getQuestionPreviewFormat() {
        return questionPreviewFormat;
    }

    /** 设置 preview format（'markdown' | 'html'）· 对齐 CC setQuestionPreviewFormat。 */
    public static void setQuestionPreviewFormat(String format) {
        questionPreviewFormat = format;
    }

    /** CC 配置源 env 名 · 对齐 CC main.tsx:835 {@code process.env.CLAUDE_CODE_QUESTION_PREVIEW_FORMAT}。 */
    public static final String QUESTION_PREVIEW_FORMAT_ENV = "CLAUDE_CODE_QUESTION_PREVIEW_FORMAT";

    /**
     * 会话建立接线 · 从配置源读 preview format 并 set（对齐 CC main.tsx:835-843）。
     *
     * <p>CC 语义：{@code process.env.CLAUDE_CODE_QUESTION_PREVIEW_FORMAT} 为 'markdown'|'html'
     * 才 set 该值（:836-837）；否则仅 CLI 客户端套默认 'markdown'（:838-842）。Java Web 后端
     * 是 server 而非 CC CLI 客户端（preview 渲染在 web 前端），不套用 CLI 默认分支，仅映射
     * 「配置值合法才 set」：env 未设置/非法 → 保持 null（feature 休眠，prompt() 省略 preview
     * 指导、html 校验不触发，对齐 CC :118-123 undefined 分支）。
     */
    public static void applyQuestionPreviewFormatFromConfig() {
        String previewFormat = System.getenv(QUESTION_PREVIEW_FORMAT_ENV);
        if ("markdown".equals(previewFormat) || "html".equals(previewFormat)) {
            setQuestionPreviewFormat(previewFormat);
            if (log.isDebugEnabled()) {
                log.debug("[AskUserQuestionTool] 会话建立接线 previewFormat={}（CC main.tsx:835-843 合法值分支）", previewFormat);
            }
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 对齐 CC AskUserQuestionTool.tsx:114-116 DESCRIPTION（prompt.ts:7-8）
        return "Asks the user multiple choice questions to gather information, clarify ambiguity, "
                + "understand preferences, make decisions or offer them choices.";
    }

    /** 是否延迟执行 · 对齐 CC AskUserQuestionTool.tsx:113 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /** 搜索提示 · 对齐 CC AskUserQuestionTool.tsx:111 searchHint。 */
    @Override
    public String searchHint() {
        return "prompt the user with a multiple-choice question";
    }

    /** 只读 · 对齐 CC AskUserQuestionTool.tsx:149-151 isReadOnly() → true（提问不改状态）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 并发安全 · 对齐 CC AskUserQuestionTool.tsx:146-148 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 自动分类器输入 · 对齐 CC AskUserQuestionTool.tsx:152-154
     * {@code input.questions.map(q => q.question).join(' | ')}。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null || !input.has("questions") || !input.get("questions").isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (JsonNode q : input.get("questions")) {
            String text = q != null ? q.path("question").asText() : "";
            if (text.isBlank()) {
                continue;
            }
            if (!first) {
                sb.append(" | ");
            }
            sb.append(text);
            first = false;
        }
        return sb.toString();
    }

    /** 用户可见名 · 对齐 CC AskUserQuestionTool.tsx:132-134 userFacingName() → ''（UI 不显示）。 */
    @Override
    public String userFacingName() {
        return "";
    }

    /**
     * 工具提示词 · 对齐 CC AskUserQuestionTool.tsx:117-125 prompt()（[IMP-G] G26③ 完整补）。
     *
     * <p>CC：{@code format = getQuestionPreviewFormat()}；format 未定义（未 opt-in）→ 返回
     * {@code ASK_USER_QUESTION_TOOL_PROMPT}（:118-123）；否则返回
     * {@code ASK_USER_QUESTION_TOOL_PROMPT + PREVIEW_FEATURE_PROMPT[format]}（:124）。
     * ToolRegistry 序列化工具描述优先取 {@code prompt()}（api.ts:171 对齐），提示词由此注入 LLM。
     */
    @Override
    public String prompt() {
        String format = getQuestionPreviewFormat();
        if (format == null) {
            // CC :118-123 未 opt-in preview format → 省略 preview 指导
            return AskUserQuestionPrompt.ASK_USER_QUESTION_TOOL_PROMPT;
        }
        // CC :124 ASK_USER_QUESTION_TOOL_PROMPT + PREVIEW_FEATURE_PROMPT[format]
        String previewPrompt = AskUserQuestionPrompt.PREVIEW_FEATURE_PROMPT.get(format);
        return previewPrompt != null
                ? AskUserQuestionPrompt.ASK_USER_QUESTION_TOOL_PROMPT + previewPrompt
                : AskUserQuestionPrompt.ASK_USER_QUESTION_TOOL_PROMPT;
    }

    /**
     * 输入校验 · 对齐 CC AskUserQuestionTool.tsx:158-181 validateInput（[IMP-G] G26③ 完整补）。
     *
     * <p>CC 两段：
     * <ol>
     *   <li>UNIQUENESS_REFINE（:32-54）：questions 文本互异 + 每题内 option label 互异，失败 message
     *       = 'Question texts must be unique, option labels must be unique within each question'（:53）；</li>
     *   <li>html preview 校验（:160-178）：{@code getQuestionPreviewFormat() === 'html'} 时逐 option
     *       校验 preview HTML，失败 errorCode 1 且 message = {@code Option "${label}" in question
     *       "${question}": ${err}}（:171-176）。</li>
     * </ol>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        if (input == null || !input.isObject() || !input.has("questions") || !input.get("questions").isArray()) {
            return ValidationResult.pass();
        }
        // CC :161 getQuestionPreviewFormat() !== 'html' → 跳过 preview 校验
        boolean htmlPreview = "html".equals(getQuestionPreviewFormat());
        java.util.Set<String> seenQuestions = new java.util.HashSet<>();
        for (JsonNode q : input.get("questions")) {
            if (q == null || !q.isObject()) {
                continue;
            }
            String questionText = q.path("question").asText("");
            if (!questionText.isBlank() && !seenQuestions.add(questionText)) {
                return ValidationResult.fail("1", UNIQUENESS_MESSAGE);
            }
            JsonNode options = q.get("options");
            if (options != null && options.isArray()) {
                java.util.Set<String> seenLabels = new java.util.HashSet<>();
                for (JsonNode opt : options) {
                    if (opt == null || !opt.isObject()) {
                        continue;
                    }
                    String label = opt.path("label").asText("");
                    if (!label.isBlank() && !seenLabels.add(label)) {
                        return ValidationResult.fail("1", UNIQUENESS_MESSAGE);
                    }
                    // CC :166-176 html preview 校验
                    if (htmlPreview) {
                        String preview = opt.has("preview") && !opt.get("preview").isNull()
                                ? opt.get("preview").asText() : null;
                        String err = validateHtmlPreview(preview);
                        if (err != null) {
                            return ValidationResult.fail("1",
                                    "Option \"" + label + "\" in question \"" + questionText + "\": " + err);
                        }
                    }
                }
            }
        }
        return ValidationResult.pass();
    }

    /**
     * 轻量 HTML fragment 校验 · 对齐 CC AskUserQuestionTool.tsx:250-265 validateHtmlPreview。
     *
     * <p>非解析器（HTML5 parser 容错接收一切）；检查模型意图（是否产出 HTML）与明确禁止项：
     * <ol>
     *   <li>{@code <html>/<body>/<!DOCTYPE>} → 必须是 fragment 非完整文档；</li>
     *   <li>{@code <script>/<style>} → 禁可执行/样式标签（SDK 通常 innerHTML 设置，防跑代码/改宿主页样式）；</li>
     *   <li>无任何 {@code <[a-z]...>} 标签 → 必须含 HTML。</li>
     * </ol>
     *
     * @param preview option preview 文本（可为 null，CC undefined → null）
     * @return 错误消息；通过 → null
     */
    static String validateHtmlPreview(String preview) {
        if (preview == null) {
            return null;
        }
        // CC :252 /<\s*(html|body|!doctype)\b/i
        if (java.util.regex.Pattern.compile("<\\s*(html|body|!doctype)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(preview).find()) {
            return "preview must be an HTML fragment, not a full document (no <html>, <body>, or <!DOCTYPE>)";
        }
        // CC :258 /<\s*(script|style)\b/i
        if (java.util.regex.Pattern.compile("<\\s*(script|style)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(preview).find()) {
            return "preview must not contain <script> or <style> tags. Use inline styles via the style attribute if needed.";
        }
        // CC :261 !/<[a-z][^>]*>/i
        if (!java.util.regex.Pattern.compile("<[a-z][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(preview).find()) {
            return "preview must contain HTML (previewFormat is set to \"html\"). Wrap content in a tag like <div> or <pre>.";
        }
        return null;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // CC AskUserQuestionTool.tsx:18-30 questions schema
        // questions: array 1-4 个，每个含 question/header/options(2-4)/multiSelect
        ObjectNode questions = props.putObject("questions");
        questions.put("type", "array");
        questions.put("minItems", 1);
        questions.put("maxItems", 4);
        questions.put("description", "Questions to ask the user (1-4 questions)");

        ObjectNode qItems = questions.putObject("items");
        qItems.put("type", "object");
        ObjectNode qProps = qItems.putObject("properties");
        // question: 必填（CC z.object question: z.string()）
        ObjectNode question = qProps.putObject("question");
        question.put("type", "string");
        question.put("description", "The complete question to ask the user. Should be clear and specific.");
        // header: 必填，1-12 chars 标签（CC z.object header: z.string()）
        ObjectNode header = qProps.putObject("header");
        header.put("type", "string");
        header.put("description", "Very short label displayed as a chip/tag (max 12 chars).");
        // options: 必填 array 2-4 个（CC z.array(questionOptionSchema).min(2).max(4)）
        ObjectNode options = qProps.putObject("options");
        options.put("type", "array");
        options.put("minItems", 2);
        options.put("maxItems", 4);
        options.put("description", "The available choices for this question. Must have 2-4 options.");
        ObjectNode optItems = options.putObject("items");
        optItems.put("type", "object");
        ObjectNode optProps = optItems.putObject("properties");
        // label: 必填（CC questionOptionSchema label: z.string()）
        ObjectNode label = optProps.putObject("label");
        label.put("type", "string");
        label.put("description", "The display label for this option (1-5 words).");
        // description: 必填（CC questionOptionSchema description: z.string()）
        ObjectNode desc = optProps.putObject("description");
        desc.put("type", "string");
        desc.put("description", "Explanation of what this option means.");
        // CC AskUserQuestionTool.tsx:17 — preview 字段（选项预览，可选）
        ObjectNode preview = optProps.putObject("preview");
        preview.put("type", "string");
        preview.put("description",
            "Optional preview content rendered when this option is focused. " +
            "Use for mockups, code snippets, or visual comparisons.");
        optItems.putArray("required").add("label").add("description");
        // multiSelect: 可选布尔（默认 false）· IMP-G2 ⊕-13：CC 字段名 multiSelect（camelCase，
        // AskUserQuestionTool.tsx:23），旧 multi_select（snake_case）删除。
        ObjectNode multiSelect = qProps.putObject("multiSelect");
        multiSelect.put("type", "boolean");
        multiSelect.put("default", false);
        multiSelect.put("description",
            "Set to true to allow the user to select multiple options instead of just one.");
        // CC z.object({question, header, options, multiSelect: z.boolean().default(false)})：
        // question/header/options 必填，multiSelect 带默认非必填。
        qItems.putArray("required").add("question").add("header").add("options");

        // CC AskUserQuestionTool.tsx:55-61 commonFields — answers / annotations / metadata
        // 由权限/交互层注入 input（工具自身不生成 answers）。
        ObjectNode answers = props.putObject("answers");
        answers.put("type", "object");
        answers.putObject("additionalProperties").put("type", "string");
        answers.put("description", "User answers collected by the permission component");

        ObjectNode annotations = props.putObject("annotations");
        annotations.put("type", "object");
        ObjectNode annValue = annotations.putObject("additionalProperties");
        annValue.put("type", "object");
        ObjectNode annProps = annValue.putObject("properties");
        ObjectNode annPreview = annProps.putObject("preview");
        annPreview.put("type", "string");
        annPreview.put("description", "The preview content of the selected option, if the question used previews.");
        ObjectNode annNotes = annProps.putObject("notes");
        annNotes.put("type", "string");
        annNotes.put("description", "Free-text notes the user added to their selection.");
        annotations.put("description", "Optional per-question annotations from the user (e.g., notes on preview selections). Keyed by question text.");

        ObjectNode metadata = props.putObject("metadata");
        metadata.put("type", "object");
        ObjectNode metaProps = metadata.putObject("properties");
        ObjectNode metaSource = metaProps.putObject("source");
        metaSource.put("type", "string");
        metaSource.put("description",
            "Optional identifier for the source of this question (e.g., \"remember\" for /remember command). Used for analytics tracking.");
        metadata.put("description", "Optional metadata for tracking and analytics purposes. Not displayed to user.");

        schema.putArray("required").add("questions");
        // CC AskUserQuestionTool.tsx:62-67 inputSchema = z.strictObject({...}) —— 顶层拒绝未知键
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 输出 schema · 对齐 CC AskUserQuestionTool.tsx:69-73 {@code {questions, answers, annotations}}。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode questions = props.putObject("questions");
        questions.put("type", "array");
        questions.put("description", "The questions that were asked");
        ObjectNode answers = props.putObject("answers");
        answers.put("type", "object");
        answers.put("description",
            "The answers provided by the user (question text -> answer string; multi-select answers are comma-separated)");
        ObjectNode annotations = props.putObject("annotations");
        annotations.put("type", "object");
        annotations.put("description", "Optional per-question annotations from the user (e.g., notes on preview selections).");
        return schema;
    }

    /**
     * checkPermissions · 对齐 CC AskUserQuestionTool.tsx:182-188 behavior='ask'（弹窗提问）。
     * requiresUserInteraction=true 保证 bypass 模式也必须问用户。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[AskUserQuestionTool] checkPermissions 返回 Ask('Answer questions?')（CC AskUserQuestionTool.tsx:182-188）");
        }
        return new PermissionResult.Ask(
            "Answer questions?",
            new PermissionDecisionReason.Other("AskUserQuestion checkPermissions ask"),
            List.of(), null, input, null, false, null, List.of());
    }

    /** requiresUserInteraction · 对齐 CC AskUserQuestionTool.tsx:155-157（强制用户交互，bypass-immune）。 */
    @Override
    public boolean requiresUserInteraction() {
        return true;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * execute · 对齐 CC AskUserQuestionTool.tsx:209-223 {@code call({questions, answers = {}, annotations})}。
     *
     * <p>answers 由权限/交互层注入 input，工具自身不生成答案。questions 缺失/空数组 → fail-loud
     * 明确失败（对齐 CC inputSchema required 仅 questions，:62-67）；answers 缺失或非法 → 对齐 CC
     * call 缺省 {@code answers = {}}（AskUserQuestionTool.tsx:209-213）静默回退空 answers
     * （不伪造答案，不 fail-loud），返回 CC outputSchema 形状 {questions, answers, annotations}。
     */
    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        if (input == null || !input.has("questions")) {
            return ToolResult.error(call.id(), "缺少必填输入: questions");
        }
        JsonNode questions = input.get("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            return ToolResult.error(call.id(), "questions 必须是非空数组");
        }

        JsonNode answers = input.get("answers");
        if (answers == null || !answers.isObject()) {
            // CC call answers={} 缺省（AskUserQuestionTool.tsx:209-213）：answers 缺失/非法
            // 静默回退空 answers，不 fail-loud（CC 无 error 分支，恒返回 data:{questions, answers:{}}）。
            answers = JsonNodeFactory.instance.objectNode();
            if (log.isDebugEnabled()) {
                log.debug("[AskUserQuestionTool] answers 缺失或非法 → 对齐 CC call answers={} 静默回退空 answers（不 fail-loud）");
            }
        }
        JsonNode annotations = input.get("annotations");

        // CC call :214-221 — 输出 {questions, answers, ...(annotations && {annotations})}
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("questions", questions);
        structuredOutput.put("answers", answers);
        if (annotations != null && annotations.isObject()) {
            structuredOutput.put("annotations", annotations);
        }

        String summary = buildAnswersContent(answers, annotations);
        log.info("[AskUserQuestionTool] 返回 {} 个问题的用户答案（CC AskUserQuestionTool.tsx:209-223 回传 questions + answers）",
            answers.size());
        return ToolResult.successWithStructuredOutput(call.id(), summary, structuredOutput);
    }

    /**
     * mapToToolResultBlockParam · 对齐 CC AskUserQuestionTool.tsx:224-244。
     * 从 structuredOutput 读 answers + annotations，拼 "User has answered your questions: ..." 文本。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        // 对齐 RemoteTriggerTool.ts:314-324 惯例：isError / 非 ToolResult → 返回 null，
        // 让 toolResultPayloadText 回退默认渲染器输出 error message（fail-loud 路径正确显错）。
        if (!(result instanceof ToolResult<?> tr) || isError) {
            if (log.isDebugEnabled()) {
                log.debug("[AskUserQuestionTool] mapToToolResultBlockParam 跳过: isError 或非 ToolResult（fail-loud 路径回退默认渲染器）");
            }
            return null;
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        JsonNode answers = so.get("answers") instanceof JsonNode n ? n : null;
        JsonNode annotations = so.get("annotations") instanceof JsonNode n ? n : null;
        String content = buildAnswersContent(answers, annotations);

        if (log.isDebugEnabled()) {
            log.debug("[AskUserQuestionTool] mapToToolResultBlockParam 生成 tool_result content长度={}（CC AskUserQuestionTool.tsx:224-244）",
                content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /**
     * 拼装 tool_result content · 对齐 CC AskUserQuestionTool.tsx:228-243。
     *
     * <p>每条 answer: {@code "question"="answer"}，若有 annotation.preview 追加
     * {@code " selected preview:\n<preview>"}，annotation.notes 追加 {@code " user notes: <notes>"}；
     * 多条以 {@code ", "} 连接，最终包裹 {@code "User has answered your questions: ..."}。
     */
    static String buildAnswersContent(JsonNode answers, JsonNode annotations) {
        StringBuilder answersText = new StringBuilder();
        if (answers != null && answers.isObject()) {
            boolean first = true;
            var fields = answers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String questionText = e.getKey();
                String answer = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
                StringBuilder parts = new StringBuilder();
                parts.append('"').append(questionText).append("\"=\"").append(answer).append('"');

                JsonNode annotation = annotations != null && annotations.isObject()
                    ? annotations.get(questionText) : null;
                if (annotation != null && annotation.isObject()) {
                    JsonNode preview = annotation.get("preview");
                    if (preview != null && preview.isTextual() && !preview.asText().isBlank()) {
                        parts.append(" selected preview:\n").append(preview.asText());
                    }
                    JsonNode notes = annotation.get("notes");
                    if (notes != null && notes.isTextual() && !notes.asText().isBlank()) {
                        parts.append(" user notes: ").append(notes.asText());
                    }
                }

                if (!first) {
                    answersText.append(", ");
                }
                answersText.append(parts);
                first = false;
            }
        }
        return "User has answered your questions: " + answersText
            + ". You can now continue with the user's answers in mind.";
    }
}
