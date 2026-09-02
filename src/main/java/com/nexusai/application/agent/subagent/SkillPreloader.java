package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.ArgumentSubstitution;
import com.nexusai.application.agent.skill.PromptShellExecutor;
import com.nexusai.application.agent.skill.SkillContentLoader;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 预加载 · 对齐 CC runAgent.ts:577-645
 *
 * <p>从 agent frontmatter 的 skills 字段加载指定 skill，
 * 并将 skill 内容作为 initialMessages 添加到 subagent 的消息链中。
 *
 * <p><b>P1-11 改造：</b>不再每次 {@code new SkillRegistry}，改为消费共享的 {@link SkillRegistry} bean
 * （对齐 CC runAgent.ts:580 {@code const allSkills = await getSkillToolCommands(getProjectRoot())}
 * — subagent preload 用模块级共享 memoized 函数，非 per-call 新建）。subagent 预加载与
 * SkillTool/SkillCatalog 共用同一命令聚合源（SkillRegistry @Bean，含 MCP/builtinPlugin/dynamicSkills
 * 源 + memoize 缓存），对齐 CC {@code getSkillToolCommands} commands.ts:563。
 *
 * <p><b>P1-11 明确不覆盖（归其他 P-item）</b>：
 * <ul>
 *   <li>{@code type==='prompt'} 过滤（runAgent.ts:607-613）</li>
 *   <li>Promise.all 并发内容加载（:617-627）</li>
 *   <li>formatSkillLoadingMetadata XML 消息 shape（:634-644）</li>
 *   <li>getCommand 抛错语义（commands.ts:704-726）——【ALIGN-VERIFY-1 G-2 复核 2026-08-13】：
 *       CC runAgent 预加载<b>不</b>依赖 getCommand 的 throw —— resolveSkillName 未命中（hasCommand
 *       false）→ warn + continue（runAgent.ts:594-599，fail-soft）；getCommand 仅在 resolve 成功
 *       后才调用（:606，防御网）。本类 findCommand==null → warn + missingSkills 继续，与 CC
 *       warn+continue 语义等价，无需 fail-loud 改造。getCommand throw 语义由 REST 层
 *       CommandController 404（镜像 ReferenceError + 可用清单）表达。</li>
 * </ul>
 */
@Component
public class SkillPreloader {

    private static final Logger log = LoggerFactory.getLogger(SkillPreloader.class);

    /**
     * 共享 SkillRegistry bean · CC 原名 getSkillToolCommands（runAgent.ts:580）·
     * Java 侧消费单一 SkillRegistry @Bean（ToolRegistrationConfig.skillRegistry()）。
     */
    private final SkillRegistry registry;

    private final SkillContentLoader contentLoader = new SkillContentLoader();

    /**
     * [P5-③] PromptShellExecutor · skill 内容 ${!`cmd` / ```!```} 内嵌 shell 注入（对齐 CC
     * loadSkillsDir.ts:374-396 executeShellCommandsInPrompt，getPromptForCommand 闭包最后一步）。
     *
     * <p>由 {@code ToolRegistrationConfig.promptShellExecutor()} @Bean 经 setter 注入
     * （BashTool/PowerShellTool/PermissionPipeline 真接线）；构造器兜底
     * {@code new PromptShellExecutor()}（默认实现 PermissionPipeline 空 → fail-closed 拒绝，
     * 对齐 SkillToolImpl 同款兜底语义）。
     */
    private PromptShellExecutor promptShellExecutor = new PromptShellExecutor();

