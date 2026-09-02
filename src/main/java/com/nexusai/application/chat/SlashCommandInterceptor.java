package com.nexusai.application.chat;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.skill.ArgumentSubstitution;
import com.nexusai.application.agent.skill.SkillContentLoader;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.util.SlashCommandParser;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Slash 输入边界拦截 · 唯一 slash 编排入口（镜像 CC {@code processSlashCommand} 全流程，
 * processSlashCommand.tsx:309-921）。
 *
 * <p><b>职责</b>（严格对齐 CC 主流程，逐分支分派）：
 * <ul>
 *   <li><b>parse</b>：{@link SlashCommandParser#parseSlashCommand} 失败 → "Commands are in the
 *       form `/command [args]`"（CC :309-324），shouldQuery=false；不 '/' 开头 → {@code handled(false)}
 *       回落普通 prompt。</li>
 *   <li><b>findCommand</b>：{@link SkillRegistry#findCommand}（三维匹配 name/userFacingName/aliases，
 *       Java SkillRegistry.java:1204；CC findCommand commands.ts:688-698 等价）；isMcp 走
 *       {@link SkillRegistry#findCommandIncludingMcp}（CC SkillTool.getAllCommands 搜索基座）。</li>
 *   <li><b>未知命令</b>（!hasCommand）：{@code looksLikeCommand}（仅 {@code [a-zA-Z0-9:_-]}，CC :304-308）
 *       且非文件路径（粗判：/var /tmp /private 前缀，CC :455）→ "Unknown skill: X"（CC :343-360），
 *       shouldQuery=false；否则（文件路径疑似）→ 回落普通 prompt（CC :362-380）。</li>
 *   <li><b>userInvocable === false</b> → 固定拒绝文案（CC :535-548），shouldQuery=false。</li>
 *   <li><b>switch(command.type)</b>：<ul>
 *       <li>{@code prompt} + context != 'fork' → 技能内容生成（bundled promptFn 闭包 / 磁盘 SKILL.md
 *           管线），shouldQuery=true + {@code skillContent}（作为 isMeta 落库内容 + 模型上下文源，
 *           对齐 CC :869-921 metadata + isMeta 双用户消息）；context=='fork' → 本批次 TODO 占位
 *           （fork 子代理执行归后续批次）。</li>
 *       <li>{@code local} → {@link UserInputDispatcher#dispatchResult}（镜像 CC {@code mod.call(args, context)}
 *           LocalCommandResult），text → {@code <local-command-stdout>...} 消息、skip → 无消息，
 *           shouldQuery=false（CC :657-721）。</li>
 *       <li>{@code local-jsx} → web 无法渲染 JSX，返回说明消息（CC :551-656 语义降级）。</li>
 *   </ul></li>
 * </ul>
 *
 * <p><b>边界</b>：不推 STOMP（非查询型终态收口在 {@link ChatService#processUserMessage} 完成），
 * {@code streamTopic}/{@code wsTemplate} 入参预留 local 命令实时事件通道（本批次未用）。
 *
 * <p><b>本批次已知缺口</b>（登记不阻塞，见 后期待实现.md）：
 * <ul>
 *   <li>fork 型技能、allowedTools 透传、local compact 型；</li>
 *   <li>CC metadata XML 标签（{@code <command-message>...}）属 CLI 渲染产物，web 以原始
 *       {@code /command args} 气泡等价，不落模型（登记差异，Fix-P2 Issue 3 措辞修正）。</li>
 * </ul>
 */
@Component
public class SlashCommandInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandInterceptor.class);

    private final SkillRegistry skillRegistry;
    private final UserInputDispatcher userInputDispatcher;
    /** 磁盘 skill 内容管线（SkillToolImpl:143 同款先例，非 Spring bean，POJO new）。 */
    private final SkillContentLoader contentLoader = new SkillContentLoader();
    /** promptFn 会话通道（CC context.messages 投影）· best-effort：未注入（直构测试）→ 空消息列表。 */
    @Autowired(required = false)
    private MessageService messageService;

    public SlashCommandInterceptor(SkillRegistry skillRegistry, UserInputDispatcher userInputDispatcher) {
        this.skillRegistry = skillRegistry;
        this.userInputDispatcher = userInputDispatcher;
    }

    /**
     * slash 分派结果 · 镜像 CC processSlashCommand 返回的 {@code {messages, shouldQuery, model, command}}。
     *
     * @param handled           是否被 slash 逻辑接管（false → ChatService 按普通 prompt 处理）
     * @param shouldQuery       是否查模型（prompt 型 = true；local/unknown/userInvocable-false = false）
     * @param effectivePrompt   prompt 型 = 技能内容生成的 skillContent（= metaMessageContent）
     * @param resultText        非查询型显示文本（Unknown skill / userInvocable-false / local stdout / 占位）
     * @param command           命中的命令（透传 allowedTools/model 源）
     * @param metaMessageContent prompt 型 isMeta 落库内容（对齐 CC :915-918 createUserMessage isMeta:true）
     * @param resultMessageId   非查询型结果消息 id（null → ChatService 兜底生成）
     */
    public record SlashResolution(
            boolean handled,
            boolean shouldQuery,
            String effectivePrompt,
            String resultText,
            Command command,
            String metaMessageContent,
            String resultMessageId) {

        /** 未接管 · 回落普通 prompt（shouldQuery=true 语义：ChatService 主链不变）。 */
        public static SlashResolution notHandled() {
            return new SlashResolution(false, true, null, null, null, null, null);
        }
    }

    /**
     * 识别并分派 '/' 开头输入（镜像 CC processSlashCommand 主流程）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 本轮用户消息 id（= controller createUserMessage 落库的 user 气泡 id）
     * @param rawContent    用户原始输入（可能不以 '/' 开头 → 快速路径 handled(false)）
     * @param streamTopic   会话级流式 topic（预留 local 命令实时事件通道）
     * @param wsTemplate    STOMP 模板（预留；非查询型终态收口在 ChatService）
     * @return SlashResolution（handled=false → 普通 prompt 不变）
     */
    public SlashResolution intercept(String sessionId, String userMessageId, String rawContent,
                                     String streamTopic, SimpMessagingTemplate wsTemplate) {
        if (rawContent == null || !rawContent.startsWith("/")) {
            // 快速路径：非 slash 输入 → 普通 prompt 不变（零影响）
            return SlashResolution.notHandled();
        }

        // ── 1) parse ──
        SlashCommandParser.ParsedSlashCommand parsed = SlashCommandParser.parseSlashCommand(rawContent);
        if (parsed == null) {
            String errorMessage = "Commands are in the form `/command [args]`";
            log.info("[slash] 识别 slash 输入（parse 失败）: session={} → 表单错误提示"
                    + "（对齐 CC processSlashCommand.tsx:313）", sessionId);
            return new SlashResolution(true, false, null, errorMessage, null, null, genResultId());
        }
        String commandName = parsed.commandName();
        String args = parsed.args();
        if (log.isInfoEnabled()) {
            log.info("[slash] 识别 slash 输入: session={} cmd={} args={} isMcp={}",
                sessionId, commandName, args, parsed.isMcp());
        }

        // ── 2) findCommand（isMcp → 含 MCP thread-in 搜索基座；否则纯本地三维匹配）──
        Command command = parsed.isMcp()
            ? skillRegistry.findCommandIncludingMcp(commandName)
            : skillRegistry.findCommand(commandName);

        // ── 3) 未知命令（!hasCommand）──
        if (command == null) {
            // [remaining-2] SkillRegistry 未命中 → 检查 UserInputDispatcher 已注册的命名 handler
            //   （/color /chrome 等 void 命令，registerSlashCommand 注册面）——本地执行而非 Unknown skill，
            //   对齐 CC：local 定义命令经 findCommand 找到后 load().call 执行；Java 的 SkillRegistry 与
            //   UserInputDispatcher 是两套注册面，intercept 需桥接。
            if (userInputDispatcher != null && userInputDispatcher.hasSlashCommandHandler(commandName)) {
                // dispatchResult 内部：result handler 优先 → void handler 执行（副作用，结果不可得 → skip）。
                // web 无可渲染 UI，返回「已执行」提示消息（shouldQuery=false，不起 LLM turn）。
                userInputDispatcher.dispatchResult(rawContent);
                String doneMessage = "Command /" + commandName + " executed.";
                log.info("[slash] UserInputDispatcher 命名 handler 本地执行: cmd={} → '{}'（shouldQuery=false，对齐 CC local 命令）",
                    commandName, doneMessage);
                return new SlashResolution(true, false, null, doneMessage, null, null, genResultId());
            }
            // CC processSlashCommand.tsx:333-381：looksLikeCommand（仅 [a-zA-Z0-9:_-]）且非文件路径
            //   → Unknown skill；否则（文件路径疑似）→ 回落普通 prompt（shouldQuery=true）。
            if (looksLikeCommand(commandName) && !isLikelyFilePath(commandName)) {
                String unknownMessage = "Unknown skill: " + commandName;
                // [Fix-P2 · Issue 4] args 系统告警 · 对齐 CC processSlashCommand.tsx:355-357
                //   createSystemMessage(`Args from unknown skill: ${parsedArgs}`, 'warning')：
                //   CC 以 system warning 附加 args（gh-32591：preserve args 供用户复制重新提交，
                //   UI-only 过滤不进 API）。Java 无 CLI system-message 概念，web 以 resultText
                //   追加提示等价承载（UI 可见，用户可复制 args）。
                if (args != null && !args.isBlank()) {
                    unknownMessage = unknownMessage + "\nArgs from unknown skill: " + args;
                }
                log.info("[slash] 未知命令: cmd={} args={} → '{}'（shouldQuery=false，对齐 CC :343-360）",
                    commandName, args, unknownMessage);
                return new SlashResolution(true, false, null, unknownMessage, null, null, genResultId());
            }
            log.info("[slash] 输入疑似文件路径回落普通 prompt: cmd={}（对齐 CC processSlashCommand.tsx:362-380）",
                commandName);
            return SlashResolution.notHandled();
        }

        // ── 4) userInvocable === false（CC :535-548）──
        if (Boolean.FALSE.equals(command.getUserInvocable())) {
            String message = "This skill can only be invoked by Claude, not directly by users. "
                + "Ask Claude to use the \"" + commandName + "\" skill for you.";
            log.info("[slash] userInvocable=false: cmd={} → 拒绝文案（shouldQuery=false）", commandName);
            return new SlashResolution(true, false, null, message, command, null, genResultId());
        }

        // ── 5) switch(command.type)（CC :550-761）──
        String type = command.getType() == null ? "prompt" : command.getType();
        switch (type) {
            case "prompt": {
                if ("fork".equals(command.getContext())) {
                    // CC :727-729 executeForkedSlashCommand —— fork 子代理执行归后续批次（登记不阻塞）
                    log.info("[slash] fork 型技能本批次 TODO: cmd={} → 占位消息（fork 子代理执行归后续批次）",
                        commandName);
                    return new SlashResolution(true, false, null,
                        "技能 '" + commandName + "' 以 fork 模式运行（子代理执行），"
                            + "web 端 fork 支持待后续批次实现。", command, null, genResultId());
                }
                String skillContent = buildSkillContent(command, args, sessionId);
                if (skillContent == null) {
                    log.warn("[slash] prompt 型技能内容为空: cmd={} → 'has no content'（fail loud）", commandName);
                    return new SlashResolution(true, false, null,
                        "Skill '" + commandName + "' has no content", command, null, genResultId());
                }
                log.info("[slash] prompt 型命令: cmd={} skillContent={}chars shouldQuery=true"
                        + "（对齐 CC processSlashCommand.tsx:869-921 getMessagesForPromptSlashCommand）",
                    commandName, skillContent.length());
                return new SlashResolution(true, true, skillContent, null, command, skillContent, null);
            }
            case "local": {
                UserInputDispatcher.LocalCommandResult lr = userInputDispatcher.dispatchResult(rawContent);
                if (lr == null) {
                    log.warn("[slash] local 命令无已注册执行 handler: cmd={}（fail loud，对齐 CC "
                        + "mod.call 缺失语义降级）", commandName);
                    return new SlashResolution(true, false, null,
                        "Local command '" + commandName + "' has no registered handler.", command, null,
                        genResultId());
                }
                if ("skip".equals(lr.kind())) {
                    log.info("[slash] local 命令 skip 结果: cmd={} → 无消息（shouldQuery=false）", commandName);
                    return new SlashResolution(true, false, null, null, command, null, genResultId());
                }
                String stdout = "<local-command-stdout>"
                    + (lr.value() == null ? "" : lr.value()) + "</local-command-stdout>";
                log.info("[slash] local 命令 text 结果: cmd={} → <local-command-stdout>（shouldQuery=false）",
                    commandName);
                return new SlashResolution(true, false, null, stdout, command, null, genResultId());
            }
            case "local-jsx": {
                log.info("[slash] local-jsx 命令 web 降级: cmd={} → 说明消息（web 无法渲染 JSX，"
                    + "对齐 CC :593 语义降级）", commandName);
                return new SlashResolution(true, false, null,
                    "Command '" + commandName + "' is a UI command (JSX) not renderable in web.",
                    command, null, genResultId());
            }
            default:
                log.warn("[slash] 未知命令类型: cmd={} type={} → 回落普通 prompt（fail loud）", commandName, type);
                return SlashResolution.notHandled();
        }
    }

    /**
     * 技能内容生成 · 严格对齐 CC getPromptForCommand 内容管线（processSlashCommand.tsx:869/:884）。
     *
     * <ul>
     *   <li><b>bundled（promptFn 非 null）</b>：{@code promptFn.apply(args, PromptFnContext.of(cwd,
     *       messages, sessionId))} → text 块 {@code join('\n\n')}（CC :884 skillContent 语义；
     *       复用 SkillToolImpl:1480-1492 既有闭包链）。</li>
     *   <li><b>磁盘（promptFn == null）</b>：{@code withBaseDirPrefix} →
     *       {@code substituteArguments(args, appendIfNoPlaceholder=true, argNames)} →
     *       {@code replacePluginVariables} → {@code replaceUserConfig} → {@code replaceSkillDir} →
     *       {@code replaceSessionId}（顺序严格对齐 CC loadSkillsDir.ts:344-396）。shell 注入
     *       （executeShellCommandsInPrompt）跳过 —— 本入口无 ToolUseContext 无法权限预检，语义对齐
     *       SkillToolImpl:1544-1548 ctx==null 跳过分支。</li>
     * </ul>
     *
     * @return 渲染后技能内容；内容为空（磁盘技能无 SKILL.md/DB content）→ null
     */
    private String buildSkillContent(Command cmd, String args, String sessionId) {
        String content;
        if (cmd.getPromptFn() != null) {
            String cwd = CwdResolution.getCwd(sessionId);
            List<ChatMessageDto> messages = (messageService == null)
                ? List.of()
                : safeListBySession(sessionId);
            List<ContentBlockParam> promptFnBlocks = cmd.getPromptFn().apply(args,
                PromptFnContext.of(cwd, messages, sessionId));
            content = promptFnBlocks.stream()
                .filter(b -> b instanceof ContentBlockParam.TextBlockParam)
                .map(b -> ((ContentBlockParam.TextBlockParam) b).text())
                .collect(Collectors.joining("\n\n"));
            if (log.isDebugEnabled()) {
                log.debug("[slash] 技能 '{}' 内容来自 getPromptForCommand 闭包: {} chars, messages={}"
                    + "（CC processSlashCommand.tsx:884）", cmd.getName(), content.length(), messages.size());
            }
        } else {
            content = contentLoader.loadContent(cmd);
            if (content.isEmpty()) {
                return null;
            }
            content = contentLoader.withBaseDirPrefix(cmd, content);
            content = ArgumentSubstitution.substituteArguments(content, args, true,
                cmd.getArgNames() != null ? cmd.getArgNames() : List.of());
            content = contentLoader.replacePluginVariables(content, cmd.getPluginRoot(), cmd.getPluginSource());
            content = contentLoader.replaceUserConfig(content, cmd.getUserConfig(), cmd.getSensitiveKeys());
            content = contentLoader.replaceSkillDir(content, cmd.getBaseDir());
            content = contentLoader.replaceSessionId(content, sessionId);
            if (log.isDebugEnabled()) {
                log.debug("[slash] 技能 '{}' 磁盘管线渲染完成: args='{}' 长度={}（CC loadSkillsDir.ts:344-396）",
                    cmd.getName(), args, content.length());
            }
        }
        return content;
    }

    /** best-effort 读取会话消息（promptFn 会话通道）· 失败 → 空列表不阻断。 */
    private List<ChatMessageDto> safeListBySession(String sessionId) {
        try {
            return messageService.listBySession(sessionId);
        } catch (Exception e) {
            log.warn("[slash] 读取会话消息失败（best-effort → 空列表）: session={} err={}", sessionId,
                e.getMessage());
            return List.of();
        }
    }

    /** CC looksLikeCommand（processSlashCommand.tsx:304-308）：命令名仅含 [a-zA-Z0-9:_-]。 */
    private static boolean looksLikeCommand(String commandName) {
        return commandName != null && commandName.matches("[a-zA-Z0-9:_-]+");
    }

    /**
     * 文件路径疑似判定 · 对齐 CC processSlashCommand.tsx:337-342 stat('/' + commandName) 实探。
     *
     * <p><b>[Fix-P1 LOW]</b>：CC 是真实 stat 探测（{@code getFsImplementation().stat('/'+name)}），
     * Java 旧实现仅 /var /tmp /private 前缀粗判 → /usr /etc /bin 等误报 'Unknown skill: usr'（CC 回落
     * 普通 prompt）。本实现两层：① 真实文件系统存在性探测（{@code Files.exists(Path.of("/"+name))}，
     * 对齐 CC stat）；② Windows 无 /usr /etc /bin 实体目录，以常见系统路径前缀补齐（对齐 CC 在
     * Linux/macOS 下 stat 命中这些目录的语义），避免 web 部署在 Windows 时误报 Unknown skill。
     */
    private static boolean isLikelyFilePath(String commandName) {
        if (commandName == null || commandName.isBlank()) {
            return false;
        }
        try {
            if (Files.exists(Path.of("/" + commandName))) {
                return true;
            }
        } catch (Exception e) {
            // 非法路径（含非法字符）→ 回退前缀粗判（fail-safe，不抛）
        }
        return commandName.equals("var") || commandName.startsWith("var/")
            || commandName.equals("tmp") || commandName.startsWith("tmp/")
            || commandName.equals("private") || commandName.startsWith("private/")
            || commandName.equals("usr") || commandName.startsWith("usr/")
            || commandName.equals("etc") || commandName.startsWith("etc/")
            || commandName.equals("bin") || commandName.startsWith("bin/")
            || commandName.equals("home") || commandName.startsWith("home/")
            || commandName.equals("opt") || commandName.startsWith("opt/");
    }

    private static String genResultId() {
        return "msg-slash-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
