package com.nexusai.application.agent.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 压缩提示词构建器 · 对齐 CC prompt.ts getCompactPrompt() + getPartialCompactPrompt()
 *
 * <h2>CC 对齐</h2>
 * <p>对齐 CC prompt.ts:
 * <ul>
 *   <li>{@code getCompactPrompt(customInstructions)} — 完整对话压缩提示词（prompt.ts:293-303）</li>
 *   <li>{@code getPartialCompactPrompt(customInstructions, direction)} — 部分压缩提示词（prompt.ts:274-291）</li>
 *   <li>{@code NO_TOOLS_PREAMBLE} — 严禁调用工具的警告（prompt.ts:19-26）</li>
 *   <li>{@code NO_TOOLS_TRAILER} — 再次提醒不要使用工具（prompt.ts:269-272）</li>
 * </ul>
 *
 * <h2>三种变体</h2>
 * <ul>
 *   <li><b>BASE</b> — 总结<b>整个对话</b>（对齐 getCompactPrompt → BASE_COMPACT_PROMPT :61-143，
 *       含 section-9 尾句、完整 example 块、additional-instructions 尾块）</li>
 *   <li><b>PARTIAL "from"</b> — 总结<b>最近消息</b>（对齐 PARTIAL_COMPACT_PROMPT :145-204，
 *       使用 {@code DETAILED_ANALYSIS_INSTRUCTION_PARTIAL}）</li>
 *   <li><b>PARTIAL "up_to"</b> — 总结<b>较早消息</b>（对齐 PARTIAL_COMPACT_UP_TO_PROMPT :206-267，
 *       使用 {@code DETAILED_ANALYSIS_INSTRUCTION_BASE}）</li>
 * </ul>
 *
 * <h2>输出格式要求</h2>
 * <p>所有变体都要求 LLM 输出：
 * <pre>
 * &lt;analysis&gt;
 * ...
 * &lt;/analysis&gt;
 * &lt;summary&gt;
 * 1. Primary Request and Intent: ...
 * 2. Key Technical Concepts: ...
 * ...
 * &lt;/summary&gt;
 * </pre>
 *
 * <h2>字节级对齐（IMP2-16，△-19）</h2>
 * <p>模板常量由脚本从 CC prompt.ts 模板字面量提取发射（CC 唯一真源）：无行尾空白的
 * 常量用文本块，含行尾空白或不以换行结尾的用转义字符串拼接（javac 文本块会剥离行尾
 * 空白）。由 {@code PromptTextCcContractTest}（对照同法生成的 {@code CcPromptFixture}）
 * 逐字节断言；重新生成参照 {@code CcPromptFixture} 头部说明。
 */
public class CompactPrompt {

    private static final Logger log = LoggerFactory.getLogger(CompactPrompt.class);

    /** 压缩方向 */
    public enum Direction {
        /** 总结整段对话（全部消息） */
        BASE,
        /** 总结最近消息（"from" — 保留旧消息在后） */
        FROM,
        /** 总结较早消息（"up_to" — 保留新消息在后） */
        UP_TO
    }

    /** 严禁工具调用警告（对齐 CC prompt.ts:19-26 NO_TOOLS_PREAMBLE，逐字节） */
    private static final String NO_TOOLS_PREAMBLE =
        "CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.\n\n"
        + "- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.\n"
        + "- You already have all the context you need in the conversation above.\n"
        + "- Tool calls will be REJECTED and will waste your only turn — you will fail the task.\n"
        + "- Your entire response must be plain text: an <analysis> block followed by a <summary> block.\n\n";

    /** 尾部提醒（对齐 CC prompt.ts:269-272 NO_TOOLS_TRAILER，逐字节） */
    private static final String NO_TOOLS_TRAILER =
        "\n\nREMINDER: Do NOT call any tools. Respond with plain text only — "
        + "an <analysis> block followed by a <summary> block. "
        + "Tool calls will be rejected and you will fail the task.";

