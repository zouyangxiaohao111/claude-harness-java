package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * /loop bundled skill 注册器 · 对齐 CC skills/bundled/loop.ts registerLoopSkill.
 *
 * <p>L1 语义: 注册 'loop' skill (user-invocable=true), getPromptForCommand 解析 `[interval] <prompt>`
 *            → 返回 cron prompt 或空 input → usage message. isEnabled 由 isKairosCronEnabled 控制.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `register(sink, isKairosCronEnabled) → boolean` 签名 + 静态 buildPrompt 输出 CC loop.ts:25-72
 *       完整指令文案（interval 解析交给模型，Java 不再自行 parseArgs/buildCronExpression）</li>
 *   <li><b>A2 Golden Trace</b>: 空 args → USAGE_MESSAGE; 非空 args → buildPrompt（完整静态指令 + ## Input 段）</li>
 *   <li><b>A3</b>: USAGE_MESSAGE 逐字对齐 CC loop.ts:11-23（5 个示例，含 'defaults to 10m' 与 'every 20m' 两行）</li>
 *   <li><b>A4</b>: buildPrompt 插值 CronCreate/CronDelete/10m/7（CC ScheduleCronTool/prompt.ts:64-65 + prompt.ts:8
 *       DEFAULT_MAX_AGE_DAYS=7）</li>
 *   <li><b>A5</b>: 真实场景 args='check the deploy every 20m' → prompt 含 '## Parsing (in priority order)' /
 *       '## Interval → cron' / '## Action' / '## Input' 四段</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Pattern digit+unit 替代 TS regex (smhd unit); record 替代 TS tuple;
 *                    interval 解析为 cron 表达式用 switch 表达式.
 */
