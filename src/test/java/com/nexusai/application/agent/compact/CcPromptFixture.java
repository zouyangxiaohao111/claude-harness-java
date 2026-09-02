package com.nexusai.application.agent.compact;

/**
 * CC prompt.ts 真源提示词常量 · 生成自 Open-ClaudeCode/src/services/compact/prompt.ts @ 8e1437ff。
 *
 * <p><b>生成方式</b>: 脚本从 prompt.ts 模板字面量程序化提取并解析
 * ${DETAILED_ANALYSIS_INSTRUCTION_BASE}/${DETAILED_ANALYSIS_INSTRUCTION_PARTIAL} 插值。
 * 无行尾空白的常量（PREAMBLE/PARTIAL/UP_TO）用文本块（4 空格 incidental）；含行尾空白或
 * 不以 \n 结尾的常量（BASE/ANALYSIS/TRAILER）用转义字符串拼接——javac 文本块会剥离行尾
 * 空白，拼接保留字节。重新生成（CC 变更时）: 以同法重跑提取脚本覆盖本文件。
 *
 * <p><b>锚点</b>: 测试 PromptTextCcContractTest 断言 CompactPrompt 输出与本文件逐字节一致。
 */
final class CcPromptFixture {
    private CcPromptFixture() {}

    /** CC original: NO_TOOLS_PREAMBLE (prompt.ts:19-26) */
    static final String CC_NO_TOOLS_PREAMBLE = """
    CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
    
    - Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
    - You already have all the context you need in the conversation above.
    - Tool calls will be REJECTED and will waste your only turn — you will fail the task.
    - Your entire response must be plain text: an <analysis> block followed by a <summary> block.
    
    """;

    /** CC original: DETAILED_ANALYSIS_INSTRUCTION_BASE (prompt.ts:32-44) */
    static final String CC_DETAILED_ANALYSIS_INSTRUCTION_BASE =
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

    /** CC original: DETAILED_ANALYSIS_INSTRUCTION_PARTIAL (prompt.ts:46-59) */
    static final String CC_DETAILED_ANALYSIS_INSTRUCTION_PARTIAL =
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

    /** CC original: BASE_COMPACT_PROMPT (prompt.ts:61-143)，含 section 9 尾句 + 完整 example + additional-instructions 块；行尾空格经转义拼接保留（文本块会剥行尾空白） */
    static final String CC_BASE_COMPACT_PROMPT =
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

    /** CC original: PARTIAL_COMPACT_PROMPT (prompt.ts:145-204)，DETAILED_ANALYSIS_INSTRUCTION_PARTIAL 已解析 */
    static final String CC_PARTIAL_COMPACT_PROMPT = """
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

    /** CC original: PARTIAL_COMPACT_UP_TO_PROMPT (prompt.ts:206-267)，DETAILED_ANALYSIS_INSTRUCTION_BASE 已解析 */
    static final String CC_PARTIAL_COMPACT_UP_TO_PROMPT = """
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

    /** CC original: NO_TOOLS_TRAILER (prompt.ts:269-272) */
    static final String CC_NO_TOOLS_TRAILER =
        "\n"
        + "\n"
        + "REMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block followed by a <summary> block. Tool calls will be rejected and you will fail the task.";
}