    /** 详细分析指导 BASE 变体（对齐 CC prompt.ts:32-44，逐字节） */
    private static final String DETAILED_ANALYSIS_INSTRUCTION_BASE =
        "Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:\n"
        + "\n"
        + "1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:\n"
        + "   - The user's explicit requests and intents\n"
        + "   - Your approach to addressing the user's requests\n"
        + "   - Key decisions, technical concepts and code patterns\n"
        + "   - Specific details like:\n"
        + "     - file names\n"
        + "     - full code snippets\n"
        + "     - function signatures\n"
        + "     - file edits\n"
        + "   - Errors that you ran into and how you fixed them\n"
        + "   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n"
        + "2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.";


    /** 详细分析指导 PARTIAL 变体（对齐 CC prompt.ts:46-59，逐字节；PARTIAL_FROM 专用，不得复用 BASE 变体） */
    private static final String DETAILED_ANALYSIS_INSTRUCTION_PARTIAL =
        "Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:\n"
        + "\n"
        + "1. Analyze the recent messages chronologically. For each section thoroughly identify:\n"
        + "   - The user's explicit requests and intents\n"
        + "   - Your approach to addressing the user's requests\n"
        + "   - Key decisions, technical concepts and code patterns\n"
        + "   - Specific details like:\n"
        + "     - file names\n"
        + "     - full code snippets\n"
        + "     - function signatures\n"
        + "     - file edits\n"
        + "   - Errors that you ran into and how you fixed them\n"
        + "   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n"
        + "2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.";


    /** BASE 压缩提示（对齐 CC prompt.ts:61-143 BASE_COMPACT_PROMPT，逐字节；含 section-9 尾句 + 完整 example 块 + additional-instructions 尾块；行尾空格经转义拼接保留） */
    private static final String BASE_COMPACT_PROMPT =
        "Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.\n"
        + "This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.\n"
        + "\n"
        + "Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:\n"
        + "\n"
        + "1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:\n"
        + "   - The user's explicit requests and intents\n"
        + "   - Your approach to addressing the user's requests\n"
        + "   - Key decisions, technical concepts and code patterns\n"
        + "   - Specific details like:\n"
        + "     - file names\n"
        + "     - full code snippets\n"
        + "     - function signatures\n"
        + "     - file edits\n"
        + "   - Errors that you ran into and how you fixed them\n"
        + "   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n"
        + "2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.\n"
        + "\n"
        + "Your summary should include the following sections:\n"
        + "\n"
        + "1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail\n"
        + "2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.\n"
        + "3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.\n"
        + "4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n"
        + "5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.\n"
        + "6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.\n"
        + "7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.\n"
        + "8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.\n"
        + "9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request. If your last task was concluded, then only list next steps if they are explicitly in line with the users request. Do not start on tangential requests or really old requests that were already completed without confirming with the user first.\n"
        + "                       If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no drift in task interpretation.\n"
        + "\n"
        + "Here's an example of how your output should be structured:\n"
        + "\n"
        + "<example>\n"
        + "<analysis>\n"
        + "[Your thought process, ensuring all points are covered thoroughly and accurately]\n"
        + "</analysis>\n"
        + "\n"
        + "<summary>\n"
        + "1. Primary Request and Intent:\n"
        + "   [Detailed description]\n"
        + "\n"
        + "2. Key Technical Concepts:\n"
        + "   - [Concept 1]\n"
        + "   - [Concept 2]\n"
        + "   - [...]\n"
        + "\n"
        + "3. Files and Code Sections:\n"
        + "   - [File Name 1]\n"
        + "      - [Summary of why this file is important]\n"
        + "      - [Summary of the changes made to this file, if any]\n"
        + "      - [Important Code Snippet]\n"
        + "   - [File Name 2]\n"
        + "      - [Important Code Snippet]\n"
        + "   - [...]\n"
        + "\n"
        + "4. Errors and fixes:\n"
        + "    - [Detailed description of error 1]:\n"
        + "      - [How you fixed the error]\n"
        + "      - [User feedback on the error if any]\n"
        + "    - [...]\n"
        + "\n"
        + "5. Problem Solving:\n"
        + "   [Description of solved problems and ongoing troubleshooting]\n"
        + "\n"
        + "6. All user messages: \n"
        + "    - [Detailed non tool use user message]\n"
        + "    - [...]\n"
        + "\n"
        + "7. Pending Tasks:\n"
        + "   - [Task 1]\n"
        + "   - [Task 2]\n"
        + "   - [...]\n"
        + "\n"
        + "8. Current Work:\n"
        + "   [Precise description of current work]\n"
        + "\n"
        + "9. Optional Next Step:\n"
        + "   [Optional Next step to take]\n"
        + "\n"
        + "</summary>\n"
        + "</example>\n"
        + "\n"
        + "Please provide your summary based on the conversation so far, following this structure and ensuring precision and thoroughness in your response. \n"
        + "\n"
        + "There may be additional summarization instructions provided in the included context. If so, remember to follow these instructions when creating the above summary. Examples of instructions include:\n"
        + "<example>\n"
        + "## Compact Instructions\n"
        + "When summarizing the conversation focus on typescript code changes and also remember the mistakes you made and how you fixed them.\n"
        + "</example>\n"
        + "\n"
        + "<example>\n"
        + "# Summary instructions\n"
        + "When you are using compact - please focus on test output and code changes. Include file reads verbatim.\n"
        + "</example>\n";


