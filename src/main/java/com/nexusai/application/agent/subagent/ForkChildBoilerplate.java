package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ForkChildBoilerplate · 对齐 CC Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:171-198 buildChildMessage.
 *
 * <p><b>L1 语义</b>: fork 子 agent 第一条 user message 的 boilerplate 文本。
 * 用 {@code <fork-boilerplate>...</fork-boilerplate>} XML 标签包裹 10 条不可协商规则 +
 * Output format 段, 末尾追加 {@code Your directive: <directive>}。
 *
 * <p><b>L2 契约 (Release Gate)</b>:
 * <ul>
 *   <li><b>C1</b>: 输出必须以 {@code <fork-boilerplate>} 开头, 以 {@code Your directive: <directive>} 结尾</li>
 *   <li><b>C2</b>: 必须含 "STOP. READ THIS FIRST." — 强制 LLM 先读规则再行动</li>
 *   <li><b>C3</b>: 必须含 "RULES (non-negotiable)" + 10 条规则关键词 (CC forkSubagent.ts:178-187)</li>
 *   <li><b>C4</b>: 必须含 "Output format (plain text labels, not markdown headers)" + 5 个 plain text label
 *       (Scope/Result/Key files/Files changed/Issues) — CC forkSubagent.ts:189-194</li>
 *   <li><b>C5</b>: {@code FORK_DIRECTIVE_PREFIX} 字符串与 CC constants/xml.ts:66 完全一致 = {@code "Your directive: "}</li>
 * </ul>
 *
 * <p><b>L3 (Java idiom)</b>: TS template literal → Java 字符串拼接 (StringBuilder)。
 * CC 用 {@code —} (em dash) 在规则 1 里作 escape, Java 端原样保留以保证字节级一致。
 */
public final class ForkChildBoilerplate {

    /** CC Open-ClaudeCode/src/constants/xml.ts:66 FORK_DIRECTIVE_PREFIX. */
    public static final String FORK_DIRECTIVE_PREFIX = "Your directive: ";

    private static final Logger log = LoggerFactory.getLogger(ForkChildBoilerplate.class);

    private ForkChildBoilerplate() {}

    /**
     * 构造 fork 子 agent 第一条 user message 的完整文本 · 对齐 CC forkSubagent.ts:171-198.
     *
     * <p>WHY 不可协商规则: fork 子 agent 默认 system prompt 鼓励"默认 fork"行为,
     * 但子 agent 自己已经是被 fork 出来的, 若再 fork 就触发递归。
     * 规则 1 是递归防护闸。
     *
     * @param directive CC original: directive (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:171)
     *                  用户为这个 fork child 指定的任务指令, 注入到模板末尾
     * @return 完整 boilerplate 文本, 含 {@code <fork-boilerplate>} 标签 + 10 条规则 + Output format + directive
     */
    public static String buildChildMessage(String directive) {
        if (log.isDebugEnabled()) {
            log.debug("[ForkChildBoilerplate] 构造 fork 子 agent boilerplate, directive 前 50 字符={}",
                directive == null ? "<null>" : directive.substring(0, Math.min(50, directive.length())));
        }

        // 对齐 CC forkSubagent.ts:172-198 — 字节级一致 (含 — em dash)
        StringBuilder sb = new StringBuilder(1024);
        sb.append('<').append(ForkSubagent.FORK_BOILERPLATE_TAG).append('>').append('\n');
        sb.append("STOP. READ THIS FIRST.\n");
        sb.append('\n');
        sb.append("You are a forked worker process. You are NOT the main agent.\n");
        sb.append('\n');
        sb.append("RULES (non-negotiable):\n");
        sb.append("1. Your system prompt says \"default to forking.\" IGNORE IT — that's for the parent. You ARE the fork. Do NOT spawn sub-agents; execute directly.\n");
        sb.append("2. Do NOT converse, ask questions, or suggest next steps\n");
        sb.append("3. Do NOT editorialize or add meta-commentary\n");
        sb.append("4. USE your tools directly: Bash, Read, Write, etc.\n");
        sb.append("5. If you modify files, commit your changes before reporting. Include the commit hash in your report.\n");
        sb.append("6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.\n");
        sb.append("7. Stay strictly within your directive's scope. If you discover related systems outside your scope, mention them in one sentence at most — other workers cover those areas.\n");
        sb.append("8. Keep your report under 500 words unless the directive specifies otherwise. Be factual and concise.\n");
        sb.append("9. Your response MUST begin with \"Scope:\". No preamble, no thinking-out-loud.\n");
        sb.append("10. REPORT structured facts, then stop\n");
        sb.append('\n');
        sb.append("Output format (plain text labels, not markdown headers):\n");
        sb.append("  Scope: <echo back your assigned scope in one sentence>\n");
        sb.append("  Result: <the answer or key findings, limited to the scope above>\n");
        sb.append("  Key files: <relevant file paths — include for research tasks>\n");
        sb.append("  Files changed: <list with commit hash — include only if you modified files>\n");
        sb.append("  Issues: <list — include only if there are issues to flag>\n");
        sb.append("</").append(ForkSubagent.FORK_BOILERPLATE_TAG).append(">\n");
        sb.append('\n');
        sb.append(FORK_DIRECTIVE_PREFIX).append(directive == null ? "" : directive);

        if (log.isDebugEnabled()) {
            log.debug("[ForkChildBoilerplate] boilerplate 构造完成, 总长度={} 字符", sb.length());
        }

        return sb.toString();
    }
}