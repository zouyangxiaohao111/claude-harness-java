package com.nexusai.application.agent.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /security-review command prompt · 对齐 CC commands/security-review.ts.
 *
 * <p>CC source: commands/security-review.ts (243 LOC).
 * SECURITY_REVIEW_MARKDOWN (long structured prompt) + parseFrontmatter +
 * parseSlashCommandToolsFromFrontmatter + executeShellCommandsInPrompt +
 * createMovedToPluginCommand wrapper.
 *
 * <p>L1 语义: 完整的 security review 提示 (git status/diff/log/show + 5 大类漏洞 +
 *      输出格式 + confidence scoring + 17 条 hard exclusions). 9 allowed-tools (git diff/status/log/show).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: prompt 文本含关键 5 大类 (Input Validation/Auth/Crypto/Injection/Data);
 *       9 allowedTools (git + Read/Glob/Grep/LS/Task);
 *       3 phase (Context/Analysis/Output).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 拉 git status/diff/log/show → review 找 HIGH/MED 漏洞 →
 *       markdown 输出 (file:line + severity + category + description + exploit + recommendation).</li>
 *   <li><b>A3</b>: 状态: IDLE / EXECUTING (bash) / WRITING (markdown output).</li>
 *   <li><b>A4</b>: prompt 含 17 条 hard exclusions (DoS, secrets on disk, rate limiting, memory safety, ipython, 等);
 *       confidence score 0-10 阈值 < 7 不报;
 *       severity 只报 HIGH/MEDIUM.</li>
 *   <li><b>A5</b>: 真实场景 — 安全工程师在 feature branch 提交 → /security-review →
 *       5 类 vulnerability 检查 → 报告 HIGH/MED 漏洞.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC source has 3 dependencies (parseFrontmatter/markdownConfigLoader/promptShellExecution
 *                    + createMovedToPluginCommand) — here we expose the **prompt text** and
 *                    **tools list** as pure data; actual command lifecycle delegated to
 *                    existing plugin/migration infrastructure.
 */
public final class SecurityReviewPrompt {

    private static final Logger log = LoggerFactory.getLogger(SecurityReviewPrompt.class);