    /** PARTIAL "from" 压缩提示（对齐 CC prompt.ts:145-204 PARTIAL_COMPACT_PROMPT，逐字节；PARTIAL 分析指令 + section-9 直接引语尾句 + 完整 example 块 + RECENT 尾句） */
    private static final String PARTIAL_PROMPT_FROM = """
    Your task is to create a detailed summary of the RECENT portion of the conversation — the messages that follow earlier retained context. The earlier messages are being kept intact and do NOT need to be summarized. Focus your summary on what was discussed, learned, and accomplished in the recent messages only.
    
    Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:
    
    1. Analyze the recent messages chronologically. For each section thoroughly identify:
       - The user's explicit requests and intents
       - Your approach to addressing the user's requests
       - Key decisions, technical concepts and code patterns
       - Specific details like:
         - file names
         - full code snippets
         - function signatures
         - file edits
       - Errors that you ran into and how you fixed them
       - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
    2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.
    
    Your summary should include the following sections:
    
    1. Primary Request and Intent: Capture the user's explicit requests and intents from the recent messages
    2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed recently.
    3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Include full code snippets where applicable and include a summary of why this file read or edit is important.
    4. Errors and fixes: List errors encountered and how they were fixed.
    5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
    6. All user messages: List ALL user messages from the recent portion that are not tool results.
    7. Pending Tasks: Outline any pending tasks from the recent messages.
    8. Current Work: Describe precisely what was being worked on immediately before this summary request.
    9. Optional Next Step: List the next step related to the most recent work. Include direct quotes from the most recent conversation.
    
    Here's an example of how your output should be structured:
    
    <example>
    <analysis>
    [Your thought process, ensuring all points are covered thoroughly and accurately]
    </analysis>
    
    <summary>
    1. Primary Request and Intent:
       [Detailed description]
    
    2. Key Technical Concepts:
       - [Concept 1]
       - [Concept 2]
    
    3. Files and Code Sections:
       - [File Name 1]
          - [Summary of why this file is important]
          - [Important Code Snippet]
    
    4. Errors and fixes:
        - [Error description]:
          - [How you fixed it]
    
    5. Problem Solving:
       [Description]
    
    6. All user messages:
        - [Detailed non tool use user message]
    
    7. Pending Tasks:
       - [Task 1]
    
    8. Current Work:
       [Precise description of current work]
    
    9. Optional Next Step:
       [Optional Next step to take]
    
    </summary>
    </example>
    
    Please provide your summary based on the RECENT messages only (after the retained earlier context), following this structure and ensuring precision and thoroughness in your response.
    """;


