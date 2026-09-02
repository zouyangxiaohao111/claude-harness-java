package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * /import-cc bundled skill 注册器 · NexusAI 自包含导入（非 CC bundled skill，本项目扩展）。
 *
 * <p><b>本质</b>：纯提示型（prompt-only）skill，指导模型执行「一键导入 CC 技能/插件到 NexusAI」——
 * 把 {@code ~/.claude/skills} + {@code ~/.claude/plugins} 复制到 {@code ~/.nexusai}，
 * 把 CC settings.json 的 enabledPlugins 映射 PUT 到 /api/v1/settings 落 DB，
 * 并把 pluginClaudeFallback 置 false（关闭插件双读）。零运行时副作用（不改主循环、不 toggle 开关）；
 * 用户敲 /import-cc 时 getPromptForCommand 把详细步骤 + 可选的 {@code ## User input} 段返回为 text 块，
 * 模型据此用 Bash 执行文件复制、用 REST 落库。
 *
 * <p><b>对齐 UltracodeSkillRegistrar 的 BundledSkillDefinition 模式</b>：
 * <ul>
 *   <li><b>无条件注册</b>：无 isEnabled gate（对齐 ultracode.ts:219 registerUltracodeSkill 无 isEnabled 字段
 *       → undefined → 默认启用；Java isEnabled=null → toCommand 不设 → Command.isCommandEnabled() 恒 true）。</li>
 *   <li><b>userInvocable=true</b></li>
 *   <li><b>args append 语义</b>（对齐 ultracode.ts:228-232）：{@code if (args) prompt += `\n## User input\n\n${args}\n`}——
 *       truthy 判定（非 null 且非空串，不 trim）；返回 {@code [{type:'text', text}]} 单块。</li>
 * </ul>
 */
public class ImportCcSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ImportCcSkillRegistrar.class);

    public static final String SKILL_NAME = "import-cc";
    /** 一键导入 CC 技能/插件到 NexusAI（复制副本 + enabledPlugins 落 DB + 关闭插件双读）。 */
    public static final String DESCRIPTION =
        "One-click import Claude Code (CC) skills and plugins from ~/.claude into NexusAI (~/.nexusai): "
            + "copy skill/plugin copies, persist the enabledPlugins map to the DB, and disable the plugin "
            + "dual-read fallback (pluginClaudeFallback=false) so NexusAI becomes self-contained.";
    public static final String WHEN_TO_USE =
        "When the user wants to import their Claude Code skills/plugins into NexusAI in one step, migrate the "
            + "enabled plugin set into the NexusAI DB, or make NexusAI stop reading ~/.claude plugins at runtime "
            + "(self-contained mode).";

    /**
     * /import-cc 详细执行步骤（英文 prompt · 指导模型逐步执行）。
     *
     * <p>内容覆盖用户拍板范围：检查源目录 → Bash 复制技能 → Bash 复制插件 → 读 CC settings.json 的
     * enabledPlugins → PUT /api/v1/settings 落 DB → PUT pluginClaudeFallback=false 关双读 → 汇报结果。
     */
    public static final String IMPORT_CC_PROMPT = """
# /import-cc — Import Claude Code Skills & Plugins into NexusAI

Import the user's Claude Code (CC) skills and plugins into NexusAI so that NexusAI becomes self-contained (no longer depending on ~/.claude at runtime). Execute the steps below IN ORDER. Use the Bash tool for all filesystem operations and the settings REST endpoint for DB persistence. Do not skip a step silently — if anything fails, report it explicitly.

## Step 1 — Verify source directories

Check that the CC source directories exist before copying:

```bash
ls -la ~/.claude/skills
ls -la ~/.claude/plugins
```

If a source directory does not exist or is empty, note it and skip the corresponding copy step — do NOT invent data. Record the missing source for the final summary.

## Step 2 — Copy skills into NexusAI

```bash
mkdir -p ~/.nexusai/skills
cp -r ~/.claude/skills/. ~/.nexusai/skills/
```

Then count the copied skill directories (`ls ~/.nexusai/skills`).

## Step 3 — Copy plugins into NexusAI

```bash
mkdir -p ~/.nexusai/plugins
cp -r ~/.claude/plugins/. ~/.nexusai/plugins/
```

Then count the copied plugin directories (`ls ~/.nexusai/plugins`).

## Step 4 — Import enabledPlugins into the DB

Read the CC settings file and extract the `enabledPlugins` map:

```bash
cat ~/.claude/settings.json
```

The map shape is a JSON object of plugin name → enabled boolean, e.g. `{"my-plugin": true, "legacy-plugin": false}`. Extract that object verbatim and persist it to the NexusAI DB:

```
PUT /api/v1/settings
Content-Type: application/json

{"enabledPlugins": <the-extracted-map>}
```

Note: if `enabledPlugins` is absent from settings.json, PUT `{"enabledPlugins": {}}` (an empty map) so the NexusAI read chain treats all plugins as configured-away rather than falling back to the dual-read path.

## Step 5 — Turn off the plugin dual-read fallback

Make NexusAI read plugins only from its own directory (self-contained):

```
PUT /api/v1/settings
Content-Type: application/json

{"pluginClaudeFallback": false}
```

## Step 6 — Report the import result

Summarize to the user:
- number of skills copied
- number of plugins copied
- the list of enabled plugins (names) from the map you imported in Step 4
- any missing source directory or caveat encountered
""";

    /**
     * 注册 /import-cc · 统一产出 BundledSkillDefinition（对齐 UltracodeSkillRegistrar）。
     *
     * <p>无条件注册（无 isEnabled gate，对齐 ultracode.ts:219）；getPromptForCommand 返回
     * {@code [IMPORT_CC_PROMPT (+ args ? "\n## User input\n\n"+args+"\n" : "")]}（对齐 ultracode.ts:227-233）。
     *
     * @param registrar 统一注册入口 Consumer（Bootstrapper register(def)）
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            WHEN_TO_USE,
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (undefined → default false)
            true,   // userInvocable（对齐 ultracode.ts:226）
            null,   // isEnabled（无条件注册，无 gate → 默认启用）
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, ctx) -> {
                String prompt = IMPORT_CC_PROMPT;
                if (args != null && !args.isEmpty()) {
                    // 对齐 ultracode.ts:229-231：if (args) prompt += `\n## User input\n\n${args}\n`
                    // truthy 判定（非 null 非空串，不 trim——空白串仍 truthy 追加）
                    prompt += "\n## User input\n\n" + args + "\n";
                }
                return List.of(PromptBlock.text(prompt));
            }
        );
        registrar.accept(def);
        log.info("[ImportCcSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}