    public static final String PROMPT_MARKDOWN = "---\n"
        + "allowed-tools: Bash(git diff:*), Bash(git status:*), Bash(git log:*), Bash(git show:*), "
        + "Bash(git remote show:*), Read, Glob, Grep, LS, Task\n"
        + "description: Complete a security review of the pending changes on the current branch\n"
        + "---\n\n"
        + "You are a senior security engineer conducting a focused security review of the changes on this branch.\n\n"
        + "GIT STATUS:\n\n"
        + "```\n"
        + "! git status\n"
        + "```\n\n"
        + "FILES MODIFIED:\n\n"
        + "```\n"
        + "! git diff --name-only origin/HEAD...\n"
        + "```\n\n"
        + "COMMITS:\n\n"
        + "```\n"
        + "! git log --no-decorate origin/HEAD...\n"
        + "```\n\n"
        + "DIFF CONTENT:\n\n"
        + "```\n"
        + "! git diff origin/HEAD...\n"
        + "```\n\n"
        + "Review the complete diff above. This contains all code changes in the PR.\n\n"
        + "OBJECTIVE:\n"
        + "Perform a security-focused code review to identify HIGH-CONFIDENCE security vulnerabilities that could have real exploitation potential. This is not a general code review - focus ONLY on security implications newly added by this PR. Do not comment on existing security concerns.\n\n"
        + "CRITICAL INSTRUCTIONS:\n"
        + "1. MINIMIZE FALSE POSITIVES: Only flag issues where you're >80% confident of actual exploitability\n"
        + "2. AVOID NOISE: Skip theoretical issues, style concerns, or low-impact findings\n"
        + "3. FOCUS ON IMPACT: Prioritize vulnerabilities that could lead to unauthorized access, data breaches, or system compromise\n"
        + "4. EXCLUSIONS: Do NOT report the following issue types:\n"
        + "   - Denial of Service (DOS) vulnerabilities, even if they allow service disruption\n"
        + "   - Secrets or sensitive data stored on disk (these are handled by other processes)\n"
        + "   - Rate limiting or resource exhaustion issues\n\n"
        + "SECURITY CATEGORIES TO EXAMINE:\n\n"
        + "**Input Validation Vulnerabilities:**\n"
        + "- SQL injection via unsanitized user input\n"
        + "- Command injection in system calls or subprocesses\n"
        + "- XXE injection in XML parsing\n"
        + "- Template injection in templating engines\n"
        + "- NoSQL injection in database queries\n"
        + "- Path traversal in file operations\n\n"
        + "**Authentication & Authorization Issues:**\n"
        + "- Authentication bypass logic\n"
        + "- Privilege escalation paths\n"
        + "- Session management flaws\n"
        + "- JWT token vulnerabilities\n"
        + "- Authorization logic bypasses\n\n"
        + "**Crypto & Secrets Management:**\n"
        + "- Hardcoded API keys, passwords, or tokens\n"
        + "- Weak cryptographic algorithms or implementations\n"
        + "- Improper key storage or management\n"
        + "- Cryptographic randomness issues\n"
        + "- Certificate validation bypasses\n\n"
        + "**Injection & Code Execution:**\n"
        + "- Remote code execution via deserialization\n"
        + "- Pickle injection in Python\n"
        + "- YAML deserialization vulnerabilities\n"
        + "- Eval injection in dynamic code execution\n"
        + "- XSS vulnerabilities in web applications (reflected, stored, DOM-based)\n\n"
        + "**Data Exposure:**\n"
        + "- Sensitive data logging or storage\n"
        + "- PII handling violations\n"
        + "- API endpoint data leakage\n"
        + "- Debug information exposure\n\n"
        + "Additional notes:\n"
        + "- Even if something is only exploitable from the local network, it can still be a HIGH severity issue\n\n"
        + "ANALYSIS METHODOLOGY:\n\n"
        + "Phase 1 - Repository Context Research (Use file search tools):\n"
        + "- Identify existing security frameworks and libraries in use\n"
        + "- Look for established secure coding patterns in the codebase\n"
        + "- Examine existing sanitization and validation patterns\n"
        + "- Understand the project's security model and threat model\n\n"
        + "Phase 2 - Comparative Analysis:\n"
        + "- Compare new code changes against existing security patterns\n"
        + "- Identify deviations from established secure practices\n"
        + "- Look for inconsistent security implementations\n"
        + "- Flag code that introduces new attack surfaces\n\n"
        + "Phase 3 - Vulnerability Assessment:\n"
        + "- Examine each modified file for security implications\n"
        + "- Trace data flow from user inputs to sensitive operations\n"
        + "- Look for privilege boundaries being crossed unsafely\n"
        + "- Identify injection points and unsafe deserialization\n\n"
        + "REQUIRED OUTPUT FORMAT:\n\n"
        + "You MUST output your findings in markdown. The markdown output should contain the file, line number, severity, category (e.g. `sql_injection` or `xss`), description, exploit scenario, and fix recommendation.\n\n"
        + "For example:\n\n"
        + "# Vuln 1: XSS: `foo.py:42`\n\n"
        + "* Severity: High\n"
        + "* Description: User input from `username` parameter is directly interpolated into HTML without escaping, allowing reflected XSS attacks\n"
        + "* Exploit Scenario: Attacker crafts URL like /bar?q=<script>alert(document.cookie)</script> to execute JavaScript in victim's browser, enabling session hijacking or data theft\n"
        + "* Recommendation: Use Flask's escape() function or Jinja2 templates with auto-escaping enabled for all user inputs rendered in HTML\n\n"
        + "SEVERITY GUIDELINES:\n"
        + "- **HIGH**: Directly exploitable vulnerabilities leading to RCE, data breach, or authentication bypass\n"
        + "- **MEDIUM**: Vulnerabilities requiring specific conditions but with significant impact\n"
        + "- **LOW**: Defense-in-depth issues or lower-impact vulnerabilities\n\n"
        + "CONFIDENCE SCORING:\n"
        + "- 0.9-1.0: Certain exploit path identified, tested if possible\n"
        + "- 0.8-0.9: Clear vulnerability pattern with known exploitation methods\n"
        + "- 0.7-0.8: Suspicious pattern requiring specific conditions to exploit\n"
        + "- Below 0.7: Don't report (too speculative)\n\n"
        + "FINAL REMINDER:\n"
        + "Focus on HIGH and MED findings only. Better to miss some theoretical issues than flood the report with false positives. Each finding should be something a security engineer would confidently raise in a PR review.\n\n"
        + "FALSE POSITIVE FILTERING:\n\n"
        + "> You do not need to run commands to reproduce the vulnerability, just read the code to determine if it is a real vulnerability. Do not use the bash tool or write to any files.\n"
        + ">\n"
        + "> HARD EXCLUSIONS - Automatically exclude findings matching these patterns:\n"
        + "> 1. Denial of Service (DOS) vulnerabilities or resource exhaustion attacks.\n"
        + "> 2. Secrets or credentials stored on disk if they are otherwise secured.\n"
        + "> 3. Rate limiting concerns or service overload scenarios.\n"
        + "> 4. Memory consumption or CPU exhaustion issues.\n"
        + "> 5. Lack of input validation on non-security-critical fields without proven security impact.\n"
        + "> 6. Input sanitization concerns for GitHub Action workflows unless they are clearly triggerable via untrusted input.\n"
        + "> 7. A lack of hardening measures. Code is not expected to implement all security best practices, only flag concrete vulnerabilities.\n"
        + "> 8. Race conditions or timing attacks that are theoretical rather than practical issues. Only report a race condition if it is concretely problematic.\n"
        + "> 9. Vulnerabilities related to outdated third-party libraries. These are managed separately and should not be reported here.\n"
        + "> 10. Memory safety issues such as buffer overflows or use-after-free-vulnerabilities are impossible in rust. Do not report memory safety issues in rust or any other memory safe languages.\n"
        + "> 11. Files that are only unit tests or only used as part of running tests.\n"
        + "> 12. Log spoofing concerns. Outputting un-sanitized user input to logs is not a vulnerability.\n"
        + "> 13. SSRF vulnerabilities that only control the path. SSRF is only a concern if it can control the host or protocol.\n"
        + "> 14. Including user-controlled content in AI system prompts is not a vulnerability.\n"
        + "> 15. Regex injection. Injecting untrusted content into a regex is not a vulnerability.\n"
        + "> 16. Regex DOS concerns.\n"
        + "> 16. Insecure documentation. Do not report any findings in documentation files such as markdown files.\n"
        + "> 17. A lack of audit logs is not a vulnerability.\n"
        + ">\n"
        + "> PRECEDENTS -\n"
        + "> 1. Logging high value secrets in plaintext is a vulnerability. Logging URLs is assumed to be safe.\n"
        + "> 2. UUIDs can be assumed to be unguessable and do not need to be validated.\n"
        + "> 3. Environment variables and CLI flags are trusted values. Attackers are generally not able to modify them in a secure environment. Any attack that relies on controlling an environment variable is invalid.\n"
        + "> 4. Resource management issues such as memory or file descriptor leaks are not valid.\n"
        + "> 5. Subtle or low impact web vulnerabilities such as tabnabbing, XS-Leaks, prototype pollution, and open redirects should not be reported unless they are extremely high confidence.\n"
        + "> 6. React and Angular are generally secure against XSS. These frameworks do not need to sanitize or escape user input unless it is using dangerouslySetInnerHTML, bypassSecurityTrustHtml, or similar methods. Do not report XSS vulnerabilities in React or Angular components or tsx files unless they are using unsafe methods.\n"
        + "> 7. Most vulnerabilities in github action workflows are not exploitable in practice. Before validating a github action workflow vulnerability ensure it is concrete and has a very specific attack path for untrusted input.\n"
        + "> 8. A lack of permission checking or authentication in client-side JS/TS code is not a vulnerability. Client-side code is not trusted and does not need to implement these checks, they are handled on the server-side. The same applies to all flows that send untrusted data to the backend, the backend is responsible for validating and sanitizing all inputs.\n"
        + "> 9. Only include MEDIUM findings if they are obvious and concrete issues.\n"
        + "> 10. Most vulnerabilities in ipython notebooks (*.ipynb files) are not exploitable in practice. Before validating a notebook vulnerability ensure it is concrete and has a very specific attack path where untrusted input can trigger the vulnerability.\n"
        + "> 11. Logging non-PII data is not a vulnerability even if the data may be sensitive. Only report logging vulnerabilities if they expose sensitive information such as secrets, passwords, or personally identifiable information (PII).\n"
        + "> 12. Command injection vulnerabilities in shell scripts are generally not exploitable in practice since shell scripts generally do not run with untrusted input. Only report command injection vulnerabilities in shell scripts if they are concrete and have a very specific attack path for untrusted input.\n"
        + ">\n"
        + "> SIGNAL QUALITY CRITERIA - For remaining findings, assess:\n"
        + "> 1. Is there a concrete, exploitable vulnerability with a clear attack path?\n"
        + "> 2. Does this represent a real security risk vs theoretical best practice?\n"
        + "> 3. Are there specific code locations and reproduction steps?\n"
        + "> 4. Would this finding be actionable for a security team?\n"
        + ">\n"
        + "> For each finding, assign a confidence score from 1-10:\n"
        + "> 1-3: Low confidence, likely false positive or noise\n"
        + "> 4-6: Medium confidence, needs investigation\n"
        + "> 7-10: High confidence, likely true vulnerability\n\n"
        + "START ANALYSIS:\n\n"
        + "Begin your analysis now. Do this in 3 steps:\n"
        + "1. Use a sub-task to identify vulnerabilities. Use the repository exploration tools to understand the codebase context, then analyze the PR changes for security implications. In the prompt for this sub-task, include all of the above.\n"
        + "2. Then for each vulnerability identified by the above sub-task, create a new sub-task to filter out false-positives. Launch these sub-tasks as parallel sub-tasks. In the prompt for these sub-tasks, include everything in the \"FALSE POSITIVE FILTERING\" instructions.\n"
        + "3. Filter out any vulnerabilities where the sub-task reported a confidence less than 8.\n\n"
        + "Your final reply must contain the markdown report and nothing else.";

    public static final java.util.List<String> ALLOWED_TOOLS = List.of(
        "Bash(git diff:*)", "Bash(git status:*)", "Bash(git log:*)", "Bash(git show:*)",
        "Bash(git remote show:*)", "Read", "Glob", "Grep", "LS", "Task"
    );

    public String getPrompt() { return PROMPT_MARKDOWN; }
    public List<String> getAllowedTools() { return ALLOWED_TOOLS; }
}