    /**
     * 构造器注入共享 SkillRegistry bean（Spring @Component 单例）。
     *
     * <p>CC 真源：runAgent.ts:580 subagent preload 调用模块级共享 memoized
     * {@code getSkillToolCommands(getProjectRoot())}，非 per-call 新建命令源。
     * Java 等价：注入 {@code skillRegistry()} @Bean，与 SkillTool/SkillCatalog 同一实例
     * （P1-2 已抽取单一 bean），自动共享 4 源聚合 + memoize 缓存。
     */
    public SkillPreloader(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * [P5-③] setter 注入 PromptShellExecutor（Spring setter 注入，可选 required=false）·
     * 未注入保留构造器兜底 {@code new PromptShellExecutor()}（对齐 SkillToolImpl:193-196 同款模式）。
     *
     * <p><b>@Lazy 打破循环依赖</b>（2026-09-01 启动循环修复）：skillPreloader → promptShellExecutor
     * → bashTool → backgroundTaskRunner → spawnInProcess → subagentExecutor → subagentTool →
     * skillPreloader 成环（SkillPreloader 新增注入后形成）。@Lazy 让 Spring 注入代理、首次调用才解析
     * promptShellExecutor 真实实例（此时全部 bean 已创建完，循环已破）；技能内联 shell 注入（
     * {@code !`cmd`}）运行期才触发，@Lazy 代理透明。
     */
    @Autowired(required = false)
    public void setPromptShellExecutor(
            @org.springframework.context.annotation.Lazy PromptShellExecutor promptShellExecutor) {
        if (promptShellExecutor != null) {
            this.promptShellExecutor = promptShellExecutor;
        }
    }

    /**
     * Skill 元数据 · 对齐 CC skill type
     */
    public record Skill(
        String name,
        String content,
        String progressMessage
    ) {}

    /**
     * 预加载结果
     */
    public record PreloadResult(
        List<Map<String, Object>> initialMessages,
        List<String> missingSkills
    ) {}

    /**
     * 预加载 agent frontmatter 中指定的 skills · 对齐 CC runAgent.ts:577-645
     *
     * <p>P1-11：消费注入的共享 {@link SkillRegistry} bean（不再 per-call {@code new SkillRegistry}），
     * 内部用 {@code registry.findCommand} 解析技能名（对齐 CC {@code findCommand} commands.ts:688-702；
     * userFacingName 解析维度归 P1-12）。
     *
     * <p>[P5-③] 兼容重载：无 sessionId/ctx（测试 / 旧调用方）→ 委托
     * {@link #preload(List, String, ToolUseContext)}（ctx=null → shell 注入跳过并 log.warn，
     * 对齐 SkillToolImpl 1 参 execute 同款语义）。
     *
     * @param skillsToPreload agent frontmatter 的 skills 字段
     * @return 预加载结果
     */
    public PreloadResult preload(List<String> skillsToPreload) {
        return preload(skillsToPreload, null, null);
    }

    /**
     * [P5-③] 预加载 agent frontmatter 中指定的 skills（全链动态派生）· 对齐 CC runAgent.ts:577-645。
     *
     * <p><b>CC 真源</b>：runAgent.ts:617-627 {@code skill.getPromptForCommand('', toolUseContext)}
     * —— 预加载内容 = getPromptForCommand 全链输出，与 SkillToolImpl.doExecute 磁盘技能分支等价：
     * withBaseDirPrefix → substituteArguments(args='') → replacePluginVariables →
     * replaceUserConfig → replaceSkillDir(cmd.getBaseDir) → replaceSessionId(sessionId) →
     * MCP 安全闸后 PromptShellExecutor.executeShellCommandsInPrompt（对齐 loadSkillsDir.ts:344-396）。
     *
     * <p><b>P5 真实缺口</b>：旧实现仅 {@code contentLoader.loadContent(cmd)}（无任何派生链），
     * ${CLAUDE_SKILL_DIR}/${CLAUDE_SESSION_ID}/${CLAUDE_PLUGIN_ROOT}/args/shell 在 subagent 预加载
     * 场景全部保持字面。本方法补全与 SkillToolImpl.doExecute:1500-1558 相同的派生链。
     *
     * <p><b>bundled skill（promptFn 非 null）</b>：内容 = promptFn 闭包输出 text 块 join('\n\n')
     * （对齐 SkillToolImpl.doExecute:1467-1492；bundled 闭包自含 args 处理，不做磁盘专属管线），
     * 再 withBaseDirPrefix（对齐 CC prependBaseDir）。ctx==null 时 cwd/messages 回落 null/空。
     *
     * <p><b>shell 注入 fail-soft 守卫</b>：fork 子代理权限上下文传递不完整时
     * {@link PromptShellExecutor} 可能 fail-closed（PermissionPipeline 空 → 拒绝）→ 本方法
     * catch MalformedCommandException log.warn 不阻断（保留未注入内容继续预加载，对齐
     * SubagentExecutor Step 14 的 catch+warn 容错语义；CC 真源 preload Promise.all reject 会
     * 拖垮 runAgent，Java 侧 deliberately 放宽为 warn——web 子代理不因技能内嵌 shell 权限缺省
     * 而整体失败）。
     *
     * @param skillsToPreload agent frontmatter 的 skills 字段
     * @param sessionId       会话 short id（${CLAUDE_SESSION_ID} 替换值；null → 不替换）
     * @param ctx             fork 子代理 ToolUseContext（shell 注入权限预检依赖；null → 跳过注入并 warn）
     * @return 预加载结果
     */
    public PreloadResult preload(List<String> skillsToPreload, String sessionId, ToolUseContext ctx) {

        if (skillsToPreload == null || skillsToPreload.isEmpty()) {
            return new PreloadResult(List.of(), List.of());
        }

        log.info("[SkillPreloader] preload: 使用共享 SkillRegistry (skillsRoot={}), 待加载技能数={}",
                registry.getSkillsRoot(), skillsToPreload.size());

        List<String> missingSkills = new ArrayList<>();
        List<Skill> validSkills = new ArrayList<>();

        for (String skillName : skillsToPreload) {
            Command cmd = registry.findCommand(skillName);
            if (cmd == null) {
                log.warn("[SkillPreloader] Skill '{}' 在共享 registry 中未找到 (bundled + file system + MCP + dynamic)",
                        skillName);
                missingSkills.add(skillName);
            } else {
                String content = deriveContent(cmd, sessionId, ctx);
                String progressMsg = cmd.getProgressMessage() != null
                    ? cmd.getProgressMessage()
                    : "Preloaded skill: " + cmd.getName();
                validSkills.add(new Skill(cmd.getName(), content, progressMsg));
                log.info("[SkillPreloader] Skill '{}' 预加载命中 (name={}, source={}, contentLen={})",
                        skillName, cmd.getName(), cmd.getSource(),
                        content == null ? 0 : content.length());
            }
        }

        // 构建 initialMessages · 对齐 CC runAgent.ts:639-644
        List<Map<String, Object>> initialMessages = new ArrayList<>();
        for (Skill skill : validSkills) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "user");
            message.put("isMeta", true);

            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", skill.progressMessage());
            content.add(textBlock);

            if (skill.content() != null && !skill.content().isEmpty()) {
                Map<String, Object> skillBlock = new LinkedHashMap<>();
                skillBlock.put("type", "text");
                skillBlock.put("text", skill.content());
                content.add(skillBlock);
            }

            message.put("content", content);
            initialMessages.add(message);

            if (log.isDebugEnabled()) {
                log.debug("[SkillPreloader] Skill '{}' initialMessage 构建完成 (contentLen={})",
                        skill.name(),
                        skill.content() != null ? skill.content().length() : 0);
            }
        }

