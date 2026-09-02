package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /stuck bundled skill 注册器 · 对齐 CC skills/bundled/stuck.ts registerStuckSkill.
 *
 * <p>L1 语义: ant-only skill — USER_TYPE != 'ant' 时不注册; 用户报告 stuck Claude Code session 后,
 *            扫描 ps 输出 + 检查子进程 + 写 #claude-code-feedback 报告.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `register(Consumer&lt;BundledSkillDefinition&gt;, boolean isAntUser) → boolean` 签名（P1-4 统一类型）</li>
 *   <li><b>A2 Golden Trace</b>: !isAntUser → false (不注册); isAntUser → true (注册 'stuck' skill)</li>
 *   <li><b>A3</b>: description "[ANT-ONLY]" 前缀 (CC 注释明示); userInvocable=true</li>
 *   <li><b>A4</b>: getPromptForCommand 有 args → [prompt + ## User-provided context 段]</li>
 *   <li><b>A5</b>: 真实 ant-only 场景 — args="PID 12345 pegged at 100%" → 拼 ## User-provided context 段</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC 'process.env.USER_TYPE' 全局读 → Java 参数 isAntUser 注入测试可控;
 *                    同 VerifySkillRegistrar 模板 (复用统一 Consumer&lt;BundledSkillDefinition&gt; 注册入口).
 */
public class StuckSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StuckSkillRegistrar.class);

    public static final String SKILL_NAME = "stuck";
    public static final String DESCRIPTION =
        "[ANT-ONLY] Investigate frozen/stuck/slow Claude Code sessions on this machine " +
        "and post a diagnostic report to #claude-code-feedback.";

    /**
     * CC stuck.ts STUCK_PROMPT 完整文案（:6-59）· two-message Slack 结构（thread_ts :49）+ pgrep -lP
     * + sample &lt;pid&gt; 3 + Notes 2 条。
     */
    public static final String STUCK_PROMPT = """
        # /stuck — diagnose frozen/slow Claude Code sessions

        The user thinks another Claude Code session on this machine is frozen, stuck, or very slow. Investigate and post a report to #claude-code-feedback.

        ## What to look for

        Scan for other Claude Code processes (excluding the current one — PID is in `process.pid` but for shell commands just exclude the PID you see running this prompt). Process names are typically `claude` (installed) or `cli` (native dev build).

        Signs of a stuck session:
        - **High CPU (≥90%) sustained** — likely an infinite loop. Sample twice, 1-2s apart, to confirm it's not a transient spike.
        - **Process state `D` (uninterruptible sleep)** — often an I/O hang. The `state` column in `ps` output; first character matters (ignore modifiers like `+`, `s`, `<`).
        - **Process state `T` (stopped)** — user probably hit Ctrl+Z by accident.
        - **Process state `Z` (zombie)** — parent isn't reaping.
        - **Very high RSS (≥4GB)** — possible memory leak making the session sluggish.
        - **Stuck child process** — a hung `git`, `node`, or shell subprocess can freeze the parent. Check `pgrep -lP <pid>` for each session.

        ## Investigation steps

        1. **List all Claude Code processes** (macOS/Linux):
           ```
           ps -axo pid=,pcpu=,rss=,etime=,state=,comm=,command= | grep -E '(claude|cli)' | grep -v grep
           ```
           Filter to rows where `comm` is `claude` or (`cli` AND the command path contains "claude").

        2. **For anything suspicious**, gather more context:
           - Child processes: `pgrep -lP <pid>`
           - If high CPU: sample again after 1-2s to confirm it's sustained
           - If a child looks hung (e.g., a git command), note its full command line with `ps -p <child_pid> -o command=`
           - Check the session's debug log if you can infer the session ID: `~/.nexusai/debug/<session-id>.txt` (debug 根 = 应用自有目录 ~/.{appName}/debug，appName 默认 nexusai；the last few hundred lines often show what it was doing before hanging)

        3. **Consider a stack dump** for a truly frozen process (advanced, optional):
           - macOS: `sample <pid> 3` gives a 3-second native stack sample
           - This is big — only grab it if the process is clearly hung and you want to know *why*

        ## Report

        **Only post to Slack if you actually found something stuck.** If every session looks healthy, tell the user that directly — do not post an all-clear to the channel.

        If you did find a stuck/slow session, post to **#claude-code-feedback** (channel ID: `C07VBSHV7EV`) using the Slack MCP tool. Use ToolSearch to find `slack_send_message` if it's not already loaded.

        **Use a two-message structure** to keep the channel scannable:

        1. **Top-level message** — one short line: hostname, Claude Code version, and a terse symptom (e.g. "session PID 12345 pegged at 100% CPU for 10min" or "git subprocess hung in D state"). No code blocks, no details.
        2. **Thread reply** — the full diagnostic dump. Pass the top-level message's `ts` as `thread_ts`. Include:
           - PID, CPU%, RSS, state, uptime, command line, child processes
           - Your diagnosis of what's likely wrong
           - Relevant debug log tail or `sample` output if you captured it

        If Slack MCP isn't available, format the report as a message the user can copy-paste into #claude-code-feedback (and let them know to thread the details themselves).

        ## Notes
        - Don't kill or signal any processes — this is diagnostic only.
        - If the user gave an argument (e.g., a specific PID or symptom), focus there first.
        """;

    /** CC registerStuckSkill — 统一产出 BundledSkillDefinition（P1-4）. */
    public boolean register(Consumer<BundledSkillDefinition> registrar, boolean isAntUser) {
        if (!isAntUser) {
            log.debug("[StuckSkillRegistrar] USER_TYPE!=ant, skipping registration");
            return false;
        }
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            null,   // whenToUse
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC stuck.ts:70)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            Map.of(),   // files
            (args, cwd) -> {
                String prompt = STUCK_PROMPT;
                if (args != null && !args.isBlank()) {
                    prompt += "\n## User-provided context\n\n" + args + "\n";
                }
                return List.of(PromptBlock.text(prompt));
            }
        );
        registrar.accept(def);
        log.info("[StuckSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}