    /** PARTIAL "up_to" 压缩提示（对齐 CC prompt.ts:206-267 PARTIAL_COMPACT_UP_TO_PROMPT，逐字节；BASE 分析指令 + 完整 example 块 + 尾句） */
    private static final String PARTIAL_PROMPT_UP_TO = """
    Your task is to create a detailed summary of this conversation. This summary will be placed at the start of a continuing session; newer messages that build on this context will follow after your summary (you do not see them here). Summarize thoroughly so that someone reading only your summary and then the newer messages can fully understand what happened and continue the work.
    
    Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:
    
    1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
       - The user's explicit requests and intents
       - Your approach to addressing the user's requests
       - Key decisions, technical concepts and code patterns
       - Specific details like:
         - file names
         - full code snippets
         - function signatures
         - file edits
       - Errors that you ran into and how you fixed them
       - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
    2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.
    
    Your summary should include the following sections:
    
    1. Primary Request and Intent: Capture the user's explicit requests and intents in detail
    2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed.
    3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Include full code snippets where applicable and include a summary of why this file read or edit is important.
    4. Errors and fixes: List errors encountered and how they were fixed.
    5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
    6. All user messages: List ALL user messages that are not tool results.
    7. Pending Tasks: Outline any pending tasks.
    8. Work Completed: Describe what was accomplished by the end of this portion.
    9. Context for Continuing Work: Summarize any context, decisions, or state that would be needed to understand and continue the work in subsequent messages.
    
    Here's an example of how your output should be structured:
    
    <example>
    <analysis>
    [Your thought process, ensuring all points are covered thoroughly and accurately]
    </analysis>
    
    <summary>
    1. Primary Request and Intent:
       [Detailed description]
    
    2. Key Technical Concepts:
       - [Concept 1]
       - [Concept 2]
    
    3. Files and Code Sections:
       - [File Name 1]
          - [Summary of why this file is important]
          - [Important Code Snippet]
    
    4. Errors and fixes:
        - [Error description]:
          - [How you fixed it]
    
    5. Problem Solving:
       [Description]
    
    6. All user messages:
        - [Detailed non tool use user message]
    
    7. Pending Tasks:
       - [Task 1]
    
    8. Work Completed:
       [Description of what was accomplished]
    
    9. Context for Continuing Work:
       [Key context, decisions, or state needed to continue the work]
    
    </summary>
    </example>
    
    Please provide your summary following this structure, ensuring precision and thoroughness in your response.
    """;


    /**
     * 构建压缩提示词 · 对齐 CC prompt.ts:293 getCompactPrompt()
     *
     * @param customInstructions 用户自定义的压缩指令（可选，null 表示无）
     * @return 完整压缩提示词（含工具禁用警告）
     */
    public static String buildCompactPrompt(String customInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append(NO_TOOLS_PREAMBLE);
        sb.append(BASE_COMPACT_PROMPT);

        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }

        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * 构建部分压缩提示词 · 对齐 CC prompt.ts:274 getPartialCompactPrompt()
     *
     * <p>CC 方向语义: {@code direction === 'up_to' ? PARTIAL_COMPACT_UP_TO_PROMPT :
     * PARTIAL_COMPACT_PROMPT}（非 up_to 一律落 from，无 BASE 概念）→ Java
     * {@code Direction.BASE} 映射到 from 模板（对齐 CC 未知方向回落语义）。
     *
     * @param customInstructions 用户自定义的压缩指令（可选）
     * @param direction          压缩方向
     * @return 部分压缩提示词
     */
    public static String buildPartialCompactPrompt(String customInstructions, Direction direction) {
        StringBuilder sb = new StringBuilder();
        sb.append(NO_TOOLS_PREAMBLE);

        switch (direction) {
            case UP_TO:
                sb.append(PARTIAL_PROMPT_UP_TO);
                break;
            case BASE:
            case FROM:
            default:
                sb.append(PARTIAL_PROMPT_FROM);
                break;
        }

        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }

        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * 获取基础压缩提示词（无自定义指令）
     */
    public static String buildCompactPrompt() {
        return buildCompactPrompt(null);
    }

}
