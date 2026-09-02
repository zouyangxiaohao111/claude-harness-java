package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Bundled skill 统一定义 · 对齐 CC skills/bundledSkills.ts:15-41 {@code BundledSkillDefinition}。
 *
 * <p><b>P1-4 统一类型</b>：改造前 Java 在 6 个 Registrar 中散布自定义碎片 record（字段数
 * 7/4/7/7/5/4，分属 Batch/ClaudeApi/Debug/Skillify/NexusaiInChrome/Keybindings）+ 5 参注册 sink 接口
 * （已删）。这些碎片 record 缺失 aliases/model/isEnabled/hooks/agent/context/files 字段，是
 * 字段丢失根因（E8/E9 复用 Batch 侧碎片 record 导致 allowedTools 丢失）。本类
 * 统一为单一 package 级 15 字段 record，所有 *SkillRegistrar 统一产出该类型，Bootstrapper 走
 * 单一 {@link #toCommand()} 入口（内含全字段映射）。
 *
 * <p><b>15 字段映射（CC bundledSkills.ts:15-41）</b>：
 * <ul>
 *   <li>name / description（必填，:16/:17）</li>
 *   <li>aliases（:18）/ whenToUse（:19）/ argumentHint（:20）</li>
 *   <li>allowedTools（:21）/ model（:22）/ disableModelInvocation（:23）/ userInvocable（:24）</li>
 *   <li>isEnabled（:25，{@code () => boolean} 惰性判定）</li>
 *   <li>hooks（:26，CC HooksSettings 结构化对象；Java Command.hooks 存 JSON 串，Command.java:89，仅透传不解析）</li>
 *   <li>context（:27，'inline'|'fork'）/ agent（:28）</li>
 *   <li>files（:36，relPath → content，参考文件解压由 Bootstrapper 走 P1-3 BundledSkillFileExtractor）</li>
 *   <li>getPromptForCommand（:37-40，CC 2 参 (args, ToolUseContext)；Java 以 (args, PromptFnContext) BiFunction 承载）</li>
 * </ul>
 *
 * <p>字段缺省语义（CC 可选字段 undefined → 默认值，bundledSkills.ts:81/:85/:86/:95）：
 * allowedTools ?? []、disableModelInvocation ?? false、userInvocable ?? true、
 * isHidden = !(userInvocable ?? true)。缺省在 {@link #toCommand()} 统一落位。
 */
public record BundledSkillDefinition(
        /** CC original: name (bundledSkills.ts:16) */
        String name,
        /** CC original: description (bundledSkills.ts:17) */
        String description,
        /** CC original: aliases (bundledSkills.ts:18) */
        List<String> aliases,
        /** CC original: whenToUse (bundledSkills.ts:19) */
        String whenToUse,
        /** CC original: argumentHint (bundledSkills.ts:20) */
        String argumentHint,
        /** CC original: allowedTools (bundledSkills.ts:21) */
        List<String> allowedTools,
        /** CC original: model (bundledSkills.ts:22) */
        String model,
        /** CC original: disableModelInvocation (bundledSkills.ts:23) */
        Boolean disableModelInvocation,
        /** CC original: userInvocable (bundledSkills.ts:24) */
        Boolean userInvocable,
        /** CC original: isEnabled (bundledSkills.ts:25) — 惰性判定（如 CC isKairosCronEnabled / isAutoMemoryEnabled） */
        BooleanSupplier isEnabled,
        /** CC original: hooks (bundledSkills.ts:26) — HooksSettings JSON 串（Command.java:89），仅透传不解析 */
        String hooks,
        /** CC original: context (bundledSkills.ts:27) — 'inline'|'fork' */
        String context,
        /** CC original: agent (bundledSkills.ts:28) */
        String agent,
        /** CC original: files (bundledSkills.ts:36) — relPath → content 参考文件映射 */
        Map<String, String> files,
        /** CC original: getPromptForCommand (bundledSkills.ts:37-40) — (args, PromptFnContext) → 内容块列表。
         *  PromptFnContext 为会话通道（cwd + messages + sessionId，对齐 CC getPromptForCommand(args, context)
         *  context.messages / skillify.ts:179-195，skill 复验决策 拍板#9 part2）。 */
        BiFunction<String, com.nexusai.model.command.PromptFnContext, List<PromptBlock>> getPromptForCommand) {

    /**
     * 把 15 字段 Definition 映射为 Command · 对齐 CC registerBundledSkill（bundledSkills.ts:75-98）。
     *
     * <p>字段映射：
     * <ul>
     *   <li>source = BUNDLED（:88）+ loadedFrom = BUNDLED（:89，CC bundledSkills.ts source 与 loadedFrom
     *       双字段独立，CommandSource 合一已由 P2-21 CommandLoadedFrom 独立建模取代）</li>
     *   <li>hasUserSpecifiedDescription = true（:80，ALL bundled 恒 true）</li>
     *   <li>allowedTools = allowedTools ?? []（:81）/ disableModelInvocation ?? false（:85）/
     *       userInvocable ?? true（:86）</li>
     *   <li>getPromptForCommand → Command.promptFn（:97，适配器降为 List&lt;String&gt; 文本块）</li>
     *   <li>isEnabled → Command.enabled（:94；CC isEnabled 惰性函数，Java 求值一次落 Boolean）</li>
     *   <li>context 未提供 → Command() 构造默认 'inline'（Command.java:136；CC :92 仅透传 definition.context
     *       无缺省）；progressMessage = 'running'（:96）/ isHidden = !(userInvocable ?? true)（:95）</li>
     * </ul>
     *
     * <p>baseDir（CC skillRoot :91）由 files 解压产生，不在此映射 —— Bootstrapper 统一注册入口
     * 在 toCommand() 之后按 P1-3 语义解压并 setBaseDir（bundledSkills.ts:59-72）。
     */
    public Command toCommand() {
        Command command = new Command();
        command.setId("bundled-" + name);
        command.setName(name);
        command.setDescription(description);
        command.setHasUserSpecifiedDescription(Boolean.TRUE); // CC :80 hasUserSpecifiedDescription: true（ALL bundled 恒 true）
        command.setSource(CommandSource.BUNDLED);   // CC :88 source: 'bundled'
        command.setLoadedFrom(CommandLoadedFrom.BUNDLED); // CC :89 loadedFrom: 'bundled'
        // P3-9 01-1 / DEL-03：不设 Command.builtin —— CC registerBundledSkill 无 builtin 字段
        // （bundledSkills.ts:75-98 Command 对象无 builtin；'builtin' 仅是 source 枚举值之一）。
        // 「builtin 性」由 source==BUNDLED 表达（CommandSource.isSystem()=BUILTIN|BUNDLED，
        // CommandService:287 删除守卫已按 source 判定）；Java web 侧 builtin 字段归 commands-integration 域
        // （BuiltInCommands source=BUILTIN 仍设），删除守卫由 source 判断承接，不依赖本字段。
        if (aliases != null) {
            command.setAliases(aliases);
        }
        if (whenToUse != null) {
            command.setWhenToUse(whenToUse);
        }
        if (argumentHint != null) {
            command.setArgumentHint(argumentHint);
        }
        command.setAllowedTools(allowedTools != null ? allowedTools : List.of()); // CC :81
        if (model != null) {
            command.setModel(model);
        }
        command.setDisableModelInvocation(disableModelInvocation != null ? disableModelInvocation : Boolean.FALSE); // CC :85
        command.setUserInvocable(userInvocable != null ? userInvocable : Boolean.TRUE); // CC :86
        if (isEnabled != null) {
            command.setEnabled(isEnabled.getAsBoolean()); // CC :94（DB/Web toggle 兼容：惰性求值落 Boolean）
            // P2-6：保留惰性供应 · CC bundledSkills.ts:94 isEnabled: definition.isEnabled 直接透传函数。
            // isCommandEnabled() 每次调用新鲜求值（CC types/command.ts:214-215 + commands.ts:478 注释），
            // enabled 字段仅作 DB/Web toggle 的兜底 + CommandDto 展示。
            command.setIsEnabled(isEnabled);
        }
        if (hooks != null) {
            command.setHooks(hooks); // CC :90
        }
        if (context != null) {
            // CC :92 仅透传 definition.context（无 '?? inline' 缺省）；context 未提供时落
            // Command() 构造默认 'inline'（Command.java:136），显式 set 冗余故省略。
            command.setContext(context);
        }
        if (agent != null) {
            command.setAgent(agent); // CC :93
        }
        // CC :97 getPromptForCommand 直挂 Command —— bundled 内容源 = 闭包输出（processSlashCommand.tsx:869/:884），
        // 非 SKILL.md 文件。适配器把 record 的 (args, PromptFnContext)→List<PromptBlock> 升格为
        // (args, PromptFnContext)→List<ContentBlockParam> 文本块（每 PromptBlock = 一个 TextBlockParam；
        // P2-16 后 Command.promptFn 为内容块数组，bundled 全 text 形态，与 MCP prompt 含 image 块同型）。
        if (getPromptForCommand != null) {
            command.setPromptFn((args, context) -> getPromptForCommand.apply(args, context)
                .stream().map(PromptBlock::text)
                .map(text -> (ContentBlockParam) new ContentBlockParam.TextBlockParam(text)).toList());
        }
        command.setProgressMessage("running"); // CC :96
        command.setIsHidden(!(userInvocable != null ? userInvocable : Boolean.TRUE)); // CC :95
        return command;
    }
}