public class LoopSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LoopSkillRegistrar.class);

    public static final String DEFAULT_INTERVAL = "10m";
    public static final String SKILL_NAME = "loop";
    public static final String DESCRIPTION =
        "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo, defaults to 10m)";
    /** CC original: whenToUse (loop.ts:80). */
    public static final String WHEN_TO_USE =
        "When the user wants to set up a recurring task, poll for status, or run something repeatedly on an interval "
            + "(e.g. \"check the deploy every 5 minutes\", \"keep running /babysit-prs\"). Do NOT invoke for one-off tasks.";
    public static final String ARGUMENT_HINT = "[interval] <prompt>";

    public static final String USAGE_MESSAGE =
        "Usage: /loop [interval] <prompt>\n\n" +
        "Run a prompt or slash command on a recurring interval.\n\n" +
        "Intervals: Ns, Nm, Nh, Nd (e.g. 5m, 30m, 2h, 1d). Minimum granularity is 1 minute.\n" +
        "If no interval is specified, defaults to " + DEFAULT_INTERVAL + ".\n\n" +
        "Examples:\n" +
        "  /loop 5m /babysit-prs\n" +
        "  /loop 30m check the deploy\n" +
        "  /loop 1h /standup 1\n" +
        "  /loop check the deploy          (defaults to " + DEFAULT_INTERVAL + ")\n" +
        "  /loop check the deploy every 20m";

    /** CC original: CRON_CREATE_TOOL_NAME (ScheduleCronTool/prompt.ts:64). */
    public static final String CRON_CREATE_TOOL_NAME = "CronCreate";
    /** CC original: CRON_DELETE_TOOL_NAME (ScheduleCronTool/prompt.ts:65). */
    public static final String CRON_DELETE_TOOL_NAME = "CronDelete";
    /** CC original: DEFAULT_MAX_AGE_DAYS = 7 (ScheduleCronTool/prompt.ts:8 = cronTasks.ts:354 recurringMaxAgeMs ÷ 日). */
    public static final String DEFAULT_MAX_AGE_DAYS = "7";

    /**
     * CC loop.ts buildPrompt 完整文案（:25-72）· 静态指令 prompt，interval 解析交给模型。
     *
     * <p>占位符 {{CRON_CREATE_TOOL_NAME}}/{{CRON_DELETE_TOOL_NAME}}/{{DEFAULT_MAX_AGE_DAYS}}/
     * {{DEFAULT_INTERVAL}} 由 {@link #buildPrompt} 替换；${args} 为 user input 插值位（CC :69-71）。
     */
    public static final String LOOP_PROMPT_TEMPLATE = """
        # /loop — schedule a recurring prompt

        Parse the input below into `[interval] <prompt…>` and schedule it with {{CRON_CREATE_TOOL_NAME}}.

        ## Parsing (in priority order)

        1. **Leading token**: if the first whitespace-delimited token matches `^\\d+[smhd]$` (e.g. `5m`, `2h`), that's the interval; the rest is the prompt.
        2. **Trailing "every" clause**: otherwise, if the input ends with `every <N><unit>` or `every <N> <unit-word>` (e.g. `every 20m`, `every 5 minutes`, `every 2 hours`), extract that as the interval and strip it from the prompt. Only match when what follows "every" is a time expression — `check every PR` has no interval.
        3. **Default**: otherwise, interval is `{{DEFAULT_INTERVAL}}` and the entire input is the prompt.

        If the resulting prompt is empty, show usage `/loop [interval] <prompt>` and stop — do not call {{CRON_CREATE_TOOL_NAME}}.

        Examples:
        - `5m /babysit-prs` → interval `5m`, prompt `/babysit-prs` (rule 1)
        - `check the deploy every 20m` → interval `20m`, prompt `check the deploy` (rule 2)
        - `run tests every 5 minutes` → interval `5m`, prompt `run tests` (rule 2)
        - `check the deploy` → interval `{{DEFAULT_INTERVAL}}`, prompt `check the deploy` (rule 3)
        - `check every PR` → interval `{{DEFAULT_INTERVAL}}`, prompt `check every PR` (rule 3 — "every" not followed by time)
        - `5m` → empty prompt → show usage

        ## Interval → cron

        Supported suffixes: `s` (seconds, rounded up to nearest minute, min 1), `m` (minutes), `h` (hours), `d` (days). Convert:

        | Interval pattern      | Cron expression     | Notes                                    |
        |-----------------------|---------------------|------------------------------------------|
        | `Nm` where N ≤ 59   | `*/N * * * *`     | every N minutes                          |
        | `Nm` where N ≥ 60   | `0 */H * * *`     | round to hours (H = N/60, must divide 24)|
        | `Nh` where N ≤ 23   | `0 */N * * *`     | every N hours                            |
        | `Nd`                | `0 0 */N * *`     | every N days at midnight local           |
        | `Ns`                | treat as `ceil(N/60)m` | cron minimum granularity is 1 minute  |

        **If the interval doesn't cleanly divide its unit** (e.g. `7m` → `*/7 * * * *` gives uneven gaps at :56→:00; `90m` → 1.5h which cron can't express), pick the nearest clean interval and tell the user what you rounded to before scheduling.

        ## Action

        1. Call {{CRON_CREATE_TOOL_NAME}} with:
           - `cron`: the expression from the table above
           - `prompt`: the parsed prompt from above, verbatim (slash commands are passed through unchanged)
           - `recurring`: `true`
        2. Briefly confirm: what's scheduled, the cron expression, the human-readable cadence, that recurring tasks auto-expire after {{DEFAULT_MAX_AGE_DAYS}} days, and that they can cancel sooner with {{CRON_DELETE_TOOL_NAME}} (include the job ID).
        3. **Then immediately execute the parsed prompt now** — don't wait for the first cron fire. If it's a slash command, invoke it via the Skill tool; otherwise act on it directly.

        ## Input

        ${args}
        """;

    /** CC buildPrompt — 完整静态指令 + ${args} 插值（loop.ts:25-72）· 不再自行解析 interval（解析交给模型）. */
    public static String buildPrompt(String args) {
        if (log.isDebugEnabled()) {
            log.debug("[LoopSkillRegistrar] buildPrompt args='{}' 渲染 LOOP_PROMPT_TEMPLATE（CC loop.ts:25-72 静态指令）", args);
        }
        return LOOP_PROMPT_TEMPLATE
            .replace("{{CRON_CREATE_TOOL_NAME}}", CRON_CREATE_TOOL_NAME)
            .replace("{{CRON_DELETE_TOOL_NAME}}", CRON_DELETE_TOOL_NAME)
            .replace("{{DEFAULT_MAX_AGE_DAYS}}", DEFAULT_MAX_AGE_DAYS)
            .replace("{{DEFAULT_INTERVAL}}", DEFAULT_INTERVAL)
            .replace("${args}", args == null ? "" : args);
    }

    /**
     * CC registerLoopSkill — 统一产出 BundledSkillDefinition（P1-4）.
     *
     * @param registrar          统一注册入口 Consumer（Bootstrapper register(def)）
     * @param isKairosCronEnabled isEnabled 开关（CC loop.ts:83 isEnabled: isKairosCronEnabled；修 E10）
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar,
                            BooleanSupplier isKairosCronEnabled) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            WHEN_TO_USE,   // CC loop.ts:80
            ARGUMENT_HINT, // CC loop.ts:81
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC loop.ts:82)
            () -> isKairosCronEnabled.getAsBoolean(),   // isEnabled (CC loop.ts:83; 修 E10/E11)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> {
                String trimmed = args == null ? "" : args.trim();
                if (trimmed.isEmpty()) {
                    return List.of(PromptBlock.text(USAGE_MESSAGE));
                }
                return List.of(PromptBlock.text(buildPrompt(trimmed)));
            }
        );
        registrar.accept(def);
        log.info("[LoopSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}