        log.info("[SkillPreloader] preload 完成: 成功 {} 个, 缺失 {} 个 (共享 registry skillsRoot={})",
                validSkills.size(), missingSkills.size(), registry.getSkillsRoot());

        return new PreloadResult(initialMessages, missingSkills);
    }

    /**
     * [P5-③] 派生技能内容（getPromptForCommand 全链等价）· 对齐 CC loadSkillsDir.ts:344-396 +
     * SkillToolImpl.doExecute:1500-1558。
     *
     * <p>顺序严格对齐：withBaseDirPrefix → substituteArguments(args='') → replacePluginVariables →
     * replaceUserConfig → replaceSkillDir(cmd.getBaseDir) → replaceSessionId(sessionId) → MCP 安全闸
     * 后 PromptShellExecutor.executeShellCommandsInPrompt（ctx==null → warn 跳过注入，对齐
     * SkillToolImpl:1543-1549）。bundled skill（promptFn 非 null）走闭包 + withBaseDirPrefix。
     *
     * @param cmd       已解析命令（非 null）
     * @param sessionId 会话 short id（${CLAUDE_SESSION_ID} 替换值；null → 不替换）
     * @param ctx       ToolUseContext（shell 注入权限预检 + promptFn 会话通道；null → 跳过注入）
     * @return 派生后的技能内容（恒非 null；失败降级为 loadContent 原始内容）
     */
    private String deriveContent(Command cmd, String sessionId, ToolUseContext ctx) {
        String content;
        if (cmd.getPromptFn() != null) {
            // bundled skill（P2-8 内容源双路径）· 对齐 SkillToolImpl.doExecute:1467-1492
            String cwd = (ctx != null && ctx.effectiveCwd() != null)
                ? ctx.effectiveCwd().toString() : null;
            List<ChatMessageDto> messages = (ctx == null || ctx.messages() == null) ? List.of()
                : ctx.messages().stream()
                    .filter(ChatMessageDto.class::isInstance)
                    .map(ChatMessageDto.class::cast)
                    .toList();
            List<ContentBlockParam> blocks = cmd.getPromptFn().apply("",
                new PromptFnContext(cwd, messages, sessionId));
            content = blocks.stream()
                .filter(b -> b instanceof ContentBlockParam.TextBlockParam)
                .map(b -> ((ContentBlockParam.TextBlockParam) b).text())
                .collect(Collectors.joining("\n\n"));
            // [Fix-P5-反思#1] bundled-with-files 参考文件前缀 · 镜像 SkillToolImpl:1503
            //   （withBaseDirPrefix 对 bundled+disk 统一应用）+ CC bundledSkills.ts:66-72 prependBaseDir
            //   （files 存在才加前缀）。BundledSkillsBootstrapper:520 注册期 eager setBaseDir →
            //   带参考文件的 bundled skill 预加载内容与 SkillTool 实际执行内容一致（含
            //   『Base directory for this skill: ...』前缀）；baseDir=null → withBaseDirPrefix no-op 安全。
            content = contentLoader.withBaseDirPrefix(cmd, content);
        } else {
            content = contentLoader.loadContent(cmd);
            // P0-4: base directory 前缀注入 · 对齐 CC loadSkillsDir.ts:345-347 + prependBaseDir
            content = contentLoader.withBaseDirPrefix(cmd, content);
            // P0-4: 参数替换（args=''，appendIfNoPlaceholder=true）· 对齐 CC loadSkillsDir.ts:349-354
            content = ArgumentSubstitution.substituteArguments(
                content, "", true,
                cmd.getArgNames() != null ? cmd.getArgNames() : java.util.List.of());
            // P1-4: plugin 命令内容链补 ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} + ${user_config.X}
            //   · 对齐 CC loadPluginCommands.ts:340-354（SkillToolImpl.doExecute:1522-1523 同款）。
            content = contentLoader.replacePluginVariables(content, cmd.getPluginRoot(), cmd.getPluginSource());
            content = contentLoader.replaceUserConfig(content, cmd.getUserConfig(), cmd.getSensitiveKeys());
            // P0-4: ${CLAUDE_SKILL_DIR} → 技能实际目录（win32 反斜杠→正斜杠，SkillContentLoader 已实现）
            content = contentLoader.replaceSkillDir(content, cmd.getBaseDir());
            // P0-4: ${CLAUDE_SESSION_ID} → 会话 short id
            content = contentLoader.replaceSessionId(content, sessionId);
            // ── [P0-5] MCP 安全闸 + shell 注入（对齐 CC loadSkillsDir.ts:371-396）──
            if (cmd.getLoadedFrom() != CommandLoadedFrom.MCP) {
                if (ctx == null) {
                    log.warn("[SkillPreloader] 技能 '{}' 内嵌 shell 命令跳过: ctx=null 无法权限预检 "
                            + "(CC promptShellExecution.ts:98；SkillToolImpl:1543-1549 同款)",
                        cmd.getName());
                } else {
                    try {
                        content = promptShellExecutor.executeShellCommandsInPrompt(
                            content, ctx, "/" + cmd.getName(), cmd.getShell(), cmd.getAllowedTools());
                        if (log.isDebugEnabled()) {
                            log.debug("[SkillPreloader] 技能 '{}' shell 注入完成: 长度={} "
                                    + "(CC loadSkillsDir.ts:374-396)", cmd.getName(), content.length());
                        }
                    } catch (Exception e) {
                        // fail-soft 守卫：fork 子代理权限上下文传递不完整时 PromptShellExecutor
                        //   fail-closed（PermissionPipeline 空 → 拒绝）→ log.warn 不阻断，保留未注入内容
                        //   （对齐 SubagentExecutor Step 14 catch+warn 容错；CC Promise.all reject 会拖垮
                        //   runAgent，Java 侧 deliberately 放宽为 warn）。
                        log.warn("[SkillPreloader] 技能 '{}' shell 注入失败，保留未注入内容（fail-soft）: "
                                + "err={}", cmd.getName(), e.toString());
                    }
                }
            }
        }
        return content != null ? content : "";
    }

}
