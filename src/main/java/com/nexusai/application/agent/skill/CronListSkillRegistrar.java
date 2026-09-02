package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * /cron-list bundled skill 注册器 · 对齐 CC skills/bundled/cronManage.ts registerCronListSkill。
 *
 * <p><b>CC 单文件双注册</b>（cronManage.ts:8-25 registerCronListSkill + :27-54 registerCronDeleteSkill）——
 * Java 沿用「每 skill 一 registrar」既有惯例（LoopSkillRegistrar 同款），拆为两个文件：
 * {@link CronListSkillRegistrar} 与 {@link CronDeleteSkillRegistrar}。
 *
 * <p><b>对齐要点（cronManage.ts:8-25）</b>：
 * <ul>
 *   <li><b>userInvocable=true</b>（:14）</li>
 *   <li><b>isEnabled=isKairosCronEnabled</b>（:15）——与 /loop 同源 gate
 *       （ScheduleCronTool/prompt.ts:36-45）。skill <b>始终注册</b>，仅 enabled 惰性门控
 *       （对齐 CC「registered unconditionally; the skill's own isEnabled callback decides visibility」）。</li>
 *   <li><b>getPromptForCommand 无参</b>（:16-23）→ 返回「Call CronListTool to list all scheduled cron jobs...」text 块。</li>
 * </ul>
 *
 * <p>CRON_LIST_TOOL_NAME 值（ScheduleCronTool/prompt.ts:66）= 'CronList'。
 */
public class CronListSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CronListSkillRegistrar.class);

    public static final String SKILL_NAME = "cron-list";
    /** CC original: description (cronManage.ts:11). */
    public static final String DESCRIPTION = "List all scheduled cron jobs in this session";
    /** CC original: whenToUse (cronManage.ts:12-13). */
    public static final String WHEN_TO_USE =
        "When the user wants to see their scheduled/recurring tasks, check active cron jobs, or review what is currently looping.";
    /** CC original: CRON_LIST_TOOL_NAME (ScheduleCronTool/prompt.ts:66). */
    public static final String CRON_LIST_TOOL_NAME = "CronList";

    /**
     * CC cronManage.ts:20-21 getPromptForCommand 静态指令文案 · 无参调用恒返回列表指令。
     */
    public static final String PROMPT =
        "Call " + CRON_LIST_TOOL_NAME + " to list all scheduled cron jobs. Display the results in a table "
            + "with columns: ID, Schedule, Prompt, Recurring, Durable. If no jobs exist, say \"No scheduled tasks.\"";

    /**
     * CC registerCronListSkill — 统一产出 BundledSkillDefinition（P1-4）。
     *
     * @param registrar          统一注册入口 Consumer（Bootstrapper register(def)）
     * @param isKairosCronEnabled isEnabled 开关（CC cronManage.ts:15 isEnabled: isKairosCronEnabled；
     *                            与 loop 同源 BundledSkillEnabledGates#isKairosCronEnabled）
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar,
                            BooleanSupplier isKairosCronEnabled) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            WHEN_TO_USE,
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC cronManage.ts:14)
            () -> isKairosCronEnabled.getAsBoolean(),   // isEnabled (CC cronManage.ts:15)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, ctx) -> List.of(PromptBlock.text(PROMPT))   // CC cronManage.ts:16-23 无参
        );
        registrar.accept(def);
        log.info("[CronListSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}
