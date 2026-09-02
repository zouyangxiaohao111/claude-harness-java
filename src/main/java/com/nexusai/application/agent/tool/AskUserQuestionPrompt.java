package com.nexusai.application.agent.tool;

import java.util.Map;

/**
 * AskUserQuestionTool 提示 / 常量 · 对齐 CC tools/AskUserQuestionTool/prompt.ts.
 *
 * <p>L1 语义: 把 Java 端 AskUserQuestionTool 写死在 description() 的提示文本集中到常量类,
 *            与 CC ASK_USER_QUESTION_TOOL_NAME / DESCRIPTION / PREVIEW_FEATURE_PROMPT / ASK_USER_QUESTION_TOOL_PROMPT 对齐.
 */
public final class AskUserQuestionPrompt {

    private AskUserQuestionPrompt() {}

    /** CC prompt.ts:3 — 工具名 'AskUserQuestion' */
    public static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";

    /** CC prompt.ts:5 — UI 渲染 chip 宽度 */
    public static final int ASK_USER_QUESTION_TOOL_CHIP_WIDTH = 12;

    /** CC prompt.ts:7-8 — 工具简短描述 */
    public static final String DESCRIPTION =
        "Asks the user multiple choice questions to gather information, " +
        "clarify ambiguity, understand preferences, make decisions or offer them choices.";

    /** CC prompt.ts:10-30 — preview feature prompt (markdown + html) */
    public static final Map<String, String> PREVIEW_FEATURE_PROMPT = Map.of(
        "markdown",
            "\nPreview feature:\n" +
            "Use the optional `preview` field on options when presenting concrete artifacts " +
            "that users need to visually compare:\n" +
            "- ASCII mockups of UI layouts or components\n" +
            "- Code snippets showing different implementations\n" +
            "- Diagram variations\n" +
            "- Configuration examples\n\n" +
            "Preview content is rendered as markdown in a monospace box. Multi-line text with " +
            "newlines is supported. When any option has a preview, the UI switches to a side-by-side " +
            "layout with a vertical option list on the left and preview on the right. Do not use " +
            "previews for simple preference questions where labels and descriptions suffice. Note: " +
            "previews are only supported for single-select questions (not multiSelect).\n",
        "html",
            "\nPreview feature:\n" +
            "Use the optional `preview` field on options when presenting concrete artifacts " +
            "that users need to visually compare:\n" +
            "- HTML mockups of UI layouts or components\n" +
            "- Formatted code snippets showing different implementations\n" +
            "- Visual comparisons or diagrams\n\n" +
            "Preview content must be a self-contained HTML fragment (no <html>/<body> wrapper, " +
            "no <script> or <style> tags — use inline style attributes instead). Do not use " +
            "previews for simple preference questions where labels and descriptions suffice. " +
            "Note: previews are only supported for single-select questions (not multiSelect).\n"
    );

    /** CC prompt.ts:32-44 — 工具完整 prompt (含 4 用法 + plan mode 提醒, 引用 ExitPlanModeTool 名称) */
    public static final String ASK_USER_QUESTION_TOOL_PROMPT =
        "Use this tool when you need to ask the user questions during execution. " +
        "This allows you to:\n" +
        "1. Gather user preferences or requirements\n" +
        "2. Clarify ambiguous instructions\n" +
        "3. Get decisions on implementation choices as you work\n" +
        "4. Offer choices to the user about what direction to take.\n\n" +
        "Usage notes:\n" +
        "- Users will always be able to select \"Other\" to provide custom text input\n" +
        "- Use multiSelect: true to allow multiple answers to be selected for a question\n" +
        "- If you recommend a specific option, make that the first option in the list and add " +
        "\"(Recommended)\" at the end of the label\n\n" +
        "Plan mode note: In plan mode, use this tool to clarify requirements or choose between " +
        "approaches BEFORE finalizing your plan. Do NOT use this tool to ask \"Is my plan ready?\" " +
        "or \"Should I proceed?\" - use ExitPlanMode for plan approval. IMPORTANT: Do not reference " +
        "\"the plan\" in your questions (e.g., \"Do you have feedback about the plan?\", " +
        "\"Does the plan look good?\") because the user cannot see the plan in the UI until you " +
        "call ExitPlanMode. If you need plan approval, use ExitPlanMode instead.\n";
}