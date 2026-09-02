package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;

import java.util.List;

/**
 * Skill Command 构建器 · 对齐 CC {@code loadSkillsDir.ts:270-401 createSkillCommand}.
 *
 * <p>CC 入参 22 项（:270-292 解构）→ 返回 25 属性 Command（:317-399）。
 * Java 端经本构建器统一落位 25 属性，避免 MCP 生产只填 8/25 字段
 * （全局反思 N-1：对齐目标 = 返回 25 属性，非 22 入参）。
 *
 * <p>getPromptForCommand 动态内容（参数替换 / shell 注入 / MCP prompts/get）由
 * 消费侧接线（P2-9 / P2-13），本构建器仅静态落位 content + argNames。
 */
public final class CreateSkillCommand {

    /**
     * CC createSkillCommand 入参（loadSkillsDir.ts:270-292，22 项解构）。
     *
     * @param skillName                 CC original: skillName — 命令名（MCP 为 mcp__{server}__{prompt}）
     * @param displayName               CC original: displayName — 可读展示名（undefined 等价 null）
     * @param description               CC original: description
     * @param hasUserSpecifiedDescription CC original: hasUserSpecifiedDescription
     * @param markdownContent           CC original: markdownContent — SKILL.md 正文 / MCP 静态内容
     * @param allowedTools              CC original: allowedTools
     * @param argumentHint              CC original: argumentHint
     * @param argumentNames             CC original: argumentNames
     * @param whenToUse                 CC original: whenToUse
     * @param version                   CC original: version
     * @param model                     CC original: model
     * @param disableModelInvocation    CC original: disableModelInvocation
     * @param userInvocable             CC original: userInvocable
     * @param source                    CC original: source（PromptCommand['source']，command.ts:32）
     * @param baseDir                   CC original: baseDir — skillRoot
     * @param loadedFrom                CC original: loadedFrom（LoadedFrom，command.ts:191-197 /
     *                                  loadSkillsDir.ts:67-74）— 独立于 source 的加载渠道字段
     * @param hooks                     CC original: hooks（HooksSettings）
     * @param executionContext          CC original: executionContext（'inline' | 'fork' | undefined）
     * @param agent                     CC original: agent
     * @param paths                     CC original: paths
     * @param effort                    CC original: effort（EffortValue）
     * @param shell                     CC original: shell（FrontmatterShell）
     */
    public record Params(
            String skillName,
            String displayName,
            String description,
            boolean hasUserSpecifiedDescription,
            String markdownContent,
            List<String> allowedTools,
            String argumentHint,
            List<String> argumentNames,
            String whenToUse,
            String version,
            String model,
            boolean disableModelInvocation,
            boolean userInvocable,
            CommandSource source,
            String baseDir,
            CommandLoadedFrom loadedFrom,
            String hooks,
            String executionContext,
            String agent,
            List<String> paths,
            String effort,
            String shell
    ) {}

    private CreateSkillCommand() {
        // 工具类：静态工厂
    }

    /**
     * 对齐 CC createSkillCommand 返回的 25 属性（loadSkillsDir.ts:317-399）。
     *
     * <p>CC 字段 → Java Command 字段映射（JavaDoc 标注 CC 行号）：
     * <ol>
     *   <li>type: 'prompt' (:318)</li>
     *   <li>name: skillName (:319)</li>
     *   <li>description (:320)</li>
     *   <li>hasUserSpecifiedDescription (:321)</li>
     *   <li>allowedTools (:322)</li>
     *   <li>argumentHint (:323)</li>
     *   <li>argNames: argumentNames.length &gt; 0 ? argumentNames : undefined (:324)</li>
     *   <li>whenToUse (:325)</li>
     *   <li>version (:326)</li>
     *   <li>model (:327)</li>
     *   <li>disableModelInvocation (:328)</li>
     *   <li>userInvocable (:329)</li>
     *   <li>context: executionContext (:330)</li>
     *   <li>agent (:331)</li>
     *   <li>effort (:332)</li>
     *   <li>paths (:333)</li>
     *   <li>contentLength: markdownContent.length (:334) — Java 经 content 派生 getContentLength()</li>
     *   <li>isHidden: !userInvocable (:335)</li>
     *   <li>progressMessage: 'running' (:336)</li>
     *   <li>userFacingName(): displayName || skillName (:337-339) — Command.userFacingName() 已实现</li>
     *   <li>source (:340)</li>
     *   <li>loadedFrom (:341) — Java 双字段独立落位（command.ts:32 vs :191-197，M20 △ 根因消除）</li>
     *   <li>hooks (:342)</li>
     *   <li>skillRoot: baseDir (:343)</li>
     *   <li>getPromptForCommand (:344-399) — 内容源 = content + argNames（静态落位，动态接线 P2-9/P2-13）</li>
     * </ol>
     *
     * @param p 22 项入参
     * @return 覆盖 25 属性的 Command
     */
    public static Command create(Params p) {
        Command cmd = new Command();
        // CC: type: 'prompt' (:318)
        cmd.setType("prompt");
        // CC: name: skillName (:319)
        cmd.setName(p.skillName());
        // CC: displayName — 供 userFacingName() 优先取用 (:337-339)
        cmd.setDisplayName(p.displayName());
        // CC: description (:320)
        cmd.setDescription(p.description());
        // CC: hasUserSpecifiedDescription (:321)
        cmd.setHasUserSpecifiedDescription(p.hasUserSpecifiedDescription());
        // CC: allowedTools (:322)
        cmd.setAllowedTools(p.allowedTools());
        // CC: argumentHint (:323)
        cmd.setArgumentHint(p.argumentHint());
        // CC: argNames (:324) — 空数组等价 CC undefined
        cmd.setArgNames(p.argumentNames() == null || p.argumentNames().isEmpty() ? null : p.argumentNames());
        // CC: whenToUse (:325)
        cmd.setWhenToUse(p.whenToUse());
        // CC: version (:326)
        cmd.setVersion(p.version());
        // CC: model (:327)
        cmd.setModel(p.model());
        // CC: disableModelInvocation (:328)
        cmd.setDisableModelInvocation(p.disableModelInvocation());
        // CC: userInvocable (:329)
        cmd.setUserInvocable(p.userInvocable());
        // CC: context: executionContext (:330)
        cmd.setContext(p.executionContext());
        // CC: agent (:331)
        cmd.setAgent(p.agent());
        // CC: effort (:332)
        cmd.setEffort(p.effort());
        // CC: paths (:333)
        cmd.setPaths(p.paths());
        // CC: contentLength: markdownContent.length (:334) — Java getContentLength() 从 content 派生
        cmd.setContent(p.markdownContent());
        // CC: isHidden: !userInvocable (:335)
        cmd.setIsHidden(!p.userInvocable());
        // CC: progressMessage: 'running' (:336)
        cmd.setProgressMessage("running");
        // CC: source (:340) + loadedFrom (:341) 双字段独立赋值（command.ts:32 source 与 :191-197
        // loadedFrom 是两独立字段 —— 旧合一赋值 cmd.setSource(loadedFrom!=null?loadedFrom:source)
        // 把 loadedFrom 值塞进 source 字段，是 M20 △ 根因；deleteList P2-21 第 2 项）
        cmd.setSource(p.source());
        cmd.setLoadedFrom(p.loadedFrom());
        // CC: hooks (:342)
        cmd.setHooks(p.hooks());
        // CC: skillRoot: baseDir (:343)
        cmd.setBaseDir(p.baseDir());
        return cmd;
    }
}
