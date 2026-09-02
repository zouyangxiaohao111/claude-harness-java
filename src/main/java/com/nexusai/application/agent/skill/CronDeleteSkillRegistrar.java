package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * /cron-delete bundled skill 注册器 · 对齐 CC skills/bundled/cronManage.ts registerCronDeleteSkill。
 *
 * <p><b>CC 单文件双注册</b>（cronManage.ts:8-25 registerCronListSkill + :27-54 registerCronDeleteSkill）——
 * Java 沿用「每 skill 一 registrar」既有惯例，拆为两个文件（见 {@link CronListSkillRegistrar} javadoc）。
 *
 * <p><b>对齐要点（cronManage.ts:27-54）</b>：
 * <ul>
 *   <li><b>userInvocable=true</b>（:34）+ <b>argumentHint='&lt;job-id&gt;'</b>（:33）</li>
 *   <li><b>isEnabled=isKairosCronEnabled</b>（:35）——与 /loop 同源 gate（见 {@link CronListSkillRegistrar}）</li>
 *   <li><b>getPromptForCommand(args)</b>（:36-52）：
 *       {@code id = args.trim()}，空 → 返回用法（:37-44）；非空 → 返回
 *       「Call CronDeleteTool with id "${id}" to cancel that scheduled job. Confirm the result to the user.」
 *       （:46-50）。CC 假定 args 恒为字符串（slash command 无参时为空串），Java 以 null 兜底对齐。</li>
 * </ul>
 *
 * <p>CRON_DELETE_TOOL_NAME 值（ScheduleCronTool/prompt.ts:65）= 'CronDelete'。
 */
public class CronDeleteSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CronDeleteSkillRegistrar.class);

    public static final String SKILL_NAME = "cron-delete";
    /** CC original: description (cronManage.ts:30). */
    public static final String DESCRIPTION = "Cancel a scheduled cron job by ID";
    /** CC original: whenToUse (cronManage.ts:31-32). */
    public static final String WHEN_TO_USE =
        "When the user wants to cancel, stop, or remove a scheduled/recurring task or cron job.";
    /** CC original: argumentHint (cronManage.ts:33). */
    public static final String ARGUMENT_HINT = "<job-id>";
    /** CC original: CRON_DELETE_TOOL_NAME (ScheduleCronTool/prompt.ts:65). */
    public static final String CRON_DELETE_TOOL_NAME = "CronDelete";

    /** CC original: 空 args 用法文案（cronManage.ts:42-43）. */
    public static final String USAGE_MESSAGE =
        "Usage: /cron-delete <job-id>\n\n"
            + "Provide the job ID to cancel. Use /cron-list to see active jobs and their IDs.";

    /**
     * CC registerCronDeleteSkill — 统一产出 BundledSkillDefinition（P1-4）。
     *
     * @param registrar          统一注册入口 Consumer（Bootstrapper register(def)）
     * @param isKairosCronEnabled isEnabled 开关（CC cronManage.ts:35 isEnabled: isKairosCronEnabled；
     *                            与 loop 同源 BundledSkillEnabledGates#isKairosCronEnabled）
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar,
                            BooleanSupplier isKairosCronEnabled) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            WHEN_TO_USE,
            ARGUMENT_HINT,
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC cronManage.ts:34)
            () -> isKairosCronEnabled.getAsBoolean(),   // isEnabled (CC cronManage.ts:35)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, ctx) -> {
                // CC cronManage.ts:37-38：const id = args.trim()——args 无参时为空串 → usage
                String id = args == null ? "" : args.trim();
                if (id.isEmpty()) {
                    return List.of(PromptBlock.text(USAGE_MESSAGE));
                }
                // CC cronManage.ts:46-50：Call CronDeleteTool with id "${id}" ...
                return List.of(PromptBlock.text(
                    "Call " + CRON_DELETE_TOOL_NAME + " with id \"" + id
                        + "\" to cancel that scheduled job. Confirm the result to the user."));
            }
        );
        registrar.accept(def);
        log.info("[CronDeleteSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}
